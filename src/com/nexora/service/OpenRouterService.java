package com.nexora.service;

import com.nexora.server.AppConfig;
import com.nexora.util.LanguageDetector.ResponseLanguage;
import com.nexora.util.PersonaPrompts;
import com.nexora.util.SimpleJson;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/**
 * OpenRouterService
 * 
 * Handles communication with OpenRouter API for free hosted models.
 * Used when running in PUBLIC mode on free cloud platforms (Render, etc.).
 * 
 * Part of NEXORA - AI-Powered Personal Chatbot
 */
public class OpenRouterService implements AiService {

    private final HttpClient httpClient;

    // Curated list of verified active free-tier models on OpenRouter
    // Retain only the official dynamic free router to avoid fragile, rate-limited individual models
    private static final List<String> FREE_MODELS = List.of(
            "openrouter/free"
    );

    public OpenRouterService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String generateCompletion(String model, List<Map<String, String>> messages, String persona, List<String> memories, ResponseLanguage responseLanguage) throws Exception {
        return generateCompletionWithKey(model, messages, persona, memories, responseLanguage, null);
    }

    @SuppressWarnings("unchecked")
    public String generateCompletionWithKey(String model, List<Map<String, String>> messages, String persona, List<String> memories, ResponseLanguage responseLanguage, String customApiKey) throws Exception {
        String effectiveApiKey = (customApiKey != null && !customApiKey.trim().isEmpty())
                ? customApiKey.trim()
                : AppConfig.getOpenRouterApiKey();

        if (effectiveApiKey.isEmpty()) {
            throw new IllegalStateException("OpenRouter API key is not configured on the server. "
                    + "Please set the OPENROUTER_API_KEY environment variable or provide your own free key in Settings.");
        }

        String effectiveModel = (model != null && !model.trim().isEmpty()) ? model.trim() : AppConfig.getOpenRouterDefaultModel();
        List<Map<String, String>> preparedMessages = prepareMessagesWithPersona(messages, persona, memories, responseLanguage);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", effectiveModel);
        payload.put("messages", preparedMessages);
        payload.put("max_tokens", 2048);
        payload.put("stream", false);

        String jsonBody = SimpleJson.toJson(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(AppConfig.getOpenRouterBaseUrl() + "/chat/completions"))
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("Authorization", "Bearer " + effectiveApiKey)
                .header("HTTP-Referer", "https://github.com/nexora-ai")
                .header("X-Title", "NEXORA AI Companion")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(AppConfig.getRequestTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            String responseBody = response.body();

            if (statusCode == 401) {
                throw new IOException("OpenRouter Authentication Failed: The configured OPENROUTER_API_KEY is invalid or expired.");
            } else if (statusCode == 429) {
                throw new IOException("OpenRouter Rate Limit Exceeded: Free tier rate limit or quota has been reached for " + effectiveModel + ". Please switch to 'openrouter/free' (Auto-Router) or a local Ollama model.");
            } else if (statusCode == 502 || statusCode == 503 || statusCode == 504) {
                throw new IOException("OpenRouter Service Unavailable: The upstream cloud provider is temporarily unreachable (HTTP " + statusCode + "). Please switch to a local Ollama model.");
            } else if (statusCode != 200) {
                Map<String, Object> errorMap = tryParseJson(responseBody);
                Map<String, Object> errInner = SimpleJson.getMap(errorMap, "error");
                String msg = SimpleJson.getString(errInner, "message", "HTTP error " + statusCode);
                if (statusCode == 404 || msg.contains("no longer available") || msg.contains("no provider serves this model")) {
                    throw new IOException("OpenRouter Model Unavailable: " + msg + " Switch to 'openrouter/free' for automatic free routing.");
                }
                throw new IOException("OpenRouter Provider Error: " + msg);
            }

            Map<String, Object> responseData = SimpleJson.parseObject(responseBody);
            List<Object> choices = SimpleJson.getList(responseData, "choices");
            if (choices.isEmpty()) {
                throw new IOException("OpenRouter returned no completions.");
            }

            Map<String, Object> firstChoice = (Map<String, Object>) choices.get(0);
            Map<String, Object> messageObj = SimpleJson.getMap(firstChoice, "message");
            String content = SimpleJson.getString(messageObj, "content", "").trim();

            if (content.isEmpty()) {
                String reasoning = SimpleJson.getString(messageObj, "reasoning", "").trim();
                if (!reasoning.isEmpty()) {
                    content = reasoning;
                }
            }
            if (content.isEmpty()) {
                String text = SimpleJson.getString(firstChoice, "text", "").trim();
                if (!text.isEmpty()) {
                    content = text;
                }
            }

            if (content.isEmpty()) {
                throw new IOException("OpenRouter returned an empty response. Please try again.");
            }

            return content;

        } catch (HttpTimeoutException e) {
            throw new IOException("The public AI provider request timed out. Please try again.");
        }
    }

    @Override
    public void streamCompletion(String model, List<Map<String, String>> messages, String persona, List<String> memories, ResponseLanguage responseLanguage,
                                 Consumer<String> chunkConsumer, Runnable onComplete, Consumer<Throwable> errorConsumer) throws Exception {
        streamCompletionWithKey(model, messages, persona, memories, responseLanguage, null, chunkConsumer, onComplete, errorConsumer);
    }

    public void streamCompletionWithKey(String model, List<Map<String, String>> messages, String persona, List<String> memories, ResponseLanguage responseLanguage, String customApiKey,
                                        Consumer<String> chunkConsumer, Runnable onComplete, Consumer<Throwable> errorConsumer) throws Exception {
        // Fallback for public mode: generate buffered completion and deliver as a single chunk
        // This ensures maximum reliability across diverse free provider models on OpenRouter
        try {
            String fullReply = generateCompletionWithKey(model, messages, persona, memories, responseLanguage, customApiKey);
            chunkConsumer.accept(fullReply);
            if (onComplete != null) {
                onComplete.run();
            }
        } catch (Throwable t) {
            errorConsumer.accept(t);
        }
    }

    @Override
    public List<String> getAvailableModels() {
        return FREE_MODELS;
    }

    @Override
    public String getProviderName() {
        return "OpenRouter (Public)";
    }

    @Override
    public String getDefaultModel() {
        return AppConfig.getOpenRouterDefaultModel();
    }

    private List<Map<String, String>> prepareMessagesWithPersona(List<Map<String, String>> messages, String persona, List<String> memories, ResponseLanguage responseLanguage) {
        List<Map<String, String>> result = new ArrayList<>();
        String systemPrompt = PersonaPrompts.getPromptWithMemory(persona, memories, responseLanguage);

        Map<String, String> systemMsg = new LinkedHashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        result.add(systemMsg);

        if (messages != null) {
            int maxHistory = 14;
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
