package com.nexora.service;

import com.nexora.util.LanguageDetector.ResponseLanguage;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * AiService
 * 
 * Standard interface for interacting with LLM providers (Ollama locally, OpenRouter in public mode).
 * Supports persona instructions, persistent cross-chat personal memories, and automatic language matching.
 * 
 * Part of NEXORA - AI-Powered Personal Chatbot
 */
public interface AiService {

    /**
     * Generates a complete, buffered AI response for the conversation with personal memory context and detected response language.
     * 
     * @param model            The requested model identifier
     * @param messages         The conversation history list of role/content maps
     * @param persona          The active persona (general, coding, writing, learning)
     * @param memories         Verified user personal memories relevant to the prompt
     * @param responseLanguage The automatically detected response language (English or Bengali)
     * @return The AI generated response text
     * @throws Exception If network, provider, or timeout errors occur
     */
    String generateCompletion(String model, List<Map<String, String>> messages, String persona, List<String> memories, ResponseLanguage responseLanguage) throws Exception;

    /**
     * Streams AI response tokens progressively to the given consumer with personal memory context and detected response language.
     * 
     * @param model            The requested model identifier
     * @param messages         The conversation history list of role/content maps
     * @param persona          The active persona
     * @param memories         Verified user personal memories relevant to the prompt
     * @param responseLanguage The automatically detected response language
     * @param chunkConsumer    Called for each progressive text chunk
     * @param errorConsumer    Called if an error occurs during streaming
     * @throws Exception If stream setup fails
     */
    void streamCompletion(String model, List<Map<String, String>> messages, String persona, List<String> memories, ResponseLanguage responseLanguage,
                          Consumer<String> chunkConsumer, Runnable onComplete, Consumer<Throwable> errorConsumer) throws Exception;

    /**
     * Backward-compatible buffered completion with default language (English).
     */
    default String generateCompletion(String model, List<Map<String, String>> messages, String persona, List<String> memories) throws Exception {
        return generateCompletion(model, messages, persona, memories, ResponseLanguage.ENGLISH);
    }

    /**
     * Backward-compatible streaming completion with default language (English).
     */
    default void streamCompletion(String model, List<Map<String, String>> messages, String persona, List<String> memories,
                                  Consumer<String> chunkConsumer, Runnable onComplete, Consumer<Throwable> errorConsumer) throws Exception {
        streamCompletion(model, messages, persona, memories, ResponseLanguage.ENGLISH, chunkConsumer, onComplete, errorConsumer);
    }

    /**
     * Backward-compatible buffered completion without memories.
     */
    default String generateCompletion(String model, List<Map<String, String>> messages, String persona) throws Exception {
        return generateCompletion(model, messages, persona, Collections.emptyList(), ResponseLanguage.ENGLISH);
    }

    /**
     * Backward-compatible streaming completion without memories.
     */
    default void streamCompletion(String model, List<Map<String, String>> messages, String persona, 
                                  Consumer<String> chunkConsumer, Runnable onComplete, Consumer<Throwable> errorConsumer) throws Exception {
        streamCompletion(model, messages, persona, Collections.emptyList(), ResponseLanguage.ENGLISH, chunkConsumer, onComplete, errorConsumer);
    }

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
