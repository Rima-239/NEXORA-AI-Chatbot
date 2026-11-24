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
    MODEL: 'nexora_model_v1'
};

const state = {
    chats: [],
    activeChatId: null,
    theme: 'dark', // 'dark', 'light', 'system'
    persona: 'general', // 'general', 'coding', 'writing', 'learning'
    selectedModel: '',
    availableModels: [],
    providerInfo: {
        mode: 'LOCAL',
        provider: 'Ollama (Local)',
        status: 'online'
    },
    isGenerating: false,
    lastUserPrompt: null,
    deferredInstallPrompt: null
};

// ==========================================================================
// 2. INITIALIZATION
// ==========================================================================

document.addEventListener('DOMContentLoaded', () => {
    initTheme();
    loadSavedData();
    initBackendConnection();
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
}

async function initBackendConnection() {
    try {
        // Query /api/config
        const configRes = await fetch('/api/config');
        if (configRes.ok) {
            const configData = await configRes.json();
            if (configData.success) {
                state.providerInfo.mode = configData.mode || 'LOCAL';
                state.providerInfo.provider = configData.provider || 'Ollama (Local)';
                if (!state.selectedModel && configData.default_model) {
                    state.selectedModel = configData.default_model;
                }
            }
        }

        // Query /api/models
        const modelsRes = await fetch('/api/models');
        if (modelsRes.ok) {
            const modelsData = await modelsRes.json();
            if (modelsData.success && Array.isArray(modelsData.models)) {
                state.availableModels = modelsData.models;
                if (!state.selectedModel || !state.availableModels.includes(state.selectedModel)) {
                    state.selectedModel = modelsData.default_model || state.availableModels[0] || 'qwen2.5:7b';
                }
            }
        }

        // Query /api/health
        const healthRes = await fetch('/api/health');
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

    const newChat = {
        id: 'chat_' + Date.now() + '_' + Math.random().toString(36).substring(2, 6),
        title: 'New Conversation',
        createdAt: Date.now(),
        updatedAt: Date.now(),
        persona: state.persona,
        messages: []
    };

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
    if (current && current.persona) {
        setPersona(current.persona, false);
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
        alert('There are no messages to export in this conversation.');
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
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
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
        // Attempt progressive token streaming (SSE)
        const response = await fetch('/api/chat', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json; charset=UTF-8',
                'Accept': 'text/event-stream'
            },
            body: JSON.stringify({
                message: rawText,
                messages: recentMessages,
                persona: state.persona,
                model: state.selectedModel,
                stream: true
            })
        });

        hideTypingIndicator();

        const contentType = response.headers.get('content-type') || '';

        // If server returns SSE stream
        if (response.ok && contentType.includes('text/event-stream')) {
            aiBubbleElement = createAssistantBubbleContainer();
            const reader = response.body.getReader();
            const decoder = new TextDecoder('utf-8');
            let buffer = '';

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
                        break;
                    }

                    if (trimmed.startsWith('data: ')) {
                        try {
                            const dataObj = JSON.parse(trimmed.substring(6));
                            if (dataObj.error) {
                                throw new Error(dataObj.error);
                            }
                            if (dataObj.chunk) {
                                accumulatedText += dataObj.chunk;
                                updateAssistantBubbleContent(aiBubbleElement, accumulatedText);
                                scrollToBottom();
                            }
                        } catch (e) {
                            // ignore partial JSON parse error
                        }
                    }
                }
            }

            if (!accumulatedText.trim()) {
                throw new Error('AI returned an empty response.');
            }

            // Finalize assistant message in state
            finalizeAssistantMessage(current, accumulatedText, aiBubbleElement);

        } else {
            // Buffered JSON response fallback
            const data = await response.json();
            if (!response.ok || !data.success) {
                throw new Error(data.error || `HTTP ${response.status}: Failed to generate response.`);
            }

            const reply = data.reply || (data.message && data.message.content) || '';
            if (!reply.trim()) {
                throw new Error('AI returned an empty response.');
            }

            finalizeAssistantMessage(current, reply, null);
        }

        state.lastUserPrompt = null;

    } catch (err) {
        hideTypingIndicator();
        if (aiBubbleElement && accumulatedText.trim()) {
            finalizeAssistantMessage(current, accumulatedText, aiBubbleElement);
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
            <div class="error-card-title">Request Issue</div>
            <div>${escapeHtml(errorMessage)}</div>
            ${canRetry ? `
                <button class="btn-error-retry" onclick="retryLastFailedMessage()">
                    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <polyline points="23 4 23 10 17 10"></polyline>
                        <path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"></path>
                    </svg>
                    Retry Prompt
                </button>
            ` : ''}
        </div>
    `;
    const container = document.getElementById('messagesContainer');
    if (container) {
        container.appendChild(card);
        scrollToBottom();
    }
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

    // Search input
    const searchInput = document.getElementById('searchFilterInput');
    if (searchInput) {
        searchInput.addEventListener('input', (e) => {
            handleSearchInput(e.target.value);
        });
    }
}
