package com.butterflyai.keyboard

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
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
    private var btnOpenWeb: Button? = null
    private var layoutWebLink: LinearLayout? = null
    private var tvWebLinkTitle: TextView? = null
    private var currentAiSourceUrl: String? = null
    private var currentAiQuestion: String = ""

    private var isAiAnswerLoading = false

    // In-Keyboard Search Panel Controls
    private lateinit var layoutSearchPanel: LinearLayout
    private lateinit var tvSearchPanelTitle: TextView
    private lateinit var btnCloseSearchPanel: Button
    private lateinit var tvSearchStatus: TextView
    private lateinit var layoutSearchResultsContainer: LinearLayout
    private var isWebSearchLoading = false


    private lateinit var btnToggleKeyboard: Button
    private lateinit var keyboardKeysLayout: LinearLayout
    private var scrollSuggestions: HorizontalScrollView? = null
    private var layoutSuggestionsContainer: LinearLayout? = null
    private val currentTypingWord = StringBuilder()
    private lateinit var spinnerSourceLang: Spinner
    private lateinit var spinnerTargetLang: Spinner
    private lateinit var btnCycleTheme: ImageButton
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

    private val defaultQuickPhrases = listOf(
        "Hi", "Hello", "How are you?", "What are you doing?", "I am fine",
        "Thank you", "Thanks a lot", "Good morning", "Good night", "Good evening",
        "Where are you?", "Call you later", "I'm busy right now", "See you soon",
        "Take care", "Ok", "Sure", "No problem", "Yes", "No", "Please call me",
        "Can we talk?", "I'm on my way", "All the best", "Sounds good", "Have a nice day"
    )

    private val wordDictionary: List<String> = listOf(
        // High-frequency H words (prioritized for user request: "hi, hello, how, have")
        "hi", "hello", "how", "have", "here", "help", "hope", "hey", "home", "happy",
        "has", "had", "having", "hear", "heard", "hard", "high", "huge", "hand", "hands",
        "hold", "hour", "hours", "house", "heart", "head", "health", "half", "hate", "hot",
        "hit", "hair", "happen", "happened", "history", "holiday", "honest", "hospital", "human",
        "hurry", "husband",
        // A words
        "and", "are", "about", "all", "also", "always", "any", "after", "again", "am", "an",
        "as", "at", "ask", "asked", "able", "above", "across", "action", "actually", "almost",
        "alone", "already", "although", "among", "amount", "answer", "anyone", "anything", "anyway",
        "app", "apply", "area", "around", "arrive", "art", "away", "awesome",
        // B words
        "be", "been", "but", "by", "back", "because", "before", "being", "best", "better",
        "between", "both", "busy", "bring", "brother", "build", "building", "built", "business",
        "buy", "bye", "beautiful", "believe", "big", "book", "boy", "break", "bad", "baby",
        // C words
        "can", "call", "called", "calling", "came", "come", "coming", "could", "care", "case",
        "change", "check", "city", "clear", "clearly", "close", "company", "complete", "continue",
        "cool", "copy", "correct", "cost", "country", "couple", "course", "create", "current",
        // D words
        "do", "did", "does", "done", "doing", "don't", "day", "days", "dear", "different",
        "difficult", "dinner", "direct", "doctor", "down", "drive", "during", "dead", "deal",
        // E words
        "every", "everyone", "everything", "each", "early", "easy", "easily", "eat", "email",
        "end", "enjoy", "enough", "enter", "even", "evening", "ever", "everywhere", "example",
        "excellent", "excited", "expect", "experience", "explain",
        // F words
        "for", "from", "fine", "find", "first", "feel", "few", "family", "fast", "favorite",
        "final", "finally", "finish", "finished", "follow", "food", "free", "friend", "friends",
        "full", "fun", "funny", "future",
        // G words
        "good", "great", "get", "getting", "go", "going", "give", "given", "giving", "glad",
        "god", "gone", "got", "game", "girl", "group", "grow", "guess", "guy", "guys",
        // I words
        "i", "i'm", "i'll", "i've", "is", "it", "its", "in", "if", "into", "idea", "important",
        "inside", "instead", "interest", "interesting", "issue",
        // J words
        "just", "job", "join", "joke", "journey", "joy", "judge", "jump",
        // K words
        "know", "knowing", "known", "knows", "keep", "keeping", "key", "kind", "knew", "kid", "kids", "kitchen",
        // L words
        "like", "look", "looking", "love", "later", "let", "let's", "last", "leave", "left",
        "life", "light", "line", "listen", "little", "live", "living", "location", "long", "lot", "lots", "lunch",
        // M words
        "me", "my", "myself", "more", "make", "making", "many", "much", "must", "may", "maybe",
        "mean", "meet", "meeting", "message", "might", "minute", "minutes", "miss", "money",
        "month", "morning", "most", "mother", "move", "music",
        // N words
        "no", "not", "now", "new", "name", "need", "needed", "needs", "never", "next", "nice",
        "night", "nine", "normal", "nothing", "number",
        // O words
        "ok", "okay", "on", "of", "or", "one", "ones", "our", "out", "outside", "other", "others",
        "only", "open", "order", "over", "own", "office", "often", "once", "online",
        // P words
        "please", "people", "part", "party", "pay", "person", "phone", "photo", "picture",
        "place", "plan", "play", "point", "possible", "post", "power", "problem", "problems",
        "program", "project", "put",
        // Q words
        "quick", "quickly", "question", "questions", "quite", "quality", "quiet",
        // R words
        "really", "right", "read", "reading", "ready", "real", "reason", "remember", "reply",
        "report", "rest", "result", "return", "road", "room", "run", "running",
        // S words
        "so", "see", "seen", "say", "saying", "said", "some", "someone", "something", "same",
        "send", "sending", "sent", "she", "should", "show", "side", "simple", "simply", "since",
        "sleep", "small", "smile", "sorry", "speak", "special", "start", "stay", "still", "stop",
        "story", "student", "study", "such", "sure", "system",
        // T words
        "the", "to", "that", "that's", "this", "there", "there's", "they", "they're", "their",
        "then", "them", "think", "thinking", "time", "times", "take", "taking", "talk", "talking",
        "tell", "than", "thank", "thanks", "thing", "things", "through", "today", "together",
        "tomorrow", "tonight", "too", "true", "try", "trying", "two", "type",
        // U words
        "up", "us", "use", "used", "under", "understand", "until", "upon", "urgent", "user", "usually",
        // V words
        "very", "video", "view", "visit", "voice", "value",
        // W words
        "we", "with", "what", "what's", "when", "where", "who", "which", "why", "will", "would",
        "way", "well", "went", "were", "want", "wanted", "wants", "water", "week", "weekend",
        "welcome", "wish", "without", "word", "words", "work", "working", "world", "worry", "write",
        // Y words
        "you", "you're", "you'll", "you've", "your", "yours", "yourself", "yes", "yeah", "year",
        "years", "yesterday", "yet", "young",
        // Z words
        "zero", "zone", "zoom",
        // Popular Indian chat words transliterated in English
        "namaste", "bagunnara", "ela", "unnaru", "enti", "cheppu", "avunu", "kadu", "telsu",
        "eppudu", "ekkada", "ravali", "vasthunna", "chusthunna", "bhayya", "bro", "dost",
        "kya", "hai", "haan", "theek", "shukriya", "dhanyavadalu", "krupaya", "kaise", "acha", "kal"
    )

    private var isKeyboardGridVisible = true
    private var lastSpokenText = ""
    private var lastSpokenLanguageCode = ""
    private var lastSpokenLanguageName = ""
    private var lastOriginalText = ""
    private var lastOriginalLanguageName = ""
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
        btnOpenWeb = inputView.findViewById(R.id.btnOpenWeb)
        layoutWebLink = inputView.findViewById(R.id.layoutWebLink)
        tvWebLinkTitle = inputView.findViewById(R.id.tvWebLinkTitle)

        btnToggleKeyboard = inputView.findViewById(R.id.btnToggleKeyboard)
        keyboardKeysLayout = inputView.findViewById(R.id.keyboardKeysLayout)
        scrollSuggestions = inputView.findViewById(R.id.scrollSuggestions)
        layoutSuggestionsContainer = inputView.findViewById(R.id.layoutSuggestionsContainer)
        spinnerSourceLang = inputView.findViewById(R.id.spinnerSourceLang)
        spinnerTargetLang = inputView.findViewById(R.id.spinnerTargetLang)

        // Bind Search in-keyboard panel
        layoutSearchPanel = inputView.findViewById(R.id.layoutSearchPanel)
        tvSearchPanelTitle = inputView.findViewById(R.id.tvSearchPanelTitle)
        btnCloseSearchPanel = inputView.findViewById(R.id.btnCloseSearchPanel)
        tvSearchStatus = inputView.findViewById(R.id.tvSearchStatus)
        layoutSearchResultsContainer = inputView.findViewById(R.id.layoutSearchResultsContainer)

        btnCloseSearchPanel.setOnClickListener {
            layoutSearchPanel.visibility = View.GONE
        }

        // Header History Action -> Open Conversation History Activity
        inputView.findViewById<View>(R.id.btnHistory)?.setOnClickListener {
            try {
                val intent = Intent(this, HistoryActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("ButterflyIME", "Open History error: ${e.message}", e)
                Toast.makeText(this, "Unable to open History", Toast.LENGTH_SHORT).show()
            }
        }

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

        val prefs = getSharedPreferences("butterfly_prefs", Context.MODE_PRIVATE)
        val savedSourceCode = prefs.getString("source_lang_code", "auto") ?: "auto"
        val savedSourcePos = languages.indexOfFirst { it.first == savedSourceCode }.let { if (it >= 0) it else 0 }
        spinnerSourceLang.setSelection(savedSourcePos)

        val savedTargetCode = prefs.getString("target_lang_code", "en") ?: "en"
        val savedTargetPos = languages.indexOfFirst { it.first == savedTargetCode }.let { if (it >= 0) it else 2 }
        spinnerTargetLang.setSelection(savedTargetPos) // Default target to English (direct English)

        val spinnerListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!isSpinnerInitialized) return
                if (parent == spinnerSourceLang) {
                    val code = languages.getOrNull(position)?.first ?: "auto"
                    prefs.edit().putString("source_lang_code", code).apply()
                } else if (parent == spinnerTargetLang) {
                    val code = languages.getOrNull(position)?.first ?: "en"
                    prefs.edit().putString("target_lang_code", code).apply()
                }
                retranslateAndUpdate(showEmptyToast = false)
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
            retranslateAndUpdate(showEmptyToast = false)
        }
        updateTranslateToggleUI()

        // Central Directional Arrow (Tap to Translate)
        val tvLangArrow = inputView.findViewById<TextView>(R.id.tvLangArrow)
        tvLangArrow?.setOnClickListener {
            isTranslateOn = true
            updateTranslateToggleUI()
            retranslateAndUpdate(showEmptyToast = true)
        }

        // Tap to Speak Action (IDLE or RESULT state) - Handles both main mic button & Voice Keyboard card
        val startVoiceRecordingAction = View.OnClickListener {
            if (currentState == KeyboardState.IDLE || currentState == KeyboardState.RESULT) {
                startRecording()
            }
        }
        btnTapToSpeak.setOnClickListener(startVoiceRecordingAction)
        tvTapToSpeak.setOnClickListener(startVoiceRecordingAction)
        imgMicIconMain.setOnClickListener(startVoiceRecordingAction)
        inputView.findViewById<View>(R.id.btnCardVoice)?.setOnClickListener(startVoiceRecordingAction)

        // Feature Card 2: Web Search -> In-keyboard search panel via backend /api/search
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

        btnOpenWeb?.setOnClickListener {
            openRelatedWebsite()
        }

        layoutWebLink?.setOnClickListener {
            openRelatedWebsite()
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

        val initialTheme = prefs.getString("keyboard_theme", "sky") ?: "sky"
        applyTheme(initialTheme, inputView)

        setKeyboardState(KeyboardState.IDLE)
        hideSuggestions()
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
                if (::layoutSearchPanel.isInitialized) layoutSearchPanel.visibility = View.GONE
                if (::keyboardKeysLayout.isInitialized) keyboardKeysLayout.visibility = View.VISIBLE
                tvTapToSpeak.text = "🎙  TAP TO SPEAK"
                btnTapToSpeak.setBackgroundColor(getThemePalette(currentThemeKey).accentColor)
            }
            KeyboardState.RECORDING -> {
                layoutActionButtons.visibility = View.GONE
                layoutRecordingState.visibility = View.VISIBLE
                layoutProcessingState.visibility = View.GONE
                layoutVoiceResult.visibility = View.GONE
                if (::layoutSearchPanel.isInitialized) layoutSearchPanel.visibility = View.GONE
                if (::keyboardKeysLayout.isInitialized) keyboardKeysLayout.visibility = View.GONE
            }
            KeyboardState.PROCESSING -> {
                layoutActionButtons.visibility = View.GONE
                layoutRecordingState.visibility = View.GONE
                layoutProcessingState.visibility = View.VISIBLE
                layoutVoiceResult.visibility = View.GONE
                if (::layoutSearchPanel.isInitialized) layoutSearchPanel.visibility = View.GONE
                if (::keyboardKeysLayout.isInitialized) keyboardKeysLayout.visibility = View.GONE
                tvProcessingStatus.text = "⏳ PROCESSING..."
            }
            KeyboardState.RESULT -> {
                layoutActionButtons.visibility = View.VISIBLE
                layoutRecordingState.visibility = View.GONE
                layoutProcessingState.visibility = View.GONE
                layoutVoiceResult.visibility = View.VISIBLE
                if (::layoutSearchPanel.isInitialized) layoutSearchPanel.visibility = View.GONE
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

    private fun triggerKeyHaptic(view: View? = null) {
        try {
            val prefs = getSharedPreferences("butterfly_prefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("haptic_feedback", true)) return

            if (view != null && view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)) {
                return
            }
            val v = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            if (v != null && v.hasVibrator()) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    v.vibrate(android.os.VibrationEffect.createOneShot(18, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(18)
                }
            }
        } catch (_: Exception) {}
    }

    private fun performBackspace() {
        triggerKeyHaptic()
        val ic = currentInputConnection ?: return
        val selectedText = ic.getSelectedText(0)
        if (!selectedText.isNullOrEmpty()) {
            ic.commitText("", 1)
            currentTypingWord.setLength(0)
            hideSuggestions()
        } else {
            ic.deleteSurroundingText(1, 0)
            if (currentTypingWord.isNotEmpty()) {
                currentTypingWord.deleteCharAt(currentTypingWord.length - 1)
            }
            if (currentTypingWord.isNotEmpty()) {
                updateWordSuggestions(currentTypingWord.toString())
            } else {
                hideSuggestions()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupKeyListeners(view: View) {
        // 1. QWERTY Letter Keys
        for ((id, charStr) in letterKeysMap) {
            view.findViewById<Button>(id)?.setOnClickListener { btnView ->
                triggerKeyHaptic(btnView)
                val isUpper = isShifted || isCapsLock
                val textToCommit = if (isUpper) charStr.uppercase(Locale.US) else charStr.lowercase(Locale.US)
                currentInputConnection?.commitText(textToCommit, 1)

                currentTypingWord.append(textToCommit)
                updateWordSuggestions(currentTypingWord.toString())

                if (isShifted && !isCapsLock) {
                    isShifted = false
                    updateLetterCase(view)
                }
            }
        }

        // 2. Shift Key (Tap cycles: Shift -> Caps Lock -> Unshifted)
        view.findViewById<Button>(R.id.btnShift)?.setOnClickListener { btnView ->
            triggerKeyHaptic(btnView)
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
            view.findViewById<Button>(id)?.setOnClickListener { btnView ->
                triggerKeyHaptic(btnView)
                currentInputConnection?.commitText(charStr, 1)
                currentTypingWord.setLength(0)
                hideSuggestions()
            }
        }

        // 4. Layout Mode Toggles (?123 | ABC | 😊)
        val numModeListener = View.OnClickListener { btnView ->
            triggerKeyHaptic(btnView)
            setKeyboardMode(KeyboardMode.NUMBERS, view)
        }
        view.findViewById<Button>(R.id.btnNumMode)?.setOnClickListener(numModeListener)

        val abcModeListener = View.OnClickListener { btnView ->
            triggerKeyHaptic(btnView)
            setKeyboardMode(KeyboardMode.LETTERS, view)
        }
        view.findViewById<Button>(R.id.btnAbcMode)?.setOnClickListener(abcModeListener)
        view.findViewById<Button>(R.id.btnAbcFromEmojiBottom)?.setOnClickListener(abcModeListener)

        val emojiModeListener = View.OnClickListener { btnView ->
            triggerKeyHaptic(btnView)
            setKeyboardMode(KeyboardMode.EMOJI, view)
        }
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

        view.findViewById<Button>(R.id.tabEmojiSmileys)?.setOnClickListener { v -> triggerKeyHaptic(v); loadEmojiCategory("smileys", view) }
        view.findViewById<Button>(R.id.tabEmojiPeople)?.setOnClickListener { v -> triggerKeyHaptic(v); loadEmojiCategory("people", view) }
        view.findViewById<Button>(R.id.tabEmojiAnimals)?.setOnClickListener { v -> triggerKeyHaptic(v); loadEmojiCategory("animals", view) }
        view.findViewById<Button>(R.id.tabEmojiFood)?.setOnClickListener { v -> triggerKeyHaptic(v); loadEmojiCategory("food", view) }
        view.findViewById<Button>(R.id.tabEmojiTravel)?.setOnClickListener { v -> triggerKeyHaptic(v); loadEmojiCategory("travel", view) }
        view.findViewById<Button>(R.id.tabEmojiActivities)?.setOnClickListener { v -> triggerKeyHaptic(v); loadEmojiCategory("activities", view) }
        view.findViewById<Button>(R.id.tabEmojiObjects)?.setOnClickListener { v -> triggerKeyHaptic(v); loadEmojiCategory("objects", view) }
        view.findViewById<Button>(R.id.tabEmojiSymbols)?.setOnClickListener { v -> triggerKeyHaptic(v); loadEmojiCategory("symbols", view) }
        view.findViewById<Button>(R.id.tabEmojiFlags)?.setOnClickListener { v -> triggerKeyHaptic(v); loadEmojiCategory("flags", view) }

        // 6. Punctuation Keys (, and .)
        val commaListener = View.OnClickListener { btnView ->
            triggerKeyHaptic(btnView)
            currentInputConnection?.commitText(",", 1)
            currentTypingWord.setLength(0)
            hideSuggestions()
        }
        view.findViewById<Button>(R.id.btnComma)?.setOnClickListener(commaListener)
        view.findViewById<Button>(R.id.btnCommaNum)?.setOnClickListener(commaListener)

        val periodListener = View.OnClickListener { btnView ->
            triggerKeyHaptic(btnView)
            currentInputConnection?.commitText(".", 1)
            currentTypingWord.setLength(0)
            hideSuggestions()
            checkAutoCapitalization()
        }
        view.findViewById<Button>(R.id.btnPeriod)?.setOnClickListener(periodListener)
        view.findViewById<Button>(R.id.btnPeriodNum)?.setOnClickListener(periodListener)

        // 7. Spacebar Keys with Double-Tap Shortcut
        var lastSpaceTapTime = 0L
        val spaceListener = View.OnClickListener { btnView ->
            triggerKeyHaptic(btnView)
            val prefs = getSharedPreferences("butterfly_prefs", Context.MODE_PRIVATE)
            val doubleSpaceEnabled = prefs.getBoolean("double_space_period", true)
            val now = System.currentTimeMillis()
            val ic = currentInputConnection
            if (doubleSpaceEnabled && (now - lastSpaceTapTime < 450L) && ic != null) {
                val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
                if (before.endsWith(" ") && !before.endsWith(". ")) {
                    ic.deleteSurroundingText(1, 0)
                    ic.commitText(". ", 1)
                    lastSpaceTapTime = 0L
                    currentTypingWord.setLength(0)
                    hideSuggestions()
                    checkAutoCapitalization()
                    return@OnClickListener
                }
            }
            lastSpaceTapTime = now
            ic?.commitText(" ", 1)
            currentTypingWord.setLength(0)
            hideSuggestions()
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
        val enterListener = View.OnClickListener { btnView ->
            triggerKeyHaptic(btnView)
            currentTypingWord.setLength(0)
            hideSuggestions()
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
        currentTypingWord.setLength(0)
        hideSuggestions()
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
                if (currentTypingWord.isNotEmpty()) {
                    updateWordSuggestions(currentTypingWord.toString())
                } else {
                    hideSuggestions()
                }
            }
            KeyboardMode.NUMBERS -> {
                qwerty?.visibility = View.GONE
                numbers?.visibility = View.VISIBLE
                emoji?.visibility = View.GONE
                if (currentTypingWord.isNotEmpty()) {
                    updateWordSuggestions(currentTypingWord.toString())
                } else {
                    hideSuggestions()
                }
            }
            KeyboardMode.EMOJI -> {
                qwerty?.visibility = View.GONE
                numbers?.visibility = View.GONE
                emoji?.visibility = View.VISIBLE
                hideSuggestions()
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
                    currentTypingWord.setLength(0)
                    hideSuggestions()
                }
            }
            gridEmoji.addView(cell)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun hideSuggestions() {
        layoutSuggestionsContainer?.removeAllViews()
        scrollSuggestions?.visibility = View.GONE
    }

    private fun showDefaultPhrases() {
        hideSuggestions()
    }

    private fun updateWordSuggestions(prefix: String) {
        if (prefix.isBlank()) {
            hideSuggestions()
            return
        }
        val lowerPrefix = prefix.lowercase(Locale.ROOT)

        // 1. Direct phrase matches starting with prefix (e.g. "Hi", "Hello", "How are you?")
        val directPhraseMatches = defaultQuickPhrases.filter { phrase ->
            phrase.startsWith(lowerPrefix, ignoreCase = true)
        }

        // 2. Phrase matches containing words with prefix (e.g. "fine" -> "I am fine")
        val containedPhraseMatches = defaultQuickPhrases.filter { phrase ->
            !phrase.startsWith(lowerPrefix, ignoreCase = true) &&
            phrase.split(" ", "'", "?", "!").any { it.startsWith(lowerPrefix, ignoreCase = true) }
        }

        // 3. Dictionary word suggestions
        val wordMatches = getWordSuggestions(prefix, limit = 15)

        // 4. Combine results without duplicates
        val combined = mutableListOf<String>()
        for (item in directPhraseMatches) {
            combined.add(item)
        }
        for (item in containedPhraseMatches) {
            if (combined.none { it.equals(item, ignoreCase = true) }) {
                combined.add(item)
            }
        }
        for (item in wordMatches) {
            if (combined.none { it.equals(item, ignoreCase = true) }) {
                combined.add(item)
            }
        }

        if (combined.isEmpty()) {
            hideSuggestions()
        } else {
            populateSuggestions(combined)
        }
    }

    private fun getWordSuggestions(prefix: String, limit: Int = 15): List<String> {
        if (prefix.isBlank()) return emptyList()
        val lowerPrefix = prefix.lowercase(Locale.ROOT)
        val isTitleCase = prefix.firstOrNull()?.isUpperCase() == true && prefix.drop(1).all { it.isLowerCase() }
        val isAllCaps = prefix.length > 1 && prefix.all { it.isUpperCase() }

        val matches = wordDictionary
            .filter { it.startsWith(lowerPrefix) }
            .distinct()
            .take(limit)

        return matches.map { word ->
            when {
                isAllCaps -> word.uppercase(Locale.ROOT)
                isTitleCase -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                else -> word
            }
        }
    }

    private fun populateSuggestions(items: List<String>) {
        val container = layoutSuggestionsContainer ?: return
        val scroll = scrollSuggestions ?: return
        container.removeAllViews()

        if (items.isEmpty()) {
            scroll.visibility = View.GONE
            return
        }
        scroll.visibility = View.VISIBLE

        val palette = getThemePalette(currentThemeKey)
        val padH = dpToPx(12)
        val padV = dpToPx(4)
        val marginH = dpToPx(3)
        val minW = dpToPx(36)
        val chipHeight = dpToPx(28)

        for ((index, text) in items.withIndex()) {
            val chip = TextView(this).apply {
                this.text = text
                textSize = 12.5f
                isClickable = true
                isFocusable = true
                gravity = android.view.Gravity.CENTER
                setPadding(padH, padV, padH, padV)
                minWidth = minW
                includeFontPadding = false

                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    chipHeight
                ).apply {
                    setMargins(marginH, 0, marginH, 0)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                layoutParams = lp

                if (palette.isDark) {
                    setBackgroundResource(R.drawable.bg_suggestion_chip_dark)
                    if (index == 0) {
                        backgroundTintList = ColorStateList.valueOf(palette.ctrlKeyBg)
                        setTextColor(palette.ctrlKeyText)
                        setTypeface(null, Typeface.BOLD)
                    } else {
                        backgroundTintList = ColorStateList.valueOf(palette.cardBg)
                        val textColor = if (currentThemeKey == "cyber") Color.parseColor("#00F0FF") else Color.WHITE
                        setTextColor(textColor)
                        setTypeface(null, Typeface.NORMAL)
                    }
                } else {
                    setBackgroundResource(R.drawable.bg_suggestion_chip)
                    if (index == 0) {
                        backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E0F2FE"))
                        setTextColor(Color.parseColor("#0284C7"))
                        setTypeface(null, Typeface.BOLD)
                    } else {
                        backgroundTintList = ColorStateList.valueOf(Color.WHITE)
                        setTextColor(Color.parseColor("#1E293B"))
                        setTypeface(null, Typeface.NORMAL)
                    }
                }

                setOnClickListener {
                    insertSuggestion(text)
                }
            }
            container.addView(chip)
        }

        scroll.post {
            scroll.scrollTo(0, 0)
        }
    }

    private fun insertSuggestion(text: String) {
        val ic = currentInputConnection ?: return
        val typedLen = if (currentTypingWord.isNotEmpty()) {
            currentTypingWord.length
        } else {
            val textBefore = ic.getTextBeforeCursor(25, 0)?.toString() ?: ""
            textBefore.takeLastWhile { it.isLetter() }.length
        }
        if (typedLen > 0) {
            ic.deleteSurroundingText(typedLen, 0)
        }
        ic.commitText("$text ", 1)
        currentTypingWord.setLength(0)
        checkAutoCapitalization()
        hideSuggestions()
    }

    private fun insertWordSuggestion(word: String) {
        insertSuggestion(word)
    }

    private fun insertQuickPhrase(phrase: String) {
        insertSuggestion(phrase)
    }

    private fun checkMicPermission(): Boolean {
        val permission = Manifest.permission.RECORD_AUDIO
        val res = ContextCompat.checkSelfPermission(this, permission)
        if (res != PackageManager.PERMISSION_GRANTED) {
            Log.w("ButterflyIME", "Microphone permission denied. Directing user to SettingsActivity.")
            Toast.makeText(
                this,
                "Microphone permission required for voice recording. Tap to grant in Settings.",
                Toast.LENGTH_LONG
            ).show()
            try {
                val intent = Intent(this, SettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("request_mic_permission", true)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("ButterflyIME", "Failed to launch SettingsActivity: ${e.message}", e)
            }
            return false
        }
        return true
    }

    private fun startRecording() {
        if (currentState != KeyboardState.IDLE && currentState != KeyboardState.RESULT) return
        if (!checkMicPermission()) return

        try {
            lastSpokenText = ""
            lastSpokenLanguageCode = ""
            lastSpokenLanguageName = ""
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
                val baseHeights = intArrayOf(3, 4, 7, 10, 13, 16, 20, 16, 22, 15, 22, 15, 22, 16, 20, 16, 13, 10, 7, 4, 3)
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
                            val wave = (Math.sin((tick * 0.35) + (i * 0.6)) * 3.0).toFloat()
                            val hDp = ((baseH * factor) + wave).coerceIn(3f, 24f)
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
            tgtLang = "en" // Default target language to English
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
                        lastSpokenText = result.originalText
                        lastOriginalText = result.originalText
                        lastTranslatedText = result.translatedText

                        val targetName = languages.getOrNull(spinnerTargetLang.selectedItemPosition)?.second ?: "Target Language"

                        // Resolve authentic spoken language code & name
                        val detectedCode = if (result.language.isNotBlank() && result.language != "auto") result.language else srcLang
                        lastSpokenLanguageCode = detectedCode
                        val spokenTuple = languages.find { it.first.equals(detectedCode, ignoreCase = true) }
                        val spokenName = if (result.languageName.isNotBlank()) result.languageName else (spokenTuple?.second ?: detectedCode.uppercase())
                        lastSpokenLanguageName = spokenName
                        lastOriginalLanguageName = spokenName

                        // If user has "auto" selected on source spinner, auto-sync spinnerSourceLang to the detected spoken language without triggering listener
                        if (srcLang == "auto" && detectedCode != "auto") {
                            val detectedPos = languages.indexOfFirst { it.first.equals(detectedCode, ignoreCase = true) }
                            if (detectedPos > 0) {
                                isSpinnerInitialized = false
                                spinnerSourceLang.setSelection(detectedPos)
                                isSpinnerInitialized = true
                            }
                        }

                        if (isTranslateOn) {
                            val reqTarget = if (tgtLang == "auto" || tgtLang.isBlank()) "en" else tgtLang
                            if (lastTranslatedText.isNotBlank() && lastTranslatedText != lastOriginalText) {
                                lastFinalText = lastTranslatedText
                            } else {
                                // Fallback: If backend returned identical text or translation was missing, translate directly
                                val effectiveSrc = if (lastSpokenLanguageCode.isNotBlank() && lastSpokenLanguageCode != "auto") lastSpokenLanguageCode else srcLang
                                val fallbackTranslated = networkService.translateTextDirect(lastOriginalText, effectiveSrc, reqTarget)
                                if (!fallbackTranslated.isNullOrBlank()) {
                                    lastTranslatedText = fallbackTranslated
                                    lastFinalText = fallbackTranslated
                                } else {
                                    lastFinalText = if (lastTranslatedText.isNotBlank()) lastTranslatedText else lastOriginalText
                                }
                            }

                            // Update Voice Result View: You said (Spoken Language) + Translation
                            val spokenHeader = if (lastSpokenLanguageName.isNotBlank() && lastSpokenLanguageName != "Auto Detect") {
                                "You said ($lastSpokenLanguageName):"
                            } else {
                                "You said:"
                            }
                            if (::lblOriginalHeader.isInitialized) {
                                lblOriginalHeader.text = spokenHeader
                                lblOriginalHeader.visibility = View.VISIBLE
                            }
                            tvOriginalText.text = lastSpokenText
                            tvOriginalText.visibility = View.VISIBLE

                            lblTranslationHeader.text = "Translation ($targetName):"
                            lblTranslationHeader.visibility = View.VISIBLE
                            tvTranslatedText.visibility = View.VISIBLE
                            tvTranslatedText.text = if (lastTranslatedText.isNotBlank()) lastTranslatedText else lastOriginalText
                        } else {
                            lastFinalText = lastOriginalText
                            val spokenHeader = if (lastSpokenLanguageName.isNotBlank() && lastSpokenLanguageName != "Auto Detect") {
                                "You said ($lastSpokenLanguageName):"
                            } else {
                                "You said:"
                            }
                            if (::lblOriginalHeader.isInitialized) {
                                lblOriginalHeader.text = spokenHeader
                                lblOriginalHeader.visibility = View.VISIBLE
                            }
                            tvOriginalText.text = lastSpokenText
                            tvOriginalText.visibility = View.VISIBLE
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
                        notifyHistoryUpdated()

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

    private fun notifyHistoryUpdated() {
        try {
            val intent = Intent("com.butterflyai.keyboard.ACTION_HISTORY_UPDATED").apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)
            Log.d("ButterflyIME", "Broadcast ACTION_HISTORY_UPDATED")
        } catch (e: Exception) {
            Log.w("ButterflyIME", "Failed to broadcast history update: ${e.message}")
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
        if (!::layoutSearchPanel.isInitialized) return

        val ic = currentInputConnection
        val selectedText = ic?.getSelectedText(0)?.toString() ?: ""
        val beforeCursor = ic?.getTextBeforeCursor(1000, 0)?.toString() ?: ""
        val currentInputText = if (selectedText.isNotBlank()) selectedText else beforeCursor
        val query = currentInputText.trim()

        if (query.isEmpty()) {
            Toast.makeText(this, "Type or speak a search query first", Toast.LENGTH_SHORT).show()
            tvSearchPanelTitle.text = "🔍 Web Search"
            tvSearchStatus.text = "⚠️ Type or speak a search query first, then tap Search."
            tvSearchStatus.setTextColor(android.graphics.Color.parseColor("#B45309"))
            tvSearchStatus.visibility = View.VISIBLE
            layoutSearchResultsContainer.removeAllViews()
            layoutVoiceResult.visibility = View.GONE
            layoutSearchPanel.visibility = View.VISIBLE
            return
        }

        layoutVoiceResult.visibility = View.GONE
        layoutSearchPanel.visibility = View.VISIBLE

        tvSearchPanelTitle.text = "🔍 Web Search: $query"
        tvSearchStatus.text = "Searching..."
        tvSearchStatus.setTextColor(android.graphics.Color.parseColor("#475569"))
        tvSearchStatus.visibility = View.VISIBLE
        layoutSearchResultsContainer.removeAllViews()

        if (isWebSearchLoading) return
        isWebSearchLoading = true

        serviceScope.launch {
            try {
                val result = networkService.searchWeb(query)
                isWebSearchLoading = false

                if (!result.success) {
                    tvSearchStatus.visibility = View.VISIBLE
                    tvSearchStatus.text = "⚠️ Search unavailable. Check your backend connection."
                    tvSearchStatus.setTextColor(android.graphics.Color.parseColor("#DC2626"))
                    return@launch
                }

                val searchItems = result.results
                if (searchItems.isEmpty()) {
                    tvSearchStatus.visibility = View.VISIBLE
                    tvSearchStatus.text = "No search results found for \"$query\"."
                    tvSearchStatus.setTextColor(android.graphics.Color.parseColor("#475569"))
                    return@launch
                }

                tvSearchStatus.visibility = View.GONE
                layoutSearchResultsContainer.removeAllViews()

                val palette = getThemePalette(currentThemeKey)
                val itemBgRes = if (palette.isDark) R.drawable.bg_lang_pill_dark else R.drawable.bg_lang_pill
                val titleColor = if (palette.isDark) android.graphics.Color.parseColor("#38BDF8") else android.graphics.Color.parseColor("#1D4ED8")
                val snippetColor = if (palette.isDark) android.graphics.Color.parseColor("#CBD5E1") else android.graphics.Color.parseColor("#334155")

                for (item in searchItems) {
                    val card = LinearLayout(this@ButterflyInputMethodService).apply {
                        orientation = LinearLayout.VERTICAL
                        setBackgroundResource(itemBgRes)
                        setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))
                        val params = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(0, 0, 0, dpToPx(5))
                        }
                        layoutParams = params
                    }

                    val titleTv = TextView(this@ButterflyInputMethodService).apply {
                        text = item.title
                        setTextColor(titleColor)
                        textSize = 12f
                        setTypeface(typeface, Typeface.BOLD)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }

                    val snippetTv = TextView(this@ButterflyInputMethodService).apply {
                        text = item.snippet
                        setTextColor(snippetColor)
                        textSize = 10.5f
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            topMargin = dpToPx(1)
                            bottomMargin = dpToPx(4)
                        }
                    }

                    val btnRow = LinearLayout(this@ButterflyInputMethodService).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dpToPx(26)
                        )
                    }

                    val btnOpen = Button(this@ButterflyInputMethodService).apply {
                        text = "🌐 Open"
                        isAllCaps = false
                        textSize = 10f
                        setTextColor(android.graphics.Color.WHITE)
                        setTypeface(typeface, Typeface.BOLD)
                        setBackgroundResource(R.drawable.bg_quick_toolbar_pill)
                        backgroundTintList = ColorStateList.valueOf(android.graphics.Color.parseColor("#2563EB"))
                        setPadding(dpToPx(8), 0, dpToPx(8), 0)
                        minHeight = 0
                        minWidth = 0
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.MATCH_PARENT
                        ).apply {
                            rightMargin = dpToPx(6)
                        }
                        setOnClickListener {
                            try {
                                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(item.url)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                startActivity(browserIntent)
                            } catch (e: Exception) {
                                Toast.makeText(this@ButterflyInputMethodService, "Unable to open link", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                    val btnInsert = Button(this@ButterflyInputMethodService).apply {
                        text = "✓ Insert"
                        isAllCaps = false
                        textSize = 10f
                        setTextColor(android.graphics.Color.WHITE)
                        setTypeface(typeface, Typeface.BOLD)
                        setBackgroundResource(R.drawable.bg_keycap_enter_blue)
                        setPadding(dpToPx(8), 0, dpToPx(8), 0)
                        minHeight = 0
                        minWidth = 0
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.MATCH_PARENT
                        )
                        setOnClickListener {
                            val activeIc = currentInputConnection
                            if (activeIc != null) {
                                val insertText = if (item.snippet.isNotBlank()) {
                                    "${item.title}: ${item.snippet} (${item.url}) "
                                } else {
                                    "${item.title} - ${item.url} "
                                }
                                activeIc.commitText(insertText, 1)
                                Toast.makeText(this@ButterflyInputMethodService, "✓ Inserted search result", Toast.LENGTH_SHORT).show()
                            }
                            layoutSearchPanel.visibility = View.GONE
                        }
                    }

                    btnRow.addView(btnOpen)
                    btnRow.addView(btnInsert)

                    card.addView(titleTv)
                    card.addView(snippetTv)
                    card.addView(btnRow)

                    layoutSearchResultsContainer.addView(card)
                }
            } catch (e: Exception) {
                Log.e("ButterflyIME", "Search execution error: ${e.message}", e)
                isWebSearchLoading = false
                tvSearchStatus.visibility = View.VISIBLE
                tvSearchStatus.text = "⚠️ Search unavailable. Check your backend connection."
                tvSearchStatus.setTextColor(android.graphics.Color.parseColor("#DC2626"))
            }
        }
    }

    private fun openRelatedWebsite(fallbackQuery: String = "") {
        val q = when {
            fallbackQuery.isNotBlank() -> fallbackQuery
            currentAiQuestion.isNotBlank() -> currentAiQuestion
            lastOriginalText.isNotBlank() -> lastOriginalText
            else -> lastFinalText
        }.trim()

        val targetUrl = when {
            !currentAiSourceUrl.isNullOrBlank() -> currentAiSourceUrl!!
            q.isNotBlank() -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(q, "UTF-8")
            else -> "https://www.google.com"
        }

        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("ButterflyIME", "Failed to open URL $targetUrl: ${e.message}", e)
            Toast.makeText(this, "Could not open browser", Toast.LENGTH_SHORT).show()
        }
    }

    private fun detectScriptLanguage(text: String): String {
        for (ch in text) {
            val code = ch.code
            when (code) {
                in 0x0C00..0x0C7F -> return "te" // Telugu
                in 0x0900..0x097F -> return "hi" // Hindi / Devanagari
                in 0x0B80..0x0BFF -> return "ta" // Tamil
                in 0x0C80..0x0CFF -> return "kn" // Kannada
                in 0x0D00..0x0D7F -> return "ml" // Malayalam
                in 0x0980..0x09FF -> return "bn" // Bengali
                in 0x0A80..0x0AFF -> return "gu" // Gujarati
                in 0x0A00..0x0A7F -> return "pa" // Punjabi
                in 0x0B00..0x0B7F -> return "or" // Odia
                in 0x0600..0x06FF -> return "ur" // Urdu
            }
        }
        val lower = text.lowercase()
        val teluguWords = setOf("ante", "enti", "cheppu", "ela", "yela", "emiti", "emti", "yenti", "chesuko", "avtundi", "undi", "kavali", "gurinchi", "cheppandi", "telugu")
        if (lower.split("\\s+".toRegex()).any { it in teluguWords }) {
            return "te"
        }
        val hindiWords = setOf("kya", "kaise", "batao", "bataiye", "hoga", "hota", "hai", "nahi", "kyun", "kare", "karna", "samjhao")
        if (lower.split("\\s+".toRegex()).any { it in hindiWords }) {
            return "hi"
        }
        return "en"
    }

    private fun handleAiAnswerButtonClick() {
        if (isAiAnswerLoading) {
            return
        }

        val ic = currentInputConnection
        val selectedText = ic?.getSelectedText(0)?.toString() ?: ""
        val beforeCursor = ic?.getTextBeforeCursor(1000, 0)?.toString() ?: ""
        val currentInputText = if (selectedText.isNotBlank()) selectedText else beforeCursor
        val question = currentInputText.trim().ifEmpty {
            if (lastOriginalText.isNotBlank()) lastOriginalText else if (lastSpokenText.isNotBlank()) lastSpokenText else ""
        }.trim()

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

        currentAiQuestion = question
        currentAiSourceUrl = null
        layoutWebLink?.visibility = View.GONE

        val selectedSrcCode = languages.getOrNull(spinnerSourceLang.selectedItemPosition)?.first ?: "auto"
        val selectedTgtCode = languages.getOrNull(spinnerTargetLang.selectedItemPosition)?.first ?: "en"

        val detectedLang = detectScriptLanguage(question)
        val effectiveSrcLang = if (selectedSrcCode != "auto") selectedSrcCode else detectedLang

        // Determine effective target language:
        // When spinnerTargetLang is left at default "en" or "auto", but the question is in Telugu/Hindi/etc.,
        // we answer in that question's language so the user gets answers in the exact language asked!
        val effectiveTgtLang = when {
            selectedTgtCode != "en" && selectedTgtCode != "auto" -> selectedTgtCode
            detectedLang != "en" -> detectedLang
            effectiveSrcLang != "auto" && effectiveSrcLang != "en" -> effectiveSrcLang
            else -> selectedTgtCode
        }

        val targetLangName = languages.firstOrNull { it.first == effectiveTgtLang }?.second
            ?: languages.getOrNull(spinnerTargetLang.selectedItemPosition)?.second
            ?: "Target Language"

        lblTranslationHeader.text = "AI Answer ($targetLangName):"
        lblTranslationHeader.visibility = View.VISIBLE
        tvTranslatedText.text = "Thinking in $targetLangName..."
        tvTranslatedText.visibility = View.VISIBLE

        tvInsertedNotice.visibility = View.GONE
        layoutVoiceResult.visibility = View.VISIBLE
        setKeyboardState(KeyboardState.RESULT)

        serviceScope.launch {
            try {
                val res = networkService.askAI(question, sourceLanguage = effectiveSrcLang, targetLanguage = effectiveTgtLang)

                if (res.success && res.answer.isNotBlank()) {
                    tvTranslatedText.text = res.answer
                    lastFinalText = res.answer
                    currentAiQuestion = question
                    currentAiSourceUrl = res.source_url ?: ("https://www.google.com/search?q=" + java.net.URLEncoder.encode(question, "UTF-8"))

                    val siteTitle = res.source_title?.takeIf { it.isNotBlank() } ?: "Search Web: $question"
                    tvWebLinkTitle?.text = siteTitle
                    layoutWebLink?.visibility = View.VISIBLE

                    tvInsertedNotice.text = "✓ AI Response Ready ($targetLangName)"
                    tvInsertedNotice.visibility = View.VISIBLE
                    notifyHistoryUpdated()
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

    private fun retranslateAndUpdate(showEmptyToast: Boolean = false) {
        if (!::spinnerSourceLang.isInitialized || !::spinnerTargetLang.isInitialized) return

        val srcLang = languages.getOrNull(spinnerSourceLang.selectedItemPosition)?.first ?: "auto"
        val tgtLang = languages.getOrNull(spinnerTargetLang.selectedItemPosition)?.first ?: "en"
        val targetLangName = languages.getOrNull(spinnerTargetLang.selectedItemPosition)?.second ?: "Target Language"

        retranslateJob?.cancel()

        // If currently viewing an AI Answer, re-translate the AI Answer to the newly selected target language
        if (::lblTranslationHeader.isInitialized && lblTranslationHeader.visibility == View.VISIBLE && lblTranslationHeader.text.contains("AI Answer") && !isAiAnswerLoading) {
            val currentAnswer = lastFinalText.ifBlank { tvTranslatedText.text?.toString() ?: "" }
            if (currentAnswer.isNotBlank()) {
                lblTranslationHeader.text = "AI Answer ($targetLangName):"
                tvInsertedNotice.text = "🔄 Translating AI answer to $targetLangName..."
                tvInsertedNotice.visibility = View.VISIBLE
                retranslateJob = serviceScope.launch {
                    try {
                        val reqTarget = if (tgtLang == "auto" || tgtLang.isBlank()) "te" else tgtLang
                        val retranslated = networkService.translateTextDirect(currentAnswer, "auto", reqTarget)
                        if (!retranslated.isNullOrBlank()) {
                            tvTranslatedText.text = retranslated
                            lastFinalText = retranslated
                            tvInsertedNotice.text = "✓ AI Response in $targetLangName"
                            tvInsertedNotice.visibility = View.VISIBLE
                        }
                    } catch (e: Exception) {
                        Log.e("ButterflyIME", "Retranslate AI answer error: ${e.message}")
                    }
                }
                return
            }
        }

        if (!isTranslateOn) {
            val textToRestore = if (lastSpokenText.isNotBlank()) lastSpokenText else lastOriginalText
            if (textToRestore.isNotBlank()) {
                lastFinalText = textToRestore
                if (::lblOriginalHeader.isInitialized) {
                    val spokenHeader = if (lastSpokenText.isNotBlank()) {
                        if (lastSpokenLanguageName.isNotBlank() && lastSpokenLanguageName != "Auto Detect") {
                            "You said ($lastSpokenLanguageName):"
                        } else {
                            "You said:"
                        }
                    } else {
                        if (lastOriginalLanguageName.isNotBlank() && lastOriginalLanguageName != "Auto Detect") {
                            "You typed ($lastOriginalLanguageName):"
                        } else {
                            "You typed:"
                        }
                    }
                    lblOriginalHeader.text = spokenHeader
                    lblOriginalHeader.visibility = View.VISIBLE
                }
                tvOriginalText.text = textToRestore
                tvOriginalText.visibility = View.VISIBLE
                lblTranslationHeader.visibility = View.GONE
                tvTranslatedText.visibility = View.GONE

                val ic = currentInputConnection
                if (ic != null) {
                    val before = ic.getTextBeforeCursor(1000, 0)?.toString() ?: ""
                    val trimmedBefore = before.trimEnd()
                    val toDelete = when {
                        lastCommittedText.isNotBlank() && before.endsWith(lastCommittedText) -> lastCommittedText.length
                        lastTranslatedText.isNotBlank() && before.endsWith(lastTranslatedText) -> lastTranslatedText.length
                        lastFinalText.isNotBlank() && before.endsWith(lastFinalText) -> lastFinalText.length
                        lastOriginalText.isNotBlank() && before.endsWith(lastOriginalText) -> lastOriginalText.length
                        lastCommittedText.isNotBlank() && trimmedBefore.endsWith(lastCommittedText) -> {
                            lastCommittedText.length + (before.length - trimmedBefore.length)
                        }
                        lastTranslatedText.isNotBlank() && trimmedBefore.endsWith(lastTranslatedText) -> {
                            lastTranslatedText.length + (before.length - trimmedBefore.length)
                        }
                        lastCommittedText.isNotBlank() -> lastCommittedText.length
                        else -> 0
                    }
                    if (toDelete > 0) {
                        ic.deleteSurroundingText(toDelete, 0)
                    }
                    ic.commitText(textToRestore, 1)
                    lastCommittedText = textToRestore
                    tvInsertedNotice.text = "✓ Showing original text (Trans: OFF)"
                    tvInsertedNotice.visibility = View.VISIBLE
                }
            }
            return
        }

        val ic = currentInputConnection
        val activeSelected = ic?.getSelectedText(0)?.toString()?.trim() ?: ""
        val activeBeforeCursor = ic?.getTextBeforeCursor(1000, 0)?.toString()?.trim() ?: ""

        val isExplicitSelection = activeSelected.isNotBlank() && activeSelected != lastCommittedText && activeSelected != lastTranslatedText

        val sourceTextToTranslate: String
        val effectiveSrcLang: String

        // Authentic spoken speech ALWAYS takes absolute priority for "You said:"!
        // Never allow translated text from editor or activeSelected to overwrite what the user spoke!
        if (lastSpokenText.isNotBlank()) {
            sourceTextToTranslate = lastSpokenText
            effectiveSrcLang = if (lastSpokenLanguageCode.isNotBlank() && lastSpokenLanguageCode != "auto") lastSpokenLanguageCode else srcLang

            val spokenHeader = if (lastSpokenLanguageName.isNotBlank() && lastSpokenLanguageName != "Auto Detect") {
                "You said ($lastSpokenLanguageName):"
            } else {
                "You said:"
            }
            if (::lblOriginalHeader.isInitialized) {
                lblOriginalHeader.text = spokenHeader
                lblOriginalHeader.visibility = View.VISIBLE
            }
            tvOriginalText.text = lastSpokenText
            tvOriginalText.visibility = View.VISIBLE
        } else if (isExplicitSelection) {
            sourceTextToTranslate = activeSelected
            lastOriginalText = activeSelected
            effectiveSrcLang = srcLang
            if (::lblOriginalHeader.isInitialized) {
                lblOriginalHeader.text = "Selected text:"
                lblOriginalHeader.visibility = View.VISIBLE
            }
            tvOriginalText.text = activeSelected
            tvOriginalText.visibility = View.VISIBLE
        } else if (lastOriginalText.isNotBlank() && lastOriginalText != lastTranslatedText && lastOriginalText != lastCommittedText) {
            sourceTextToTranslate = lastOriginalText
            effectiveSrcLang = srcLang
            if (::lblOriginalHeader.isInitialized) {
                val typedHeader = if (lastOriginalLanguageName.isNotBlank() && lastOriginalLanguageName != "Auto Detect") {
                    "You typed ($lastOriginalLanguageName):"
                } else {
                    "You typed:"
                }
                lblOriginalHeader.text = typedHeader
                lblOriginalHeader.visibility = View.VISIBLE
            }
            tvOriginalText.text = lastOriginalText
            tvOriginalText.visibility = View.VISIBLE
        } else if (activeBeforeCursor.isNotBlank() && activeBeforeCursor != lastCommittedText && activeBeforeCursor != lastTranslatedText) {
            sourceTextToTranslate = activeBeforeCursor
            lastOriginalText = activeBeforeCursor
            effectiveSrcLang = srcLang
            if (::lblOriginalHeader.isInitialized) {
                lblOriginalHeader.text = "You typed:"
                lblOriginalHeader.visibility = View.VISIBLE
            }
            tvOriginalText.text = activeBeforeCursor
            tvOriginalText.visibility = View.VISIBLE
        } else if (lastOriginalText.isNotBlank()) {
            sourceTextToTranslate = lastOriginalText
            effectiveSrcLang = srcLang
            if (::lblOriginalHeader.isInitialized) {
                val typedHeader = if (lastOriginalLanguageName.isNotBlank() && lastOriginalLanguageName != "Auto Detect") {
                    "You typed ($lastOriginalLanguageName):"
                } else {
                    "You typed:"
                }
                lblOriginalHeader.text = typedHeader
                lblOriginalHeader.visibility = View.VISIBLE
            }
            tvOriginalText.text = lastOriginalText
            tvOriginalText.visibility = View.VISIBLE
        } else {
            sourceTextToTranslate = ""
            effectiveSrcLang = srcLang
        }

        if (sourceTextToTranslate.isBlank()) {
            if (showEmptyToast) {
                Toast.makeText(this, "Type or speak text to translate", Toast.LENGTH_SHORT).show()
            }
            return
        }

        lblTranslationHeader.text = "Translation ($targetLangName):"
        lblTranslationHeader.visibility = View.VISIBLE
        tvInsertedNotice.text = "🔄 Translating to $targetLangName..."
        tvInsertedNotice.visibility = View.VISIBLE

        retranslateJob = serviceScope.launch {
            try {
                val reqTarget = if (tgtLang == "auto" || tgtLang.isBlank()) "te" else tgtLang
                val translated = networkService.translateTextDirect(sourceTextToTranslate, effectiveSrcLang, reqTarget)

                if (!translated.isNullOrBlank()) {
                    // Do NOT overwrite lastSpokenText or lastOriginalText! Only update lastTranslatedText and lastFinalText!
                    lastTranslatedText = translated
                    lastFinalText = translated

                    // "You said:" strictly preserves the authentic spoken speech or original input without any modification!
                    if (lastSpokenText.isNotBlank()) {
                        val spokenHeader = if (lastSpokenLanguageName.isNotBlank() && lastSpokenLanguageName != "Auto Detect") {
                            "You said ($lastSpokenLanguageName):"
                        } else {
                            "You said:"
                        }
                        if (::lblOriginalHeader.isInitialized) {
                            lblOriginalHeader.text = spokenHeader
                        }
                        tvOriginalText.text = lastSpokenText
                    } else {
                        val typedHeader = if (lastOriginalLanguageName.isNotBlank() && lastOriginalLanguageName != "Auto Detect") {
                            "You typed ($lastOriginalLanguageName):"
                        } else {
                            "You typed:"
                        }
                        if (::lblOriginalHeader.isInitialized) {
                            lblOriginalHeader.text = typedHeader
                        }
                        tvOriginalText.text = lastOriginalText
                    }
                    tvOriginalText.visibility = View.VISIBLE

                    // ONLY update the Translation view:
                    lblTranslationHeader.text = "Translation ($targetLangName):"
                    lblTranslationHeader.visibility = View.VISIBLE
                    tvTranslatedText.visibility = View.VISIBLE
                    tvTranslatedText.text = translated

                    val currentIc = currentInputConnection
                    if (currentIc != null) {
                        if (isExplicitSelection) {
                            currentIc.commitText(translated, 1)
                            lastCommittedText = translated
                        } else {
                            val before = currentIc.getTextBeforeCursor(1000, 0)?.toString() ?: ""
                            val trimmedBefore = before.trimEnd()
                            val toDelete = when {
                                lastCommittedText.isNotBlank() && before.endsWith(lastCommittedText) -> lastCommittedText.length
                                lastTranslatedText.isNotBlank() && before.endsWith(lastTranslatedText) -> lastTranslatedText.length
                                lastFinalText.isNotBlank() && before.endsWith(lastFinalText) -> lastFinalText.length
                                lastOriginalText.isNotBlank() && before.endsWith(lastOriginalText) -> lastOriginalText.length
                                lastCommittedText.isNotBlank() && trimmedBefore.endsWith(lastCommittedText) -> {
                                    lastCommittedText.length + (before.length - trimmedBefore.length)
                                }
                                lastTranslatedText.isNotBlank() && trimmedBefore.endsWith(lastTranslatedText) -> {
                                    lastTranslatedText.length + (before.length - trimmedBefore.length)
                                }
                                lastFinalText.isNotBlank() && trimmedBefore.endsWith(lastFinalText) -> {
                                    lastFinalText.length + (before.length - trimmedBefore.length)
                                }
                                lastOriginalText.isNotBlank() && trimmedBefore.endsWith(lastOriginalText) -> {
                                    lastOriginalText.length + (before.length - trimmedBefore.length)
                                }
                                lastCommittedText.isNotBlank() -> lastCommittedText.length
                                activeBeforeCursor.isNotBlank() && before == activeBeforeCursor -> before.length
                                else -> 0
                            }
                            if (toDelete > 0) {
                                currentIc.deleteSurroundingText(toDelete, 0)
                            }
                            currentIc.commitText(translated, 1)
                            lastCommittedText = translated
                        }
                        tvInsertedNotice.text = "✓ Automatically translated to $targetLangName"
                        tvInsertedNotice.visibility = View.VISIBLE
                    }
                    setKeyboardState(KeyboardState.RESULT)
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
        currentTypingWord.setLength(0)
        hideSuggestions()
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

        // 4. Action Toolbar Buttons (⌨, History, Switch IME, Gear)
        val toolbarIds = listOf(R.id.btnToggleKeyboard, R.id.btnHistory, R.id.btnSwitchIme, R.id.btnCycleTheme)
        for (id in toolbarIds) {
            val view = rootRootView.findViewById<View>(id) ?: continue
            if (themeKey != "sky") {
                view.backgroundTintList = android.content.res.ColorStateList.valueOf(palette.ctrlKeyBg)
            } else {
                view.backgroundTintList = null
            }
            val iconTint = if (themeKey == "sky") android.graphics.Color.parseColor("#1D4ED8") else palette.ctrlKeyText
            if (view is TextView) {
                view.setTextColor(palette.ctrlKeyText)
                view.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(iconTint)
            }
            if (view is ImageView) {
                view.imageTintList = android.content.res.ColorStateList.valueOf(iconTint)
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
            val btnOpenWeb = rootRootView.findViewById<Button>(R.id.btnOpenWeb)
            val layoutWebLink = rootRootView.findViewById<View>(R.id.layoutWebLink)
            val tvWebLinkTitle = rootRootView.findViewById<TextView>(R.id.tvWebLinkTitle)

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

            if (btnOpenWeb != null) {
                if (themeKey != "sky") {
                    btnOpenWeb.backgroundTintList = android.content.res.ColorStateList.valueOf(palette.primary)
                    btnOpenWeb.setTextColor(if (palette.isDark) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                } else {
                    btnOpenWeb.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#2563EB"))
                    btnOpenWeb.setTextColor(android.graphics.Color.WHITE)
                }
            }

            if (layoutWebLink != null) {
                if (palette.isDark) {
                    layoutWebLink.backgroundTintList = android.content.res.ColorStateList.valueOf(palette.ctrlKeyBg)
                    tvWebLinkTitle?.setTextColor(if (themeKey == "cyber") android.graphics.Color.parseColor("#00F0FF") else android.graphics.Color.parseColor("#60A5FA"))
                } else {
                    layoutWebLink.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#EFF6FF"))
                    tvWebLinkTitle?.setTextColor(android.graphics.Color.parseColor("#1D4ED8"))
                }
            }
        }

        val layoutSearchPanel = rootRootView.findViewById<View>(R.id.layoutSearchPanel)
        if (layoutSearchPanel != null) {
            if (palette.isDark) {
                layoutSearchPanel.setBackgroundColor(palette.cardBg)
            } else {
                layoutSearchPanel.setBackgroundColor(android.graphics.Color.parseColor("#F1F5F9"))
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
                cornerRadius = 18f * density
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
                cornerRadius = 20f * density
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



        if (::btnTapToSpeak.isInitialized && currentState == KeyboardState.IDLE) {
            btnTapToSpeak.setBackgroundColor(palette.accentColor)
        }

        if (currentTypingWord.isNotEmpty()) {
            updateWordSuggestions(currentTypingWord.toString())
        } else {
            hideSuggestions()
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
