package com.nexora.service;

import com.nexora.model.UserMemory;
import com.nexora.server.AppConfig;
import com.nexora.util.SimpleJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MemoryService
 * 
 * Manages persistent cross-chat personal memories for NEXORA.
 * 
 * Key Features:
 * - Isolated per-user storage in JSON files (<memoryDir>/<userId>.json)
 * - Safe atomic file writes preventing accidental corruption
 * - Automatic extraction of long-term preferences, projects, languages, tools, and goals
 * - Secret and sensitive credential scanning to prevent saving private keys/passwords
 * - Relevance-based cross-chat retrieval for AI prompt injection
 * - Manual memory CRUD operations and auto-extraction toggling
 * 
 * Part of NEXORA - AI-Powered Personal Chatbot
 */
public class MemoryService {

    private static final MemoryService INSTANCE = new MemoryService();

    private final Path storageDir;
    private final ConcurrentHashMap<String, Object> userLocks = new ConcurrentHashMap<>();

    // Sensitive data filters (API keys, passwords, bearer tokens, private keys)
    private static final List<Pattern> SECRET_PATTERNS = List.of(
            Pattern.compile("(?i)(?:password|passwd|pwd)\\s*[:=]\\s*\\S+"),
            Pattern.compile("(?i)(?:bearer\\s+[a-zA-Z0-9_\\-\\.]{12,}|sk-[a-zA-Z0-9_\\-]{15,}|ghp_[a-zA-Z0-9_\\-]{15,})"),
            Pattern.compile("(?i)(?:api[_-]?key|secret[_-]?key|auth[_-]?token|access[_-]?token)\\s*[:=]\\s*\\S+"),
            Pattern.compile("(?i)(?:-----BEGIN\\s+(?:RSA\\s+)?PRIVATE\\s+KEY-----|ssh-rsa\\s+[A-Za-z0-9+/]+)")
    );

