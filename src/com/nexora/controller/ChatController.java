package com.nexora.controller;

import com.nexora.model.PerformanceMetric;
import com.nexora.model.UserMemory;
import com.nexora.server.AppConfig;
import com.nexora.server.RateLimiter;
import com.nexora.service.AiService;
import com.nexora.service.AiServiceFactory;
import com.nexora.service.MemoryService;
import com.nexora.service.ModelManagerService;
import com.nexora.util.LanguageDetector;
import com.nexora.util.LanguageDetector.ResponseLanguage;
import com.nexora.util.SimpleJson;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

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
        String requestedProvider = SimpleJson.getString(requestData, "provider", null);
        String persona = SimpleJson.getString(requestData, "persona", "general");
        boolean isStream = SimpleJson.getBoolean(requestData, "stream", false);

        // Optional BYOK API Key for OpenRouter
        String clientApiKey = exchange.getRequestHeaders().getFirst("X-OpenRouter-Key");
        if (clientApiKey == null || clientApiKey.trim().isEmpty()) {
            clientApiKey = SimpleJson.getString(requestData, "apiKey", null);
        }

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

        // Ensure latest message is present in messagesList
        String singleMessage = SimpleJson.getString(requestData, "message", "").trim();
        if (!singleMessage.isEmpty()) {
            boolean alreadyPresent = false;
            if (!messagesList.isEmpty()) {
                Map<String, String> last = messagesList.get(messagesList.size() - 1);
                if ("user".equalsIgnoreCase(last.get("role")) && singleMessage.equals(last.get("content"))) {
                    alreadyPresent = true;
                }
            }
            if (!alreadyPresent) {
                Map<String, String> userMsg = new LinkedHashMap<>();
                userMsg.put("role", "user");
                userMsg.put("content", singleMessage);
                messagesList.add(userMsg);
            }
        }

        if (messagesList.isEmpty()) {
            sendError(exchange, 400, "Message cannot be empty. Please provide 'message' or 'messages'.");
            return;
        }

        // Find last user prompt
        String lastUserMessage = "";
        for (int i = messagesList.size() - 1; i >= 0; i--) {
            Map<String, String> m = messagesList.get(i);
            if ("user".equalsIgnoreCase(m.get("role"))) {
                lastUserMessage = m.getOrDefault("content", "").trim();
                break;
            }
        }

        // Memory Retrieval and Auto-Extraction
        MemoryService memoryService = MemoryService.getInstance();
        List<String> relevantMemories = new ArrayList<>();
        UserMemory extractedMemory = null;

        // 1. Check if client provided memories directly (stateless client-side privacy isolation)
        List<Object> clientMemsRaw = SimpleJson.getList(requestData, "memories");
        for (Object item : clientMemsRaw) {
            if (item != null) {
                String mStr = item.toString().trim();
                if (!mStr.isEmpty() && mStr.length() <= 500 && !memoryService.containsSecrets(mStr)) {
                    relevantMemories.add(mStr);
                }
            }
        }

        if (AppConfig.isLocalMode()) {
            // In LOCAL mode, resolve userId for local disk storage
            String rawUserId = exchange.getRequestHeaders().getFirst("X-User-Id");
            if (rawUserId == null || rawUserId.trim().isEmpty()) {
                rawUserId = SimpleJson.getString(requestData, "userId", "default");
            }
            String userId = memoryService.sanitizeUserId(rawUserId);

            if (relevantMemories.isEmpty()) {
                relevantMemories = memoryService.getRelevantMemoryTexts(userId, lastUserMessage);
            }

            if (memoryService.isAutoMemoryEnabled(userId) && !lastUserMessage.isEmpty()) {
                extractedMemory = memoryService.autoExtractAndSave(userId, lastUserMessage);
            }
        } else {
            // In PUBLIC mode, never write to server disk (data/memories/)
            // Perform in-memory candidate extraction to return to client
            boolean autoEnabled = SimpleJson.getBoolean(requestData, "autoMemoryEnabled", true);
            if (autoEnabled && !lastUserMessage.isEmpty()) {
                extractedMemory = memoryService.extractCandidateMemory(lastUserMessage);
            }
        }

        // Automatic Language Detection strictly from the latest user message
        ResponseLanguage responseLanguage = LanguageDetector.detectLanguage(lastUserMessage);

        AiService aiService = AiServiceFactory.getServiceForProvider(requestedProvider);
        String effectiveModel = (requestedModel != null && !requestedModel.trim().isEmpty())
                ? requestedModel.trim()
                : aiService.getDefaultModel();

        if (isStream) {
            handleStreamingChat(exchange, aiService, effectiveModel, messagesList, persona, relevantMemories, responseLanguage, extractedMemory, clientApiKey);
        } else {
            handleBufferedChat(exchange, aiService, effectiveModel, messagesList, persona, relevantMemories, responseLanguage, extractedMemory, clientApiKey);
        }
    }

    private void handleBufferedChat(HttpExchange exchange, AiService aiService, 
                                    String effectiveModel, List<Map<String, String>> messages, String persona,
                                    List<String> memories, ResponseLanguage responseLanguage, UserMemory extractedMemory,
                                    String clientApiKey) throws IOException {
        long startNano = System.nanoTime();
        try {
            String reply;
            if (aiService instanceof com.nexora.service.OpenRouterService ors && clientApiKey != null && !clientApiKey.trim().isEmpty()) {
                reply = ors.generateCompletionWithKey(effectiveModel, messages, persona, memories, responseLanguage, clientApiKey.trim());
            } else {
                reply = aiService.generateCompletion(effectiveModel, messages, persona, memories, responseLanguage);
            }
            long durationMs = (System.nanoTime() - startNano) / 1_000_000;

            int approxTokens = Math.max(1, reply.split("\\s+").length);
            double tokensPerSec = durationMs > 0 ? ((double) approxTokens / (durationMs / 1000.0)) : 0.0;

            // Record performance metric
            ModelManagerService.getInstance().recordMetric(new PerformanceMetric(
                    effectiveModel,
                    aiService.getProviderName(),
                    durationMs,
                    0,
                    approxTokens,
                    Math.round(tokensPerSec * 10.0) / 10.0,
                    true,
                    "",
                    System.currentTimeMillis()
            ));

            Map<String, Object> responseData = new LinkedHashMap<>();
            responseData.put("success", true);
            responseData.put("reply", reply);
            responseData.put("model", effectiveModel);
            responseData.put("persona", persona);
            responseData.put("language", responseLanguage.getCode());
            responseData.put("provider", aiService.getProviderName());
            responseData.put("memoriesUsed", memories);
            responseData.put("memorySaved", extractedMemory != null);
            if (extractedMemory != null) {
                responseData.put("extractedMemory", extractedMemory.toMap());
            }
            responseData.put("durationMs", durationMs);

            // Legacy backward-compatibility wrapper
            Map<String, Object> msgMap = new LinkedHashMap<>();
            msgMap.put("role", "assistant");
            msgMap.put("content", reply);
            responseData.put("message", msgMap);

            sendJsonResponse(exchange, 200, responseData);

        } catch (IllegalStateException e) {
            long durationMs = (System.nanoTime() - startNano) / 1_000_000;
            ModelManagerService.getInstance().recordMetric(new PerformanceMetric(
                    effectiveModel, aiService.getProviderName(), durationMs, 0, 0, 0.0, false, e.getMessage(), System.currentTimeMillis()));
            sendError(exchange, 503, e.getMessage());
        } catch (IOException e) {
            long durationMs = (System.nanoTime() - startNano) / 1_000_000;
            ModelManagerService.getInstance().recordMetric(new PerformanceMetric(
                    effectiveModel, aiService.getProviderName(), durationMs, 0, 0, 0.0, false, e.getMessage(), System.currentTimeMillis()));
            sendError(exchange, 502, e.getMessage());
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - startNano) / 1_000_000;
            ModelManagerService.getInstance().recordMetric(new PerformanceMetric(
                    effectiveModel, aiService.getProviderName(), durationMs, 0, 0, 0.0, false, e.getMessage(), System.currentTimeMillis()));
            sendError(exchange, 500, "Internal Server Error: " + e.getMessage());
        }
    }

    private void handleStreamingChat(HttpExchange exchange, AiService aiService, 
                                     String effectiveModel, List<Map<String, String>> messages, String persona,
                                     List<String> memories, ResponseLanguage responseLanguage, UserMemory extractedMemory,
                                     String clientApiKey) throws IOException {
        handleCors(exchange);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");

        // Status code 200 with response length 0 indicates chunked transfer encoding in Java HttpServer
        exchange.sendResponseHeaders(200, 0);

        OutputStream os = exchange.getResponseBody();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean hasError = new AtomicBoolean(false);

        long startNano = System.nanoTime();
        AtomicLong firstTokenNano = new AtomicLong(0);
        AtomicInteger tokenCount = new AtomicInteger(0);

        Consumer<String> chunkConsumer = chunk -> {
            try {
                firstTokenNano.compareAndSet(0, System.nanoTime());
                if (chunk != null && !chunk.isEmpty()) {
                    tokenCount.addAndGet(Math.max(1, chunk.trim().split("\\s+").length));
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("chunk", chunk);
                String sseMessage = "data: " + SimpleJson.toJson(data) + "\n\n";
                os.write(sseMessage.getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (IOException e) {
                hasError.set(true);
                latch.countDown();
            }
        };

        Runnable onComplete = () -> {
            latch.countDown();
        };

        Consumer<Throwable> errorConsumer = error -> {
            try {
                long durationMs = (System.nanoTime() - startNano) / 1_000_000;
                ModelManagerService.getInstance().recordMetric(new PerformanceMetric(
                        effectiveModel, aiService.getProviderName(), durationMs, 0, 0, 0.0, false, error.getMessage(), System.currentTimeMillis()));

                Map<String, Object> errData = new LinkedHashMap<>();
                errData.put("error", error.getMessage());
                String sseError = "data: " + SimpleJson.toJson(errData) + "\n\n";
                os.write(sseError.getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (IOException ignored) {
            }
            hasError.set(true);
            latch.countDown();
        };

        try {
            if (aiService instanceof com.nexora.service.OpenRouterService ors && clientApiKey != null && !clientApiKey.trim().isEmpty()) {
                ors.streamCompletionWithKey(
                        effectiveModel,
                        messages,
                        persona,
                        memories,
                        responseLanguage,
                        clientApiKey.trim(),
                        chunkConsumer,
                        onComplete,
                        errorConsumer
                );
            } else {
                aiService.streamCompletion(
                        effectiveModel,
                        messages,
                        persona,
                        memories,
                        responseLanguage,
                        chunkConsumer,
                        onComplete,
                        errorConsumer
                );
            }

            // Wait for completion or timeout
            boolean finished = latch.await(AppConfig.getRequestTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished && !hasError.get()) {
                String timeoutMsg = "data: {\"error\":\"AI generation timed out after " + AppConfig.getRequestTimeoutSeconds() + " seconds. Please try again with a lighter prompt or smaller model.\"}\n\n";
                os.write(timeoutMsg.getBytes(StandardCharsets.UTF_8));
                os.flush();
                hasError.set(true);
            }

            if (!hasError.get() && tokenCount.get() == 0) {
                String emptyMsg = "data: {\"error\":\"The AI model returned zero tokens. Please check that the selected model is running properly in Ollama.\"}\n\n";
                os.write(emptyMsg.getBytes(StandardCharsets.UTF_8));
                os.flush();
                hasError.set(true);
            }

            long durationMs = (System.nanoTime() - startNano) / 1_000_000;
            long ttftMs = (firstTokenNano.get() > 0) ? (firstTokenNano.get() - startNano) / 1_000_000 : 0;
            double tokensPerSec = durationMs > 0 ? ((double) tokenCount.get() / (durationMs / 1000.0)) : 0.0;

            if (!hasError.get()) {
                ModelManagerService.getInstance().recordMetric(new PerformanceMetric(
                        effectiveModel,
                        aiService.getProviderName(),
                        durationMs,
                        ttftMs,
                        tokenCount.get(),
                        Math.round(tokensPerSec * 10.0) / 10.0,
                        true,
                        "",
                        System.currentTimeMillis()
                ));
            }

            // Send stream metadata (memories used, language, model, performance metrics)
            Map<String, Object> metaData = new LinkedHashMap<>();
            metaData.put("memorySaved", extractedMemory != null);
            if (extractedMemory != null) {
                metaData.put("extractedMemory", extractedMemory.toMap());
            }
            metaData.put("memoriesUsed", memories);
            metaData.put("language", responseLanguage.getCode());
            metaData.put("model", effectiveModel);
            metaData.put("provider", aiService.getProviderName());
            metaData.put("durationMs", durationMs);
            metaData.put("ttftMs", ttftMs);
            metaData.put("tokenCount", tokenCount.get());
            metaData.put("tokensPerSec", Math.round(tokensPerSec * 10.0) / 10.0);

            String metaMsg = "data: " + SimpleJson.toJson(metaData) + "\n\n";
            os.write(metaMsg.getBytes(StandardCharsets.UTF_8));
            os.flush();

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
