package com.butterflyai.keyboard

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale

enum class KeyboardState {
    IDLE, RECORDING, PROCESSING, RESULT
}

class ButterflyInputMethodService : InputMethodService(), TextToSpeech.OnInitListener {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var networkService: NetworkService

    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var currentState = KeyboardState.IDLE
    private var isTranslateOn = true

    private var recordingStartTime: Long = 0
    private var recordingTimerJob: Job? = null

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    // UI View References
    private lateinit var layoutActionButtons: LinearLayout
    private lateinit var btnTapToSpeak: LinearLayout
    private lateinit var tvTapToSpeak: TextView
    private lateinit var imgMicIconMain: ImageView
    private lateinit var btnPolish: LinearLayout
    private lateinit var btnAskAI: LinearLayout
    private lateinit var btnToggleTranslate: Button

    // Recording State Controls
    private lateinit var layoutRecordingState: LinearLayout
    private lateinit var tvRecordingTimer: TextView
    private lateinit var btnStopRecording: Button
    private lateinit var btnCancelRecording: Button

    // Processing State Controls
    private lateinit var layoutProcessingState: LinearLayout
    private lateinit var tvProcessingStatus: TextView

    // Voice Result Widget Controls
    private lateinit var layoutVoiceResult: LinearLayout
    private lateinit var tvOriginalText: TextView
    private lateinit var tvTranslatedText: TextView
    private lateinit var lblTranslationHeader: TextView
    private lateinit var tvInsertedNotice: TextView
    private lateinit var btnInsertText: Button
    private lateinit var btnCopyText: Button
    private lateinit var btnSpeakText: Button
    private lateinit var btnDismissResult: Button

    // Navigation & Spinners
    private lateinit var btnToggleKeyboard: ImageButton
    private lateinit var keyboardKeysLayout: LinearLayout
    private lateinit var spinnerSourceLang: Spinner
    private lateinit var spinnerTargetLang: Spinner

    private var isKeyboardGridVisible = false
    private var lastOriginalText = ""
    private var lastTranslatedText = ""
    private var lastFinalText = ""

    private val languages = arrayOf(
        "auto" to "✨ Auto Detect",
        "te" to "Telugu (తెలుగు)",
        "en" to "English",
        "hi" to "Hindi (हिंदी)",
        "ta" to "Tamil (தமிழ்)",
        "kn" to "Kannada (కన్నడ)",
        "ml" to "Malayalam (മലയാളം)",
        "mr" to "Marathi (మరాఠీ)",
        "bn" to "Bengali (বাংলা)",
        "gu" to "Gujarati (ગુજરાતી)",
        "pa" to "Punjabi (ਪੰਜਾਬੀ)",
        "ur" to "Urdu (اردو)",
        "es" to "Spanish",
        "fr" to "French",
        "de" to "German",
        "it" to "Italian",
        "pt" to "Portuguese",
        "ar" to "Arabic",
        "ja" to "Japanese",
        "ko" to "Korean",
        "zh" to "Chinese",
        "ru" to "Russian"
    )

