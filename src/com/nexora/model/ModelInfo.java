package com.nexora.model;

import com.nexora.util.SimpleJson;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ModelInfo
 * 
 * Represents detailed metadata for an AI model (local Ollama or cloud OpenRouter),
 * including parameter size, quantization, context length, pricing, and hardware suitability.
 * 
 * Part of NEXORA - AI-Powered Personal Chatbot
 */
public class ModelInfo {

    private String id;
    private String name;
    private String tag;
    private String provider; // "LOCAL" or "PUBLIC"
    private long sizeBytes;
    private String parameterSize;
    private String quantization;
    private String family;
    private int contextLength;
    private double pricingPrompt;
    private double pricingCompletion;
    private boolean isInstalled;
    private String suitabilityLabel;  // "RECOMMENDED", "MAY_RUN_SLOWLY", "HIGH_RAM", "UNKNOWN"
    private String suitabilityReason;
    private String status; // "verified", "rate_limited", "unverified", "unavailable"

    public ModelInfo() {
        this.provider = "LOCAL";
        this.isInstalled = false;
        this.suitabilityLabel = "UNKNOWN";
        this.suitabilityReason = "";
        this.status = "verified";
    }

    public ModelInfo(String id, String name, String tag, String provider, long sizeBytes,
                     String parameterSize, String quantization, String family, int contextLength,
                     double pricingPrompt, double pricingCompletion, boolean isInstalled,
                     String suitabilityLabel, String suitabilityReason) {
        this.id = id;
        this.name = name != null ? name : id;
        this.tag = tag != null ? tag : "";
        this.provider = provider != null ? provider : "LOCAL";
        this.sizeBytes = sizeBytes;
        this.parameterSize = parameterSize != null ? parameterSize : "";
        this.quantization = quantization != null ? quantization : "";
        this.family = family != null ? family : "";
        this.contextLength = contextLength;
        this.pricingPrompt = pricingPrompt;
        this.pricingCompletion = pricingCompletion;
        this.isInstalled = isInstalled;
        this.suitabilityLabel = suitabilityLabel != null ? suitabilityLabel : "UNKNOWN";
        this.suitabilityReason = suitabilityReason != null ? suitabilityReason : "";
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTag() { return tag; }
    public void setTag(String tag) { this.tag = tag; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }

    public String getParameterSize() { return parameterSize; }
    public void setParameterSize(String parameterSize) { this.parameterSize = parameterSize; }

    public String getQuantization() { return quantization; }
    public void setQuantization(String quantization) { this.quantization = quantization; }

    public String getFamily() { return family; }
    public void setFamily(String family) { this.family = family; }

    public int getContextLength() { return contextLength; }
    public void setContextLength(int contextLength) { this.contextLength = contextLength; }

    public double getPricingPrompt() { return pricingPrompt; }
    public void setPricingPrompt(double pricingPrompt) { this.pricingPrompt = pricingPrompt; }

    public double getPricingCompletion() { return pricingCompletion; }
    public void setPricingCompletion(double pricingCompletion) { this.pricingCompletion = pricingCompletion; }

    public boolean isInstalled() { return isInstalled; }
    public void setInstalled(boolean installed) { isInstalled = installed; }

    public String getSuitabilityLabel() { return suitabilityLabel; }
    public void setSuitabilityLabel(String suitabilityLabel) { this.suitabilityLabel = suitabilityLabel; }

    public String getSuitabilityReason() { return suitabilityReason; }
    public void setSuitabilityReason(String suitabilityReason) { this.suitabilityReason = suitabilityReason; }

    public String getStatus() { return status != null ? status : "verified"; }
    public void setStatus(String status) { this.status = status; }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("tag", tag);
        map.put("provider", provider);
        map.put("sizeBytes", sizeBytes);
        map.put("parameterSize", parameterSize);
        map.put("quantization", quantization);
        map.put("family", family);
        map.put("contextLength", contextLength);
        map.put("pricingPrompt", pricingPrompt);
        map.put("pricingCompletion", pricingCompletion);
        map.put("isInstalled", isInstalled);
        map.put("suitabilityLabel", suitabilityLabel);
        map.put("suitabilityReason", suitabilityReason);
        map.put("status", getStatus());
        return map;
    }

    public static ModelInfo fromMap(Map<String, Object> map) {
        if (map == null) return null;
        ModelInfo info = new ModelInfo();
        info.setId(SimpleJson.getString(map, "id", ""));
        info.setName(SimpleJson.getString(map, "name", info.getId()));
        info.setTag(SimpleJson.getString(map, "tag", ""));
        info.setProvider(SimpleJson.getString(map, "provider", "LOCAL"));
        Object sizeObj = map.get("sizeBytes");
        if (sizeObj instanceof Number n) {
            info.setSizeBytes(n.longValue());
        }
        info.setParameterSize(SimpleJson.getString(map, "parameterSize", ""));
        info.setQuantization(SimpleJson.getString(map, "quantization", ""));
        info.setFamily(SimpleJson.getString(map, "family", ""));
        info.setContextLength(SimpleJson.getInt(map, "contextLength", 0));
        Object pp = map.get("pricingPrompt");
        if (pp instanceof Number n) info.setPricingPrompt(n.doubleValue());
        Object pc = map.get("pricingCompletion");
        if (pc instanceof Number n) info.setPricingCompletion(n.doubleValue());
        info.setInstalled(SimpleJson.getBoolean(map, "isInstalled", false));
        info.setSuitabilityLabel(SimpleJson.getString(map, "suitabilityLabel", "UNKNOWN"));
        info.setSuitabilityReason(SimpleJson.getString(map, "suitabilityReason", ""));
        info.setStatus(SimpleJson.getString(map, "status", "verified"));
        return info;
    }
}
