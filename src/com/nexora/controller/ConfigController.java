package com.nexora.controller;

import com.nexora.server.AppConfig;
import com.nexora.service.AiService;
import com.nexora.service.AiServiceFactory;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ConfigController
 * 
 * Handles GET /api/config requests:
 * Safely shares public application branding, available personas, and default model info
 * without exposing internal server paths or sensitive API keys.
 * 
 * Part of NEXORA - AI-Powered Personal Chatbot
 */
public class ConfigController extends BaseController {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (handlePreflight(exchange)) {
            return;
        }

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Only GET requests are permitted on /api/config.");
            return;
        }

        AiService aiService = AiServiceFactory.getService();

        Map<String, Object> configData = new LinkedHashMap<>();
        configData.put("success", true);
        configData.put("app_name", "NEXORA");
        configData.put("tagline", "Think Smarter. Create Freely. Stay Private.");
        configData.put("description", "Your Private AI Companion.");
        configData.put("mode", AppConfig.getAppMode().name());
        configData.put("provider", aiService.getProviderName());
        configData.put("default_model", aiService.getDefaultModel());
        configData.put("personas", List.of("general", "coding", "writing", "learning"));
        configData.put("rate_limit_per_minute", AppConfig.getRateLimitPerMinute());
        configData.put("hasOpenRouterApiKey", AppConfig.hasOpenRouterApiKey());

        sendJsonResponse(exchange, 200, configData);
    }
}
