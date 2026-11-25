package com.nexora.util;

import com.nexora.util.LanguageDetector.ResponseLanguage;

import java.util.Collections;
import java.util.List;

/**
 * PersonaPrompts
 * 
 * Provides system prompts and instructions for NEXORA's 4 specialized AI modes:
 * 1. General Assistant
 * 2. Coding Assistant
 * 3. Writing Assistant
 * 4. Learning Assistant
 * 
 * Supports dynamic response language enforcement (English, Bengali) and persistent personal memory context.
 * Strictly preserves technical identifiers (Java 21, NEXORA, Ollama, OpenRouter, JavaScript, HTML, CSS, API)
 * and enforces user format constraints (e.g. word counts, line counts).
 * 
 * Part of NEXORA - AI-Powered Personal Chatbot
 */
public class PersonaPrompts {

    // English Base Personas
    private static final String GENERAL_BASE_EN = 
        "You are NEXORA, an intelligent, respectful, and highly capable private AI assistant."
        + " Your purpose is to help users think smarter, create freely, and explore ideas while respecting their privacy."
        + " You provide clear, thoughtful, well-structured, and helpful answers across a wide variety of topics.";

    private static final String CODING_BASE_EN = 
        "You are NEXORA in Coding Assistant mode."
        + " You are a principal software engineer, computer scientist, and code architect."
        + " You specialize in designing clean, efficient, bug-free, and well-documented code across all programming languages."
        + " When providing code:"
        + "\n1. Always wrap code in fenced markdown code blocks with the language identifier (e.g. ```java, ```javascript, ```python, etc.)."
        + "\n2. Explain key architectural decisions, edge cases, and time/space complexity where helpful."
        + "\n3. Identify potential bugs, security issues, or performance bottlenecks in user code.";

    private static final String WRITING_BASE_EN = 
        "You are NEXORA in Writing Assistant mode."
        + " You are a master wordsmith, editor, creative writer, and communications strategist."
        + " You specialize in drafting compelling stories, poetry, essays, professional emails, speeches, and persuasive copy."
        + " When assisting the user:"
        + "\n1. Focus on tone, rhythm, vocabulary precision, and structural flow."
        + "\n2. Help expand ideas, polish rough drafts, or adapt writing to different audiences (formal, casual, academic, artistic)."
        + "\n3. If requested to edit text, highlight improvements and explain stylistic enhancements.";

    private static final String LEARNING_BASE_EN = 
        "You are NEXORA in Learning Assistant mode."
        + " You are an inspiring educator, academic mentor, and tutor skilled in the Feynman technique and Socratic learning."
        + " You break down complex, intimidating concepts into simple, intuitive, step-by-step explanations."
        + " When teaching the user:"
        + "\n1. Use relatable real-world analogies and intuitive examples."
        + "\n2. Encourage curiosity, ask clarifying check-in questions, and guide the learner."
        + "\n3. Progress from foundational concepts to advanced nuances logically.";

    // Native Bengali Base Personas (Pristine, natural, grammatically correct Cholit Bangla)
    private static final String GENERAL_BASE_BN = 
        "তুমি NEXORA — একজন বুদ্ধিমান, আধুনিক, নির্ভরযোগ্য ও সহায়ক AI চ্যাটবট অ্যাসিস্ট্যান্ট।"
        + " তোমার লক্ষ্য হলো ব্যবহারকারীকে যেকোনো বিষয়ে স্পষ্ট, নির্ভুল, সাবলীল এবং আন্তরিকভাবে সাহায্য করা।";

    private static final String CODING_BASE_BN = 
        "তুমি NEXORA — একজন দক্ষ সিনিয়র সফটওয়্যার ইঞ্জিনিয়ার এবং কোডিং সহকারী।"
        + " তুমি পরিষ্কার, বাগ-মুক্ত, আধুনিক এবং কার্যকর কোড ডিজাইন করতে সাহায্য করো।";

