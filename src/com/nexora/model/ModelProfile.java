package com.nexora.model;

import com.nexora.util.SimpleJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ModelProfile
 * 
 * Represents an intelligent Model Profile combining a domain/task
 * with a preferred provider, model identifier, and prompt persona.
 * 
 * Part of NEXORA - AI-Powered Personal Chatbot
 */
public class ModelProfile {

    private String id;
    private String name;
    private String description;
    private String provider; // "LOCAL" or "PUBLIC"
    private String modelId;
    private String persona;  // "general", "coding", "writing", "learning"
    private boolean isCustom;

    public ModelProfile() {
    }

    public ModelProfile(String id, String name, String description, String provider,
                        String modelId, String persona, boolean isCustom) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.provider = provider != null ? provider : "LOCAL";
        this.modelId = modelId;
        this.persona = persona != null ? persona : "general";
        this.isCustom = isCustom;
    }

    /**
     * Returns the 4 default built-in system profiles.
     */
    public static List<ModelProfile> createDefaultProfiles() {
        List<ModelProfile> defaults = new ArrayList<>();
        defaults.add(new ModelProfile(
                "general",
                "General Chat",
                "Balanced conversational intelligence for everyday inquiries and explanations.",
                "LOCAL",
                "qwen2.5:7b",
                "general",
                false
        ));
        defaults.add(new ModelProfile(
                "coding",
                "Coding Master",
                "Optimized for software development, debugging, and code generation.",
                "LOCAL",
                "qwen2.5:7b",
                "coding",
                false
        ));
        defaults.add(new ModelProfile(
                "writing",
                "Creative Writing",
                "Tailored for essays, documentation, storytelling, and structured drafting.",
                "LOCAL",
                "qwen2.5:7b",
                "writing",
                false
        ));
        defaults.add(new ModelProfile(
                "learning",
                "Fast Learning",
                "High-speed learning, step-by-step guidance, and conceptual breakdowns.",
                "LOCAL",
                "qwen2.5:3b",
                "learning",
                false
        ));
        return defaults;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }

    public String getPersona() { return persona; }
    public void setPersona(String persona) { this.persona = persona; }

    public boolean isCustom() { return isCustom; }
    public void setCustom(boolean custom) { isCustom = custom; }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("description", description);
        map.put("provider", provider);
        map.put("modelId", modelId);
        map.put("persona", persona);
        map.put("isCustom", isCustom);
        return map;
    }

    public static ModelProfile fromMap(Map<String, Object> map) {
        if (map == null) return null;
        ModelProfile p = new ModelProfile();
        p.setId(SimpleJson.getString(map, "id", ""));
        p.setName(SimpleJson.getString(map, "name", ""));
        p.setDescription(SimpleJson.getString(map, "description", ""));
        p.setProvider(SimpleJson.getString(map, "provider", "LOCAL"));
        p.setModelId(SimpleJson.getString(map, "modelId", ""));
        p.setPersona(SimpleJson.getString(map, "persona", "general"));
        p.setCustom(SimpleJson.getBoolean(map, "isCustom", false));
        return p;
    }
}
