package com.nexora.controller;

import com.nexora.model.UserMemory;
import com.nexora.server.AppConfig;
import com.nexora.server.RateLimiter;
import com.nexora.service.MemoryService;
import com.nexora.util.SimpleJson;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.URI;
import java.util.*;

/**
 * MemoryController
 * 
 * REST API Controller for managing Personal Memory:
 * - GET    /api/memory : Retrieve saved memories & auto-memory status
 * - POST   /api/memory : Manually add a memory or update settings
 * - PUT    /api/memory : Edit an existing memory
 * - DELETE /api/memory : Delete an individual memory or clear all
 * 
 * Part of NEXORA - AI-Powered Personal Chatbot
 */
public class MemoryController extends BaseController {

    private final RateLimiter rateLimiter;
    private final MemoryService memoryService;

    public MemoryController(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
        this.memoryService = MemoryService.getInstance();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (handlePreflight(exchange)) {
            return;
        }

        String clientIp = getClientIp(exchange);
        if (!rateLimiter.isAllowed(clientIp)) {
            sendError(exchange, 429, "Rate limit exceeded. Please wait a moment before sending another request.");
            return;
        }

        String method = exchange.getRequestMethod().toUpperCase();

        if (AppConfig.isPublicMode() && !"GET".equalsIgnoreCase(method)) {
            sendError(exchange, 403, "Server-side memory modification is disabled in PUBLIC mode. All personal memories are stored privately in your browser.");
            return;
        }

        try {
            switch (method) {
                case "GET" -> handleGet(exchange);
                case "POST" -> handlePost(exchange);
                case "PUT" -> handlePut(exchange);
                case "DELETE" -> handleDelete(exchange);
                default -> sendError(exchange, 405, "Method Not Allowed. Supported: GET, POST, PUT, DELETE.");
            }
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, e.getMessage());
        } catch (NoSuchElementException e) {
            sendError(exchange, 404, e.getMessage());
        } catch (Exception e) {
            sendError(exchange, 500, "Internal Server Error: " + e.getMessage());
        }
    }

    private void handleGet(HttpExchange exchange) throws IOException {
        if (AppConfig.isPublicMode()) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("userId", "client-isolated");
            resp.put("autoMemoryEnabled", true);
            resp.put("count", 0);
            resp.put("memories", Collections.emptyList());
            resp.put("storageMode", "CLIENT_LOCAL_STORAGE");
            resp.put("message", "In PUBLIC mode, memories are stored privately in client browser localStorage.");
            sendJsonResponse(exchange, 200, resp);
            return;
        }

        Map<String, String> queryParams = parseQueryParams(exchange.getRequestURI());
        String userId = resolveUserId(exchange, queryParams.get("userId"));

        List<UserMemory> memories = memoryService.getMemories(userId);
        boolean autoEnabled = memoryService.isAutoMemoryEnabled(userId);

        List<Map<String, Object>> memMaps = new ArrayList<>();
        for (UserMemory m : memories) {
            memMaps.add(m.toMap());
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("userId", userId);
        resp.put("autoMemoryEnabled", autoEnabled);
        resp.put("count", memories.size());
        resp.put("memories", memMaps);

        sendJsonResponse(exchange, 200, resp);
    }

    private void handlePost(HttpExchange exchange) throws IOException {
        String body = readRequestBody(exchange);
        if (body == null || body.trim().isEmpty()) {
            sendError(exchange, 400, "Request body cannot be empty.");
            return;
        }

        Map<String, Object> data = SimpleJson.parseObject(body);
        String userId = resolveUserId(exchange, SimpleJson.getString(data, "userId", null));

        // Case 1: Toggle auto-memory setting
        if (data.containsKey("autoMemoryEnabled") || "set_settings".equalsIgnoreCase(SimpleJson.getString(data, "action", ""))) {
            boolean enabled = SimpleJson.getBoolean(data, "autoMemoryEnabled", true);
            memoryService.setAutoMemoryEnabled(userId, enabled);

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("userId", userId);
            resp.put("autoMemoryEnabled", enabled);
            resp.put("message", "Auto-memory setting updated to: " + enabled);
            sendJsonResponse(exchange, 200, resp);
            return;
        }

        // Case 2: Manually add memory
        String text = SimpleJson.getString(data, "text", "").trim();
        if (text.isEmpty()) {
            sendError(exchange, 400, "Memory text cannot be empty.");
            return;
        }
        if (text.length() > 500) {
            sendError(exchange, 400, "Memory text exceeds maximum length of 500 characters.");
            return;
        }
        if (memoryService.containsSecrets(text)) {
            sendError(exchange, 400, "Security Violation: Personal memories cannot contain passwords, API keys, or private tokens.");
            return;
        }

        String category = SimpleJson.getString(data, "category", "general");
        UserMemory created = memoryService.addMemory(userId, text, category, "manual");

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("userId", userId);
        resp.put("memory", created.toMap());
        sendJsonResponse(exchange, 201, resp);
    }

    private void handlePut(HttpExchange exchange) throws IOException {
        String body = readRequestBody(exchange);
        if (body == null || body.trim().isEmpty()) {
            sendError(exchange, 400, "Request body cannot be empty.");
            return;
        }

        Map<String, Object> data = SimpleJson.parseObject(body);
        String userId = resolveUserId(exchange, SimpleJson.getString(data, "userId", null));
        String memoryId = SimpleJson.getString(data, "id", "").trim();
        String text = SimpleJson.getString(data, "text", "").trim();
        String category = SimpleJson.getString(data, "category", "").trim();

        if (memoryId.isEmpty()) {
            sendError(exchange, 400, "Missing required parameter 'id'.");
            return;
        }
        if (text.isEmpty()) {
            sendError(exchange, 400, "Memory text cannot be empty.");
            return;
        }
        if (text.length() > 500) {
            sendError(exchange, 400, "Memory text exceeds maximum length of 500 characters.");
            return;
        }
        if (memoryService.containsSecrets(text)) {
            sendError(exchange, 400, "Security Violation: Personal memories cannot contain passwords, API keys, or private tokens.");
            return;
        }

        UserMemory updated = memoryService.updateMemory(userId, memoryId, text, category.isEmpty() ? null : category);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("userId", userId);
        resp.put("memory", updated.toMap());
        sendJsonResponse(exchange, 200, resp);
    }

    private void handleDelete(HttpExchange exchange) throws IOException {
        Map<String, String> queryParams = parseQueryParams(exchange.getRequestURI());
        String body = null;
        try {
            body = readRequestBody(exchange);
        } catch (Exception ignored) {
        }

        Map<String, Object> bodyData = (body != null && !body.trim().isEmpty()) ? SimpleJson.parseObject(body) : Collections.emptyMap();

        String rawUserId = queryParams.containsKey("userId") 
                ? queryParams.get("userId") 
                : SimpleJson.getString(bodyData, "userId", null);
        String userId = resolveUserId(exchange, rawUserId);

        boolean clearAll = "true".equalsIgnoreCase(queryParams.get("clearAll")) 
                || SimpleJson.getBoolean(bodyData, "clearAll", false);

        if (clearAll) {
            memoryService.clearAllMemories(userId);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("userId", userId);
            resp.put("message", "All personal memories cleared successfully.");
            sendJsonResponse(exchange, 200, resp);
            return;
        }

        String memoryId = queryParams.containsKey("id") ? queryParams.get("id") : SimpleJson.getString(bodyData, "id", "");
        if (memoryId == null || memoryId.trim().isEmpty()) {
            sendError(exchange, 400, "Missing required parameter 'id' (or set clearAll=true).");
            return;
        }

        boolean deleted = memoryService.deleteMemory(userId, memoryId.trim());
        if (!deleted) {
            sendError(exchange, 404, "Memory with ID " + memoryId + " not found.");
            return;
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("userId", userId);
        resp.put("message", "Memory deleted successfully.");
        sendJsonResponse(exchange, 200, resp);
    }

    private String resolveUserId(HttpExchange exchange, String paramUserId) {
        if (paramUserId != null && !paramUserId.trim().isEmpty()) {
            return memoryService.sanitizeUserId(paramUserId);
        }
        String headerUserId = exchange.getRequestHeaders().getFirst("X-User-Id");
        if (headerUserId != null && !headerUserId.trim().isEmpty()) {
            return memoryService.sanitizeUserId(headerUserId);
        }
        return "default";
    }

    private Map<String, String> parseQueryParams(URI uri) {
        Map<String, String> result = new HashMap<>();
        String query = uri.getRawQuery();
        if (query == null || query.isEmpty()) {
            return result;
        }
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0 && idx < pair.length() - 1) {
                String key = pair.substring(0, idx);
                String val = pair.substring(idx + 1);
                result.put(key, val);
            } else if (idx > 0) {
                result.put(pair.substring(0, idx), "");
            }
        }
        return result;
    }
}