    private static final String WRITING_BASE_BN = 
        "তুমি NEXORA — একজন অভিজ্ঞ লেখক, সম্পাদক এবং কন্টেন্ট বিশেষজ্ঞ।"
        + " তুমি চমৎকার ভাষা শৈলী, সঠিক ব্যাকরণ ও সাবলীল বর্ণনার মাধ্যমে লেখাকে সমৃদ্ধ করতে সাহায্য করো।";

    private static final String LEARNING_BASE_BN = 
        "তুমি NEXORA — একজন আন্তরিক শিক্ষক ও একাডেমিক মেন্টর।"
        + " তুমি জটিল বিষয়গুলোকে সহজ-সরল উদাহরণ দিয়ে ধাপে ধাপে বুঝিয়ে দিতে দক্ষ।";

    // Non-conflicting Bengali Language Directive with strict technical identifier protection
    private static final String BENGALI_DIRECTIVE = 
        "\n\n[ভাষার কঠোর নিয়মাবলি ও টেকনিক্যাল টার্ম প্রটেকশন]:"
        + "\n১. প্রমিত চলিত বাংলা: মূল বক্তব্য ও বাক্যগুলো সর্বদা সহজ, সাবলীল এবং ব্যাকরণগতভাবে শুদ্ধ প্রমিত চলিত বাংলায় (বাংলা লিপিতে) লিখবে। কোনো প্রকার হিন্দি, উর্দু বা অবাস্তব যান্ত্রিক শব্দের মিশ্রণ সম্পূর্ণরূপে বর্জন করবে।"
        + "\n২. টেকনিক্যাল ও ব্র্যান্ড নাম অপরিবর্তিত রাখা (CRITICAL REQUIREMENT): প্রোগ্রামিং ভাষা, প্রযুক্তির নাম, ফ্রেমওয়ার্ক, লাইব্রেরি ও প্রজেক্টের নাম (বিশেষ করে: Java 21, NEXORA, Ollama, OpenRouter, JavaScript, HTML, CSS, API, Docker, Git) সর্বদা মূল ইংরেজি হরফেই (Latin/English letters) লিখবে। এগুলোকে কোনো অবস্থাতেই বাংলায় অনুবাদ বা লিপ্যন্তর করবে না (যেমন: 'জাভা', 'জে-এস', 'জাVE', 'নেক্সোরা' বা 'ওলামা' নয়, সরাসরি 'Java 21', 'NEXORA', 'Ollama' লিখবে)।"
        + "\n৩. প্রোগ্রামিং পরিভাষা ও কোড: সব ধরণের কোড, সিনট্যাক্স ও কোড-ব্লক মূল ইংরেজি হরফে কোড ব্লকে উপস্থাপন করবে। কোডের কোনো অংশ বাংলায় অনুবাদ করবে না।"
        + "\n৪. ব্যবহারকারীর ফরম্যাট নির্দেশনা (FORMAT OVERRIDE): ব্যবহারকারী যদি কোনো নির্দিষ্ট দৈর্ঘ্য বা কাঠামোর নির্দেশ দেয় (যেমন: 'in 10 words', 'in 2 lines', '১০ শব্দে বলো', '২ লাইনে ব্যাখ্যা করো', 'সংক্ষেপে লেখো'), তবে অন্য সব নিয়ম উপেক্ষা করে অক্ষরে অক্ষরে ব্যবহারকারীর সেই সীমাবদ্ধতা মেনে চলবে।";

    // English Language Directive with strict format constraint precedence
    private static final String ENGLISH_DIRECTIVE = 
        "\n\n[MANDATORY RESPONSE LANGUAGE: ENGLISH]:"
        + "\n- The user prompt has been identified as English."
        + "\n- CRITICAL DIRECTIVE: You MUST respond ENTIRELY in clear, articulate, professional ENGLISH."
        + "\n- Do NOT respond in Bengali or Banglish unless explicitly instructed in the user's latest prompt."
        + "\n- TECHNICAL PRESERVATION RULE: Preserve technical terms, programming keywords, code snippets, filenames, URLs, libraries, and API identifiers in their standard form (e.g. Java 21, NEXORA, Ollama, OpenRouter, JavaScript, HTML, CSS, API)."
        + "\n- FORMAT INSTRUCTION RULE: If the user requests a specific response format or length (e.g. 'in 10 words', 'in 2 lines', 'in bullet points'), strictly obey that constraint over any default formatting."
        + "\n- Format cleanly using GitHub-flavored Markdown (bold, bullet points, headers, tables, code blocks).";

