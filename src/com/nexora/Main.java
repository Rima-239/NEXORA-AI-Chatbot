package com.nexora;

import com.nexora.server.NexoraHttpServer;

import java.util.concurrent.CountDownLatch;

/**
 * Main
 * 
 * Application entry point for NEXORA backend.
 * Compiles cleanly with standard `javac` and runs with zero external dependencies.
 * 
 * Part of NEXORA - AI-Powered Personal Chatbot
 */
public class Main {

    private static final CountDownLatch KEEP_ALIVE = new CountDownLatch(1);

    public static void main(String[] args) {
        try {
            NexoraHttpServer server = new NexoraHttpServer();
            server.start();

            // Register latch release on JVM shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(KEEP_ALIVE::countDown));

            // Keep main thread alive indefinitely until explicit shutdown
            KEEP_ALIVE.await();
        } catch (InterruptedException e) {
            System.out.println("[NEXORA] Server thread interrupted.");
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("[FATAL] Failed to start NEXORA server: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
