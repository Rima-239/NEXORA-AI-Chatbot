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
 * ModelsController
 * 
 * Handles GET /api/models requests:
 * Discovers and returns available models from the active provider
 * (Ollama installed models locally, or recommended free models publicly).
 * 
 * Part of NEXORA - AI-Powered Personal Chatbot
 */
public class ModelsController extends BaseController {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (handlePreflight(exchange)) {
            return;
        }

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Only GET requests are permitted on /api/models.");
            return;
        }

        AiService aiService = AiServiceFactory.getService();

        try {
            List<String> models = aiService.getAvailableModels();

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("mode", AppConfig.getAppMode().name());
            response.put("provider", aiService.getProviderName());
            response.put("default_model", aiService.getDefaultModel());
            response.put("models", models);

            sendJsonResponse(exchange, 200, response);

        } catch (Exception e) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("success", true);
            fallback.put("mode", AppConfig.getAppMode().name());
            fallback.put("provider", aiService.getProviderName());
            fallback.put("default_model", aiService.getDefaultModel());
            fallback.put("models", List.of(aiService.getDefaultModel()));

            sendJsonResponse(exchange, 200, fallback);
        }
    }
}
