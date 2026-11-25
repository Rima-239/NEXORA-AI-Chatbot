/**
 * NEXORA: Frontend Application Engine
 * Connects to Core Java 21 backend (/api/chat, /api/models, /api/health, /api/config)
 * 
 * Features:
 * - Theme Switcher (Dark, Light, System) with localStorage persistence
 * - Real-time SSE streaming response decoding with buffered fallback
 * - 4 Personas: General, Coding, Writing, Learning
 * - 4 Quick Actions: Explain, Summarize, Translate, Improve Writing
 * - Multi-turn Conversation Context & localStorage management
 * - Live Chat Search, Inline Rename, Delete, Clear, and Export as TXT/MD
 * - Progressive Web App (PWA) registration & installation prompt
 */

// ==========================================================================
// 1. CONSTANTS & APPLICATION STATE
// ==========================================================================

const STORAGE_KEYS = {
    CHATS: 'nexora_chats_v1',
    ACTIVE_CHAT: 'nexora_active_chat_v1',
    THEME: 'nexora_theme_v1',
    PERSONA: 'nexora_persona_v1',
    MODEL: 'nexora_model_v1',
    USER_ID: 'nexora_user_id_v1',
    MEMORIES: 'nexora_memories_v1',
    BYOK_KEY: 'nexora_openrouter_byok_v1',
    API_BASE: 'nexora_api_base_v1'
};

function getApiUrl(endpoint) {
    const base = window.__NEXORA_API_BASE__ || localStorage.getItem(STORAGE_KEYS.API_BASE) || '';
    if (base && !endpoint.startsWith('http')) {
        return base.replace(/\/+$/, '') + (endpoint.startsWith('/') ? endpoint : '/' + endpoint);
    }
    return endpoint;
}

const state = {
    chats: [],
    activeChatId: null,
    theme: 'dark', // 'dark', 'light', 'system'
    persona: 'general', // 'general', 'coding', 'writing', 'learning'
    selectedModel: 'qwen2.5:7b',
    selectedProvider: 'LOCAL',
    availableModels: [],
    installedModels: [],
    cloudCatalog: [],
    hasOpenRouterApiKey: false,
    modelProfiles: [],
    favorites: ['qwen2.5:7b'],
    hardwareInfo: null,
    modelSettings: {
        defaultProvider: 'LOCAL',
        defaultLocalModel: 'qwen2.5:7b',
        defaultCloudModel: 'openrouter/free',
        activeProfileId: 'general',
        favorites: ['qwen2.5:7b']
    },
    activePullAbort: null,
    inspectingModel: null,
    providerInfo: {
        mode: 'LOCAL',
        provider: 'Ollama (Local)',
        status: 'online'
    },
    isGenerating: false,
    lastUserPrompt: null,
    deferredInstallPrompt: null,
    memories: [],
    autoMemoryEnabled: true,
    userId: 'default',
    editingMemoryId: null
};

// ==========================================================================
// 2. INITIALIZATION
// ==========================================================================

document.addEventListener('DOMContentLoaded', () => {
    initTheme();
    loadSavedData();
    initBackendConnection();
    initMemory();
    initPWA();
    setupEventListeners();
    renderApp();
});

function initTheme() {
    const urlTheme = new URLSearchParams(window.location.search).get('theme');
    const saved = urlTheme || localStorage.getItem(STORAGE_KEYS.THEME) || 'dark';
    setTheme(saved, false);

    // Watch for OS theme changes when System theme is active
    window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', (e) => {
        if (state.theme === 'system') {
            applyThemeToDOM('system');
        }
    });
}

function setTheme(newTheme, save = true) {
    state.theme = newTheme;
    if (save) {
        localStorage.setItem(STORAGE_KEYS.THEME, newTheme);
    }
    applyThemeToDOM(newTheme);
    updateThemeUI(newTheme);
}

function applyThemeToDOM(theme) {
    if (theme === 'system') {
        document.documentElement.setAttribute('data-theme', 'system');
    } else {
        document.documentElement.setAttribute('data-theme', theme);
    }
}

function updateThemeUI(theme) {
    document.querySelectorAll('.theme-pill-btn, .theme-card-option').forEach(btn => {
        const t = btn.getAttribute('data-theme-val');
        if (t === theme) {
            btn.classList.add('active');
        } else {
            btn.classList.remove('active');
        }
    });
}

function loadSavedData() {
    // 1. Load Persona
    const savedPersona = localStorage.getItem(STORAGE_KEYS.PERSONA);
    if (savedPersona && ['general', 'coding', 'writing', 'learning'].includes(savedPersona)) {
        state.persona = savedPersona;
    }

    // 2. Load Model
    const savedModel = localStorage.getItem(STORAGE_KEYS.MODEL);
    if (savedModel) {
        state.selectedModel = savedModel;
    }

    // 3. Load Chats
    try {
        const raw = localStorage.getItem(STORAGE_KEYS.CHATS);
        state.chats = raw ? JSON.parse(raw) : [];
        if (!Array.isArray(state.chats)) state.chats = [];
    } catch (e) {
        state.chats = [];
    }

    // 4. Resolve Active Chat
    const savedActiveId = localStorage.getItem(STORAGE_KEYS.ACTIVE_CHAT);
    if (savedActiveId && state.chats.some(c => c.id === savedActiveId)) {
        state.activeChatId = savedActiveId;
    } else if (state.chats.length > 0) {
        state.activeChatId = state.chats[0].id;
    } else {
        createNewChat(false);
    }

    // 5. Auto-migrate deprecated or rate-limited cloud models (e.g. deepseek-r1:free, ling-3.2-flash:free, gemma-4:free)
    const DEPRECATED_MODELS = [
        'deepseek/deepseek-r1:free',
        'meta-llama/llama-3.2-3b-instruct:free',
        'inclusionai/ling-3.2-flash:free',
        'google/gemma-4-26b-a4b-it:free',
        'google/gemma-4-31b-it:free',
        'baidu/ernie-5.5-flash-preview:free',
        'particle-ai/particle-1:free'
    ];
    if (DEPRECATED_MODELS.includes(state.selectedModel)) {
        state.selectedModel = 'openrouter/free';
        localStorage.setItem(STORAGE_KEYS.MODEL, 'openrouter/free');
    }
    state.chats.forEach(c => {
        if (DEPRECATED_MODELS.includes(c.model)) {
            c.model = 'openrouter/free';
            c.provider = 'PUBLIC';
        }
    });
}

async function initBackendConnection() {
    try {
        // Query /api/config
        const configRes = await fetch(getApiUrl('/api/config'));
        if (configRes.ok) {
            const configData = await configRes.json();
            if (configData.success) {
                state.providerInfo.mode = configData.mode || 'LOCAL';
                state.providerInfo.provider = configData.provider || 'Ollama (Local)';
                state.hasOpenRouterApiKey = !!configData.hasOpenRouterApiKey;

                if (state.providerInfo.mode === 'PUBLIC') {
                    state.selectedProvider = 'PUBLIC';
                    if (!state.selectedModel || state.selectedModel.startsWith('qwen2.5')) {
                        state.selectedModel = configData.default_model || 'openrouter/free';
                    }
                    applyPublicModeUI();
                } else {
                    if (!state.selectedModel && configData.default_model) {
                        state.selectedModel = configData.default_model;
                    }
                }
            }
        }

        // Query /api/models
        const modelsRes = await fetch(getApiUrl('/api/models'));
        if (modelsRes.ok) {
            const modelsData = await modelsRes.json();
            if (modelsData.success && Array.isArray(modelsData.models)) {
                state.availableModels = modelsData.models;
                if (!state.selectedModel || !state.availableModels.includes(state.selectedModel)) {
                    state.selectedModel = modelsData.default_model || state.availableModels[0] || (state.providerInfo.mode === 'PUBLIC' ? 'openrouter/free' : 'qwen2.5:7b');
                }
            }
        }

        // Query /api/health
        const healthRes = await fetch(getApiUrl('/api/health'));
        if (healthRes.ok) {
            state.providerInfo.status = 'online';
        } else {
            state.providerInfo.status = 'offline';
        }
    } catch (e) {
        console.warn('[NEXORA] Could not reach backend diagnostics:', e);
        state.providerInfo.status = 'offline';
    }

    updateProviderBadgeUI();
}

function applyPublicModeUI() {
    // 1. Hide Local Models tab in Model Center, make Cloud Catalog the default
    const localTabBtn = document.querySelector('.mc-tab-btn[data-tab="installed"]');
    if (localTabBtn) {
        localTabBtn.style.display = 'none';
    }

    // 2. Hide local provider button in Section 1
    const btnLocal = document.getElementById('btnProviderLocal');
    if (btnLocal) {
        btnLocal.style.display = 'none';
    }
    const btnPublic = document.getElementById('btnProviderPublic');
    if (btnPublic) {
        btnPublic.classList.add('active');
    }

    // 3. Update footer badge
    const footerMeta = document.querySelector('.composer-meta');
    if (footerMeta) {
        footerMeta.innerHTML = 'NEXORA 1.0 • Core Java 21 Cloud Engine • Private Client Storage • <span style="color:var(--accent-cyan);">Public Cloud Mode</span>';
    }

    // 4. Update sidebar status text
    const statusText = document.getElementById('sidebarStatusText');
    if (statusText) {
        statusText.textContent = 'Cloud AI (OpenRouter) • Core Java 21';
    }
}

function initPWA() {
    if ('serviceWorker' in navigator) {
        window.addEventListener('load', () => {
            navigator.serviceWorker.register('/sw.js').then((reg) => {
                // Service worker registered
            }).catch((err) => {
                console.warn('[NEXORA PWA] Registration error:', err);
            });
        });
    }

    window.addEventListener('beforeinstallprompt', (e) => {
        e.preventDefault();
        state.deferredInstallPrompt = e;
        const installBtn = document.getElementById('btnInstallPwa');
        if (installBtn) {
            installBtn.style.display = 'inline-flex';
        }
    });
}

function promptPwaInstall() {
    if (state.deferredInstallPrompt) {
        state.deferredInstallPrompt.prompt();
        state.deferredInstallPrompt.userChoice.then((choiceResult) => {
            if (choiceResult.outcome === 'accepted') {
                const installBtn = document.getElementById('btnInstallPwa');
                if (installBtn) installBtn.style.display = 'none';
            }
            state.deferredInstallPrompt = null;
        });
    }
}

// ==========================================================================
// 3. CHAT SESSIONS & LOCALSTORAGE
// ==========================================================================

function saveChats() {
    try {
        localStorage.setItem(STORAGE_KEYS.CHATS, JSON.stringify(state.chats));
        localStorage.setItem(STORAGE_KEYS.ACTIVE_CHAT, state.activeChatId || '');
    } catch (e) {
        console.error('[NEXORA] Failed to write to localStorage:', e);
    }
}

function getActiveChat() {
    return state.chats.find(c => c.id === state.activeChatId) || null;
}

function createNewChat(shouldRender = true) {
    const current = getActiveChat();
    // If current chat is already fresh & empty, reuse it
    if (current && current.messages.length === 0) {
        if (shouldRender) {
            renderActiveChat();
            toggleSidebar(false);
            focusComposer();
        }
        return current;
    }

    const defProvider = state.modelSettings?.defaultProvider || state.selectedProvider || 'LOCAL';
    const defModel = defProvider === 'PUBLIC'
        ? (state.modelSettings?.defaultCloudModel || 'openrouter/free')
        : (state.modelSettings?.defaultLocalModel || 'qwen2.5:7b');

    const newChat = {
        id: 'chat_' + Date.now() + '_' + Math.random().toString(36).substring(2, 6),
        title: 'New Conversation',
        createdAt: Date.now(),
        updatedAt: Date.now(),
        persona: state.persona,
        provider: defProvider,
        model: defModel,
        profileId: state.modelSettings?.activeProfileId || 'general',
        messages: []
    };

    state.selectedProvider = defProvider;
    state.selectedModel = defModel;
    updateHeaderModelBadge();

    state.chats.unshift(newChat);
    state.activeChatId = newChat.id;
    saveChats();

    if (shouldRender) {
        renderSidebar();
        renderActiveChat();
        toggleSidebar(false);
        focusComposer();
    }
    return newChat;
}

function selectChat(chatId) {
    if (state.isGenerating || state.activeChatId === chatId) return;
    state.activeChatId = chatId;
    const current = getActiveChat();
    if (current) {
        if (current.persona) {
            setPersona(current.persona, false);
        }
        if (current.model) {
            state.selectedModel = current.model;
        }
        if (current.provider) {
            state.selectedProvider = current.provider;
        }
        updateHeaderModelBadge();
    }
    saveChats();
    renderSidebar();
    renderActiveChat();
    toggleSidebar(false);
    focusComposer();
}

function renameChatPrompt(chatId) {
    const targetChat = state.chats.find(c => c.id === chatId);
    if (!targetChat) return;

    const currentTitle = targetChat.title || 'New Conversation';
    const newTitle = prompt('Enter a new title for this conversation:', currentTitle);
    if (newTitle && newTitle.trim()) {
        targetChat.title = newTitle.trim().substring(0, 48);
        targetChat.updatedAt = Date.now();
        saveChats();
        renderSidebar();
        const headerTitle = document.getElementById('chatActiveTitle');
        if (headerTitle && state.activeChatId === chatId) {
            headerTitle.textContent = targetChat.title;
        }
    }
}

function deleteChat(event, chatId) {
    if (event) event.stopPropagation();
    if (state.isGenerating) return;

    if (!confirm('Are you sure you want to delete this conversation?')) return;

    state.chats = state.chats.filter(c => c.id !== chatId);
    if (state.activeChatId === chatId) {
        state.activeChatId = state.chats.length > 0 ? state.chats[0].id : null;
    }

    if (!state.activeChatId) {
        createNewChat(false);
    }

    saveChats();
    renderSidebar();
    renderActiveChat();
}

function clearCurrentChat() {
    if (state.isGenerating) return;
    const current = getActiveChat();
    if (!current || current.messages.length === 0) return;

    if (confirm('Clear all messages in the current conversation?')) {
        current.messages = [];
        current.title = 'New Conversation';
        current.updatedAt = Date.now();
        saveChats();
        renderSidebar();
        renderActiveChat();
    }
}

function clearAllChatHistory() {
    if (state.isGenerating) return;
    if (confirm('Permanently delete all chat history? This cannot be undone.')) {
        state.chats = [];
        state.activeChatId = null;
        createNewChat(false);
        saveChats();
        renderSidebar();
        renderActiveChat();
        closeSettingsModal();
    }
}

// Search functionality
function handleSearchInput(query) {
    const clean = query.trim().toLowerCase();
    const clearBtn = document.getElementById('searchClearBtn');
    if (clearBtn) {
        clearBtn.classList.toggle('visible', clean.length > 0);
    }
    renderSidebar(clean);
}

