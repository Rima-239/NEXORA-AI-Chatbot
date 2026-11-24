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

    public static AiService getService() {
        if (cachedService == null) {
            synchronized (AiServiceFactory.class) {
                if (cachedService == null) {
                    if (AppConfig.isPublicMode()) {
                        cachedService = new OpenRouterService();
                    } else {
                        cachedService = new OllamaService();
                    }
                }
            }
        }
        return cachedService;
    }

    /**
     * Resets cached service instance (useful during testing or runtime mode switching).
     */
    public static synchronized void reset() {
        cachedService = null;
    }
}
