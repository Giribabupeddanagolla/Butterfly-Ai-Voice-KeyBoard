package com.butterflyai.keyboard

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
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
    private lateinit var btnStopRecording: View
    private lateinit var btnCancelRecording: View
    private var layoutRecordingDotContainer: View? = null
    private var layoutWaveformBars: LinearLayout? = null
    private var dotPulseAnimator: ObjectAnimator? = null

    // Processing State Controls
    private lateinit var layoutProcessingState: LinearLayout
    private lateinit var tvProcessingStatus: TextView

    // Voice Result Widget Controls
    private lateinit var layoutVoiceResult: LinearLayout
    private lateinit var lblOriginalHeader: TextView
    private lateinit var tvOriginalText: TextView
    private lateinit var tvTranslatedText: TextView
    private lateinit var lblTranslationHeader: TextView
    private lateinit var tvInsertedNotice: TextView
    private lateinit var btnInsertText: Button
    private lateinit var btnCopyText: Button
    private lateinit var btnSpeakText: Button
    private lateinit var btnDismissResult: Button

    private var isAiAnswerLoading = false

    private lateinit var btnToggleKeyboard: Button
    private lateinit var keyboardKeysLayout: LinearLayout
    private lateinit var spinnerSourceLang: Spinner
    private lateinit var spinnerTargetLang: Spinner
    private lateinit var btnCycleTheme: Button
    private var btnStatusOnline: View? = null
    private var tvOnlineStatus: TextView? = null

    private var currentRootView: View? = null
    private val themeList = arrayOf("sky", "dark", "light", "oled", "cyber", "sunset")
    private var currentThemeKey = "sky"

    private var isShifted = true
    private var isCapsLock = false
    private var lastShiftClickTime = 0L

    private enum class KeyboardMode { LETTERS, NUMBERS, EMOJI }
    private var currentMode = KeyboardMode.LETTERS

    private val letterKeysMap = mapOf(
        R.id.keyQ to "q", R.id.keyW to "w", R.id.keyE to "e", R.id.keyR to "r", R.id.keyT to "t",
        R.id.keyY to "y", R.id.keyU to "u", R.id.keyI to "i", R.id.keyO to "o", R.id.keyP to "p",
        R.id.keyA to "a", R.id.keyS to "s", R.id.keyD to "d", R.id.keyF to "f", R.id.keyG to "g",
        R.id.keyH to "h", R.id.keyJ to "j", R.id.keyK to "k", R.id.keyL to "l",
        R.id.keyZ to "z", R.id.keyX to "x", R.id.keyC to "c", R.id.keyV to "v", R.id.keyB to "b",
        R.id.keyN to "n", R.id.keyM to "m"
    )

    private val numberKeysMap = mapOf(
        R.id.num1 to "1", R.id.num2 to "2", R.id.num3 to "3", R.id.num4 to "4", R.id.num5 to "5",
        R.id.num6 to "6", R.id.num7 to "7", R.id.num8 to "8", R.id.num9 to "9", R.id.num0 to "0",
        R.id.symAt to "@", R.id.symHash to "#", R.id.symDollar to "$", R.id.symPercent to "%",
        R.id.symAmp to "&", R.id.symMinus to "-", R.id.symPlus to "+", R.id.symParenOpen to "(",
        R.id.symParenClose to ")", R.id.symSlash to "/", R.id.symStar to "*", R.id.symQuoteDouble to "\"",
        R.id.symQuoteSingle to "'", R.id.symColon to ":", R.id.symSemicolon to ";",
        R.id.symExclamation to "!", R.id.symQuestion to "?", R.id.symEqual to "=", R.id.symBackslash to "\\"
    )

    private val emojiCategories = mapOf(
        "smileys" to listOf(
            "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇", "🙂", "🙃", "😉", "😌", "😍", "🥰", "😘", "😗",
            "😙", "😚", "😋", "😛", "😝", "😜", "🤪", "🤨", "🧐", "🤓", "😎", "🥸", "🤩", "🥳", "😏", "😒", "😞", "😔",
            "😟", "😕", "🙁", "☹️", "😣", "😖", "😫", "😩", "🥺", "😢", "😭", "😤", "😠", "😡", "🤬", "🤯", "😳", "🥵",
            "🥶", "😱", "😨", "😰", "😥", "😓", "🤗", "🤔", "🫣", "🤭", "🤫", "🤥", "😶", "😐", "😑", "😬", "🫠", "🙄",
            "😯", "😦", "😧", "😮", "😲", "🥱", "😴", "🤤", "😪", "😵", "🤐", "🥴", "🤢", "🤮", "🤧", "😷", "🤒", "🤕",
            "🤑", "🤠", "😈", "👿", "👹", "👺", "🤡", "💩", "👻", "💀", "☠️", "👽", "👾", "🤖", "🎃"
        ),
        "people" to listOf(
            "👋", "🤚", "🖐", "✋", "🖖", "🫲", "🫱", "👌", "🤌", "✌️", "🤞", "🫰", "🤟", "🤘", "🤙", "👈", "👉",
            "👆", "🖕", "👇", "☝️", "👍", "👎", "✊", "👊", "🤛", "🤜", "👏", "🙌", "🫶", "👐", "🤲", "🤝", "🙏", "✍️",
            "💅", "🤳", "💪", "🦾", "🦿", "🦵", "🦶", "👂", "🦻", "👃", "🧠", "🫀", "🫁", "🦷", "🦴", "👀", "👁️", "👅",
            "👄", "👶", "🧒", "👦", "👧", "🧑", "👱", "👨", "🧔", "👩", "🧓", "👴", "👵", "🙍", "🗣️", "👤", "👥", "🫂"
        ),
        "animals" to listOf(
            "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐨", "🐯", "🦁", "🐮", "🐷", "🐸", "🐵", "🙈", "🙉", "🙊",
            "🐒", "🐔", "🐧", "🐦", "🐤", "🐣", "🐥", "🦆", "🦅", "🦉", "🦇", "🐺", "🐗", "🐴", "🦄", "🐝", "🐛", "🦋",
            "🐌", "🐞", "🐜", "🪰", "🪲", "🪳", "🦟", "🦗", "🕷️", "🦂", "🐢", "🐍", "🦎", "🐙", "🦑", "🦞", "🦀", "🐡",
            "🐠", "🐟", "🐬", "🐳", "🐋", "🦈", "🦭", "🐊", "🐅", "🐆", "🦍", "🦧", "🐘", "🦛", "🦏", "🐪", "🐫", "🦒",
            "🦘", "🦬", "🐃", "🐂", "🐄", "🐎", "🐖", "🐏", "🐑", "🦙", "🐐", "🦌", "🐕", "🐩", "🦮", "🐈", "🦚", "🦜",
            "🦩", "🕊️", "🐇", "🦝", "🦨", "🦡", "🦦", "🦥", "🦔", "🌵", "🎄", "🌲", "🌳", "🌴", "🌱", "🌿", "☘️", "🍀",
            "🎍", "🪴", "🎋", "🍃", "🍂", "🍁", "🍄", "🌾", "💐", "🌷", "🌹", "🥀", "🌺", "🌸", "🌼", "🌻", "🌞", "🌝",
            "🌛", "🌜", "🌚", "🌕", "🌖", "🌗", "🌘", "🌑", "🌒", "🌓", "🌔", "🌙", "🌎", "🌍", "🌏", "🪐", "💫", "⭐",
            "🌟", "✨", "⚡", "☄️", "💥", "🔥", "🌪️", "🌈", "☀️", "🌤️", "⛅", "🌥️", "☁️", "🌦️", "🌧️", "⛈️", "🌨️", "❄️",
            "☃️", "⛄", "🌬️", "💨", "💧", "💦", "🌊"
        ),
        "food" to listOf(
            "🍇", "🍈", "🍉", "🍊", "🍋", "🍌", "🍍", "🥭", "🍎", "🍏", "🍐", "🍑", "🍒", "🍓", "🫐", "🥝", "🍅", "🫒",
            "🥥", "🥑", "🍆", "🥔", "🥕", "🌽", "🌶️", "🫑", "🥒", "🥬", "🥦", "🧄", "🧅", "🥜", "🌰", "🍞", "🥐", "🥖",
            "🫓", "🥨", "🥯", "🥞", "🧇", "🧀", "🍖", "🍗", "🥩", "🥓", "🍔", "🍟", "🍕", "🌭", "🥪", "🌮", "🌯", "🫔",
            "🥙", "🧆", "🥚", "🍳", "🥘", "🍲", "🫕", "🥣", "🥗", "🍿", "🧂", "🥫", "🍱", "🍘", "🍙", "🍚", "🍛", "🍜",
            "🍝", "🍠", "🍢", "🍣", "🍤", "🍥", "🥮", "🍡", "🥟", "🥠", "🥡", "🍦", "🍧", "🍨", "🍩", "🍪", "🎂", "🍰",
            "🧁", "🥧", "🍫", "🍬", "🍭", "🍮", "🍯", "🍼", "🥛", "☕", "🫖", "🍵", "🍶", "🍾", "🍷", "🍸", "🍹", "🍺",
            "🍻", "🥂", "🥃", "🥤", "🧋", "🧃", "🧉", "🧊"
        ),
        "travel" to listOf(
            "🚗", "🚕", "🚙", "🚌", "🏎️", "🚓", "🚑", "🚒", "🚐", "🛻", "🚚", "🚛", "🚜", "🛴", "🚲", "🛵", "🏍️", "🛺",
            "🚨", "🚔", "🚍", "🚘", "🚖", "🚡", "🚠", "🚟", "🚃", "🚋", "🚝", "🚅", "🚆", "🚇", "🚈", "🚉", "✈️",
            "🛫", "🛬", "🪂", "🚁", "🏣", "🏢", "🏥", "🏦", "🏨", "🏪", "🏫", "🏬", "🏭", "🏯", "🏰", "💒", "🗼", "🗽",
            "⛪", "🕌", "🛕", "🕍", "⛩️", "🕋", "⛲", "⛺", "🌁", "🌃", "🏙️", "🌄", "🌅", "🌆", "🌇", "🌉", "♨️", "🎠",
            "🎡", "🎢", "💈", "🎪", "🚀", "🛸", "🛰️", "🌋", "⛰️", "🏔️", "🗻", "🏕️", "🏖️", "🏜️", "🏝️", "🏞️", "🏟️",
            "🏛️", "🏗️", "🧱", "🏠", "🏡", "🏘️", "🏚️", "🌐", "🗺️", "🧭"
        ),
        "activities" to listOf(
            "⚽", "🏀", "🏈", "⚾", "🥎", "🎾", "🏐", "🏉", "🥏", "🎱", "🪀", "🏓", "🏸", "🏒", "🥍", "🏏", "🪃", "🥅",
            "⛳", "🪁", "🏹", "🎣", "🤿", "🥊", "🥋", "🎽", "🛹", "🛼", "🛷", "⛸️", "🎿", "⛷️", "🏂", "🪂", "🏋️", "🤼",
            "🤸", "⛹️", "🤺", "🤾", "🏌️", "🏇", "🧘", "🏄", "🏊", "🚣", "🧗", "🚵", "🚴", "🏆", "🥇", "🥈", "🥉",
            "🏅", "🎖️", "🏵️", "🎗️", "🎫", "🎟️", "🎪", "🤹", "🎭", "🩰", "🎨", "🎬", "🎤", "🎧", "🎼", "🎵", "🎶", "🥁",
            "🎷", "🎺", "🎸", "🪕", "🎻", "🎲", "♟️", "🎯", "🎳", "🎮", "🎰", "🧩", "🎉", "🎊", "🎈", "🎂", "🎄",
            "🎆", "🎇", "🧨", "✨", "🎃", "🎀", "🎁"
        ),
        "objects" to listOf(
            "👓", "🕶️", "🥽", "🥼", "👔", "👕", "👖", "🧣", "🧤", "🧥", "🦺", "👑", "👒", "🎩", "🎓", "🧢", "⛑️", "💍",
            "💼", "🎒", "🧳", "☂️", "☔", "📱", "📲", "💻", "⌨️", "🖥️", "🖨️", "🖱️", "🕹️", "🎙️", "🎚️", "🎛️", "📺",
            "📷", "📸", "📹", "📼", "🔍", "🔎", "🕯️", "💡", "🔦", "🏮", "📔", "📕", "📖", "📗", "📘", "📙", "📚", "📓",
            "📒", "📃", "📜", "📄", "📰", "🗞️", "📑", "🔖", "🏷️", "💰", "🪙", "<ctrl42>", "💵", "💶", "💷", "💸", "💳", "🧾",
            "✉️", "📧", "📨", "📩", "📤", "📥", "📦", "📫", "📬", "📭", "📮", "📝", "✏️", "✒️", "🖊️", "🖌️", "🖍️", "📌",
            "📍", "📎", "🖇️", "📐", "📏", "🧮", "✂️", "🗑️", "🔒", "🔓", "🔏", "🔐", "🔑", "🗝️", "🔨", "🪓", "⛏️", "⚒️",
            "🛠️", "🗡️", "⚔️", "💣", "🛡️", "⚙️", "🧱", "⛓️", "🧲", "⚗️", "🧪", "🧫", "🧬", "🔬", "🔭", "📡", "💉", "🩸",
            "💊", "🩹", "🩺", "🚪", "🛗", "🪞", "🪟", "🛏️", "🛋️", "🚽", "🚰", "🛁", "🧼", "🪥", "🪒", "🧽", "🪣", "🧴",
            "🔮", "🚬", "🪦", "⚱️", "🗿"
        ),
        "symbols" to listOf(
            "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔", "❣️", "💕", "💞", "💓", "💗", "💖", "💘", "💝",
            "💟", "☮️", "✝️", "☪️", "🕉️", "☸️", "✡️", "🔯", "🕎", "☯️", "☦️", "🛐", "<ctrl42>", "♈", "♉", "♊", "♋", "♌",
            "♍", "♎", "♏", "♐", "♑", "♒", "♓", "🆔", "⚛️", "📴", "📳", "✴️", "🆚", "💮", "🅰️", "🅱️", "🆎", "🆑",
            "🅾️", "🆘", "❌", "⭕", "🛑", "⛔", "📛", "🚫", "💯", "💢", "♨️", "🚷", "🚯", "🚳", "🚱", "🔞", "📵", "🚭",
            "❗", "❕", "❓", "❔", "‼️", "⁉️", "🔅", "🔆", "〽️", "⚠️", "🚸", "🔱", "⚜️", "🔰", "♻️", "✅", "❇️", "✳️",
            "❎", "🌐", "💠", "Ⓜ️", "🌀", "💤", "🏧", "🚾", "♿", "🅿️", "🈳", "<ctrl42>", "🛃", "🛄", "🛅", "🚹", "🚺", "🚼",
            "🚻", "▶️", "⏩", "⏭️", "⏯️", "◀️", "⏪", "⏮️", "🔼", "⏫", "🔽", "⏬", "⏸️", "⏹️", "⏺️", "⏏️", "♀️", "♂️",
            "⚕️", "♾️", "🔀", "🔁", "🔂", "🔄", "➕", "➖", "➗", "✖️", "💲", "💱", "🪬", "🧿", "🔮", "⭐", "🌟", "✨",
            "⚡", "💥"
        ),
        "flags" to listOf(
            "🏁", "🚩", "🎌", "🏴", "🏳️", "🏳️‍🌈", "🏳️‍⚧️", "🏴‍☠️", "🇮🇳", "🇺🇸", "🇬🇧", "🇨🇦", "🇦🇺", "🇯🇵", "🇰🇷", "🇩🇪",
            "🇫🇷", "🇧🇷", "🇮🇹", "🇪🇸", "🇷🇺", "🇨🇳", "🇲🇽", "🇿🇦", "🇸🇬", "🇳🇿", "🇦🇪", "🇸🇦", "🇳🇵", "🇧🇩", "🇱🇰", "🇵🇰",
            "🇮🇩", "🇲🇾", "🇵🇭", "🇹🇭", "🇻🇳", "🇹🇷", "🇪🇬", "🇳🇬", "🇰🇪", "🇦🇷", "🇨🇱", "🇨🇴", "🇵🇪", "🇺🇦", "🇵🇱", "🇳🇱",
            "🇸🇪", "🇨🇭"
        )
    )

    private var isKeyboardGridVisible = true
    private var lastOriginalText = ""
    private var lastTranslatedText = ""
    private var lastFinalText = ""
    private var lastCommittedText = ""
    private var isSpinnerInitialized = false
    private var retranslateJob: Job? = null

    private val languages = arrayOf(
        "auto" to "Auto Detect",
        "te" to "Telugu (తెలుగు)",
        "en" to "English",
        "hi" to "Hindi (हिंदी)",
        "ta" to "Tamil (தமிழ்)",
        "kn" to "Kannada (ಕನ್ನಡ)",
        "ml" to "Malayalam (മലയാളം)",
        "mr" to "Marathi (मराठी)",
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
        layoutRecordingDotContainer = inputView.findViewById(R.id.layoutRecordingDotContainer)
        layoutWaveformBars = inputView.findViewById(R.id.layoutWaveformBars)

        layoutProcessingState = inputView.findViewById(R.id.layoutProcessingState)
        tvProcessingStatus = inputView.findViewById(R.id.tvProcessingStatus)

        layoutVoiceResult = inputView.findViewById(R.id.layoutVoiceResult)
        lblOriginalHeader = inputView.findViewById(R.id.lblOriginalHeader)
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

        // Setup Language Adapters with custom layout for explicit text color & dynamic theme styling
        val langNames = languages.map { it.second }
        val mainSourceAdapter = object : ArrayAdapter<String>(this, R.layout.spinner_item, langNames) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                if (v is TextView) {
                    val palette = getThemePalette(currentThemeKey)
                    if (palette.isDark) {
                        v.setTextColor(if (currentThemeKey == "cyber") android.graphics.Color.parseColor("#00F0FF") else android.graphics.Color.WHITE)
                    } else {
                        v.setTextColor(android.graphics.Color.parseColor("#1E293B"))
                    }
                }
                return v
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent)
                if (v is TextView) {
                    val palette = getThemePalette(currentThemeKey)
                    v.textSize = 9.5f
                    v.includeFontPadding = false
                    v.setPadding(dpToPx(8), dpToPx(3), dpToPx(8), dpToPx(3))
                    if (palette.isDark) {
                        v.setTextColor(if (currentThemeKey == "cyber") android.graphics.Color.parseColor("#00F0FF") else android.graphics.Color.WHITE)
                        v.setBackgroundColor(palette.cardBg)
                    } else {
                        v.setTextColor(android.graphics.Color.parseColor("#1E293B"))
                        v.setBackgroundColor(android.graphics.Color.WHITE)
                    }
                }
                return v
            }
        }.apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }

        val mainTargetAdapter = object : ArrayAdapter<String>(this, R.layout.spinner_item, langNames) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                if (v is TextView) {
                    val palette = getThemePalette(currentThemeKey)
                    if (palette.isDark) {
                        v.setTextColor(if (currentThemeKey == "cyber") android.graphics.Color.parseColor("#00F0FF") else android.graphics.Color.WHITE)
                    } else {
                        v.setTextColor(android.graphics.Color.parseColor("#1E293B"))
                    }
                }
                return v
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent)
                if (v is TextView) {
                    val palette = getThemePalette(currentThemeKey)
                    v.textSize = 9.5f
                    v.includeFontPadding = false
                    v.setPadding(dpToPx(8), dpToPx(3), dpToPx(8), dpToPx(3))
                    if (palette.isDark) {
                        v.setTextColor(if (currentThemeKey == "cyber") android.graphics.Color.parseColor("#00F0FF") else android.graphics.Color.WHITE)
                        v.setBackgroundColor(palette.cardBg)
                    } else {
                        v.setTextColor(android.graphics.Color.parseColor("#1E293B"))
                        v.setBackgroundColor(android.graphics.Color.WHITE)
                    }
                }
                return v
            }
        }.apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }

        spinnerSourceLang.adapter = mainSourceAdapter
        spinnerTargetLang.adapter = mainTargetAdapter
        spinnerTargetLang.setSelection(1) // Default target to Telugu (తెలుగు)

        val spinnerListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!isSpinnerInitialized) return
                retranslateAndUpdate()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spinnerSourceLang.onItemSelectedListener = spinnerListener
        spinnerTargetLang.onItemSelectedListener = spinnerListener
        isSpinnerInitialized = true

        // Translation ON/OFF Toggle
        btnToggleTranslate.setOnClickListener {
            isTranslateOn = !isTranslateOn
            updateTranslateToggleUI()
            retranslateAndUpdate()
        }
        updateTranslateToggleUI()

        // Tap to Speak Action (IDLE state) - Handles both main mic button & Voice Keyboard card
        val startVoiceRecordingAction = View.OnClickListener {
            if (currentState == KeyboardState.IDLE) {
                startRecording()
            }
        }
        btnTapToSpeak.setOnClickListener(startVoiceRecordingAction)
        tvTapToSpeak.setOnClickListener(startVoiceRecordingAction)
        imgMicIconMain.setOnClickListener(startVoiceRecordingAction)
        inputView.findViewById<View>(R.id.btnCardVoice)?.setOnClickListener(startVoiceRecordingAction)

        // Feature Card 2: Web Search -> DuckDuckGo in external browser
        inputView.findViewById<View>(R.id.btnCardSearch)?.setOnClickListener {
            handleSearchButtonClick()
        }

        // Feature Card 3: AI Answer -> Butterfly AI backend to in-keyboard widget
        inputView.findViewById<View>(R.id.btnCardAI)?.setOnClickListener {
            handleAiAnswerButtonClick()
        }

        // Online Status Pill Click Listener & Connection Check
        btnStatusOnline = inputView.findViewById(R.id.btnStatusOnline)
        tvOnlineStatus = inputView.findViewById(R.id.tvOnlineStatus)

        btnStatusOnline?.setOnClickListener {
            Toast.makeText(this, "Checking connection...", Toast.LENGTH_SHORT).show()
            serviceScope.launch {
                val result = networkService.testConnection()
                if (result.success) {
                    tvOnlineStatus?.text = "● Online"
                    tvOnlineStatus?.setTextColor(android.graphics.Color.parseColor("#059669"))
                    Toast.makeText(this@ButterflyInputMethodService, result.message, Toast.LENGTH_LONG).show()
                } else {
                    tvOnlineStatus?.text = "● Offline"
                    tvOnlineStatus?.setTextColor(android.graphics.Color.parseColor("#DC2626"))
                    Toast.makeText(this@ButterflyInputMethodService, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }

        // Run background check on creation
        serviceScope.launch {
            val result = networkService.testConnection()
            if (result.success) {
                tvOnlineStatus?.text = "● Online"
                tvOnlineStatus?.setTextColor(android.graphics.Color.parseColor("#059669"))
            } else {
                tvOnlineStatus?.text = "● Offline"
                tvOnlineStatus?.setTextColor(android.graphics.Color.parseColor("#DC2626"))
            }
        }

        // Snippets Quick Action
        inputView.findViewById<View>(R.id.btnSnippets)?.setOnClickListener {
            val ic = currentInputConnection
            if (ic != null) {
                ic.commitText("Hello! Thanks for reaching out. ", 1)
                Toast.makeText(this, "Snippet inserted", Toast.LENGTH_SHORT).show()
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
                    lastCommittedText = lastFinalText
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

        currentRootView = inputView
        btnCycleTheme = inputView.findViewById(R.id.btnCycleTheme)
        btnCycleTheme.setOnClickListener {
            val prefs = getSharedPreferences("butterfly_prefs", Context.MODE_PRIVATE)
            val currentTheme = prefs.getString("keyboard_theme", "sky") ?: "sky"
            val currentIndex = themeList.indexOf(currentTheme).let { if (it >= 0) it else 0 }
            val nextIndex = (currentIndex + 1) % themeList.size
            val nextTheme = themeList[nextIndex]
            prefs.edit().putString("keyboard_theme", nextTheme).apply()
            applyTheme(nextTheme, inputView)
            Toast.makeText(this, "Theme: ${nextTheme.uppercase()}", Toast.LENGTH_SHORT).show()
        }

        // Key Listeners Setup
        setupKeyListeners(inputView)

        val prefs = getSharedPreferences("butterfly_prefs", Context.MODE_PRIVATE)
        val initialTheme = prefs.getString("keyboard_theme", "sky") ?: "sky"
        applyTheme(initialTheme, inputView)

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
                if (::keyboardKeysLayout.isInitialized) keyboardKeysLayout.visibility = View.VISIBLE
                tvTapToSpeak.text = "🎙  TAP TO SPEAK"
                btnTapToSpeak.setBackgroundColor(getThemePalette(currentThemeKey).accentColor)
            }
            KeyboardState.RECORDING -> {
                layoutActionButtons.visibility = View.GONE
                layoutRecordingState.visibility = View.VISIBLE
                layoutProcessingState.visibility = View.GONE
                layoutVoiceResult.visibility = View.GONE
                if (::keyboardKeysLayout.isInitialized) keyboardKeysLayout.visibility = View.GONE
            }
            KeyboardState.PROCESSING -> {
                layoutActionButtons.visibility = View.GONE
                layoutRecordingState.visibility = View.GONE
                layoutProcessingState.visibility = View.VISIBLE
                layoutVoiceResult.visibility = View.GONE
                if (::keyboardKeysLayout.isInitialized) keyboardKeysLayout.visibility = View.GONE
                tvProcessingStatus.text = "⏳ PROCESSING..."
            }
            KeyboardState.RESULT -> {
                layoutActionButtons.visibility = View.VISIBLE
                layoutRecordingState.visibility = View.GONE
                layoutProcessingState.visibility = View.GONE
                layoutVoiceResult.visibility = View.VISIBLE
                if (::keyboardKeysLayout.isInitialized) keyboardKeysLayout.visibility = View.VISIBLE
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

    private var backspaceRepeatJob: Job? = null

    private fun performBackspace() {
        val ic = currentInputConnection ?: return
        val selectedText = ic.getSelectedText(0)
        if (!selectedText.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupKeyListeners(view: View) {
        // 1. QWERTY Letter Keys
        for ((id, charStr) in letterKeysMap) {
            view.findViewById<Button>(id)?.setOnClickListener {
                val isUpper = isShifted || isCapsLock
                val textToCommit = if (isUpper) charStr.uppercase(Locale.US) else charStr.lowercase(Locale.US)
                currentInputConnection?.commitText(textToCommit, 1)

                if (isShifted && !isCapsLock) {
                    isShifted = false
                    updateLetterCase(view)
                }
            }
        }

        // 2. Shift Key (Tap cycles: Shift -> Caps Lock -> Unshifted)
        view.findViewById<Button>(R.id.btnShift)?.setOnClickListener {
            when {
                isCapsLock -> {
                    isCapsLock = false
                    isShifted = false
                }
                isShifted -> {
                    isCapsLock = true
                    isShifted = false
                }
                else -> {
                    isShifted = true
                    isCapsLock = false
                }
            }
            updateLetterCase(view)
        }

        // 3. Numbers & Symbols Keys
        for ((id, charStr) in numberKeysMap) {
            view.findViewById<Button>(id)?.setOnClickListener {
                currentInputConnection?.commitText(charStr, 1)
            }
        }

        // 4. Layout Mode Toggles (?123 | ABC | 😊)
        val numModeListener = View.OnClickListener { setKeyboardMode(KeyboardMode.NUMBERS, view) }
        view.findViewById<Button>(R.id.btnNumMode)?.setOnClickListener(numModeListener)

        val abcModeListener = View.OnClickListener { setKeyboardMode(KeyboardMode.LETTERS, view) }
        view.findViewById<Button>(R.id.btnAbcMode)?.setOnClickListener(abcModeListener)
        view.findViewById<Button>(R.id.btnAbcFromEmojiBottom)?.setOnClickListener(abcModeListener)

        val emojiModeListener = View.OnClickListener { setKeyboardMode(KeyboardMode.EMOJI, view) }
        view.findViewById<Button>(R.id.btnEmojiMode)?.apply {
            isAllCaps = false
            setOnClickListener(emojiModeListener)
        }
        view.findViewById<Button>(R.id.btnEmojiModeNum)?.apply {
            isAllCaps = false
            setOnClickListener(emojiModeListener)
        }

        // 5. Emoji Category Tab Buttons (9 Standard Categories)
        val emojiTabIds = listOf(
            R.id.tabEmojiSmileys,
            R.id.tabEmojiPeople,
            R.id.tabEmojiAnimals,
            R.id.tabEmojiFood,
            R.id.tabEmojiTravel,
            R.id.tabEmojiActivities,
            R.id.tabEmojiObjects,
            R.id.tabEmojiSymbols,
            R.id.tabEmojiFlags
        )
        emojiTabIds.forEach { id ->
            view.findViewById<Button>(id)?.isAllCaps = false
        }

        view.findViewById<Button>(R.id.tabEmojiSmileys)?.setOnClickListener { loadEmojiCategory("smileys", view) }
        view.findViewById<Button>(R.id.tabEmojiPeople)?.setOnClickListener { loadEmojiCategory("people", view) }
        view.findViewById<Button>(R.id.tabEmojiAnimals)?.setOnClickListener { loadEmojiCategory("animals", view) }
        view.findViewById<Button>(R.id.tabEmojiFood)?.setOnClickListener { loadEmojiCategory("food", view) }
        view.findViewById<Button>(R.id.tabEmojiTravel)?.setOnClickListener { loadEmojiCategory("travel", view) }
        view.findViewById<Button>(R.id.tabEmojiActivities)?.setOnClickListener { loadEmojiCategory("activities", view) }
        view.findViewById<Button>(R.id.tabEmojiObjects)?.setOnClickListener { loadEmojiCategory("objects", view) }
        view.findViewById<Button>(R.id.tabEmojiSymbols)?.setOnClickListener { loadEmojiCategory("symbols", view) }
        view.findViewById<Button>(R.id.tabEmojiFlags)?.setOnClickListener { loadEmojiCategory("flags", view) }

        // 6. Punctuation Keys (, and .)
        val commaListener = View.OnClickListener { currentInputConnection?.commitText(",", 1) }
        view.findViewById<Button>(R.id.btnComma)?.setOnClickListener(commaListener)
        view.findViewById<Button>(R.id.btnCommaNum)?.setOnClickListener(commaListener)

        val periodListener = View.OnClickListener {
            currentInputConnection?.commitText(".", 1)
            checkAutoCapitalization()
        }
        view.findViewById<Button>(R.id.btnPeriod)?.setOnClickListener(periodListener)
        view.findViewById<Button>(R.id.btnPeriodNum)?.setOnClickListener(periodListener)

        // 7. Spacebar Keys
        val spaceListener = View.OnClickListener {
            currentInputConnection?.commitText(" ", 1)
            checkAutoCapitalization()
        }
        listOf(R.id.btnSpaceQwerty, R.id.btnSpaceNum, R.id.btnSpaceEmoji).forEach { id ->
            view.findViewById<Button>(id)?.setOnClickListener(spaceListener)
        }

        // 8. Fast Continuous Backspace for all backspace buttons
        listOf(R.id.btnBackspaceQwerty, R.id.btnBackspaceNum, R.id.btnBackspaceEmoji).forEach { id ->
            view.findViewById<Button>(id)?.setOnTouchListener { _, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        performBackspace()
                        backspaceRepeatJob?.cancel()
                        backspaceRepeatJob = serviceScope.launch {
                            delay(350)
                            var count = 0
                            while (isActive) {
                                performBackspace()
                                count++
                                val speedDelay = if (count > 12) 25L else 45L
                                delay(speedDelay)
                            }
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        backspaceRepeatJob?.cancel()
                        backspaceRepeatJob = null
                        true
                    }
                    else -> false
                }
            }
        }

        // 9. Enter Keys
        val enterListener = View.OnClickListener {
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
            checkAutoCapitalization()
        }
        listOf(R.id.btnEnterQwerty, R.id.btnEnterNum).forEach { id ->
            view.findViewById<Button>(id)?.setOnClickListener(enterListener)
        }

        updateLetterCase(view)
    }

    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return true
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!isCapsLock) {
            isShifted = true
        }
        currentRootView?.let { view ->
            updateLetterCase(view)
            val prefs = getSharedPreferences("butterfly_prefs", Context.MODE_PRIVATE)
            val themeKey = prefs.getString("keyboard_theme", "sky") ?: "sky"
            applyTheme(themeKey, view)
        }
    }

    private fun checkAutoCapitalization() {
        if (isCapsLock) return
        val ic = currentInputConnection ?: return
        val textBefore = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
        if (textBefore.isEmpty() || textBefore.endsWith("\n") || textBefore.endsWith(". ") || textBefore.endsWith("! ") || textBefore.endsWith("? ")) {
            isShifted = true
            currentRootView?.let { updateLetterCase(it) }
        }
    }

    private fun updateLetterCase(rootView: View) {
        val isUpper = isShifted || isCapsLock
        for ((id, charStr) in letterKeysMap) {
            val btn = rootView.findViewById<Button>(id)
            btn?.text = if (isUpper) charStr.uppercase(Locale.US) else charStr.lowercase(Locale.US)
        }

        val palette = getThemePalette(currentThemeKey)
        val btnShift = rootView.findViewById<Button>(R.id.btnShift)
        when {
            isCapsLock -> {
                btnShift?.text = "⇪"
                btnShift?.backgroundTintList = android.content.res.ColorStateList.valueOf(palette.accentColor)
                btnShift?.setTextColor(android.graphics.Color.WHITE)
            }
            isShifted -> {
                btnShift?.text = "⇧"
                btnShift?.backgroundTintList = android.content.res.ColorStateList.valueOf(palette.accentColor)
                btnShift?.setTextColor(android.graphics.Color.WHITE)
            }
            else -> {
                btnShift?.text = "⇧"
                btnShift?.backgroundTintList = android.content.res.ColorStateList.valueOf(palette.ctrlKeyBg)
                btnShift?.setTextColor(palette.ctrlKeyText)
            }
        }
    }

    private fun setKeyboardMode(mode: KeyboardMode, rootView: View) {
        currentMode = mode
        val qwerty = rootView.findViewById<LinearLayout>(R.id.keyboardQwertyView)
        val numbers = rootView.findViewById<LinearLayout>(R.id.keyboardNumbersView)
        val emoji = rootView.findViewById<LinearLayout>(R.id.keyboardEmojiView)

        when (mode) {
            KeyboardMode.LETTERS -> {
                qwerty?.visibility = View.VISIBLE
                numbers?.visibility = View.GONE
                emoji?.visibility = View.GONE
            }
            KeyboardMode.NUMBERS -> {
                qwerty?.visibility = View.GONE
                numbers?.visibility = View.VISIBLE
                emoji?.visibility = View.GONE
            }
            KeyboardMode.EMOJI -> {
                qwerty?.visibility = View.GONE
                numbers?.visibility = View.GONE
                emoji?.visibility = View.VISIBLE
                loadEmojiCategory("smileys", rootView)
            }
        }
    }

    private fun loadEmojiCategory(catKey: String, rootView: View) {
        val gridEmoji = rootView.findViewById<GridLayout>(R.id.gridEmoji) ?: return
        gridEmoji.removeAllViews()

        val emojis = emojiCategories[catKey] ?: return
        val palette = getThemePalette(currentThemeKey)

        // Highlight active category tab button
        val tabs = mapOf(
            "smileys" to R.id.tabEmojiSmileys,
            "people" to R.id.tabEmojiPeople,
            "animals" to R.id.tabEmojiAnimals,
            "food" to R.id.tabEmojiFood,
            "travel" to R.id.tabEmojiTravel,
            "activities" to R.id.tabEmojiActivities,
            "objects" to R.id.tabEmojiObjects,
            "symbols" to R.id.tabEmojiSymbols,
            "flags" to R.id.tabEmojiFlags
        )
        for ((key, tabId) in tabs) {
            val tabBtn = rootView.findViewById<Button>(tabId) ?: continue
            tabBtn.isAllCaps = false
            if (key == catKey) {
                tabBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(palette.accentColor)
                tabBtn.setTextColor(android.graphics.Color.WHITE)
            } else {
                tabBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(palette.ctrlKeyBg)
                tabBtn.setTextColor(palette.ctrlKeyText)
            }
        }

        for (emojiStr in emojis) {
            val cell = TextView(this).apply {
                text = emojiStr
                textSize = 22f
                gravity = android.view.Gravity.CENTER
                includeFontPadding = false
                isAllCaps = false
                transformationMethod = null
                setPadding(0, 0, 0, 0)
                minWidth = 0
                minHeight = 0
                isClickable = true
                isFocusable = true
                background = null
                val outValue = android.util.TypedValue()
                if (theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)) {
                    setBackgroundResource(outValue.resourceId)
                }
                val params = GridLayout.LayoutParams()
                params.width = dpToPx(44)
                params.height = dpToPx(44)
                params.setMargins(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2))
                layoutParams = params
                setOnClickListener {
                    currentInputConnection?.commitText(emojiStr, 1)
                }
            }
            gridEmoji.addView(cell)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
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

            // Pulsing glow animation on recording indicator
            layoutRecordingDotContainer?.let { dot ->
                dotPulseAnimator?.cancel()
                dotPulseAnimator = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.4f).apply {
                    duration = 750
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            }

            recordingTimerJob?.cancel()
            recordingTimerJob = serviceScope.launch {
                var tick = 0
                val density = resources.displayMetrics.density
                val baseHeights = intArrayOf(4, 6, 10, 14, 18, 24, 30, 24, 32, 22, 32, 22, 32, 24, 30, 24, 18, 14, 10, 6, 4)
                while (currentState == KeyboardState.RECORDING) {
                    val elapsedMs = SystemClock.elapsedRealtime() - recordingStartTime
                    val totalSecs = (elapsedMs / 1000).toInt()
                    val mins = totalSecs / 60
                    val secs = totalSecs % 60
                    tvRecordingTimer.text = String.format("%02d:%02d", mins, secs)

                    // Live Waveform audio animation
                    layoutWaveformBars?.let { container ->
                        val amp = try { mediaRecorder?.maxAmplitude ?: 0 } catch (_: Exception) { 0 }
                        val factor = if (amp > 200) (amp / 14000f).coerceIn(0.5f, 1.4f) else 0.75f
                        for (i in 0 until container.childCount.coerceAtMost(baseHeights.size)) {
                            val bar = container.getChildAt(i)
                            val baseH = baseHeights[i]
                            val wave = (Math.sin((tick * 0.35) + (i * 0.6)) * 4.0).toFloat()
                            val hDp = ((baseH * factor) + wave).coerceIn(4f, 34f)
                            val lp = bar.layoutParams
                            lp.height = (hDp * density).toInt()
                            bar.layoutParams = lp
                        }
                    }

                    tick++
                    delay(120)
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
        dotPulseAnimator?.cancel()
        layoutRecordingDotContainer?.alpha = 1f
        cleanupRecorder()
        try { audioFile?.delete() } catch (e: Exception) {}
        setKeyboardState(KeyboardState.IDLE)
        Toast.makeText(this, "Recording cancelled", Toast.LENGTH_SHORT).show()
    }

    private fun stopRecordingAndTranscribe() {
        if (currentState != KeyboardState.RECORDING) return
        recordingTimerJob?.cancel()
        dotPulseAnimator?.cancel()
        layoutRecordingDotContainer?.alpha = 1f
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
        var tgtLang = languages[spinnerTargetLang.selectedItemPosition].first
        if (isTranslateOn && (tgtLang == "auto" || tgtLang.isBlank())) {
            tgtLang = "te" // Default target language to Telugu
        }

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

                        if (isTranslateOn) {
                            if (lastTranslatedText.isNotBlank() && lastTranslatedText != lastOriginalText) {
                                lastFinalText = lastTranslatedText
                            } else {
                                // Fallback: If backend returned raw text, perform direct translation call to target language
                                val reqTarget = if (tgtLang == "auto" || tgtLang == "en") "te" else tgtLang
                                val fallbackTranslated = networkService.translateTextDirect(lastOriginalText, result.language, reqTarget)
                                if (!fallbackTranslated.isNullOrBlank() && fallbackTranslated != lastOriginalText) {
                                    lastTranslatedText = fallbackTranslated
                                    lastFinalText = fallbackTranslated
                                } else {
                                    lastFinalText = lastOriginalText
                                }
                            }
                        } else {
                            lastFinalText = lastOriginalText
                        }

                        // Update Voice Result View
                        if (::lblOriginalHeader.isInitialized) {
                            lblOriginalHeader.text = "You said:"
                            lblOriginalHeader.visibility = View.VISIBLE
                        }
                        lblTranslationHeader.text = "Translation:"
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
                            lastCommittedText = lastFinalText
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

    private fun handleSearchButtonClick() {
        try {
            val ic = currentInputConnection
            val selectedText = ic?.getSelectedText(0)?.toString() ?: ""
            val beforeCursor = ic?.getTextBeforeCursor(1000, 0)?.toString() ?: ""
            val currentInputText = if (selectedText.isNotBlank()) selectedText else beforeCursor
            val query = currentInputText.trim()

            val url = if (query.isNotEmpty()) {
                "https://duckduckgo.com/?q=" + Uri.encode(query)
            } else {
                "https://duckduckgo.com/"
            }

            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("ButterflyIME", "Search button error: ${e.message}", e)
            Toast.makeText(this, "Unable to open browser", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleAiAnswerButtonClick() {
        if (isAiAnswerLoading) {
            return
        }

        val ic = currentInputConnection
        val selectedText = ic?.getSelectedText(0)?.toString() ?: ""
        val beforeCursor = ic?.getTextBeforeCursor(1000, 0)?.toString() ?: ""
        val currentInputText = if (selectedText.isNotBlank()) selectedText else beforeCursor
        val question = currentInputText.trim()

        if (question.isEmpty()) {
            Toast.makeText(this, "Enter a question first.", Toast.LENGTH_SHORT).show()
            tvInsertedNotice.text = "⚠️ Enter a question first."
            tvInsertedNotice.visibility = View.VISIBLE
            if (::lblOriginalHeader.isInitialized) {
                lblOriginalHeader.visibility = View.GONE
            }
            tvOriginalText.visibility = View.GONE
            lblTranslationHeader.visibility = View.GONE
            tvTranslatedText.visibility = View.GONE
            layoutVoiceResult.visibility = View.VISIBLE
            setKeyboardState(KeyboardState.RESULT)
            return
        }

        isAiAnswerLoading = true

        if (::lblOriginalHeader.isInitialized) {
            lblOriginalHeader.text = "Question:"
            lblOriginalHeader.visibility = View.VISIBLE
        }
        tvOriginalText.text = question
        tvOriginalText.visibility = View.VISIBLE

        lblTranslationHeader.text = "AI Answer:"
        lblTranslationHeader.visibility = View.VISIBLE
        tvTranslatedText.text = "Thinking..."
        tvTranslatedText.visibility = View.VISIBLE

        tvInsertedNotice.visibility = View.GONE
        layoutVoiceResult.visibility = View.VISIBLE
        setKeyboardState(KeyboardState.RESULT)

        val srcLang = languages.getOrNull(spinnerSourceLang.selectedItemPosition)?.first ?: "auto"

        serviceScope.launch {
            try {
                val res = networkService.askAI(question, srcLang)

                if (res.success && res.answer.isNotBlank()) {
                    tvTranslatedText.text = res.answer
                    lastFinalText = res.answer
                    tvInsertedNotice.text = "✓ AI Response Ready"
                    tvInsertedNotice.visibility = View.VISIBLE
                } else {
                    val rawError = res.error ?: ""
                    val errorMsg = when {
                        rawError.contains("API key", ignoreCase = true) ||
                        rawError.contains("OPENAI_API_KEY", ignoreCase = true) ||
                        rawError.contains("Configure your OpenAI", ignoreCase = true) -> {
                            "Configure your OpenAI API key in Settings."
                        }
                        rawError.isNotBlank() && !rawError.contains("HTTP", ignoreCase = true) -> {
                            rawError
                        }
                        else -> {
                            "AI service unavailable. Check your connection."
                        }
                    }
                    tvTranslatedText.text = errorMsg
                    Toast.makeText(this@ButterflyInputMethodService, errorMsg, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("ButterflyIME", "AI Answer error: ${e.message}", e)
                val errorMsg = "AI service unavailable. Check your connection."
                tvTranslatedText.text = errorMsg
                Toast.makeText(this@ButterflyInputMethodService, errorMsg, Toast.LENGTH_LONG).show()
            } finally {
                isAiAnswerLoading = false
            }
        }
    }

    private fun handleAiAsk() {
        handleAiAnswerButtonClick()
    }

    private fun retranslateAndUpdate() {
        if (!::spinnerSourceLang.isInitialized || !::spinnerTargetLang.isInitialized) return

        val srcLang = languages.getOrNull(spinnerSourceLang.selectedItemPosition)?.first ?: "auto"
        val tgtLang = languages.getOrNull(spinnerTargetLang.selectedItemPosition)?.first ?: "te"
        val targetLangName = languages.getOrNull(spinnerTargetLang.selectedItemPosition)?.second ?: "Target Language"

        retranslateJob?.cancel()

        if (!isTranslateOn) {
            if (lastOriginalText.isNotBlank()) {
                val textToRestore = lastOriginalText
                lastFinalText = textToRestore
                tvOriginalText.text = lastOriginalText
                lblTranslationHeader.visibility = View.GONE
                tvTranslatedText.visibility = View.GONE

                val ic = currentInputConnection
                if (ic != null && lastCommittedText.isNotBlank()) {
                    ic.deleteSurroundingText(lastCommittedText.length, 0)
                    ic.commitText(textToRestore, 1)
                    lastCommittedText = textToRestore
                    tvInsertedNotice.text = "✓ Showing original text (Trans: OFF)"
                    tvInsertedNotice.visibility = View.VISIBLE
                }
            }
            return
        }

        val ic = currentInputConnection
        val textToTranslate = when {
            lastOriginalText.isNotBlank() -> lastOriginalText
            ic != null -> ic.getTextBeforeCursor(1000, 0)?.toString()?.trim() ?: ""
            else -> ""
        }

        if (textToTranslate.isBlank()) return

        tvInsertedNotice.text = "🔄 Translating to $targetLangName..."
        tvInsertedNotice.visibility = View.VISIBLE

        retranslateJob = serviceScope.launch {
            try {
                val reqTarget = if (tgtLang == "auto") "te" else tgtLang
                val translated = networkService.translateTextDirect(textToTranslate, srcLang, reqTarget)

                if (!translated.isNullOrBlank()) {
                    lastTranslatedText = translated
                    lastFinalText = translated

                    tvOriginalText.text = textToTranslate
                    lblTranslationHeader.visibility = View.VISIBLE
                    tvTranslatedText.visibility = View.VISIBLE
                    tvTranslatedText.text = translated

                    val currentIc = currentInputConnection
                    if (currentIc != null) {
                        if (lastCommittedText.isNotBlank()) {
                            currentIc.deleteSurroundingText(lastCommittedText.length, 0)
                        }
                        currentIc.commitText(translated, 1)
                        lastCommittedText = translated
                        tvInsertedNotice.text = "✓ Automatically translated to $targetLangName"
                        tvInsertedNotice.visibility = View.VISIBLE
                    }
                } else {
                    tvInsertedNotice.text = "⚠️ Translation failed. Check connection."
                    tvInsertedNotice.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                Log.e("ButterflyIME", "Retranslate error: ${e.message}", e)
                tvInsertedNotice.text = "⚠️ Translation error: ${e.localizedMessage}"
                tvInsertedNotice.visibility = View.VISIBLE
            }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (currentState == KeyboardState.RECORDING) {
            cancelRecording()
        }
    }

    private fun applyTheme(themeKey: String, rootRootView: View) {
        currentThemeKey = themeKey
        val palette = getThemePalette(themeKey)

        val layoutKeyboardRoot = rootRootView.findViewById<LinearLayout>(R.id.layoutKeyboardRoot)
        val layoutHeaderBanner = rootRootView.findViewById<LinearLayout>(R.id.layoutHeaderBanner)

        if (themeKey == "sky") {
            layoutKeyboardRoot?.setBackgroundResource(R.drawable.bg_keyboard_soft_sky)
            layoutHeaderBanner?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        } else {
            layoutKeyboardRoot?.setBackgroundColor(palette.rootBg)
            layoutHeaderBanner?.setBackgroundColor(palette.headerBg)
        }

        // 1. Header Title & Subtitle Text Color
        val tvHeaderTitle = rootRootView.findViewById<TextView>(R.id.tvHeaderTitle)
        tvHeaderTitle?.setTextColor(palette.ctrlKeyText)
        val tvHeaderSubtitle = rootRootView.findViewById<TextView>(R.id.tvHeaderSubtitle)
        tvHeaderSubtitle?.setTextColor(if (palette.isDark) android.graphics.Color.parseColor("#94A3B8") else android.graphics.Color.parseColor("#64748B"))

        // 2. Feature Cards Backgrounds & Titles (Voice | Search | AI Answer)
        val btnCardVoice = rootRootView.findViewById<View>(R.id.btnCardVoice)
        val btnCardSearch = rootRootView.findViewById<View>(R.id.btnCardSearch)
        val btnCardAI = rootRootView.findViewById<View>(R.id.btnCardAI)

        val tvCardVoiceTitle = rootRootView.findViewById<TextView>(R.id.tvCardVoiceTitle)
        val tvCardSearchTitle = rootRootView.findViewById<TextView>(R.id.tvCardSearchTitle)
        val tvCardAITitle = rootRootView.findViewById<TextView>(R.id.tvCardAITitle)

        if (palette.isDark) {
            btnCardVoice?.setBackgroundResource(R.drawable.bg_lang_pill_dark)
            btnCardSearch?.setBackgroundResource(R.drawable.bg_lang_pill_dark)
            btnCardAI?.setBackgroundResource(R.drawable.bg_lang_pill_dark)

            btnCardVoice?.backgroundTintList = android.content.res.ColorStateList.valueOf(palette.cardBg)
            btnCardSearch?.backgroundTintList = android.content.res.ColorStateList.valueOf(palette.cardBg)
            btnCardAI?.backgroundTintList = android.content.res.ColorStateList.valueOf(palette.cardBg)

            val titleColor = if (currentThemeKey == "cyber") android.graphics.Color.parseColor("#00F0FF") else android.graphics.Color.WHITE
            tvCardVoiceTitle?.setTextColor(titleColor)
            tvCardSearchTitle?.setTextColor(titleColor)
            tvCardAITitle?.setTextColor(titleColor)
        } else {
            btnCardVoice?.setBackgroundResource(R.drawable.bg_card_voice)
            btnCardSearch?.setBackgroundResource(R.drawable.bg_card_search)
            btnCardAI?.setBackgroundResource(R.drawable.bg_card_ai)

            btnCardVoice?.backgroundTintList = null
            btnCardSearch?.backgroundTintList = null
            btnCardAI?.backgroundTintList = null

            val titleColor = android.graphics.Color.parseColor("#1E293B")
            tvCardVoiceTitle?.setTextColor(titleColor)
            tvCardSearchTitle?.setTextColor(titleColor)
            tvCardAITitle?.setTextColor(titleColor)
        }

        // 3. Source & Target Language Pill Cards & Labels
        val cardSourceLang = rootRootView.findViewById<View>(R.id.cardSourceLang)
        val cardTargetLang = rootRootView.findViewById<View>(R.id.cardTargetLang)
        val lblSourceLang = rootRootView.findViewById<TextView>(R.id.lblSourceLang)
        val lblTargetLang = rootRootView.findViewById<TextView>(R.id.lblTargetLang)
        val tvLangArrow = rootRootView.findViewById<TextView>(R.id.tvLangArrow)

        if (palette.isDark) {
            cardSourceLang?.setBackgroundResource(R.drawable.bg_lang_pill_dark)
            cardTargetLang?.setBackgroundResource(R.drawable.bg_lang_pill_dark)
            cardSourceLang?.backgroundTintList = android.content.res.ColorStateList.valueOf(palette.cardBg)
            cardTargetLang?.backgroundTintList = android.content.res.ColorStateList.valueOf(palette.cardBg)

            lblSourceLang?.setTextColor(android.graphics.Color.parseColor("#CBD5E1"))
            lblTargetLang?.setTextColor(android.graphics.Color.parseColor("#CBD5E1"))
            tvLangArrow?.setTextColor(if (currentThemeKey == "cyber") android.graphics.Color.parseColor("#00F0FF") else android.graphics.Color.WHITE)
        } else {
            cardSourceLang?.setBackgroundResource(R.drawable.bg_lang_pill)
            cardTargetLang?.setBackgroundResource(R.drawable.bg_lang_pill)
            cardSourceLang?.backgroundTintList = null
            cardTargetLang?.backgroundTintList = null

            lblSourceLang?.setTextColor(android.graphics.Color.parseColor("#64748B"))
            lblTargetLang?.setTextColor(android.graphics.Color.parseColor("#64748B"))
            tvLangArrow?.setTextColor(android.graphics.Color.parseColor("#1E293B"))
        }

        // Tint Header Butterfly Logo & Card Icons to Match Active Theme Palette
        val imgHeaderLogo = rootRootView.findViewById<ImageView>(R.id.imgHeaderLogo)
        val tvCardVoiceIcon = rootRootView.findViewById<ImageView>(R.id.tvCardVoiceIcon)
        val tvCardSearchIcon = rootRootView.findViewById<ImageView>(R.id.tvCardSearchIcon)
        val tvCardAIIcon = rootRootView.findViewById<ImageView>(R.id.tvCardAIIcon)
        val imgSourceLangIcon = rootRootView.findViewById<ImageView>(R.id.imgSourceLangIcon)
        val imgTargetLangIcon = rootRootView.findViewById<ImageView>(R.id.imgTargetLangIcon)

        val cardIconTint = android.content.res.ColorStateList.valueOf(palette.accentColor)
        listOf(imgHeaderLogo, tvCardVoiceIcon, tvCardSearchIcon, tvCardAIIcon, imgSourceLangIcon, imgTargetLangIcon).forEach { iconView ->
            iconView?.imageTintList = cardIconTint
        }

        // Refresh Language Spinners
        val spinnerSource = rootRootView.findViewById<Spinner>(R.id.spinnerSourceLang)
        val spinnerTarget = rootRootView.findViewById<Spinner>(R.id.spinnerTargetLang)
        (spinnerSource?.adapter as? ArrayAdapter<*>)?.notifyDataSetChanged()
        (spinnerTarget?.adapter as? ArrayAdapter<*>)?.notifyDataSetChanged()
        val spinnerTextColor = if (palette.isDark) (if (currentThemeKey == "cyber") android.graphics.Color.parseColor("#00F0FF") else android.graphics.Color.WHITE) else android.graphics.Color.parseColor("#1E293B")
        (spinnerSource?.selectedView as? TextView)?.setTextColor(spinnerTextColor)
        (spinnerTarget?.selectedView as? TextView)?.setTextColor(spinnerTextColor)

        // 4. Action Toolbar Buttons (⌨, Snippets, Switch IME, Gear)
        val toolbarIds = listOf(R.id.btnToggleKeyboard, R.id.btnSnippets, R.id.btnSwitchIme, R.id.btnCycleTheme)
        for (id in toolbarIds) {
            val btn = rootRootView.findViewById<Button>(id)
            if (btn != null) {
                if (themeKey != "sky") {
                    btn.backgroundTintList = android.content.res.ColorStateList.valueOf(palette.ctrlKeyBg)
                } else {
                    btn.backgroundTintList = null
                }
                btn.setTextColor(palette.ctrlKeyText)
            }
        }

        // 5. Voice Result & AI Answer Card Styling
        val layoutVoiceResult = rootRootView.findViewById<View>(R.id.layoutVoiceResult)
        if (layoutVoiceResult != null) {
            if (palette.isDark) {
                layoutVoiceResult.setBackgroundColor(palette.cardBg)
            } else {
                layoutVoiceResult.setBackgroundColor(android.graphics.Color.parseColor("#F1F5F9"))
            }

            val lblOriginalHeader = rootRootView.findViewById<TextView>(R.id.lblOriginalHeader)
            lblOriginalHeader?.setTextColor(palette.ctrlKeyText)

            val tvOriginalText = rootRootView.findViewById<TextView>(R.id.tvOriginalText)
            tvOriginalText?.setTextColor(palette.keyText)

            val lblTranslationHeader = rootRootView.findViewById<TextView>(R.id.lblTranslationHeader)
            lblTranslationHeader?.setTextColor(if (palette.isDark) android.graphics.Color.parseColor("#34D399") else android.graphics.Color.parseColor("#10B981"))

            val tvTranslatedText = rootRootView.findViewById<TextView>(R.id.tvTranslatedText)
            tvTranslatedText?.setTextColor(palette.keyText)

            val tvInsertedNotice = rootRootView.findViewById<TextView>(R.id.tvInsertedNotice)
            tvInsertedNotice?.setTextColor(if (palette.isDark) android.graphics.Color.parseColor("#6EE7B7") else android.graphics.Color.parseColor("#059669"))

            val btnCopyText = rootRootView.findViewById<Button>(R.id.btnCopyText)
            val btnSpeakText = rootRootView.findViewById<Button>(R.id.btnSpeakText)
            val btnDismissResult = rootRootView.findViewById<Button>(R.id.btnDismissResult)

            for (resBtn in listOf(btnCopyText, btnSpeakText, btnDismissResult)) {
                if (resBtn != null) {
                    if (themeKey != "sky") {
                        resBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(palette.ctrlKeyBg)
                    } else {
                        resBtn.backgroundTintList = null
                    }
                    if (resBtn == btnDismissResult && palette.isDark) {
                        resBtn.setTextColor(android.graphics.Color.parseColor("#F87171"))
                    } else {
                        resBtn.setTextColor(palette.ctrlKeyText)
                    }
                }
            }
        }

        // 6. Recording & Processing State Cards Styling
        val density = resources.displayMetrics.density
        val layoutRecordingState = rootRootView.findViewById<View>(R.id.layoutRecordingState)
        val tvRecordingTimer = rootRootView.findViewById<TextView>(R.id.tvRecordingTimer)
        val tvRecordingTitle = rootRootView.findViewById<TextView>(R.id.tvRecordingTitle)
        val btnCancelRecording = rootRootView.findViewById<TextView>(R.id.btnCancelRecording)
        val btnStopRecording = rootRootView.findViewById<View>(R.id.btnStopRecording)
        val layoutRecordingDotContainer = rootRootView.findViewById<View>(R.id.layoutRecordingDotContainer)
        val viewRecordingDot = rootRootView.findViewById<View>(R.id.viewRecordingDot)
        val layoutWaveformBars = rootRootView.findViewById<LinearLayout>(R.id.layoutWaveformBars)

        if (layoutRecordingState != null) {
            // A. Themed Card Background & Stroke
            val cardDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24f * density
                if (palette.isDark) {
                    setColor(palette.cardBg)
                    val strokeColor = when (themeKey) {
                        "cyber" -> Color.parseColor("#4C237A")
                        "sunset" -> Color.parseColor("#4338CA")
                        "oled" -> Color.parseColor("#27272A")
                        else -> Color.parseColor("#334155")
                    }
                    setStroke((1f * density).toInt(), strokeColor)
                } else {
                    setColor(Color.WHITE)
                    val strokeColor = if (themeKey == "light") Color.parseColor("#CBD5E1") else Color.parseColor("#E2E8F0")
                    setStroke((1f * density).toInt(), strokeColor)
                }
            }
            layoutRecordingState.background = cardDrawable

            // B. Themed Stop Recording Button Gradient
            val (stopGradStart, stopGradEnd) = when (themeKey) {
                "oled" -> Pair(Color.parseColor("#059669"), Color.parseColor("#10B981"))
                "cyber" -> Pair(Color.parseColor("#FF007A"), Color.parseColor("#7928CA"))
                "sunset" -> Pair(Color.parseColor("#EA580C"), Color.parseColor("#F59E0B"))
                "light" -> Pair(Color.parseColor("#4F46E5"), Color.parseColor("#7C3AED"))
                "dark" -> Pair(Color.parseColor("#2563EB"), Color.parseColor("#38BDF8"))
                else -> Pair(Color.parseColor("#0077FF"), Color.parseColor("#00C2FE")) // sky
            }
            val stopBtnDrawable = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(stopGradStart, stopGradEnd)
            ).apply {
                cornerRadius = 26f * density
            }
            btnStopRecording?.background = stopBtnDrawable

            // C. Themed Waveform Bars Color
            val waveformColor = when (themeKey) {
                "oled" -> Color.parseColor("#10B981")
                "cyber" -> Color.parseColor("#00F0FF")
                "sunset" -> Color.parseColor("#F59E0B")
                "light" -> Color.parseColor("#4F46E5")
                "dark" -> Color.parseColor("#60A5FA")
                else -> Color.parseColor("#38BDF8") // sky
            }
            if (layoutWaveformBars != null) {
                val wfTint = ColorStateList.valueOf(waveformColor)
                for (i in 0 until layoutWaveformBars.childCount) {
                    layoutWaveformBars.getChildAt(i)?.backgroundTintList = wfTint
                }
            }

            // D. Themed Recording Indicator Halo & Dot
            val haloColor = if (palette.isDark) {
                when (themeKey) {
                    "cyber" -> Color.parseColor("#4A0D2A")
                    "sunset" -> Color.parseColor("#451420")
                    "oled" -> Color.parseColor("#3B1215")
                    else -> Color.parseColor("#37131B")
                }
            } else {
                Color.parseColor("#FEE2E2")
            }
            layoutRecordingDotContainer?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(haloColor)
            }

            val innerDotColor = when (themeKey) {
                "cyber" -> Color.parseColor("#FF007A")
                "sunset" -> Color.parseColor("#F43F5E")
                else -> Color.parseColor("#EF4444")
            }
            viewRecordingDot?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(innerDotColor)
            }

            // E. Themed Title, Timer & Cancel Button Text
            when (themeKey) {
                "cyber" -> {
                    tvRecordingTitle?.setTextColor(Color.parseColor("#FF007A"))
                    tvRecordingTimer?.setTextColor(Color.parseColor("#00F0FF"))
                    btnCancelRecording?.setTextColor(Color.parseColor("#E2E8F0"))
                }
                "sunset" -> {
                    tvRecordingTitle?.setTextColor(Color.parseColor("#FED7AA"))
                    tvRecordingTimer?.setTextColor(Color.parseColor("#FDE047"))
                    btnCancelRecording?.setTextColor(Color.parseColor("#FDE047"))
                }
                "oled" -> {
                    tvRecordingTitle?.setTextColor(Color.parseColor("#E4E4E7"))
                    tvRecordingTimer?.setTextColor(Color.WHITE)
                    btnCancelRecording?.setTextColor(Color.parseColor("#A1A1AA"))
                }
                "dark" -> {
                    tvRecordingTitle?.setTextColor(Color.parseColor("#F1F5F9"))
                    tvRecordingTimer?.setTextColor(Color.WHITE)
                    btnCancelRecording?.setTextColor(Color.parseColor("#94A3B8"))
                }
                "light" -> {
                    tvRecordingTitle?.setTextColor(Color.parseColor("#1E293B"))
                    tvRecordingTimer?.setTextColor(Color.parseColor("#0F172A"))
                    btnCancelRecording?.setTextColor(Color.parseColor("#64748B"))
                }
                else -> { // "sky"
                    tvRecordingTitle?.setTextColor(Color.parseColor("#334155"))
                    tvRecordingTimer?.setTextColor(Color.parseColor("#0F172A"))
                    btnCancelRecording?.setTextColor(Color.parseColor("#64748B"))
                }
            }
        }

        val layoutProcessingState = rootRootView.findViewById<View>(R.id.layoutProcessingState)
        val tvProcessingStatus = rootRootView.findViewById<TextView>(R.id.tvProcessingStatus)
        if (layoutProcessingState != null) {
            if (palette.isDark) {
                layoutProcessingState.setBackgroundColor(android.graphics.Color.parseColor("#382009"))
                tvProcessingStatus?.setTextColor(android.graphics.Color.parseColor("#FDE68A"))
            } else {
                layoutProcessingState.setBackgroundColor(android.graphics.Color.parseColor("#FFFBEB"))
                tvProcessingStatus?.setTextColor(android.graphics.Color.parseColor("#B45309"))
            }
        }

        // 7. Keypad Letter Keys
        val allKeyIds = letterKeysMap.keys + numberKeysMap.keys + listOf(
            R.id.btnComma, R.id.btnPeriod, R.id.btnCommaNum, R.id.btnPeriodNum
        )

        for (id in allKeyIds) {
            val btn = rootRootView.findViewById<Button>(id)
            if (btn != null) {
                if (themeKey != "sky") {
                    btn.backgroundTintList = android.content.res.ColorStateList.valueOf(palette.keyBg)
                } else {
                    btn.backgroundTintList = null
                }
                btn.setTextColor(palette.keyText)
            }
        }

        // 8. Keypad Control Keys
        val controlKeyIds = listOf(
            R.id.btnShift, R.id.btnNumMode, R.id.btnEmojiMode, R.id.btnEmojiModeNum,
            R.id.btnAbcMode, R.id.btnAbcFromEmojiBottom,
            R.id.btnSpaceQwerty, R.id.btnSpaceNum, R.id.btnSpaceEmoji,
            R.id.btnBackspaceQwerty, R.id.btnBackspaceNum, R.id.btnBackspaceEmoji,
            R.id.btnEnterQwerty, R.id.btnEnterNum,
            R.id.tabEmojiSmileys, R.id.tabEmojiPeople, R.id.tabEmojiAnimals,
            R.id.tabEmojiFood, R.id.tabEmojiTravel, R.id.tabEmojiActivities,
            R.id.tabEmojiObjects, R.id.tabEmojiSymbols, R.id.tabEmojiFlags
        )

        for (id in controlKeyIds) {
            val view = rootRootView.findViewById<View>(id)
            if (view is Button) {
                if (id == R.id.btnEnterQwerty || id == R.id.btnEnterNum) {
                    view.backgroundTintList = android.content.res.ColorStateList.valueOf(palette.accentColor)
                    view.setTextColor(android.graphics.Color.WHITE)
                } else if (themeKey != "sky") {
                    view.backgroundTintList = android.content.res.ColorStateList.valueOf(palette.ctrlKeyBg)
                    view.setTextColor(palette.ctrlKeyText)
                } else {
                    view.backgroundTintList = null
                    view.setTextColor(palette.ctrlKeyText)
                }
            } else if (view is ImageButton) {
                if (themeKey != "sky") {
                    view.backgroundTintList = android.content.res.ColorStateList.valueOf(palette.ctrlKeyBg)
                } else {
                    view.backgroundTintList = null
                }
                view.setColorFilter(palette.ctrlKeyText)
            }
        }

        updateLetterCase(rootRootView)

        val btnThemeView = rootRootView.findViewById<Button>(R.id.btnCycleTheme)
        if (btnThemeView != null) {
            btnThemeView.text = "⚙"
        }

        if (::btnTapToSpeak.isInitialized && currentState == KeyboardState.IDLE) {
            btnTapToSpeak.setBackgroundColor(palette.accentColor)
        }
    }

    private fun getThemePalette(themeKey: String): ThemePalette {
        return when (themeKey) {
            "sky" -> ThemePalette(
                rootBg = android.graphics.Color.parseColor("#E6F2FF"),
                cardBg = android.graphics.Color.parseColor("#FFFFFF"),
                headerBg = android.graphics.Color.parseColor("#E6F2FF"),
                keyBg = android.graphics.Color.parseColor("#FFFFFF"),
                keyText = android.graphics.Color.parseColor("#1E293B"),
                ctrlKeyBg = android.graphics.Color.parseColor("#DCEBFE"),
                ctrlKeyText = android.graphics.Color.parseColor("#1D4ED8"),
                accentColor = android.graphics.Color.parseColor("#2563EB"),
                isDark = false
            )
            "light" -> ThemePalette(
                rootBg = android.graphics.Color.parseColor("#E2E8F0"),
                cardBg = android.graphics.Color.parseColor("#FFFFFF"),
                headerBg = android.graphics.Color.parseColor("#E2E8F0"),
                keyBg = android.graphics.Color.parseColor("#FFFFFF"),
                keyText = android.graphics.Color.parseColor("#0F172A"),
                ctrlKeyBg = android.graphics.Color.parseColor("#CBD5E1"),
                ctrlKeyText = android.graphics.Color.parseColor("#4F46E5"),
                accentColor = android.graphics.Color.parseColor("#4F46E5"),
                isDark = false
            )
            "oled" -> ThemePalette(
                rootBg = android.graphics.Color.parseColor("#000000"),
                cardBg = android.graphics.Color.parseColor("#121212"),
                headerBg = android.graphics.Color.parseColor("#121212"),
                keyBg = android.graphics.Color.parseColor("#1F1F23"),
                keyText = android.graphics.Color.parseColor("#FFFFFF"),
                ctrlKeyBg = android.graphics.Color.parseColor("#27272A"),
                ctrlKeyText = android.graphics.Color.parseColor("#34D399"),
                accentColor = android.graphics.Color.parseColor("#10B981"),
                isDark = true
            )
            "cyber" -> ThemePalette(
                rootBg = android.graphics.Color.parseColor("#180828"),
                cardBg = android.graphics.Color.parseColor("#27123E"),
                headerBg = android.graphics.Color.parseColor("#27123E"),
                keyBg = android.graphics.Color.parseColor("#3B1B5D"),
                keyText = android.graphics.Color.parseColor("#00F0FF"),
                ctrlKeyBg = android.graphics.Color.parseColor("#4C237A"),
                ctrlKeyText = android.graphics.Color.parseColor("#FF007A"),
                accentColor = android.graphics.Color.parseColor("#FF007A"),
                isDark = true
            )
            "sunset" -> ThemePalette(
                rootBg = android.graphics.Color.parseColor("#1E1B4B"),
                cardBg = android.graphics.Color.parseColor("#2E1065"),
                headerBg = android.graphics.Color.parseColor("#2E1065"),
                keyBg = android.graphics.Color.parseColor("#3730A3"),
                keyText = android.graphics.Color.parseColor("#FFFFFF"),
                ctrlKeyBg = android.graphics.Color.parseColor("#4338CA"),
                ctrlKeyText = android.graphics.Color.parseColor("#FDE047"),
                accentColor = android.graphics.Color.parseColor("#F59E0B"),
                isDark = true
            )
            else -> ThemePalette( // "dark"
                rootBg = android.graphics.Color.parseColor("#0F172A"),
                cardBg = android.graphics.Color.parseColor("#1E293B"),
                headerBg = android.graphics.Color.parseColor("#1E293B"),
                keyBg = android.graphics.Color.parseColor("#334155"),
                keyText = android.graphics.Color.parseColor("#FFFFFF"),
                ctrlKeyBg = android.graphics.Color.parseColor("#2D3748"),
                ctrlKeyText = android.graphics.Color.parseColor("#60A5FA"),
                accentColor = android.graphics.Color.parseColor("#3B82F6"),
                isDark = true
            )
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

data class ThemePalette(
    val rootBg: Int,
    val cardBg: Int,
    val headerBg: Int,
    val keyBg: Int,
    val keyText: Int,
    val ctrlKeyBg: Int,
    val ctrlKeyText: Int,
    val accentColor: Int,
    val isDark: Boolean = false
)
