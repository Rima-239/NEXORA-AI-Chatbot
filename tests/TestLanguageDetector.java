package com.nexora.tests;

import com.nexora.util.LanguageDetector;
import com.nexora.util.LanguageDetector.ResponseLanguage;

public class TestLanguageDetector {

    public static void main(String[] args) {
        System.out.println("[TestLanguageDetector] Running language detection test suite...");

        // 1. Required test cases
        assertLanguage("Explain what NEXORA is.", ResponseLanguage.ENGLISH, "Test 1");
        assertLanguage("NEXORA কী?", ResponseLanguage.BENGALI, "Test 2");
        assertLanguage("NEXORA ki?", ResponseLanguage.BENGALI, "Test 3");
        assertLanguage("Amar project Java 21 diye banano.", ResponseLanguage.BENGALI, "Test 4");
        assertLanguage("What is Personal Memory?", ResponseLanguage.ENGLISH, "Test 5");
        assertLanguage("আমার project সম্পর্কে তুমি কী জানো?", ResponseLanguage.BENGALI, "Test 6");

        // 2. Additional edge cases & mixed inputs
        assertLanguage("Hello! How are you?", ResponseLanguage.ENGLISH, "English greeting");
        assertLanguage("kemon acho?", ResponseLanguage.BENGALI, "Banglish greeting");
        assertLanguage("apni kemon achen?", ResponseLanguage.BENGALI, "Banglish greeting 2");
        assertLanguage("NEXORA project er details bolo", ResponseLanguage.BENGALI, "Mixed Banglish");
        assertLanguage("Can you explain NEXORA in Bengali?", ResponseLanguage.BENGALI, "Explicit Bengali request in English");
        assertLanguage("NEXORA কী? Answer in English please.", ResponseLanguage.ENGLISH, "Explicit English override with Bengali script");
        assertLanguage("ইংরেজিতে বলো NEXORA কী", ResponseLanguage.ENGLISH, "Explicit English override in Bengali script");
        assertLanguage("বাংলায় বলো NEXORA কী", ResponseLanguage.BENGALI, "Explicit Bengali request in Bengali script");
        assertLanguage("How do I use Java 21 virtual threads?", ResponseLanguage.ENGLISH, "Technical English question");

        System.out.println("ALL 15 LanguageDetector assertions PASSED successfully!");
    }

    private static void assertLanguage(String input, ResponseLanguage expected, String label) {
        ResponseLanguage actual = LanguageDetector.detectLanguage(input);
        if (actual != expected) {
            System.err.println("FAIL [" + label + "]: Input: '" + input + "' | Expected: " + expected + " | Actual: " + actual);
            System.exit(1);
        } else {
            System.out.println("PASS [" + label + "]: '" + input + "' -> " + actual);
        }
    }
}
