package com.nexora.util;

import java.util.*;
import java.util.regex.Pattern;

/**
 * LanguageDetector
 * 
 * Automatically detects the language and script of user messages in NEXORA:
 * 1. English input -> English response.
 * 2. Bengali script input -> Bengali response in Bengali script.
 * 3. Banglish input (Romanized Bengali) -> Bengali response in Bengali script.
 * 4. Mixed Bengali & English input -> Bengali response in Bengali script.
 * 5. Explicit user override -> Follows requested language.
 * 
 * Part of NEXORA - AI-Powered Personal Chatbot
 */
public class LanguageDetector {

    public enum ResponseLanguage {
        ENGLISH("en", "English"),
        BENGALI("bn", "Bengali (বাংলা)");

        private final String code;
        private final String displayName;

        ResponseLanguage(String code, String displayName) {
            this.code = code;
            this.displayName = displayName;
        }

        public String getCode() {
            return code;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    // Explicit request patterns for English
    private static final List<Pattern> EXPLICIT_ENGLISH_PATTERNS = List.of(
            Pattern.compile("(?i)\\b(?:in\\s+english|reply\\s+in\\s+english|respond\\s+in\\s+english|answer\\s+in\\s+english|write\\s+in\\s+english|speak\\s+in\\s+english|use\\s+english|explain\\s+in\\s+english|english\\s+please)\\b"),
            Pattern.compile("(?i)(?:ইংরেজিতে\\s*(?:বলো|বলুন|লিখুন|লিখো|উত্তর|জানাও)|ইংলিশে\\s*(?:বলো|বলুন|লিখুন|লিখো|উত্তর|জানাও))"),
            Pattern.compile("(?i)(?:ইংরেজি\\s*ভাষায়|ইংলিশ\\s*ভাষায়)")
    );

    // Explicit request patterns for Bengali
    private static final List<Pattern> EXPLICIT_BENGALI_PATTERNS = List.of(
            Pattern.compile("(?i)\\b(?:in\\s+bengali|in\\s+bangla|reply\\s+in\\s+bengali|reply\\s+in\\s+bangla|respond\\s+in\\s+bengali|respond\\s+in\\s+bangla|answer\\s+in\\s+bengali|answer\\s+in\\s+bangla|write\\s+in\\s+bengali|write\\s+in\\s+bangla|speak\\s+in\\s+bengali|speak\\s+in\\s+bangla|explain\\s+in\\s+bengali|explain\\s+in\\s+bangla|bangla\\s+please)\\b"),
            Pattern.compile("(?i)\\b(?:banglay\\s*(?:bolo|bolun|likho|likhun)|bangla\\s*te\\s*(?:bolo|bolun|likho|likhun))\\b"),
            Pattern.compile("(?i)(?:বাংলায়\\s*(?:বলো|বলুন|লিখুন|লিখো|উত্তর|জানাও)|বাংলাতে\\s*(?:বলো|বলুন|লিখুন|লিখো|উত্তর|জানাও)|বাংলা\\s*ভাষায়)")
    );

    // Multi-word Banglish phrases
    private static final List<String> BANGLISH_PHRASES = List.of(
            "kemon acho", "kemon achen", "kemon aso", "kemon asen", "ki obostha", "ki khobor",
            "thik ache", "thik ase", "diye banano", "diye toiri", "diye kora", "kono shomossha",
            "kono somossa", "dhonnobad apnake", "amake bolo", "amake bolun", "ami jani", "tumi jano",
            "apni janen", "kivabe korbo", "kibhabe korbo", "eta ki", "ota ki", "shob thik", "sob thik"
    );

    // Distinctive Romanized Bengali (Banglish) vocabulary
    private static final Set<String> BANGLISH_WORDS = Set.of(
            // Question words
            "ki", "kee", "kobe", "kothay", "kothai", "kemon", "keno", "kivabe", "kibhabe", "kon", "konta", "konti", "kader", "kake", "koi", "koto", "kototuku",
            // Pronouns & Demonstratives
            "amar", "amra", "amader", "amake", "ami", "tumi", "tomar", "tomader", "tomake", "apni", "apnar", "apnader", "apnake", "tui", "tor", "toke", 
            "oder", "oke", "ora", "tara", "tader", "take", "eta", "eti", "ota", "oti", "egulo", "ogulo", "sheta", "shei", "oi",
            // Verbs
            "acho", "achho", "achen", "achi", "ase", "ache", "chilo", "chilam", "chile", "chilen", "thako", "thaken", "thakte", "thakbe",
            "hobe", "hoyeche", "hoise", "hocche", "hobar", "koro", "korben", "korchi", "kore", "korte", "korbo", "korechi", "koresi", "korlam", "korle", "korar",
            "banano", "banate", "baniyechi", "banaisi", "banabo", "toiri",
            "bolun", "bolo", "bolte", "bolchi", "bolbo", "bolechi", "boltecho", "dekho", "dekhun", "dekhte", "dekhchi", 
            "shuno", "shunun", "shunte", "shunbo", "jani", "janen", "jano", "janbo", "parbo", "parben", "paro", "pari", "parte", "parlam",
            "dao", "din", "dite", "diye", "diyo", "niye", "neo", "nin", "nite",
            "asho", "ashen", "ashte", "ashbo", "eshechi", "jao", "jan", "jete", "jabo", "gechi", "gesilam",
            "kheyechi", "khabo", "shikhchi", "shikhte", "likhte", "likhun", "porchi", "porte", "bujhte", "bujhechi", "lagbe", "lagche",
            // Particles, Adjectives, Adverbs
            "bhalo", "valo", "khub", "onek", "beshi", "kom", "shob", "sob", "ektu", "kichu", "jeno", "naki", "haa",
            "shathe", "sathe", "shonge", "songe", "theke", "jonno", "jonne", "aar", "ebong", "kintu", "nahole", "tahole", "tai", "karon", "karone",
            "thik", "thikache", "shotti", "sotti", "oboshoy", "oboshshoi", "dhonnobad", "shagotom", "shubho", "shokal", "dupur", "shondha", "raat",
            "ekhon", "kokhon", "tokhon", "shomossha", "somossa", "shobar", "sobar", "shobai", "sobai", "shudhu", "sudhu", "bhabchi", "moner", "shomporke", "somporke",
            "khobor", "obostha", "shobthik", "sobthik"
    );

    /**
     * Detects the target response language strictly from the user's latest prompt.
     * 
     * @param userMessage The latest prompt sent by the user
     * @return ResponseLanguage.ENGLISH or ResponseLanguage.BENGALI
     */
    public static ResponseLanguage detectLanguage(String userMessage) {
        if (userMessage == null || userMessage.trim().isEmpty()) {
            return ResponseLanguage.ENGLISH;
        }

        String trimmed = userMessage.trim();

        // 1. Explicit request override (Rule 5)
        for (Pattern p : EXPLICIT_ENGLISH_PATTERNS) {
            if (p.matcher(trimmed).find()) {
                return ResponseLanguage.ENGLISH;
            }
        }
        for (Pattern p : EXPLICIT_BENGALI_PATTERNS) {
            if (p.matcher(trimmed).find()) {
                return ResponseLanguage.BENGALI;
            }
        }

        // 2. Check for Bengali script characters (Rule 2 & Rule 4)
        if (containsBengaliScript(trimmed)) {
            return ResponseLanguage.BENGALI;
        }

        // 3. Check for Banglish multi-word phrases (Rule 3)
        String lowerText = trimmed.toLowerCase();
        for (String phrase : BANGLISH_PHRASES) {
            if (lowerText.contains(phrase)) {
                return ResponseLanguage.BENGALI;
            }
        }

        // 4. Tokenize and check for Banglish words (Rule 3)
        String[] tokens = lowerText.replaceAll("[^a-z0-9\\s]", " ").split("\\s+");
        int banglishCount = 0;
        for (String token : tokens) {
            if (!token.isEmpty() && BANGLISH_WORDS.contains(token)) {
                banglishCount++;
            }
        }

        if (banglishCount > 0) {
            return ResponseLanguage.BENGALI;
        }

        // 5. Default: English input -> English response (Rule 1)
        return ResponseLanguage.ENGLISH;
    }

    /**
     * Checks if the given text contains any character in the Bengali Unicode block (\u0980 - \u09FF).
     */
    public static boolean containsBengaliScript(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\u0980' && c <= '\u09FF') {
                return true;
            }
        }
        return false;
    }
}
