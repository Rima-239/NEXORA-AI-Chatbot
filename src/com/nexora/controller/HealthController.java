package com.nexora.controller;

import com.nexora.server.AppConfig;
import com.nexora.service.AiService;
import com.nexora.service.AiServiceFactory;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HealthController
 * 
 * Handles GET /api/health requests:
 * Provides real-time server diagnostics, uptime, operating mode, and JVM memory metrics.
 * 
 * Part of NEXORA - AI-Powered Personal Chatbot
 */
public class HealthController extends BaseController {

    private final long startTimeMillis;

    public HealthController(long startTimeMillis) {
        this.startTimeMillis = startTimeMillis;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (handlePreflight(exchange)) {
            return;
        }

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Only GET requests are permitted on /api/health.");
            return;
        }

        AiService aiService = AiServiceFactory.getService();
        Runtime runtime = Runtime.getRuntime();

        long uptimeSeconds = (System.currentTimeMillis() - startTimeMillis) / 1000;
        long freeMemMb = runtime.freeMemory() / (1024 * 1024);
        long totalMemMb = runtime.totalMemory() / (1024 * 1024);

        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "ok");
        health.put("service", "NEXORA");
        health.put("version", "1.0.0");
        health.put("mode", AppConfig.getAppMode().name());
        health.put("provider", aiService.getProviderName());
        health.put("default_model", aiService.getDefaultModel());
        health.put("uptime_seconds", uptimeSeconds);
        health.put("jvm_free_memory_mb", freeMemMb);
        health.put("jvm_total_memory_mb", totalMemMb);
        health.put("timestamp", System.currentTimeMillis());

        sendJsonResponse(exchange, 200, health);
    }
}
