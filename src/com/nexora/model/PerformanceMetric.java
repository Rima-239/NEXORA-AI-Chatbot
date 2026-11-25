package com.nexora.model;

import com.nexora.util.SimpleJson;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PerformanceMetric
 * 
 * Captures execution metrics for an AI generation request:
 * model, provider, duration, time to first token, token count, and success status.
 * 
 * Part of NEXORA - AI-Powered Personal Chatbot
 */
public class PerformanceMetric {

    private String modelId;
    private String provider;
    private long durationMs;
    private long ttftMs;
    private int tokenCount;
    private double tokensPerSec;
    private boolean success;
    private String errorMessage;
    private long timestamp;

    public PerformanceMetric() {
        this.timestamp = System.currentTimeMillis();
    }

    public PerformanceMetric(String modelId, String provider, long durationMs, long ttftMs,
                             int tokenCount, double tokensPerSec, boolean success,
                             String errorMessage, long timestamp) {
        this.modelId = modelId;
        this.provider = provider != null ? provider : "LOCAL";
        this.durationMs = durationMs;
        this.ttftMs = ttftMs;
        this.tokenCount = tokenCount;
        this.tokensPerSec = tokensPerSec;
        this.success = success;
        this.errorMessage = errorMessage != null ? errorMessage : "";
        this.timestamp = timestamp > 0 ? timestamp : System.currentTimeMillis();
    }

    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public long getTtftMs() { return ttftMs; }
    public void setTtftMs(long ttftMs) { this.ttftMs = ttftMs; }

    public int getTokenCount() { return tokenCount; }
    public void setTokenCount(int tokenCount) { this.tokenCount = tokenCount; }

    public double getTokensPerSec() { return tokensPerSec; }
    public void setTokensPerSec(double tokensPerSec) { this.tokensPerSec = tokensPerSec; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("modelId", modelId);
        map.put("provider", provider);
        map.put("durationMs", durationMs);
        map.put("ttftMs", ttftMs);
        map.put("tokenCount", tokenCount);
        map.put("tokensPerSec", tokensPerSec);
        map.put("success", success);
        map.put("errorMessage", errorMessage);
        map.put("timestamp", timestamp);
        return map;
    }

    public static PerformanceMetric fromMap(Map<String, Object> map) {
        if (map == null) return null;
        PerformanceMetric m = new PerformanceMetric();
        m.setModelId(SimpleJson.getString(map, "modelId", ""));
        m.setProvider(SimpleJson.getString(map, "provider", "LOCAL"));
        Object dur = map.get("durationMs");
        if (dur instanceof Number n) m.setDurationMs(n.longValue());
        Object ttft = map.get("ttftMs");
        if (ttft instanceof Number n) m.setTtftMs(n.longValue());
        m.setTokenCount(SimpleJson.getInt(map, "tokenCount", 0));
        Object tps = map.get("tokensPerSec");
        if (tps instanceof Number n) m.setTokensPerSec(n.doubleValue());
        m.setSuccess(SimpleJson.getBoolean(map, "success", true));
        m.setErrorMessage(SimpleJson.getString(map, "errorMessage", ""));
        Object ts = map.get("timestamp");
        if (ts instanceof Number n) m.setTimestamp(n.longValue());
        return m;
    }
}
