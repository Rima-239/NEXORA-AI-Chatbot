package com.nexora.server;

/**
 * AppConfig
 * 
 * Central configuration manager for NEXORA.
 * Reads environment variables with sensible defaults for Local and Public modes.
 * 
 * Part of NEXORA - AI-Powered Personal Chatbot
 */
public class AppConfig {

    public enum Mode {
        LOCAL,
        PUBLIC
    }

    // Server Settings
    private static final int PORT;
    private static final Mode APP_MODE;
    private static final String FRONTEND_DIR;
    private static final int MAX_REQUEST_SIZE_BYTES;
    private static final int RATE_LIMIT_PER_MINUTE;
    private static final int REQUEST_TIMEOUT_SECONDS;

    // Local AI Settings (Ollama)
    private static final String OLLAMA_BASE_URL;
    private static final String OLLAMA_DEFAULT_MODEL;

    // Public AI Settings (OpenRouter)
    private static final String OPENROUTER_BASE_URL;
    private static final String OPENROUTER_API_KEY;
    private static final String OPENROUTER_DEFAULT_MODEL;

    static {
        // Port configuration (standard 8080 locally, or dynamic PORT provided by Render/Heroku)
        String envPort = System.getenv("PORT");
        PORT = (envPort != null && !envPort.trim().isEmpty()) ? parsePort(envPort.trim(), 8080) : 8080;

        // Operating mode: LOCAL or PUBLIC
        String envMode = System.getenv("APP_MODE");
        if (envMode != null && envMode.trim().equalsIgnoreCase("PUBLIC")) {
            APP_MODE = Mode.PUBLIC;
        } else {
            APP_MODE = Mode.LOCAL;
        }

        // Frontend static directory location (defaults to "frontend")
        String envFrontend = System.getenv("FRONTEND_DIR");
        FRONTEND_DIR = (envFrontend != null && !envFrontend.trim().isEmpty()) ? envFrontend.trim() : "frontend";

        // Local Ollama Settings
        String envOllamaUrl = System.getenv("OLLAMA_BASE_URL");
        OLLAMA_BASE_URL = (envOllamaUrl != null && !envOllamaUrl.trim().isEmpty()) 
            ? envOllamaUrl.trim().replaceAll("/+$", "") 
            : "http://127.0.0.1:11434";

        String envOllamaModel = System.getenv("OLLAMA_DEFAULT_MODEL");
        OLLAMA_DEFAULT_MODEL = (envOllamaModel != null && !envOllamaModel.trim().isEmpty()) 
            ? envOllamaModel.trim() 
            : "qwen2.5:7b";

        // Public OpenRouter Settings
        String envOpenRouterUrl = System.getenv("OPENROUTER_BASE_URL");
        OPENROUTER_BASE_URL = (envOpenRouterUrl != null && !envOpenRouterUrl.trim().isEmpty())
            ? envOpenRouterUrl.trim().replaceAll("/+$", "")
            : "https://openrouter.ai/api/v1";

        String envApiKey = System.getenv("OPENROUTER_API_KEY");
        OPENROUTER_API_KEY = (envApiKey != null) ? envApiKey.trim() : "";

        String envOpenRouterModel = System.getenv("OPENROUTER_DEFAULT_MODEL");
        OPENROUTER_DEFAULT_MODEL = (envOpenRouterModel != null && !envOpenRouterModel.trim().isEmpty())
            ? envOpenRouterModel.trim()
            : "meta-llama/llama-3.2-3b-instruct:free";

        // Security & Rate Limiting
        RATE_LIMIT_PER_MINUTE = parsePositiveInt(System.getenv("RATE_LIMIT_PER_MINUTE"), 30);
        MAX_REQUEST_SIZE_BYTES = parsePositiveInt(System.getenv("MAX_REQUEST_SIZE_BYTES"), 1048576); // 1 MB
        REQUEST_TIMEOUT_SECONDS = parsePositiveInt(System.getenv("REQUEST_TIMEOUT_SECONDS"), 180); // 3 minutes
    }

    private static int parsePort(String value, int defaultVal) {
        try {
            int port = Integer.parseInt(value);
            return (port > 0 && port <= 65535) ? port : defaultVal;
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private static int parsePositiveInt(String value, int defaultVal) {
        if (value == null || value.trim().isEmpty()) {
            return defaultVal;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : defaultVal;
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    // Getters
    public static int getPort() { return PORT; }
    public static Mode getAppMode() { return APP_MODE; }
    public static String getFrontendDir() { return FRONTEND_DIR; }
    public static boolean isLocalMode() { return APP_MODE == Mode.LOCAL; }
    public static boolean isPublicMode() { return APP_MODE == Mode.PUBLIC; }

    public static String getOllamaBaseUrl() { return OLLAMA_BASE_URL; }
    public static String getOllamaDefaultModel() { return OLLAMA_DEFAULT_MODEL; }

    public static String getOpenRouterBaseUrl() { return OPENROUTER_BASE_URL; }
    public static String getOpenRouterApiKey() { return OPENROUTER_API_KEY; }
    public static String getOpenRouterDefaultModel() { return OPENROUTER_DEFAULT_MODEL; }
    public static boolean hasOpenRouterApiKey() { return !OPENROUTER_API_KEY.isEmpty(); }

    public static int getRateLimitPerMinute() { return RATE_LIMIT_PER_MINUTE; }
    public static int getMaxRequestSizeBytes() { return MAX_REQUEST_SIZE_BYTES; }
    public static int getRequestTimeoutSeconds() { return REQUEST_TIMEOUT_SECONDS; }

    /**
     * Resolves the model to use: uses client requested model if valid, otherwise falls back to mode default.
     */
    public static String resolveModel(String requestedModel) {
        if (requestedModel != null && !requestedModel.trim().isEmpty()) {
            return requestedModel.trim();
        }
        return isLocalMode() ? OLLAMA_DEFAULT_MODEL : OPENROUTER_DEFAULT_MODEL;
    }
}