function clearSearch() {
    const input = document.getElementById('searchFilterInput');
    if (input) {
        input.value = '';
        handleSearchInput('');
        input.focus();
    }
}

// Export conversation as TXT or Markdown
function exportCurrentConversation(format = 'txt') {
    const current = getActiveChat();
    if (!current || current.messages.length === 0) {
        showToast('There are no messages to export in this conversation.');
        return;
    }

    let fileContent = '';
    const dateStr = new Date(current.createdAt).toLocaleString();

    if (format === 'md') {
        fileContent += `# ${current.title}\n`;
        fileContent += `*NEXORA AI Conversation — ${dateStr}*\n\n---\n\n`;
        current.messages.forEach(msg => {
            const roleName = msg.role === 'user' ? '### 👤 You' : '### 🤖 NEXORA (' + (msg.persona || 'AI') + ')';
            fileContent += `${roleName}\n\n${msg.content}\n\n---\n\n`;
        });
    } else {
        fileContent += `=====================================================\n`;
        fileContent += `NEXORA AI Conversation: ${current.title}\n`;
        fileContent += `Date: ${dateStr}\n`;
        fileContent += `=====================================================\n\n`;
        current.messages.forEach(msg => {
            const roleName = msg.role === 'user' ? 'YOU' : 'NEXORA';
            fileContent += `[${roleName}]:\n${msg.content}\n\n-----------------------------------------------------\n\n`;
        });
    }

    const blob = new Blob([fileContent], { type: 'text/plain;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    const sanitizedTitle = (current.title || 'conversation').replace(/[^a-zA-Z0-9_\-\u0980-\u09FF]/g, '_');
    link.download = `NEXORA_${sanitizedTitle}.${format}`;
    link.style.display = 'none';
    link.style.position = 'fixed';
    link.style.top = '-9999px';
    link.style.left = '-9999px';
    link.setAttribute('aria-hidden', 'true');
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    showToast(`Conversation exported (.${format})`);
}

// ==========================================================================
// 4. PERSONA & QUICK ACTION SYSTEM
// ==========================================================================

function setPersona(newPersona, savePreference = true) {
    if (!['general', 'coding', 'writing', 'learning'].includes(newPersona)) return;
    state.persona = newPersona;

    if (savePreference) {
        localStorage.setItem(STORAGE_KEYS.PERSONA, newPersona);
    }

    // Update active chat persona
    const current = getActiveChat();
    if (current) {
        current.persona = newPersona;
        saveChats();
    }

    // Update UI elements
    document.querySelectorAll('.persona-pill-btn').forEach(btn => {
        if (btn.getAttribute('data-persona') === newPersona) {
            btn.classList.add('active');
        } else {
            btn.classList.remove('active');
        }
    });

    document.querySelectorAll('.persona-card').forEach(card => {
        if (card.getAttribute('data-persona') === newPersona) {
            card.classList.add('active-card');
        } else {
            card.classList.remove('active-card');
        }
    });

    const activeBadge = document.getElementById('chatPersonaBadge');
    if (activeBadge) {
        activeBadge.textContent = capitalizeFirstLetter(newPersona);
    }
}

function handleQuickAction(actionType) {
    const input = document.getElementById('composerTextarea');
    if (!input) return;

    let prefix = '';
    switch (actionType) {
        case 'explain':
            prefix = 'Explain this concept in simple terms with an example: ';
            break;
        case 'summarize':
            prefix = 'Summarize the following text into key bullet points: ';
            break;
        case 'translate':
            prefix = 'Translate the following text naturally between English and Bengali (বাংলা): ';
            break;
        case 'improve':
            prefix = 'Proofread, polish, and enhance the tone and flow of the following text: ';
            break;
    }

    input.value = prefix;
    adjustComposerHeight();
    input.focus();
    input.setSelectionRange(input.value.length, input.value.length);
}

function usePromptTemplate(promptText, persona = null) {
    if (persona) {
        setPersona(persona, true);
    }
    const input = document.getElementById('composerTextarea');
    if (input) {
        input.value = promptText;
        adjustComposerHeight();
        handleSendMessage();
    }
}

// ==========================================================================
// 5. MESSAGE SENDING & STREAMING ENGINE
// ==========================================================================

async function handleSendMessage() {
    const input = document.getElementById('composerTextarea');
    if (!input) return;

    const rawText = input.value.trim();
    if (!rawText || state.isGenerating) return;

    let current = getActiveChat();
    if (!current) {
        current = createNewChat(false);
    }

    // 1. Remove welcome hero if visible
    const hero = document.getElementById('welcomeHero');
    if (hero) hero.remove();

    // 2. Add user message to state
    const userTimestamp = Date.now();
    current.messages.push({
        role: 'user',
        content: rawText,
        timestamp: userTimestamp
    });

    // 3. Auto-generate chat title from first prompt
    if (current.messages.length === 1) {
        current.title = generateSmartTitle(rawText);
        const headerTitle = document.getElementById('chatActiveTitle');
        if (headerTitle) headerTitle.textContent = current.title;
    }
    current.updatedAt = Date.now();
    saveChats();
    renderSidebar();

    // 4. Render user bubble in UI
    appendMessageBubble('user', rawText, userTimestamp, false);

    // 5. Reset input field
    input.value = '';
    adjustComposerHeight();
    state.lastUserPrompt = rawText;

    // 6. Set generating UI state
    setGeneratingState(true);
    showTypingIndicator();

    // 7. Prepare conversation context
    const recentMessages = current.messages.slice(-14).map(m => ({
        role: m.role,
        content: m.content
    }));

    let aiBubbleElement = null;
    let accumulatedText = '';

    try {
        const byokKey = localStorage.getItem(STORAGE_KEYS.BYOK_KEY) || '';
        const headers = {
            'Content-Type': 'application/json; charset=UTF-8',
            'Accept': 'text/event-stream',
            'X-User-Id': getUserId()
        };
        if (byokKey) {
            headers['X-OpenRouter-Key'] = byokKey;
        }

        // Attempt progressive token streaming (SSE)
        const response = await fetch(getApiUrl('/api/chat'), {
            method: 'POST',
            headers: headers,
            body: JSON.stringify({
                message: rawText,
                messages: recentMessages,
                persona: state.persona,
                model: state.selectedModel || current.model || (state.providerInfo.mode === 'PUBLIC' ? 'openrouter/free' : 'qwen2.5:7b'),
                provider: state.selectedProvider || current.provider || (state.providerInfo.mode === 'PUBLIC' ? 'PUBLIC' : 'LOCAL'),
                stream: true,
                userId: getUserId(),
                memories: state.memories ? state.memories.map(m => m.text) : [],
                autoMemoryEnabled: state.autoMemoryEnabled
            })
        });

        hideTypingIndicator();

        const contentType = response.headers.get('content-type') || '';

        // If server returns SSE stream
        if (response.ok && contentType.includes('text/event-stream')) {
            const reader = response.body.getReader();
            const decoder = new TextDecoder('utf-8');
            let buffer = '';
            let streamError = null;
            let streamDone = false;

            while (true) {
                const { done, value } = await reader.read();
                if (done) break;

                buffer += decoder.decode(value, { stream: true });
                const lines = buffer.split('\n');
                buffer = lines.pop(); // keep partial line

                for (const line of lines) {
                    const trimmed = line.trim();
                    if (!trimmed || trimmed.startsWith(':')) continue;

                    if (trimmed === 'data: [DONE]') {
                        streamDone = true;
                        break;
                    }

                    if (trimmed.startsWith('data: ')) {
                        let dataObj = null;
                        try {
                            dataObj = JSON.parse(trimmed.substring(6));
                        } catch (e) {
                            // ignore partial JSON parse error
                        }

                        if (dataObj) {
                            if (dataObj.error) {
                                streamError = dataObj.error;
                                break;
                            }
                            if (typeof dataObj.chunk === 'string' && dataObj.chunk.length > 0) {
                                if (!aiBubbleElement) {
                                    aiBubbleElement = createAssistantBubbleContainer();
                                }
                                accumulatedText += dataObj.chunk;
                                updateAssistantBubbleContent(aiBubbleElement, accumulatedText);
                                scrollToBottom();
                            }
                            if (dataObj.extractedMemory) {
                                const mem = dataObj.extractedMemory;
                                if (mem && mem.text && !state.memories.some(m => m.text.toLowerCase() === mem.text.toLowerCase())) {
                                    state.memories.unshift(mem);
                                    saveLocalMemories();
                                    showToast('🧠 Learned new memory: ' + mem.text);
                                }
                            } else if (dataObj.memorySaved) {
                                showToast('🧠 Personal memory updated');
                                fetchMemories();
                            }
                        }
                    }
                }

                if (streamError || streamDone) {
                    break;
                }
            }

            if (streamError) {
                throw new Error(streamError);
            }

            if (!accumulatedText.trim()) {
                throw new Error('AI returned an empty response. The model may still be loading or initializing. Please try again.');
            }

            // Finalize assistant message in state
            finalizeAssistantMessage(current, accumulatedText, aiBubbleElement);
            setTimeout(() => fetchMemories(), 500);

        } else {
            // Buffered JSON response fallback
            let data = {};
            try {
                data = await response.json();
            } catch (jsonErr) {
                // not json
            }

            if (!response.ok || !data.success) {
                throw new Error(data.error || `HTTP ${response.status}: Failed to generate response.`);
            }

            const reply = data.reply || (data.message && data.message.content) || '';
            if (!reply.trim()) {
                throw new Error('AI returned an empty response. The model may still be loading or initializing. Please try again.');
            }

            finalizeAssistantMessage(current, reply, null);
            if (data.extractedMemory) {
                const mem = data.extractedMemory;
                if (mem && mem.text && !state.memories.some(m => m.text.toLowerCase() === mem.text.toLowerCase())) {
                    state.memories.unshift(mem);
                    saveLocalMemories();
                    showToast('🧠 Learned new memory: ' + mem.text);
                }
            } else if (data.memorySaved) {
                showToast('🧠 Personal memory updated');
            }
            fetchMemories();
        }

        state.lastUserPrompt = null;

    } catch (err) {
        hideTypingIndicator();
        if (aiBubbleElement && accumulatedText.trim()) {
            finalizeAssistantMessage(current, accumulatedText, aiBubbleElement);
        } else if (aiBubbleElement) {
            aiBubbleElement.remove();
        }
        showErrorBubble(err.message || 'An unexpected connection error occurred.', true);
    } finally {
        setGeneratingState(false);
        focusComposer();
    }
}

function finalizeAssistantMessage(chat, fullText, existingBubbleElement) {
    const aiTimestamp = Date.now();
    chat.messages.push({
        role: 'assistant',
        content: fullText,
        persona: state.persona,
        timestamp: aiTimestamp
    });
    chat.updatedAt = Date.now();
    saveChats();
    renderSidebar();

    if (existingBubbleElement) {
        attachMessageMetaBar(existingBubbleElement, fullText, aiTimestamp);
    } else {
        appendMessageBubble('assistant', fullText, aiTimestamp, true);
    }
}

function regenerateLastResponse() {
    if (state.isGenerating) return;
    const current = getActiveChat();
    if (!current || current.messages.length === 0) return;

    // Find the last user message
    let lastUserIndex = -1;
    for (let i = current.messages.length - 1; i >= 0; i--) {
        if (current.messages[i].role === 'user') {
            lastUserIndex = i;
            break;
        }
    }

    if (lastUserIndex === -1) return;

    const userPrompt = current.messages[lastUserIndex].content;
    // Remove all messages after this user prompt
    current.messages = current.messages.slice(0, lastUserIndex);
    saveChats();
    renderActiveChat();

    const input = document.getElementById('composerTextarea');
    if (input) {
        input.value = userPrompt;
        adjustComposerHeight();
        handleSendMessage();
    }
}

function retryLastFailedMessage() {
    if (!state.lastUserPrompt || state.isGenerating) return;
    const input = document.getElementById('composerTextarea');
    if (input) {
        input.value = state.lastUserPrompt;
        adjustComposerHeight();
        // Remove error bubble
        const lastErr = document.querySelector('.error-card-bubble:last-child');
        if (lastErr) lastErr.remove();
        handleSendMessage();
    }
}

function setGeneratingState(generating) {
    state.isGenerating = generating;
    const sendBtn = document.getElementById('btnSendMessage');
    const input = document.getElementById('composerTextarea');
    if (sendBtn) sendBtn.disabled = generating;
    if (input) input.disabled = generating;
}

// ==========================================================================
// 6. UI RENDERING & DOM BUILDERS
// ==========================================================================

function renderApp() {
    renderSidebar();
    renderActiveChat();
    updateProviderBadgeUI();
}

function renderSidebar(searchFilter = '') {
    const list = document.getElementById('historyList');
    if (!list) return;

    list.innerHTML = '';

    let displayedChats = state.chats;
    if (searchFilter) {
        displayedChats = state.chats.filter(c => {
            const titleMatch = (c.title || '').toLowerCase().includes(searchFilter);
            const contentMatch = (c.messages || []).some(m => (m.content || '').toLowerCase().includes(searchFilter));
            return titleMatch || contentMatch;
        });
    }

    if (displayedChats.length === 0) {
        list.innerHTML = `
            <div class="empty-history-text">
                ${searchFilter ? 'No conversations matching "' + escapeHtml(searchFilter) + '"' : 'No saved conversations yet.<br>Start chatting to build your history!'}
            </div>
        `;
        return;
    }

    displayedChats.forEach(chat => {
        const item = document.createElement('div');
        item.className = 'history-item' + (chat.id === state.activeChatId ? ' active' : '');
        item.onclick = () => selectChat(chat.id);

        item.innerHTML = `
            <div class="history-item-main">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="flex-shrink:0;">
                    <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path>
                </svg>
                <span class="history-item-title">${escapeHtml(chat.title || 'New Conversation')}</span>
            </div>
            <div class="history-item-actions">
                <button class="item-action-btn" onclick="event.stopPropagation(); renameChatPrompt('${chat.id}')" title="Rename conversation">
                    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M12 20h9"></path>
                        <path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"></path>
                    </svg>
                </button>
                <button class="item-action-btn delete-btn" onclick="deleteChat(event, '${chat.id}')" title="Delete conversation">
                    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <polyline points="3 6 5 6 21 6"></polyline>
                        <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
                    </svg>
                </button>
            </div>
        `;
        list.appendChild(item);
    });
}

function renderActiveChat() {
    const container = document.getElementById('messagesContainer');
    if (!container) return;

    container.innerHTML = '';
    const current = getActiveChat();

    // Update Header Title & Persona Badge
    const titleEl = document.getElementById('chatActiveTitle');
    if (titleEl) {
        titleEl.textContent = current ? (current.title || 'New Conversation') : 'New Conversation';
    }

    const personaBadge = document.getElementById('chatPersonaBadge');
    if (personaBadge) {
        personaBadge.textContent = capitalizeFirstLetter(state.persona);
    }

    if (!current || current.messages.length === 0) {
        renderWelcomeHero(container);
        return;
    }

    current.messages.forEach(msg => {
        appendMessageBubble(msg.role, msg.content, msg.timestamp, false);
    });

    scrollToBottom();
}

function renderWelcomeHero(container) {
    const hero = document.createElement('div');
    hero.id = 'welcomeHero';
    hero.className = 'welcome-hero';

    hero.innerHTML = `
        <div class="welcome-logo-badge">
            <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2">
                <path d="M12 2a8 8 0 0 0-8 8c0 4.418 5 12 8 12s8-7.582 8-12a8 8 0 0 0-8-8z"></path>
                <circle cx="12" cy="10" r="3"></circle>
            </svg>
        </div>

        <h1 class="welcome-heading">Welcome to <span>NEXORA</span></h1>
        <p class="welcome-subtext">
            Think Smarter. Create Freely. Stay Private.<br>
            আপনার ব্যক্তিগত ও নিরাপদ AI সহকারী। প্রশ্ন করুন বাংলায় অথবা ইংরেজিতে।
        </p>

        <!-- Persona Mode Cards -->
        <div class="persona-cards-grid">
            <div class="persona-card ${state.persona === 'general' ? 'active-card' : ''}" data-persona="general" onclick="setPersona('general')">
                <div class="persona-card-icon">⚡</div>
                <div class="persona-card-name">General</div>
                <div class="persona-card-desc">Everyday tasks, facts, and conversation</div>
            </div>
            <div class="persona-card ${state.persona === 'coding' ? 'active-card' : ''}" data-persona="coding" onclick="setPersona('coding')">
                <div class="persona-card-icon">💻</div>
                <div class="persona-card-name">Coding</div>
                <div class="persona-card-desc">Clean code, architecture & bug fixing</div>
            </div>
            <div class="persona-card ${state.persona === 'writing' ? 'active-card' : ''}" data-persona="writing" onclick="setPersona('writing')">
                <div class="persona-card-icon">✍️</div>
                <div class="persona-card-name">Writing</div>
                <div class="persona-card-desc">Stories, essays, emails & tone polish</div>
            </div>
            <div class="persona-card ${state.persona === 'learning' ? 'active-card' : ''}" data-persona="learning" onclick="setPersona('learning')">
                <div class="persona-card-icon">🎓</div>
                <div class="persona-card-name">Learning</div>
                <div class="persona-card-desc">Socratic tutor with simple analogies</div>
            </div>
        </div>

        <!-- Quick Action Chips -->
        <div class="quick-actions-strip">
            <button class="quick-action-chip" onclick="handleQuickAction('explain')">
                <span>💡</span> Explain
            </button>
            <button class="quick-action-chip" onclick="handleQuickAction('summarize')">
                <span>📝</span> Summarize
            </button>
            <button class="quick-action-chip" onclick="handleQuickAction('translate')">
                <span>🌐</span> Translate (বাংলা ⇄ EN)
            </button>
            <button class="quick-action-chip" onclick="handleQuickAction('improve')">
                <span>✨</span> Improve Writing
            </button>
        </div>

        <!-- Suggestion Prompts -->
        <div class="suggestions-grid">
            <div class="suggestion-prompt-card" onclick="usePromptTemplate('বাংলায় একটি সুন্দর ও অনুপ্রেরণাদায়ী ছোট গল্প বলো', 'writing')">
                <div class="suggestion-prompt-title">বাংলা সাহিত্য ও গল্প</div>
                <div class="suggestion-prompt-text">একটি অনুপ্রেরণাদায়ী রূপক গল্প লেখো</div>
            </div>
            <div class="suggestion-prompt-card" onclick="usePromptTemplate('Write a clean Java 21 implementation of a thread-safe LRU Cache with explanations', 'coding')">
                <div class="suggestion-prompt-title">Java LRU Cache</div>
                <div class="suggestion-prompt-text">Thread-safe cache snippet with Java 21</div>
            </div>
            <div class="suggestion-prompt-card" onclick="usePromptTemplate('Explain Quantum Computing using a simple coin flip analogy that a 12-year-old can understand', 'learning')">
                <div class="suggestion-prompt-title">Quantum Computing</div>
                <div class="suggestion-prompt-text">Simple analogy for superposition & qubits</div>
            </div>
            <div class="suggestion-prompt-card" onclick="usePromptTemplate('Draft a polite, professional email to an engineering manager requesting feedback on a PR', 'writing')">
                <div class="suggestion-prompt-title">Professional Email</div>
                <div class="suggestion-prompt-text">Polite follow-up message on a pull request</div>
            </div>
        </div>
    `;

    container.appendChild(hero);
}

function appendMessageBubble(role, content, timestamp = Date.now(), shouldScroll = true) {
    const isUser = role === 'user';
    const row = document.createElement('div');
    row.className = `message-row ${isUser ? 'user-row' : 'ai-row'}`;

    const avatarHtml = isUser
        ? `<div class="message-avatar-wrap" title="You">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path>
                <circle cx="12" cy="7" r="4"></circle>
            </svg>
          </div>`
        : `<div class="message-avatar-wrap" title="NEXORA">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M12 2a8 8 0 0 0-8 8c0 4.418 5 12 8 12s8-7.582 8-12a8 8 0 0 0-8-8z"></path>
                <circle cx="12" cy="10" r="3"></circle>
            </svg>
          </div>`;

    const bodyHtml = isUser ? escapeHtml(content) : renderMarkdown(content);
    const formattedTime = formatTimestamp(timestamp);

    const actionButtonsHtml = !isUser
        ? `
            <button class="msg-action-btn" onclick="copyMessageText(this)" data-content="${encodeURIComponent(content)}">
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
                    <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
                </svg>
                Copy
            </button>
            <button class="msg-action-btn" onclick="regenerateLastResponse()" title="Regenerate this response">
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <polyline points="23 4 23 10 17 10"></polyline>
                    <path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"></path>
                </svg>
                Regenerate
            </button>
          `
        : '';

    row.innerHTML = `
        ${avatarHtml}
        <div class="message-bubble-wrapper">
            <div class="message-bubble">${bodyHtml}</div>
            <div class="message-meta-bar">
                <span>${formattedTime}</span>
                ${actionButtonsHtml}
            </div>
        </div>
    `;

    const container = document.getElementById('messagesContainer');
    if (container) {
        container.appendChild(row);
        if (shouldScroll) scrollToBottom();
    }
}

function createAssistantBubbleContainer() {
    const row = document.createElement('div');
    row.className = 'message-row ai-row';
    row.innerHTML = `
        <div class="message-avatar-wrap" title="NEXORA">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M12 2a8 8 0 0 0-8 8c0 4.418 5 12 8 12s8-7.582 8-12a8 8 0 0 0-8-8z"></path>
                <circle cx="12" cy="10" r="3"></circle>
            </svg>
        </div>
        <div class="message-bubble-wrapper">
            <div class="message-bubble"></div>
        </div>
    `;
    const container = document.getElementById('messagesContainer');
    if (container) {
        container.appendChild(row);
        scrollToBottom();
    }
    return row;
}

function updateAssistantBubbleContent(rowElement, currentMarkdown) {
    if (!rowElement) return;
    const bubble = rowElement.querySelector('.message-bubble');
    if (bubble) {
        bubble.innerHTML = renderMarkdown(currentMarkdown);
    }
}

function attachMessageMetaBar(rowElement, fullText, timestamp) {
    if (!rowElement) return;
    const wrapper = rowElement.querySelector('.message-bubble-wrapper');
    if (!wrapper) return;

    const formattedTime = formatTimestamp(timestamp);
    const metaBar = document.createElement('div');
    metaBar.className = 'message-meta-bar';
    metaBar.innerHTML = `
        <span>${formattedTime}</span>
        <button class="msg-action-btn" onclick="copyMessageText(this)" data-content="${encodeURIComponent(fullText)}">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
                <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
            </svg>
            Copy
        </button>
        <button class="msg-action-btn" onclick="regenerateLastResponse()" title="Regenerate this response">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="23 4 23 10 17 10"></polyline>
                <path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"></path>
            </svg>
            Regenerate
        </button>
    `;
    wrapper.appendChild(metaBar);
}

function showTypingIndicator() {
    const existing = document.getElementById('nexoraTypingRow');
    if (existing) return;

    const row = document.createElement('div');
    row.id = 'nexoraTypingRow';
    row.className = 'message-row ai-row';
    row.innerHTML = `
        <div class="message-avatar-wrap" title="Thinking">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M12 2a8 8 0 0 0-8 8c0 4.418 5 12 8 12s8-7.582 8-12a8 8 0 0 0-8-8z"></path>
                <circle cx="12" cy="10" r="3"></circle>
            </svg>
        </div>
        <div class="message-bubble-wrapper">
            <div class="typing-bubble">
                <div class="typing-dots">
                    <span></span>
                    <span></span>
                    <span></span>
                </div>
                <span class="typing-text-label">NEXORA is thinking...</span>
            </div>
        </div>
    `;
    const container = document.getElementById('messagesContainer');
    if (container) {
        container.appendChild(row);
        scrollToBottom();
    }
}

function hideTypingIndicator() {
    const el = document.getElementById('nexoraTypingRow');
    if (el) el.remove();
}

function showErrorBubble(errorMessage, canRetry = true) {
    const isRateLimit = errorMessage && (errorMessage.includes('Rate Limit') || errorMessage.includes('quota') || errorMessage.includes('Quota'));
    const isModelUnavailable = errorMessage && (errorMessage.includes('no longer available') || errorMessage.includes('no provider serves') || errorMessage.includes('Model Unavailable'));
    const isOpenRouterError = errorMessage && (errorMessage.includes('OpenRouter') || errorMessage.includes('OPENROUTER_API_KEY'));

    const card = document.createElement('div');
    card.className = 'error-card-bubble';
    card.innerHTML = `
        <div class="error-card-icon">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <circle cx="12" cy="12" r="10"></circle>
                <line x1="12" y1="8" x2="12" y2="12"></line>
                <line x1="12" y1="16" x2="12.01" y2="16"></line>
            </svg>
        </div>
        <div class="error-card-body">
            <div class="error-card-title">${isRateLimit ? 'OpenRouter Free Tier Quota Reached' : 'Request Issue'}</div>
            <div>${escapeHtml(errorMessage)}</div>
            ${isRateLimit ? `
                <div style="font-size:12px; color:var(--text-secondary); margin-top:5px; line-height:1.4;">
                    ℹ️ OpenRouter free-tier slots for this specific model are temporarily exhausted. Instead of repeatedly retrying the same rate-limited model, switch to <strong>openrouter/free</strong> (Auto-Router) or a local Ollama model below.
                </div>
            ` : ''}
            <div style="display:flex; flex-wrap:wrap; gap:8px; margin-top:8px;">
                ${canRetry ? `
                    <button class="btn-error-retry" onclick="retryLastFailedMessage()" title="${isRateLimit ? 'Retrying the same model may continue to hit rate limits' : 'Retry sending prompt'}">
                        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <polyline points="23 4 23 10 17 10"></polyline>
                            <path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"></path>
                        </svg>
                        Retry Prompt
                    </button>
                ` : ''}
                ${(isModelUnavailable || isRateLimit || (isOpenRouterError && !errorMessage.includes('API key'))) ? `
                    <button class="btn-error-retry" onclick="switchToOpenRouterFreeAndRetry();" style="background:rgba(168,85,247,0.18); color:var(--accent-purple); border-color:rgba(168,85,247,0.4);" title="Switch active model to openrouter/free (Dynamic Free Router) and retry immediately">
                        🔄 Switch to OpenRouter Free &amp; Retry
                    </button>
                ` : ''}
                ${isOpenRouterError ? `
                    <button class="btn-error-retry" onclick="selectQuickModel('LOCAL', 'qwen2.5:3b'); retryLastFailedMessage();" style="background:rgba(6,182,212,0.15); color:var(--accent-cyan); border-color:rgba(6,182,212,0.35);" title="Switch active model to local qwen2.5:3b and retry immediately">
                        ⚡ Switch to Local Model (qwen2.5:3b) &amp; Retry
                    </button>
                ` : ''}
            </div>
        </div>
    `;
    const container = document.getElementById('messagesContainer');
    if (container) {
        container.appendChild(card);
        scrollToBottom();
    }
}

function switchToOpenRouterFreeAndRetry() {
    selectQuickModel('PUBLIC', 'openrouter/free');
    showToast('Switched to openrouter/free (Dynamic Free Router)');
    retryLastFailedMessage();
}

function updateProviderBadgeUI() {
    const statusDot = document.getElementById('statusPulseDot');
    const statusText = document.getElementById('systemStatusText');
    const modelBadge = document.getElementById('headerModelPill');

    const isOnline = state.providerInfo.status === 'online';

    if (statusDot) {
        statusDot.className = 'status-pulse-dot ' + (isOnline ? '' : 'offline');
    }

    if (statusText) {
        statusText.textContent = `${state.providerInfo.provider} · Core Java 21`;
    }

    if (modelBadge) {
        modelBadge.textContent = state.selectedModel || 'qwen2.5:7b';
    }

    // Settings Modal Provider Info
    const modalMode = document.getElementById('settingsAppMode');
    if (modalMode) {
        modalMode.textContent = state.providerInfo.mode;
    }
    const modalProvider = document.getElementById('settingsProvider');
    if (modalProvider) {
        modalProvider.textContent = state.providerInfo.provider;
    }

    const modalDefault = document.getElementById('settingsDefaultModel');
    if (modalDefault) {
        modalDefault.textContent = state.modelSettings?.defaultLocalModel || state.selectedModel || 'qwen2.5:7b';
    }

    updateHeaderModelBadge();
}

function updateHeaderModelBadge() {
    const current = getActiveChat();
    const effectiveModel = state.selectedModel || current?.model || 'qwen2.5:7b';
    const effectiveProvider = state.selectedProvider || current?.provider || 'LOCAL';

    const headerName = document.getElementById('headerModelName');
    const headerIcon = document.getElementById('headerModelIcon');
    const legacyPill = document.getElementById('headerModelPill');
    const mcActiveBadge = document.getElementById('mcActiveModelBadge');

    if (headerName) {
        headerName.textContent = effectiveModel;
    }
    if (headerIcon) {
        headerIcon.textContent = (effectiveProvider === 'PUBLIC') ? '☁️' : '⚡';
    }
    if (legacyPill) {
        legacyPill.textContent = effectiveModel;
    }
    if (mcActiveBadge) {
        mcActiveBadge.textContent = `Active: ${effectiveModel}`;
    }
}

// ==========================================================================
// 7. MARKDOWN ENGINE & TEXT UTILITIES
// ==========================================================================

function escapeHtml(text) {
    if (!text) return '';
    return text
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
}

function renderMarkdown(rawText) {
    if (!rawText) return '';

    // 1. Extract and preserve code blocks
    const codeBlocks = [];
    let text = rawText.replace(/```([a-zA-Z0-9_\-\+\#]*)\r?\n?([\s\S]*?)```/g, (match, lang, code) => {
        const placeholder = `__CODE_BLOCK_${codeBlocks.length}__`;
        codeBlocks.push({
            lang: lang.trim() || 'code',
            code: escapeHtml(code.replace(/\r?\n$/, ''))
        });
        return placeholder;
    });

    // 2. Escape regular text
    text = escapeHtml(text);

    // 3. Inline code
    text = text.replace(/`([^`\n]+)`/g, '<code class="inline-code">$1</code>');

    // 4. Headers
    text = text.replace(/^### (.*$)/gim, '<h4>$1</h4>');
    text = text.replace(/^## (.*$)/gim, '<h3>$1</h3>');
    text = text.replace(/^# (.*$)/gim, '<h2>$1</h2>');

    // 5. Bold & Italics
    text = text.replace(/\*\*\*(.*?)\*\*\*/g, '<strong><em>$1</em></strong>');
    text = text.replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>');
    text = text.replace(/\*([^*\n]+)\*/g, '<em>$1</em>');

    // 6. Blockquotes
    text = text.replace(/^\&gt;\s?(.*$)/gim, '<blockquote>$1</blockquote>');

    // 7. Unordered lists
    text = text.replace(/^\s*[-*]\s+(.*$)/gim, '<li>$1</li>');
    text = text.replace(/(<li>[\s\S]*?<\/li>)/gim, '<ul>$1</ul>');
    text = text.replace(/<\/ul>\s*<ul>/gim, '');

    // 8. Ordered lists
    text = text.replace(/^\s*(\d+)\.\s+(.*$)/gim, '<li>$2</li>');

    // 9. Markdown Tables (simple GFM table parser)
    text = text.replace(/((?:\|[^\n]+\|\r?\n)+)/g, (match) => {
        const rows = match.trim().split(/\r?\n/);
        if (rows.length < 2) return match;
        
        // Check if row 1 is delimiter (|---|---|)
        if (!rows[1].includes('---')) return match;

        let tableHtml = '<div class="table-wrapper"><table>';
        // Header
        const headerCols = rows[0].split('|').slice(1, -1);
        tableHtml += '<thead><tr>';
        headerCols.forEach(col => {
            tableHtml += `<th>${col.trim()}</th>`;
        });
        tableHtml += '</tr></thead><tbody>';

        // Body rows
        for (let i = 2; i < rows.length; i++) {
            const cols = rows[i].split('|').slice(1, -1);
            tableHtml += '<tr>';
            cols.forEach(col => {
                tableHtml += `<td>${col.trim()}</td>`;
            });
            tableHtml += '</tr>';
        }
        tableHtml += '</tbody></table></div>';
        return tableHtml;
    });

    // 10. Paragraphs and line breaks
    const paragraphs = text.split(/\r?\n\s*\r?\n/);
    text = paragraphs.map(p => {
        p = p.trim();
        if (!p) return '';
        if (p.startsWith('<h') || p.startsWith('<ul') || p.startsWith('<ol') || p.startsWith('<blockquote') || p.startsWith('<div class="table-wrapper"') || p.includes('__CODE_BLOCK_')) {
            return p;
        }
        return `<p>${p.replace(/\r?\n/g, '<br>')}</p>`;
    }).join('');

    // 11. Reinsert code blocks
    codeBlocks.forEach((block, idx) => {
        const replacement = `
            <div class="code-block-card">
                <div class="code-block-header">
                    <span>${block.lang}</span>
                    <button class="copy-code-btn" type="button" onclick="copySnippetText(this)">
                        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
                            <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
                        </svg>
                        Copy Code
                    </button>
                </div>
                <pre><code class="language-${block.lang}">${block.code}</code></pre>
            </div>
        `;
        text = text.replace(`__CODE_BLOCK_${idx}__`, replacement);
    });

    return text;
}

function generateSmartTitle(prompt) {
    if (!prompt) return 'New Conversation';
    const cleaned = prompt.replace(/\s+/g, ' ').trim();
    if (cleaned.length <= 34) return cleaned;
    return cleaned.substring(0, 32) + '...';
}

function formatTimestamp(timestamp) {
    if (!timestamp) return '';
    const date = new Date(timestamp);
    return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function capitalizeFirstLetter(str) {
    if (!str) return '';
    return str.charAt(0).toUpperCase() + str.slice(1);
}

function scrollToBottom() {
    const container = document.getElementById('messagesContainer');
    if (container) {
        container.scrollTop = container.scrollHeight;
    }
}

// ==========================================================================
// 8. CLIPBOARD & MODALS
// ==========================================================================

function copyToClipboard(text, onSuccess) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(onSuccess).catch(() => fallbackCopy(text, onSuccess));
    } else {
        fallbackCopy(text, onSuccess);
    }
}

function fallbackCopy(text, onSuccess) {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    try {
        document.execCommand('copy');
        if (onSuccess) onSuccess();
    } catch (e) {
        console.error('Copy failed:', e);
    }
    document.body.removeChild(ta);
}

function copySnippetText(btn) {
    const pre = btn.closest('.code-block-card').querySelector('code');
    if (!pre) return;
    const text = pre.textContent;

    copyToClipboard(text, () => {
        const orig = btn.innerHTML;
        btn.innerHTML = `
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#10b981" stroke-width="2.5">
                <polyline points="20 6 9 17 4 12"></polyline>
            </svg>
            <span style="color:#10b981;">Copied!</span>
        `;
        setTimeout(() => { btn.innerHTML = orig; }, 2000);
    });
}

function copyMessageText(btn) {
    const raw = decodeURIComponent(btn.getAttribute('data-content') || '');
    if (!raw) return;

    copyToClipboard(raw, () => {
        const orig = btn.innerHTML;
        btn.innerHTML = `
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#10b981" stroke-width="2.5">
                <polyline points="20 6 9 17 4 12"></polyline>
            </svg>
            <span style="color:#10b981;">Copied!</span>
        `;
        setTimeout(() => { btn.innerHTML = orig; }, 2000);
    });
}

// Sidebar Drawer
function toggleSidebar(open) {
    const sidebar = document.getElementById('sidebar');
    const backdrop = document.getElementById('sidebarBackdrop');
    if (!sidebar || !backdrop) return;

    if (open) {
        sidebar.classList.add('mobile-open');
        backdrop.classList.add('active');
    } else {
        sidebar.classList.remove('mobile-open');
        backdrop.classList.remove('active');
    }
}

// Settings Modal
function openSettingsModal() {
    const modal = document.getElementById('settingsModal');
    if (modal) {
        modal.classList.add('active');
    }
}

function closeSettingsModal() {
    const modal = document.getElementById('settingsModal');
    if (modal) {
        modal.classList.remove('active');
    }
}

// Input Auto-Resize
function adjustComposerHeight() {
    const ta = document.getElementById('composerTextarea');
    if (!ta) return;
    ta.style.height = 'auto';
    ta.style.height = Math.min(ta.scrollHeight, 170) + 'px';
}

function focusComposer() {
    const ta = document.getElementById('composerTextarea');
    if (ta) ta.focus();
}

// ==========================================================================
// 9. EVENT LISTENERS
// ==========================================================================

function setupEventListeners() {
    const ta = document.getElementById('composerTextarea');
    if (ta) {
        ta.addEventListener('input', adjustComposerHeight);
        ta.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                handleSendMessage();
            }
        });
    }

    // Modal background close
    const modal = document.getElementById('settingsModal');
    if (modal) {
        modal.addEventListener('click', (e) => {
            if (e.target === modal) {
                closeSettingsModal();
            }
        });
    }

    const memModal = document.getElementById('memoryModal');
    if (memModal) {
        memModal.addEventListener('click', (e) => {
            if (e.target === memModal) {
                closeMemoryModal();
            }
        });
    }

    const confirmModal = document.getElementById('confirmClearModal');
    if (confirmModal) {
        confirmModal.addEventListener('click', (e) => {
            if (e.target === confirmModal) {
                closeConfirmClearModal();
            }
        });
    }

    // Search input
    const searchInput = document.getElementById('searchFilterInput');
    if (searchInput) {
        searchInput.addEventListener('input', (e) => {
            handleSearchInput(e.target.value);
        });
    }
}

// ==========================================================================
// 10. PERSONAL MEMORY ENGINE
// ==========================================================================

function getUserId() {
    let uid = localStorage.getItem(STORAGE_KEYS.USER_ID);
    if (!uid || !uid.trim() || uid === 'default') {
        uid = 'user_' + (typeof crypto !== 'undefined' && crypto.randomUUID 
            ? crypto.randomUUID().replace(/-/g, '').substring(0, 16) 
            : Math.random().toString(36).substring(2, 14));
        localStorage.setItem(STORAGE_KEYS.USER_ID, uid);
    }
    state.userId = uid;
    return uid;
}

function loadLocalMemories() {
    try {
        const raw = localStorage.getItem(STORAGE_KEYS.MEMORIES);
        if (raw) {
            const parsed = JSON.parse(raw);
            if (Array.isArray(parsed)) {
                state.memories = parsed;
            }
        }
    } catch (e) {
        state.memories = [];
    }
    updateMemoryBadges(state.memories.length);
}

function saveLocalMemories() {
    localStorage.setItem(STORAGE_KEYS.MEMORIES, JSON.stringify(state.memories));
    updateMemoryBadges(state.memories.length);
}

async function initMemory() {
    loadLocalMemories();
    if (state.providerInfo.mode === 'LOCAL') {
        await fetchMemories();
    } else {
        renderMemoriesList();
        updateMemoryBadges(state.memories.length);
    }
}

async function fetchMemories() {
    if (state.providerInfo.mode === 'PUBLIC') {
        loadLocalMemories();
        renderMemoriesList();
        return;
    }

    try {
        const uid = getUserId();
        const resp = await fetch(getApiUrl(`/api/memory?userId=${encodeURIComponent(uid)}`), {
            headers: {
                'X-User-Id': uid,
                'Accept': 'application/json'
            }
        });
        if (!resp.ok) return;

        const data = await resp.json();
        if (data.success && Array.isArray(data.memories)) {
            // Merge remote memories with local memories
            const merged = [...state.memories];
            for (const m of data.memories) {
                if (!merged.some(ex => ex.id === m.id || ex.text === m.text)) {
                    merged.push(m);
                }
            }
            state.memories = merged;
            state.autoMemoryEnabled = data.autoMemoryEnabled !== false;
            saveLocalMemories();

            const badge = document.getElementById('memoryUserIdBadge');
            if (badge) {
                badge.textContent = 'User: ' + uid;
            }

            const toggle = document.getElementById('autoMemoryToggle');
            if (toggle) {
                toggle.checked = state.autoMemoryEnabled;
            }

            const memModal = document.getElementById('memoryModal');
            if (memModal && memModal.classList.contains('active')) {
                renderMemoriesList();
            }
        }
    } catch (e) {
        loadLocalMemories();
    }
}

function updateMemoryBadges(count) {
    const elHeader = document.getElementById('headerMemoryCount');
    if (elHeader) elHeader.textContent = count;

    const elSidebar = document.getElementById('sidebarMemoryCount');
    if (elSidebar) elSidebar.textContent = count;

    const elModal = document.getElementById('memoryModalCount');
    if (elModal) elModal.textContent = count;
}

function openMemoryModal() {
    state.editingMemoryId = null;
    const modal = document.getElementById('memoryModal');
    if (modal) {
        modal.classList.add('active');
        fetchMemories().then(() => {
            renderMemoriesList();
            const input = document.getElementById('manualMemoryInput');
            if (input) input.focus();
        });
    }
}

function closeMemoryModal() {
    state.editingMemoryId = null;
    const modal = document.getElementById('memoryModal');
    if (modal) {
        modal.classList.remove('active');
    }
}

function renderMemoriesList() {
    const container = document.getElementById('memoryItemsContainer');
    if (!container) return;

    if (!state.memories || state.memories.length === 0) {
        container.innerHTML = `
            <div class="memory-empty-state">
                <div class="memory-empty-icon">🧠</div>
                <div style="font-weight:600; margin-bottom:4px; color:var(--text-main);">No Memories Stored Yet</div>
                <div>NEXORA will automatically learn key projects, tools, and preferences as you chat, or you can add them manually above.</div>
            </div>
        `;
        return;
    }

    // Sort newest updated first
    const sorted = [...state.memories].sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));

    container.innerHTML = sorted.map(mem => {
        const isEditing = state.editingMemoryId === mem.id;
        const badgeClass = getCategoryBadgeClass(mem.category);
        const dateStr = formatMemoryDate(mem.updatedAt || mem.createdAt);
        const sourceLabel = mem.source === 'auto' ? 'Auto-extracted' : 'Manual';

        if (isEditing) {
            return `
                <div class="memory-item-card" id="memCard_${escapeHtml(mem.id)}">
                    <div class="memory-edit-box">
                        <input type="text" id="editInput_${escapeHtml(mem.id)}" class="memory-edit-input" value="${escapeHtml(mem.text)}" maxlength="500">
                        <div style="display:flex; justify-content:space-between; align-items:center;">
                            <select id="editCat_${escapeHtml(mem.id)}" class="memory-category-select" style="font-size:11.5px; padding:4px 8px;">
                                <option value="project" ${mem.category === 'project' ? 'selected' : ''}>Project</option>
                                <option value="skill" ${mem.category === 'skill' ? 'selected' : ''}>Skill / Language</option>
                                <option value="preference" ${mem.category === 'preference' ? 'selected' : ''}>Preference</option>
                                <option value="tool" ${mem.category === 'tool' ? 'selected' : ''}>Tool</option>
                                <option value="goal" ${mem.category === 'goal' ? 'selected' : ''}>Goal</option>
                                <option value="general" ${mem.category === 'general' ? 'selected' : ''}>General</option>
                            </select>
                            <div class="memory-edit-buttons">
                                <button class="btn-secondary" onclick="cancelEditMemory()" style="font-size:11.5px; padding:4px 10px;">Cancel</button>
                                <button class="btn-primary" onclick="saveEditMemory('${escapeHtml(mem.id)}')" style="font-size:11.5px; padding:4px 12px;">Save</button>
                            </div>
                        </div>
                    </div>
                </div>
            `;
        }

        return `
            <div class="memory-item-card" id="memCard_${escapeHtml(mem.id)}">
                <div class="memory-item-main">
                    <div class="memory-item-text">${escapeHtml(mem.text)}</div>
                    <div class="memory-item-meta">
                        <span class="memory-badge ${badgeClass}">${escapeHtml(mem.category || 'general')}</span>
                        <span>•</span>
                        <span>${sourceLabel}</span>
                        <span>•</span>
                        <span>${dateStr}</span>
                    </div>
                </div>
                <div class="memory-item-actions">
                    <button class="btn-memory-action" onclick="startEditMemory('${escapeHtml(mem.id)}')" title="Edit memory">
                        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <path d="M12 20h9"></path>
                            <path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"></path>
                        </svg>
                    </button>
                    <button class="btn-memory-action btn-delete" onclick="deleteMemory('${escapeHtml(mem.id)}')" title="Delete memory">
                        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <polyline points="3 6 5 6 21 6"></polyline>
                            <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
                        </svg>
                    </button>
                </div>
            </div>
        `;
    }).join('');
}

function getCategoryBadgeClass(category) {
    if (!category) return 'badge-general';
    switch (category.toLowerCase()) {
        case 'project': return 'badge-project';
        case 'skill': return 'badge-skill';
        case 'preference': return 'badge-preference';
        case 'tool': return 'badge-tool';
        case 'goal': return 'badge-goal';
        default: return 'badge-general';
    }
}

function formatMemoryDate(ts) {
    if (!ts) return '';
    try {
        const d = new Date(ts);
        return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
    } catch (e) {
        return '';
    }
}

async function handleManualAddMemory() {
    const input = document.getElementById('manualMemoryInput');
    const select = document.getElementById('manualMemoryCategory');
    if (!input || !input.value.trim()) {
        if (input) input.focus();
        return;
    }

    const text = input.value.trim();
    const category = select ? select.value : 'general';
    const uid = getUserId();

    const newMem = {
        id: 'mem_' + Date.now().toString(36) + '_' + Math.random().toString(36).substring(2, 7),
        text: text,
        category: category,
        source: 'manual',
        createdAt: Date.now(),
        updatedAt: Date.now()
    };

    // Client-side persistent privacy isolation
    state.memories.unshift(newMem);
    saveLocalMemories();

    // In LOCAL mode, mirror to local server storage
    if (state.providerInfo.mode === 'LOCAL') {
        try {
            await fetch(getApiUrl('/api/memory'), {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json; charset=UTF-8',
                    'X-User-Id': uid
                },
                body: JSON.stringify({
                    text: text,
                    category: category,
                    userId: uid
                })
            });
        } catch (ignored) {}
    }

    input.value = '';
    showToast('🧠 Personal memory added');
    renderMemoriesList();
}

function startEditMemory(id) {
    state.editingMemoryId = id;
    renderMemoriesList();
    const input = document.getElementById(`editInput_${id}`);
    if (input) {
        input.focus();
        input.setSelectionRange(input.value.length, input.value.length);
    }
}

function cancelEditMemory() {
    state.editingMemoryId = null;
    renderMemoriesList();
}

async function saveEditMemory(id) {
    const input = document.getElementById(`editInput_${id}`);
    const select = document.getElementById(`editCat_${id}`);
    if (!input || !input.value.trim()) {
        alert('Memory text cannot be empty.');
        return;
    }

    const newText = input.value.trim();
    const newCategory = select ? select.value : 'general';
    const uid = getUserId();

    const mem = state.memories.find(m => m.id === id);
    if (mem) {
        mem.text = newText;
        mem.category = newCategory;
        mem.updatedAt = Date.now();
        saveLocalMemories();
    }

    if (state.providerInfo.mode === 'LOCAL') {
        try {
            await fetch(getApiUrl('/api/memory'), {
                method: 'PUT',
                headers: {
                    'Content-Type': 'application/json; charset=UTF-8',
                    'X-User-Id': uid
                },
                body: JSON.stringify({
                    id: id,
                    text: newText,
                    category: newCategory,
                    userId: uid
                })
            });
        } catch (ignored) {}
    }

    state.editingMemoryId = null;
    showToast('🧠 Memory updated');
    renderMemoriesList();
}

async function deleteMemory(id) {
    const uid = getUserId();
    state.memories = state.memories.filter(m => m.id !== id);
    saveLocalMemories();

    if (state.providerInfo.mode === 'LOCAL') {
        try {
            await fetch(getApiUrl(`/api/memory?id=${encodeURIComponent(id)}&userId=${encodeURIComponent(uid)}`), {
                method: 'DELETE',
                headers: {
                    'X-User-Id': uid
                }
            });
        } catch (ignored) {}
    }

    showToast('Memory deleted');
    renderMemoriesList();
}

function promptClearAllMemories() {
    const modal = document.getElementById('confirmClearModal');
    if (modal) {
        modal.classList.add('active');
    }
}

function closeConfirmClearModal() {
    const modal = document.getElementById('confirmClearModal');
    if (modal) {
        modal.classList.remove('active');
    }
}

async function executeClearAllMemories() {
    closeConfirmClearModal();
    const uid = getUserId();
    state.memories = [];
    saveLocalMemories();

    if (state.providerInfo.mode === 'LOCAL') {
        try {
            await fetch(getApiUrl(`/api/memory?clearAll=true&userId=${encodeURIComponent(uid)}`), {
                method: 'DELETE',
                headers: {
                    'X-User-Id': uid
                }
            });
        } catch (ignored) {}
    }

    showToast('All personal memories cleared');
    renderMemoriesList();
}

function exportMemories() {
    if (!state.memories || state.memories.length === 0) {
        showToast('No personal memories to export.');
        return;
    }
    const jsonStr = JSON.stringify(state.memories, null, 2);
    const blob = new Blob([jsonStr], { type: 'application/json; charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `nexora_memories_${new Date().toISOString().slice(0, 10)}.json`;
    a.style.display = 'none';
    a.style.position = 'fixed';
    a.style.top = '-9999px';
    a.style.left = '-9999px';
    a.setAttribute('aria-hidden', 'true');
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    showToast('🧠 Memories exported');
}

async function toggleAutoMemory(enabled) {
    state.autoMemoryEnabled = enabled;
    localStorage.setItem('nexora_auto_memory_v1', enabled ? 'true' : 'false');

    if (state.providerInfo.mode === 'LOCAL') {
        try {
            const uid = getUserId();
            await fetch(getApiUrl('/api/memory'), {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json; charset=UTF-8',
                    'X-User-Id': uid
                },
                body: JSON.stringify({
                    action: 'set_settings',
                    autoMemoryEnabled: enabled,
                    userId: uid
                })
            });
        } catch (ignored) {}
    }

    showToast(enabled ? 'Automatic memory extraction enabled' : 'Automatic memory extraction disabled');
}

function showToast(message) {
    let toast = document.getElementById('nexoraGlobalToast');
    if (!toast) {
        toast = document.createElement('div');
        toast.id = 'nexoraGlobalToast';
        toast.className = 'nexora-toast';
        document.body.appendChild(toast);
    }

    toast.textContent = message;
    toast.classList.add('show');

    clearTimeout(toast._timer);
    toast._timer = setTimeout(() => {
        toast.classList.remove('show');
    }, 2800);
}

// ==========================================================================
// 12. AI MODEL CENTER & INTELLIGENT MODEL MANAGEMENT MODULE
// ==========================================================================

let deleteTargetModelName = null;
let catalogSearchDebounceTimer = null;

function openModelCenterModal() {
    const modal = document.getElementById('modelCenterModal');
    if (!modal) return;
    document.body.classList.add('modal-open');
    modal.classList.add('active');

    // Populate current chat options
    updateCurrentChatModelPicker();

    // Trigger full data refresh
    loadModelSettings();
    loadInstalledModels();
    loadHardwareTelemetry();
    loadModelProfiles();
    loadFavorites();
    loadPerformanceHistory();
    checkAllConnections();
}

function closeModelCenterModal() {
    const modal = document.getElementById('modelCenterModal');
    if (modal) modal.classList.remove('active');
    document.body.classList.remove('modal-open');
}

function switchModelCenterTab(tabName) {
    const buttons = document.querySelectorAll('.mc-tab-btn');
    buttons.forEach(btn => {
        if (btn.dataset.tab === tabName) {
            btn.classList.add('active');
        } else {
            btn.classList.remove('active');
        }
    });

    const panes = {
        'installed': document.getElementById('mcTabInstalled'),
        'catalog': document.getElementById('mcTabCatalog'),
        'profiles': document.getElementById('mcTabProfiles'),
        'performance': document.getElementById('mcTabPerformance')
    };

    Object.keys(panes).forEach(k => {
        if (panes[k]) {
            if (k === tabName) {
                panes[k].classList.add('active');
            } else {
                panes[k].classList.remove('active');
            }
        }
    });

    if (tabName === 'catalog' && (!state.cloudCatalog || state.cloudCatalog.length === 0)) {
        loadOpenRouterCatalog();
    }
    if (tabName === 'performance') {
        loadPerformanceHistory();
    }
}

// -------------------------------------------------------------------------
// Section 1: AI Provider Selector
// -------------------------------------------------------------------------

async function setAiProvider(provider) {
    state.selectedProvider = provider;
    const btnLocal = document.getElementById('btnProviderLocal');
    const btnPublic = document.getElementById('btnProviderPublic');
    const badge = document.getElementById('mcCurrentProviderBadge');

    if (btnLocal && btnPublic) {
        if (provider === 'LOCAL') {
            btnLocal.classList.add('active');
            btnPublic.classList.remove('active');
        } else {
            btnPublic.classList.add('active');
            btnLocal.classList.remove('active');
        }
    }

    if (badge) {
        badge.textContent = provider;
        badge.className = (provider === 'LOCAL') ? 'mc-badge-cyan' : 'mc-badge-purple';
    }

    const current = getActiveChat();
    if (current) {
        current.provider = provider;
        saveChats();
    }

    updateHeaderModelBadge();
    showToast(`AI Engine set to ${provider === 'LOCAL' ? 'Local Ollama' : 'OpenRouter Cloud'}`);
}

// -------------------------------------------------------------------------
// Section 2: Connection Status Checker
// -------------------------------------------------------------------------

async function checkAllConnections() {
    const ollamaDot = document.getElementById('mcOllamaDot');
    const ollamaText = document.getElementById('mcOllamaStatusText');
    const openRouterDot = document.getElementById('mcOpenRouterDot');
    const openRouterText = document.getElementById('mcOpenRouterStatusText');

    if (ollamaText) ollamaText.textContent = 'Checking...';
    if (openRouterText) openRouterText.textContent = 'Checking...';

    // Check Ollama via installed models endpoint
    try {
        const t0 = Date.now();
        const res = await fetch(getApiUrl('/api/models/installed'));
        const elapsed = Date.now() - t0;
        if (res.ok) {
            const data = await res.json();
            if (data.success) {
                if (ollamaDot) ollamaDot.className = 'status-dot online';
                if (ollamaText) ollamaText.textContent = `Online · ${elapsed}ms (${data.count} models)`;
            } else {
                if (ollamaDot) ollamaDot.className = 'status-dot offline';
                if (ollamaText) ollamaText.textContent = data.error || 'Offline';
            }
        } else {
            if (ollamaDot) ollamaDot.className = 'status-dot offline';
            if (ollamaText) ollamaText.textContent = `Offline (HTTP ${res.status})`;
        }
    } catch (e) {
        if (ollamaDot) ollamaDot.className = 'status-dot offline';
        if (ollamaText) ollamaText.textContent = 'Offline (Connection refused)';
    }

    // Check OpenRouter via catalog endpoint
    try {
        const t0 = Date.now();
        const res = await fetch(getApiUrl('/api/models/catalog?freeOnly=true'));
        const elapsed = Date.now() - t0;
        if (res.ok) {
            const data = await res.json();
            if (openRouterDot) openRouterDot.className = 'status-dot online';
            const keyNotice = data.hasApiKey ? 'Key active' : 'Public browse';
            if (openRouterText) openRouterText.textContent = `Online · ${elapsed}ms (${keyNotice})`;
        } else {
            if (openRouterDot) openRouterDot.className = 'status-dot offline';
            if (openRouterText) openRouterText.textContent = `HTTP ${res.status}`;
        }
    } catch (e) {
        if (openRouterDot) openRouterDot.className = 'status-dot offline';
        if (openRouterText) openRouterText.textContent = 'Catalog unreachable';
    }
}

// -------------------------------------------------------------------------
// Section 3: Hardware Recommendations Advisor
// -------------------------------------------------------------------------

async function loadHardwareTelemetry() {
    try {
        const res = await fetch(getApiUrl('/api/models/hardware'));
        if (res.ok) {
            const data = await res.json();
            if (data.success && data.hardware) {
                state.hardwareInfo = data.hardware;
                renderHardwareBanner(data.hardware);
            }
        }
    } catch (e) {
        console.warn('[ModelCenter] Could not load hardware telemetry:', e);
    }
}

function renderHardwareBanner(hw) {
    const badge = document.getElementById('mcHwBadge');
    const details = document.getElementById('mcHwDetails');
    const ramTotal = document.getElementById('mcHwRamTotal');
    const ramFree = document.getElementById('mcHwRamFree');

    if (badge && hw.cpuName) {
        badge.textContent = hw.cpuName;
    }
    if (ramTotal && hw.totalRamGb) {
        ramTotal.textContent = hw.totalRamGb;
    }
    if (ramFree && hw.freeRamGb) {
        ramFree.textContent = hw.freeRamGb;
    }
    if (details) {
        details.innerHTML = `Physical Memory: <strong>${hw.totalRamGb || '16 GB'}</strong> · Free: <strong>${hw.freeRamGb || '8 GB'}</strong> · Cores: <strong>${hw.processorCores || 6}</strong> (${hw.cpuArch || 'amd64'})`;
    }
}

// -------------------------------------------------------------------------
// Section 4: Installed Local Models
// -------------------------------------------------------------------------

async function loadInstalledModels() {
    const list = document.getElementById('mcInstalledList');
    const countBadge = document.getElementById('mcInstalledCount');
    if (list) list.innerHTML = '<div style="padding:20px; text-align:center; color:var(--text-muted); grid-column:1/-1;">Loading installed models...</div>';

    try {
        const res = await fetch(getApiUrl('/api/models/installed'));
        if (res.ok) {
            const data = await res.json();
            if (data.success && Array.isArray(data.models)) {
                state.installedModels = data.models;
                if (countBadge) countBadge.textContent = data.models.length;
                renderInstalledModels(data.models);
                updateModelSelectDropdowns();
                return;
            } else if (data.error) {
                if (list) list.innerHTML = `<div style="padding:20px; text-align:center; color:var(--danger); grid-column:1/-1;">⚠️ ${escapeHtml(data.error)}</div>`;
                return;
            }
        }
        if (list) list.innerHTML = '<div style="padding:20px; text-align:center; color:var(--text-muted); grid-column:1/-1;">No installed Ollama models detected.</div>';
    } catch (e) {
        if (list) list.innerHTML = `<div style="padding:20px; text-align:center; color:var(--danger); grid-column:1/-1;">Could not reach Ollama: ${escapeHtml(e.message)}</div>`;
    }
}

function renderInstalledModels(models) {
    const list = document.getElementById('mcInstalledList');
    if (!list) return;

    if (!models || models.length === 0) {
        list.innerHTML = `
            <div style="padding:30px; text-align:center; color:var(--text-muted); grid-column:1/-1; background:var(--bg-base); border-radius:var(--radius-md);">
                <div style="font-size:24px; margin-bottom:8px;">📦</div>
                <div>No local models found in Ollama.</div>
                <div style="font-size:12px; margin-top:4px;">Use the install section below to download <code>qwen2.5:7b</code> or <code>qwen2.5:3b</code>.</div>
            </div>
        `;
        return;
    }

    const currentChat = getActiveChat();
    const activeModelId = state.selectedModel || currentChat?.model || 'qwen2.5:7b';
    const favSet = new Set(state.favorites || []);

    let html = '';
    models.forEach(m => {
        const isActive = m.id === activeModelId;
        const isFav = favSet.has(m.id) || m.isFavorite;
        const sizeFormatted = m.sizeBytes ? formatBytes(m.sizeBytes) : 'Unknown size';
        const suitability = m.suitabilityLabel || 'RECOMMENDED';
        const suitPillClass = suitability === 'HIGH_RAM' ? 'high' : (suitability === 'MAY_RUN_SLOWLY' ? 'slow' : 'rec');

        const isProtected = m.id === 'qwen2.5:7b' || m.id === 'qwen2.5:3b';

        html += `
            <div class="mc-model-card ${isActive ? 'active-model' : ''}">
                <div>
                    <div class="mc-model-header">
                        <div style="display:flex; align-items:center; flex-wrap:wrap; gap:4px;">
                            <span class="mc-model-name">${escapeHtml(m.name)}</span>
                            <span class="mc-model-tag">${escapeHtml(m.tag || 'latest')}</span>
                        </div>
                        <button class="mc-fav-btn ${isFav ? 'active' : ''}" onclick="toggleModelFavorite('${escapeHtml(m.id)}')" title="${isFav ? 'Remove from favorites' : 'Add to favorites'}">
                            ★
                        </button>
                    </div>

                    <div class="mc-model-meta-row">
                        <span>📦 ${sizeFormatted}</span>
                        ${m.parameterSize ? `<span>⚡ ${escapeHtml(m.parameterSize)}</span>` : ''}
                        ${m.quantization ? `<span>🎯 ${escapeHtml(m.quantization)}</span>` : ''}
                    </div>

                    <div style="margin: 6px 0;">
                        <span class="mc-suitability-pill ${suitPillClass}">
                            ${suitability === 'HIGH_RAM' ? '⛔' : (suitability === 'MAY_RUN_SLOWLY' ? '⚠️' : '✓')} ${suitability}
                        </span>
                    </div>

                    ${m.suitabilityReason ? `<div style="font-size:11px; color:var(--text-muted); line-height:1.4;">${escapeHtml(m.suitabilityReason)}</div>` : ''}
                </div>

                <div class="mc-model-actions">
                    <button class="btn-secondary" onclick="inspectModelDetails('${escapeHtml(m.id)}', 'LOCAL')" style="font-size:11px; padding:3px 8px;">
                        ℹ️ Details
                    </button>
                    <div style="display:flex; gap:6px;">
                        <button class="btn-primary" onclick="selectQuickModel('LOCAL', '${escapeHtml(m.id)}')" style="font-size:11px; padding:3px 10px; ${isActive ? 'background:var(--accent-purple); opacity:0.85;' : ''}">
                            ${isActive ? '✓ Selected' : 'Select'}
                        </button>
                        ${!isProtected ? `
                            <button class="btn-secondary" onclick="confirmDeleteModel('${escapeHtml(m.id)}')" style="font-size:11px; padding:3px 8px; color:var(--danger); border-color:rgba(239, 68, 68, 0.25);" title="Uninstall model">
                                🗑️
                            </button>
                        ` : ''}
                    </div>
                </div>
            </div>
        `;
    });

    list.innerHTML = html;
}

// -------------------------------------------------------------------------
// Section 5: Streaming Model Installation
// -------------------------------------------------------------------------

function setPullInput(tag) {
    const input = document.getElementById('mcPullInput');
    if (input) {
        input.value = tag;
        input.focus();
        input.dispatchEvent(new Event('input', { bubbles: true }));
    }
    // Visually toggle active/selected chip state
    document.querySelectorAll('.mc-chip-btn').forEach(btn => {
        const btnTag = btn.getAttribute('data-tag') || btn.textContent.trim();
        if (btnTag === tag) {
            btn.classList.add('active', 'selected');
        } else {
            btn.classList.remove('active', 'selected');
        }
    });
}

async function startModelPull() {
    const input = document.getElementById('mcPullInput');
    const modelTag = input ? input.value.trim() : '';

    if (!modelTag) {
        alert('Please enter a valid model identifier (e.g. qwen2.5:3b, llama3.2:3b).');
        if (input) input.focus();
        return;
    }

    // Check if another pull is actively running
    if (state.activePullAbort) {
        alert('Another model installation is already in progress. Please wait for it to finish or click Cancel.');
        return;
    }

    // Check if model is already installed
    const isAlreadyInstalled = state.installedModels && state.installedModels.some(m => m.id === modelTag || m.name === modelTag);
    if (isAlreadyInstalled) {
        showToast(`Model "${modelTag}" is already installed locally in Ollama!`);
        if (!confirm(`"${modelTag}" is already installed on your system.\n\nDo you want to re-download or update it anyway?`)) {
            return;
        }
    }

    const progressCard = document.getElementById('mcPullProgressCard');
    const progressBar = document.getElementById('mcPullProgressBar');
    const title = document.getElementById('mcPullStatusTitle');
    const nameLabel = document.getElementById('mcPullModelName');
    const percentLabel = document.getElementById('mcPullPercent');
    const sizeLabel = document.getElementById('mcPullSize');
    const speedLabel = document.getElementById('mcPullSpeed');
    const startBtn = document.getElementById('btnStartPull');

    if (progressCard) progressCard.style.display = 'block';
    if (nameLabel) nameLabel.textContent = modelTag;
    if (title) title.textContent = 'Connecting to Ollama...';
    if (progressBar) progressBar.style.width = '0%';
    if (percentLabel) percentLabel.textContent = '0%';
    if (sizeLabel) sizeLabel.textContent = '0 MB / 0 MB';
    if (speedLabel) speedLabel.textContent = '0 MB/s';
    if (startBtn) startBtn.disabled = true;

    state.activePullAbort = new AbortController();

    try {
        const response = await fetch(getApiUrl('/api/models/pull'), {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json; charset=UTF-8',
                'Accept': 'text/event-stream'
            },
            body: JSON.stringify({ name: modelTag }),
            signal: state.activePullAbort.signal
        });

        if (!response.ok) {
            let errorText = `Server returned HTTP ${response.status}`;
            try {
                const errJson = await response.json();
                if (errJson.error) errorText = errJson.error;
            } catch (e) {}
            throw new Error(errorText);
        }

        const reader = response.body.getReader();
        const decoder = new TextDecoder('utf-8');
        let buffer = '';
        let pullError = null;
        let pullSuccess = false;

        while (true) {
            const { done, value } = await reader.read();
            if (done) break;

            buffer += decoder.decode(value, { stream: true });
            const lines = buffer.split('\n');
            buffer = lines.pop(); // keep partial line

            for (const line of lines) {
                const trimmed = line.trim();
                if (!trimmed.startsWith('data:')) continue;
                const payload = trimmed.substring(5).trim();
                if (payload === '[DONE]') {
                    pullSuccess = true;
                    break;
                }

                let data = null;
                try {
                    data = JSON.parse(payload);
                } catch (pe) {
                    // ignore partial json
                }

                if (data) {
                    if (data.error) {
                        pullError = data.error;
                        break;
                    }
                    if (data.status) {
                        if (title) title.textContent = data.status;
                    }
                    if (data.percent !== undefined) {
                        const p = Math.min(100, Math.max(0, data.percent));
                        if (progressBar) progressBar.style.width = `${p}%`;
                        if (percentLabel) percentLabel.textContent = `${p}%`;
                    }
                    if (data.completedFormatted && data.totalFormatted) {
                        if (sizeLabel) sizeLabel.textContent = `${data.completedFormatted} / ${data.totalFormatted}`;
                    }
                    if (data.speedFormatted) {
                        if (speedLabel) speedLabel.textContent = data.speedFormatted;
                    }
                    if (data.done) {
                        pullSuccess = true;
                    }
                }
            }

            if (pullError || pullSuccess) {
                break;
            }
        }

        if (pullError) {
            throw new Error(pullError);
        }

        if (pullSuccess) {
            if (title) title.textContent = 'Installation Complete!';
            if (progressBar) progressBar.style.width = '100%';
            if (percentLabel) percentLabel.textContent = '100%';
            showToast(`Successfully installed ${modelTag}`);
            await loadInstalledModels();
        }

    } catch (err) {
        if (err.name === 'AbortError') {
            if (title) title.textContent = 'Installation Cancelled.';
            showToast('Model installation was cancelled.');
        } else {
            if (title) title.textContent = 'Installation Failed: ' + err.message;
            alert('Failed to install model: ' + err.message);
        }
    } finally {
        if (startBtn) startBtn.disabled = false;
        state.activePullAbort = null;
        setTimeout(() => {
            if (progressCard && !state.activePullAbort) {
                progressCard.style.display = 'none';
            }
        }, 5000);
    }
}

function cancelModelPull() {
    if (state.activePullAbort) {
        state.activePullAbort.abort();
        state.activePullAbort = null;
    }
}

// -------------------------------------------------------------------------
// Section 4b: Safe Model Deletion
// -------------------------------------------------------------------------

function confirmDeleteModel(modelName) {
    deleteTargetModelName = modelName;
    const modal = document.getElementById('confirmDeleteModelModal');
    const label = document.getElementById('deleteModelNameTarget');
    if (label) label.textContent = modelName;
    if (modal) modal.classList.add('active');
}

function closeConfirmDeleteModelModal() {
    deleteTargetModelName = null;
    const modal = document.getElementById('confirmDeleteModelModal');
    if (modal) modal.classList.remove('active');
}

async function executeDeleteModel() {
    if (!deleteTargetModelName) return;
    const target = deleteTargetModelName;
    closeConfirmDeleteModelModal();

    try {
        const res = await fetch(getApiUrl('/api/models/delete'), {
            method: 'POST',
            headers: { 'Content-Type': 'application/json; charset=UTF-8' },
            body: JSON.stringify({ name: target, confirmed: true })
        });

        const data = await res.json();
        if (res.ok && data.success) {
            showToast(`Uninstalled model ${target}`);
            await loadInstalledModels();
        } else {
            alert(data.error || 'Failed to delete model.');
        }
    } catch (e) {
        alert('Network error while deleting model: ' + e.message);
    }
}

// -------------------------------------------------------------------------
// Section 6: OpenRouter Cloud Catalog
// -------------------------------------------------------------------------

async function loadOpenRouterCatalog(forceRefresh = false) {
    const list = document.getElementById('mcCatalogList');
    const countBadge = document.getElementById('mcCatalogCount');
    if (list) list.innerHTML = '<div style="padding:20px; text-align:center; color:var(--text-muted); grid-column:1/-1;">Fetching cloud catalog from OpenRouter...</div>';

    const freeOnly = document.getElementById('mcCatalogFreeOnly')?.checked || false;
    const search = document.getElementById('mcCatalogSearch')?.value?.trim() || '';

    try {
        let url = `/api/models/catalog?freeOnly=${freeOnly}&search=${encodeURIComponent(search)}`;
        if (forceRefresh) {
            url += '&refresh=true';
        }
        const res = await fetch(getApiUrl(url));
        if (res.ok) {
            const data = await res.json();
            if (data.success && Array.isArray(data.models)) {
                if (typeof data.hasApiKey !== 'undefined') {
                    state.hasOpenRouterApiKey = !!data.hasApiKey;
                }
                state.cloudCatalog = data.models;
                if (countBadge) countBadge.textContent = data.models.length;
                updateCatalogNotice();
                renderOpenRouterCatalog(data.models);
                migrateDeprecatedCloudModel();
                return;
            }
        }
        if (list) list.innerHTML = '<div style="padding:20px; text-align:center; color:var(--text-muted); grid-column:1/-1;">Could not load OpenRouter catalog.</div>';
    } catch (e) {
        if (list) list.innerHTML = `<div style="padding:20px; text-align:center; color:var(--danger); grid-column:1/-1;">Network error: ${escapeHtml(e.message)}</div>`;
    }
}

function migrateDeprecatedCloudModel() {
    const DEPRECATED_MODELS = [
        'deepseek/deepseek-r1:free',
        'meta-llama/llama-3.2-3b-instruct:free',
        'inclusionai/ling-3.2-flash:free',
        'google/gemma-4-26b-a4b-it:free',
        'google/gemma-4-31b-it:free',
        'baidu/ernie-5.5-flash-preview:free',
        'particle-ai/particle-1:free'
    ];
    const currentChat = getActiveChat();
    if (currentChat && DEPRECATED_MODELS.includes(currentChat.model)) {
        currentChat.model = 'openrouter/free';
        currentChat.provider = 'PUBLIC';
        saveChats();
        if (DEPRECATED_MODELS.includes(state.selectedModel)) {
            state.selectedModel = 'openrouter/free';
            state.selectedProvider = 'PUBLIC';
        }
        updateHeaderModelBadge();
    }
    if (state.modelSettings && DEPRECATED_MODELS.includes(state.modelSettings.defaultCloudModel)) {
        state.modelSettings.defaultCloudModel = 'openrouter/free';
        const cloudSelect = document.getElementById('mcSettingsDefaultCloudModel');
        if (cloudSelect) cloudSelect.value = 'openrouter/free';
    }
}

function updateCatalogNotice() {
    const notice = document.getElementById('mcCatalogNotice');
    if (!notice) return;
    if (state.hasOpenRouterApiKey) {
        notice.className = '';
        notice.style.cssText = 'font-size:11.5px; color:var(--text-muted); margin-bottom:10px; background:var(--bg-base); padding:8px 12px; border-radius:var(--radius-sm); border:1px solid var(--border-subtle);';
        notice.innerHTML = 'ℹ️ OpenRouter models run in the cloud. Free-tier models have <code>:free</code> tag. API keys are handled securely on the Java server.';
    } else {
        notice.className = 'mc-alert-warning';
        notice.style.cssText = '';
        notice.innerHTML = `
            <span style="font-size:18px; line-height:1;">🔑</span>
            <div>
                <strong>OpenRouter API Key Not Configured</strong>
                <div style="font-size:12px; margin-top:3px; opacity:0.95;">
                    To chat with cloud models, create a <code>.env</code> file in your project directory with <code>OPENROUTER_API_KEY=your_key</code> and restart NEXORA.
                    <span style="display:block; margin-top:2px; color:var(--text-secondary);">Local Ollama models (e.g. <code>qwen2.5:3b</code> and <code>qwen2.5:7b</code>) remain 100% private, free, and require no API key.</span>
                </div>
            </div>
        `;
    }
}

function filterCatalogDebounced() {
    clearTimeout(catalogSearchDebounceTimer);
    catalogSearchDebounceTimer = setTimeout(() => {
        loadOpenRouterCatalog();
    }, 350);
}

function renderOpenRouterCatalog(models) {
    const list = document.getElementById('mcCatalogList');
    if (!list) return;

    updateCatalogNotice();

    if (!models || models.length === 0) {
        list.innerHTML = `
            <div style="padding:30px; text-align:center; color:var(--text-muted); grid-column:1/-1; background:var(--bg-base); border-radius:var(--radius-md);">
                <div>No cloud models match your current filter.</div>
            </div>
        `;
        return;
    }

    const currentChat = getActiveChat();
    const activeModelId = state.selectedModel || currentChat?.model || '';
    const favSet = new Set(state.favorites || []);

    let html = '';
    models.forEach(m => {
        const isActive = m.id === activeModelId;
        const isFav = favSet.has(m.id) || m.isFavorite;
        const isFree = m.pricingPrompt === 0 && m.pricingCompletion === 0;
        const isVerified = m.status === 'verified';
        const isRateLimited = m.status === 'rate_limited';

        html += `
            <div class="mc-model-card ${isActive ? 'active-model' : ''}">
                <div>
                    <div class="mc-model-header">
                        <div style="display:flex; align-items:center; flex-wrap:wrap; gap:6px;">
                            <span class="mc-model-name">${escapeHtml(m.name)}</span>
                            ${isVerified ? '<span class="mc-badge-green" style="font-size:10px;">✓ VERIFIED FREE</span>' : (isRateLimited ? '<span style="font-size:10px; padding:2px 6px; border-radius:4px; background:rgba(245,158,11,0.18); color:#f59e0b; border:1px solid rgba(245,158,11,0.3); font-weight:600;">⚠️ QUOTA REACHED</span>' : (isFree ? '<span class="mc-badge-green" style="font-size:10px;">FREE</span>' : ''))}
                        </div>
                        <button class="mc-fav-btn ${isFav ? 'active' : ''}" onclick="toggleModelFavorite('${escapeHtml(m.id)}')" title="${isFav ? 'Remove from favorites' : 'Add to favorites'}">
                            ★
                        </button>
                    </div>

                    <div style="font-size:11px; font-family:var(--font-mono, monospace); color:var(--accent-purple); margin-bottom:6px; word-break:break-word; overflow-wrap:anywhere;">
                        ${escapeHtml(m.id)}
                    </div>

                    <div class="mc-model-meta-row">
                        <span>Context: ${m.contextLength ? formatNumber(m.contextLength) : '4k'} tokens</span>
                        <span>Prompt: ${isFree ? '$0.00' : '$' + m.pricingPrompt}</span>
                    </div>

                    <div style="margin: 6px 0;">
                        ${isVerified
                            ? '<span class="mc-suitability-pill rec">✓ RECOMMENDED (Verified Active)</span>'
                            : (isRateLimited
                                ? '<span class="mc-suitability-pill warn" style="background:rgba(245,158,11,0.15); color:#f59e0b; border:1px solid rgba(245,158,11,0.3);">⚠️ Free Tier Quota Exhausted</span>'
                                : '<span class="mc-suitability-pill rec">✓ RECOMMENDED (Cloud Hosted)</span>')
                        }
                    </div>
                </div>

                <div class="mc-model-actions">
                    <button class="btn-secondary" onclick="inspectModelDetails('${escapeHtml(m.id)}', 'PUBLIC')" style="font-size:11px; padding:3px 8px;">
                        ℹ️ Details
                    </button>
                    <button class="btn-primary" onclick="selectQuickModel('PUBLIC', '${escapeHtml(m.id)}')" style="font-size:11px; padding:3px 10px; ${isActive ? 'background:var(--accent-purple); opacity:0.85;' : ''}" title="${!state.hasOpenRouterApiKey ? 'Requires OPENROUTER_API_KEY in .env or server environment' : (isRateLimited ? 'Free tier quota currently reached on OpenRouter' : 'Select this model for current chat')}">
                        ${isActive ? '✓ Selected' : (isRateLimited ? 'Select (Quota Reached)' : (state.hasOpenRouterApiKey ? 'Select' : 'Select (Key Needed)'))}
                    </button>
                </div>
            </div>
        `;
    });

    list.innerHTML = html;
}

// -------------------------------------------------------------------------
// Section 7 & 8: Settings & Per-Chat Model Picker
// -------------------------------------------------------------------------

async function loadModelSettings() {
    try {
        const res = await fetch('/api/models/settings');
        if (res.ok) {
            const data = await res.json();
            if (data.success && data.settings) {
                state.modelSettings = data.settings;
                populateSettingsInputs(data.settings);
            }
        }
    } catch (e) {
        console.warn('[ModelCenter] Could not load model settings:', e);
    }
}

function populateSettingsInputs(settings) {
    const provSelect = document.getElementById('mcSettingsDefaultProvider');
    const localSelect = document.getElementById('mcSettingsDefaultLocalModel');
    const cloudSelect = document.getElementById('mcSettingsDefaultCloudModel');

    if (provSelect && settings.defaultProvider) {
        provSelect.value = settings.defaultProvider;
    }
    if (cloudSelect && settings.defaultCloudModel) {
        cloudSelect.value = settings.defaultCloudModel;
    }
    updateModelSelectDropdowns();
}

function updateModelSelectDropdowns() {
    const localSelect = document.getElementById('mcSettingsDefaultLocalModel');
    const currentChatSelect = document.getElementById('mcCurrentChatModelSelect');
    const profModelSelect = document.getElementById('profInputModel');

    const options = (state.installedModels && state.installedModels.length > 0)
        ? state.installedModels.map(m => `<option value="${escapeHtml(m.id)}">${escapeHtml(m.id)} (${m.parameterSize || 'Local'})</option>`).join('')
        : '<option value="qwen2.5:7b">qwen2.5:7b</option><option value="qwen2.5:3b">qwen2.5:3b</option>';

    if (localSelect) {
        localSelect.innerHTML = options;
        if (state.modelSettings?.defaultLocalModel) {
            localSelect.value = state.modelSettings.defaultLocalModel;
        }
    }

    if (currentChatSelect) {
        currentChatSelect.innerHTML = options;
        const currentChat = getActiveChat();
        if (currentChat && currentChat.model) {
            currentChatSelect.value = currentChat.model;
        } else if (state.selectedModel) {
            currentChatSelect.value = state.selectedModel;
        }
    }

    if (profModelSelect) {
        profModelSelect.innerHTML = options;
    }
}

function updateCurrentChatModelPicker() {
    const select = document.getElementById('mcCurrentChatModelSelect');
    if (select) {
        const currentChat = getActiveChat();
        const activeModel = currentChat?.model || state.selectedModel || 'qwen2.5:7b';
        select.value = activeModel;
    }
}

async function saveGlobalModelSettings() {
    const defProvider = document.getElementById('mcSettingsDefaultProvider')?.value || 'LOCAL';
    const defLocal = document.getElementById('mcSettingsDefaultLocalModel')?.value || 'qwen2.5:7b';
    const defCloud = document.getElementById('mcSettingsDefaultCloudModel')?.value || 'openrouter/free';

    try {
        const res = await fetch('/api/models/settings', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json; charset=UTF-8' },
            body: JSON.stringify({
                defaultProvider: defProvider,
                defaultLocalModel: defLocal,
                defaultCloudModel: defCloud
            })
        });

        const data = await res.json();
        if (res.ok && data.success) {
            state.modelSettings = data.settings;
            showToast('Global AI model settings saved successfully');
        } else {
            alert(data.error || 'Failed to save settings.');
        }
    } catch (e) {
        alert('Network error: ' + e.message);
    }
}

function applyModelToCurrentChat() {
    const select = document.getElementById('mcCurrentChatModelSelect');
    if (!select || !select.value) return;

    const chosenModel = select.value;
    selectQuickModel('LOCAL', chosenModel);
    showToast(`Model '${chosenModel}' applied to this conversation.`);
}

// -------------------------------------------------------------------------
// Section 9: Model Profiles
// -------------------------------------------------------------------------

async function loadModelProfiles() {
    try {
        const res = await fetch('/api/models/profiles');
        if (res.ok) {
            const data = await res.json();
            if (data.success && Array.isArray(data.profiles)) {
                state.modelProfiles = data.profiles;
                renderModelProfiles(data.profiles);
            }
        }
    } catch (e) {
        console.warn('[ModelCenter] Could not load profiles:', e);
    }
}

function renderModelProfiles(profiles) {
    const list = document.getElementById('mcProfilesList');
    if (!list) return;

    let html = '';
    profiles.forEach(p => {
        const isCustom = p.isCustom;
        html += `
            <div class="mc-profile-card">
                <div>
                    <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:4px;">
                        <strong style="color:var(--text-primary); font-size:13.5px;">${escapeHtml(p.name)}</strong>
                        <span class="${isCustom ? 'mc-badge-purple' : 'mc-badge-cyan'}" style="font-size:10px;">${isCustom ? 'CUSTOM' : 'SYSTEM'}</span>
                    </div>
                    <div style="font-size:12px; color:var(--text-secondary); margin-bottom:10px; line-height:1.4;">
                        ${escapeHtml(p.description || '')}
                    </div>
                    <div style="font-size:11.5px; color:var(--text-muted); display:flex; flex-direction:column; gap:3px;">
                        <div>Model: <code style="color:var(--accent-cyan);">${escapeHtml(p.modelId || 'qwen2.5:7b')}</code></div>
                        <div>Persona: <strong style="color:var(--text-primary);">${escapeHtml(p.persona || 'general')}</strong></div>
                    </div>
                </div>

                <div style="display:flex; justify-content:space-between; align-items:center; margin-top:12px; padding-top:10px; border-top:1px solid var(--border-subtle);">
                    <button class="btn-primary" onclick="applyProfileToChat('${escapeHtml(p.id)}')" style="font-size:11px; padding:3px 10px;">
                        Apply to Chat
                    </button>
                    ${isCustom ? `
                        <button class="btn-secondary" onclick="deleteCustomProfile('${escapeHtml(p.id)}')" style="font-size:11px; padding:3px 8px; color:var(--danger); border-color:rgba(239, 68, 68, 0.25);" title="Delete Profile">
                            🗑️
                        </button>
                    ` : ''}
                </div>
            </div>
        `;
    });

    list.innerHTML = html;
}

function applyProfileToChat(profileId) {
    const profile = (state.modelProfiles || []).find(p => p.id === profileId);
    if (!profile) return;

    if (profile.persona) {
        setPersona(profile.persona, true);
    }
    if (profile.modelId) {
        state.selectedModel = profile.modelId;
    }
    if (profile.provider) {
        state.selectedProvider = profile.provider;
    }

    const currentChat = getActiveChat();
    if (currentChat) {
        currentChat.profileId = profile.id;
        currentChat.model = profile.modelId;
        currentChat.provider = profile.provider;
        saveChats();
    }

    updateHeaderModelBadge();
    showToast(`Applied profile: ${profile.name}`);
}

function openCreateProfileModal() {
    const modal = document.getElementById('createProfileModal');
    if (!modal) return;
    updateProfileModelOptions();
    modal.classList.add('active');
}

function closeCreateProfileModal() {
    const modal = document.getElementById('createProfileModal');
    if (modal) modal.classList.remove('active');
}

function updateProfileModelOptions() {
    const prov = document.getElementById('profInputProvider')?.value || 'LOCAL';
    const select = document.getElementById('profInputModel');
    if (!select) return;

    if (prov === 'PUBLIC') {
        const catalog = state.cloudCatalog || [];
        if (catalog.length > 0) {
            select.innerHTML = catalog.slice(0, 50).map(m => `<option value="${escapeHtml(m.id)}">${escapeHtml(m.name || m.id)}</option>`).join('');
        } else {
            select.innerHTML = '<option value="meta-llama/llama-3.2-3b-instruct:free">meta-llama/llama-3.2-3b-instruct:free</option>';
        }
    } else {
        updateModelSelectDropdowns();
    }
}

async function submitCustomProfile() {
    const name = document.getElementById('profInputName')?.value?.trim();
    const desc = document.getElementById('profInputDesc')?.value?.trim();
    const provider = document.getElementById('profInputProvider')?.value || 'LOCAL';
    const modelId = document.getElementById('profInputModel')?.value;
    const persona = document.getElementById('profInputPersona')?.value || 'general';

    if (!name) {
        alert('Please enter a profile name.');
        return;
    }

    try {
        const res = await fetch(getApiUrl('/api/models/profiles'), {
            method: 'POST',
            headers: { 'Content-Type': 'application/json; charset=UTF-8' },
            body: JSON.stringify({
                name,
                description: desc,
                provider,
                modelId,
                persona,
                isCustom: true
            })
        });

        const data = await res.json();
        if (res.ok && data.success) {
            closeCreateProfileModal();
            showToast(`Created profile '${name}'`);
            await loadModelProfiles();
        } else {
            alert(data.error || 'Failed to save profile.');
        }
    } catch (e) {
        alert('Network error: ' + e.message);
    }
}

async function deleteCustomProfile(id) {
    if (!confirm('Are you sure you want to delete this custom profile?')) return;
    try {
        const res = await fetch(getApiUrl(`/api/models/profiles?id=${encodeURIComponent(id)}`), {
            method: 'DELETE'
        });
        const data = await res.json();
        if (res.ok && data.success) {
            showToast('Profile deleted.');
            await loadModelProfiles();
        } else {
            alert(data.error || 'Failed to delete profile.');
        }
    } catch (e) {
        alert('Network error: ' + e.message);
    }
}

// -------------------------------------------------------------------------
// Section 10: Favorite Models
// -------------------------------------------------------------------------

async function loadFavorites() {
    try {
        const res = await fetch(getApiUrl('/api/models/favorites'));
        if (res.ok) {
            const data = await res.json();
            if (data.success && Array.isArray(data.favorites)) {
                state.favorites = data.favorites;
                const countBadge = document.getElementById('mcFavoritesCount');
                if (countBadge) countBadge.textContent = data.favorites.length;
                renderFavorites(data.favorites);
            }
        }
    } catch (e) {
        console.warn('[ModelCenter] Could not load favorites:', e);
    }
}

function renderFavorites(favs) {
    const list = document.getElementById('mcFavoritesList');
    if (!list) return;

    if (!favs || favs.length === 0) {
        list.innerHTML = `
            <div style="padding:24px; text-align:center; color:var(--text-muted); grid-column:1/-1;">
                No favorite models starred yet. Click the star ★ on any model card to save it here!
            </div>
        `;
        return;
    }

    const installedIds = new Set((state.installedModels || []).map(m => m.id));

    let html = '';
    favs.forEach(modelId => {
        const isCloud = modelId.includes('/') || modelId.includes(':free');
        const isInstalled = isCloud || installedIds.has(modelId);

        html += `
            <div class="mc-model-card">
                <div>
                    <div style="display:flex; justify-content:space-between; align-items:center;">
                        <strong style="color:var(--text-primary); font-size:13px; word-break:break-all;">${escapeHtml(modelId)}</strong>
                        <button class="mc-fav-btn active" onclick="toggleModelFavorite('${escapeHtml(modelId)}')" title="Remove from favorites">
                            ★
                        </button>
                    </div>
                    <div style="margin-top:6px; display:flex; gap:6px;">
                        <span class="${isCloud ? 'mc-badge-cyan' : 'mc-badge-purple'}" style="font-size:10px;">${isCloud ? 'CLOUD' : 'LOCAL'}</span>
                        ${!isInstalled ? '<span class="mc-badge-rose" style="font-size:10px;">UNAVAILABLE</span>' : '<span class="mc-badge-green" style="font-size:10px;">READY</span>'}
                    </div>
                    ${!isInstalled ? `
                        <div style="font-size:11px; color:var(--danger); margin-top:6px;">
                            This local model is not installed. Install it below to use it.
                        </div>
                    ` : ''}
                </div>
                <div class="mc-model-actions">
                    <button class="btn-secondary" onclick="inspectModelDetails('${escapeHtml(modelId)}', '${isCloud ? 'PUBLIC' : 'LOCAL'}')" style="font-size:11px; padding:3px 8px;">
                        ℹ️ Details
                    </button>
                    ${isInstalled ? `
                        <button class="btn-primary" onclick="selectQuickModel('${isCloud ? 'PUBLIC' : 'LOCAL'}', '${escapeHtml(modelId)}')" style="font-size:11px; padding:3px 10px;">
                            Select
                        </button>
                    ` : `
                        <button class="btn-secondary" onclick="setPullInput('${escapeHtml(modelId)}'); switchModelCenterTab('installed');" style="font-size:11px; padding:3px 10px; color:var(--accent-cyan); border-color:var(--accent-cyan);">
                            Install
                        </button>
                    `}
                </div>
            </div>
        `;
    });

    list.innerHTML = html;
}

async function toggleModelFavorite(modelId) {
    if (!modelId) return;
    try {
        const res = await fetch('/api/models/favorites/toggle', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json; charset=UTF-8' },
            body: JSON.stringify({ modelId })
        });

        const data = await res.json();
        if (res.ok && data.success) {
            state.favorites = data.favorites;
            const countBadge = document.getElementById('mcFavoritesCount');
            if (countBadge) countBadge.textContent = data.favorites.length;
            renderFavorites(data.favorites);
            renderInstalledModels(state.installedModels);
            showToast(data.isFavorite ? `Added '${modelId}' to favorites` : `Removed '${modelId}' from favorites`);
        }
    } catch (e) {
        alert('Network error toggling favorite: ' + e.message);
    }
}

// -------------------------------------------------------------------------
// Section 11: Performance History Dashboard
// -------------------------------------------------------------------------

async function loadPerformanceHistory() {
    try {
        const res = await fetch('/api/models/performance');
        if (res.ok) {
            const data = await res.json();
            if (data.success && data.performance) {
                renderPerformanceHistory(data.performance);
            }
        }
    } catch (e) {
        console.warn('[ModelCenter] Could not load performance:', e);
    }
}

function renderPerformanceHistory(perf) {
    const elAvgDur = document.getElementById('mcStatAvgDuration');
    const elAvgTtft = document.getElementById('mcStatAvgTtft');
    const elAvgTps = document.getElementById('mcStatAvgTps');
    const elTotal = document.getElementById('mcStatTotalRequests');
    const tableBody = document.getElementById('mcPerfTableBody');

    if (elAvgDur) elAvgDur.textContent = `${perf.avgDurationMs || 0} ms`;
    if (elAvgTtft) elAvgTtft.textContent = `${perf.avgTtftMs || 0} ms`;
    if (elAvgTps) elAvgTps.textContent = `${perf.avgTokensPerSec || 0.0}`;
    if (elTotal) elTotal.textContent = `${perf.totalRequests || 0}`;

    if (tableBody) {
        const recent = perf.recentRecords || [];
        if (recent.length === 0) {
            tableBody.innerHTML = '<tr><td colspan="7" style="text-align:center; color:var(--text-muted); padding:20px;">No performance logs recorded yet. Send a prompt to measure latency!</td></tr>';
            return;
        }

        let rows = '';
        recent.forEach(r => {
            const timeStr = new Date(r.timestamp).toLocaleTimeString();
            const statusBadge = r.success
                ? '<span class="mc-badge-green" style="font-size:10px;">SUCCESS</span>'
                : '<span class="mc-badge-rose" style="font-size:10px;">FAILED</span>';

            rows += `
                <tr>
                    <td><strong>${escapeHtml(r.modelId || 'default')}</strong></td>
                    <td><span class="mc-badge-cyan" style="font-size:10px;">${escapeHtml(r.provider || 'LOCAL')}</span></td>
                    <td>${r.durationMs || 0} ms</td>
                    <td>${r.ttftMs > 0 ? r.ttftMs + ' ms' : '—'}</td>
                    <td>${r.tokensPerSec > 0 ? r.tokensPerSec + ' t/s' : '—'}</td>
                    <td>${statusBadge}</td>
                    <td style="color:var(--text-muted); font-size:11px;">${timeStr}</td>
                </tr>
            `;
        });

        tableBody.innerHTML = rows;
    }
}

function clearPerformanceHistoryConfirm() {
    const modal = document.getElementById('confirmClearPerfModal');
    if (modal) modal.classList.add('active');
}

function closeConfirmClearPerfModal() {
    const modal = document.getElementById('confirmClearPerfModal');
    if (modal) modal.classList.remove('active');
}

async function executeClearPerformance() {
    closeConfirmClearPerfModal();
    try {
        const res = await fetch('/api/models/performance/clear', { method: 'POST' });
        const data = await res.json();
        if (res.ok && data.success) {
            showToast('Performance history cleared.');
            await loadPerformanceHistory();
        }
    } catch (e) {
        alert('Network error clearing performance: ' + e.message);
    }
}

// -------------------------------------------------------------------------
// Section 12: Model Details Inspector
// -------------------------------------------------------------------------

function inspectModelDetails(modelId, provider) {
    switchModelCenterTab('performance');
    const badge = document.getElementById('mcDetailsModelId');
    const grid = document.getElementById('mcModelDetailsGrid');

    if (badge) badge.textContent = modelId;

    let modelObj = null;
    if (provider === 'LOCAL') {
        modelObj = (state.installedModels || []).find(m => m.id === modelId);
    } else {
        modelObj = (state.cloudCatalog || []).find(m => m.id === modelId);
    }

    if (!modelObj) {
        modelObj = {
            id: modelId,
            name: modelId,
            provider: provider || 'LOCAL',
            parameterSize: modelId.includes('3b') ? '3B' : (modelId.includes('7b') ? '7B' : 'Standard'),
            quantization: 'Q4_K_M',
            family: 'qwen2',
            contextLength: 32768,
            suitabilityLabel: 'RECOMMENDED',
            suitabilityReason: 'Optimal configuration for AMD Ryzen 16GB DDR5 hardware'
        };
    }

    if (grid) {
        grid.innerHTML = `
            <div class="mc-detail-item">
                <div class="mc-detail-key">Model Identifier</div>
                <div class="mc-detail-val">${escapeHtml(modelObj.id)}</div>
            </div>
            <div class="mc-detail-item">
                <div class="mc-detail-key">Provider Engine</div>
                <div class="mc-detail-val">${escapeHtml(modelObj.provider || 'LOCAL')}</div>
            </div>
            <div class="mc-detail-item">
                <div class="mc-detail-key">Parameter Size</div>
                <div class="mc-detail-val">${escapeHtml(modelObj.parameterSize || 'Standard')}</div>
            </div>
            <div class="mc-detail-item">
                <div class="mc-detail-key">Quantization Level</div>
                <div class="mc-detail-val">${escapeHtml(modelObj.quantization || 'Q4_K_M')}</div>
            </div>
            <div class="mc-detail-item">
                <div class="mc-detail-key">Context Window</div>
                <div class="mc-detail-val">${modelObj.contextLength ? formatNumber(modelObj.contextLength) + ' tokens' : '32k tokens'}</div>
            </div>
            <div class="mc-detail-item">
                <div class="mc-detail-key">Hardware Suitability</div>
                <div class="mc-detail-val" style="color:#10b981;">${escapeHtml(modelObj.suitabilityLabel || 'RECOMMENDED')}</div>
            </div>
            <div class="mc-detail-item" style="grid-column: 1 / -1;">
                <div class="mc-detail-key">Suitability Explanation</div>
                <div class="mc-detail-val" style="font-weight:normal; font-size:12px; line-height:1.4;">
                    ${escapeHtml(modelObj.suitabilityReason || 'Analyzed against host hardware: AMD Ryzen 5 7535HS with 16GB DDR5 memory.')}
                </div>
            </div>
        `;
    }
}

// -------------------------------------------------------------------------
// Section 13: Quick Model Dropdown (Header Pill)
// -------------------------------------------------------------------------

function toggleQuickModelDropdown(event) {
    if (event) event.stopPropagation();
    const dropdown = document.getElementById('quickModelDropdown');
    if (!dropdown) return;

    if (dropdown.style.display === 'none' || !dropdown.style.display) {
        renderQuickModelDropdown();
        dropdown.style.display = 'flex';
        // Close on outer click
        const closeListener = (e) => {
            if (!dropdown.contains(e.target)) {
                dropdown.style.display = 'none';
                document.removeEventListener('click', closeListener);
            }
        };
        setTimeout(() => document.addEventListener('click', closeListener), 10);
    } else {
        dropdown.style.display = 'none';
    }
}

function renderQuickModelDropdown() {
    const dropdown = document.getElementById('quickModelDropdown');
    if (!dropdown) return;

    const currentChat = getActiveChat();
    const activeModelId = state.selectedModel || currentChat?.model || 'qwen2.5:7b';

    let html = `
        <div style="font-size:11px; font-weight:700; color:var(--text-muted); padding:4px 8px; text-transform:uppercase; letter-spacing:0.5px;">
            Installed Local Models
        </div>
    `;

    const localModels = (state.installedModels && state.installedModels.length > 0)
        ? state.installedModels
        : [{ id: 'qwen2.5:7b', name: 'qwen2.5:7b', parameterSize: '7B' }, { id: 'qwen2.5:3b', name: 'qwen2.5:3b', parameterSize: '3B' }];

    localModels.forEach(m => {
        const isActive = m.id === activeModelId;
        html += `
            <button class="quick-model-item ${isActive ? 'active' : ''}" onclick="selectQuickModel('LOCAL', '${escapeHtml(m.id)}')">
                <div>
                    <span>⚡ ${escapeHtml(m.id)}</span>
                    ${m.parameterSize ? `<span style="font-size:10px; opacity:0.7; margin-left:4px;">(${escapeHtml(m.parameterSize)})</span>` : ''}
                </div>
                ${isActive ? '<span>✓</span>' : ''}
            </button>
        `;
    });

    html += `
        <div style="font-size:11px; font-weight:700; color:var(--text-muted); padding:6px 8px 4px 8px; text-transform:uppercase; letter-spacing:0.5px; border-top:1px solid var(--border-subtle); margin-top:4px; display:flex; justify-content:space-between; align-items:center;">
            <span>Cloud Free Models</span>
            ${!state.hasOpenRouterApiKey ? '<span style="font-size:9.5px; color:#fbbf24; background:rgba(245,158,11,0.15); border:1px solid rgba(245,158,11,0.3); padding:1px 6px; border-radius:4px; text-transform:none; font-weight:500;">Key Required</span>' : ''}
        </div>
        <button class="quick-model-item ${activeModelId === 'openrouter/free' ? 'active' : ''}" onclick="selectQuickModel('PUBLIC', 'openrouter/free')" title="OpenRouter Dynamic Free Router (Auto-selects active free model)">
            <div style="display:flex; align-items:center; gap:4px;">
                <span>☁️ OpenRouter Free (Router)</span>
                <span style="font-size:9.5px; background:rgba(16,185,129,0.18); color:#10b981; border:1px solid rgba(16,185,129,0.3); padding:1px 5px; border-radius:3px; font-weight:600;">★ Recommended</span>
            </div>
            ${!state.hasOpenRouterApiKey ? '<span style="font-size:10px; color:#fbbf24;">(No Key)</span>' : (activeModelId === 'openrouter/free' ? '<span>✓</span>' : '')}
        </button>
    `;

    html += `
        <div style="border-top:1px solid var(--border-subtle); margin-top:4px; padding-top:4px;">
            <button class="quick-model-item" onclick="openModelCenterModal()" style="color:var(--accent-purple); font-weight:600;">
                <span>🤖 Manage All Models...</span>
            </button>
        </div>
    `;

    dropdown.innerHTML = html;
}

function selectQuickModel(provider, modelId) {
    state.selectedProvider = provider;
    state.selectedModel = modelId;

    const currentChat = getActiveChat();
    if (currentChat) {
        currentChat.provider = provider;
        currentChat.model = modelId;
        saveChats();
    }

    updateHeaderModelBadge();
    const dropdown = document.getElementById('quickModelDropdown');
    if (dropdown) dropdown.style.display = 'none';

    // Re-render installed models in Model Center so the card immediately highlights with active border and "✓ Selected"
    if (state.installedModels && state.installedModels.length > 0) {
        renderInstalledModels(state.installedModels);
    }
    if (state.cloudCatalog && state.cloudCatalog.length > 0) {
        renderOpenRouterCatalog(state.cloudCatalog);
    }
    updateModelSelectDropdowns();

    if (provider === 'PUBLIC' && !state.hasOpenRouterApiKey) {
        showToast(`⚠️ Cloud model '${modelId}' selected. OpenRouter API key is not configured (add OPENROUTER_API_KEY to .env).`, 5000);
    } else {
        showToast(`Switched active chat to ${modelId}`);
    }
}

function formatBytes(bytes) {
    if (!bytes || bytes <= 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}

function formatNumber(num) {
    return new Intl.NumberFormat().format(num);
}

