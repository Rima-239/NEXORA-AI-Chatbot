package com.nexora.model;

import com.nexora.util.SimpleJson;

import java.util.*;

/**
 * ModelSettings
 * 
 * Encapsulates global AI model settings, including default provider,
 * default local and cloud models, active model profile, and user favorites.
 * 
 * Part of NEXORA - AI-Powered Personal Chatbot
 */
public class ModelSettings {

    private String defaultProvider;
    private String defaultLocalModel;
    private String defaultCloudModel;
    private String activeProfileId;
    private Set<String> favorites;

    public ModelSettings() {
        this.defaultProvider = "LOCAL";
        this.defaultLocalModel = "qwen2.5:7b";
        this.defaultCloudModel = "openrouter/free";
        this.activeProfileId = "general";
        this.favorites = new LinkedHashSet<>();
        this.favorites.add("qwen2.5:7b");
    }

    public ModelSettings(String defaultProvider, String defaultLocalModel,
                         String defaultCloudModel, String activeProfileId,
                         Set<String> favorites) {
        this.defaultProvider = (defaultProvider != null && !defaultProvider.trim().isEmpty()) ? defaultProvider.trim().toUpperCase() : "LOCAL";
        this.defaultLocalModel = (defaultLocalModel != null && !defaultLocalModel.trim().isEmpty()) ? defaultLocalModel.trim() : "qwen2.5:7b";
        this.defaultCloudModel = (defaultCloudModel != null && !defaultCloudModel.trim().isEmpty()) ? defaultCloudModel.trim() : "openrouter/free";
        this.activeProfileId = (activeProfileId != null && !activeProfileId.trim().isEmpty()) ? activeProfileId.trim() : "general";
        this.favorites = favorites != null ? new LinkedHashSet<>(favorites) : new LinkedHashSet<>();
    }

    public String getDefaultProvider() { return defaultProvider; }
    public void setDefaultProvider(String defaultProvider) {
        if (defaultProvider != null && !defaultProvider.trim().isEmpty()) {
            this.defaultProvider = defaultProvider.trim().toUpperCase();
        }
    }

    public String getDefaultLocalModel() { return defaultLocalModel; }
    public void setDefaultLocalModel(String defaultLocalModel) {
        if (defaultLocalModel != null && !defaultLocalModel.trim().isEmpty()) {
            this.defaultLocalModel = defaultLocalModel.trim();
        }
    }

    public String getDefaultCloudModel() { return defaultCloudModel; }
    public void setDefaultCloudModel(String defaultCloudModel) {
        if (defaultCloudModel != null && !defaultCloudModel.trim().isEmpty()) {
            this.defaultCloudModel = defaultCloudModel.trim();
        }
    }

    public String getActiveProfileId() { return activeProfileId; }
    public void setActiveProfileId(String activeProfileId) {
        if (activeProfileId != null && !activeProfileId.trim().isEmpty()) {
            this.activeProfileId = activeProfileId.trim();
        }
    }

    public Set<String> getFavorites() { return favorites; }
    public void setFavorites(Set<String> favorites) {
        this.favorites = favorites != null ? new LinkedHashSet<>(favorites) : new LinkedHashSet<>();
    }

    public boolean addFavorite(String modelId) {
        if (modelId != null && !modelId.trim().isEmpty()) {
            return favorites.add(modelId.trim());
        }
        return false;
    }

    public boolean removeFavorite(String modelId) {
        if (modelId != null) {
            return favorites.remove(modelId.trim());
        }
        return false;
    }

    public boolean isFavorite(String modelId) {
        return modelId != null && favorites.contains(modelId.trim());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("defaultProvider", defaultProvider);
        map.put("defaultLocalModel", defaultLocalModel);
        map.put("defaultCloudModel", defaultCloudModel);
        map.put("activeProfileId", activeProfileId);
        map.put("favorites", new ArrayList<>(favorites));
        return map;
    }

    public static ModelSettings fromMap(Map<String, Object> map) {
        if (map == null) return new ModelSettings();
        ModelSettings s = new ModelSettings();
        s.setDefaultProvider(SimpleJson.getString(map, "defaultProvider", "LOCAL"));
        s.setDefaultLocalModel(SimpleJson.getString(map, "defaultLocalModel", "qwen2.5:7b"));
        String cloudModel = SimpleJson.getString(map, "defaultCloudModel", "openrouter/free");
        if (cloudModel == null || cloudModel.isEmpty() || cloudModel.contains("deepseek-r1:free") || cloudModel.contains("llama-3.2-3b-instruct:free")) {
            cloudModel = "openrouter/free";
        }
        s.setDefaultCloudModel(cloudModel);
        s.setActiveProfileId(SimpleJson.getString(map, "activeProfileId", "general"));

        List<Object> rawFavs = SimpleJson.getList(map, "favorites");
        Set<String> favSet = new LinkedHashSet<>();
        for (Object item : rawFavs) {
            if (item != null) favSet.add(item.toString());
        }
        if (favSet.isEmpty()) favSet.add("qwen2.5:7b");
        s.setFavorites(favSet);
        return s;
    }
}
