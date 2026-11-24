package com.nexora.service;

import com.nexora.server.AppConfig;
import com.nexora.util.PersonaPrompts;
import com.nexora.util.SimpleJson;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/**
 * OllamaService
 * 
 * Handles communication with the local Ollama instance using Java 21 HttpClient.
 * Used when running in LOCAL mode (default on user's machine).
 * 
 * Part of NEXORA - AI-Powered Personal Chatbot
 */
public class OllamaService implements AiService {

    private final HttpClient httpClient;

    public OllamaService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .proxy(HttpClient.Builder.NO_PROXY)
                .build();
    }

    @Override
    public String generateCompletion(String model, List<Map<String, String>> messages, String persona) throws Exception {
        String effectiveModel = (model != null && !model.trim().isEmpty()) ? model.trim() : AppConfig.getOllamaDefaultModel();
        List<Map<String, String>> preparedMessages = prepareMessagesWithPersona(messages, persona);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", effectiveModel);
        payload.put("messages", preparedMessages);
        payload.put("stream", false);

        String jsonBody = SimpleJson.toJson(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(AppConfig.getOllamaBaseUrl() + "/api/chat"))
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(AppConfig.getRequestTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            String responseBody = response.body();

            if (statusCode != 200) {
                Map<String, Object> errorMap = tryParseJson(responseBody);
                String errMsg = SimpleJson.getString(errorMap, "error", "Ollama returned HTTP status " + statusCode);
                throw new IOException("Ollama error: " + errMsg);
            }

            Map<String, Object> responseData = SimpleJson.parseObject(responseBody);
            Map<String, Object> messageObj = SimpleJson.getMap(responseData, "message");
            String content = SimpleJson.getString(messageObj, "content", "").trim();

            if (content.isEmpty()) {
                throw new IOException("Ollama returned an empty response. Please try again.");
            }

            return content;

        } catch (ConnectException e) {
            throw new IOException("Cannot connect to local Ollama server at " + AppConfig.getOllamaBaseUrl() 
                    + ". Please ensure the Ollama application is running on your machine.");
        } catch (HttpTimeoutException e) {
            throw new IOException("The local AI model timed out after " + AppConfig.getRequestTimeoutSeconds() 
                    + " seconds. Try asking a shorter prompt or using a lighter model (e.g. qwen2.5:3b).");
        }
    }

    @Override
    public void streamCompletion(String model, List<Map<String, String>> messages, String persona, 
                                 Consumer<String> chunkConsumer, Runnable onComplete, Consumer<Throwable> errorConsumer) throws Exception {
        String effectiveModel = (model != null && !model.trim().isEmpty()) ? model.trim() : AppConfig.getOllamaDefaultModel();
        List<Map<String, String>> preparedMessages = prepareMessagesWithPersona(messages, persona);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", effectiveModel);
        payload.put("messages", preparedMessages);
        payload.put("stream", true);

        String jsonBody = SimpleJson.toJson(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(AppConfig.getOllamaBaseUrl() + "/api/chat"))
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(AppConfig.getRequestTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        try {
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                    .thenAccept(response -> {
                        if (response.statusCode() != 200) {
                            errorConsumer.accept(new IOException("Ollama streaming error: HTTP status " + response.statusCode()));
                            return;
                        }
                        response.body().forEach(line -> {
                            if (line != null && !line.trim().isEmpty()) {
                                try {
                                    Map<String, Object> chunkObj = SimpleJson.parseObject(line.trim());
                                    Map<String, Object> msg = SimpleJson.getMap(chunkObj, "message");
                                    String text = SimpleJson.getString(msg, "content", "");
                                    if (!text.isEmpty()) {
                                        chunkConsumer.accept(text);
                                    }
                                } catch (Exception parseEx) {
                                    // Ignore parse errors on partial chunks
                                }
                            }
                        });
                        if (onComplete != null) {
                            onComplete.run();
                        }
                    })
                    .exceptionally(ex -> {
                        errorConsumer.accept(ex);
                        return null;
                    });
        } catch (Exception e) {
            errorConsumer.accept(e);
            throw e;
        }
    }

    @Override
    public List<String> getAvailableModels() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(AppConfig.getOllamaBaseUrl() + "/api/tags"))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(6))
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                Map<String, Object> data = SimpleJson.parseObject(response.body());
                List<Object> modelList = SimpleJson.getList(data, "models");
                List<String> names = new ArrayList<>();
                for (Object item : modelList) {
                    if (item instanceof Map<?, ?> m) {
                        Object nameVal = m.get("name");
                        if (nameVal != null) {
                            names.add(nameVal.toString());
                        }
                    }
                }
                if (!names.isEmpty()) {
                    return names;
                }
            }
        } catch (Exception ignored) {
            // Fallback to default list if Ollama is starting up or temporarily offline
        }

        return List.of(AppConfig.getOllamaDefaultModel(), "qwen2.5:3b");
    }

    @Override
    public String getProviderName() {
        return "Ollama (Local)";
    }

    @Override
    public String getDefaultModel() {
        return AppConfig.getOllamaDefaultModel();
    }

    private List<Map<String, String>> prepareMessagesWithPersona(List<Map<String, String>> messages, String persona) {
        List<Map<String, String>> result = new ArrayList<>();
        String systemPrompt = PersonaPrompts.getPrompt(persona);

        // Prepend system persona directive
        Map<String, String> systemMsg = new LinkedHashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        result.add(systemMsg);

        if (messages != null) {
            // Keep recent history (up to last 16 turns) to balance memory and token latency
            int maxHistory = 16;
            int startIdx = Math.max(0, messages.size() - maxHistory);
            for (int i = startIdx; i < messages.size(); i++) {
                Map<String, String> m = messages.get(i);
                if (m != null && m.containsKey("content")) {
                    String role = m.getOrDefault("role", "user");
                    if (!"system".equalsIgnoreCase(role)) {
                        Map<String, String> clean = new LinkedHashMap<>();
                        clean.put("role", role);
                        clean.put("content", m.get("content"));
                        result.add(clean);
                    }
                }
            }
        }

        return result;
    }

    private Map<String, Object> tryParseJson(String body) {
        try {
            return SimpleJson.parseObject(body);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
}
