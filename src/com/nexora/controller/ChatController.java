package com.nexora.controller;

import com.nexora.server.AppConfig;
import com.nexora.server.RateLimiter;
import com.nexora.service.AiService;
import com.nexora.service.AiServiceFactory;
import com.nexora.util.SimpleJson;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ChatController
 * 
 * Handles POST /api/chat requests:
 * - Rate limiting verification
 * - Request validation (payload size, required message)
 * - Persona and context handling
 * - Buffered and streaming (SSE) response delivery
 * 
 * Part of NEXORA - AI-Powered Personal Chatbot
 */
public class ChatController extends BaseController {

    private final RateLimiter rateLimiter;

    public ChatController(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (handlePreflight(exchange)) {
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Only POST requests are permitted on /api/chat.");
            return;
        }

        String clientIp = getClientIp(exchange);
        if (!rateLimiter.isAllowed(clientIp)) {
            sendError(exchange, 429, "Rate limit exceeded (" + AppConfig.getRateLimitPerMinute() 
                    + " requests/minute). Please wait a moment before sending another message.");
            return;
        }

        String requestBody;
        try {
            requestBody = readRequestBody(exchange);
        } catch (IllegalArgumentException e) {
            sendError(exchange, 413, e.getMessage());
            return;
        }

        if (requestBody == null || requestBody.trim().isEmpty()) {
            sendError(exchange, 400, "Request body cannot be empty.");
            return;
        }

        Map<String, Object> requestData;
        try {
            requestData = SimpleJson.parseObject(requestBody);
        } catch (Exception e) {
            sendError(exchange, 400, "Malformed JSON payload: " + e.getMessage());
            return;
        }

        String requestedModel = SimpleJson.getString(requestData, "model", null);
        String persona = SimpleJson.getString(requestData, "persona", "general");
        boolean isStream = SimpleJson.getBoolean(requestData, "stream", false);

        // Extract conversation messages
        List<Map<String, String>> messagesList = new ArrayList<>();
        List<Object> rawMessages = SimpleJson.getList(requestData, "messages");

        if (!rawMessages.isEmpty()) {
            for (Object item : rawMessages) {
                if (item instanceof Map<?, ?> m) {
                    Object roleVal = m.get("role");
                    Object contentVal = m.get("content");
                    if (roleVal != null && contentVal != null) {
                        String contentStr = contentVal.toString().trim();
                        if (!contentStr.isEmpty()) {
                            Map<String, String> msg = new LinkedHashMap<>();
                            msg.put("role", roleVal.toString());
                            msg.put("content", contentStr);
                            messagesList.add(msg);
                        }
                    }
                }
            }
        }

        // Single message fallback if messages array was not provided
        if (messagesList.isEmpty()) {
            String singleMessage = SimpleJson.getString(requestData, "message", "").trim();
            if (singleMessage.isEmpty()) {
                sendError(exchange, 400, "Message cannot be empty. Please provide 'message' or 'messages'.");
                return;
            }
            Map<String, String> userMsg = new LinkedHashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", singleMessage);
            messagesList.add(userMsg);
        }

        AiService aiService = AiServiceFactory.getService();

        if (isStream) {
            handleStreamingChat(exchange, aiService, requestedModel, messagesList, persona);
        } else {
            handleBufferedChat(exchange, aiService, requestedModel, messagesList, persona);
        }
    }

    private void handleBufferedChat(HttpExchange exchange, AiService aiService, 
                                    String requestedModel, List<Map<String, String>> messages, String persona) throws IOException {
        try {
            String reply = aiService.generateCompletion(requestedModel, messages, persona);

            Map<String, Object> responseData = new LinkedHashMap<>();
            responseData.put("success", true);
            responseData.put("reply", reply);
            responseData.put("model", requestedModel != null ? requestedModel : aiService.getDefaultModel());
            responseData.put("persona", persona);
            responseData.put("provider", aiService.getProviderName());

            // Legacy backward-compatibility wrapper
            Map<String, Object> msgMap = new LinkedHashMap<>();
            msgMap.put("role", "assistant");
            msgMap.put("content", reply);
            responseData.put("message", msgMap);

            sendJsonResponse(exchange, 200, responseData);

        } catch (IllegalStateException e) {
            // Configuration missing (e.g. OpenRouter key missing in public mode)
            sendError(exchange, 503, e.getMessage());
        } catch (IOException e) {
            // Upstream provider or connection error
            sendError(exchange, 502, e.getMessage());
        } catch (Exception e) {
            sendError(exchange, 500, "Internal Server Error: " + e.getMessage());
        }
    }

    private void handleStreamingChat(HttpExchange exchange, AiService aiService, 
                                     String requestedModel, List<Map<String, String>> messages, String persona) throws IOException {
        handleCors(exchange);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");

        // Status code 200 with response length 0 indicates chunked transfer encoding in Java HttpServer
        exchange.sendResponseHeaders(200, 0);

        OutputStream os = exchange.getResponseBody();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean hasError = new AtomicBoolean(false);

        try {
            aiService.streamCompletion(
                    requestedModel,
                    messages,
                    persona,
                    chunk -> {
                        try {
                            Map<String, Object> data = new LinkedHashMap<>();
                            data.put("chunk", chunk);
                            String sseMessage = "data: " + SimpleJson.toJson(data) + "\n\n";
                            os.write(sseMessage.getBytes(StandardCharsets.UTF_8));
                            os.flush();
                        } catch (IOException e) {
                            hasError.set(true);
                            latch.countDown();
                        }
                    },
                    () -> {
                        latch.countDown();
                    },
                    error -> {
                        try {
                            Map<String, Object> errData = new LinkedHashMap<>();
                            errData.put("error", error.getMessage());
                            String sseError = "data: " + SimpleJson.toJson(errData) + "\n\n";
                            os.write(sseError.getBytes(StandardCharsets.UTF_8));
                            os.flush();
                        } catch (IOException ignored) {
                        }
                        hasError.set(true);
                        latch.countDown();
                    }
            );

            // Wait for completion or timeout
            boolean finished = latch.await(AppConfig.getRequestTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished && !hasError.get()) {
                String timeoutMsg = "data: {\"error\":\"Stream timed out\"}\n\n";
                os.write(timeoutMsg.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            // Signal stream termination
            os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();

        } catch (Exception e) {
            try {
                String errorMsg = "data: {\"error\":\"" + e.getMessage() + "\"}\n\n";
                os.write(errorMsg.getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (IOException ignored) {
            }
        } finally {
            try {
                os.close();
            } catch (IOException ignored) {
            }
        }
    }
}
