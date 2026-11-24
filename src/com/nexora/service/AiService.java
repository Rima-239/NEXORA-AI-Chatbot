package com.nexora.service;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * AiService
 * 
 * Standard interface for interacting with LLM providers (Ollama locally, OpenRouter in public mode).
 * 
 * Part of NEXORA - AI-Powered Personal Chatbot
 */
public interface AiService {

    /**
     * Generates a complete, buffered AI response for the conversation.
     * 
     * @param model    The requested model identifier
     * @param messages The conversation history list of role/content maps
     * @param persona  The active persona (general, coding, writing, learning)
     * @return The AI generated response text
     * @throws Exception If network, provider, or timeout errors occur
     */
    String generateCompletion(String model, List<Map<String, String>> messages, String persona) throws Exception;

    /**
     * Streams AI response tokens progressively to the given consumer.
     * 
     * @param model         The requested model identifier
     * @param messages      The conversation history list of role/content maps
     * @param persona       The active persona
     * @param chunkConsumer Called for each progressive text chunk
     * @param errorConsumer Called if an error occurs during streaming
     * @throws Exception    If stream setup fails
     */
    void streamCompletion(String model, List<Map<String, String>> messages, String persona, 
                          Consumer<String> chunkConsumer, Runnable onComplete, Consumer<Throwable> errorConsumer) throws Exception;

    /**
     * Retrieves the list of available model names from this provider.
     */
    List<String> getAvailableModels() throws Exception;

    /**
     * Gets the human-readable provider name (e.g. "Ollama (Local)", "OpenRouter (Public)").
     */
    String getProviderName();

    /**
     * Gets the default model name for this provider.
     */
    String getDefaultModel();
}