    override fun onCreate() {
        super.onCreate()
        networkService = NetworkService(this)
        try {
            tts = TextToSpeech(this, this)
        } catch (e: Exception) {
            Log.e("ButterflyIME", "TTS initialization failed: ${e.message}")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            tts?.language = Locale.US
        } else {
            Log.w("ButterflyIME", "TTS init status failed: $status")
        }
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    override fun onCreateInputView(): View {
        val inputView = layoutInflater.inflate(R.layout.keyboard_view, null)

        // Bind UI Views
        layoutActionButtons = inputView.findViewById(R.id.layoutActionButtons)
        btnTapToSpeak = inputView.findViewById(R.id.btnTapToSpeak)
        tvTapToSpeak = inputView.findViewById(R.id.tvTapToSpeak)
        imgMicIconMain = inputView.findViewById(R.id.imgMicIconMain)
        btnPolish = inputView.findViewById(R.id.btnPolish)
        btnAskAI = inputView.findViewById(R.id.btnAskAI)
        btnToggleTranslate = inputView.findViewById(R.id.btnToggleTranslate)

        layoutRecordingState = inputView.findViewById(R.id.layoutRecordingState)
        tvRecordingTimer = inputView.findViewById(R.id.tvRecordingTimer)
        btnStopRecording = inputView.findViewById(R.id.btnStopRecording)
        btnCancelRecording = inputView.findViewById(R.id.btnCancelRecording)

        layoutProcessingState = inputView.findViewById(R.id.layoutProcessingState)
        tvProcessingStatus = inputView.findViewById(R.id.tvProcessingStatus)

        layoutVoiceResult = inputView.findViewById(R.id.layoutVoiceResult)
        tvOriginalText = inputView.findViewById(R.id.tvOriginalText)
        tvTranslatedText = inputView.findViewById(R.id.tvTranslatedText)
        lblTranslationHeader = inputView.findViewById(R.id.lblTranslationHeader)
        tvInsertedNotice = inputView.findViewById(R.id.tvInsertedNotice)
        btnInsertText = inputView.findViewById(R.id.btnInsertText)
        btnCopyText = inputView.findViewById(R.id.btnCopyText)
        btnSpeakText = inputView.findViewById(R.id.btnSpeakText)
        btnDismissResult = inputView.findViewById(R.id.btnDismissResult)

        btnToggleKeyboard = inputView.findViewById(R.id.btnToggleKeyboard)
        keyboardKeysLayout = inputView.findViewById(R.id.keyboardKeysLayout)
        spinnerSourceLang = inputView.findViewById(R.id.spinnerSourceLang)
        spinnerTargetLang = inputView.findViewById(R.id.spinnerTargetLang)

        // Setup Language Adapters
        val langNames = languages.map { it.second }
        val mainSourceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, langNames)
        val mainTargetAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, langNames)

        spinnerSourceLang.adapter = mainSourceAdapter
        spinnerTargetLang.adapter = mainTargetAdapter
        spinnerTargetLang.setSelection(2) // Default to English

        // Translation ON/OFF Toggle
        btnToggleTranslate.setOnClickListener {
            isTranslateOn = !isTranslateOn
            updateTranslateToggleUI()
        }
        updateTranslateToggleUI()

        // Tap to Speak Action (IDLE state)
        btnTapToSpeak.setOnClickListener {
            if (currentState == KeyboardState.IDLE) {
                startRecording()
            }
        }

        // Recording State Actions
        btnStopRecording.setOnClickListener {
            if (currentState == KeyboardState.RECORDING) {
                stopRecordingAndTranscribe()
            }
        }

        btnCancelRecording.setOnClickListener {
            if (currentState == KeyboardState.RECORDING) {
                cancelRecording()
            }
        }

