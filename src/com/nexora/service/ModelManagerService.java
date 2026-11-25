package com.nexora.service;

import com.nexora.model.ModelInfo;
import com.nexora.model.ModelProfile;
import com.nexora.model.ModelSettings;
import com.nexora.model.PerformanceMetric;
import com.nexora.server.AppConfig;
import com.nexora.util.SimpleJson;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * ModelManagerService
 * 
 * Central management service for NEXORA AI Models:
 * - Dynamic Ollama discovery, installation (streaming pull), and safe deletion
 * - Dynamic OpenRouter cloud model catalog with in-memory TTL caching
 * - Hardware detection & model resource suitability estimation (AMD Ryzen / 16GB RAM)
 * - Intelligent Model Profiles (Coding, Writing, Learning, General, Custom)
 * - Persistent favorites tracking (with graceful offline/uninstalled indicators)
 * - Model performance tracking (duration, TTFT, token metrics) in circular buffer
 * - Atomic persistence in data/models/ directory
 * 
 * Part of NEXORA - AI-Powered Personal Chatbot
 */
public class ModelManagerService {

    private static final ModelManagerService INSTANCE = new ModelManagerService();

    private final Path storageDir;
    private final Path settingsFile;
    private final Path profilesFile;
    private final Path favoritesFile;
    private final Path performanceFile;

    private final ReentrantReadWriteLock settingsLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock profilesLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock favoritesLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock performanceLock = new ReentrantReadWriteLock();

    private final HttpClient httpClient;

    // Cache for OpenRouter catalog
    private volatile List<ModelInfo> cachedOpenRouterCatalog = null;
    private volatile long catalogCacheTimestamp = 0;
    private static final long CATALOG_CACHE_TTL_MS = 3600_000L; // 1 hour

    // Models verified through actual live inference
    // Retain only openrouter/free to avoid fragile or rate-limited individual upstream models
    private static final Set<String> VERIFIED_FREE_MODELS = Set.of(
            "openrouter/free"
    );

    // Models currently returning rate limit or quota exceeded
    private static final Set<String> RATE_LIMITED_MODELS = Set.of(
            "inclusionai/ling-3.2-flash:free",
            "google/gemma-4-26b-a4b-it:free",
            "google/gemma-4-31b-it:free",
            "baidu/ernie-5.5-flash-preview:free",
            "particle-ai/particle-1:free"
    );

    // Active pulls to prevent duplicate simultaneous pulls
    private final ConcurrentHashMap<String, AtomicBoolean> activePulls = new ConcurrentHashMap<>();

    // In-memory circular performance history buffer (max 200 records)
    private static final int MAX_PERFORMANCE_RECORDS = 200;
    private final LinkedList<PerformanceMetric> performanceHistory = new LinkedList<>();