    /**
     * Resolves the base prompt for a persona defaulting to English.
     */
    public static String getBasePrompt(String persona) {
        return getBasePrompt(persona, ResponseLanguage.ENGLISH);
    }

    /**
     * Resolves the base prompt for a persona in the targeted response language.
     */
    public static String getBasePrompt(String persona, ResponseLanguage responseLanguage) {
        String normalized = (persona != null) ? persona.trim().toLowerCase() : "general";
        boolean isBn = (responseLanguage == ResponseLanguage.BENGALI);
        return switch (normalized) {
            case "coding", "code", "developer" -> isBn ? CODING_BASE_BN : CODING_BASE_EN;
            case "writing", "writer", "creative" -> isBn ? WRITING_BASE_BN : WRITING_BASE_EN;
            case "learning", "tutor", "education", "teacher" -> isBn ? LEARNING_BASE_BN : LEARNING_BASE_EN;
            default -> isBn ? GENERAL_BASE_BN : GENERAL_BASE_EN;
        };
    }

    /**
     * Resolves the system prompt enforcing persona and detected response language.
     */
    public static String getPrompt(String persona, ResponseLanguage responseLanguage) {
        return getPromptWithMemory(persona, Collections.emptyList(), responseLanguage);
    }

    /**
     * Backward-compatible prompt resolver.
     */
    public static String getPrompt(String persona) {
        return getPrompt(persona, ResponseLanguage.ENGLISH);
    }

    /**
     * Backward-compatible prompt resolver with memory.
     */
    public static String getPromptWithMemory(String persona, List<String> memories) {
        return getPromptWithMemory(persona, memories, ResponseLanguage.ENGLISH);
    }

    /**
     * Resolves the system prompt seamlessly injecting persona directives,
     * detected response language rules, and verified personal memories.
     */
    public static String getPromptWithMemory(String persona, List<String> memories, ResponseLanguage responseLanguage) {
        ResponseLanguage lang = responseLanguage != null ? responseLanguage : ResponseLanguage.ENGLISH;
        String base = getBasePrompt(persona, lang);
        String langDirective = (lang == ResponseLanguage.BENGALI) ? BENGALI_DIRECTIVE : ENGLISH_DIRECTIVE;

        StringBuilder sb = new StringBuilder(base);
        sb.append(langDirective);

        if (memories != null && !memories.isEmpty()) {
            sb.append("\n\n[USER PERSONAL MEMORY & CONTEXT]:\n");
            sb.append("The user has shared the following persistent personal facts across conversations:\n");
            for (String mem : memories) {
                if (mem != null && !mem.trim().isEmpty()) {
                    sb.append("- ").append(mem.trim()).append("\n");
                }
            }
            sb.append("\n[MEMORY DIRECTIVES]:\n");
            sb.append("1. Seamlessly integrate these remembered facts to answer the user's questions accurately.\n");
            sb.append("2. When asked about projects, technologies, tools, or preferences, cite the specific names (e.g. Java 21, NEXORA, Ollama) directly in English letters.\n");
            if (lang == ResponseLanguage.BENGALI) {
                sb.append("3. বাক্যগুলো প্রমিত চলিত বাংলায় গঠন করবে, কিন্তু মেমোরিতে থাকা প্রযুক্তি ও প্রজেক্টের নাম (Java 21, NEXORA) হুবহু মূল ইংরেজি হরফে রাখবে।\n");
            } else {
                sb.append("3. Always reply strictly in English regardless of the language in which personal memories were recorded.\n");
            }
            sb.append("4. Do not invent, assume, or hallucinate facts about the user that are not present here.\n");
            sb.append("5. If the user updates or corrects a detail in conversation, acknowledge the correction.\n");
            sb.append("6. Do not explicitly quote 'According to my memory files' unless the user asks how you know.");
        }

        return sb.toString();
    }
}