        // Voice Result Widget Actions
        btnInsertText.setOnClickListener {
            if (lastFinalText.isNotBlank()) {
                val ic = currentInputConnection
                if (ic != null) {
                    ic.commitText(lastFinalText, 1)
                    Toast.makeText(this, "Inserted to input field", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Target text field not focused", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnCopyText.setOnClickListener {
            if (lastFinalText.isNotBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Butterfly Voice Text", lastFinalText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }

        btnSpeakText.setOnClickListener {
            if (lastFinalText.isNotBlank() && isTtsReady) {
                tts?.speak(lastFinalText, TextToSpeech.QUEUE_FLUSH, null, "butterfly_tts")
            } else {
                Toast.makeText(this, "TTS not ready", Toast.LENGTH_SHORT).show()
            }
        }

        btnDismissResult.setOnClickListener {
            setKeyboardState(KeyboardState.IDLE)
        }

        // Toggle QWERTY Grid
        btnToggleKeyboard.setOnClickListener {
            isKeyboardGridVisible = !isKeyboardGridVisible
            keyboardKeysLayout.visibility = if (isKeyboardGridVisible) View.VISIBLE else View.GONE
        }

        // Action: ✨ AI Polish
        btnPolish.setOnClickListener {
            if (currentState == KeyboardState.IDLE || currentState == KeyboardState.RESULT) {
                handleAiPolish()
            }
        }

        // Action: 🤖 AI Ask
        btnAskAI.setOnClickListener {
            if (currentState == KeyboardState.IDLE || currentState == KeyboardState.RESULT) {
                handleAiAsk()
            }
        }

        // 🌐 Globe Keyboard Switcher
        inputView.findViewById<Button>(R.id.btnSwitchIme)?.setOnClickListener {
            Log.d("ButterflyIME", "Switch keyboard pressed")
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    switchToNextInputMethod(false)
                } catch (e: Exception) {
                    imm.showInputMethodPicker()
                }
            } else {
                imm.showInputMethodPicker()
            }
        }

        // Key Listeners Setup
        setupKeyListeners(inputView)

        setKeyboardState(KeyboardState.IDLE)
        return inputView
    }

    private fun setKeyboardState(state: KeyboardState) {
        currentState = state
        when (state) {
            KeyboardState.IDLE -> {
                layoutActionButtons.visibility = View.VISIBLE
                layoutRecordingState.visibility = View.GONE
                layoutProcessingState.visibility = View.GONE
                layoutVoiceResult.visibility = View.GONE
                tvTapToSpeak.text = "🎙  TAP TO SPEAK"
                btnTapToSpeak.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
            }
            KeyboardState.RECORDING -> {
                layoutActionButtons.visibility = View.GONE
                layoutRecordingState.visibility = View.VISIBLE
                layoutProcessingState.visibility = View.GONE
                layoutVoiceResult.visibility = View.GONE
            }
            KeyboardState.PROCESSING -> {
                layoutActionButtons.visibility = View.GONE
                layoutRecordingState.visibility = View.GONE
                layoutProcessingState.visibility = View.VISIBLE
                layoutVoiceResult.visibility = View.GONE
                tvProcessingStatus.text = "⏳ PROCESSING..."
            }
            KeyboardState.RESULT -> {
                layoutActionButtons.visibility = View.VISIBLE
                layoutRecordingState.visibility = View.GONE
                layoutProcessingState.visibility = View.GONE
                layoutVoiceResult.visibility = View.VISIBLE
            }
        }
    }

    private fun updateTranslateToggleUI() {
        if (isTranslateOn) {
            btnToggleTranslate.text = "Trans: ON"
            btnToggleTranslate.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_green))
        } else {
            btnToggleTranslate.text = "Trans: OFF"
            btnToggleTranslate.setBackgroundColor(ContextCompat.getColor(this, R.color.bg_key))
        }
    }

    private fun setupKeyListeners(view: View) {
        val letterKeys = mapOf(
            R.id.keyQ to "q", R.id.keyW to "w", R.id.keyE to "e", R.id.keyR to "r", R.id.keyT to "t",
            R.id.keyY to "y", R.id.keyU to "u", R.id.keyI to "i", R.id.keyO to "o", R.id.keyP to "p",
            R.id.keyA to "a", R.id.keyS to "s", R.id.keyD to "d", R.id.keyF to "f", R.id.keyG to "g",
            R.id.keyH to "h", R.id.keyJ to "j", R.id.keyK to "k", R.id.keyL to "l",
            R.id.keyZ to "z", R.id.keyX to "x", R.id.keyC to "c", R.id.keyV to "v", R.id.keyB to "b",
            R.id.keyN to "n", R.id.keyM to "m"
        )

        for ((id, char) in letterKeys) {
            view.findViewById<Button>(id)?.setOnClickListener {
                currentInputConnection?.commitText(char, 1)
            }
        }

        // SPACE BUTTON
        view.findViewById<Button>(R.id.btnSpace)?.setOnClickListener {
            currentInputConnection?.commitText(" ", 1)
        }

        // BACKSPACE BUTTON
        view.findViewById<Button>(R.id.btnBackspace)?.setOnClickListener {
            val ic = currentInputConnection
            if (ic != null) {
                val selectedText = ic.getSelectedText(0)
                if (!selectedText.isNullOrEmpty()) {
                    ic.commitText("", 1)
                } else {
                    ic.deleteSurroundingText(1, 0)
                }
            }
        }

        // ENTER BUTTON
        view.findViewById<Button>(R.id.btnEnter)?.setOnClickListener {
            val ic = currentInputConnection
            if (ic != null) {
                val editorInfo = currentInputEditorInfo
                val action = editorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
                if (action != null && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                    ic.performEditorAction(action)
                } else if (editorInfo != null && (editorInfo.inputType and EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE) != 0) {
                    ic.commitText("\n", 1)
                } else {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                }
            }
        }
    }

