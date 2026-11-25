package com.nexora.controller;

import com.nexora.model.ModelInfo;
import com.nexora.model.ModelProfile;
import com.nexora.model.ModelSettings;
import com.nexora.server.AppConfig;
import com.nexora.service.AiService;
import com.nexora.service.AiServiceFactory;
import com.nexora.service.ModelManagerService;
import com.nexora.util.SimpleJson;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ModelManagementController
 * 
 * REST API Controller managing the complete AI Model Center:
 * - GET    /api/models/installed          Installed Ollama models with metadata & suitability
 * - GET    /api/models/catalog            OpenRouter cloud catalog with search & free filter
 * - POST   /api/models/pull               Streamed model pull via Server-Sent Events (SSE)
 * - POST   /api/models/delete             Safe local model deletion
 * - GET    /api/models/profiles           List all model profiles
 * - POST   /api/models/profiles           Create or update profile
 * - DELETE /api/models/profiles           Delete custom profile (?id=... or JSON body)
 * - GET    /api/models/favorites          List favorite model identifiers
 * - POST   /api/models/favorites/toggle   Toggle model favorite status
 * - GET    /api/models/performance        Performance history & analytics summary
 * - POST   /api/models/performance/clear  Clear performance history records
 * - GET    /api/models/hardware           Dynamic CPU/RAM hardware telemetry
 * - GET    /api/models/settings           Retrieve global model settings
 * - POST   /api/models/settings           Update global model settings
 * - GET    /api/models                    Backward-compatible model list
 * 
 * Part of NEXORA - AI-Powered Personal Chatbot
 */
public class ModelManagementController extends BaseController {

    private final ModelManagerService modelManager;

    public ModelManagementController() {
        this.modelManager = ModelManagerService.getInstance();
    }

    @Override
    protected void handleCors(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Accept, Authorization, X-Requested-With, X-User-Id");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (handlePreflight(exchange)) {
            return;
        }

        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod().toUpperCase();

        // Normalize trailing slashes
        if (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }

