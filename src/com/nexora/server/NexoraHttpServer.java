package com.nexora.server;

import com.nexora.controller.*;
import com.nexora.service.AiService;
import com.nexora.service.AiServiceFactory;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * NexoraHttpServer
 * 
 * Configures and launches the lightweight Core Java 21 HTTP server.
 * Uses Java 21 Virtual Threads to efficiently serve API endpoints and static assets.
 * 
 * Part of NEXORA - AI-Powered Personal Chatbot
 */
public class NexoraHttpServer {

    private final HttpServer server;
    private final int port;
    private final long startTimeMillis;

    public NexoraHttpServer() throws IOException {
        this.port = AppConfig.getPort();
        this.startTimeMillis = System.currentTimeMillis();

        // Bind server to all interfaces on configured port
        this.server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);

        // Utilize Java 21 Virtual Threads for non-blocking concurrency
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        // Initialize security and rate limiter
        RateLimiter rateLimiter = new RateLimiter(AppConfig.getRateLimitPerMinute());

        // Register API and Static File routes
        server.createContext("/api/chat", new ChatController(rateLimiter));
        server.createContext("/api/models", new ModelsController());
        server.createContext("/api/health", new HealthController(startTimeMillis));
        server.createContext("/api/config", new ConfigController());

        // Catch-all route serves frontend static assets from configured frontend directory
        server.createContext("/", new StaticFileHandler(AppConfig.getFrontendDir()));
    }

    /**
     * Starts the HTTP server and logs operational status.
     */
    public void start() {
        server.start();

        AiService aiService = AiServiceFactory.getService();

        System.out.println("=============================================================");
        System.out.println("   _   _ _______   ______  _____            ");
        System.out.println("  | \\ | |  ___\\ \\ / / __ \\|  __ \\   /\\      ");
        System.out.println("  |  \\| | |__  \\ V / |  | | |__) | /  \\     ");
        System.out.println("  | . ` |  __|  > <| |  | |  _  / / /\\ \\    ");
        System.out.println("  | |\\  | |___ / . \\ |__| | | \\ \\/ ____ \\   ");
        System.out.println("  |_| \\_|_____/_/ \\_\\____/|_|  \\_\\_/    \\_\\  ");
        System.out.println("                                             ");
        System.out.println("  NEXORA: Think Smarter. Create Freely. Stay Private.");
        System.out.println("=============================================================");
        System.out.println("  [Status]   Server online at: http://127.0.0.1:" + port + "/");
        System.out.println("  [Mode]     " + AppConfig.getAppMode());
        System.out.println("  [Provider] " + aiService.getProviderName());
        System.out.println("  [Model]    " + aiService.getDefaultModel());
        System.out.println("  [Executor] Java 21 Virtual Threads (Thread-per-task)");
        System.out.println("=============================================================");
        System.out.println("  Ready to process requests. Press Ctrl+C to stop.");

        // Graceful shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[NEXORA] Shutting down HTTP server gracefully...");
            server.stop(1);
            System.out.println("[NEXORA] Server stopped.");
        }));
    }

    /**
     * Stops the server programmatically.
     */
    public void stop() {
        server.stop(0);
    }
}
