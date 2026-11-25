package com.nexora.tests;

import com.nexora.service.OllamaService;
import com.nexora.service.OpenRouterService;
import com.nexora.util.PersonaPrompts;

import java.util.List;

public class TestMemoryContext {

    public static void main(String[] args) {
        System.out.println("[TestMemoryContext] Validating memory context integration...");

        // 1. Validate PersonaPrompts formatting
        List<String> memories = List.of("Favorite project is NEXORA in Java 21");
        String formatted = PersonaPrompts.getPromptWithMemory("general", memories);

        if (!formatted.contains("[USER PERSONAL MEMORY & CONTEXT]")) {
            System.err.println("FAIL: Missing memory header in PersonaPrompts");
            System.exit(1);
        }
        if (!formatted.contains("Favorite project is NEXORA in Java 21")) {
            System.err.println("FAIL: Missing memory item in PersonaPrompts");
            System.exit(1);
        }
        if (!formatted.contains("[MEMORY DIRECTIVES]")) {
            System.err.println("FAIL: Missing memory directives in PersonaPrompts");
            System.exit(1);
        }

        // 2. Validate Ollama & OpenRouter provider services instantiate properly
        OllamaService ollama = new OllamaService();
        if (ollama.getProviderName() == null || ollama.getDefaultModel() == null) {
            System.err.println("FAIL: OllamaService provider invalid");
            System.exit(1);
        }

        OpenRouterService openRouter = new OpenRouterService();
        if (openRouter.getProviderName() == null || openRouter.getDefaultModel() == null) {
            System.err.println("FAIL: OpenRouterService provider invalid");
            System.exit(1);
        }

        System.out.println("PASS: Ollama and OpenRouter context integration verified successfully.");
        System.exit(0);
    }
}