        try {
            switch (path) {
                case "/api/models":
                    if ("GET".equals(method)) {
                        handleLegacyGetModels(exchange);
                    } else {
                        sendError(exchange, 405, "Method Not Allowed on /api/models");
                    }
                    break;

                case "/api/models/installed":
                    if ("GET".equals(method)) {
                        handleGetInstalled(exchange);
                    } else {
                        sendError(exchange, 405, "Method Not Allowed on /api/models/installed");
                    }
                    break;

                case "/api/models/catalog":
                    if ("GET".equals(method)) {
                        handleGetCatalog(exchange);
                    } else {
                        sendError(exchange, 405, "Method Not Allowed on /api/models/catalog");
                    }
                    break;

                case "/api/models/pull":
                    if ("POST".equals(method)) {
                        handlePullModelStream(exchange);
                    } else {
                        sendError(exchange, 405, "Method Not Allowed on /api/models/pull");
                    }
                    break;

                case "/api/models/delete":
                    if ("POST".equals(method)) {
                        handleDeleteModel(exchange);
                    } else {
                        sendError(exchange, 405, "Method Not Allowed on /api/models/delete");
                    }
                    break;

                case "/api/models/profiles":
                    if ("GET".equals(method)) {
                        handleGetProfiles(exchange);
                    } else if ("POST".equals(method)) {
                        handleSaveProfile(exchange);
                    } else if ("DELETE".equals(method)) {
                        handleDeleteProfile(exchange);
                    } else {
                        sendError(exchange, 405, "Method Not Allowed on /api/models/profiles");
                    }
                    break;

                case "/api/models/favorites":
                    if ("GET".equals(method)) {
                        handleGetFavorites(exchange);
                    } else if ("POST".equals(method)) {
                        handleToggleFavorite(exchange);
                    } else {
                        sendError(exchange, 405, "Method Not Allowed on /api/models/favorites");
                    }
                    break;

                case "/api/models/favorites/toggle":
                    if ("POST".equals(method)) {
                        handleToggleFavorite(exchange);
                    } else {
                        sendError(exchange, 405, "Method Not Allowed on /api/models/favorites/toggle");
                    }
                    break;

                case "/api/models/performance":
                    if ("GET".equals(method)) {
                        handleGetPerformance(exchange);
                    } else {
                        sendError(exchange, 405, "Method Not Allowed on /api/models/performance");
                    }
                    break;

                case "/api/models/performance/clear":
                    if ("POST".equals(method)) {
                        handleClearPerformance(exchange);
                    } else {
                        sendError(exchange, 405, "Method Not Allowed on /api/models/performance/clear");
                    }
                    break;

                case "/api/models/hardware":
                    if ("GET".equals(method)) {
                        handleGetHardware(exchange);
                    } else {
                        sendError(exchange, 405, "Method Not Allowed on /api/models/hardware");
                    }
                    break;

                case "/api/models/settings":
                    if ("GET".equals(method)) {
                        handleGetSettings(exchange);
                    } else if ("POST".equals(method)) {
                        handleUpdateSettings(exchange);
                    } else {
                        sendError(exchange, 405, "Method Not Allowed on /api/models/settings");
                    }
                    break;

                default:
                    sendError(exchange, 404, "Endpoint not found: " + path);
                    break;
            }
        } catch (Exception e) {
            sendError(exchange, 500, "Internal Server Error: " + e.getMessage());
        }
    }

    // =========================================================================
    // ENDPOINT HANDLERS
    // =========================================================================

    private void handleLegacyGetModels(HttpExchange exchange) throws IOException {
        AiService aiService = AiServiceFactory.getService();
        List<String> models;
        try {
            models = aiService.getAvailableModels();
        } catch (Exception e) {
            models = List.of(aiService.getDefaultModel());
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("mode", AppConfig.getAppMode().name());
        response.put("provider", aiService.getProviderName());
        response.put("default_model", aiService.getDefaultModel());
        response.put("models", models);

        sendJsonResponse(exchange, 200, response);
    }

    private void handleGetInstalled(HttpExchange exchange) throws IOException {
        if (AppConfig.isPublicMode()) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("provider", "LOCAL");
            response.put("endpoint", "disabled");
            response.put("count", 0);
            response.put("models", Collections.emptyList());
            response.put("message", "Local Ollama engine is not available in public cloud deployment. Please use Cloud Catalog.");
            sendJsonResponse(exchange, 200, response);
            return;
        }
        try {
            List<ModelInfo> installed = modelManager.getInstalledOllamaModels();
            Set<String> favs = modelManager.getFavorites();

            List<Map<String, Object>> modelsList = new ArrayList<>();
            for (ModelInfo info : installed) {
                Map<String, Object> map = info.toMap();
                map.put("isFavorite", favs.contains(info.getId()));
                modelsList.add(map);
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("provider", "LOCAL");
            response.put("endpoint", AppConfig.getOllamaBaseUrl());
            response.put("count", modelsList.size());
            response.put("models", modelsList);

            sendJsonResponse(exchange, 200, response);

        } catch (IOException e) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", false);
            response.put("provider", "LOCAL");
            response.put("endpoint", AppConfig.getOllamaBaseUrl());
            response.put("error", e.getMessage());
            response.put("models", Collections.emptyList());

            sendJsonResponse(exchange, 200, response);
        }
    }

    private void handleGetCatalog(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String search = "";
        boolean freeOnly = false;
        boolean refresh = false;

        if (query != null && !query.isEmpty()) {
            for (String param : query.split("&")) {
                String[] pair = param.split("=");
                if (pair.length == 2) {
                    if ("search".equalsIgnoreCase(pair[0])) {
                        search = java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                    } else if ("freeOnly".equalsIgnoreCase(pair[0])) {
                        freeOnly = "true".equalsIgnoreCase(pair[1]) || "1".equals(pair[1]);
                    } else if ("refresh".equalsIgnoreCase(pair[0])) {
                        refresh = "true".equalsIgnoreCase(pair[1]) || "1".equals(pair[1]);
                    }
                }
            }
        }

        try {
            List<ModelInfo> catalog = modelManager.getOpenRouterCatalog(search, freeOnly, refresh);
            Set<String> favs = modelManager.getFavorites();

            List<Map<String, Object>> modelsList = new ArrayList<>();
            for (ModelInfo info : catalog) {
                Map<String, Object> map = info.toMap();
                map.put("isFavorite", favs.contains(info.getId()));
                modelsList.add(map);
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("provider", "PUBLIC");
            response.put("hasApiKey", AppConfig.hasOpenRouterApiKey());
            response.put("count", modelsList.size());
            response.put("models", modelsList);

            sendJsonResponse(exchange, 200, response);

        } catch (Exception e) {
            sendError(exchange, 502, "Failed to retrieve cloud catalog: " + e.getMessage());
        }
    }

    private void handlePullModelStream(HttpExchange exchange) throws IOException {
        if (AppConfig.isPublicMode()) {
            sendError(exchange, 403, "Forbidden: Model installation is disabled in PUBLIC mode.");
            return;
        }

        String body = readRequestBody(exchange);
        if (body == null || body.trim().isEmpty()) {
            sendError(exchange, 400, "Request payload cannot be empty.");
            return;
        }

        Map<String, Object> req;
        try {
            req = SimpleJson.parseObject(body);
        } catch (Exception e) {
            sendError(exchange, 400, "Malformed JSON payload: " + e.getMessage());
            return;
        }

        String modelName = SimpleJson.getString(req, "name", "").trim();
        if (modelName.isEmpty()) {
            sendError(exchange, 400, "Parameter 'name' is required.");
            return;
        }

        if (!ModelManagerService.MODEL_IDENTIFIER_PATTERN.matcher(modelName).matches()) {
            sendError(exchange, 400, "Invalid model identifier format: " + modelName);
            return;
        }

        handleCors(exchange);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");

        // Status code 200 with response length 0 sets chunked transfer encoding in Java HttpServer
        exchange.sendResponseHeaders(200, 0);

        OutputStream os = exchange.getResponseBody();
        AtomicBoolean cancelFlag = new AtomicBoolean(false);

        try {
            modelManager.pullModel(modelName, updateMap -> {
                try {
                    String event = "data: " + SimpleJson.toJson(updateMap) + "\n\n";
                    os.write(event.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                } catch (IOException e) {
                    // Browser disconnected or cancelled
                    cancelFlag.set(true);
                }
            }, cancelFlag);

            // Signal stream termination
            os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();

        } catch (IllegalArgumentException e) {
            String errEvent = "data: {\"error\":\"" + e.getMessage() + "\"}\n\n";
            os.write(errEvent.getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (IllegalStateException e) {
            String errEvent = "data: {\"error\":\"" + e.getMessage() + "\"}\n\n";
            os.write(errEvent.getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (Exception e) {
            String errEvent = "data: {\"error\":\"" + e.getMessage() + "\"}\n\n";
            os.write(errEvent.getBytes(StandardCharsets.UTF_8));
            os.flush();
        } finally {
            try {
                os.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void handleDeleteModel(HttpExchange exchange) throws IOException {
        if (AppConfig.isPublicMode()) {
            sendError(exchange, 403, "Forbidden: Model deletion is disabled in PUBLIC mode.");
            return;
        }

        String body = readRequestBody(exchange);
        Map<String, Object> req;
        try {
            req = SimpleJson.parseObject(body);
        } catch (Exception e) {
            sendError(exchange, 400, "Malformed JSON payload: " + e.getMessage());
            return;
        }

        String modelName = SimpleJson.getString(req, "name", "").trim();
        boolean confirmed = SimpleJson.getBoolean(req, "confirmed", false);

        if (modelName.isEmpty()) {
            sendError(exchange, 400, "Parameter 'name' is required.");
            return;
        }

        if (!confirmed) {
            sendError(exchange, 400, "Explicit confirmation required ('confirmed: true').");
            return;
        }

        try {
            modelManager.deleteModel(modelName, confirmed);
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("success", true);
            res.put("message", "Model '" + modelName + "' deleted successfully.");
            sendJsonResponse(exchange, 200, res);

        } catch (IllegalStateException e) {
            sendError(exchange, 409, e.getMessage()); // Conflict (e.g. protected baseline or active model)
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, e.getMessage());
        } catch (Exception e) {
            sendError(exchange, 500, "Failed to delete model: " + e.getMessage());
        }
    }

    private void handleGetProfiles(HttpExchange exchange) throws IOException {
        List<ModelProfile> profiles = modelManager.getProfiles();
        List<Map<String, Object>> list = new ArrayList<>();
        for (ModelProfile p : profiles) {
            list.add(p.toMap());
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("profiles", list);
        sendJsonResponse(exchange, 200, res);
    }

    private void handleSaveProfile(HttpExchange exchange) throws IOException {
        if (AppConfig.isPublicMode()) {
            sendError(exchange, 403, "Forbidden: Profile modification is disabled in PUBLIC mode.");
            return;
        }

        String body = readRequestBody(exchange);
        Map<String, Object> req;
        try {
            req = SimpleJson.parseObject(body);
        } catch (Exception e) {
            sendError(exchange, 400, "Malformed JSON payload: " + e.getMessage());
            return;
        }

        String name = SimpleJson.getString(req, "name", "").trim();
        if (name.isEmpty()) {
            sendError(exchange, 400, "Profile name is required.");
            return;
        }

        ModelProfile p = ModelProfile.fromMap(req);
        ModelProfile saved = modelManager.saveProfile(p);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("profile", saved.toMap());
        sendJsonResponse(exchange, 200, res);
    }

    private void handleDeleteProfile(HttpExchange exchange) throws IOException {
        if (AppConfig.isPublicMode()) {
            sendError(exchange, 403, "Forbidden: Profile deletion is disabled in PUBLIC mode.");
            return;
        }

        String id = null;
        String query = exchange.getRequestURI().getQuery();
        if (query != null && !query.isEmpty()) {
            for (String param : query.split("&")) {
                String[] pair = param.split("=");
                if (pair.length == 2 && "id".equalsIgnoreCase(pair[0])) {
                    id = java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                }
            }
        }

        if (id == null || id.trim().isEmpty()) {
            String body = readRequestBody(exchange);
            if (body != null && !body.trim().isEmpty()) {
                try {
                    Map<String, Object> map = SimpleJson.parseObject(body);
                    id = SimpleJson.getString(map, "id", null);
                } catch (Exception ignored) {
                }
            }
        }

        if (id == null || id.trim().isEmpty()) {
            sendError(exchange, 400, "Profile 'id' is required.");
            return;
        }

        try {
            boolean deleted = modelManager.deleteProfile(id);
            if (deleted) {
                Map<String, Object> res = new LinkedHashMap<>();
                res.put("success", true);
                res.put("message", "Profile deleted successfully.");
                sendJsonResponse(exchange, 200, res);
            } else {
                sendError(exchange, 404, "Profile not found: " + id);
            }
        } catch (IllegalStateException e) {
            sendError(exchange, 403, e.getMessage()); // Cannot delete built-in profile
        } catch (Exception e) {
            sendError(exchange, 500, "Failed to delete profile: " + e.getMessage());
        }
    }

    private void handleGetFavorites(HttpExchange exchange) throws IOException {
        Set<String> favs = modelManager.getFavorites();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("favorites", new ArrayList<>(favs));
        sendJsonResponse(exchange, 200, res);
    }

    private void handleToggleFavorite(HttpExchange exchange) throws IOException {
        String body = readRequestBody(exchange);
        Map<String, Object> req;
        try {
            req = SimpleJson.parseObject(body);
        } catch (Exception e) {
            sendError(exchange, 400, "Malformed JSON payload: " + e.getMessage());
            return;
        }

        String modelId = SimpleJson.getString(req, "modelId", "").trim();
        if (modelId.isEmpty()) {
            modelId = SimpleJson.getString(req, "id", "").trim();
        }

        if (modelId.isEmpty()) {
            sendError(exchange, 400, "Parameter 'modelId' is required.");
            return;
        }

        boolean isFav = modelManager.toggleFavorite(modelId);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("modelId", modelId);
        res.put("isFavorite", isFav);
        res.put("favorites", new ArrayList<>(modelManager.getFavorites()));
        sendJsonResponse(exchange, 200, res);
    }

    private void handleGetPerformance(HttpExchange exchange) throws IOException {
        Map<String, Object> summary = modelManager.getPerformanceSummary();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("performance", summary);
        sendJsonResponse(exchange, 200, res);
    }

    private void handleClearPerformance(HttpExchange exchange) throws IOException {
        if (AppConfig.isPublicMode()) {
            sendError(exchange, 403, "Forbidden: Telemetry modification is disabled in PUBLIC mode.");
            return;
        }

        modelManager.clearPerformanceHistory();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("message", "Performance history cleared.");
        sendJsonResponse(exchange, 200, res);
    }

    private void handleGetHardware(HttpExchange exchange) throws IOException {
        Map<String, Object> hw = modelManager.getHardwareTelemetry();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("hardware", hw);
        sendJsonResponse(exchange, 200, res);
    }

    private void handleGetSettings(HttpExchange exchange) throws IOException {
        ModelSettings settings = modelManager.getSettings();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("settings", settings.toMap());
        sendJsonResponse(exchange, 200, res);
    }

    private void handleUpdateSettings(HttpExchange exchange) throws IOException {
        if (AppConfig.isPublicMode()) {
            sendError(exchange, 403, "Forbidden: Global settings modification is disabled in PUBLIC mode.");
            return;
        }

        String body = readRequestBody(exchange);
        Map<String, Object> updates;
        try {
            updates = SimpleJson.parseObject(body);
        } catch (Exception e) {
            sendError(exchange, 400, "Malformed JSON payload: " + e.getMessage());
            return;
        }

        ModelSettings settings = modelManager.updateSettings(updates);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", true);
        res.put("settings", settings.toMap());
        sendJsonResponse(exchange, 200, res);
    }
}