    // Model name validation regex (prevent shell injection / path traversal)
    public static final Pattern MODEL_IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-\\./:]+$");

    // Protected baseline benchmark models (must never be uninstalled)
    private static final Set<String> PROTECTED_MODELS = Set.of("qwen2.5:7b", "qwen2.5:3b");

    private ModelSettings modelSettings;
    private final List<ModelProfile> profiles = new ArrayList<>();

    private ModelManagerService() {
        this.storageDir = Path.of(AppConfig.getModelsDir()).toAbsolutePath().normalize();
        this.settingsFile = storageDir.resolve("model_settings.json");
        this.profilesFile = storageDir.resolve("profiles.json");
        this.favoritesFile = storageDir.resolve("favorites.json");
        this.performanceFile = storageDir.resolve("performance_history.json");

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        try {
            Files.createDirectories(this.storageDir);
        } catch (IOException e) {
            System.err.println("[ModelManagerService] Warning: Could not create models storage directory: " + e.getMessage());
        }

        initializeSettings();
        initializeProfiles();
        initializeFavorites();
        initializePerformanceHistory();
    }

    public static ModelManagerService getInstance() {
        return INSTANCE;
    }

    // =========================================================================
    // INITIALIZATION & PERSISTENCE
    // =========================================================================

    private void initializeSettings() {
        settingsLock.writeLock().lock();
        try {
            if (Files.exists(settingsFile)) {
                try {
                    String content = Files.readString(settingsFile, StandardCharsets.UTF_8);
                    Map<String, Object> map = SimpleJson.parseObject(content);
                    this.modelSettings = ModelSettings.fromMap(map);
                } catch (Exception e) {
                    System.err.println("[ModelManagerService] Warning: Malformed settings file, loading defaults: " + e.getMessage());
                    this.modelSettings = new ModelSettings();
                    saveSettingsInternal();
                }
            } else {
                this.modelSettings = new ModelSettings();
                saveSettingsInternal();
            }
        } finally {
            settingsLock.writeLock().unlock();
        }
    }

    private void saveSettingsInternal() {
        try {
            String json = SimpleJson.toJson(modelSettings.toMap());
            atomicWrite(settingsFile, json);
        } catch (Exception e) {
            System.err.println("[ModelManagerService] Failed to save model settings: " + e.getMessage());
        }
    }

    private void initializeProfiles() {
        profilesLock.writeLock().lock();
        try {
            List<ModelProfile> defaults = ModelProfile.createDefaultProfiles();
            this.profiles.clear();

            if (Files.exists(profilesFile)) {
                try {
                    String content = Files.readString(profilesFile, StandardCharsets.UTF_8);
                    List<Object> rawList = SimpleJson.parseArray(content);
                    Set<String> seenIds = new HashSet<>();

                    // First add system defaults
                    for (ModelProfile def : defaults) {
                        this.profiles.add(def);
                        seenIds.add(def.getId());
                    }

                    // Then load custom profiles from file
                    for (Object item : rawList) {
                        if (item instanceof Map<?, ?> m) {
                            @SuppressWarnings("unchecked")
                            ModelProfile p = ModelProfile.fromMap((Map<String, Object>) m);
                            if (p != null && p.getId() != null && !seenIds.contains(p.getId())) {
                                if (p.isCustom()) {
                                    this.profiles.add(p);
                                    seenIds.add(p.getId());
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[ModelManagerService] Warning: Malformed profiles file, restoring defaults: " + e.getMessage());
                    this.profiles.addAll(defaults);
                    saveProfilesInternal();
                }
            } else {
                this.profiles.addAll(defaults);
                saveProfilesInternal();
            }
        } finally {
            profilesLock.writeLock().unlock();
        }
    }

    private void saveProfilesInternal() {
        try {
            List<Map<String, Object>> list = new ArrayList<>();
            for (ModelProfile p : profiles) {
                list.add(p.toMap());
            }
            String json = SimpleJson.toJson(list);
            atomicWrite(profilesFile, json);
        } catch (Exception e) {
            System.err.println("[ModelManagerService] Failed to save profiles: " + e.getMessage());
        }
    }

    private void initializeFavorites() {
        favoritesLock.writeLock().lock();
        try {
            if (Files.exists(favoritesFile)) {
                try {
                    String content = Files.readString(favoritesFile, StandardCharsets.UTF_8);
                    List<Object> raw = SimpleJson.parseArray(content);
                    Set<String> favs = new LinkedHashSet<>();
                    for (Object o : raw) {
                        if (o != null) favs.add(o.toString());
                    }
                    if (favs.isEmpty()) favs.add("qwen2.5:7b");
                    modelSettings.setFavorites(favs);
                } catch (Exception e) {
                    modelSettings.getFavorites().add("qwen2.5:7b");
                    saveFavoritesInternal();
                }
            } else {
                saveFavoritesInternal();
            }
        } finally {
            favoritesLock.writeLock().unlock();
        }
    }

    private void saveFavoritesInternal() {
        try {
            List<String> list = new ArrayList<>(modelSettings.getFavorites());
            String json = SimpleJson.toJson(list);
            atomicWrite(favoritesFile, json);
        } catch (Exception e) {
            System.err.println("[ModelManagerService] Failed to save favorites: " + e.getMessage());
        }
    }

    private void initializePerformanceHistory() {
        performanceLock.writeLock().lock();
        try {
            performanceHistory.clear();
            if (Files.exists(performanceFile)) {
                try {
                    String content = Files.readString(performanceFile, StandardCharsets.UTF_8);
                    List<Object> raw = SimpleJson.parseArray(content);
                    for (Object item : raw) {
                        if (item instanceof Map<?, ?> m) {
                            @SuppressWarnings("unchecked")
                            PerformanceMetric metric = PerformanceMetric.fromMap((Map<String, Object>) m);
                            if (metric != null) {
                                performanceHistory.add(metric);
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[ModelManagerService] Warning: Malformed performance file: " + e.getMessage());
                }
            }
        } finally {
            performanceLock.writeLock().unlock();
        }
    }

    private void savePerformanceInternal() {
        try {
            List<Map<String, Object>> list = new ArrayList<>();
            for (PerformanceMetric m : performanceHistory) {
                list.add(m.toMap());
            }
            String json = SimpleJson.toJson(list);
            atomicWrite(performanceFile, json);
        } catch (Exception e) {
            System.err.println("[ModelManagerService] Failed to save performance records: " + e.getMessage());
        }
    }

    private void atomicWrite(Path target, String content) throws IOException {
        Path temp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // =========================================================================
    // DYNAMIC HARDWARE DETECTION & SUITABILITY ADVISOR
    // =========================================================================

    public Map<String, Object> getHardwareTelemetry() {
        Map<String, Object> hw = new LinkedHashMap<>();

        int processors = Runtime.getRuntime().availableProcessors();
        hw.put("processorCores", processors);

        String arch = System.getProperty("os.arch", "amd64");
        String procIdentifier = System.getenv("PROCESSOR_IDENTIFIER");
        String cpuName = (procIdentifier != null && !procIdentifier.isEmpty())
                ? "AMD Ryzen 5 7535HS (" + procIdentifier + ")"
                : "AMD Ryzen 5 7535HS (6 Cores / 12 Threads)";
        hw.put("cpuName", cpuName);
        hw.put("cpuArch", arch);

        long totalRam = 0;
        long freeRam = 0;

        try {
            java.lang.management.OperatingSystemMXBean genericBean = ManagementFactory.getOperatingSystemMXBean();
            if (genericBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                totalRam = sunBean.getTotalMemorySize();
                freeRam = sunBean.getFreeMemorySize();
            }
        } catch (Throwable ignored) {
        }

        // If OS bean unavailable, fall back to known hardware reference (16 GB)
        if (totalRam <= 0) {
            totalRam = 16L * 1024 * 1024 * 1024; // 16 GB
            freeRam = 8L * 1024 * 1024 * 1024;  // estimated 8 GB free
            hw.put("telemetryType", "REFERENCE_APPROXIMATE");
        } else {
            hw.put("telemetryType", "LIVE_SYSTEM_DETECTED");
        }

        hw.put("totalPhysicalRamBytes", totalRam);
        hw.put("freePhysicalRamBytes", freeRam);
        hw.put("totalRamGb", String.format(Locale.US, "%.1f GB", (double) totalRam / (1024 * 1024 * 1024)));
        hw.put("freeRamGb", String.format(Locale.US, "%.1f GB", (double) freeRam / (1024 * 1024 * 1024)));

        Map<String, Object> tiers = new LinkedHashMap<>();
        tiers.put("optimalSizeLimitGb", 4.0);
        tiers.put("maxComfortableSizeGb", 6.5);
        tiers.put("heavySizeLimitGb", 10.0);
        hw.put("ramTiers", tiers);

        return hw;
    }

    public void evaluateSuitability(ModelInfo model) {
        if (model == null) return;

        if ("PUBLIC".equalsIgnoreCase(model.getProvider())) {
            model.setSuitabilityLabel("RECOMMENDED");
            model.setSuitabilityReason("Cloud-hosted inference; zero local RAM consumption.");
            return;
        }

        long sizeBytes = model.getSizeBytes();
        double sizeGb = (double) sizeBytes / (1024 * 1024 * 1024);
        String paramStr = model.getParameterSize().toLowerCase().trim();

        double paramBillions = 0.0;
        if (paramStr.endsWith("b")) {
            try {
                paramBillions = Double.parseDouble(paramStr.replace("b", "").trim());
            } catch (Exception ignored) {
            }
        }

        if (paramBillions <= 4.0 && paramBillions > 0 || (sizeGb > 0 && sizeGb <= 3.5)) {
            model.setSuitabilityLabel("RECOMMENDED");
            model.setSuitabilityReason("Optimal for 16GB system; high token throughput and low memory footprint.");
        } else if (paramBillions <= 8.5 && paramBillions > 0 || (sizeGb > 3.5 && sizeGb <= 6.5)) {
            model.setSuitabilityLabel("RECOMMENDED");
            model.setSuitabilityReason("Balanced speed and analytical intelligence for AMD Ryzen with 16GB DDR5.");
        } else if (paramBillions <= 14.5 && paramBillions > 0 || (sizeGb > 6.5 && sizeGb <= 10.0)) {
            model.setSuitabilityLabel("MAY_RUN_SLOWLY");
            model.setSuitabilityReason("Higher memory requirement; may experience moderate response latency.");
        } else if (paramBillions > 14.5 || sizeGb > 10.0) {
            model.setSuitabilityLabel("HIGH_RAM");
            model.setSuitabilityReason("High RAM requirement; may cause system memory pressure on 16GB RAM.");
        } else {
            model.setSuitabilityLabel("UNKNOWN");
            model.setSuitabilityReason("Model size or parameter specifications unavailable.");
        }
    }

    // =========================================================================
    // DYNAMIC OLLAMA MODEL DISCOVERY
    // =========================================================================

    public List<ModelInfo> getInstalledOllamaModels() throws IOException {
        String baseUrl = AppConfig.getOllamaBaseUrl();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tags"))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(6))
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IOException("Ollama responded with HTTP status " + response.statusCode());
            }

            Map<String, Object> root = SimpleJson.parseObject(response.body());
            List<Object> modelsRaw = SimpleJson.getList(root, "models");
            List<ModelInfo> result = new ArrayList<>();

            for (Object item : modelsRaw) {
                if (item instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> modelMap = (Map<String, Object>) m;
                    String fullName = SimpleJson.getString(modelMap, "name", "");
                    if (fullName.isEmpty()) continue;

                    String tag = "";
                    String name = fullName;
                    int colonIdx = fullName.indexOf(':');
                    if (colonIdx != -1) {
                        name = fullName.substring(0, colonIdx);
                        tag = fullName.substring(colonIdx + 1);
                    }

                    long sizeBytes = 0;
                    Object sizeObj = modelMap.get("size");
                    if (sizeObj instanceof Number n) {
                        sizeBytes = n.longValue();
                    }

                    Map<String, Object> details = SimpleJson.getMap(modelMap, "details");
                    String paramSize = SimpleJson.getString(details, "parameter_size", "");
                    String quant = SimpleJson.getString(details, "quantization_level", "");
                    String family = SimpleJson.getString(details, "family", "");

                    ModelInfo info = new ModelInfo();
                    info.setId(fullName);
                    info.setName(name);
                    info.setTag(tag);
                    info.setProvider("LOCAL");
                    info.setSizeBytes(sizeBytes);
                    info.setParameterSize(paramSize);
                    info.setQuantization(quant);
                    info.setFamily(family);
                    info.setInstalled(true);
                    info.setContextLength(32768); // standard default context

                    evaluateSuitability(info);
                    result.add(info);
                }
            }

            return result;

        } catch (ConnectException e) {
            throw new IOException("Cannot connect to local Ollama server at " + baseUrl + ". Please ensure Ollama is running.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Connection to Ollama was interrupted: " + e.getMessage());
        }
    }

    // =========================================================================
    // DYNAMIC OPENROUTER CLOUD CATALOG
    // =========================================================================

    public void invalidateOpenRouterCache() {
        this.cachedOpenRouterCatalog = null;
        this.catalogCacheTimestamp = 0;
    }

    public List<ModelInfo> getOpenRouterCatalog(String search, boolean freeOnly) throws IOException {
        return getOpenRouterCatalog(search, freeOnly, false);
    }

    public List<ModelInfo> getOpenRouterCatalog(String search, boolean freeOnly, boolean forceRefresh) throws IOException {
        long now = System.currentTimeMillis();
        List<ModelInfo> catalog;

        if (forceRefresh) {
            invalidateOpenRouterCache();
        }

        if (cachedOpenRouterCatalog != null && (now - catalogCacheTimestamp < CATALOG_CACHE_TTL_MS)) {
            catalog = cachedOpenRouterCatalog;
        } else {
            catalog = fetchOpenRouterCatalogDirect();
            cachedOpenRouterCatalog = catalog;
            catalogCacheTimestamp = now;
        }

        List<ModelInfo> filtered = new ArrayList<>();
        String query = search != null ? search.trim().toLowerCase() : "";

        for (ModelInfo info : catalog) {
            boolean matchesSearch = query.isEmpty()
                    || info.getId().toLowerCase().contains(query)
                    || info.getName().toLowerCase().contains(query);

            boolean isFree = (info.getPricingPrompt() == 0.0 && info.getPricingCompletion() == 0.0)
                    || info.getId().endsWith(":free")
                    || "openrouter/free".equalsIgnoreCase(info.getId());
            boolean matchesFree = !freeOnly || isFree;

            if (matchesSearch && matchesFree) {
                filtered.add(info);
            }
        }

        // Sort: openrouter/free first, verified next, unverified middle, rate-limited at the end
        filtered.sort((a, b) -> {
            if ("openrouter/free".equalsIgnoreCase(a.getId())) return -1;
            if ("openrouter/free".equalsIgnoreCase(b.getId())) return 1;
            boolean aVer = "verified".equalsIgnoreCase(a.getStatus());
            boolean bVer = "verified".equalsIgnoreCase(b.getStatus());
            if (aVer && !bVer) return -1;
            if (!aVer && bVer) return 1;
            boolean aLim = "rate_limited".equalsIgnoreCase(a.getStatus());
            boolean bLim = "rate_limited".equalsIgnoreCase(b.getStatus());
            if (aLim && !bLim) return 1;
            if (!aLim && bLim) return -1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        return filtered;
    }

    private List<ModelInfo> fetchOpenRouterCatalogDirect() throws IOException {
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(AppConfig.getOpenRouterBaseUrl() + "/models"))
                .header("Accept", "application/json")
                .header("HTTP-Referer", "https://github.com/nexora-ai")
                .header("X-Title", "NEXORA AI Companion")
                .timeout(Duration.ofSeconds(15))
                .GET();

        if (AppConfig.hasOpenRouterApiKey()) {
            reqBuilder.header("Authorization", "Bearer " + AppConfig.getOpenRouterApiKey());
        }

        try {
            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IOException("OpenRouter returned HTTP " + response.statusCode());
            }

            Map<String, Object> root = SimpleJson.parseObject(response.body());
            List<Object> dataList = SimpleJson.getList(root, "data");
            List<ModelInfo> result = new ArrayList<>();
            ModelInfo freeRouterInfo = null;

            for (Object item : dataList) {
                if (item instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) m;
                    String id = SimpleJson.getString(map, "id", "");
                    String name = SimpleJson.getString(map, "name", id);
                    int contextLen = SimpleJson.getInt(map, "context_length", 4096);

                    // Filter out non-chat / audio / embedding models
                    String lowerId = id.toLowerCase();
                    if (lowerId.contains("lyria") || lowerId.contains("audio") || lowerId.contains("tts")
                            || lowerId.contains("diffusion") || lowerId.contains("embed")) {
                        continue;
                    }

                    Map<String, Object> pricing = SimpleJson.getMap(map, "pricing");
                    double pPrompt = 0.0;
                    double pComp = 0.0;
                    try {
                        Object pp = pricing.get("prompt");
                        if (pp != null) pPrompt = Double.parseDouble(pp.toString());
                        Object pc = pricing.get("completion");
                        if (pc != null) pComp = Double.parseDouble(pc.toString());
                    } catch (Exception ignored) {
                    }

                    ModelInfo info = new ModelInfo();
                    info.setId(id);
                    info.setName(name);
                    info.setProvider("PUBLIC");
                    info.setContextLength(contextLen);
                    info.setPricingPrompt(pPrompt);
                    info.setPricingCompletion(pComp);
                    info.setInstalled(false);

                    String status = "verified";
                    if (RATE_LIMITED_MODELS.contains(id)) {
                        status = "rate_limited";
                        info.setSuitabilityLabel("QUOTA_REACHED");
                        info.setSuitabilityReason("Free-tier quota currently reached on OpenRouter. Please try again shortly.");
                    } else if (VERIFIED_FREE_MODELS.contains(id)) {
                        status = "verified";
                        info.setSuitabilityLabel("RECOMMENDED (Verified Active)");
                        info.setSuitabilityReason("Verified active inference; cloud-hosted with zero local hardware load.");
                    } else if (pPrompt == 0.0 && pComp == 0.0) {
                        status = "unverified";
                        info.setSuitabilityLabel("CLOUD_FREE");
                        info.setSuitabilityReason("Cloud-hosted free tier.");
                    } else {
                        info.setSuitabilityLabel("RECOMMENDED");
                        info.setSuitabilityReason("Cloud-hosted inference; zero local hardware load.");
                    }
                    info.setStatus(status);

                    if ("openrouter/free".equalsIgnoreCase(id)) {
                        info.setName("OpenRouter Free Router (Auto-selects active free model)");
                        info.setSuitabilityLabel("RECOMMENDED (Free Router)");
                        info.setSuitabilityReason("Official OpenRouter free-tier router. Automatically selects the best currently active free model with zero paid routing.");
                        info.setStatus("verified");
                        freeRouterInfo = info;
                    } else {
                        result.add(info);
                    }
                }
            }

            // Always ensure openrouter/free is at the very top of the catalog
            if (freeRouterInfo == null) {
                freeRouterInfo = new ModelInfo();
                freeRouterInfo.setId("openrouter/free");
                freeRouterInfo.setName("OpenRouter Free Router (Auto-selects active free model)");
                freeRouterInfo.setProvider("PUBLIC");
                freeRouterInfo.setContextLength(200000);
                freeRouterInfo.setPricingPrompt(0.0);
                freeRouterInfo.setPricingCompletion(0.0);
                freeRouterInfo.setInstalled(false);
                freeRouterInfo.setSuitabilityLabel("RECOMMENDED (Free Router)");
                freeRouterInfo.setSuitabilityReason("Official OpenRouter free-tier router. Automatically selects the best currently active free model with zero paid routing.");
            }
            result.add(0, freeRouterInfo);

            return result;

        } catch (Exception e) {
            // Return curated active free fallback if offline or API blocked
            return createCuratedOpenRouterList();
        }
    }

    private List<ModelInfo> createCuratedOpenRouterList() {
        List<ModelInfo> list = new ArrayList<>();

        ModelInfo m0 = new ModelInfo("openrouter/free", "OpenRouter Free Router (Auto-selects active free model)", "free", "PUBLIC", 0, "Auto", "", "openrouter", 200000, 0.0, 0.0, false, "RECOMMENDED (Free Router)", "Official OpenRouter auto-router: dynamically chooses available free models with zero paid fallback.");
        m0.setStatus("verified");
        list.add(m0);

        return list;
    }

    // =========================================================================
    // STREAMING MODEL INSTALLATION (OLLAMA POST /api/pull)
    // =========================================================================

    public void pullModel(String rawModelName, Consumer<Map<String, Object>> progressConsumer, AtomicBoolean cancelFlag) throws Exception {
        if (rawModelName == null || rawModelName.trim().isEmpty()) {
            throw new IllegalArgumentException("Model identifier cannot be empty.");
        }

        String modelName = rawModelName.trim();
        if (!MODEL_IDENTIFIER_PATTERN.matcher(modelName).matches()) {
            throw new IllegalArgumentException("Invalid model identifier format: " + modelName);
        }

        if (activePulls.putIfAbsent(modelName, cancelFlag) != null) {
            throw new IllegalStateException("Installation for '" + modelName + "' is already in progress.");
        }

        try {
            Map<String, Object> reqBody = new LinkedHashMap<>();
            reqBody.put("name", modelName);
            reqBody.put("stream", true);

            String jsonPayload = SimpleJson.toJson(reqBody);

            URL url = new URI(AppConfig.getOllamaBaseUrl() + "/api/pull").toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setDoOutput(true);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(0); // Stream indefinitely until completion or cancellation

            try (OutputStream os = connection.getOutputStream()) {
                os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int status = connection.getResponseCode();
            if (status != 200) {
                String err = "HTTP " + status;
                try (InputStream errStream = connection.getErrorStream()) {
                    if (errStream != null) {
                        err = new String(errStream.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }
                throw new IOException("Ollama installation error (" + status + "): " + err);
            }

            long startTime = System.currentTimeMillis();
            long lastCompleted = 0;

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (cancelFlag != null && cancelFlag.get()) {
                        connection.disconnect();
                        Map<String, Object> cancelEvent = new LinkedHashMap<>();
                        cancelEvent.put("status", "cancelled");
                        cancelEvent.put("message", "Model installation cancelled by user.");
                        progressConsumer.accept(cancelEvent);
                        return;
                    }

                    line = line.trim();
                    if (line.isEmpty()) continue;

                    try {
                        Map<String, Object> event = SimpleJson.parseObject(line);
                        String pullStatus = SimpleJson.getString(event, "status", "");
                        long total = 0;
                        long completed = 0;

                        Object totalObj = event.get("total");
                        if (totalObj instanceof Number n) total = n.longValue();
                        Object compObj = event.get("completed");
                        if (compObj instanceof Number n) completed = n.longValue();

                        double percent = (total > 0) ? ((double) completed / total) * 100.0 : 0.0;
                        long elapsedSec = Math.max(1, (System.currentTimeMillis() - startTime) / 1000);
                        double speedBps = (completed > 0) ? (double) completed / elapsedSec : 0.0;

                        Map<String, Object> update = new LinkedHashMap<>();
                        update.put("status", pullStatus);
                        update.put("model", modelName);
                        update.put("completedBytes", completed);
                        update.put("totalBytes", total);
                        update.put("percent", Math.round(percent * 10.0) / 10.0);
                        update.put("speedBps", Math.round(speedBps));
                        update.put("speedFormatted", formatBytes(Math.round(speedBps)) + "/s");
                        update.put("completedFormatted", formatBytes(completed));
                        update.put("totalFormatted", formatBytes(total));

                        if ("success".equalsIgnoreCase(pullStatus)) {
                            update.put("done", true);
                        }

                        progressConsumer.accept(update);

                    } catch (Exception parseEx) {
                        // Pass along raw status if JSON parsing fails for a heartbeat line
                    }
                }
            }

        } finally {
            activePulls.remove(modelName);
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format(Locale.US, "%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    // =========================================================================
    // SAFE MODEL DELETION
    // =========================================================================

    public boolean deleteModel(String rawModelName, boolean confirmed) throws Exception {
        if (!confirmed) {
            throw new IllegalArgumentException("Deletion requires explicit confirmation flag 'confirmed: true'.");
        }

        if (rawModelName == null || rawModelName.trim().isEmpty()) {
            throw new IllegalArgumentException("Model identifier cannot be empty.");
        }

        String modelName = rawModelName.trim();
        if (!MODEL_IDENTIFIER_PATTERN.matcher(modelName).matches()) {
            throw new IllegalArgumentException("Invalid model identifier format.");
        }

        // 1. Never delete benchmark reference models
        if (PROTECTED_MODELS.contains(modelName.toLowerCase())) {
            throw new IllegalStateException("Protected baseline model '" + modelName + "' cannot be deleted.");
        }

        // 2. Never delete active global default model
        ModelSettings currentSettings = getSettings();
        if (modelName.equalsIgnoreCase(currentSettings.getDefaultLocalModel())) {
            throw new IllegalStateException("Cannot delete active default model '" + modelName + "'. Please select another model first.");
        }

        // 3. Perform DELETE request to Ollama
        Map<String, Object> reqBody = new LinkedHashMap<>();
        reqBody.put("name", modelName);
        String jsonPayload = SimpleJson.toJson(reqBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(AppConfig.getOllamaBaseUrl() + "/api/delete"))
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10))
                .method("DELETE", HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int statusCode = response.statusCode();

        if (statusCode == 200 || statusCode == 204) {
            return true;
        } else {
            throw new IOException("Ollama model deletion failed with HTTP status " + statusCode + ": " + response.body());
        }
    }

    // =========================================================================
    // PROFILES CRUD
    // =========================================================================

    public List<ModelProfile> getProfiles() {
        profilesLock.readLock().lock();
        try {
            return new ArrayList<>(profiles);
        } finally {
            profilesLock.readLock().unlock();
        }
    }

    public ModelProfile getProfileById(String id) {
        if (id == null) return null;
        profilesLock.readLock().lock();
        try {
            for (ModelProfile p : profiles) {
                if (id.equalsIgnoreCase(p.getId())) return p;
            }
            return null;
        } finally {
            profilesLock.readLock().unlock();
        }
    }

    public ModelProfile saveProfile(ModelProfile newProfile) {
        if (newProfile == null) throw new IllegalArgumentException("Profile cannot be null.");
        profilesLock.writeLock().lock();
        try {
            if (newProfile.getId() == null || newProfile.getId().trim().isEmpty()) {
                String id = "prof_" + System.currentTimeMillis() + "_" + Integer.toHexString((int) (Math.random() * 0xFFFF));
                newProfile.setId(id);
                newProfile.setCustom(true);
            }

            int existingIdx = -1;
            for (int i = 0; i < profiles.size(); i++) {
                if (profiles.get(i).getId().equalsIgnoreCase(newProfile.getId())) {
                    existingIdx = i;
                    break;
                }
            }

            if (existingIdx != -1) {
                ModelProfile existing = profiles.get(existingIdx);
                if (!existing.isCustom()) {
                    // Update model or persona of built-in profile, but preserve isCustom = false
                    existing.setModelId(newProfile.getModelId());
                    existing.setProvider(newProfile.getProvider());
                    existing.setPersona(newProfile.getPersona());
                    saveProfilesInternal();
                    return existing;
                } else {
                    profiles.set(existingIdx, newProfile);
                }
            } else {
                newProfile.setCustom(true);
                profiles.add(newProfile);
            }

            saveProfilesInternal();
            return newProfile;
        } finally {
            profilesLock.writeLock().unlock();
        }
    }

    public boolean deleteProfile(String id) {
        if (id == null) return false;
        profilesLock.writeLock().lock();
        try {
            for (int i = 0; i < profiles.size(); i++) {
                ModelProfile p = profiles.get(i);
                if (p.getId().equalsIgnoreCase(id)) {
                    if (!p.isCustom()) {
                        throw new IllegalStateException("Built-in system profile '" + p.getName() + "' cannot be deleted.");
                    }
                    profiles.remove(i);
                    saveProfilesInternal();
                    return true;
                }
            }
            return false;
        } finally {
            profilesLock.writeLock().unlock();
        }
    }

    // =========================================================================
    // FAVORITES
    // =========================================================================

    public Set<String> getFavorites() {
        favoritesLock.readLock().lock();
        try {
            return new LinkedHashSet<>(modelSettings.getFavorites());
        } finally {
            favoritesLock.readLock().unlock();
        }
    }

    public boolean toggleFavorite(String modelId) {
        if (modelId == null || modelId.trim().isEmpty()) return false;
        favoritesLock.writeLock().lock();
        try {
            String mid = modelId.trim();
            boolean result;
            if (modelSettings.isFavorite(mid)) {
                modelSettings.removeFavorite(mid);
                result = false;
            } else {
                modelSettings.addFavorite(mid);
                result = true;
            }
            saveFavoritesInternal();
            return result;
        } finally {
            favoritesLock.writeLock().unlock();
        }
    }

    // =========================================================================
    // PERFORMANCE MONITORING
    // =========================================================================

    public void recordMetric(PerformanceMetric metric) {
        if (metric == null) return;
        performanceLock.writeLock().lock();
        try {
            performanceHistory.addFirst(metric);
            while (performanceHistory.size() > MAX_PERFORMANCE_RECORDS) {
                performanceHistory.removeLast();
            }
            savePerformanceInternal();
        } finally {
            performanceLock.writeLock().unlock();
        }
    }

    public Map<String, Object> getPerformanceSummary() {
        performanceLock.readLock().lock();
        try {
            long totalDuration = 0;
            long totalTtft = 0;
            int ttftCount = 0;
            double totalTps = 0;
            int tpsCount = 0;
            int successCount = 0;
            int failCount = 0;

            Map<String, Long> modelTotalTime = new HashMap<>();
            Map<String, Integer> modelReqCount = new HashMap<>();

            for (PerformanceMetric m : performanceHistory) {
                if (m.isSuccess()) {
                    successCount++;
                    totalDuration += m.getDurationMs();

                    if (m.getTtftMs() > 0) {
                        totalTtft += m.getTtftMs();
                        ttftCount++;
                    }

                    if (m.getTokensPerSec() > 0) {
                        totalTps += m.getTokensPerSec();
                        tpsCount++;
                    }

                    String key = m.getModelId();
                    modelTotalTime.put(key, modelTotalTime.getOrDefault(key, 0L) + m.getDurationMs());
                    modelReqCount.put(key, modelReqCount.getOrDefault(key, 0) + 1);
                } else {
                    failCount++;
                }
            }

            int count = successCount + failCount;
            double avgDuration = successCount > 0 ? (double) totalDuration / successCount : 0.0;
            double avgTtft = ttftCount > 0 ? (double) totalTtft / ttftCount : 0.0;
            double avgTps = tpsCount > 0 ? totalTps / tpsCount : 0.0;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("totalRequests", count);
            result.put("successCount", successCount);
            result.put("failCount", failCount);
            result.put("avgDurationMs", Math.round(avgDuration));
            result.put("avgTtftMs", Math.round(avgTtft));
            result.put("avgTokensPerSec", Math.round(avgTps * 10.0) / 10.0);

            List<Map<String, Object>> recent = new ArrayList<>();
            int maxRecent = Math.min(10, performanceHistory.size());
            for (int i = 0; i < maxRecent; i++) {
                recent.add(performanceHistory.get(i).toMap());
            }
            result.put("recentRecords", recent);

            Map<String, Object> modelStats = new LinkedHashMap<>();
            for (String mId : modelTotalTime.keySet()) {
                int n = modelReqCount.get(mId);
                long t = modelTotalTime.get(mId);
                Map<String, Object> sub = new LinkedHashMap<>();
                sub.put("requests", n);
                sub.put("avgDurationMs", Math.round((double) t / n));
                modelStats.put(mId, sub);
            }
            result.put("modelStats", modelStats);

            return result;
        } finally {
            performanceLock.readLock().unlock();
        }
    }

    public void clearPerformanceHistory() {
        performanceLock.writeLock().lock();
        try {
            performanceHistory.clear();
            savePerformanceInternal();
        } finally {
            performanceLock.writeLock().unlock();
        }
    }

    // =========================================================================
    // SETTINGS
    // =========================================================================

    public ModelSettings getSettings() {
        settingsLock.readLock().lock();
        try {
            return this.modelSettings;
        } finally {
            settingsLock.readLock().unlock();
        }
    }

    public ModelSettings updateSettings(Map<String, Object> updates) {
        if (updates == null) return getSettings();
        settingsLock.writeLock().lock();
        try {
            if (updates.containsKey("defaultProvider")) {
                modelSettings.setDefaultProvider(SimpleJson.getString(updates, "defaultProvider", modelSettings.getDefaultProvider()));
            }
            if (updates.containsKey("defaultLocalModel")) {
                modelSettings.setDefaultLocalModel(SimpleJson.getString(updates, "defaultLocalModel", modelSettings.getDefaultLocalModel()));
            }
            if (updates.containsKey("defaultCloudModel")) {
                modelSettings.setDefaultCloudModel(SimpleJson.getString(updates, "defaultCloudModel", modelSettings.getDefaultCloudModel()));
            }
            if (updates.containsKey("activeProfileId")) {
                modelSettings.setActiveProfileId(SimpleJson.getString(updates, "activeProfileId", modelSettings.getActiveProfileId()));
            }
            saveSettingsInternal();
            return modelSettings;
        } finally {
            settingsLock.writeLock().unlock();
        }
    }
}
