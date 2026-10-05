package com.butterflyai.keyboard

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class HistoryActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_HISTORY_UPDATED = "com.butterflyai.keyboard.ACTION_HISTORY_UPDATED"
        private val IST_TIMEZONE = TimeZone.getTimeZone("Asia/Kolkata")
    }

    private val activityScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var networkService: NetworkService

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private lateinit var btnBack: Button
    private lateinit var btnRefreshHistory: Button
    private lateinit var layoutHistoryLoading: View
    private lateinit var layoutHistoryError: View
    private lateinit var tvHistoryErrorMessage: TextView
    private lateinit var btnRetryHistory: Button
    private lateinit var layoutHistoryEmpty: View
    private lateinit var rvHistory: RecyclerView

    private lateinit var etHistorySearch: android.widget.EditText
    private lateinit var btnClearHistorySearch: Button
    private var searchDebounceJob: Job? = null

    private var realtimePollingJob: Job? = null
    private var isReceiverRegistered = false

    private val historySessions = mutableListOf<HistorySession>()
    private lateinit var adapter: HistoryAdapter

    private val historyUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d("HistoryActivity", "Received ACTION_HISTORY_UPDATED, refreshing history in real time")
            val q = etHistorySearch.text.toString().trim()
            loadHistory(q.ifEmpty { null }, isSilent = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        networkService = NetworkService(this)
        tts = TextToSpeech(this, this)

        btnBack = findViewById(R.id.btnBack)
        btnRefreshHistory = findViewById(R.id.btnRefreshHistory)
        layoutHistoryLoading = findViewById(R.id.layoutHistoryLoading)
        layoutHistoryError = findViewById(R.id.layoutHistoryError)
        tvHistoryErrorMessage = findViewById(R.id.tvHistoryErrorMessage)
        btnRetryHistory = findViewById(R.id.btnRetryHistory)
        layoutHistoryEmpty = findViewById(R.id.layoutHistoryEmpty)
        rvHistory = findViewById(R.id.rvHistory)
        etHistorySearch = findViewById(R.id.etHistorySearch)
        btnClearHistorySearch = findViewById(R.id.btnClearHistorySearch)

        rvHistory.layoutManager = LinearLayoutManager(this)
        adapter = HistoryAdapter(
            items = historySessions,
            onCopy = { text -> copyToClipboard(text) },
            onSpeak = { text -> speakText(text) },
            onDelete = { session, position -> deleteSession(session, position) }
        )
        rvHistory.adapter = adapter

        btnBack.setOnClickListener {
            finish()
        }

        btnRefreshHistory.setOnClickListener {
            val q = etHistorySearch.text.toString().trim()
            loadHistory(q.ifEmpty { null }, isSilent = false)
        }

        btnRetryHistory.setOnClickListener {
            val q = etHistorySearch.text.toString().trim()
            loadHistory(q.ifEmpty { null }, isSilent = false)
        }

        etHistorySearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim() ?: ""
                btnClearHistorySearch.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                searchDebounceJob?.cancel()
                searchDebounceJob = activityScope.launch {
                    delay(350)
                    loadHistory(query.ifEmpty { null }, isSilent = historySessions.isNotEmpty())
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        btnClearHistorySearch.setOnClickListener {
            etHistorySearch.setText("")
            btnClearHistorySearch.visibility = View.GONE
            loadHistory(null, isSilent = false)
        }

        loadHistory(isSilent = false)
    }

    override fun onResume() {
        super.onResume()
        registerHistoryReceiver()
        startRealtimePolling()
        val q = etHistorySearch.text.toString().trim()
        loadHistory(q.ifEmpty { null }, isSilent = historySessions.isNotEmpty())
    }

    override fun onPause() {
        super.onPause()
        stopRealtimePolling()
        unregisterHistoryReceiver()
    }

    private fun registerHistoryReceiver() {
        if (!isReceiverRegistered) {
            try {
                val filter = IntentFilter(ACTION_HISTORY_UPDATED)
                ContextCompat.registerReceiver(
                    this,
                    historyUpdateReceiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                isReceiverRegistered = true
            } catch (e: Exception) {
                Log.w("HistoryActivity", "Failed to register history receiver: ${e.message}")
            }
        }
    }

    private fun unregisterHistoryReceiver() {
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(historyUpdateReceiver)
            } catch (_: Exception) {}
            isReceiverRegistered = false
        }
    }

    private fun startRealtimePolling() {
        realtimePollingJob?.cancel()
        realtimePollingJob = activityScope.launch {
            while (isActive) {
                delay(5000)
                val q = etHistorySearch.text.toString().trim()
                loadHistory(q.ifEmpty { null }, isSilent = true)
            }
        }
    }

    private fun stopRealtimePolling() {
        realtimePollingJob?.cancel()
        realtimePollingJob = null
    }

    private fun loadHistory(searchQuery: String? = null, isSilent: Boolean = false) {
        if (!isSilent || historySessions.isEmpty()) {
            showLoading()
        }
        activityScope.launch {
            val result = networkService.getHistory(searchQuery)
            if (result.success) {
                historySessions.clear()
                historySessions.addAll(result.sessions)
                adapter.notifyDataSetChanged()

                if (historySessions.isEmpty()) {
                    showEmpty()
                } else {
                    showContent()
                }
            } else {
                if (!isSilent || historySessions.isEmpty()) {
                    showError(result.error ?: "Unable to load history. Check your backend connection.")
                }
            }
        }
    }

    private fun deleteSession(session: HistorySession, position: Int) {
        activityScope.launch {
            val deleted = networkService.deleteHistorySession(session.session_id)
            if (deleted) {
                if (position in 0 until historySessions.size) {
                    historySessions.removeAt(position)
                    adapter.notifyItemRemoved(position)
                    adapter.notifyItemRangeChanged(position, historySessions.size)
                }
                Toast.makeText(this@HistoryActivity, "Session deleted", Toast.LENGTH_SHORT).show()
                if (historySessions.isEmpty()) {
                    showEmpty()
                }
            } else {
                Toast.makeText(this@HistoryActivity, "Failed to delete session", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun copyToClipboard(text: String) {
        if (text.isBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Butterfly History", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun speakText(text: String) {
        if (text.isBlank()) return
        if (!isTtsReady || tts == null) {
            Toast.makeText(this, "Text-to-speech engine initializing...", Toast.LENGTH_SHORT).show()
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "HistorySpeech_${System.currentTimeMillis()}")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
            isTtsReady = true
        }
    }

    private fun showLoading() {
        layoutHistoryLoading.visibility = View.VISIBLE
        layoutHistoryError.visibility = View.GONE
        layoutHistoryEmpty.visibility = View.GONE
        rvHistory.visibility = View.GONE
    }

    private fun showContent() {
        layoutHistoryLoading.visibility = View.GONE
        layoutHistoryError.visibility = View.GONE
        layoutHistoryEmpty.visibility = View.GONE
        rvHistory.visibility = View.VISIBLE
    }

    private fun showEmpty() {
        layoutHistoryLoading.visibility = View.GONE
        layoutHistoryError.visibility = View.GONE
        layoutHistoryEmpty.visibility = View.VISIBLE
        rvHistory.visibility = View.GONE
    }

    private fun showError(message: String) {
        layoutHistoryLoading.visibility = View.GONE
        layoutHistoryError.visibility = View.VISIBLE
        layoutHistoryEmpty.visibility = View.GONE
        rvHistory.visibility = View.GONE
        tvHistoryErrorMessage.text = message
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRealtimePolling()
        unregisterHistoryReceiver()
        activityScope.cancel()
        tts?.stop()
        tts?.shutdown()
    }

    // RecyclerView Adapter for History Sessions
    private class HistoryAdapter(
        private val items: List<HistorySession>,
        private val onCopy: (String) -> Unit,
        private val onSpeak: (String) -> Unit,
        private val onDelete: (HistorySession, Int) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvHistoryLanguages: TextView = itemView.findViewById(R.id.tvHistoryLanguages)
            val tvHistoryDate: TextView = itemView.findViewById(R.id.tvHistoryDate)
            val layoutOriginal: View = itemView.findViewById(R.id.layoutOriginal)
            val tvOriginalText: TextView = itemView.findViewById(R.id.tvOriginalText)
            val layoutTranslated: View = itemView.findViewById(R.id.layoutTranslated)
            val tvTranslatedText: TextView = itemView.findViewById(R.id.tvTranslatedText)
            val btnCopyHistory: Button = itemView.findViewById(R.id.btnCopyHistory)
            val btnSpeakHistory: Button = itemView.findViewById(R.id.btnSpeakHistory)
            val btnDeleteHistory: Button = itemView.findViewById(R.id.btnDeleteHistory)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val session = items[position]

            // Language pair formatting
            val src = session.source_language?.uppercase() ?: "AUTO"
            val tgt = session.translation_language?.uppercase() ?: "EN"
            holder.tvHistoryLanguages.text = "$src ➔ $tgt"

            // Timestamp formatting with Indian Standard Time & real-time relative indicators
            holder.tvHistoryDate.text = formatIndianTimestamp(session)

            val original = session.original_text?.trim() ?: ""
            val translated = session.translated_text?.trim() ?: session.last_message?.trim() ?: ""

            if (original.isNotEmpty()) {
                holder.layoutOriginal.visibility = View.VISIBLE
                holder.tvOriginalText.text = original
            } else {
                holder.layoutOriginal.visibility = View.GONE
            }

            if (translated.isNotEmpty()) {
                holder.layoutTranslated.visibility = View.VISIBLE
                holder.tvTranslatedText.text = translated
            } else if (original.isNotEmpty()) {
                holder.layoutTranslated.visibility = View.GONE
            } else {
                holder.layoutTranslated.visibility = View.VISIBLE
                holder.tvTranslatedText.text = session.title ?: "(Empty recording)"
            }

            val textToActUpon = if (translated.isNotEmpty()) translated else original

            holder.btnCopyHistory.setOnClickListener {
                onCopy(textToActUpon)
            }

            holder.btnSpeakHistory.setOnClickListener {
                onSpeak(textToActUpon)
            }

            holder.btnDeleteHistory.setOnClickListener {
                val pos = holder.adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onDelete(session, pos)
                }
            }
        }

        override fun getItemCount(): Int = items.size

        private fun parseIsoToEpochMs(isoStr: String?): Long {
            if (isoStr.isNullOrBlank()) return 0L
            val patterns = arrayOf(
                "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX",
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSSSSS",
                "yyyy-MM-dd'T'HH:mm:ss.SSS",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss"
            )
            val cleanStr = isoStr.trim()
            for (pat in patterns) {
                try {
                    val sdf = SimpleDateFormat(pat, Locale.US)
                    sdf.timeZone = TimeZone.getTimeZone("UTC")
                    val parsed = sdf.parse(cleanStr)
                    if (parsed != null) return parsed.time
                } catch (_: Exception) {}
            }
            try {
                val normalized = cleanStr.replace("Z", "").substringBefore(".")
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                val parsed = sdf.parse(normalized.replace(" ", "T"))
                if (parsed != null) return parsed.time
            } catch (_: Exception) {}
            return 0L
        }

        private fun formatIndianTimestamp(session: HistorySession): String {
            val epochMs = if (session.timestamp_ms != null && session.timestamp_ms > 0) {
                session.timestamp_ms
            } else {
                parseIsoToEpochMs(session.created_at)
            }

            if (epochMs <= 0L) {
                return session.created_at_ist ?: session.created_at?.replace("T", " ")?.substringBefore(".") ?: "Recent"
            }

            val now = System.currentTimeMillis()
            val diffMs = maxOf(0L, now - epochMs)
            val diffSec = diffMs / 1000
            val diffMin = diffSec / 60

            val itemCal = Calendar.getInstance(IST_TIMEZONE).apply { timeInMillis = epochMs }
            val nowCal = Calendar.getInstance(IST_TIMEZONE).apply { timeInMillis = now }

            val timeFormat = SimpleDateFormat("h:mm a", Locale.US).apply {
                timeZone = IST_TIMEZONE
            }
            val timeStr = timeFormat.format(Date(epochMs))

            val isSameDay = nowCal.get(Calendar.YEAR) == itemCal.get(Calendar.YEAR) &&
                    nowCal.get(Calendar.DAY_OF_YEAR) == itemCal.get(Calendar.DAY_OF_YEAR)

            val isYesterday = nowCal.get(Calendar.YEAR) == itemCal.get(Calendar.YEAR) &&
                    (nowCal.get(Calendar.DAY_OF_YEAR) - itemCal.get(Calendar.DAY_OF_YEAR) == 1)

            return when {
                diffSec < 45 -> "Just now • $timeStr"
                diffMin < 60 -> "${diffMin}m ago • $timeStr"
                isSameDay -> "Today, $timeStr"
                isYesterday -> "Yesterday, $timeStr"
                else -> {
                    val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.US).apply {
                        timeZone = IST_TIMEZONE
                    }
                    dateFormat.format(Date(epochMs))
                }
            }
        }
    }
}
