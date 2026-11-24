package com.nexora.controller;

import com.nexora.server.AppConfig;
import com.nexora.util.SimpleJson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * BaseController
 * 
 * Abstract base class providing common HTTP utilities, CORS negotiation,
 * request parsing, and standardized JSON response encoding.
 * 
 * Part of NEXORA - AI-Powered Personal Chatbot
 */
public abstract class BaseController implements HttpHandler {

    protected void handleCors(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Accept, Authorization, X-Requested-With");
    }

    protected boolean handlePreflight(HttpExchange exchange) throws IOException {
        handleCors(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return true;
        }
        return false;
    }

    protected void sendJsonResponse(HttpExchange exchange, int statusCode, Map<String, Object> data) throws IOException {
        handleCors(exchange);
        String jsonString = SimpleJson.toJson(data);
        byte[] responseBytes = jsonString.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Content-Length", String.valueOf(responseBytes.length));

        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
            os.flush();
        }
    }

    protected void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        Map<String, Object> errorMap = new LinkedHashMap<>();
        errorMap.put("success", false);
        errorMap.put("error", message);
        errorMap.put("status", statusCode);
        sendJsonResponse(exchange, statusCode, errorMap);
    }

    protected String readRequestBody(HttpExchange exchange) throws IOException {
        int maxBytes = AppConfig.getMaxRequestSizeBytes();
        try (InputStream is = exchange.getRequestBody();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[4096];
            int read;
            int totalBytes = 0;

            while ((read = is.read(buffer)) != -1) {
                totalBytes += read;
                if (totalBytes > maxBytes) {
                    throw new IllegalArgumentException("Payload size exceeds maximum allowed limit of " + (maxBytes / 1024) + " KB.");
                }
                baos.write(buffer, 0, read);
            }

            return baos.toString(StandardCharsets.UTF_8);
        }
    }

    protected String getClientIp(HttpExchange exchange) {
        String xForwardedFor = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.trim().isEmpty()) {
            // First IP in comma-separated list
            String[] parts = xForwardedFor.split(",");
            if (parts.length > 0 && !parts[0].trim().isEmpty()) {
                return parts[0].trim();
            }
        }
        if (exchange.getRemoteAddress() != null && exchange.getRemoteAddress().getAddress() != null) {
            return exchange.getRemoteAddress().getAddress().getHostAddress();
        }
        return "127.0.0.1";
    }
}
