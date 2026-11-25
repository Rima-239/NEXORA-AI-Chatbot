package com.nexora.controller;

import com.sun.net.httpserver.HttpExchange;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * StaticFileHandler
 * 
 * Serves static web frontend files (HTML, CSS, JS, PWA assets, icons)
 * directly from the frontend/ directory on the same port as the REST API.
 * Includes security checks against directory traversal attacks.
 * 
 * Part of NEXORA - AI-Powered Personal Chatbot
 */
public class StaticFileHandler extends BaseController {

    private final Path rootPath;

    public StaticFileHandler(String frontendDir) {
        this.rootPath = Paths.get(frontendDir).toAbsolutePath().normalize();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (handlePreflight(exchange)) {
            return;
        }

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Static assets only support GET/HEAD.");
            return;
        }

        URI requestUri = exchange.getRequestURI();
        String path = requestUri.getPath();

        // Default root maps to index.html
        if (path == null || path.equals("/") || path.trim().isEmpty()) {
            path = "/index.html";
        }

        // Prevent path traversal outside root directory
        Path requestedPath = rootPath.resolve(path.startsWith("/") ? path.substring(1) : path).normalize();
        if (!requestedPath.startsWith(rootPath)) {
            sendError(exchange, 403, "Access Denied: Path traversal not permitted.");
            return;
        }

        File file = requestedPath.toFile();

        // If a directory was requested, look for index.html inside it
        if (file.isDirectory()) {
            file = new File(file, "index.html");
        }

        if (!file.exists() || !file.isFile()) {
            sendError(exchange, 404, "File Not Found: " + path);
            return;
        }

        String mimeType = getMimeType(file.getName());

        handleCors(exchange);
        exchange.getResponseHeaders().set("Content-Type", mimeType);
        exchange.getResponseHeaders().set("Content-Length", String.valueOf(file.length()));

        String fileNameLower = file.getName().toLowerCase();
        if (fileNameLower.endsWith(".html") || fileNameLower.endsWith(".js") || fileNameLower.endsWith(".css")) {
            exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
            exchange.getResponseHeaders().set("Pragma", "no-cache");
            exchange.getResponseHeaders().set("Expires", "0");
        } else {
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
        }

        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }

        exchange.sendResponseHeaders(200, file.length());

        try (FileInputStream fis = new FileInputStream(file);
             OutputStream os = exchange.getResponseBody()) {

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }
            os.flush();
        }
    }

    private String getMimeType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=UTF-8";
        if (lower.endsWith(".css")) return "text/css; charset=UTF-8";
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "application/javascript; charset=UTF-8";
        if (lower.endsWith(".json")) return "application/json; charset=UTF-8";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".txt")) return "text/plain; charset=UTF-8";
        if (lower.endsWith(".md")) return "text/markdown; charset=UTF-8";
        if (lower.endsWith(".webmanifest") || lower.endsWith(".manifest")) return "application/manifest+json";
        return "application/octet-stream";
    }
}
