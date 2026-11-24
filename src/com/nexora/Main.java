package com.nexora;

import com.nexora.server.NexoraHttpServer;

/**
 * Main
 * 
 * Application entry point for NEXORA backend.
 * Compiles cleanly with standard `javac` and runs with zero external dependencies.
 * 
 * Part of NEXORA - AI-Powered Personal Chatbot
 */
public class Main {

    public static void main(String[] args) {
        try {
            NexoraHttpServer server = new NexoraHttpServer();
            server.start();
            // Keep the main thread alive so daemon virtual threads can continue serving requests
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            System.out.println("[NEXORA] Server interrupted.");
        } catch (Exception e) {
            System.err.println("[FATAL] Failed to start NEXORA server: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
