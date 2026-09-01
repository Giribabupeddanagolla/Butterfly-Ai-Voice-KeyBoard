/**
 * Butterfly AI - AI Voice Keyboard Client Script
 */

// Global function reference for state machine
var setVoiceState = function(state, extraData = {}) {
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

    // Realtime Speech State
    const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
    let speechRecognizer = null;
    let finalText = '';
    let partialText = '';
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
            const inputEl = TextInsertionService.getActiveInput();
            if (!inputEl) return;
            if (inputEl.tagName === 'INPUT' || inputEl.tagName === 'TEXTAREA') {
                inputEl.focus();
                const start = inputEl.selectionStart || 0;
                const end = inputEl.selectionEnd || 0;
                const val = inputEl.value || '';
                if (start > 0 || start !== end) {
                    const deleteStart = (start === end) ? start - 1 : start;
                    inputEl.value = val.substring(0, deleteStart) + val.substring(end);
                    inputEl.selectionStart = inputEl.selectionEnd = deleteStart;
                    inputEl.dispatchEvent(new Event('input', { bubbles: true }));
                }
            } else if (inputEl.isContentEditable) {
                inputEl.innerText = inputEl.innerText.slice(0, -1);
                inputEl.dispatchEvent(new Event('input', { bubbles: true }));
            }
        }
    };

    // --- Core Initialization ---
    initApp();

    function initApp() {
        setupEventListeners();
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

        // Settings Modal
        if (settingsBtn) {
            settingsBtn.addEventListener('click', () => {
                if (settingsModal) settingsModal.classList.add('active');
                checkOpenAIStatus();
            });
        }
        if (closeSettingsBtn) closeSettingsBtn.addEventListener('click', () => {
            if (settingsModal) settingsModal.classList.remove('active');
        });
        if (saveSettingsBtn) {
            saveSettingsBtn.addEventListener('click', async () => {
                if (apiKeyInput && apiKeyInput.value.trim()) {
                    const keyVal = apiKeyInput.value.trim();
                    try {
                        const res = await fetch(`${API_BASE}/api/settings/key`, {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({ api_key: keyVal })
                        });
                        if (res.ok) {
                            alert('OpenAI API Key updated successfully!');
                            checkOpenAIStatus();
                        }
                    } catch (e) {
                        console.warn('Failed to update API key:', e);
                    }
                }
                if (settingsModal) settingsModal.classList.remove('active');
            });
        }

        // Global Shortcut: Ctrl + Shift + Space
        document.addEventListener('keydown', (e) => {
            if (e.ctrlKey && e.shiftKey && e.code === 'Space') {
                e.preventDefault();
                toggleRecording();
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

        function showVirtualKeyboard() {}
        function hideVirtualKeyboard() {}

        // --- AI Voice Keyboard UI State Machine ---
        function setVoiceState(state, extraData = {}) {
            voiceState = state; // 'idle' | 'recording' | 'processing' | 'completed' | 'error'
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
            const voiceTranscriptionTextarea = document.getElementById('voiceTranscriptionTextarea');

            const cancelVoiceBtn = document.getElementById('cancelVoiceBtn');
            const startVoiceRecordBtn = document.getElementById('startVoiceRecordBtn');
            const startBtnIcon = document.getElementById('startBtnIcon');
            const startBtnText = document.getElementById('startBtnText');
            const rerecordVoiceBtn = document.getElementById('rerecordVoiceBtn');
            const insertTextVoiceBtn = document.getElementById('insertTextVoiceBtn');

            const selectedLangCode = getSourceLang();
            const langDisplayName = selectedLangCode === 'auto' ? 'Auto Detect' : (getLangTag(selectedLangCode) || selectedLangCode.toUpperCase());

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
                if (translateToGroup) translateToGroup.style.display = 'flex';
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

                if (cancelVoiceBtn) cancelVoiceBtn.style.display = 'inline-flex';
                if (startVoiceRecordBtn) {
                    startVoiceRecordBtn.style.display = 'inline-flex';
                    startVoiceRecordBtn.disabled = false;
                    startVoiceRecordBtn.className = 'btn-primary btn-record-start';
                    if (startBtnIcon) startBtnIcon.setAttribute('data-lucide', 'mic');
                    if (startBtnText) startBtnText.textContent = 'Start Recording';
                }
                if (rerecordVoiceBtn) rerecordVoiceBtn.style.display = 'none';
                if (insertTextVoiceBtn) insertTextVoiceBtn.style.display = 'none';
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
                }
                if (modalMicIcon) modalMicIcon.setAttribute('data-lucide', 'square');
                if (modalMicStatus) modalMicStatus.textContent = 'Listening... Speak naturally';
                if (modalWaveformCanvas) modalWaveformCanvas.style.display = 'block';
                if (modalRecordTimer) modalRecordTimer.style.display = 'block';
                if (liveSpeechPreview) liveSpeechPreview.style.display = 'block';
                if (transcriptionResultContainer) transcriptionResultContainer.style.display = 'none';

                if (cancelVoiceBtn) cancelVoiceBtn.style.display = 'inline-flex';
                if (startVoiceRecordBtn) {
                    startVoiceRecordBtn.style.display = 'inline-flex';
                    startVoiceRecordBtn.disabled = false;
                    startVoiceRecordBtn.className = 'btn-primary btn-record-start recording';
                    if (startBtnIcon) startBtnIcon.setAttribute('data-lucide', 'square');
                    if (startBtnText) startBtnText.textContent = 'Stop Recording';
                }
                if (rerecordVoiceBtn) rerecordVoiceBtn.style.display = 'none';
                if (insertTextVoiceBtn) insertTextVoiceBtn.style.display = 'none';
            }
            else if (state === 'processing') {
                isRecording = false;
                if (modalHeaderBadgeIcon) modalHeaderBadgeIcon.textContent = '⏳';
                if (modalCardTitle) modalCardTitle.textContent = 'AI Voice Keyboard';
                if (voiceLangIndicator) voiceLangIndicator.textContent = extraData.language || langDisplayName;
                if (voiceStatusBadge) {
                    voiceStatusBadge.textContent = 'Processing...';
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
                if (modalMicStatus) modalMicStatus.textContent = 'Converting your speech to text...';
                if (modalWaveformCanvas) modalWaveformCanvas.style.display = 'none';
                if (modalRecordTimer) modalRecordTimer.style.display = 'none';
                if (transcriptionResultContainer) transcriptionResultContainer.style.display = 'none';

                if (cancelVoiceBtn) cancelVoiceBtn.style.display = 'none';
                if (startVoiceRecordBtn) {
                    startVoiceRecordBtn.style.display = 'inline-flex';
                    startVoiceRecordBtn.disabled = true;
                    startVoiceRecordBtn.className = 'btn-primary btn-record-start processing';
                    if (startBtnIcon) startBtnIcon.setAttribute('data-lucide', 'loader');
                    if (startBtnText) startBtnText.textContent = 'Processing...';
                }
                if (rerecordVoiceBtn) rerecordVoiceBtn.style.display = 'none';
                if (insertTextVoiceBtn) insertTextVoiceBtn.style.display = 'none';
            }
            else if (state === 'completed') {
                isRecording = false;
                if (modalHeaderBadgeIcon) modalHeaderBadgeIcon.textContent = '✓';
                if (modalCardTitle) modalCardTitle.textContent = 'Transcription Complete';
                if (voiceLangIndicator) voiceLangIndicator.textContent = extraData.language || 'Detected';
                if (voiceStatusBadge) {
                    voiceStatusBadge.textContent = 'Transcription complete';
                    voiceStatusBadge.className = 'voice-status-badge completed';
                }
                if (voiceLangSelectRow) voiceLangSelectRow.style.display = 'none';
                if (voiceCenterVisual) voiceCenterVisual.style.display = 'none';
                if (transcriptionResultContainer) {
                    transcriptionResultContainer.style.display = 'flex';
                    if (voiceTranscriptionTextarea) {
                        voiceTranscriptionTextarea.value = extraData.text || '';
                        voiceTranscriptionTextarea.focus();
                    }
                }

                if (cancelVoiceBtn) cancelVoiceBtn.style.setProperty('display', 'none', 'important');
                if (rerecordVoiceBtn) rerecordVoiceBtn.style.setProperty('display', 'none', 'important');
                if (startVoiceRecordBtn) {
                    startVoiceRecordBtn.style.setProperty('display', 'inline-flex', 'important');
                    startVoiceRecordBtn.disabled = false;
                    startVoiceRecordBtn.className = 'btn-secondary btn-record-start';
                    if (startBtnIcon) startBtnIcon.setAttribute('data-lucide', 'mic');
                    if (startBtnText) startBtnText.textContent = 'Start Recording';
                }
                if (insertTextVoiceBtn) {
                    insertTextVoiceBtn.style.setProperty('display', 'inline-flex', 'important');
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
                    modalMicRecordBtn.className = 'voice-record-circle';
                    modalMicRecordBtn.disabled = false;
                }
                if (modalMicIcon) modalMicIcon.setAttribute('data-lucide', 'alert-circle');
                if (modalMicStatus) modalMicStatus.textContent = extraData.error || 'Unable to transcribe audio. Please try again.';
                if (modalWaveformCanvas) modalWaveformCanvas.style.display = 'none';
                if (modalRecordTimer) modalRecordTimer.style.display = 'none';
                if (liveSpeechPreview) liveSpeechPreview.style.display = 'none';
                if (transcriptionResultContainer) transcriptionResultContainer.style.display = 'none';

                if (cancelVoiceBtn) cancelVoiceBtn.style.display = 'inline-flex';
                if (startVoiceRecordBtn) {
                    startVoiceRecordBtn.style.display = 'inline-flex';
                    startVoiceRecordBtn.disabled = false;
                    startVoiceRecordBtn.className = 'btn-primary btn-record-start';
                    if (startBtnIcon) startBtnIcon.setAttribute('data-lucide', 'refresh-cw');
                    if (startBtnText) startBtnText.textContent = 'Try Again';
                }
                if (rerecordVoiceBtn) rerecordVoiceBtn.style.display = 'none';
                if (insertTextVoiceBtn) insertTextVoiceBtn.style.display = 'none';
            }

            if (window.lucide) lucide.createIcons();
        }

        function resetToIdleState() {
            clearInterval(recordTimerInterval);
            cancelAnimationFrame(animFrameId);

            if (speechRecognizer) {
                try { speechRecognizer.stop(); } catch (e) {}
            }
            if (mediaRecorder) {
                try {
                    if (mediaRecorder.stream) {
                        mediaRecorder.stream.getTracks().forEach(track => track.stop());
                    }
                    if (mediaRecorder.state !== 'inactive') {
                        mediaRecorder.stop();
                    }
                } catch (e) {}
            }
            if (audioContext && audioContext.state !== 'closed') {
                try { audioContext.close(); } catch (e) {}
            }

            finalText = '';
            partialText = '';
            audioChunks = [];

            setVoiceState('idle');
        }

        let lastActiveInput = null;

        function openVoiceModal(targetEl = null) {
            lastActiveInput = targetEl || currentTargetInput || TextInsertionService.getActiveInput();
            hideVirtualKeyboard();
            resetToIdleState();
            if (voiceModal) voiceModal.classList.add('active');
        }

        function closeVoiceModal() {
            resetToIdleState();
            if (voiceModal) voiceModal.classList.remove('active');
        }

        if (mainMicBtn) {
            mainMicBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                openVoiceModal();
            });
        }

        if (closeVoiceModalBtn) closeVoiceModalBtn.addEventListener('click', closeVoiceModal);
        if (cancelVoiceBtn) cancelVoiceBtn.addEventListener('click', closeVoiceModal);

        if (startVoiceRecordBtn) {
            startVoiceRecordBtn.addEventListener('click', (e) => {
                e.preventDefault();
                toggleRecording();
            });
        }
        if (modalMicRecordBtn) {
            modalMicRecordBtn.addEventListener('click', (e) => {
                e.preventDefault();
                toggleRecording();
            });
        }
        if (rerecordVoiceBtn) {
            rerecordVoiceBtn.addEventListener('click', (e) => {
                e.preventDefault();
                resetToIdleState();
            });
        }
        if (insertTextVoiceBtn) {
            insertTextVoiceBtn.addEventListener('click', (e) => {
                e.preventDefault();
                handleInsertTextClick();
            });
        }

        function handleInsertTextClick() {
            const voiceTranscriptionTextarea = document.getElementById('voiceTranscriptionTextarea');
            const textToInsert = voiceTranscriptionTextarea ? voiceTranscriptionTextarea.value.trim() : '';
            if (!textToInsert) {
                alert('No transcribed text to insert.');
                return;
            }
            const targetEl = lastActiveInput || currentTargetInput || TextInsertionService.getActiveInput();
            console.log("Inserting text into target element:", targetEl ? (targetEl.id || targetEl.tagName) : "None");
            TextInsertionService.insertText(textToInsert, targetEl, insertTextVoiceBtn);
            
            // Auto-save voice record entry for history CRUD
            fetch(`${API_BASE}/api/records`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    text: textToInsert,
                    source_language: getSourceLang(),
                    target_language: getTargetLang(),
                    translated_text: textToInsert
                })
            }).catch(e => console.warn('Record save error:', e));

            setTimeout(() => {
                closeVoiceModal();
            }, 300);
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
                if (isTranslationOn) triggerTranslation();
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

        const closeSearchResultsBtn = document.getElementById('closeSearchResultsBtn');
        if (closeSearchResultsBtn) {
            closeSearchResultsBtn.addEventListener('click', () => {
                const card = document.getElementById('searchResultsCard');
                if (card) card.style.display = 'none';
            });
        }

        // Action Buttons
        if (copyOriginalBtn) copyOriginalBtn.addEventListener('click', () => copyToClipboard(mainSearchInput ? mainSearchInput.value : (originalTextEditor ? originalTextEditor.value : ''), copyOriginalBtn));
        if (copyTranslationBtn) copyTranslationBtn.addEventListener('click', () => copyToClipboard(translatedTextEditor ? translatedTextEditor.value : '', copyTranslationBtn));

        if (speakOriginalBtn) speakOriginalBtn.addEventListener('click', () => playTextToSpeech(mainSearchInput ? mainSearchInput.value : (originalTextEditor ? originalTextEditor.value : ''), getSourceLang(), speakOriginalBtn));
        if (speakTranslationBtn) speakTranslationBtn.addEventListener('click', () => playTextToSpeech(translatedTextEditor ? translatedTextEditor.value : '', getTargetLang(), speakTranslationBtn));

        if (clearBtn) clearBtn.addEventListener('click', clearEditors);
        if (undoBtn) undoBtn.addEventListener('click', undoState);
        if (redoBtn) redoBtn.addEventListener('click', redoState);
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

    window.handleStartRecordClick = function(e) {
        if (e) {
            e.preventDefault();
            e.stopPropagation();
        }
        toggleRecording();
    };

    let lastToggleTime = 0;

    function toggleRecording() {
        const now = Date.now();
        if (now - lastToggleTime < 500) {
            return; // Ignore duplicate click events within 500ms
        }
        lastToggleTime = now;

        if (voiceState === 'recording' || isRecording) {
            stopRecordingFlow();
        } else if (voiceState === 'completed') {
            resetToIdleState();
        } else if (voiceState === 'idle' || voiceState === 'error') {
            startRecordingFlow();
        }
    }

    async function startRecordingFlow() {
        if (voiceState === 'recording' || voiceState === 'processing') return;

        partialText = '';
        finalText = '';

        try {
            console.log("Recording started");
            // 1. Request Microphone Access
            const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
            
            // 2. Initialize MediaRecorder & Audio Chunks
            audioChunks = [];

            let selectedMimeType = 'audio/webm;codecs=opus';
            if (typeof MediaRecorder !== 'undefined' && typeof MediaRecorder.isTypeSupported === 'function') {
                if (!MediaRecorder.isTypeSupported(selectedMimeType)) {
                    if (MediaRecorder.isTypeSupported('audio/webm')) {
                        selectedMimeType = 'audio/webm';
                    } else if (MediaRecorder.isTypeSupported('audio/mp4')) {
                        selectedMimeType = 'audio/mp4';
                    } else {
                        selectedMimeType = '';
                    }
                }
            } else {
                selectedMimeType = '';
            }

            mediaRecorder = selectedMimeType ? new MediaRecorder(stream, { mimeType: selectedMimeType }) : new MediaRecorder(stream);
            console.log("MediaRecorder initialized with MIME type:", mediaRecorder.mimeType || "default");

            mediaRecorder.ondataavailable = (event) => {
                if (event.data && event.data.size > 0) {
                    audioChunks.push(event.data);
                }
            };

            // 3. Transition to 'recording' state
            setVoiceState('recording');

            // 4. Start Audio Visualizer
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

            // 5. Start Recording Timer (00:00 -> 00:01 ...)
            recordStartTime = Date.now();
            updateTimerDisplay();
            clearInterval(recordTimerInterval);
            recordTimerInterval = setInterval(updateTimerDisplay, 1000);

            // 6. Start WebSpeech STT stream for live speech preview if supported
            if (SpeechRecognition) {
                try {
                    speechRecognizer = new SpeechRecognition();
                    speechRecognizer.continuous = true;
                    speechRecognizer.interimResults = true;
                    speechRecognizer.lang = getLangTag(getSourceLang() === 'auto' ? 'te' : getSourceLang());

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
                    console.warn('SpeechRecognition init error:', e);
                }
            }

            // 7. Start MediaRecorder with 100ms timeslice to collect chunks continuously
            mediaRecorder.start(100);

        } catch (err) {
            console.warn('Microphone access denied or error:', err);
            setVoiceState('error', { error: 'Microphone permission denied or unavailable. Please allow access and try again.' });
        }
    }

    function drawWaveform() {
        const canvas = document.getElementById('modalWaveformCanvas');
        if (!canvas || !analyser) return;
        const ctx = canvas.getContext('2d');
        const bufferLength = analyser.frequencyBinCount;
        const dataArray = new Uint8Array(bufferLength);

        function render() {
            if (!isRecording) return;
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
        const modalRecordTimer = document.getElementById('modalRecordTimer');
        const elapsed = Math.floor((Date.now() - recordStartTime) / 1000);
        const mins = Math.floor(elapsed / 60).toString().padStart(2, '0');
        const secs = (elapsed % 60).toString().padStart(2, '0');
        const timerStr = `${mins}:${secs}`;
        if (mainRecordTimer) mainRecordTimer.textContent = timerStr;
        if (modalRecordTimer) modalRecordTimer.textContent = timerStr;
    }

    function updateEditorDisplay() {
        const fullText = (finalText ? finalText.trim() : '') + (partialText ? (finalText ? ' ' : '') + partialText : '');
        const liveSpeechPreview = document.getElementById('liveSpeechPreview');

        if (liveSpeechPreview) {
            liveSpeechPreview.textContent = fullText ? `Listening: ${fullText}` : '';
        }
    }

    function stopRecordingFlow() {
        if (voiceState !== 'recording' && !isRecording) return;

        console.log("Recording stopped");
        // 1. Transition State to 'processing'
        setVoiceState('processing');

        // 2. Stop Realtime WebSpeech Recognizer
        if (speechRecognizer) {
            try { speechRecognizer.stop(); } catch (e) {}
        }

        clearInterval(recordTimerInterval);

        // 3. Attach onstop callback to MediaRecorder before stopping
        if (mediaRecorder) {
            mediaRecorder.onstop = async () => {
                // Release microphone stream tracks
                if (mediaRecorder.stream) {
                    mediaRecorder.stream.getTracks().forEach(track => track.stop());
                }

                // Small delay to ensure all final audio chunks are processed
                await new Promise(r => setTimeout(r, 60));

                const mimeType = mediaRecorder.mimeType || 'audio/webm';
                const audioBlob = new Blob(audioChunks, { type: mimeType });
                console.log("Audio blob:", audioBlob.size, "bytes, MIME type:", audioBlob.type);

                if (!audioBlob || audioBlob.size === 0) {
                    console.error("Audio recording failed: audioBlob size is 0");
                    setVoiceState('error', { error: 'No audio was recorded. Please try speaking again.' });
                    return;
                }

                const sourceLang = getSourceLang();
                let transcribedText = '';
                let detectedLanguage = sourceLang;

                const formData = new FormData();
                formData.append('session_id', currentSessionId);
                formData.append('source_language', sourceLang);
                formData.append('audio', audioBlob, 'recording.webm');

                console.log("Sending audio for transcription");

                try {
                    const res = await fetch(`${API_BASE}/api/voice/transcribe`, {
                        method: 'POST',
                        body: formData
                    });

                    console.log("Transcription API status:", res.status);

                    if (res.ok) {
                        const data = await res.json();
                        console.log("Transcription API response:", data);
                        if (data.success && (data.text || data.transcription || data.original_text)) {
                            transcribedText = data.text || data.transcription || data.original_text;
                            detectedLanguage = data.language || sourceLang;
                            console.log("Final transcription:", transcribedText);
                        } else if (data.error) {
                            console.warn("Transcription API returned error:", data.error);
                            setVoiceState('error', { error: data.error });
                            return;
                        }
                    } else {
                        console.error("Transcription API request failed with status:", res.status);
                        setVoiceState('error', { error: 'Unable to transcribe audio. Please try again.' });
                        return;
                    }
                } catch (err) {
                    console.error('Backend Whisper STT fetch error:', err);
                    setVoiceState('error', { error: 'Unable to transcribe audio. Connection issue.' });
                    return;
                }

                if (!transcribedText || !transcribedText.trim()) {
                    console.warn("No transcription text returned from backend");
                    setVoiceState('error', { error: 'No speech detected. Please speak again.' });
                    return;
                }

                // If Translation toggle is ON, perform translation into target language
                if (isTranslationOn && transcribedText) {
                    const targetLang = getTargetLang();
                    try {
                        const transRes = await fetch(`${API_BASE}/api/translate`, {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({
                                session_id: currentSessionId,
                                text: transcribedText,
                                source_language: detectedLanguage || sourceLang,
                                target_language: targetLang
                            })
                        });
                        if (transRes.ok) {
                            const transData = await transRes.json();
                            if (transData.success && transData.translated_text) {
                                transcribedText = transData.translated_text;
                                detectedLanguage = `${detectedLanguage || 'Auto'} ➔ ${targetLang.toUpperCase()}`;
                            }
                        }
                    } catch (e) {
                        console.warn('Translation error in voice card:', e);
                    }
                }

                // Transition to 'completed' State displaying text
                setVoiceState('completed', {
                    text: transcribedText,
                    language: detectedLanguage
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
                setVoiceState('error', { error: 'Recording stop error.' });
            }
        } else {
            setVoiceState('error', { error: 'MediaRecorder initialization error.' });
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

        // Save Record via CRUD API (CREATE)
        const mainSearchInput = document.getElementById('mainSearchInput');
        const currentText = mainSearchInput ? mainSearchInput.value.trim() : '';
        if (currentText) {
            fetch(`${API_BASE}/api/records`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    text: currentText,
                    source_language: getSourceLang(),
                    target_language: getTargetLang(),
                    translated_text: currentText
                })
            }).catch(e => console.warn('Record auto-save failed:', e));
        }
    }

    function handleTranslatedOutput(translatedText) {
        if (!translatedText) return;
        const mainSearchInput = document.getElementById('mainSearchInput');
        const previewTranslatedText = document.getElementById('previewTranslatedText');

        if (previewTranslatedText) {
            previewTranslatedText.textContent = translatedText;
        }

        if (translatedTextEditor) {
            translatedTextEditor.value = translatedText;
        }

        if (mainSearchInput && isTranslationOn) {
            mainSearchInput.value = translatedText;
            mainSearchInput.dispatchEvent(new Event('input', { bubbles: true }));
        }
    }

    // --- Translation Logic ---
    async function triggerTranslation() {
        const target = currentTargetInput || TextInsertionService.getActiveInput();
        const text = target ? (target.value || target.innerText || '').trim() : (originalTextEditor ? originalTextEditor.value.trim() : '');
        if (!text || !isTranslationOn) {
            if (!isTranslationOn && translatedTextEditor) translatedTextEditor.value = '';
            return;
        }

        try {
            const res = await fetch(`${API_BASE}/api/translate`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    session_id: currentSessionId,
                    text: text,
                    source_language: getSourceLang(),
                    translation_language: getTargetLang(),
                    target_language: getTargetLang()
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
        lastSelectionStart: 0,
        lastSelectionEnd: 0,

        setActiveInput(el) {
            if (!el) return;
            this.activeInput = el;
            if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
                this.lastSelectionStart = el.selectionStart || 0;
                this.lastSelectionEnd = el.selectionEnd || 0;
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

        insertText(text, targetEl = null, btnElement = null) {
            if (!text) return;
            const inputEl = targetEl || this.getActiveInput();

            if (!inputEl) {
                alert('Please select a text field first.');
                copyToClipboard(text, btnElement);
                return;
            }

            // 1. Handle HTML Input / Textarea (Cursor position & selection replacement)
            if (inputEl.tagName === 'INPUT' || inputEl.tagName === 'TEXTAREA') {
                try {
                    inputEl.focus();
                    const start = (inputEl.selectionStart !== null && inputEl.selectionStart !== undefined) ? inputEl.selectionStart : inputEl.value.length;
                    const end = (inputEl.selectionEnd !== null && inputEl.selectionEnd !== undefined) ? inputEl.selectionEnd : inputEl.value.length;
                    const val = inputEl.value || '';
                    
                    inputEl.value = val.substring(0, start) + text + val.substring(end);
                    const newCursorPos = start + text.length;
                    inputEl.selectionStart = inputEl.selectionEnd = newCursorPos;
                    inputEl.dispatchEvent(new Event('input', { bubbles: true }));
                    inputEl.dispatchEvent(new Event('change', { bubbles: true }));
                } catch (e) {
                    inputEl.value = text;
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



    // --- Search Execution ---
    async function performSearch(queryText = null) {
        const mainSearchInput = document.getElementById('mainSearchInput');
        const globalSearchInput = document.getElementById('globalSearchInput');
        const searchCard = document.getElementById('searchResultsCard');
        const searchContainer = document.getElementById('searchResultsContainer');
        const queryTitle = document.getElementById('searchQueryTitle');

        const query = (queryText || (mainSearchInput ? mainSearchInput.value : '') || (globalSearchInput ? globalSearchInput.value : '')).trim();
        if (!query) {
            alert('Please enter or speak a query to search.');
            return;
        }

        if (queryTitle) queryTitle.textContent = `"${query}"`;
        if (searchCard) searchCard.style.display = 'block';
        if (searchContainer) {
            searchContainer.innerHTML = '<div style="text-align: center; padding: 20px; color: var(--text-muted);"><i data-lucide="loader-2" class="spin"></i> Searching...</div>';
            if (window.lucide) lucide.createIcons();
        }

        try {
            const res = await fetch(`${API_BASE}/api/search?q=${encodeURIComponent(query)}`);
            if (res.ok) {
                const data = await res.json();
                if (data.results && data.results.length > 0) {
                    searchContainer.innerHTML = data.results.map(item => `
                        <div class="search-result-item">
                            <a href="${item.url}" target="_blank" class="search-result-title">${item.title}</a>
                            <div class="search-result-snippet">${item.snippet}</div>
                        </div>
                    `).join('');
                    searchCard.scrollIntoView({ behavior: 'smooth' });
                    return;
                }
            }
        } catch (err) {
            console.warn('Search API call error:', err);
        }

        if (searchContainer) {
            searchContainer.innerHTML = `
                <div class="search-result-item">
                    <a href="https://www.google.com/search?q=${encodeURIComponent(query)}" target="_blank" class="search-result-title">Search Google for "${query}"</a>
                    <div class="search-result-snippet">Click to view web search results for "${query}".</div>
                </div>
            `;
            searchCard.scrollIntoView({ behavior: 'smooth' });
        }
    }

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
            try { currentAudioElement.pause(); currentAudioElement.currentTime = 0; } catch (e) {}
            currentAudioElement = null;
        }
        if ('speechSynthesis' in window) {
            try { window.speechSynthesis.cancel(); } catch (e) {}
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

    async function playTextToSpeech(text, lang, playBtn = null) {
        if (!text || !text.trim()) return;

        if (isSpeaking && activeAudioBtn === playBtn && playBtn !== null) {
            stopSpeaking();
            return;
        }

        stopSpeaking();

        if (playBtn) {
            activeAudioBtn = playBtn;
            activeAudioBtn.classList.add('playing');
            activeAudioBtn.innerHTML = '<i data-lucide="loader-2" class="spin"></i> Loading...';
            if (window.lucide) lucide.createIcons();
        }

        if (headerStopAudioBtn) headerStopAudioBtn.style.display = 'flex';
        isSpeaking = true;

        const cleanText = text.replace(/<[^>]*>?/gm, '').replace(/[*_#`~]/g, '').trim();

        fetchBackendAudio(cleanText, lang, (playedSuccessfully) => {
            if (playedSuccessfully) return;

            if ('speechSynthesis' in window) {
                try {
                    window.speechSynthesis.cancel();
                    const utterance = new SpeechSynthesisUtterance(cleanText);
                    utterance.lang = getLangTag(lang);
                    utterance.rate = 1.0;
                    utterance.onend = () => stopSpeaking();
                    utterance.onerror = () => stopSpeaking();

                    if (activeAudioBtn) {
                        activeAudioBtn.classList.add('playing');
                        activeAudioBtn.innerHTML = '<i data-lucide="square"></i> Stop Audio';
                        if (window.lucide) lucide.createIcons();
                    }

                    window.speechSynthesis.speak(utterance);
                    return;
                } catch (err) {
                    console.warn('SpeechSynthesis fallback error:', err);
                }
            }
            stopSpeaking();
        });
    }

    async function fetchBackendAudio(text, lang, onFinish) {
        try {
            const res = await fetch(`${API_BASE}/text-to-speech`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ text: text, language: lang })
            });

            if (res.ok) {
                const data = await res.json();
                if (data.success && data.audio_url) {
                    if (currentAudioElement) {
                        try { currentAudioElement.pause(); } catch (e) {}
                    }
                    const fullUrl = data.audio_url.startsWith('http') ? data.audio_url : `${window.location.origin}${data.audio_url}?t=${Date.now()}`;
                    currentAudioElement = new Audio(fullUrl);
                    currentAudioElement.onended = () => stopSpeaking();
                    currentAudioElement.onerror = (e) => {
                        stopSpeaking();
                        onFinish(false);
                    };
                    const playPromise = currentAudioElement.play();
                    if (playPromise !== undefined) {
                        playPromise.then(() => {
                            if (activeAudioBtn) {
                                activeAudioBtn.classList.add('playing');
                                activeAudioBtn.innerHTML = '<i data-lucide="square"></i> Stop Audio';
                                if (window.lucide) lucide.createIcons();
                            }
                            onFinish(true);
                        }).catch(err => {
                            stopSpeaking();
                            onFinish(false);
                        });
                    } else {
                        onFinish(true);
                    }
                    return;
                }
            }
        } catch (e) {
            console.warn('Backend TTS endpoint error:', e);
        }
        onFinish(false);
    }

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
