package com.nexora.server;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * AppConfig
 * 
 * Central configuration manager for NEXORA.
 * Reads environment variables and optional .env file with sensible defaults for Local and Public modes.
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
    private static final String MEMORY_DIR;
    private static final String MODELS_DIR;

    // Local AI Settings (Ollama)
    private static final String OLLAMA_BASE_URL;
    private static final String OLLAMA_DEFAULT_MODEL;

    // Public AI Settings (OpenRouter)
    private static final String OPENROUTER_BASE_URL;
    private static final String OPENROUTER_API_KEY;
    private static final String OPENROUTER_DEFAULT_MODEL;

    static {
        // Load optional .env file from project root if present
        Map<String, String> dotEnv = loadDotEnv();

        // Port configuration (standard 8080 locally, or dynamic PORT provided by Render/Heroku)
        String envPort = getEnvOrDot("PORT", dotEnv);
        PORT = (envPort != null && !envPort.trim().isEmpty()) ? parsePort(envPort.trim(), 8080) : 8080;

        // Operating mode: LOCAL or PUBLIC
        String envMode = getEnvOrDot("APP_MODE", dotEnv);
        if (envMode != null && envMode.trim().equalsIgnoreCase("PUBLIC")) {
            APP_MODE = Mode.PUBLIC;
        } else {
            APP_MODE = Mode.LOCAL;
        }

        // Frontend static directory location (defaults to "frontend")
        String envFrontend = getEnvOrDot("FRONTEND_DIR", dotEnv);
        FRONTEND_DIR = (envFrontend != null && !envFrontend.trim().isEmpty()) ? envFrontend.trim() : "frontend";

        // Local Ollama Settings
        String envOllamaUrl = getEnvOrDot("OLLAMA_BASE_URL", dotEnv);
        OLLAMA_BASE_URL = (envOllamaUrl != null && !envOllamaUrl.trim().isEmpty()) 
            ? envOllamaUrl.trim().replaceAll("/+$", "") 
            : "http://127.0.0.1:11434";

        String envOllamaModel = getEnvOrDot("OLLAMA_DEFAULT_MODEL", dotEnv);
        OLLAMA_DEFAULT_MODEL = (envOllamaModel != null && !envOllamaModel.trim().isEmpty()) 
            ? envOllamaModel.trim() 
            : "qwen2.5:7b";

        // Public OpenRouter Settings
        String envOpenRouterUrl = getEnvOrDot("OPENROUTER_BASE_URL", dotEnv);
        OPENROUTER_BASE_URL = (envOpenRouterUrl != null && !envOpenRouterUrl.trim().isEmpty())
            ? envOpenRouterUrl.trim().replaceAll("/+$", "")
            : "https://openrouter.ai/api/v1";

        String envApiKey = getEnvOrDot("OPENROUTER_API_KEY", dotEnv);
        OPENROUTER_API_KEY = (envApiKey != null) ? envApiKey.trim() : "";

        String envOpenRouterModel = getEnvOrDot("OPENROUTER_DEFAULT_MODEL", dotEnv);
        OPENROUTER_DEFAULT_MODEL = (envOpenRouterModel != null && !envOpenRouterModel.trim().isEmpty())
            ? envOpenRouterModel.trim()
            : "openrouter/free";

        // Security & Rate Limiting
        RATE_LIMIT_PER_MINUTE = parsePositiveInt(getEnvOrDot("RATE_LIMIT_PER_MINUTE", dotEnv), 30);
        MAX_REQUEST_SIZE_BYTES = parsePositiveInt(getEnvOrDot("MAX_REQUEST_SIZE_BYTES", dotEnv), 1048576); // 1 MB
        REQUEST_TIMEOUT_SECONDS = parsePositiveInt(getEnvOrDot("REQUEST_TIMEOUT_SECONDS", dotEnv), 180); // 3 minutes

        // Memory Storage Directory
        String envMemoryDir = getEnvOrDot("MEMORY_DIR", dotEnv);
        MEMORY_DIR = (envMemoryDir != null && !envMemoryDir.trim().isEmpty()) ? envMemoryDir.trim() : "data/memories";

        // Models Storage Directory
        String envModelsDir = getEnvOrDot("MODELS_DIR", dotEnv);
        MODELS_DIR = (envModelsDir != null && !envModelsDir.trim().isEmpty()) ? envModelsDir.trim() : "data/models";
    }

    private static Map<String, String> loadDotEnv() {
        Map<String, String> envMap = new HashMap<>();
        File envFile = new File(".env");
        if (envFile.exists() && envFile.isFile()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(envFile, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        String key = line.substring(0, eq).trim();
                        String val = line.substring(eq + 1).trim();
                        if ((val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2) ||
                            (val.startsWith("'") && val.endsWith("'") && val.length() >= 2)) {
                            val = val.substring(1, val.length() - 1);
                        }
                        if (!key.isEmpty()) {
                            envMap.put(key, val);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return envMap;
    }

    private static String getEnvOrDot(String key, Map<String, String> dotEnv) {
        String sys = System.getenv(key);
        if (sys != null && !sys.trim().isEmpty()) {
            return sys.trim();
        }
        String dot = dotEnv.get(key);
        if (dot != null && !dot.trim().isEmpty()) {
            return dot.trim();
        }
        return null;
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
    public static String getMemoryDir() { return MEMORY_DIR; }
    public static String getModelsDir() { return MODELS_DIR; }

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
