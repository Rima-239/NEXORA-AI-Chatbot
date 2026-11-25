package com.nexora.service;

import com.nexora.server.AppConfig;

/**
 * AiServiceFactory
 * 
 * Factory responsible for instantiating the appropriate AiService implementation
 * based on the active operating mode (LOCAL vs PUBLIC).
 * 
 * Part of NEXORA - AI-Powered Personal Chatbot
 */
public class AiServiceFactory {

    private static volatile AiService cachedService;
    private static volatile OllamaService localService;
    private static volatile OpenRouterService publicService;

    public static AiService getService() {
        if (cachedService == null) {
            synchronized (AiServiceFactory.class) {
                if (cachedService == null) {
                    if (AppConfig.isPublicMode()) {
                        cachedService = getPublicService();
                    } else {
                        cachedService = getLocalService();
                    }
                }
            }
        }
        return cachedService;
    }

    /**
     * Resolves the AI service dynamically based on the requested provider ("LOCAL", "OLLAMA", "PUBLIC", "OPENROUTER").
     * Falls back to the default service if provider is null or unrecognized.
     */
    public static AiService getServiceForProvider(String providerName) {
        if (providerName != null && !providerName.trim().isEmpty()) {
            String p = providerName.trim().toUpperCase();
            if (p.contains("PUBLIC") || p.contains("OPENROUTER") || p.contains("CLOUD")) {
                return getPublicService();
            }
            if (p.contains("LOCAL") || p.contains("OLLAMA")) {
                return getLocalService();
            }
        }
        return getService();
    }

    public static OllamaService getLocalService() {
        if (localService == null) {
            synchronized (AiServiceFactory.class) {
                if (localService == null) {
                    localService = new OllamaService();
                }
            }
        }
        return localService;
    }

    public static OpenRouterService getPublicService() {
        if (publicService == null) {
            synchronized (AiServiceFactory.class) {
                if (publicService == null) {
                    publicService = new OpenRouterService();
                }
            }
        }
        return publicService;
    }

    /**
     * Resets cached service instances (useful during testing or runtime mode switching).
     */
    public static synchronized void reset() {
        cachedService = null;
        localService = null;
        publicService = null;
    }
}