    // Extraction patterns
    private static final Pattern PATTERN_FAVORITE_PROJECT = Pattern.compile(
            "(?i)(?:my\\s+(?:favorite|primary|main|current)\\s+project\\s+is\\s+([^,.!?\n]+?)(?:[,.]?\\s*(?:and\\s*|which\\s*)?(?:i\\s*am|i'm)?\\s*(?:building|developing|writing|coding|working\\s+on|creating)\\s+(?:it\\s+)?(?:using|with|in)\\s+([^,.!?\n]+))?(?:[.!?\n]|$))",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern PATTERN_BUILDING_WITH = Pattern.compile(
            "(?i)i\\s+(?:am\\s+building|am\\s+working\\s+on|build|'m\\s+building|'m\\s+working\\s+on)\\s+([^,.!?\n]+?)\\s+(?:using|with|in)\\s+([^,.!?\n]+)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern PATTERN_PROGRAMMING_LANG = Pattern.compile(
            "(?i)(?:my\\s+(?:primary|favorite|main)\\s+(?:programming\\s+)?language\\s+is\\s+([^,.!?\n]+))",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern PATTERN_DEVELOPS_IN = Pattern.compile(
            "(?i)i\\s+(?:code|program|develop)\\s+(?:primarily\\s+)?in\\s+([^,.!?\n]+)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern PATTERN_PREFERENCE = Pattern.compile(
            "(?i)i\\s+prefer\\s+([^,.!?\n]+?)(?:\\s+over\\s+([^,.!?\n]+))?[.!?]?$",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern PATTERN_EXPLICIT_REMEMBER = Pattern.compile(
            "(?i)(?:remember\\s+(?:that\\s+)?|note\\s+that\\s+|keep\\s+in\\s+mind\\s+that\\s+)(.+)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern PATTERN_LEARNING = Pattern.compile(
            "(?i)i\\s+am\\s+learning\\s+([^,.\n]+)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern PATTERN_TOOL_USAGE = Pattern.compile(
            "(?i)i\\s+(?:regularly\\s+|always\\s+|frequently\\s+)?use\\s+([^,.\n]+)\\s+for\\s+([^,.\n]+)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern PATTERN_USER_NAME = Pattern.compile(
            "(?i)my\\s+name\\s+is\\s+([A-Za-z\\s]+)",
            Pattern.CASE_INSENSITIVE
    );

    private MemoryService() {
        this.storageDir = Path.of(AppConfig.getMemoryDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.storageDir);
        } catch (IOException e) {
            System.err.println("[MemoryService] Warning: Could not create memory storage directory: " + e.getMessage());
        }
    }

    public static MemoryService getInstance() {
        return INSTANCE;
    }

    /**
     * Sanitizes and validates user ID to enforce strict per-user file isolation and prevent directory traversal.
     */
    public String sanitizeUserId(String rawUserId) {
        if (rawUserId == null || rawUserId.trim().isEmpty()) {
            return "default";
        }
        String clean = rawUserId.trim();
        if (!clean.matches("^[a-zA-Z0-9_\\-]{1,64}$")) {
            throw new IllegalArgumentException("Invalid userId. Must be alphanumeric with hyphens or underscores (1-64 characters).");
        }
        return clean;
    }

    private Object getLock(String userId) {
        return userLocks.computeIfAbsent(userId, k -> new Object());
    }

    private Path getUserFilePath(String userId) {
        return storageDir.resolve(userId + ".json");
    }

    /**
     * Checks if given text contains potential secrets or credentials.
     */
    public boolean containsSecrets(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        for (Pattern p : SECRET_PATTERNS) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Loads the stored memory document for a user.
     */
    @SuppressWarnings("unchecked")
    private UserData loadUserData(String userId) {
        String cleanUserId = sanitizeUserId(userId);
        Path file = getUserFilePath(cleanUserId);

        synchronized (getLock(cleanUserId)) {
            if (!Files.exists(file)) {
                return new UserData(cleanUserId, true, new ArrayList<>());
            }

            try {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                Map<String, Object> data = SimpleJson.parseObject(content);
                boolean autoMemoryEnabled = SimpleJson.getBoolean(data, "autoMemoryEnabled", true);

                List<UserMemory> list = new ArrayList<>();
                List<Object> rawList = SimpleJson.getList(data, "memories");
                for (Object item : rawList) {
                    if (item instanceof Map<?, ?> m) {
                        UserMemory mem = UserMemory.fromMap((Map<String, Object>) m);
                        if (mem != null && !mem.getText().isEmpty()) {
                            list.add(mem);
                        }
                    }
                }
                return new UserData(cleanUserId, autoMemoryEnabled, list);

            } catch (Exception e) {
                System.err.println("[MemoryService] Corrupted memory file for user " + cleanUserId + ": " + e.getMessage());
                // Create backup of corrupted file
                try {
                    Path backup = storageDir.resolve(cleanUserId + ".corrupt." + System.currentTimeMillis() + ".json");
                    Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                }
                return new UserData(cleanUserId, true, new ArrayList<>());
            }
        }
    }

    /**
     * Atomically saves user memory data to disk.
     */
    private void saveUserData(UserData userData) throws IOException {
        String cleanUserId = sanitizeUserId(userData.userId);
        Path targetFile = getUserFilePath(cleanUserId);
        Path tempFile = storageDir.resolve(cleanUserId + ".tmp." + System.currentTimeMillis());

        synchronized (getLock(cleanUserId)) {
            Files.createDirectories(storageDir);

            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("userId", cleanUserId);
            doc.put("autoMemoryEnabled", userData.autoMemoryEnabled);
            doc.put("updatedAt", System.currentTimeMillis());

            List<Map<String, Object>> memList = new ArrayList<>();
            for (UserMemory mem : userData.memories) {
                memList.add(mem.toMap());
            }
            doc.put("memories", memList);

            String json = SimpleJson.toJson(doc);
            Files.writeString(tempFile, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            try {
                Files.move(tempFile, targetFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Retrieves all memories for a user.
     */
    public List<UserMemory> getMemories(String userId) {
        return loadUserData(userId).memories;
    }

    /**
     * Checks if auto memory extraction is enabled for a user.
     */
    public boolean isAutoMemoryEnabled(String userId) {
        return loadUserData(userId).autoMemoryEnabled;
    }

    /**
     * Enables or disables auto memory extraction for a user.
     */
    public void setAutoMemoryEnabled(String userId, boolean enabled) throws IOException {
        String cleanUserId = sanitizeUserId(userId);
        synchronized (getLock(cleanUserId)) {
            UserData data = loadUserData(cleanUserId);
            data.autoMemoryEnabled = enabled;
            saveUserData(data);
        }
    }

    /**
     * Manually adds a memory for a user.
     */
    public UserMemory addMemory(String userId, String text, String category, String source) throws IOException {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Memory text cannot be empty.");
        }
        String cleanText = text.trim();
        if (cleanText.length() > 500) {
            cleanText = cleanText.substring(0, 500);
        }
        if (containsSecrets(cleanText)) {
            throw new IllegalArgumentException("Cannot save memory containing credentials, tokens, or passwords.");
        }

        String cleanUserId = sanitizeUserId(userId);
        synchronized (getLock(cleanUserId)) {
            UserData data = loadUserData(cleanUserId);
            UserMemory memory = UserMemory.create(cleanText, category, source);
            data.memories.add(memory);
            saveUserData(data);
            return memory;
        }
    }

    /**
     * Updates an existing memory item.
     */
    public UserMemory updateMemory(String userId, String memoryId, String newText, String newCategory) throws IOException {
        if (memoryId == null || memoryId.trim().isEmpty()) {
            throw new IllegalArgumentException("Memory ID is required.");
        }
        if (newText == null || newText.trim().isEmpty()) {
            throw new IllegalArgumentException("Memory text cannot be empty.");
        }
        String cleanText = newText.trim();
        if (containsSecrets(cleanText)) {
            throw new IllegalArgumentException("Cannot update memory to contain credentials, tokens, or passwords.");
        }

        String cleanUserId = sanitizeUserId(userId);
        synchronized (getLock(cleanUserId)) {
            UserData data = loadUserData(cleanUserId);
            for (UserMemory mem : data.memories) {
                if (mem.getId().equals(memoryId.trim())) {
                    mem.setText(cleanText);
                    if (newCategory != null && !newCategory.trim().isEmpty()) {
                        mem.setCategory(newCategory);
                    }
                    saveUserData(data);
                    return mem;
                }
            }
            throw new NoSuchElementException("Memory with ID " + memoryId + " not found.");
        }
    }

    /**
     * Deletes a single memory item by ID.
     */
    public boolean deleteMemory(String userId, String memoryId) throws IOException {
        if (memoryId == null || memoryId.trim().isEmpty()) {
            return false;
        }
        String cleanUserId = sanitizeUserId(userId);
        synchronized (getLock(cleanUserId)) {
            UserData data = loadUserData(cleanUserId);
            boolean removed = data.memories.removeIf(m -> m.getId().equals(memoryId.trim()));
            if (removed) {
                saveUserData(data);
            }
            return removed;
        }
    }

    /**
     * Clears all memories for a user.
     */
    public void clearAllMemories(String userId) throws IOException {
        String cleanUserId = sanitizeUserId(userId);
        synchronized (getLock(cleanUserId)) {
            UserData data = loadUserData(cleanUserId);
            data.memories.clear();
            saveUserData(data);
        }
    }

    private String cleanExtractedValue(String val) {
        if (val == null) {
            return "";
        }
        return val.replaceAll("[.!?]+$", "").trim();
    }

    /**
     * Extracts a candidate personal memory from a user message in-memory without persisting to disk.
     * Used in PUBLIC mode for stateless, client-isolated operation.
     */
    public UserMemory extractCandidateMemory(String userMessage) {
        if (userMessage == null || userMessage.trim().isEmpty()) {
            return null;
        }
        String cleanMsg = userMessage.trim();

        // 1. Secret scanning
        if (containsSecrets(cleanMsg)) {
            return null;
        }

        // 2. Pattern Matching & Extraction
        String extractedText = null;
        String category = "general";

        // Scenario: "My favorite project is NEXORA, and I am building it using Java 21."
        Matcher mFavProj = PATTERN_FAVORITE_PROJECT.matcher(cleanMsg);
        if (mFavProj.find()) {
            String projName = cleanExtractedValue(mFavProj.group(1));
            String tech = cleanExtractedValue(mFavProj.group(2));
            if (!projName.isEmpty()) {
                category = "project";
                if (!tech.isEmpty()) {
                    extractedText = "Favorite project is " + projName + ", built using " + tech;
                } else {
                    Matcher mBuildExtra = PATTERN_BUILDING_WITH.matcher(cleanMsg);
                    if (mBuildExtra.find() && mBuildExtra.group(2) != null) {
                        String extraTech = cleanExtractedValue(mBuildExtra.group(2));
                        if (!extraTech.isEmpty()) {
                            extractedText = "Favorite project is " + projName + ", built using " + extraTech;
                        } else {
                            extractedText = "Favorite project is " + projName;
                        }
                    } else {
                        extractedText = "Favorite project is " + projName;
                    }
                }
            }
        }

        if (extractedText == null) {
            Matcher mBuild = PATTERN_BUILDING_WITH.matcher(cleanMsg);
            if (mBuild.find()) {
                String proj = cleanExtractedValue(mBuild.group(1));
                String tech = cleanExtractedValue(mBuild.group(2));
                if (!proj.isEmpty() && !tech.isEmpty()) {
                    category = "project";
                    extractedText = "Building " + proj + " using " + tech;
                }
            }
        }

        if (extractedText == null) {
            Matcher mLang = PATTERN_PROGRAMMING_LANG.matcher(cleanMsg);
            if (mLang.find()) {
                String lang = cleanExtractedValue(mLang.group(1));
                if (!lang.isEmpty()) {
                    category = "skill";
                    extractedText = "Primary programming language is " + lang;
                }
            }
        }

        if (extractedText == null) {
            Matcher mDev = PATTERN_DEVELOPS_IN.matcher(cleanMsg);
            if (mDev.find()) {
                String lang = cleanExtractedValue(mDev.group(1));
                if (!lang.isEmpty()) {
                    category = "skill";
                    extractedText = "Develops primarily in " + lang;
                }
            }
        }

        if (extractedText == null) {
            Matcher mPref = PATTERN_PREFERENCE.matcher(cleanMsg);
            if (mPref.find()) {
                String pref1 = cleanExtractedValue(mPref.group(1));
                String pref2 = cleanExtractedValue(mPref.group(2));
                if (!pref1.isEmpty()) {
                    category = "preference";
                    if (!pref2.isEmpty()) {
                        extractedText = "Prefers " + pref1 + " over " + pref2;
                    } else {
                        extractedText = "Prefers " + pref1;
                    }
                }
            }
        }

        if (extractedText == null) {
            Matcher mLearn = PATTERN_LEARNING.matcher(cleanMsg);
            if (mLearn.find()) {
                String topic = mLearn.group(1).trim();
                category = "goal";
                extractedText = "Currently learning " + topic;
            }
        }

        if (extractedText == null) {
            Matcher mTool = PATTERN_TOOL_USAGE.matcher(cleanMsg);
            if (mTool.find()) {
                String tool = mTool.group(1).trim();
                String useFor = mTool.group(2).trim();
                category = "tool";
                extractedText = "Uses " + tool + " for " + useFor;
            }
        }

        if (extractedText == null) {
            Matcher mExplicit = PATTERN_EXPLICIT_REMEMBER.matcher(cleanMsg);
            if (mExplicit.find()) {
                String fact = mExplicit.group(1).trim();
                if (!fact.isEmpty()) {
                    category = "general";
                    extractedText = fact;
                }
            }
        }

        if (extractedText == null) {
            Matcher mName = PATTERN_USER_NAME.matcher(cleanMsg);
            if (mName.find()) {
                String name = mName.group(1).trim();
                category = "general";
                extractedText = "User's name is " + name;
            }
        }

        if (extractedText == null || extractedText.trim().isEmpty()) {
            return null;
        }

        extractedText = extractedText.replaceAll("[.!?]+$", "").trim();
        return UserMemory.create(extractedText, category, "auto");
    }

    /**
     * Automatically extracts personal memory facts from a user message.
     * Returns the created/updated UserMemory if an explicit fact was extracted, or null.
     */
    public UserMemory autoExtractAndSave(String userId, String userMessage) {
        if (AppConfig.isPublicMode()) {
            // Never write to server disk in PUBLIC mode
            return null;
        }

        if (userMessage == null || userMessage.trim().isEmpty()) {
            return null;
        }
        String cleanMsg = userMessage.trim();

        // 1. Secret scanning
        if (containsSecrets(cleanMsg)) {
            return null;
        }

        // 2. Check if auto memory is enabled for this user
        if (!isAutoMemoryEnabled(userId)) {
            return null;
        }

        // 3. Pattern Matching & Extraction
        String extractedText = null;
        String category = "general";

        // Scenario: "My favorite project is NEXORA, and I am building it using Java 21."
        Matcher mFavProj = PATTERN_FAVORITE_PROJECT.matcher(cleanMsg);
        if (mFavProj.find()) {
            String projName = cleanExtractedValue(mFavProj.group(1));
            String tech = cleanExtractedValue(mFavProj.group(2));
            if (!projName.isEmpty()) {
                category = "project";
                if (!tech.isEmpty()) {
                    extractedText = "Favorite project is " + projName + ", built using " + tech;
                } else {
                    Matcher mBuildExtra = PATTERN_BUILDING_WITH.matcher(cleanMsg);
                    if (mBuildExtra.find() && mBuildExtra.group(2) != null) {
                        String extraTech = cleanExtractedValue(mBuildExtra.group(2));
                        if (!extraTech.isEmpty()) {
                            extractedText = "Favorite project is " + projName + ", built using " + extraTech;
                        } else {
                            extractedText = "Favorite project is " + projName;
                        }
                    } else {
                        extractedText = "Favorite project is " + projName;
                    }
                }
            }
        }

        if (extractedText == null) {
            Matcher mBuild = PATTERN_BUILDING_WITH.matcher(cleanMsg);
            if (mBuild.find()) {
                String proj = cleanExtractedValue(mBuild.group(1));
                String tech = cleanExtractedValue(mBuild.group(2));
                if (!proj.isEmpty() && !tech.isEmpty()) {
                    category = "project";
                    extractedText = "Building " + proj + " using " + tech;
                }
            }
        }

        if (extractedText == null) {
            Matcher mLang = PATTERN_PROGRAMMING_LANG.matcher(cleanMsg);
            if (mLang.find()) {
                String lang = cleanExtractedValue(mLang.group(1));
                if (!lang.isEmpty()) {
                    category = "skill";
                    extractedText = "Primary programming language is " + lang;
                }
            }
        }

        if (extractedText == null) {
            Matcher mDev = PATTERN_DEVELOPS_IN.matcher(cleanMsg);
            if (mDev.find()) {
                String lang = cleanExtractedValue(mDev.group(1));
                if (!lang.isEmpty()) {
                    category = "skill";
                    extractedText = "Develops primarily in " + lang;
                }
            }
        }

        if (extractedText == null) {
            Matcher mPref = PATTERN_PREFERENCE.matcher(cleanMsg);
            if (mPref.find()) {
                String pref1 = cleanExtractedValue(mPref.group(1));
                String pref2 = cleanExtractedValue(mPref.group(2));
                if (!pref1.isEmpty()) {
                    category = "preference";
                    if (!pref2.isEmpty()) {
                        extractedText = "Prefers " + pref1 + " over " + pref2;
                    } else {
                        extractedText = "Prefers " + pref1;
                    }
                }
            }
        }

        if (extractedText == null) {
            Matcher mLearn = PATTERN_LEARNING.matcher(cleanMsg);
            if (mLearn.find()) {
                String topic = mLearn.group(1).trim();
                category = "goal";
                extractedText = "Currently learning " + topic;
            }
        }

        if (extractedText == null) {
            Matcher mTool = PATTERN_TOOL_USAGE.matcher(cleanMsg);
            if (mTool.find()) {
                String tool = mTool.group(1).trim();
                String useFor = mTool.group(2).trim();
                category = "tool";
                extractedText = "Uses " + tool + " for " + useFor;
            }
        }

        if (extractedText == null) {
            Matcher mRem = PATTERN_EXPLICIT_REMEMBER.matcher(cleanMsg);
            if (mRem.find()) {
                String fact = mRem.group(1).trim();
                // Strip punctuation at end
                fact = fact.replaceAll("[.!?]+$", "");
                if (fact.length() >= 3) {
                    category = "general";
                    extractedText = fact;
                }
            }
        }

        if (extractedText == null) {
            Matcher mName = PATTERN_USER_NAME.matcher(cleanMsg);
            if (mName.find()) {
                String name = mName.group(1).trim();
                category = "general";
                extractedText = "User's name is " + name;
            }
        }

        if (extractedText == null || extractedText.trim().isEmpty()) {
            return null;
        }

        // Clean up extracted text
        extractedText = extractedText.replaceAll("[.!?]+$", "").trim();

        // 4. Update existing memory if on same topic, or add new
        String cleanUserId = sanitizeUserId(userId);
        synchronized (getLock(cleanUserId)) {
            UserData data = loadUserData(cleanUserId);

            // Check if existing memory matches key concept (e.g., favorite project or primary language)
            UserMemory targetToUpdate = null;
            String lowerNew = extractedText.toLowerCase();

            for (UserMemory existing : data.memories) {
                String lowerEx = existing.getText().toLowerCase();
                if (lowerNew.contains("favorite project") && lowerEx.contains("favorite project")) {
                    targetToUpdate = existing;
                    break;
                }
                if (lowerNew.contains("primary programming language") && lowerEx.contains("primary programming language")) {
                    targetToUpdate = existing;
                    break;
                }
                if (lowerNew.equals(lowerEx)) {
                    // Exact match already exists, no need to add duplicate
                    return existing;
                }
            }

            try {
                if (targetToUpdate != null) {
                    targetToUpdate.setText(extractedText);
                    targetToUpdate.setCategory(category);
                    saveUserData(data);
                    return targetToUpdate;
                } else {
                    UserMemory newMem = UserMemory.create(extractedText, category, "auto");
                    data.memories.add(newMem);
                    saveUserData(data);
                    return newMem;
                }
            } catch (IOException e) {
                System.err.println("[MemoryService] Failed to save auto-extracted memory: " + e.getMessage());
                return null;
            }
        }
    }

    /**
     * Retrieves memory strings relevant to a user query for injection into the AI prompt.
     */
    public List<String> getRelevantMemoryTexts(String userId, String userQuery) {
        List<UserMemory> all = getMemories(userId);
        if (all.isEmpty()) {
            return Collections.emptyList();
        }

        // Tokenize query into meaningful search terms
        Set<String> queryWords = extractKeywords(userQuery);

        // Score each memory
        List<ScoredMemory> scored = new ArrayList<>();
        for (UserMemory mem : all) {
            int score = scoreMemory(mem, queryWords);
            scored.add(new ScoredMemory(mem, score));
        }

        // Sort descending by score, then newest first
        scored.sort((a, b) -> {
            if (b.score != a.score) {
                return Integer.compare(b.score, a.score);
            }
            return Long.compare(b.memory.getUpdatedAt(), a.memory.getUpdatedAt());
        });

        // If total memories is small (<= 15), include all of them sorted by relevance
        // If larger, include top 8 with score > 0 (or fallback to top 5 general)
        List<String> result = new ArrayList<>();
        int limit = all.size() <= 15 ? all.size() : 8;

        for (int i = 0; i < Math.min(limit, scored.size()); i++) {
            ScoredMemory sm = scored.get(i);
            result.add(sm.memory.getText());
        }

        return result;
    }

    private int scoreMemory(UserMemory mem, Set<String> queryWords) {
        if (queryWords.isEmpty()) {
            return 1;
        }
        int score = 0;
        String textLower = mem.getText().toLowerCase();
        String catLower = mem.getCategory().toLowerCase();

        for (String w : queryWords) {
            if (textLower.contains(w)) {
                score += 3;
            }
            if (catLower.equals(w)) {
                score += 2;
            }
        }

        // Higher weight if asking specifically about project and memory is a project
        if (queryWords.contains("project") && (catLower.contains("project") || textLower.contains("project"))) {
            score += 4;
        }
        if ((queryWords.contains("language") || queryWords.contains("programming")) && (catLower.contains("skill") || textLower.contains("java") || textLower.contains("language"))) {
            score += 4;
        }

        return score;
    }

    private Set<String> extractKeywords(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> stopwords = Set.of(
                "a", "an", "the", "in", "on", "at", "to", "for", "of", "and", "or", "is", "are", "am", 
                "was", "were", "what", "which", "who", "whom", "this", "that", "it", "my", "your", "me"
        );

        String[] parts = text.toLowerCase().replaceAll("[^a-z0-9\\s]", " ").split("\\s+");
        Set<String> keywords = new HashSet<>();
        for (String p : parts) {
            String clean = p.trim();
            if (clean.length() >= 2 && !stopwords.contains(clean)) {
                keywords.add(clean);
            }
        }
        return keywords;
    }

    private static class UserData {
        String userId;
        boolean autoMemoryEnabled;
        List<UserMemory> memories;

        UserData(String userId, boolean autoMemoryEnabled, List<UserMemory> memories) {
            this.userId = userId;
            this.autoMemoryEnabled = autoMemoryEnabled;
            this.memories = memories;
        }
    }

    private static class ScoredMemory {
        UserMemory memory;
        int score;

        ScoredMemory(UserMemory memory, int score) {
            this.memory = memory;
            this.score = score;
        }
    }
}
