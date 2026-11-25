package com.nexora.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * UserMemory
 * 
 * Represents an individual piece of persistent personal memory.
 * Stored independently from conversation histories and preserved across restarts.
 * 
 * Part of NEXORA - AI-Powered Personal Chatbot
 */
public class UserMemory {

    private String id;
    private String text;
    private String category; // project, skill, preference, tool, goal, general
    private long createdAt;
    private long updatedAt;
    private String source;   // "auto" (extracted) or "manual" (user-created)

    public UserMemory() {
    }

    public UserMemory(String id, String text, String category, long createdAt, long updatedAt, String source) {
        this.id = id;
        this.text = text != null ? text.trim() : "";
        this.category = (category != null && !category.trim().isEmpty()) ? category.trim().toLowerCase() : "general";
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.source = (source != null && !source.trim().isEmpty()) ? source.trim().toLowerCase() : "auto";
    }

    public static UserMemory create(String text, String category, String source) {
        long now = System.currentTimeMillis();
        String id = "mem_" + now + "_" + Integer.toHexString((int) (Math.random() * 0xFFFF));
        return new UserMemory(id, text, category, now, now, source);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text != null ? text.trim() : "";
        this.updatedAt = System.currentTimeMillis();
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = (category != null && !category.trim().isEmpty()) ? category.trim().toLowerCase() : "general";
        this.updatedAt = System.currentTimeMillis();
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("text", text);
        map.put("category", category);
        map.put("createdAt", createdAt);
        map.put("updatedAt", updatedAt);
        map.put("source", source);
        return map;
    }

    public static UserMemory fromMap(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        String id = String.valueOf(map.getOrDefault("id", ""));
        String text = String.valueOf(map.getOrDefault("text", ""));
        String category = String.valueOf(map.getOrDefault("category", "general"));
        long createdAt = parseLong(map.get("createdAt"), System.currentTimeMillis());
        long updatedAt = parseLong(map.get("updatedAt"), createdAt);
        String source = String.valueOf(map.getOrDefault("source", "auto"));

        return new UserMemory(id, text, category, createdAt, updatedAt, source);
    }

    private static long parseLong(Object val, long defaultVal) {
        if (val instanceof Number n) {
            return n.longValue();
        }
        if (val != null) {
            try {
                return Long.parseLong(val.toString().trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultVal;
    }
}