    private fun checkMicPermission(): Boolean {
        val permission = Manifest.permission.RECORD_AUDIO
        val res = ContextCompat.checkSelfPermission(this, permission)
        if (res != PackageManager.PERMISSION_GRANTED) {
            Log.e("ButterflyIME", "Microphone permission denied")
            Toast.makeText(this, "Microphone permission required for voice recording", Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    private fun startRecording() {
        if (currentState != KeyboardState.IDLE) return
        if (!checkMicPermission()) return

        try {
            audioFile = File(cacheDir, "butterfly_voice_${System.currentTimeMillis()}.m4a")
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(audioFile?.absolutePath)
                prepare()
                start()
            }

            recordingStartTime = SystemClock.elapsedRealtime()
            setKeyboardState(KeyboardState.RECORDING)
            tvRecordingTimer.text = "00:00"

            recordingTimerJob?.cancel()
            recordingTimerJob = serviceScope.launch {
                while (currentState == KeyboardState.RECORDING) {
                    val elapsedMs = SystemClock.elapsedRealtime() - recordingStartTime
                    val totalSecs = (elapsedMs / 1000).toInt()
                    val mins = totalSecs / 60
                    val secs = totalSecs % 60
                    tvRecordingTimer.text = String.format("%02d:%02d", mins, secs)
                    delay(200)
                }
            }

            Log.d("ButterflyIME", "Recording started successfully")

        } catch (e: Exception) {
            Log.e("ButterflyIME", "Start recording error: ${e.message}", e)
            Toast.makeText(this, "Failed to start recording: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            cleanupRecorder()
            setKeyboardState(KeyboardState.IDLE)
        }
    }

    private fun cancelRecording() {
        if (currentState != KeyboardState.RECORDING) return
        recordingTimerJob?.cancel()
        cleanupRecorder()
        try { audioFile?.delete() } catch (e: Exception) {}
        setKeyboardState(KeyboardState.IDLE)
        Toast.makeText(this, "Recording cancelled", Toast.LENGTH_SHORT).show()
    }

    private fun stopRecordingAndTranscribe() {
        if (currentState != KeyboardState.RECORDING) return
        recordingTimerJob?.cancel()
        Log.d("ButterflyIME", "Recording stopping...")

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.w("ButterflyIME", "Error stopping mediaRecorder: ${e.message}")
        }
        mediaRecorder = null

        val currentAudio = audioFile
        setKeyboardState(KeyboardState.PROCESSING)

        val srcLang = languages[spinnerSourceLang.selectedItemPosition].first
        val tgtLang = languages[spinnerTargetLang.selectedItemPosition].first

        if (currentAudio != null && currentAudio.exists() && currentAudio.length() > 0) {
            serviceScope.launch {
                try {
                    val result = networkService.transcribeAudio(
                        audioFile = currentAudio,
                        sourceLanguage = srcLang,
                        targetLanguage = tgtLang,
                        isTranslateOn = isTranslateOn
                    )

                    if (result.success && result.originalText.isNotBlank()) {
                        lastOriginalText = result.originalText
                        lastTranslatedText = result.translatedText

                        lastFinalText = if (isTranslateOn && result.translatedText.isNotBlank()) {
                            result.translatedText
                        } else {
                            result.originalText
                        }

                        // Update Voice Result View
                        tvOriginalText.text = lastOriginalText
                        if (isTranslateOn && lastTranslatedText.isNotBlank() && lastTranslatedText != lastOriginalText) {
                            lblTranslationHeader.visibility = View.VISIBLE
                            tvTranslatedText.visibility = View.VISIBLE
                            tvTranslatedText.text = lastTranslatedText
                        } else {
                            lblTranslationHeader.visibility = View.GONE
                            tvTranslatedText.visibility = View.GONE
                        }

                        // 1. Commit text into active input connection immediately
                        val ic = currentInputConnection
                        if (ic != null) {
                            val committed = ic.commitText(lastFinalText, 1)
                            Log.d("ButterflyIME", "Text committed: $lastFinalText (success: $committed)")
                            tvInsertedNotice.text = "✓ Inserted into app: \"$lastFinalText\""
                            tvInsertedNotice.visibility = View.VISIBLE
                        } else {
                            Log.w("ButterflyIME", "InputConnection is null")
                            tvInsertedNotice.text = "⚠️ Focused text field lost; tap 'Insert Again'"
                            tvInsertedNotice.visibility = View.VISIBLE
                        }

                        // 2. Transition to RESULT state (Keyboard remains open)
                        setKeyboardState(KeyboardState.RESULT)

                    } else {
                        val errMsg = result.error ?: "No speech detected"
                        Log.w("ButterflyIME", "Transcription failed: $errMsg")
                        Toast.makeText(this@ButterflyInputMethodService, "Speech error: $errMsg", Toast.LENGTH_SHORT).show()
                        setKeyboardState(KeyboardState.IDLE)
                    }
                } catch (e: Exception) {
                    Log.e("ButterflyIME", "Backend transcription request error: ${e.message}", e)
                    Toast.makeText(this@ButterflyInputMethodService, "Backend error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                    setKeyboardState(KeyboardState.IDLE)
                } finally {
                    try { currentAudio.delete() } catch (e: Exception) {}
                }
            }
        } else {
            Toast.makeText(this, "Empty speech recording", Toast.LENGTH_SHORT).show()
            setKeyboardState(KeyboardState.IDLE)
        }
    }

    private fun cleanupRecorder() {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {}
        mediaRecorder = null
    }

    private fun handleAiPolish() {
        val ic = currentInputConnection
        val selectedText = ic?.getSelectedText(0)?.toString() ?: ""
        val beforeCursor = ic?.getTextBeforeCursor(1000, 0)?.toString() ?: ""
        val textToPolish = when {
            selectedText.isNotBlank() -> selectedText
            beforeCursor.isNotBlank() -> beforeCursor.trim()
            else -> lastFinalText
        }

        if (textToPolish.isNotBlank()) {
            tvTapToSpeak.text = "⏳ Polishing..."
            serviceScope.launch {
                try {
                    val res = networkService.polishText(textToPolish)
                    tvTapToSpeak.text = "🎙  TAP TO SPEAK"
                    if (res.success && res.polishedText.isNotBlank()) {
                        val currentIc = currentInputConnection
                        if (currentIc != null) {
                            if (selectedText.isNotBlank()) {
                                currentIc.commitText(res.polishedText, 1)
                            } else if (beforeCursor.isNotBlank()) {
                                currentIc.deleteSurroundingText(beforeCursor.length, 0)
                                currentIc.commitText(res.polishedText, 1)
                            } else {
                                currentIc.commitText(res.polishedText, 1)
                            }
                        }
                        Toast.makeText(this@ButterflyInputMethodService, "✨ Polished!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@ButterflyInputMethodService, res.error ?: "Polish failed", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    tvTapToSpeak.text = "🎙  TAP TO SPEAK"
                    Toast.makeText(this@ButterflyInputMethodService, "Polish error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Toast.makeText(this, "Type or speak text first to polish", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleAiAsk() {
        val ic = currentInputConnection
        val selectedText = ic?.getSelectedText(0)?.toString() ?: ""
        val beforeCursor = ic?.getTextBeforeCursor(1000, 0)?.toString() ?: ""
        val prompt = when {
            selectedText.isNotBlank() -> selectedText
            beforeCursor.isNotBlank() -> beforeCursor.trim()
            else -> lastFinalText
        }

        if (prompt.isNotBlank()) {
            tvTapToSpeak.text = "⏳ Asking AI..."
            val srcLang = languages[spinnerSourceLang.selectedItemPosition].first
            serviceScope.launch {
                try {
                    val res = networkService.askAI(prompt, srcLang)
                    tvTapToSpeak.text = "🎙  TAP TO SPEAK"
                    if (res.success && res.answer.isNotBlank()) {
                        currentInputConnection?.commitText("\n" + res.answer, 1)
                        Toast.makeText(this@ButterflyInputMethodService, "🤖 Answer inserted!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@ButterflyInputMethodService, res.error ?: "Ask AI failed", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    tvTapToSpeak.text = "🎙  TAP TO SPEAK"
                    Toast.makeText(this@ButterflyInputMethodService, "Ask AI error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Toast.makeText(this, "Type a question or speak first", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (currentState == KeyboardState.RECORDING) {
            cancelRecording()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        recordingTimerJob?.cancel()
        cleanupRecorder()
        serviceScope.cancel()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {}
    }
}
