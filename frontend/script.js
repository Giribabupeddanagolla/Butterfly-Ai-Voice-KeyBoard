/**
 * Butterfly AI - AI Voice Keyboard Client Script
 */

// Global function reference for state machine
var setVoiceState = function (state, extraData = {}) {
    if (window.setVoiceStateImpl) {
        return window.setVoiceStateImpl(state, extraData);
    }
};

document.addEventListener('DOMContentLoaded', () => {
    // API Config
    const API_BASE = '';

    // State Management
    let currentSessionId = localStorage.getItem('ai_agent_session') || generateUUID();
    let voiceState = 'idle'; // 'idle' | 'recording' | 'processing' | 'completed' | 'error'
    let isRecording = false;
    let mediaRecorder = null;
    let audioChunks = [];
    let recordTimerInterval = null;
    let recordStartTime = 0;
    let audioContext = null;
    let analyser = null;
    let animFrameId = null;
    let currentAudioElement = null;
    let activeRecordingSessionToken = 0;
    let recordedMimeType = 'audio/webm;codecs=opus';
    let recordedFileExt = 'webm';

    // Realtime Speech State
    const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
    let speechRecognizer = null;
    let finalText = '';
    let partialText = '';
    let accumulatedTextPrefix = '';
    let isTranslationOn = false;

    // Undo / Redo Stack
    let historyStack = [];
    let historyIndex = -1;

    // DOM Elements
    const sidebar = document.getElementById('sidebar');
    const toggleSidebarBtn = document.getElementById('toggleSidebarBtn');
    const closeSidebarBtn = document.getElementById('closeSidebarBtn');
    const newChatBtn = document.getElementById('newChatBtn');
    const conversationList = document.getElementById('conversationList');
    const chatTitle = document.getElementById('chatTitle');
    const currentLangText = document.getElementById('currentLangText');

    const themeToggleBtn = document.getElementById('themeToggleBtn');
    const themeIcon = document.getElementById('themeIcon');
    const settingsBtn = document.getElementById('settingsBtn');
    const settingsModal = document.getElementById('settingsModal');
    const closeSettingsBtn = document.getElementById('closeSettingsBtn');
    const saveSettingsBtn = document.getElementById('saveSettingsBtn');
    const headerStopAudioBtn = document.getElementById('headerStopAudioBtn');

    // Search Controls & Inputs
    const mainSearchInput = document.getElementById('mainSearchInput');
    const mainSearchActionBtn = document.getElementById('mainSearchActionBtn');
    const globalSearchInput = document.getElementById('globalSearchInput');
    const globalSearchActionBtn = document.getElementById('globalSearchActionBtn');

    // Voice Keyboard Controls
    const mainMicBtn = document.getElementById('mainSearchMicBtn') || document.getElementById('mainMicBtn');
    const mainMicIcon = document.getElementById('mainSearchMicIcon') || document.getElementById('mainMicIcon');
    const micPulseRing = document.getElementById('micPulseRing');
    const mainMicStatus = document.getElementById('mainMicStatus') || document.getElementById('micStatusText');
    const mainRecordTimer = document.getElementById('mainRecordTimer');
    const mainWaveformCanvas = document.getElementById('mainWaveformCanvas');

    const sourceLangSelect = document.getElementById('sourceLangSelect');
    const translationToggleBtn = document.getElementById('translationToggleBtn');
    const toggleText = document.getElementById('toggleText');
    const translateToGroup = document.getElementById('translateToGroup');
    const targetLangSelect = document.getElementById('targetLangSelect');

    // Editors & Cards
    const originalCard = document.getElementById('originalCard');
    const origLangTag = document.getElementById('origLangTag');
    const originalTextEditor = document.getElementById('originalTextEditor');
    const copyOriginalBtn = document.getElementById('copyOriginalBtn');
    const speakOriginalBtn = document.getElementById('speakOriginalBtn');
    const insertOriginalBtn = document.getElementById('insertOriginalBtn');
    const undoBtn = document.getElementById('undoBtn');
    const redoBtn = document.getElementById('redoBtn');
    const clearBtn = document.getElementById('clearBtn');

    const translationCard = document.getElementById('translationCard');
    const targetLangTag = document.getElementById('targetLangTag');
    const translatedTextEditor = document.getElementById('translatedTextEditor');
    const copyTranslationBtn = document.getElementById('copyTranslationBtn');
    const speakTranslationBtn = document.getElementById('speakTranslationBtn');
    const insertTranslationBtn = document.getElementById('insertTranslationBtn');

    // Initialize Lucide Icons
    if (window.lucide) {
        lucide.createIcons();
    }

    // --- Virtual Keyboard Engine ---
    const VirtualKeyboard = {
        container: null,
        isShift: false,
        isCaps: false,
        isSymbol: false,

        init() {
            this.container = document.getElementById('virtualKeyboard');
            if (!this.container) return;
            this.render();
        },

        render() {
            if (!this.container) return;
            this.container.innerHTML = '';

            let rows = [];

            if (!this.isSymbol) {
                const row1 = ['Q', 'W', 'E', 'R', 'T', 'Y', 'U', 'I', 'O', 'P'];
                const row2 = ['A', 'S', 'D', 'F', 'G', 'H', 'J', 'K', 'L'];
                const row3 = ['shift', 'Z', 'X', 'C', 'V', 'B', 'N', 'M', 'backspace'];
                const row4 = ['123', 'space', 'search'];
                rows = [row1, row2, row3, row4];
            } else {
                const row1 = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '0'];
                const row2 = ['!', '@', '#', '$', '%', '^', '&', '*', '(', ')'];
                const row3 = ['abc', '-', '_', '+', '=', '[', ']', '{', '}', 'backspace'];
                const row4 = ['123', 'space', 'search'];
                rows = [row1, row2, row3, row4];
            }

            rows.forEach(rowKeys => {
                const rowEl = document.createElement('div');
                rowEl.className = 'vk-key-row';

                rowKeys.forEach(key => {
                    const btn = document.createElement('button');
                    btn.className = 'vk-key';

                    if (key === 'shift') {
                        btn.className += ' key-wide shift-key';
                        if (this.isShift || this.isCaps) btn.className += ' active-toggle';
                        btn.innerHTML = '<i data-lucide="arrow-up"></i> ' + (this.isCaps ? 'CAPS' : 'SHIFT');
                        btn.onclick = (e) => {
                            e.preventDefault();
                            if (this.isShift) {
                                this.isCaps = !this.isCaps;
                                this.isShift = false;
                            } else {
                                this.isShift = true;
                            }
                            const capsBadge = document.getElementById('capsBadge');
                            if (capsBadge) capsBadge.textContent = this.isCaps ? 'CAPS ON' : (this.isShift ? 'SHIFT ON' : 'CAPS OFF');
                            this.render();
                        };
                    } else if (key === 'backspace') {
                        btn.className += ' key-wide';
                        btn.innerHTML = '<i data-lucide="delete"></i>';
                        btn.onclick = (e) => {
                            e.preventDefault();
                            this.handleBackspace();
                        };
                    } else if (key === 'space') {
                        btn.className += ' key-space';
                        btn.textContent = 'SPACE';
                        btn.onclick = (e) => {
                            e.preventDefault();
                            TextInsertionService.insertText(' ');
                        };
                    } else if (key === '123' || key === 'abc') {
                        btn.className += ' key-wide';
                        btn.textContent = key.toUpperCase();
                        btn.onclick = (e) => {
                            e.preventDefault();
                            this.isSymbol = !this.isSymbol;
                            this.render();
                        };
                    } else if (key === 'search') {
                        btn.className += ' key-wide key-action';
                        btn.innerHTML = '<i data-lucide="search"></i> SEARCH';
                        btn.onclick = (e) => {
                            e.preventDefault();
                            performSearch();
                        };
                    } else {
                        let label = key;
                        if (!this.isSymbol) {
                            label = (this.isShift || this.isCaps) ? key.toUpperCase() : key.toLowerCase();
                        }
                        btn.textContent = label;
                        btn.onclick = (e) => {
                            e.preventDefault();
                            TextInsertionService.insertText(label);
                            if (this.isShift && !this.isCaps) {
                                this.isShift = false;
                                this.render();
                            }
                        };
                    }
                    rowEl.appendChild(btn);
                });
                this.container.appendChild(rowEl);
            });
            if (window.lucide) lucide.createIcons();
        },

        handleBackspace() {
            TextInsertionService.handleBackspace();
        }
    };

    // --- Keyboard Settings Management ---
    const DEFAULT_SETTINGS = {
        ttsEnabled: true,
        autoSpeak: false,
        ttsVoice: 'nova',
        ttsSpeed: 1.0,
        hotkeyEnabled: true
    };

    function getAppSettings() {
        try {
            const saved = localStorage.getItem('butterfly_keyboard_settings');
            if (saved) {
                return { ...DEFAULT_SETTINGS, ...JSON.parse(saved) };
            }
        } catch (e) {
            console.warn('Failed to parse saved keyboard settings:', e);
        }
        return { ...DEFAULT_SETTINGS };
    }

    function saveAppSettings(settings) {
        try {
            localStorage.setItem('butterfly_keyboard_settings', JSON.stringify(settings));
        } catch (e) {
            console.warn('Failed to save keyboard settings to localStorage:', e);
        }
    }

    function updateDependentSettingsState() {
        const ttsToggle = document.getElementById('ttsToggle');
        const autoSpeakToggle = document.getElementById('autoSpeakToggle');
        const ttsVoiceSelect = document.getElementById('ttsVoiceSelect');
        const ttsSpeedSelect = document.getElementById('ttsSpeedSelect');

        const isTTSEnabled = ttsToggle ? ttsToggle.checked : true;

        [autoSpeakToggle, ttsVoiceSelect, ttsSpeedSelect].forEach(el => {
            if (!el) return;
            const container = el.closest('.setting-item');
            if (container) {
                if (isTTSEnabled) {
                    container.classList.remove('disabled-setting');
                    el.disabled = false;
                } else {
                    container.classList.add('disabled-setting');
                    el.disabled = true;
                }
            }
        });
    }

    function syncSettingsUI() {
        const settings = getAppSettings();
        const ttsToggle = document.getElementById('ttsToggle');
        const autoSpeakToggle = document.getElementById('autoSpeakToggle');
        const ttsVoiceSelect = document.getElementById('ttsVoiceSelect');
        const ttsSpeedSelect = document.getElementById('ttsSpeedSelect');
        const hotkeyToggle = document.getElementById('hotkeyToggle');

        if (ttsToggle) ttsToggle.checked = !!settings.ttsEnabled;
        if (autoSpeakToggle) autoSpeakToggle.checked = !!settings.autoSpeak;
        if (ttsVoiceSelect) {
            const savedVoice = (settings.ttsVoice || 'nova').toLowerCase();
            const matchedVoice = Array.from(ttsVoiceSelect.options).find(opt => opt.value.toLowerCase() === savedVoice);
            ttsVoiceSelect.value = matchedVoice ? matchedVoice.value : (ttsVoiceSelect.options[0] ? ttsVoiceSelect.options[0].value : 'nova');
        }
        if (ttsSpeedSelect) {
            const rawSpeed = settings.ttsSpeed !== undefined ? settings.ttsSpeed : (settings.speechPlaybackSpeed !== undefined ? settings.speechPlaybackSpeed : 1.0);
            const speedNum = parseFloat(rawSpeed) || 1.0;
            const matchedSpeed = Array.from(ttsSpeedSelect.options).find(opt => Math.abs(parseFloat(opt.value) - speedNum) < 0.01);
            if (matchedSpeed) {
                ttsSpeedSelect.value = matchedSpeed.value;
            } else {
                const defaultOpt = Array.from(ttsSpeedSelect.options).find(opt => Math.abs(parseFloat(opt.value) - 1.0) < 0.01) || ttsSpeedSelect.options[0];
                if (defaultOpt) ttsSpeedSelect.value = defaultOpt.value;
            }
        }
        if (hotkeyToggle) hotkeyToggle.checked = !!settings.hotkeyEnabled;

        updateDependentSettingsState();
    }

    // --- Core Initialization ---
    initApp();

    function initApp() {
        setupEventListeners();
        syncSettingsUI();
        VirtualKeyboard.init();
        loadVoiceHistoryList();
        pushHistoryState();
        checkOpenAIStatus();
    }

    async function checkOpenAIStatus() {
        const badgeDot = document.getElementById('openaiStatusDot');
        const badgeText = document.getElementById('openaiStatusText');
        if (!badgeDot || !badgeText) return;

        try {
            const res = await fetch(`${API_BASE}/api/openai/status`);
            if (res.ok) {
                const data = await res.json();
                if (data.configured) {
                    badgeDot.style.background = '#10b981';
                    badgeText.textContent = 'OpenAI API Connected';
                } else {
                    badgeDot.style.background = '#f59e0b';
                    badgeText.textContent = 'OpenAI API Not Configured';
                }
            } else {
                badgeDot.style.background = '#ef4444';
                badgeText.textContent = 'OpenAI API Error';
            }
        } catch (e) {
            badgeDot.style.background = '#ef4444';
            badgeText.textContent = 'Backend Disconnected';
        }
    }

    // --- Event Listeners ---
    function setupEventListeners() {
        if (toggleSidebarBtn) {
            toggleSidebarBtn.addEventListener('click', () => {
                sidebar.classList.toggle('closed');
                sidebar.classList.toggle('open');
            });
        }
        if (closeSidebarBtn) {
            closeSidebarBtn.addEventListener('click', () => {
                sidebar.classList.add('closed');
                sidebar.classList.remove('open');
            });
        }

        if (newChatBtn) {
            newChatBtn.addEventListener('click', startNewSession);
        }

        // Theme Toggle
        if (themeToggleBtn) {
            themeToggleBtn.addEventListener('click', () => {
                document.body.classList.toggle('light-theme');
                document.body.classList.toggle('dark-theme');
                const isLight = document.body.classList.contains('light-theme');
                if (themeIcon) themeIcon.setAttribute('data-lucide', isLight ? 'moon' : 'sun');
                if (window.lucide) lucide.createIcons();
            });
        }

        const apiKeyInput = document.getElementById('apiKeyInput');
        const ttsToggleEl = document.getElementById('ttsToggle');
        const autoSpeakToggleEl = document.getElementById('autoSpeakToggle');

        if (ttsToggleEl) {
            ttsToggleEl.addEventListener('change', () => {
                updateDependentSettingsState();
            });
        }

        if (autoSpeakToggleEl) {
            autoSpeakToggleEl.addEventListener('change', () => {
                if (autoSpeakToggleEl.checked && ttsToggleEl && !ttsToggleEl.checked) {
                    ttsToggleEl.checked = true;
                    updateDependentSettingsState();
                }
            });
        }

        // Allow clicking anywhere on a setting card row to toggle its checkbox
        document.querySelectorAll('.setting-item').forEach(item => {
            item.addEventListener('click', (e) => {
                if (e.target.tagName === 'INPUT' || e.target.tagName === 'SELECT' || e.target.tagName === 'LABEL' || e.target.tagName === 'A') {
                    return;
                }
                const chk = item.querySelector('input[type="checkbox"]');
                if (chk && !chk.disabled) {
                    chk.checked = !chk.checked;
                    chk.dispatchEvent(new Event('change'));
                }
            });
        });

        // Settings Modal Handlers
        const confirmSettingsBtn = document.getElementById('confirmSettingsBtn');

        if (settingsBtn) {
            settingsBtn.addEventListener('click', () => {
                syncSettingsUI();
                const modalBody = settingsModal ? settingsModal.querySelector('.modal-body') : null;
                if (modalBody) modalBody.scrollTop = 0;
                if (saveSettingsBtn) saveSettingsBtn.style.display = 'inline-flex';
                if (confirmSettingsBtn) confirmSettingsBtn.style.display = 'none';
                if (settingsModal) settingsModal.classList.add('active');
                checkOpenAIStatus();
            });
        }

        if (closeSettingsBtn) {
            closeSettingsBtn.addEventListener('click', () => {
                if (settingsModal) settingsModal.classList.remove('active');
            });
        }

        if (settingsModal) {
            settingsModal.addEventListener('click', (e) => {
                if (e.target === settingsModal) {
                    settingsModal.classList.remove('active');
                }
            });
        }

        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape' && settingsModal && settingsModal.classList.contains('active')) {
                settingsModal.classList.remove('active');
            }
        });

        const testVoiceBtn = document.getElementById('testVoiceBtn');
        if (testVoiceBtn) {
            testVoiceBtn.addEventListener('click', (e) => {
                e.preventDefault();
                e.stopPropagation();
                speakText("This is a Butterfly AI voice test.", "en", testVoiceBtn);
            });
        }

        if (saveSettingsBtn) {
            saveSettingsBtn.addEventListener('click', () => {
                if (confirmSettingsBtn) {
                    saveSettingsBtn.style.display = 'none';
                    confirmSettingsBtn.style.display = 'inline-flex';
                    confirmSettingsBtn.innerHTML = '<i data-lucide="check-circle"></i> Confirm Settings';
                    if (window.lucide) lucide.createIcons();
                }
            });
        }

        if (confirmSettingsBtn) {
            confirmSettingsBtn.addEventListener('click', async () => {
                const ttsToggle = document.getElementById('ttsToggle');
                const autoSpeakToggle = document.getElementById('autoSpeakToggle');
                const ttsVoiceSelect = document.getElementById('ttsVoiceSelect');
                const ttsSpeedSelect = document.getElementById('ttsSpeedSelect');
                const hotkeyToggle = document.getElementById('hotkeyToggle');

                const newSettings = {
                    ttsEnabled: ttsToggle ? ttsToggle.checked : true,
                    autoSpeak: autoSpeakToggle ? autoSpeakToggle.checked : false,
                    autoSpeakAnswers: autoSpeakToggle ? autoSpeakToggle.checked : false,
                    ttsVoice: ttsVoiceSelect ? ttsVoiceSelect.value : 'nova',
                    ttsSpeed: ttsSpeedSelect ? parseFloat(ttsSpeedSelect.value) : 1.0,
                    speechPlaybackSpeed: ttsSpeedSelect ? parseFloat(ttsSpeedSelect.value) : 1.0,
                    hotkeyEnabled: hotkeyToggle ? hotkeyToggle.checked : true
                };

                saveAppSettings(newSettings);

                if (apiKeyInput && apiKeyInput.value.trim()) {
                    const keyVal = apiKeyInput.value.trim();
                    try {
                        const res = await fetch(`${API_BASE}/api/settings/key`, {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({ api_key: keyVal })
                        });
                        if (res.ok) {
                            checkOpenAIStatus();
                        }
                    } catch (e) {
                        console.warn('Failed to update API key:', e);
                    }
                }

                confirmSettingsBtn.innerHTML = '<i data-lucide="check"></i> ✓ Confirmed!';
                if (window.lucide) lucide.createIcons();

                setTimeout(() => {
                    if (saveSettingsBtn) saveSettingsBtn.style.display = 'inline-flex';
                    if (confirmSettingsBtn) confirmSettingsBtn.style.display = 'none';
                    if (settingsModal) settingsModal.classList.remove('active');
                }, 500);
            });
        }

        // Global Shortcut: Ctrl + Shift + Space
        document.addEventListener('keydown', (e) => {
            if (e.ctrlKey && e.shiftKey && e.code === 'Space') {
                const settings = getAppSettings();
                if (settings.hotkeyEnabled !== false) {
                    e.preventDefault();
                    if (voiceModal && !voiceModal.classList.contains('active')) {
                        openVoiceModal();
                    }
                    toggleRecording();
                }
            }
        });

        // Translation Toggle Switch
        if (translationToggleBtn) {
            translationToggleBtn.addEventListener('click', () => {
                isTranslationOn = !isTranslationOn;
                translationToggleBtn.classList.toggle('active', isTranslationOn);
                if (toggleText) toggleText.textContent = isTranslationOn ? 'ON' : 'OFF';

                if (isTranslationOn) {
                    if (translateToGroup) {
                        translateToGroup.style.opacity = '1';
                        translateToGroup.style.pointerEvents = 'auto';
                    }
                    if (targetLangSelect) targetLangSelect.disabled = false;
                    if (translationCard) translationCard.style.display = 'flex';
                    updateTargetLangTag();
                    triggerTranslation();
                } else {
                    if (translateToGroup) {
                        translateToGroup.style.opacity = '0.6';
                        translateToGroup.style.pointerEvents = 'none';
                    }
                    if (targetLangSelect) targetLangSelect.disabled = true;
                    if (translationCard) translationCard.style.display = 'none';
                }
            });
        }

        if (sourceLangSelect) {
            sourceLangSelect.addEventListener('change', () => {
                if (origLangTag) origLangTag.textContent = sourceLangSelect.value.toUpperCase();
            });
        }

        if (targetLangSelect) {
            targetLangSelect.addEventListener('change', () => {
                updateTargetLangTag();
                if (isTranslationOn) triggerTranslation();
            });
        }

        // --- Mode 1 & Mode 2 Interactivity ---
        const virtualKeyboardCard = document.getElementById('virtualKeyboardCard');
        const voiceModal = document.getElementById('voiceModal');
        const closeVoiceModalBtn = document.getElementById('closeVoiceModalBtn');
        const cancelVoiceBtn = document.getElementById('cancelVoiceBtn');
        const startVoiceRecordBtn = document.getElementById('startVoiceRecordBtn');
        const modalMicRecordBtn = document.getElementById('modalMicRecordBtn');
        const rerecordVoiceBtn = document.getElementById('rerecordVoiceBtn');
        const insertTextVoiceBtn = document.getElementById('insertTextVoiceBtn');

        function showVirtualKeyboard() { }
        function hideVirtualKeyboard() { }

        const LANG_DISPLAY_NAMES = {
            'auto': 'Auto Detect', 'en': 'English', 'te': 'Telugu', 'hi': 'Hindi',
            'ta': 'Tamil', 'kn': 'Kannada', 'ml': 'Malayalam', 'mr': 'Marathi',
            'bn': 'Bengali', 'gu': 'Gujarati', 'pa': 'Punjabi', 'ur': 'Urdu',
            'es': 'Spanish', 'fr': 'French', 'de': 'German', 'it': 'Italian',
            'pt': 'Portuguese', 'ar': 'Arabic', 'ja': 'Japanese', 'ko': 'Korean',
            'zh': 'Chinese', 'ru': 'Russian'
        };

        function getLangDisplayName(code) {
            if (!code) return 'Auto Detect';
            const clean = code.toLowerCase().trim();
            return LANG_DISPLAY_NAMES[clean] || code.toUpperCase();
        }

        function cleanMusicSymbols(str) {
            if (!str) return '';
            return str
                .replace(/[♪♫🎵🎶♭♮♯]/g, '')
                .replace(/\[(music|singing|background music)\]/gi, '')
                .replace(/\((music|singing|background music)\)/gi, '')
                .trim();
        }

        let latestSourceLang = 'en';
        let latestTargetLang = 'en';

        // --- AI Voice Keyboard UI State Machine ---
        function setVoiceState(state, extraData = {}) {
            voiceState = state; // 'idle' | 'recording' | 'transcribing' | 'translating' | 'completed' | 'error'
            window.setVoiceState = setVoiceState;
            window.setVoiceStateImpl = setVoiceState;

            const voiceStatusBadge = document.getElementById('voiceStatusBadge');
            const voiceLangIndicator = document.getElementById('voiceLangIndicator');
            const modalHeaderBadgeIcon = document.getElementById('modalHeaderBadgeIcon');
            const modalCardTitle = document.getElementById('modalCardTitle');
            const voiceLangSelectRow = document.getElementById('voiceLangSelectRow');
            const voiceToggleRow = document.getElementById('voiceToggleRow');
            const translateToGroup = document.getElementById('translateToGroup');
            const voiceCenterVisual = document.getElementById('voiceCenterVisual');
            const modalMicRecordBtn = document.getElementById('modalMicRecordBtn');
            const modalMicIcon = document.getElementById('modalMicIcon');
            const modalMicStatus = document.getElementById('modalMicStatus');
            const modalWaveformCanvas = document.getElementById('modalWaveformCanvas');
            const modalRecordTimer = document.getElementById('modalRecordTimer');
            const liveSpeechPreview = document.getElementById('liveSpeechPreview');
            const transcriptionResultContainer = document.getElementById('transcriptionResultContainer');

            const originalSpeechCard = document.getElementById('originalSpeechCard');
            const voiceOriginalTextarea = document.getElementById('voiceOriginalTextarea');
            const originalLangTag = document.getElementById('originalLangTag');

            const translationSpeechCard = document.getElementById('translationSpeechCard');
            const voiceTranslatedTextarea = document.getElementById('voiceTranslatedTextarea');
            const translationLangTag = document.getElementById('translationLangTag');

            const cancelVoiceBtn = document.getElementById('cancelVoiceBtn');
            const startVoiceRecordBtn = document.getElementById('startVoiceRecordBtn');
            const startBtnIcon = document.getElementById('startBtnIcon');
            const startBtnText = document.getElementById('startBtnText');
            const rerecordVoiceBtn = document.getElementById('rerecordVoiceBtn');

            const selectedLangCode = getSourceLang();
            const langDisplayName = selectedLangCode === 'auto' ? 'Auto Detect' : getLangDisplayName(selectedLangCode);

            if (state === 'idle') {
                isRecording = false;
                if (modalHeaderBadgeIcon) modalHeaderBadgeIcon.textContent = '🎤';
                if (modalCardTitle) modalCardTitle.textContent = 'AI Voice Keyboard';
                if (voiceLangIndicator) voiceLangIndicator.textContent = extraData.language || langDisplayName;
                if (voiceStatusBadge) {
                    voiceStatusBadge.textContent = 'Ready';
                    voiceStatusBadge.className = 'voice-status-badge';
                }
                if (voiceLangSelectRow) voiceLangSelectRow.style.display = 'flex';
                if (voiceToggleRow) voiceToggleRow.style.display = 'flex';
                if (translateToGroup) {
                    translateToGroup.style.display = 'flex';
                    translateToGroup.style.opacity = isTranslationOn ? '1' : '0.5';
                    translateToGroup.style.pointerEvents = isTranslationOn ? 'auto' : 'none';
                    if (targetLangSelect) targetLangSelect.disabled = !isTranslationOn;
                }
                if (voiceCenterVisual) voiceCenterVisual.style.display = 'flex';

                if (modalMicRecordBtn) {
                    modalMicRecordBtn.className = 'voice-record-circle';
                    modalMicRecordBtn.disabled = false;
                }
                if (modalMicIcon) modalMicIcon.setAttribute('data-lucide', 'mic');
                if (modalMicStatus) modalMicStatus.textContent = 'Ready to record';
                if (modalWaveformCanvas) modalWaveformCanvas.style.display = 'none';
                if (modalRecordTimer) {
                    modalRecordTimer.style.display = 'none';
                    modalRecordTimer.textContent = '00:00';
                }
                if (liveSpeechPreview) {
                    liveSpeechPreview.style.display = 'none';
                    liveSpeechPreview.textContent = '';
                }
                if (transcriptionResultContainer) transcriptionResultContainer.style.display = 'none';

                if (cancelVoiceBtn) {
                    cancelVoiceBtn.style.display = 'inline-flex';
                    cancelVoiceBtn.textContent = 'Cancel';
                }
                if (rerecordVoiceBtn) rerecordVoiceBtn.style.display = 'none';

                if (startVoiceRecordBtn) {
                    startVoiceRecordBtn.style.display = 'inline-flex';
                    startVoiceRecordBtn.disabled = false;
                    startVoiceRecordBtn.className = 'btn-primary btn-record-start';
                    if (startBtnIcon) startBtnIcon.setAttribute('data-lucide', 'mic');
                    if (startBtnText) startBtnText.textContent = 'Start Recording';
                }
            }
            else if (state === 'recording') {
                isRecording = true;
                if (modalHeaderBadgeIcon) modalHeaderBadgeIcon.textContent = '🔴';
                if (modalCardTitle) modalCardTitle.textContent = 'AI Voice Keyboard';
                if (voiceLangIndicator) voiceLangIndicator.textContent = extraData.language || langDisplayName;
                if (voiceStatusBadge) {
                    voiceStatusBadge.textContent = 'Recording...';
                    voiceStatusBadge.className = 'voice-status-badge recording';
                }
                if (voiceLangSelectRow) voiceLangSelectRow.style.display = 'none';
                if (voiceToggleRow) voiceToggleRow.style.display = 'none';
                if (translateToGroup) translateToGroup.style.display = 'none';
                if (voiceCenterVisual) voiceCenterVisual.style.display = 'flex';

                if (modalMicRecordBtn) {
                    modalMicRecordBtn.className = 'voice-record-circle recording';
                    modalMicRecordBtn.disabled = false;
                    modalMicRecordBtn.setAttribute('title', 'Click to Pause Recording');
                }
                if (modalMicIcon) modalMicIcon.setAttribute('data-lucide', 'pause');
                if (modalMicStatus) modalMicStatus.textContent = 'Listening... Speak naturally';
                if (modalWaveformCanvas) modalWaveformCanvas.style.display = 'block';
                if (modalRecordTimer) modalRecordTimer.style.display = 'block';
                if (liveSpeechPreview) liveSpeechPreview.style.display = 'block';
                if (transcriptionResultContainer) transcriptionResultContainer.style.display = 'none';

                if (cancelVoiceBtn) {
                    cancelVoiceBtn.style.display = 'inline-flex';
                    cancelVoiceBtn.textContent = 'Cancel';
                }
                if (rerecordVoiceBtn) rerecordVoiceBtn.style.display = 'none';

                if (startVoiceRecordBtn) {
                    startVoiceRecordBtn.style.display = 'inline-flex';
                    startVoiceRecordBtn.disabled = false;
                    startVoiceRecordBtn.className = 'btn-primary btn-record-start recording';
                    if (startBtnIcon) startBtnIcon.setAttribute('data-lucide', 'square');
                    if (startBtnText) startBtnText.textContent = 'Stop Recording';
                }
            }
            else if (state === 'paused') {
                isRecording = true;
                if (modalHeaderBadgeIcon) modalHeaderBadgeIcon.textContent = '⏸️';
                if (modalCardTitle) modalCardTitle.textContent = 'AI Voice Keyboard';
                if (voiceLangIndicator) voiceLangIndicator.textContent = extraData.language || langDisplayName;
                if (voiceStatusBadge) {
                    voiceStatusBadge.textContent = 'Paused...';
                    voiceStatusBadge.className = 'voice-status-badge processing';
                }
                if (voiceLangSelectRow) voiceLangSelectRow.style.display = 'none';
                if (voiceToggleRow) voiceToggleRow.style.display = 'none';
                if (translateToGroup) translateToGroup.style.display = 'none';
                if (voiceCenterVisual) voiceCenterVisual.style.display = 'flex';

                if (modalMicRecordBtn) {
                    modalMicRecordBtn.className = 'voice-record-circle paused';
                    modalMicRecordBtn.disabled = false;
                    modalMicRecordBtn.setAttribute('title', 'Click to Resume Recording');
                }
                if (modalMicIcon) modalMicIcon.setAttribute('data-lucide', 'play');
                if (modalMicStatus) modalMicStatus.textContent = 'Paused (Click button to Resume)';
                if (modalWaveformCanvas) modalWaveformCanvas.style.display = 'none';
                if (modalRecordTimer) modalRecordTimer.style.display = 'block';
                if (liveSpeechPreview) liveSpeechPreview.style.display = 'block';
                if (transcriptionResultContainer) transcriptionResultContainer.style.display = 'none';

                if (cancelVoiceBtn) {
                    cancelVoiceBtn.style.display = 'inline-flex';
                    cancelVoiceBtn.textContent = 'Cancel';
                }
                if (rerecordVoiceBtn) rerecordVoiceBtn.style.display = 'none';

                if (startVoiceRecordBtn) {
                    startVoiceRecordBtn.style.display = 'inline-flex';
                    startVoiceRecordBtn.disabled = false;
                    startVoiceRecordBtn.className = 'btn-primary btn-record-start recording';
                    if (startBtnIcon) startBtnIcon.setAttribute('data-lucide', 'square');
                    if (startBtnText) startBtnText.textContent = 'Stop Recording';
                }
            }
            else if (state === 'transcribing' || state === 'processing') {
                isRecording = false;
                if (modalHeaderBadgeIcon) modalHeaderBadgeIcon.textContent = '⏳';
                if (modalCardTitle) modalCardTitle.textContent = 'AI Voice Keyboard';
                if (voiceLangIndicator) voiceLangIndicator.textContent = extraData.language || langDisplayName;
                if (voiceStatusBadge) {
                    voiceStatusBadge.textContent = 'Transcribing...';
                    voiceStatusBadge.className = 'voice-status-badge processing';
                }
                if (voiceLangSelectRow) voiceLangSelectRow.style.display = 'none';
                if (voiceToggleRow) voiceToggleRow.style.display = 'none';
                if (translateToGroup) translateToGroup.style.display = 'none';
                if (voiceCenterVisual) voiceCenterVisual.style.display = 'flex';

                if (modalMicRecordBtn) {
                    modalMicRecordBtn.className = 'voice-record-circle processing';
                    modalMicRecordBtn.disabled = true;
                }
                if (modalMicIcon) modalMicIcon.setAttribute('data-lucide', 'loader');
                if (modalMicStatus) modalMicStatus.textContent = 'Transcribing your speech...';
                if (modalWaveformCanvas) modalWaveformCanvas.style.display = 'none';
                if (modalRecordTimer) modalRecordTimer.style.display = 'none';
                if (transcriptionResultContainer) transcriptionResultContainer.style.display = 'none';

                if (cancelVoiceBtn) cancelVoiceBtn.style.display = 'none';
                if (rerecordVoiceBtn) rerecordVoiceBtn.style.display = 'none';

                if (startVoiceRecordBtn) {
                    startVoiceRecordBtn.style.display = 'inline-flex';
                    startVoiceRecordBtn.disabled = true;
                    startVoiceRecordBtn.className = 'btn-primary btn-record-start processing';
                    if (startBtnIcon) startBtnIcon.setAttribute('data-lucide', 'loader');
                    if (startBtnText) startBtnText.textContent = 'Transcribing...';
                }
            }
            else if (state === 'translating') {
                if (voiceStatusBadge) {
                    voiceStatusBadge.textContent = 'Translating...';
                    voiceStatusBadge.className = 'voice-status-badge processing';
                }
                if (voiceLangSelectRow) voiceLangSelectRow.style.setProperty('display', 'none', 'important');
                if (voiceToggleRow) voiceToggleRow.style.setProperty('display', 'none', 'important');
                if (translateToGroup) translateToGroup.style.setProperty('display', 'none', 'important');
                if (voiceCenterVisual) voiceCenterVisual.style.setProperty('display', 'flex', 'important');
                if (transcriptionResultContainer) transcriptionResultContainer.style.setProperty('display', 'none', 'important');
                if (modalMicStatus) modalMicStatus.textContent = 'Translating text...';
            }
            else if (state === 'completed') {
                isRecording = false;
                if (modalHeaderBadgeIcon) modalHeaderBadgeIcon.textContent = '✓';
                if (modalCardTitle) modalCardTitle.textContent = 'Transcription Complete';

                latestSourceLang = extraData.sourceLang || 'en';
                latestTargetLang = extraData.targetLang || getTargetLang();

                const srcLangDisplay = getLangDisplayName(latestSourceLang);
                if (voiceLangIndicator) voiceLangIndicator.textContent = srcLangDisplay;

                if (voiceStatusBadge) {
                    voiceStatusBadge.textContent = 'Detected: ' + srcLangDisplay;
                    voiceStatusBadge.className = 'voice-status-badge completed';
                }

                // Hide all top setting rows & center mic visual so ONLY speech result cards show!
                if (voiceLangSelectRow) voiceLangSelectRow.style.setProperty('display', 'none', 'important');
                if (voiceToggleRow) voiceToggleRow.style.setProperty('display', 'none', 'important');
                if (translateToGroup) translateToGroup.style.setProperty('display', 'none', 'important');
                if (voiceCenterVisual) voiceCenterVisual.style.setProperty('display', 'none', 'important');

                const modalBody = document.querySelector('.voice-modal-body');
                if (modalBody) modalBody.scrollTop = 0;

                if (transcriptionResultContainer) {
                    transcriptionResultContainer.style.setProperty('display', 'flex', 'important');

                    if (originalSpeechCard && voiceOriginalTextarea) {
                        originalSpeechCard.style.setProperty('display', 'block', 'important');
                        voiceOriginalTextarea.value = extraData.originalText || '';
                        if (originalLangTag) originalLangTag.textContent = srcLangDisplay.toUpperCase();
                    }

                    if (isTranslationOn) {
                        if (translationSpeechCard && voiceTranslatedTextarea) {
                            translationSpeechCard.style.setProperty('display', 'block', 'important');
                            if (extraData.translatedText) {
                                voiceTranslatedTextarea.value = extraData.translatedText;
                            } else if (extraData.translationErrorText) {
                                voiceTranslatedTextarea.value = extraData.translationErrorText;
                            } else {
                                voiceTranslatedTextarea.value = '';
                            }
                            const tgtLangDisplay = getLangDisplayName(latestTargetLang);
                            if (translationLangTag) translationLangTag.textContent = tgtLangDisplay.toUpperCase();
                        }
                    } else {
                        if (translationSpeechCard) translationSpeechCard.style.setProperty('display', 'none', 'important');
                    }

                    const savedVoiceSetting = getAppSettings().ttsVoice || 'nova';
                    const origVoiceSelect = document.getElementById('originalVoiceAccentSelect');
                    const transVoiceSelect = document.getElementById('translationVoiceAccentSelect');
                    if (origVoiceSelect) origVoiceSelect.value = savedVoiceSetting;
                    if (transVoiceSelect) transVoiceSelect.value = savedVoiceSetting;
                }

                if (cancelVoiceBtn) {
                    cancelVoiceBtn.style.display = 'inline-flex';
                    cancelVoiceBtn.textContent = 'Close';
                }

                if (rerecordVoiceBtn) {
                    rerecordVoiceBtn.style.display = 'inline-flex';
                }

                if (startVoiceRecordBtn) {
                    startVoiceRecordBtn.style.display = 'none';
                }

                // Auto-Speak Answers if enabled in Keyboard Settings
                const appSettings = getAppSettings();
                if (appSettings.autoSpeak && appSettings.ttsEnabled) {
                    const textToSpeak = (isTranslationOn && extraData.translatedText) ? extraData.translatedText : (extraData.originalText || '');
                    const langToSpeak = (isTranslationOn && extraData.translatedText) ? latestTargetLang : latestSourceLang;
                    if (textToSpeak) {
                        setTimeout(() => {
                            if (voiceState === 'completed') {
                                handleTTSPlay(textToSpeak, langToSpeak, null, null, null, '');
                            }
                        }, 400);
                    }
                }
            }
            else if (state === 'error') {
                isRecording = false;
                if (modalHeaderBadgeIcon) modalHeaderBadgeIcon.textContent = '⚠️';
                if (modalCardTitle) modalCardTitle.textContent = 'Unable to Transcribe';
                if (voiceStatusBadge) {
                    voiceStatusBadge.textContent = 'Error';
                    voiceStatusBadge.className = 'voice-status-badge error';
                }
                if (voiceLangSelectRow) voiceLangSelectRow.style.display = 'flex';
                if (voiceCenterVisual) voiceCenterVisual.style.display = 'flex';

                if (modalMicRecordBtn) {
                    modalMicRecordBtn.className = 'voice-record-circle error';
                    modalMicRecordBtn.disabled = false;
                }
                if (modalMicIcon) modalMicIcon.setAttribute('data-lucide', 'alert-triangle');
                if (modalMicStatus) modalMicStatus.textContent = extraData.error || 'Unable to transcribe audio. Please try again.';
                if (modalWaveformCanvas) modalWaveformCanvas.style.display = 'none';
                if (modalRecordTimer) modalRecordTimer.style.display = 'none';
                if (liveSpeechPreview) liveSpeechPreview.style.display = 'none';
                if (transcriptionResultContainer) transcriptionResultContainer.style.display = 'none';

                if (cancelVoiceBtn) {
                    cancelVoiceBtn.style.display = 'inline-flex';
                    cancelVoiceBtn.textContent = 'Close';
                }
                if (rerecordVoiceBtn) rerecordVoiceBtn.style.display = 'inline-flex';

                if (startVoiceRecordBtn) {
                    startVoiceRecordBtn.style.display = 'inline-flex';
                    startVoiceRecordBtn.disabled = false;
                    startVoiceRecordBtn.className = 'btn-primary btn-record-start error';
                    if (startBtnIcon) startBtnIcon.setAttribute('data-lucide', 'refresh-cw');
                    if (startBtnText) startBtnText.textContent = 'Try Again';
                }
            }

            if (window.lucide) lucide.createIcons();
        }

        function resetToIdleState() {
            stopSpeaking();
            clearInterval(recordTimerInterval);
            if (animFrameId) {
                cancelAnimationFrame(animFrameId);
                animFrameId = null;
            }

            if (speechRecognizer) {
                try {
                    speechRecognizer.onresult = null;
                    speechRecognizer.onerror = null;
                    speechRecognizer.onend = null;
                    speechRecognizer.stop();
                } catch (e) { }
                speechRecognizer = null;
            }
            if (mediaRecorder) {
                try {
                    mediaRecorder.onstop = null;
                    mediaRecorder.ondataavailable = null;
                    if (mediaRecorder.stream) {
                        mediaRecorder.stream.getTracks().forEach(track => {
                            try { track.stop(); } catch (err) { }
                        });
                    }
                    if (mediaRecorder.state !== 'inactive') {
                        mediaRecorder.stop();
                    }
                } catch (e) { }
                mediaRecorder = null;
            }
            if (audioContext && audioContext.state !== 'closed') {
                try { audioContext.close(); } catch (e) { }
                audioContext = null;
            }

            finalText = '';
            partialText = '';
            isPaused = false;
            totalPausedMs = 0;
            pauseStartTime = 0;
            audioChunks = [];

            setVoiceState('idle');
        }

        function cancelRecordingSession(e) {
            if (e) {
                e.preventDefault();
                e.stopPropagation();
            }

            activeRecordingSessionToken++;
            stopSpeaking();
            clearInterval(recordTimerInterval);
            recordTimerInterval = null;
            if (animFrameId) {
                cancelAnimationFrame(animFrameId);
                animFrameId = null;
            }

            if (speechRecognizer) {
                try {
                    speechRecognizer.onresult = null;
                    speechRecognizer.onerror = null;
                    speechRecognizer.onend = null;
                    speechRecognizer.stop();
                } catch (err) { }
                speechRecognizer = null;
            }

            if (mediaRecorder) {
                try {
                    // Detach onstop callback so MediaRecorder.stop() does not trigger transcription
                    mediaRecorder.onstop = null;
                    mediaRecorder.ondataavailable = null;
                    if (mediaRecorder.stream) {
                        mediaRecorder.stream.getTracks().forEach(track => {
                            try { track.stop(); } catch (err) { }
                        });
                    }
                    if (mediaRecorder.state !== 'inactive') {
                        mediaRecorder.stop();
                    }
                } catch (err) { }
                mediaRecorder = null;
            }

            if (audioContext && audioContext.state !== 'closed') {
                try { audioContext.close(); } catch (e) { }
                audioContext = null;
            }

            finalText = '';
            partialText = '';
            accumulatedTextPrefix = '';
            isPaused = false;
            totalPausedMs = 0;
            pauseStartTime = 0;
            audioChunks = [];

            const voiceOriginalTextarea = document.getElementById('voiceOriginalTextarea');
            const voiceTranslatedTextarea = document.getElementById('voiceTranslatedTextarea');
            const liveSpeechPreview = document.getElementById('liveSpeechPreview');
            if (voiceOriginalTextarea) voiceOriginalTextarea.value = '';
            if (voiceTranslatedTextarea) voiceTranslatedTextarea.value = '';
            if (liveSpeechPreview) {
                liveSpeechPreview.textContent = '';
                liveSpeechPreview.style.display = 'none';
            }

            setVoiceState('idle');
        }

        function cancelRecording(e) {
            cancelRecordingSession(e);
            closeVoiceModal();
        }

        function closeVoiceModal() {
            cancelRecordingSession();
            if (voiceModal) voiceModal.classList.remove('active');

            const targetEl = lastActiveInput || currentTargetInput || document.getElementById('mainSearchInput');
            if (targetEl && (targetEl.tagName === 'INPUT' || targetEl.tagName === 'TEXTAREA')) {
                setTimeout(() => {
                    try {
                        targetEl.disabled = false;
                        targetEl.readOnly = false;
                        targetEl.style.pointerEvents = 'auto';
                        targetEl.focus();
                        const len = targetEl.value ? targetEl.value.length : 0;
                        targetEl.setSelectionRange(len, len);
                    } catch (e) { }
                }, 50);
            }
        }

        let lastActiveInput = null;

        function openVoiceModal(targetEl = null) {
            lastActiveInput = targetEl || currentTargetInput || TextInsertionService.getActiveInput();
            hideVirtualKeyboard();
            resetToIdleState();
            const voiceOriginalTextarea = document.getElementById('voiceOriginalTextarea');
            if (lastActiveInput && (lastActiveInput.tagName === 'INPUT' || lastActiveInput.tagName === 'TEXTAREA') && lastActiveInput.value.trim()) {
                if (voiceOriginalTextarea) voiceOriginalTextarea.value = lastActiveInput.value.trim();
            }
            if (voiceModal) voiceModal.classList.add('active');
        }

        if (voiceModal) {
            voiceModal.addEventListener('click', (e) => {
                if (e.target === voiceModal) {
                    closeVoiceModal();
                }
            });
        }

        if (mainMicBtn) {
            mainMicBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                openVoiceModal();
            });
        }

        if (closeVoiceModalBtn) {
            closeVoiceModalBtn.addEventListener('click', (e) => {
                e.preventDefault();
                e.stopPropagation();
                closeVoiceModal();
            });
        }

        if (cancelVoiceBtn) {
            cancelVoiceBtn.addEventListener('click', (e) => {
                e.preventDefault();
                e.stopPropagation();
                closeVoiceModal();
            });
        }

        if (startVoiceRecordBtn) {
            startVoiceRecordBtn.addEventListener('click', (e) => {
                e.preventDefault();
                if (voiceState === 'recording' || voiceState === 'paused' || isRecording) {
                    stopRecordingFlow();
                } else if (voiceState === 'completed') {
                    resetToIdleState();
                } else {
                    startRecordingFlow();
                }
            });
        }
        if (modalMicRecordBtn) {
            modalMicRecordBtn.addEventListener('click', (e) => {
                e.preventDefault();
                togglePauseRecording();
            });
        }
        if (rerecordVoiceBtn) {
            rerecordVoiceBtn.addEventListener('click', (e) => {
                e.preventDefault();
                resetToIdleState();
            });
        }

        // --- Central Audio & TTS Manager ---
        let activeTTSAudio = null;
        let activeTTSFetchController = null;

        function stopSpeaking() {
            if (activeTTSFetchController) {
                try {
                    activeTTSFetchController.abort();
                } catch (e) { }
                activeTTSFetchController = null;
            }
            if (activeTTSAudio) {
                try {
                    activeTTSAudio.pause();
                    activeTTSAudio.currentTime = 0;
                } catch (e) { }
                activeTTSAudio = null;
            }
            if ('speechSynthesis' in window) {
                try {
                    window.speechSynthesis.cancel();
                } catch (e) { }
            }
            // Reset speech button indicators across UI
            const speakButtons = document.querySelectorAll('#speakOriginalBtn, #speakTranslationBtn, #speakAiAnswerBtn, .btn-audio-play, #testVoiceBtn');
            speakButtons.forEach(btn => {
                const iconEl = btn.querySelector('i') || btn.querySelector('[data-lucide]');
                const textEl = btn.querySelector('span');
                if (iconEl) iconEl.setAttribute('data-lucide', 'volume-2');
                if (btn.id === 'speakOriginalBtn' && textEl) textEl.textContent = 'Speak Original';
                if (btn.id === 'speakTranslationBtn' && textEl) textEl.textContent = 'Speak Translation';
                if (btn.id === 'speakAiAnswerBtn' && textEl) textEl.textContent = 'Speak Answer';
                if (btn.id === 'testVoiceBtn' && textEl) textEl.textContent = 'Test Voice';
            });
            if (window.lucide) lucide.createIcons();
        }

        async function speakText(text, language = 'en', btnEl = null, iconEl = null, textEl = null, label = '', voiceOverride = null) {
            console.log("speakText triggered for text:", text, "language:", language, "voiceOverride:", voiceOverride);
            if (!text || !text.trim()) {
                console.warn("speakText called with empty text");
                return;
            }

            const settings = getAppSettings();
            if (!settings.ttsEnabled) {
                console.warn('Voice Output (TTS) is turned off in Keyboard Settings.');
                return;
            }

            // Stop any currently playing audio or pending request first (toggle behavior)
            const isCurrentlySpeaking = (activeTTSAudio && !activeTTSAudio.paused) || activeTTSFetchController !== null || (window.speechSynthesis && window.speechSynthesis.speaking);
            if (isCurrentlySpeaking) {
                stopSpeaking();
                return;
            }
            stopSpeaking();

            const targetIconEl = iconEl || (btnEl ? (btnEl.querySelector('i') || btnEl.querySelector('[data-lucide]')) : null);
            const targetTextEl = textEl || (btnEl ? btnEl.querySelector('span') : null);
            const defaultLabel = label || (targetTextEl ? targetTextEl.textContent : 'Speak');

            if (targetTextEl) targetTextEl.textContent = 'Speaking...';
            if (targetIconEl) targetIconEl.setAttribute('data-lucide', 'volume-x');
            if (window.lucide) lucide.createIcons();

            try {
                const ttsVoiceVal = voiceOverride || settings.ttsVoice || 'nova';
                const speedVal = parseFloat(settings.speechPlaybackSpeed || settings.ttsSpeed) || 1.0;

                activeTTSFetchController = new AbortController();
                const res = await fetch('/api/voice/speak', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        text: text.trim(),
                        language: language || 'en',
                        voice: ttsVoiceVal,
                        speed: speedVal
                    }),
                    signal: activeTTSFetchController.signal
                });
                activeTTSFetchController = null;

                console.log("TTS API response status:", res.status);

                if (res.ok) {
                    const data = await res.json();
                    console.log("TTS API response data:", data);
                    if (data.success && data.audio_url) {
                        const fullUrl = data.audio_url.startsWith('http') ? data.audio_url : `${window.location.origin}${data.audio_url}?t=${Date.now()}`;
                        const audio = new Audio(fullUrl);
                        audio.playbackRate = speedVal;
                        activeTTSAudio = audio;

                        audio.onended = () => {
                            activeTTSAudio = null;
                            if (targetTextEl) targetTextEl.textContent = defaultLabel;
                            if (targetIconEl) targetIconEl.setAttribute('data-lucide', 'volume-2');
                            if (window.lucide) lucide.createIcons();
                        };
                        audio.onerror = (err) => {
                            console.error("Audio element error during playback:", err);
                            activeTTSAudio = null;
                            if (targetTextEl) targetTextEl.textContent = defaultLabel;
                            if (targetIconEl) targetIconEl.setAttribute('data-lucide', 'volume-2');
                            if (window.lucide) lucide.createIcons();
                        };

                        await audio.play();
                        return;
                    }
                }
            } catch (err) {
                activeTTSFetchController = null;
                if (err.name === 'AbortError') {
                    console.log('TTS fetch aborted');
                    return;
                }
                console.warn('Backend TTS endpoint error, falling back to Web Speech API:', err);
            }

            // Web Speech API Fallback
            if ('speechSynthesis' in window) {
                try {
                    window.speechSynthesis.cancel();
                    const utterance = new SpeechSynthesisUtterance(text.trim());
                    utterance.lang = getLangTag(language);
                    const speedVal = parseFloat(settings.speechPlaybackSpeed || settings.ttsSpeed) || 1.0;
                    utterance.rate = speedVal;

                    const voices = window.speechSynthesis.getVoices();
                    if (voices && voices.length > 0) {
                        const vName = (voiceOverride || settings.ttsVoice || 'nova').toLowerCase();
                        const isMale = ['echo', 'onyx', 'fable'].includes(vName);
                        const isFemale = ['nova', 'shimmer'].includes(vName);

                        let matched = voices.find(v => {
                            const nameLower = v.name.toLowerCase();
                            const matchesLang = v.lang.toLowerCase().startsWith(getLangTag(language).toLowerCase().split('-')[0]);
                            if (!matchesLang) return false;
                            if (isMale && (nameLower.includes('male') || nameLower.includes('david') || nameLower.includes('mark') || nameLower.includes('george') || nameLower.includes('guy'))) return true;
                            if (isFemale && (nameLower.includes('female') || nameLower.includes('zira') || nameLower.includes('hazel') || nameLower.includes('susan') || nameLower.includes('aria'))) return true;
                            return false;
                        });
                        if (!matched) {
                            matched = voices.find(v => v.lang.toLowerCase().startsWith(getLangTag(language).toLowerCase().split('-')[0]));
                        }
                        if (matched) utterance.voice = matched;
                    }

                    utterance.onend = () => {
                        if (targetTextEl) targetTextEl.textContent = defaultLabel;
                        if (targetIconEl) targetIconEl.setAttribute('data-lucide', 'volume-2');
                        if (window.lucide) lucide.createIcons();
                    };
                    utterance.onerror = () => {
                        if (targetTextEl) targetTextEl.textContent = defaultLabel;
                        if (targetIconEl) targetIconEl.setAttribute('data-lucide', 'volume-2');
                        if (window.lucide) lucide.createIcons();
                    };

                    window.speechSynthesis.speak(utterance);
                    return;
                } catch (synthErr) {
                    console.error('SpeechSynthesis error:', synthErr);
                }
            }

            if (targetTextEl) targetTextEl.textContent = defaultLabel;
            if (targetIconEl) targetIconEl.setAttribute('data-lucide', 'volume-2');
            if (window.lucide) lucide.createIcons();
        }

        // Compatibility Aliases
        function handleTTSPlay(text, lang, btnEl, iconEl, textEl, label, voiceOverride = null) {
            return speakText(text, lang, btnEl, iconEl, textEl, label, voiceOverride);
        }

        function playTextToSpeech(text, lang, btnEl) {
            return speakText(text, lang, btnEl);
        }

        const speakOriginalBtn = document.getElementById('speakOriginalBtn');
        const speakOriginalIcon = document.getElementById('speakOriginalIcon');
        const speakOriginalText = document.getElementById('speakOriginalText');
        const copyOriginalBtn = document.getElementById('copyOriginalBtn');
        const copyOriginalText = document.getElementById('copyOriginalText');
        const insertOriginalBtn = document.getElementById('insertOriginalBtn');
        const voiceOriginalTextarea = document.getElementById('voiceOriginalTextarea');

        if (voiceOriginalTextarea) {
            voiceOriginalTextarea.addEventListener('input', () => {
                if (isTranslationOn) triggerTranslationDebounced();
            });
        }

        const speakTranslationBtn = document.getElementById('speakTranslationBtn');
        const speakTranslationIcon = document.getElementById('speakTranslationIcon');
        const speakTranslationText = document.getElementById('speakTranslationText');
        const copyTranslationBtn = document.getElementById('copyTranslationBtn');
        const copyTranslationText = document.getElementById('copyTranslationText');
        const insertTranslationBtn = document.getElementById('insertTranslationBtn');
        const voiceTranslatedTextarea = document.getElementById('voiceTranslatedTextarea');

        const originalVoiceAccentSelect = document.getElementById('originalVoiceAccentSelect');
        const translationVoiceAccentSelect = document.getElementById('translationVoiceAccentSelect');

        if (originalVoiceAccentSelect) {
            originalVoiceAccentSelect.addEventListener('change', (e) => {
                const val = e.target.value;
                const currentSettings = getAppSettings();
                currentSettings.ttsVoice = val;
                saveAppSettings(currentSettings);
                if (translationVoiceAccentSelect) translationVoiceAccentSelect.value = val;
                const globalSelect = document.getElementById('ttsVoiceSelect');
                if (globalSelect) globalSelect.value = val;
            });
        }

        if (translationVoiceAccentSelect) {
            translationVoiceAccentSelect.addEventListener('change', (e) => {
                const val = e.target.value;
                const currentSettings = getAppSettings();
                currentSettings.ttsVoice = val;
                saveAppSettings(currentSettings);
                if (originalVoiceAccentSelect) originalVoiceAccentSelect.value = val;
                const globalSelect = document.getElementById('ttsVoiceSelect');
                if (globalSelect) globalSelect.value = val;
            });
        }

        if (speakOriginalBtn) {
            speakOriginalBtn.addEventListener('click', (e) => {
                e.preventDefault();
                e.stopPropagation();
                const origArea = document.getElementById('voiceOriginalTextarea');
                const text = origArea ? origArea.value.trim() : '';
                const selectedVoice = document.getElementById('originalVoiceAccentSelect')?.value || getAppSettings().ttsVoice || 'nova';
                console.log("Speak Original clicked, text:", text, "voice:", selectedVoice);
                handleTTSPlay(text, latestSourceLang || 'en', speakOriginalBtn, speakOriginalIcon, speakOriginalText, 'Speak Original', selectedVoice);
            });
        }

        if (copyOriginalBtn) {
            copyOriginalBtn.addEventListener('click', (e) => {
                e.preventDefault();
                e.stopPropagation();
                const origArea = document.getElementById('voiceOriginalTextarea');
                const text = origArea ? origArea.value : '';
                if (text) {
                    navigator.clipboard.writeText(text).then(() => {
                        if (copyOriginalText) copyOriginalText.textContent = '✓ Copied!';
                        setTimeout(() => { if (copyOriginalText) copyOriginalText.textContent = 'Copy Original'; }, 1500);
                    });
                }
            });
        }

        if (insertOriginalBtn) {
            insertOriginalBtn.addEventListener('click', (e) => {
                e.preventDefault();
                e.stopPropagation();
                const origArea = document.getElementById('voiceOriginalTextarea');
                const text = origArea ? origArea.value.trim() : '';
                if (text) {
                    const targetEl = lastActiveInput || currentTargetInput || document.getElementById('mainSearchInput');
                    TextInsertionService.insertText(text, targetEl);
                }
                closeVoiceModal();
            });
        }

        if (speakTranslationBtn) {
            speakTranslationBtn.addEventListener('click', (e) => {
                e.preventDefault();
                e.stopPropagation();
                const transArea = document.getElementById('voiceTranslatedTextarea');
                const text = transArea ? transArea.value.trim() : '';
                const selectedVoice = document.getElementById('translationVoiceAccentSelect')?.value || getAppSettings().ttsVoice || 'nova';
                console.log("Speak Translation clicked, text:", text, "voice:", selectedVoice);
                handleTTSPlay(text, latestTargetLang || 'en', speakTranslationBtn, speakTranslationIcon, speakTranslationText, 'Speak Translation', selectedVoice);
            });
        }

        if (copyTranslationBtn) {
            copyTranslationBtn.addEventListener('click', (e) => {
                e.preventDefault();
                e.stopPropagation();
                const text = voiceTranslatedTextarea ? voiceTranslatedTextarea.value : '';
                if (text) {
                    navigator.clipboard.writeText(text).then(() => {
                        if (copyTranslationText) copyTranslationText.textContent = '✓ Copied!';
                        setTimeout(() => { if (copyTranslationText) copyTranslationText.textContent = 'Copy Translation'; }, 1500);
                    });
                }
            });
        }

        if (insertTranslationBtn) {
            insertTranslationBtn.addEventListener('click', (e) => {
                e.preventDefault();
                e.stopPropagation();
                const text = voiceTranslatedTextarea ? voiceTranslatedTextarea.value.trim() : '';
                if (text) {
                    const targetEl = lastActiveInput || currentTargetInput || document.getElementById('mainSearchInput');
                    TextInsertionService.insertText(text, targetEl);
                }
                closeVoiceModal();
            });
        }

        function handleInsertTextClick() {
            const voiceTranscriptionTextarea = document.getElementById('voiceTranscriptionTextarea');
            const textToInsert = voiceTranscriptionTextarea ? voiceTranscriptionTextarea.value.trim() : '';
            if (!textToInsert) {
                alert('No transcribed text to insert.');
                return;
            }
            const targetEl = lastActiveInput || currentTargetInput || document.getElementById('mainSearchInput');
            console.log("Inserting text into target element:", targetEl ? (targetEl.id || targetEl.tagName) : "None");

            // Insert text into search bar
            TextInsertionService.insertText(textToInsert, targetEl, insertTextVoiceBtn);


            closeVoiceModal();
        }

        // Hide keyboard on outside click
        document.addEventListener('click', (e) => {
            if (
                virtualKeyboardCard &&
                !virtualKeyboardCard.contains(e.target) &&
                mainSearchInput &&
                !mainSearchInput.contains(e.target) &&
                mainMicBtn &&
                !mainMicBtn.contains(e.target) &&
                voiceModal &&
                !voiceModal.contains(e.target)
            ) {
                hideVirtualKeyboard();
            }
        });

        // Virtual Keyboard & Search Listeners
        VirtualKeyboard.init();

        if (mainSearchInput) {
            mainSearchInput.addEventListener('keydown', (e) => {
                if (e.key === 'Enter') {
                    e.preventDefault();
                    performSearch();
                    hideVirtualKeyboard();
                }
            });
            mainSearchInput.addEventListener('input', () => {
                if (originalTextEditor && originalTextEditor !== mainSearchInput) {
                    originalTextEditor.value = mainSearchInput.value;
                }
                if (isTranslationOn) triggerTranslationDebounced();
            });
        }

        if (mainSearchActionBtn) mainSearchActionBtn.addEventListener('click', () => {
            performSearch();
            hideVirtualKeyboard();
        });
        if (globalSearchActionBtn) globalSearchActionBtn.addEventListener('click', () => {
            performSearch();
            hideVirtualKeyboard();
        });

        const bottomSearchBtn = document.getElementById('bottomSearchBtn');
        if (bottomSearchBtn) bottomSearchBtn.addEventListener('click', () => {
            performSearch();
            hideVirtualKeyboard();
        });

        let isSearchInProgress = false;

        const closeSearchResultsBtn = document.getElementById('closeSearchResultsBtn');
        if (closeSearchResultsBtn) {
            closeSearchResultsBtn.addEventListener('click', () => {
                const searchCard = document.getElementById('searchResultsCard');
                if (searchCard) searchCard.style.display = 'none';
                stopSpeaking();
            });
        }

        async function performSearch(queryText = null) {
            const mainSearchInput = document.getElementById('mainSearchInput');
            const globalSearchInput = document.getElementById('globalSearchInput');
            const query = (queryText || (mainSearchInput ? mainSearchInput.value : '') || (globalSearchInput ? globalSearchInput.value : '')).trim();

            if (!query) {
                alert('Please type a question to search.');
                return;
            }

            if (isSearchInProgress) return;
            isSearchInProgress = true;

            const mainSearchActionBtn = document.getElementById('mainSearchActionBtn');
            if (mainSearchActionBtn) mainSearchActionBtn.disabled = true;

            const searchCard = document.getElementById('searchResultsCard');
            const searchContainer = document.getElementById('searchResultsContainer');
            const queryTitle = document.getElementById('searchQueryTitle');

            if (queryTitle) queryTitle.textContent = `"${query}"`;
            if (searchCard) {
                searchCard.style.display = 'block';
            }

            if (searchContainer) {
                searchContainer.innerHTML = `
                    <div style="text-align: center; padding: 28px 16px; color: var(--text-secondary);">
                        <i data-lucide="bot" class="spin" style="width: 28px; height: 28px; color: var(--primary-color); margin-bottom: 8px;"></i>
                        <div style="font-weight: 600; font-size: 0.95rem; color: var(--text-primary);">🤖 Butterfly AI is thinking...</div>
                        <div style="font-size: 0.8rem; color: var(--text-muted); margin-top: 4px;">Generating AI answer for "${escapeHtml(query)}"</div>
                    </div>
                `;
                if (window.lucide) lucide.createIcons();
                const workspace = document.querySelector('.keyboard-workspace');
                if (workspace && searchCard) {
                    workspace.scrollTo({ top: Math.max(0, searchCard.offsetTop - 30), behavior: 'smooth' });
                }
            }

            let aiAnswerText = '';
            let targetLang = getTargetLang() || 'auto';
            let apiErrorMsg = '';

            try {
                const srcLang = getSourceLang() || 'auto';
                const res = await fetch('/api/ai/chat', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        message: query,
                        text: query,
                        source_language: srcLang,
                        language: targetLang
                    })
                });

                const data = await res.json();
                if (data.success && data.answer) {
                    aiAnswerText = data.answer;
                    if (data.language) targetLang = data.language;
                } else if (data.error) {
                    apiErrorMsg = data.error;
                } else {
                    apiErrorMsg = 'Server failed to produce an AI response.';
                }
            } catch (err) {
                console.error("Search AI API call failed:", err);
                apiErrorMsg = "Unable to connect to Butterfly AI server. Please check your network connection.";
            }

            if (aiAnswerText) {
                renderSearchResultsUI(query, aiAnswerText, targetLang);
                fetchAndRenderWebLinks(query);

                const appSettings = getAppSettings();
                if (appSettings.autoSpeak && appSettings.ttsEnabled) {
                    setTimeout(() => {
                        const speakBtn = document.getElementById('speakAiAnswerBtn');
                        const selectedVoice = document.getElementById('translationVoiceAccentSelect')?.value || document.getElementById('ttsVoiceSelect')?.value || appSettings.ttsVoice || 'nova';
                        speakText(aiAnswerText, targetLang, speakBtn, null, null, 'Speak Answer', selectedVoice);
                    }, 350);
                }
            } else {
                renderSearchErrorUI(query, apiErrorMsg || "Unable to generate AI response. Please check API Key configuration.");
            }

            isSearchInProgress = false;
            if (mainSearchActionBtn) mainSearchActionBtn.disabled = false;
        }

        function renderSearchErrorUI(query, errorMessage) {
            const searchContainer = document.getElementById('searchResultsContainer');
            if (!searchContainer) return;

            searchContainer.innerHTML = `
                <div class="ai-answer-card" style="border-color: rgba(239, 68, 68, 0.5); background: rgba(239, 68, 68, 0.08);">
                    <div class="ai-answer-header">
                        <div class="ai-answer-badge" style="background: rgba(239, 68, 68, 0.2); color: #f87171;">
                            <i data-lucide="alert-triangle"></i>
                            <span>AI ERROR</span>
                        </div>
                    </div>
                    <div class="ai-answer-question">"${escapeHtml(query)}"</div>
                    <div class="ai-answer-text" style="color: #fca5a5;">${escapeHtml(errorMessage)}</div>
                    <div class="ai-answer-actions" style="margin-top: 12px;">
                        <button type="button" class="btn-action-pill" id="openSettingsFromErrorBtn" title="Open Settings to update API Key">
                            <i data-lucide="settings"></i> <span>Open Settings</span>
                        </button>
                    </div>
                </div>

                <div class="web-search-section">
                    <div class="web-search-header">
                        <i data-lucide="globe"></i> <span>WEB SEARCH</span>
                    </div>
                    <div id="webSearchItemsContainer">
                        <div style="padding: 10px; font-size: 0.8rem; color: var(--text-muted);"><i data-lucide="loader-2" class="spin"></i> Loading web links...</div>
                    </div>
                </div>
            `;
            if (window.lucide) lucide.createIcons();
            const openSettingsBtn = document.getElementById('openSettingsFromErrorBtn');
            if (openSettingsBtn) {
                openSettingsBtn.addEventListener('click', () => {
                    const settingsModal = document.getElementById('settingsModal');
                    if (settingsModal) settingsModal.style.display = 'flex';
                });
            }
            if (typeof fetchAndRenderWebLinks === 'function') {
                fetchAndRenderWebLinks(query);
            }
        }

        function escapeHtml(str) {
            if (!str) return '';
            return String(str)
                .replace(/&/g, '&amp;')
                .replace(/</g, '&lt;')
                .replace(/>/g, '&gt;')
                .replace(/"/g, '&quot;')
                .replace(/'/g, '&#039;');
        }


        function renderSearchResultsUI(query, aiAnswer, lang = 'en') {
            const searchContainer = document.getElementById('searchResultsContainer');
            if (!searchContainer) return;

            let currentAnswerLang = lang || getTargetLang() || 'te';
            let originalAnswerText = aiAnswer;

            const languages = [
                { code: 'te', name: 'Telugu (తెలుగు)' },
                { code: 'en', name: 'English' },
                { code: 'hi', name: 'Hindi (हिन्दी)' },
                { code: 'ta', name: 'Tamil (தமிழ்)' },
                { code: 'kn', name: 'Kannada (ಕನ್ನಡ)' },
                { code: 'ml', name: 'Malayalam (മലയാളം)' },
                { code: 'mr', name: 'Marathi (मराठी)' },
                { code: 'bn', name: 'Bengali (বাংলা)' },
                { code: 'gu', name: 'Gujarati (ગુજરાતી)' },
                { code: 'pa', name: 'Punjabi (ਪੰਜਾਬੀ)' },
                { code: 'ur', name: 'Urdu (اردو)' },
                { code: 'es', name: 'Spanish' },
                { code: 'fr', name: 'French' },
                { code: 'de', name: 'German' },
                { code: 'it', name: 'Italian' },
                { code: 'pt', name: 'Portuguese' },
                { code: 'ar', name: 'Arabic' },
                { code: 'ja', name: 'Japanese' },
                { code: 'ko', name: 'Korean' },
                { code: 'zh', name: 'Chinese' },
                { code: 'ru', name: 'Russian' }
            ];

            const langOptionsHtml = languages.map(l => 
                `<option value="${l.code}" ${l.code === currentAnswerLang ? 'selected' : ''}>${l.name}</option>`
            ).join('');

            searchContainer.innerHTML = `
                <div class="ai-answer-card">
                    <div class="ai-answer-header">
                        <div class="ai-answer-badge">
                            <i data-lucide="bot"></i>
                            <span>AI ANSWER</span>
                        </div>
                    </div>
                    <div class="ai-answer-question">"${escapeHtml(query)}"</div>
                    <div class="ai-answer-text" id="aiAnswerContentText">${escapeHtml(aiAnswer)}</div>
                    <div class="ai-answer-actions">
                        <button type="button" class="btn-action-pill" id="speakAiAnswerBtn" title="Play AI Answer Audio">
                            <i data-lucide="volume-2" id="speakAiAnswerIcon"></i> <span id="speakAiAnswerTextLabel">Speak Answer</span>
                        </button>
                        
                        <div style="display: inline-flex; align-items: center; gap: 6px; flex-wrap: nowrap;">
                            <button type="button" class="btn-action-pill" id="translateAiAnswerBtn" title="Translate Answer">
                                <i data-lucide="languages" id="translateAiAnswerIcon"></i> <span id="translateAiAnswerTextLabel">Translate</span>
                            </button>
                            <select id="aiAnswerTargetLangSelect" class="card-voice-accent-select" style="max-width: 140px;" title="Target Language">
                                ${langOptionsHtml}
                            </select>
                        </div>

                        <button type="button" class="btn-action-pill" id="copyAiAnswerBtn" title="Copy AI Answer">
                            <i data-lucide="copy" id="copyAiAnswerIcon"></i> <span id="copyAiAnswerTextLabel">Copy Answer</span>
                        </button>
                    </div>
                </div>

                <div class="web-search-section">
                    <div class="web-search-header">
                        <i data-lucide="globe"></i> <span>WEB SEARCH</span>
                    </div>
                    <div id="webSearchItemsContainer">
                        <div style="padding: 10px; font-size: 0.8rem; color: var(--text-muted);"><i data-lucide="loader-2" class="spin"></i> Loading web links...</div>
                    </div>
                </div>
            `;
            if (window.lucide) lucide.createIcons();

            // Speak Answer Button
            const speakBtn = document.getElementById('speakAiAnswerBtn');
            if (speakBtn) {
                speakBtn.addEventListener('click', (e) => {
                    e.preventDefault();
                    e.stopPropagation();
                    const textToSpeak = document.getElementById('aiAnswerContentText')?.textContent || aiAnswer;
                    const selectedVoice = document.getElementById('translationVoiceAccentSelect')?.value || document.getElementById('ttsVoiceSelect')?.value || getAppSettings().ttsVoice || 'nova';
                    const activeLang = targetLangSelect ? targetLangSelect.value : (currentAnswerLang || 'auto');
                    speakText(textToSpeak, activeLang, speakBtn, null, null, 'Speak Answer', selectedVoice);
                });
            }

            // Translation Functionality
            const translateBtn = document.getElementById('translateAiAnswerBtn');
            const targetLangSelect = document.getElementById('aiAnswerTargetLangSelect');

            async function performAnswerTranslation() {
                if (!targetLangSelect) return;
                const targetLang = targetLangSelect.value;
                const currentText = document.getElementById('aiAnswerContentText')?.textContent || originalAnswerText;
                if (!currentText || !currentText.trim()) return;

                const translateLabel = document.getElementById('translateAiAnswerTextLabel');
                const translateIcon = document.getElementById('translateAiAnswerIcon');
                if (translateLabel) translateLabel.textContent = 'Translating...';
                if (translateIcon) translateIcon.setAttribute('data-lucide', 'loader-2');
                if (window.lucide) lucide.createIcons();

                try {
                    const res = await fetch('/api/translate', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({
                            text: originalAnswerText,
                            target_language: targetLang,
                            source_language: 'auto'
                        })
                    });

                    if (res.ok) {
                        const data = await res.json();
                        if (data.success && (data.translation || data.translated_text)) {
                            const translatedText = data.translation || data.translated_text;
                            const textEl = document.getElementById('aiAnswerContentText');
                            if (textEl) textEl.textContent = translatedText;
                            currentAnswerLang = targetLang;

                            if (translateLabel) translateLabel.textContent = '✓ Translated';
                            if (translateIcon) translateIcon.setAttribute('data-lucide', 'check');
                            if (window.lucide) lucide.createIcons();

                            setTimeout(() => {
                                if (translateLabel) translateLabel.textContent = 'Translate';
                                if (translateIcon) translateIcon.setAttribute('data-lucide', 'languages');
                                if (window.lucide) lucide.createIcons();
                            }, 1500);
                            return;
                        }
                    }
                } catch (err) {
                    console.error("Answer translation error:", err);
                }

                if (translateLabel) translateLabel.textContent = 'Translate';
                if (translateIcon) translateIcon.setAttribute('data-lucide', 'languages');
                if (window.lucide) lucide.createIcons();
            }

            if (translateBtn) {
                translateBtn.addEventListener('click', (e) => {
                    e.preventDefault();
                    e.stopPropagation();
                    performAnswerTranslation();
                });
            }

            if (targetLangSelect) {
                targetLangSelect.addEventListener('change', () => {
                    performAnswerTranslation();
                });
            }

            // Copy Answer Button
            const copyBtn = document.getElementById('copyAiAnswerBtn');
            if (copyBtn) {
                copyBtn.addEventListener('click', (e) => {
                    e.preventDefault();
                    e.stopPropagation();
                    const textToCopy = document.getElementById('aiAnswerContentText')?.textContent || aiAnswer;
                    if (textToCopy) {
                        navigator.clipboard.writeText(textToCopy).then(() => {
                            const label = document.getElementById('copyAiAnswerTextLabel');
                            if (label) label.textContent = '✓ Copied!';
                            setTimeout(() => { if (label) label.textContent = 'Copy Answer'; }, 1500);
                        });
                    }
                });
            }

            fetchAndRenderWebLinks(query);
        }

        async function fetchAndRenderWebLinks(query) {
            const container = document.getElementById('webSearchItemsContainer');
            if (!container) return;

            try {
                const res = await fetch(`${API_BASE}/api/search?q=${encodeURIComponent(query)}`);
                if (res.ok) {
                    const data = await res.json();
                    if (data.results && data.results.length > 0) {
                        container.innerHTML = data.results.map(item => `
                            <div class="search-result-item" style="margin-bottom: 8px;">
                                <a href="${escapeHtml(item.url)}" target="_blank" rel="noopener noreferrer" class="search-result-title">
                                    <i data-lucide="external-link" style="width: 14px; height: 14px; flex-shrink: 0;"></i> ${escapeHtml(item.title)}
                                </a>
                                <div class="search-result-url">${escapeHtml(item.url)}</div>
                                <div class="search-result-snippet">${escapeHtml(item.snippet)}</div>
                            </div>
                        `).join('');
                        if (window.lucide) lucide.createIcons();
                        return;
                    }
                }
            } catch (err) {
                console.warn("Web search links error:", err);
            }

            container.innerHTML = `
                <div class="search-result-item" style="margin-bottom: 8px;">
                    <a href="https://www.google.com/search?q=${encodeURIComponent(query)}" target="_blank" rel="noopener noreferrer" class="search-result-title">
                        <i data-lucide="external-link" style="width: 14px; height: 14px; flex-shrink: 0;"></i> Google Web Search: "${escapeHtml(query)}"
                    </a>
                    <div class="search-result-url">https://www.google.com/search?q=${encodeURIComponent(query)}</div>
                    <div class="search-result-snippet">Click to view live Google search results for "${escapeHtml(query)}".</div>
                </div>
                <div class="search-result-item">
                    <a href="https://duckduckgo.com/?q=${encodeURIComponent(query)}" target="_blank" rel="noopener noreferrer" class="search-result-title">
                        <i data-lucide="external-link" style="width: 14px; height: 14px; flex-shrink: 0;"></i> DuckDuckGo Search: "${escapeHtml(query)}"
                    </a>
                    <div class="search-result-url">https://duckduckgo.com/?q=${encodeURIComponent(query)}</div>
                    <div class="search-result-snippet">Click to view DuckDuckGo search results for "${escapeHtml(query)}".</div>
                </div>
            `;
            if (window.lucide) lucide.createIcons();
        }
    }

    function updateTargetLangTag() {
        if (!targetLangSelect || !targetLangTag) return;
        const selectedOption = targetLangSelect.options[targetLangSelect.selectedIndex];
        if (targetLangTag && selectedOption) {
            targetLangTag.textContent = selectedOption.text.split('(')[0].replace(/[^a-zA-Z\s]/g, '').trim().toUpperCase();
        }
    }

    function getSourceLang() {
        return sourceLangSelect ? sourceLangSelect.value : 'auto';
    }

    function getTargetLang() {
        return targetLangSelect ? targetLangSelect.value : 'en';
    }

    function generateUUID() {
        return 'session_' + Math.random().toString(36).substring(2, 11) + Date.now().toString(36);
    }

    function startNewSession() {
        currentSessionId = generateUUID();
        localStorage.setItem('ai_agent_session', currentSessionId);
        clearEditors();
        if (mainMicStatus) mainMicStatus.textContent = 'Ready to Speak';
        loadVoiceHistoryList();
    }

    // --- Voice Recording Flow (Strict State Machine: idle -> recording -> processing -> completed/error) ---

    window.handleStartRecordClick = function (e) {
        if (e) {
            e.preventDefault();
            e.stopPropagation();
        }
        toggleRecording();
    };

    let lastToggleTime = 0;
    let isPaused = false;
    let totalPausedMs = 0;
    let pauseStartTime = 0;

    function pauseRecordingFlow() {
        if (mediaRecorder && (mediaRecorder.state === 'recording' || mediaRecorder.state === 'paused')) {
            try {
                if (mediaRecorder.state === 'recording') {
                    mediaRecorder.pause();
                }
            } catch (e) {
                console.warn('MediaRecorder pause error:', e);
            }
        }
        if (speechRecognizer) {
            try { speechRecognizer.stop(); } catch (e) { }
            speechRecognizer = null;
        }
        isPaused = true;
        pauseStartTime = Date.now();
        setVoiceState('paused');
    }

    function resumeRecordingFlow() {
        if (pauseStartTime > 0) {
            totalPausedMs += (Date.now() - pauseStartTime);
            pauseStartTime = 0;
        }
        isPaused = false;

        if (mediaRecorder && mediaRecorder.state === 'paused') {
            try {
                mediaRecorder.resume();
            } catch (e) {
                console.warn('MediaRecorder resume error:', e);
            }
        }

        if (SpeechRecognition && !speechRecognizer) {
            try {
                speechRecognizer = new SpeechRecognition();
                speechRecognizer.continuous = true;
                speechRecognizer.interimResults = true;
                const activeSrcLang = getSourceLang();
                if (activeSrcLang && activeSrcLang !== 'auto') {
                    speechRecognizer.lang = getLangTag(activeSrcLang);
                } else {
                    speechRecognizer.lang = navigator.language || 'en-US';
                }

                speechRecognizer.onresult = (event) => {
                    let interim = '';
                    let finalChunk = '';
                    for (let i = event.resultIndex; i < event.results.length; ++i) {
                        if (event.results[i].isFinal) {
                            finalChunk += event.results[i][0].transcript;
                        } else {
                            interim += event.results[i][0].transcript;
                        }
                    }
                    if (finalChunk.trim()) {
                        finalText = (finalText ? finalText.trim() + ' ' : '') + finalChunk.trim();
                        partialText = '';
                    } else {
                        partialText = interim;
                    }
                    updateEditorDisplay();
                };
                speechRecognizer.start();
            } catch (e) {
                console.warn('SpeechRecognition resume error:', e);
            }
        }

        setVoiceState('recording');
    }

    function togglePauseRecording() {
        const now = Date.now();
        if (now - lastToggleTime < 300) return;
        lastToggleTime = now;

        if (voiceState === 'recording') {
            pauseRecordingFlow();
        } else if (voiceState === 'paused') {
            resumeRecordingFlow();
        } else if (voiceState === 'completed') {
            resetToIdleState();
        } else if (voiceState === 'idle' || voiceState === 'error') {
            startRecordingFlow();
        }
    }

    function toggleRecording() {
        const now = Date.now();
        if (now - lastToggleTime < 300) {
            return; // Ignore duplicate click events within 300ms
        }
        lastToggleTime = now;

        if (voiceState === 'recording' || voiceState === 'paused' || isRecording) {
            stopRecordingFlow();
        } else if (voiceState === 'completed') {
            resetToIdleState();
        } else if (voiceState === 'idle' || voiceState === 'error') {
            startRecordingFlow();
        }
    }

    async function startRecordingFlow() {
        if (voiceState === 'recording' || voiceState === 'processing') return;

        activeRecordingSessionToken++;
        const currentSessionToken = activeRecordingSessionToken;

        partialText = '';
        finalText = '';
        const origArea = document.getElementById('voiceOriginalTextarea');
        accumulatedTextPrefix = origArea ? origArea.value.trim() : '';

        try {
            console.log("Recording started");
            const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
            if (currentSessionToken !== activeRecordingSessionToken) {
                stream.getTracks().forEach(t => { try { t.stop(); } catch(e){} });
                return;
            }

            audioChunks = [];

            recordedMimeType = 'audio/webm;codecs=opus';
            recordedFileExt = 'webm';
            if (typeof MediaRecorder !== 'undefined' && typeof MediaRecorder.isTypeSupported === 'function') {
                if (MediaRecorder.isTypeSupported('audio/webm;codecs=opus')) {
                    recordedMimeType = 'audio/webm;codecs=opus';
                    recordedFileExt = 'webm';
                } else if (MediaRecorder.isTypeSupported('audio/webm')) {
                    recordedMimeType = 'audio/webm';
                    recordedFileExt = 'webm';
                } else if (MediaRecorder.isTypeSupported('audio/ogg;codecs=opus')) {
                    recordedMimeType = 'audio/ogg;codecs=opus';
                    recordedFileExt = 'ogg';
                } else if (MediaRecorder.isTypeSupported('audio/ogg')) {
                    recordedMimeType = 'audio/ogg';
                    recordedFileExt = 'ogg';
                } else if (MediaRecorder.isTypeSupported('audio/wav')) {
                    recordedMimeType = 'audio/wav';
                    recordedFileExt = 'wav';
                } else if (MediaRecorder.isTypeSupported('audio/mp4')) {
                    recordedMimeType = 'audio/mp4';
                    recordedFileExt = 'mp4';
                } else {
                    recordedMimeType = '';
                    recordedFileExt = 'webm';
                }
            } else {
                recordedMimeType = '';
                recordedFileExt = 'webm';
            }

            mediaRecorder = recordedMimeType ? new MediaRecorder(stream, { mimeType: recordedMimeType }) : new MediaRecorder(stream);
            console.log("MediaRecorder initialized with MIME type:", mediaRecorder.mimeType || "default");

            mediaRecorder.ondataavailable = (event) => {
                if (event.data && event.data.size > 0) {
                    audioChunks.push(event.data);
                }
            };

            setVoiceState('recording');

            try {
                audioContext = new (window.AudioContext || window.webkitAudioContext)();
                const source = audioContext.createMediaStreamSource(stream);
                analyser = audioContext.createAnalyser();
                analyser.fftSize = 64;
                source.connect(analyser);
                drawWaveform();
            } catch (e) {
                console.warn('Audio visualizer init error:', e);
            }

            isPaused = false;
            totalPausedMs = 0;
            pauseStartTime = 0;
            recordStartTime = Date.now();
            updateTimerDisplay();
            clearInterval(recordTimerInterval);
            recordTimerInterval = setInterval(updateTimerDisplay, 1000);

            if (SpeechRecognition) {
                try {
                    speechRecognizer = new SpeechRecognition();
                    speechRecognizer.continuous = true;
                    speechRecognizer.interimResults = true;
                    const activeSrcLang = getSourceLang();
                    if (activeSrcLang && activeSrcLang !== 'auto') {
                        speechRecognizer.lang = getLangTag(activeSrcLang);
                    } else {
                        speechRecognizer.lang = navigator.language || 'en-US';
                    }

                    speechRecognizer.onresult = (event) => {
                        if (currentSessionToken !== activeRecordingSessionToken) return;
                        let interim = '';
                        let finalChunk = '';
                        for (let i = event.resultIndex; i < event.results.length; ++i) {
                            if (event.results[i].isFinal) {
                                finalChunk += event.results[i][0].transcript;
                            } else {
                                interim += event.results[i][0].transcript;
                            }
                        }
                        if (finalChunk.trim()) {
                            finalText = (finalText ? finalText.trim() + ' ' : '') + finalChunk.trim();
                            partialText = '';
                        } else {
                            partialText = interim;
                        }
                        updateEditorDisplay();
                    };
                    speechRecognizer.start();
                } catch (e) {
                    console.warn('SpeechRecognition init error:', e);
                }
            }

            mediaRecorder.start(100);

        } catch (err) {
            console.warn('Microphone access denied or error:', err);
            if (currentSessionToken === activeRecordingSessionToken) {
                setVoiceState('error', { error: 'Microphone permission is required to record your voice.' });
            }
        }
    }

    function drawWaveform() {
        const canvas = document.getElementById('modalWaveformCanvas');
        if (!canvas || !analyser) return;
        const ctx = canvas.getContext('2d');
        const bufferLength = analyser.frequencyBinCount;
        const dataArray = new Uint8Array(bufferLength);

        function render() {
            if (!isRecording || voiceState === 'paused' || isPaused) return;
            animFrameId = requestAnimationFrame(render);
            analyser.getByteFrequencyData(dataArray);

            ctx.clearRect(0, 0, canvas.width, canvas.height);
            ctx.fillStyle = '#00D2FF';

            const barWidth = (canvas.width / bufferLength) * 1.5;
            let x = 0;

            for (let i = 0; i < bufferLength; i++) {
                const barHeight = (dataArray[i] / 255) * canvas.height;
                ctx.fillRect(x, canvas.height - barHeight, barWidth, barHeight);
                x += barWidth + 2;
            }
        }
        render();
    }

    function updateTimerDisplay() {
        if (voiceState === 'paused' || isPaused) return;
        const modalRecordTimer = document.getElementById('modalRecordTimer');
        const elapsed = Math.max(0, Math.floor((Date.now() - recordStartTime - totalPausedMs) / 1000));
        const mins = Math.floor(elapsed / 60).toString().padStart(2, '0');
        const secs = (elapsed % 60).toString().padStart(2, '0');
        const timerStr = `${mins}:${secs}`;
        if (mainRecordTimer) mainRecordTimer.textContent = timerStr;
        if (modalRecordTimer) modalRecordTimer.textContent = timerStr;
    }

    function updateEditorDisplay() {
        const fullNewText = (finalText ? finalText.trim() : '') + (partialText ? (finalText ? ' ' : '') + partialText : '');
        const fullCombined = accumulatedTextPrefix ? (accumulatedTextPrefix + (fullNewText ? ' ' + fullNewText : '')) : fullNewText;
        const liveSpeechPreview = document.getElementById('liveSpeechPreview');

        if (liveSpeechPreview) {
            liveSpeechPreview.textContent = fullCombined ? `Listening: ${fullCombined}` : '';
        }
    }

    function stopRecordingFlow() {
        if (voiceState !== 'recording' && !isRecording) return;

        const currentSessionToken = activeRecordingSessionToken;
        console.log("Recording stopped");
        setVoiceState('transcribing');

        if (speechRecognizer) {
            try { speechRecognizer.stop(); } catch (e) { }
        }

        clearInterval(recordTimerInterval);

        if (mediaRecorder) {
            mediaRecorder.onstop = async () => {
                if (currentSessionToken !== activeRecordingSessionToken) return;

                if (mediaRecorder && mediaRecorder.stream) {
                    mediaRecorder.stream.getTracks().forEach(track => {
                        try { track.stop(); } catch (e) { }
                    });
                }

                await new Promise(r => setTimeout(r, 60));
                if (currentSessionToken !== activeRecordingSessionToken) return;

                const mimeType = (mediaRecorder && mediaRecorder.mimeType) ? mediaRecorder.mimeType : (recordedMimeType || 'audio/webm');
                const audioBlob = new Blob(audioChunks, { type: mimeType });
                console.log("Audio blob:", audioBlob.size, "bytes, MIME type:", audioBlob.type);

                const liveFallbackText = ((finalText || '') + ' ' + (partialText || '')).trim();

                let originalSpokenText = '';
                let detectedLanguage = getSourceLang();

                if (audioBlob && audioBlob.size > 0) {
                    const formData = new FormData();
                    formData.append('session_id', currentSessionId);
                    formData.append('source_language', getSourceLang());
                    formData.append('audio', audioBlob, `recording.${recordedFileExt}`);
                    if (liveFallbackText) {
                        formData.append('fallback_text', liveFallbackText);
                    }

                    console.log("Sending audio for transcription");

                    try {
                        const res = await fetch('/api/voice/transcribe', {
                            method: 'POST',
                            body: formData
                        });

                        if (currentSessionToken !== activeRecordingSessionToken) return;
                        console.log("Transcription API status:", res.status);

                        if (res.ok) {
                            const data = await res.json();
                            if (currentSessionToken !== activeRecordingSessionToken) return;
                            console.log("Transcription API response:", data);
                            if (data.success && (data.text || data.transcription || data.original_text)) {
                                originalSpokenText = cleanMusicSymbols(data.text || data.transcription || data.original_text);
                                detectedLanguage = data.language || getSourceLang();
                                console.log("Final transcription:", originalSpokenText);
                            } else if (data.error) {
                                if (liveFallbackText) {
                                    originalSpokenText = liveFallbackText;
                                } else {
                                    console.warn("Transcription API returned error:", data.error);
                                    setVoiceState('error', { error: data.error });
                                    return;
                                }
                            }
                        } else {
                            if (liveFallbackText) {
                                originalSpokenText = liveFallbackText;
                            } else {
                                console.error("Transcription API request failed with status:", res.status);
                                setVoiceState('error', { error: 'Unable to transcribe the recording. Please try again.' });
                                return;
                            }
                        }
                    } catch (err) {
                        if (currentSessionToken !== activeRecordingSessionToken) return;
                        if (liveFallbackText) {
                            originalSpokenText = liveFallbackText;
                        } else {
                            console.error('Backend Whisper STT fetch error:', err);
                            setVoiceState('error', { error: 'Unable to transcribe the recording. Please try again.' });
                            return;
                        }
                    }
                } else if (liveFallbackText) {
                    originalSpokenText = liveFallbackText;
                } else {
                    if (currentSessionToken === activeRecordingSessionToken) {
                        setVoiceState('error', { error: 'No speech detected. Please speak and try again.' });
                    }
                    return;
                }

                if (currentSessionToken !== activeRecordingSessionToken) return;

                if (!originalSpokenText || !originalSpokenText.trim()) {
                    setVoiceState('error', { error: 'No speech detected. Please speak and try again.' });
                    return;
                }

                if (accumulatedTextPrefix && originalSpokenText.trim()) {
                    const cleanSpoken = originalSpokenText.trim();
                    if (!cleanSpoken.startsWith(accumulatedTextPrefix)) {
                        originalSpokenText = accumulatedTextPrefix + (accumulatedTextPrefix.endsWith(' ') ? '' : ' ') + cleanSpoken;
                    }
                }

                let translatedTextResult = '';
                let translationErrorText = '';
                const targetLang = getTargetLang();

                if (isTranslationOn && originalSpokenText) {
                    setVoiceState('translating');
                    try {
                        const transRes = await fetch('/api/translate', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({
                                session_id: currentSessionId,
                                text: originalSpokenText,
                                source_language: detectedLanguage || getSourceLang(),
                                target_language: targetLang
                            })
                        });
                        if (currentSessionToken !== activeRecordingSessionToken) return;
                        if (transRes.ok) {
                            const transData = await transRes.json();
                            if (transData.success && (transData.translated_text || transData.translation)) {
                                translatedTextResult = transData.translated_text || transData.translation;
                            } else {
                                translationErrorText = 'Translation failed. Please try again.';
                            }
                        } else {
                            translationErrorText = 'Translation failed. Please try again.';
                        }
                    } catch (e) {
                        if (currentSessionToken !== activeRecordingSessionToken) return;
                        console.warn('Translation error in voice card:', e);
                        translationErrorText = 'Translation failed. Please try again.';
                    }
                }

                if (currentSessionToken !== activeRecordingSessionToken) return;

                setVoiceState('completed', {
                    originalText: originalSpokenText,
                    translatedText: translatedTextResult,
                    translationErrorText: translationErrorText,
                    sourceLang: detectedLanguage,
                    targetLang: targetLang
                });
            };

            try {
                if (mediaRecorder.state !== 'inactive') {
                    mediaRecorder.stop();
                } else {
                    mediaRecorder.onstop();
                }
            } catch (e) {
                console.warn('MediaRecorder stop error:', e);
                if (currentSessionToken === activeRecordingSessionToken) {
                    setVoiceState('error', { error: 'Recording stop error.' });
                }
            }
        } else {
            if (currentSessionToken === activeRecordingSessionToken) {
                setVoiceState('error', { error: 'MediaRecorder initialization error.' });
            }
        }
    }

    function finishMicSuccessState() {
        voiceState = 'completed';
        isRecording = false;

        const modalMicStatus = document.getElementById('modalMicStatus');
        const startVoiceRecordBtn = document.getElementById('startVoiceRecordBtn');
        const modalMicRecordBtn = document.getElementById('modalMicRecordBtn');
        const cancelVoiceBtn = document.getElementById('cancelVoiceBtn');

        if (mainMicStatus) mainMicStatus.textContent = '✓ Complete';
        if (modalMicStatus) modalMicStatus.textContent = '✓ Complete';

        if (cancelVoiceBtn) {
            cancelVoiceBtn.textContent = 'Close';
        }

        if (startVoiceRecordBtn) {
            startVoiceRecordBtn.disabled = false;
            startVoiceRecordBtn.classList.remove('recording', 'processing');
            startVoiceRecordBtn.innerHTML = '<i data-lucide="mic"></i> Record Again';
        }
        if (modalMicRecordBtn) modalMicRecordBtn.classList.remove('recording', 'processing');
        if (mainMicBtn) mainMicBtn.classList.remove('recording', 'processing');
        if (window.lucide) lucide.createIcons();

    }

    function handleTranslatedOutput(translatedText) {
        if (!translatedText) return;
        const previewTranslatedText = document.getElementById('previewTranslatedText');
        const voiceTranslatedTextarea = document.getElementById('voiceTranslatedTextarea');

        if (previewTranslatedText) {
            previewTranslatedText.textContent = translatedText;
        }

        if (translatedTextEditor) {
            translatedTextEditor.value = translatedText;
        }

        if (voiceTranslatedTextarea) {
            voiceTranslatedTextarea.value = translatedText;
        }
    }

    // --- Translation Logic with Debouncing & Request Deduplication ---
    let translationDebounceTimer = null;
    let lastTranslationKey = '';

    function triggerTranslationDebounced() {
        if (translationDebounceTimer) clearTimeout(translationDebounceTimer);
        translationDebounceTimer = setTimeout(() => {
            triggerTranslation();
        }, 600);
    }

    async function triggerTranslation() {
        if (!isTranslationOn) {
            if (translatedTextEditor) translatedTextEditor.value = '';
            const voiceTranslatedTextarea = document.getElementById('voiceTranslatedTextarea');
            if (voiceTranslatedTextarea) voiceTranslatedTextarea.value = '';
            lastTranslationKey = '';
            return;
        }

        const voiceOriginalTextarea = document.getElementById('voiceOriginalTextarea');
        const target = currentTargetInput || TextInsertionService.getActiveInput();
        const text = (voiceOriginalTextarea && voiceOriginalTextarea.value.trim()) ? voiceOriginalTextarea.value.trim() : (target ? (target.value || target.innerText || '').trim() : (originalTextEditor ? originalTextEditor.value.trim() : ''));
        if (!text) {
            if (translatedTextEditor) translatedTextEditor.value = '';
            const vTrans = document.getElementById('voiceTranslatedTextarea');
            if (vTrans) vTrans.value = '';
            lastTranslationKey = '';
            return;
        }

        const srcLang = getSourceLang();
        const tgtLang = getTargetLang();

        // Skip translation API network call if source language matches target language
        if (srcLang !== 'auto' && srcLang === tgtLang) {
            handleTranslatedOutput(text);
            lastTranslationKey = `${srcLang}:${tgtLang}:${text}`;
            return;
        }

        const translationKey = `${srcLang}:${tgtLang}:${text}`;
        if (translationKey === lastTranslationKey) return;
        lastTranslationKey = translationKey;

        try {
            const res = await fetch(`${API_BASE}/api/translate`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    session_id: currentSessionId,
                    text: text,
                    source_language: srcLang,
                    translation_language: tgtLang,
                    target_language: tgtLang
                })
            });

            if (res.ok) {
                const data = await res.json();
                if (data.success && data.translated_text) {
                    handleTranslatedOutput(data.translated_text);
                    return;
                }
            }
        } catch (err) {
            console.warn('Translate error:', err);
        }
    }

    // --- Text Editor Actions (Copy, Clear, Undo, Redo) ---
    function copyToClipboard(text, btnElement = null) {
        if (!text) return;
        navigator.clipboard.writeText(text).then(() => {
            if (btnElement) {
                const origHTML = btnElement.innerHTML;
                btnElement.innerHTML = '<i data-lucide="check"></i> Copied!';
                if (window.lucide) lucide.createIcons();
                setTimeout(() => {
                    btnElement.innerHTML = origHTML;
                    if (window.lucide) lucide.createIcons();
                }, 1800);
            }
        }).catch(err => {
            console.error('Clipboard copy error:', err);
        });
    }

    function clearEditors() {
        const mainSearchInput = document.getElementById('mainSearchInput');
        const previewOriginalText = document.getElementById('previewOriginalText');
        const previewTranslatedText = document.getElementById('previewTranslatedText');

        if (mainSearchInput) mainSearchInput.value = '';
        if (originalTextEditor) originalTextEditor.value = '';
        if (translatedTextEditor) translatedTextEditor.value = '';
        if (previewOriginalText) previewOriginalText.textContent = 'Your text appears here...';
        if (previewTranslatedText) previewTranslatedText.textContent = 'Translation will appear here...';

        finalText = '';
        partialText = '';
        pushHistoryState();
    }

    function pushHistoryState() {
        const mainSearchInput = document.getElementById('mainSearchInput');
        const currentState = {
            orig: mainSearchInput ? mainSearchInput.value : (originalTextEditor ? originalTextEditor.value : ''),
            trans: translatedTextEditor ? translatedTextEditor.value : ''
        };
        if (historyIndex >= 0 && historyIndex < historyStack.length) {
            if (historyStack[historyIndex].orig === currentState.orig && historyStack[historyIndex].trans === currentState.trans) {
                return;
            }
        }
        historyStack = historyStack.slice(0, historyIndex + 1);
        historyStack.push(currentState);
        historyIndex = historyStack.length - 1;
    }

    function undoState() {
        if (historyIndex > 0) {
            historyIndex--;
            const state = historyStack[historyIndex];
            const mainSearchInput = document.getElementById('mainSearchInput');
            if (mainSearchInput) mainSearchInput.value = state.orig;
            if (originalTextEditor) originalTextEditor.value = state.orig;
            if (translatedTextEditor) translatedTextEditor.value = state.trans;
            finalText = state.orig;
        }
    }

    function redoState() {
        if (historyIndex < historyStack.length - 1) {
            historyIndex++;
            const state = historyStack[historyIndex];
            const mainSearchInput = document.getElementById('mainSearchInput');
            if (mainSearchInput) mainSearchInput.value = state.orig;
            if (originalTextEditor) originalTextEditor.value = state.orig;
            if (translatedTextEditor) translatedTextEditor.value = state.trans;
            finalText = state.orig;
        }
    }

    // --- TextInsertionService Architecture ---
    const TextInsertionService = {
        activeInput: null,
        lastSelectionStart: null,
        lastSelectionEnd: null,

        setActiveInput(el) {
            if (!el) return;
            this.activeInput = el;
            if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
                if (el.selectionStart !== null && el.selectionStart !== undefined) {
                    this.lastSelectionStart = el.selectionStart;
                    this.lastSelectionEnd = (el.selectionEnd !== null && el.selectionEnd !== undefined) ? el.selectionEnd : el.selectionStart;
                }
            }
        },

        getActiveInput() {
            if (this.activeInput && document.body.contains(this.activeInput)) {
                return this.activeInput;
            }
            const activeEl = document.activeElement;
            if (activeEl && (activeEl.tagName === 'INPUT' || activeEl.tagName === 'TEXTAREA' || activeEl.isContentEditable)) {
                return activeEl;
            }
            return document.getElementById('mainSearchInput') || document.getElementById('originalTextEditor') || document.getElementById('globalSearchInput');
        },

        handleBackspace(targetEl = null) {
            const inputEl = targetEl || this.getActiveInput();
            if (!inputEl) return;

            if (inputEl.tagName === 'INPUT' || inputEl.tagName === 'TEXTAREA') {
                try {
                    inputEl.disabled = false;
                    inputEl.readOnly = false;
                    inputEl.style.pointerEvents = 'auto';
                    inputEl.focus();
                } catch (e) { }

                const val = inputEl.value || '';
                if (!val) return;

                let start = inputEl.selectionStart;
                let end = inputEl.selectionEnd;

                // Fallback to end of text if selectionStart is null/undefined
                if (start === null || start === undefined) {
                    start = val.length;
                    end = val.length;
                }

                // If cursor is at position 0 with no text selected, backspace cannot delete before index 0
                if (start === 0 && end === 0) {
                    return;
                }

                const deleteStart = (start === end) ? Math.max(0, start - 1) : start;
                const newVal = val.substring(0, deleteStart) + val.substring(end);
                inputEl.value = newVal;
                const newCursorPos = deleteStart;

                try {
                    inputEl.setSelectionRange(newCursorPos, newCursorPos);
                } catch (e) {
                    inputEl.selectionStart = inputEl.selectionEnd = newCursorPos;
                }

                this.setActiveInput(inputEl);
                inputEl.dispatchEvent(new Event('input', { bubbles: true }));
                inputEl.dispatchEvent(new Event('change', { bubbles: true }));
            } else if (inputEl.isContentEditable) {
                inputEl.focus();
                const sel = window.getSelection();
                if (sel && sel.rangeCount > 0 && !sel.isCollapsed) {
                    const range = sel.getRangeAt(0);
                    range.deleteContents();
                } else {
                    const text = inputEl.innerText || inputEl.textContent || '';
                    if (text.length > 0) {
                        inputEl.innerText = text.slice(0, -1);
                    }
                }
                inputEl.dispatchEvent(new Event('input', { bubbles: true }));
            }
        },

        insertText(text, targetEl = null, btnElement = null) {
            if (!text) return;
            const inputEl = targetEl || this.getActiveInput();

            if (!inputEl) {
                alert('Please select a text field first.');
                copyToClipboard(text, btnElement);
                return;
            }

            // Ensure input element is enabled, editable, and unblocked
            try {
                inputEl.disabled = false;
                inputEl.readOnly = false;
                inputEl.style.pointerEvents = 'auto';
            } catch (e) { }

            // 1. Handle HTML Input / Textarea (Cursor position & selection replacement)
            if (inputEl.tagName === 'INPUT' || inputEl.tagName === 'TEXTAREA') {
                try {
                    inputEl.focus();
                    const val = inputEl.value || '';
                    const start = (inputEl.selectionStart !== null && inputEl.selectionStart !== undefined) ? inputEl.selectionStart : val.length;
                    const end = (inputEl.selectionEnd !== null && inputEl.selectionEnd !== undefined) ? inputEl.selectionEnd : val.length;

                    const newVal = val.substring(0, start) + text + val.substring(end);
                    inputEl.value = newVal;
                    const newCursorPos = start + text.length;

                    try {
                        inputEl.setSelectionRange(newCursorPos, newCursorPos);
                    } catch (e) {
                        inputEl.selectionStart = inputEl.selectionEnd = newCursorPos;
                    }

                    inputEl.dispatchEvent(new Event('input', { bubbles: true }));
                    inputEl.dispatchEvent(new Event('change', { bubbles: true }));

                    this.setActiveInput(inputEl);

                    // Ensure focus & cursor placement are preserved after event dispatch
                    setTimeout(() => {
                        try {
                            inputEl.disabled = false;
                            inputEl.readOnly = false;
                            inputEl.focus();
                            inputEl.setSelectionRange(newCursorPos, newCursorPos);
                        } catch (e) { }
                    }, 40);
                } catch (e) {
                    inputEl.value = text;
                    inputEl.focus();
                }
            }
            // 2. Handle ContentEditable Elements
            else if (inputEl.isContentEditable) {
                try {
                    inputEl.focus();
                    const sel = window.getSelection();
                    if (sel.rangeCount > 0) {
                        const range = sel.getRangeAt(0);
                        range.deleteContents();
                        const textNode = document.createTextNode(text);
                        range.insertNode(textNode);
                        range.collapse(false);
                        sel.removeAllRanges();
                        sel.addRange(range);
                    } else {
                        inputEl.innerText += text;
                    }
                    inputEl.dispatchEvent(new Event('input', { bubbles: true }));
                } catch (e) {
                    inputEl.innerText += text;
                }
            } else {
                copyToClipboard(text, btnElement);
            }

            if (btnElement) {
                const origHTML = btnElement.innerHTML;
                btnElement.innerHTML = '<i data-lucide="check"></i> Inserted!';
                if (window.lucide) lucide.createIcons();
                setTimeout(() => {
                    btnElement.innerHTML = origHTML;
                    if (window.lucide) lucide.createIcons();
                }, 1800);
            }
        },

        replaceSelectedText(text, targetEl = null) {
            this.insertText(text, targetEl);
        },

        appendText(text, targetEl = null) {
            const inputEl = targetEl || this.getActiveInput();
            if (!inputEl) return;
            if (inputEl.tagName === 'INPUT' || inputEl.tagName === 'TEXTAREA') {
                const val = inputEl.value || '';
                inputEl.value = val + (val && !val.endsWith(' ') ? ' ' : '') + text;
                inputEl.dispatchEvent(new Event('input', { bubbles: true }));
            } else if (inputEl.isContentEditable) {
                inputEl.innerText += ' ' + text;
            }
        },

        pasteText(text, targetEl = null) {
            this.insertText(text, targetEl);
        }
    };

    // Global Focus & Cursor Selection Listeners to track active target input
    document.addEventListener('focusin', (e) => {
        const target = e.target;
        if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)) {
            TextInsertionService.setActiveInput(target);
        }
    });

    document.addEventListener('selectionchange', () => {
        const activeEl = document.activeElement;
        if (activeEl && (activeEl.tagName === 'INPUT' || activeEl.tagName === 'TEXTAREA')) {
            TextInsertionService.setActiveInput(activeEl);
        }
    });

    document.addEventListener('keyup', (e) => {
        const activeEl = document.activeElement;
        if (activeEl && (activeEl.tagName === 'INPUT' || activeEl.tagName === 'TEXTAREA')) {
            TextInsertionService.setActiveInput(activeEl);
        }
    });

    document.addEventListener('mouseup', (e) => {
        const activeEl = document.activeElement;
        if (activeEl && (activeEl.tagName === 'INPUT' || activeEl.tagName === 'TEXTAREA')) {
            TextInsertionService.setActiveInput(activeEl);
        }
    });



    // Global Focus Listener to track active target input
    document.addEventListener('focusin', (e) => {
        const target = e.target;
        if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)) {
            TextInsertionService.setActiveInput(target);
        }
    });

    // Inline Mic Button Click Handlers
    document.addEventListener('click', (e) => {
        const micBtn = e.target.closest('.inline-mic-btn');
        if (micBtn) {
            if (micBtn.id === 'mainSearchMicBtn' || micBtn.id === 'mainMicBtn') {
                return; // Handled explicitly by mainMicBtn listener / openVoiceModal
            }
            e.preventDefault();
            e.stopPropagation();
            const targetId = micBtn.getAttribute('data-target');
            let targetEl = targetId ? document.getElementById(targetId) : null;
            if (!targetEl) {
                const container = micBtn.closest('.voice-input-group, .search-input-wrapper, .message-input-wrapper, .vk-card-body');
                if (container) {
                    targetEl = container.querySelector('input, textarea, [contenteditable="true"]');
                }
            }
            if (targetEl) {
                TextInsertionService.setActiveInput(targetEl);
                targetEl.focus();
            }
            toggleRecordingForTarget(targetEl, micBtn);
        }
    });

    let currentTargetInput = null;
    let currentInlineMicBtn = null;

    async function toggleRecordingForTarget(targetEl, micBtn) {
        currentTargetInput = targetEl || TextInsertionService.getActiveInput();
        currentInlineMicBtn = micBtn;
        openVoiceModal();
    }

    // --- Voice History Sidebar ---
    async function loadVoiceHistoryList() {
        try {
            const res = await fetch(`${API_BASE}/conversations`);
            if (!res.ok) return;
            const data = await res.json();
            renderVoiceHistoryList(data.sessions || []);
        } catch (err) {
            console.error('Failed to load history:', err);
        }
    }

    function renderVoiceHistoryList(sessions) {
        if (!conversationList) return;
        conversationList.innerHTML = '';
        if (!sessions || sessions.length === 0) {
            conversationList.innerHTML = '<div style="padding: 10px 14px; color: var(--text-muted); font-size: 0.82rem;">No voice history yet</div>';
            return;
        }

        sessions.forEach(session => {
            const item = document.createElement('div');
            item.className = `session-item ${session.session_id === currentSessionId ? 'active' : ''}`;

            const title = document.createElement('span');
            title.className = 'session-title';
            title.textContent = session.title || session.last_message || 'Voice Transcript';
            title.addEventListener('click', () => loadHistorySession(session.session_id));

            const delBtn = document.createElement('button');
            delBtn.className = 'btn-delete-session';
            delBtn.setAttribute('title', 'Delete Record');
            delBtn.innerHTML = '<i data-lucide="trash-2"></i>';
            delBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                deleteHistorySession(session.session_id);
            });

            item.appendChild(title);
            item.appendChild(delBtn);
            conversationList.appendChild(item);
        });

        if (window.lucide) lucide.createIcons();
    }

    async function loadHistorySession(sessionId) {
        try {
            const res = await fetch(`${API_BASE}/conversation/${sessionId}`);
            if (!res.ok) return;
            const data = await res.json();

            if (data.messages && data.messages.length > 0) {
                const userMsg = data.messages.find(m => m.role === 'user');
                const assistantMsg = data.messages.find(m => m.role === 'assistant');

                if (userMsg) {
                    if (originalTextEditor) originalTextEditor.value = userMsg.content;
                    if (mainSearchInput) mainSearchInput.value = userMsg.content;
                    finalText = userMsg.content;
                }
                if (assistantMsg && assistantMsg.content !== userMsg?.content) {
                    if (translatedTextEditor) translatedTextEditor.value = assistantMsg.content;
                    isTranslationOn = true;
                    if (translationToggleBtn) translationToggleBtn.classList.add('active');
                    if (toggleText) toggleText.textContent = 'ON';
                    if (translationCard) translationCard.style.display = 'flex';
                }
                pushHistoryState();
            }
            currentSessionId = sessionId;
            localStorage.setItem('ai_agent_session', currentSessionId);
            loadVoiceHistoryList();
            if (window.innerWidth <= 768 && sidebar) sidebar.classList.remove('open');
        } catch (err) {
            console.error('Error loading session history:', err);
        }
    }

    async function deleteHistorySession(sessionId) {
        if (!confirm('Are you sure you want to delete this record?')) return;
        try {
            await fetch(`${API_BASE}/conversation/${sessionId}`, { method: 'DELETE' });
            if (sessionId === currentSessionId) {
                startNewSession();
            }
            loadVoiceHistoryList();
        } catch (err) {
            console.error('Failed to delete record:', err);
        }
    }

    // --- Text-to-Speech Output ---
    let isSpeaking = false;
    let activeAudioBtn = null;

    function stopSpeaking() {
        if (currentAudioElement) {
            try { currentAudioElement.pause(); currentAudioElement.currentTime = 0; } catch (e) { }
            currentAudioElement = null;
        }
        if ('speechSynthesis' in window) {
            try { window.speechSynthesis.cancel(); } catch (e) { }
        }
        isSpeaking = false;
        if (activeAudioBtn) {
            activeAudioBtn.classList.remove('playing');
            activeAudioBtn.innerHTML = '<i data-lucide="volume-2"></i> Speak Audio';
            activeAudioBtn = null;
        }
        if (headerStopAudioBtn) headerStopAudioBtn.style.display = 'none';
        if (window.lucide) lucide.createIcons();
    }

    // --- Helper Utilities ---

    function audioBufferToWav(buffer) {
        const numChannels = 1;
        const sampleRate = buffer.sampleRate;
        const format = 1;
        const bitDepth = 16;
        const samples = buffer.getChannelData(0);
        const dataLength = samples.length * 2;
        const bufferLength = 44 + dataLength;
        const arrayBuffer = new ArrayBuffer(bufferLength);
        const view = new DataView(arrayBuffer);

        function writeString(v, offset, str) {
            for (let i = 0; i < str.length; i++) {
                v.setUint8(offset + i, str.charCodeAt(i));
            }
        }

        writeString(view, 0, 'RIFF');
        view.setUint32(4, 36 + dataLength, true);
        writeString(view, 8, 'WAVE');
        writeString(view, 12, 'fmt ');
        view.setUint32(16, 16, true);
        view.setUint16(20, format, true);
        view.setUint16(22, numChannels, true);
        view.setUint32(24, sampleRate, true);
        view.setUint32(28, sampleRate * numChannels * 2, true);
        view.setUint16(32, numChannels * 2, true);
        view.setUint16(34, bitDepth, true);
        writeString(view, 36, 'data');
        view.setUint32(40, dataLength, true);

        let offset = 44;
        for (let i = 0; i < samples.length; i++, offset += 2) {
            const s = Math.max(-1, Math.min(1, samples[i]));
            view.setInt16(offset, s < 0 ? s * 0x8000 : s * 0x7FFF, true);
        }

        return new Blob([view], { type: 'audio/wav' });
    }

    function getLangTag(lang) {
        const map = {
            'te': 'te-IN', 'hi': 'hi-IN', 'ta': 'ta-IN', 'kn': 'kn-IN',
            'ml': 'ml-IN', 'mr': 'mr-IN', 'bn': 'bn-IN', 'gu': 'gu-IN',
            'pa': 'pa-IN', 'ur': 'ur-PK', 'en': 'en-US', 'es': 'es-ES',
            'fr': 'fr-FR', 'de': 'de-DE', 'it': 'it-IT', 'pt': 'pt-PT',
            'ar': 'ar-SA', 'ja': 'ja-JP', 'ko': 'ko-KR', 'zh': 'zh-CN', 'ru': 'ru-RU'
        };
        return map[lang] || 'en-US';
    }
});
