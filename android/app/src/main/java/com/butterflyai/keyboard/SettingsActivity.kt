package com.butterflyai.keyboard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

class SettingsActivity : AppCompatActivity() {

    private val activityScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var networkService: NetworkService
    private val gson = Gson()

    private lateinit var etServerUrl: EditText
    private lateinit var btnSaveUrl: Button
    private lateinit var btnTestConnection: Button
    private lateinit var tvTestResult: TextView
    private lateinit var tvImeStatus: TextView
    private lateinit var btnEnableIme: Button
    private lateinit var btnSelectIme: Button
    private lateinit var spinnerKeyboardTheme: Spinner

    private lateinit var tvMicStatus: TextView
    private lateinit var btnGrantMicPermission: Button
    private lateinit var btnOpenAppSettings: Button
    private lateinit var btnOpenHistory: Button

    // Snippets Manager Views
    private lateinit var cardSnippetsSection: View
    private lateinit var etSnippetName: EditText
    private lateinit var etSnippetText: EditText
    private lateinit var etSnippetTrigger: EditText
    private lateinit var btnAddSnippet: Button
    private lateinit var btnResetSnippets: Button
    private lateinit var tvSettingsSnippetsStatus: TextView
    private lateinit var layoutSettingsSnippetsList: LinearLayout
    private var currentSnippetsList: MutableList<SnippetItem> = mutableListOf()

    private val requestMicPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            updateMicPermissionUI(true)
            Toast.makeText(this, "Microphone permission granted!", Toast.LENGTH_SHORT).show()
        } else {
            val permanentlyDenied = !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)
            updateMicPermissionUI(false, permanentlyDenied)
            if (permanentlyDenied) {
                Toast.makeText(this, "Microphone permission is disabled. Enable it from Android Settings.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Microphone permission is required for voice recording.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val themeOptions = arrayOf(
        "sky" to "☁️ Soft Sky (Default screenshot)",
        "dark" to "🌙 Dark Neon",
        "light" to "☀️ Light Modern",
        "oled" to "🖤 OLED Pure Black",
        "cyber" to "🌆 Cyberpunk Pink",
        "sunset" to "🌅 Sunset Violet"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        networkService = NetworkService(this)

        etServerUrl = findViewById(R.id.etServerUrl)
        btnSaveUrl = findViewById(R.id.btnSaveUrl)
        btnTestConnection = findViewById(R.id.btnTestConnection)
        tvTestResult = findViewById(R.id.tvTestResult)
        tvImeStatus = findViewById(R.id.tvImeStatus)
        btnEnableIme = findViewById(R.id.btnEnableIme)
        btnSelectIme = findViewById(R.id.btnSelectIme)
        spinnerKeyboardTheme = findViewById(R.id.spinnerKeyboardTheme)

        tvMicStatus = findViewById(R.id.tvMicStatus)
        btnGrantMicPermission = findViewById(R.id.btnGrantMicPermission)
        btnOpenAppSettings = findViewById(R.id.btnOpenAppSettings)
        btnOpenHistory = findViewById(R.id.btnOpenHistory)

        val imgSettingsLogo = findViewById<android.widget.ImageView>(R.id.imgSettingsLogo)
        imgSettingsLogo?.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#3B82F6"))

        val prefs = getSharedPreferences("butterfly_prefs", Context.MODE_PRIVATE)
        // Priority: 1. User-configured/saved URL. 2. Safe default Render URL only if none configured.
        // NOTE: Local/LAN URLs (e.g. 192.168.x.x, localhost, 127.0.0.1) MUST NOT be overwritten.
        val currentUrl = prefs.getString("server_url", "https://butterfly-ai-voice-keyboard.onrender.com") ?: "https://butterfly-ai-voice-keyboard.onrender.com"
        etServerUrl.setText(currentUrl)

        btnGrantMicPermission.setOnClickListener {
            val isGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (isGranted) {
                Toast.makeText(this, "Microphone permission is already granted.", Toast.LENGTH_SHORT).show()
                updateMicPermissionUI(true)
            } else {
                requestMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        btnOpenAppSettings.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Unable to open Android Settings", Toast.LENGTH_SHORT).show()
            }
        }

        btnOpenHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        // Typing & Feedback Preferences
        val switchHaptic = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchHapticFeedback)
        val switchDoubleSpace = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchDoubleSpacePeriod)
        val btnCheckUpdates = findViewById<Button>(R.id.btnCheckUpdates)

        switchHaptic?.isChecked = prefs.getBoolean("haptic_feedback", true)
        switchHaptic?.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("haptic_feedback", isChecked).apply()
        }

        switchDoubleSpace?.isChecked = prefs.getBoolean("double_space_period", true)
        switchDoubleSpace?.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("double_space_period", isChecked).apply()
        }

        btnCheckUpdates?.setOnClickListener {
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Giribabupeddanagolla/Butterfly-Ai-Voice-KeyBoard/releases"))
                startActivity(browserIntent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open releases link", Toast.LENGTH_SHORT).show()
            }
        }

        // Auto-launch permission dialog if opened specifically from the keyboard for mic permission
        if (intent.getBooleanExtra("request_mic_permission", false)) {
            val isGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (!isGranted) {
                requestMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        // Setup Theme Selector Spinner with custom layout for explicit high-contrast white text color
        val themeNames = themeOptions.map { it.second }
        val themeAdapter = object : ArrayAdapter<String>(this, R.layout.spinner_item, themeNames) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                if (v is TextView) {
                    v.setTextColor(android.graphics.Color.WHITE)
                    v.textSize = 13.5f
                    v.setPadding(0, 0, 0, 0)
                }
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent)
                if (v is TextView) {
                    v.setTextColor(android.graphics.Color.WHITE)
                    v.setBackgroundColor(android.graphics.Color.parseColor("#1E293B"))
                    v.textSize = 9.5f
                    v.includeFontPadding = false
                    v.setPadding(12, 6, 12, 6)
                }
                return v
            }
        }
        themeAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinnerKeyboardTheme.adapter = themeAdapter

        val savedTheme = prefs.getString("keyboard_theme", "sky") ?: "sky"
        val initialIndex = themeOptions.indexOfFirst { it.first == savedTheme }.let { if (it >= 0) it else 0 }
        spinnerKeyboardTheme.setSelection(initialIndex)

        var isThemeSpinnerInit = false
        spinnerKeyboardTheme.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!isThemeSpinnerInit) {
                    isThemeSpinnerInit = true
                    return
                }
                val selectedThemeKey = themeOptions[position].first
                prefs.edit().putString("keyboard_theme", selectedThemeKey).apply()
                Toast.makeText(this@SettingsActivity, "Keyboard Theme Set: ${themeOptions[position].second}", Toast.LENGTH_SHORT).show()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnSaveUrl.setOnClickListener {
            val inputUrl = etServerUrl.text.toString().trim()
            if (inputUrl.isNotEmpty() && (inputUrl.startsWith("http://") || inputUrl.startsWith("https://"))) {
                prefs.edit().putString("server_url", inputUrl).apply()
                Toast.makeText(this, "Server URL saved successfully!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Please enter a valid HTTP/HTTPS URL (e.g. https://butterfly-ai-voice-keyboard.onrender.com)", Toast.LENGTH_LONG).show()
            }
        }

        btnTestConnection.setOnClickListener {
            val inputUrl = etServerUrl.text.toString().trim()
            if (inputUrl.isNotEmpty() && (inputUrl.startsWith("http://") || inputUrl.startsWith("https://"))) {
                prefs.edit().putString("server_url", inputUrl).apply()
            }

            tvTestResult.visibility = View.VISIBLE
            tvTestResult.text = "Testing connection to Butterfly AI backend..."
            tvTestResult.setTextColor(android.graphics.Color.parseColor("#CBD5E1"))

            activityScope.launch {
                val result = networkService.testConnection()
                if (result.success) {
                    tvTestResult.text = "CONNECTED! ${result.message}"
                    tvTestResult.setTextColor(android.graphics.Color.parseColor("#10B981"))
                } else {
                    tvTestResult.text = "CONNECTION FAILED: ${result.message}"
                    tvTestResult.setTextColor(android.graphics.Color.parseColor("#EF4444"))
                }
            }
        }

        btnEnableIme.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        btnSelectIme.setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        // Setup Custom Snippets Manager
        cardSnippetsSection = findViewById(R.id.cardSnippetsSection)
        etSnippetName = findViewById(R.id.etSnippetName)
        etSnippetText = findViewById(R.id.etSnippetText)
        etSnippetTrigger = findViewById(R.id.etSnippetTrigger)
        btnAddSnippet = findViewById(R.id.btnAddSnippet)
        btnResetSnippets = findViewById(R.id.btnResetSnippets)
        tvSettingsSnippetsStatus = findViewById(R.id.tvSettingsSnippetsStatus)
        layoutSettingsSnippetsList = findViewById(R.id.layoutSettingsSnippetsList)

        setupSnippetsManager()

        if (intent.getBooleanExtra("open_snippets", false)) {
            val scrollView = findViewById<ScrollView>(R.id.settingsScrollView)
            cardSnippetsSection.post {
                scrollView?.smoothScrollTo(0, cardSnippetsSection.top)
            }
        }

        checkImeStatus()
        checkMicPermissionStatus()
    }

    override fun onResume() {
        super.onResume()
        checkImeStatus()
        checkMicPermissionStatus()
        loadCachedSnippets()
        fetchSnippetsFromBackend()
    }

    private fun setupSnippetsManager() {
        loadCachedSnippets()
        fetchSnippetsFromBackend()

        btnAddSnippet.setOnClickListener {
            val name = etSnippetName.text.toString().trim()
            val text = etSnippetText.text.toString().trim()
            val trigger = etSnippetTrigger.text.toString().trim().ifBlank { null }

            if (name.isEmpty() || text.isEmpty()) {
                android.widget.Toast.makeText(this, "Please provide both Snippet Name and Content.", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnAddSnippet.isEnabled = false
            btnAddSnippet.text = "Saving..."

            activityScope.launch {
                val success = networkService.createSnippet(name, text, trigger)
                if (success) {
                    etSnippetName.setText("")
                    etSnippetText.setText("")
                    etSnippetTrigger.setText("")
                    android.widget.Toast.makeText(this@SettingsActivity, "✓ Snippet '$name' added to Butterfly AI!", android.widget.Toast.LENGTH_SHORT).show()
                    fetchSnippetsFromBackend()
                } else {
                    // Offline or server not reached: persist locally so keyboard can use it immediately
                    val newId = (currentSnippetsList.maxOfOrNull { it.id } ?: 0) + 1
                    val localSnippet = SnippetItem(id = newId, name = name, text = text, voice_trigger = trigger)
                    currentSnippetsList.add(0, localSnippet)
                    saveSnippetsToCache(currentSnippetsList)
                    renderSnippetsUI(currentSnippetsList)
                    etSnippetName.setText("")
                    etSnippetText.setText("")
                    etSnippetTrigger.setText("")
                    android.widget.Toast.makeText(this@SettingsActivity, "✓ Saved locally to Keyboard! (Server offline)", android.widget.Toast.LENGTH_LONG).show()
                }
                btnAddSnippet.isEnabled = true
                btnAddSnippet.text = "+ Add Snippet"
            }
        }

        btnResetSnippets.setOnClickListener {
            btnResetSnippets.isEnabled = false
            btnResetSnippets.text = "↺ Resetting..."
            activityScope.launch {
                val result = networkService.resetSnippets()
                if (result.success && result.snippets.isNotEmpty()) {
                    currentSnippetsList = result.snippets.toMutableList()
                    saveSnippetsToCache(currentSnippetsList)
                    renderSnippetsUI(currentSnippetsList)
                    android.widget.Toast.makeText(this@SettingsActivity, "✓ Restored default starter snippets!", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    currentSnippetsList = StarterSnippets.list.toMutableList()
                    saveSnippetsToCache(currentSnippetsList)
                    renderSnippetsUI(currentSnippetsList)
                    android.widget.Toast.makeText(this@SettingsActivity, "✓ Restored standard starter snippets", android.widget.Toast.LENGTH_SHORT).show()
                }
                btnResetSnippets.isEnabled = true
                btnResetSnippets.text = "↺ Defaults"
            }
        }
    }

    private fun loadCachedSnippets() {
        val prefs = getSharedPreferences("butterfly_prefs", Context.MODE_PRIVATE)
        val json = prefs.getString("cached_snippets_json", null)
        if (!json.isNullOrEmpty()) {
            try {
                val listType = object : TypeToken<List<SnippetItem>>() {}.type
                val cached: List<SnippetItem> = gson.fromJson(json, listType) ?: emptyList()
                if (cached.isNotEmpty()) {
                    currentSnippetsList = cached.toMutableList()
                    renderSnippetsUI(currentSnippetsList)
                    return
                }
            } catch (e: Exception) {
                android.util.Log.e("SettingsActivity", "Error loading cached snippets: ${e.message}")
            }
        }
        currentSnippetsList = StarterSnippets.list.toMutableList()
        renderSnippetsUI(currentSnippetsList)
    }

    private fun saveSnippetsToCache(list: List<SnippetItem>) {
        val prefs = getSharedPreferences("butterfly_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("cached_snippets_json", gson.toJson(list)).apply()
    }

    private fun fetchSnippetsFromBackend() {
        tvSettingsSnippetsStatus.visibility = View.VISIBLE
        tvSettingsSnippetsStatus.text = "Syncing with Butterfly AI cloud..."
        activityScope.launch {
            val result = networkService.getSnippets()
            if (result.success) {
                if (result.snippets.isNotEmpty()) {
                    currentSnippetsList = result.snippets.toMutableList()
                    saveSnippetsToCache(currentSnippetsList)
                    renderSnippetsUI(currentSnippetsList)
                } else {
                    // Empty backend: seed default snippets
                    val resetRes = networkService.resetSnippets()
                    if (resetRes.success && resetRes.snippets.isNotEmpty()) {
                        currentSnippetsList = resetRes.snippets.toMutableList()
                        saveSnippetsToCache(currentSnippetsList)
                        renderSnippetsUI(currentSnippetsList)
                    } else {
                        renderSnippetsUI(currentSnippetsList)
                    }
                }
            } else {
                tvSettingsSnippetsStatus.text = "Offline Mode: Showing ${currentSnippetsList.size} cached snippets"
            }
        }
    }

    private fun renderSnippetsUI(snippets: List<SnippetItem>) {
        layoutSettingsSnippetsList.removeAllViews()

        if (snippets.isEmpty()) {
            tvSettingsSnippetsStatus.text = "No snippets found. Tap '↺ Defaults' or add one above."
            tvSettingsSnippetsStatus.visibility = View.VISIBLE
            return
        }

        tvSettingsSnippetsStatus.text = "Showing ${snippets.size} custom snippets (Tap to copy):"
        tvSettingsSnippetsStatus.visibility = View.VISIBLE

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        for (snippet in snippets) {
            val itemCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(this@SettingsActivity, R.drawable.bg_search_bar)?.constantState?.newDrawable()?.mutate()
                backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#0F172A"))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 0, dp(8))
                }
                layoutParams = lp
                isClickable = true
                isFocusable = true
            }

            // Top Header: Name + Trigger Pill + Delete Button
            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            val titleView = TextView(this).apply {
                text = snippet.name
                setTextColor(android.graphics.Color.WHITE)
                textSize = 13.5f
                setTypeface(typeface, Typeface.BOLD)
                val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                layoutParams = p
            }
            headerRow.addView(titleView)

            if (!snippet.voice_trigger.isNullOrBlank()) {
                val triggerPill = TextView(this).apply {
                    text = "🎙️ \"${snippet.voice_trigger}\""
                    setTextColor(android.graphics.Color.parseColor("#38BDF8"))
                    textSize = 10.5f
                    setBackgroundColor(android.graphics.Color.parseColor("#1E293B"))
                    setPadding(dp(7), dp(2), dp(7), dp(2))
                    val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(dp(4), 0, dp(8), 0)
                    }
                    layoutParams = p
                }
                headerRow.addView(triggerPill)
            }

            val deleteBtn = Button(this).apply {
                text = "✕ Delete"
                setTextColor(android.graphics.Color.parseColor("#EF4444"))
                textSize = 10.5f
                setTypeface(typeface, Typeface.BOLD)
                setBackgroundResource(R.drawable.bg_quick_toolbar_pill)
                backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#1E293B"))
                setPadding(dp(8), dp(0), dp(8), dp(0))
                minHeight = dp(28)
                minWidth = dp(40)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(28))
            }

            deleteBtn.setOnClickListener {
                activityScope.launch {
                    networkService.deleteSnippet(snippet.id)
                    currentSnippetsList.removeAll { it.id == snippet.id }
                    saveSnippetsToCache(currentSnippetsList)
                    renderSnippetsUI(currentSnippetsList)
                    android.widget.Toast.makeText(this@SettingsActivity, "Snippet deleted", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            headerRow.addView(deleteBtn)
            itemCard.addView(headerRow)

            // Content preview
            val contentView = TextView(this).apply {
                text = snippet.text
                setTextColor(android.graphics.Color.parseColor("#CBD5E1"))
                textSize = 12f
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, dp(4), 0, 0)
                }
                layoutParams = p
            }
            itemCard.addView(contentView)

            itemCard.setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText(snippet.name, snippet.text)
                clipboard.setPrimaryClip(clip)
                android.widget.Toast.makeText(this, "✓ Copied \"${snippet.name}\" to clipboard!", android.widget.Toast.LENGTH_SHORT).show()
            }

            layoutSettingsSnippetsList.addView(itemCard)
        }
    }

    private fun checkMicPermissionStatus() {
        val isGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (isGranted) {
            updateMicPermissionUI(true)
        } else {
            val isPermanentlyDenied = !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)
            // If the user has denied and shouldShowRequestPermissionRationale is false, it's permanently disabled or not yet requested
            updateMicPermissionUI(false, isPermanentlyDenied = false)
        }
    }

    private fun updateMicPermissionUI(granted: Boolean, isPermanentlyDenied: Boolean = false) {
        if (!::tvMicStatus.isInitialized || !::btnGrantMicPermission.isInitialized) return
        when {
            granted -> {
                tvMicStatus.text = "✓ Status: Microphone permission granted"
                tvMicStatus.setTextColor(android.graphics.Color.parseColor("#10B981"))
                btnGrantMicPermission.text = "Permission Granted"
                btnGrantMicPermission.isEnabled = false
                btnGrantMicPermission.alpha = 0.6f
            }
            isPermanentlyDenied -> {
                tvMicStatus.text = "⚠️ Status: Microphone permission is disabled. Enable it from Android Settings."
                tvMicStatus.setTextColor(android.graphics.Color.parseColor("#EF4444"))
                btnGrantMicPermission.text = "Grant Mic Permission"
                btnGrantMicPermission.isEnabled = true
                btnGrantMicPermission.alpha = 1.0f
            }
            else -> {
                tvMicStatus.text = "● Status: Microphone permission required for voice recording"
                tvMicStatus.setTextColor(android.graphics.Color.parseColor("#F59E0B"))
                btnGrantMicPermission.text = "Grant Mic Permission"
                btnGrantMicPermission.isEnabled = true
                btnGrantMicPermission.alpha = 1.0f
            }
        }
    }

    private fun checkImeStatus() {
        val enabledMethods = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_INPUT_METHODS) ?: ""
        val defaultMethod = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val pkg = packageName

        val isEnabled = enabledMethods.contains(pkg)
        val isDefault = defaultMethod.contains(pkg)

        when {
            isEnabled && isDefault -> {
                tvImeStatus.text = "✓ Status: Butterfly AI Voice Keyboard is ENABLED & ACTIVE!"
                tvImeStatus.setTextColor(android.graphics.Color.parseColor("#10B981"))
            }
            isEnabled -> {
                tvImeStatus.text = "● Status: Enabled! Now tap 'Step 2' to select Butterfly AI"
                tvImeStatus.setTextColor(android.graphics.Color.parseColor("#3B82F6"))
            }
            else -> {
                tvImeStatus.text = "● Status: Not enabled yet. Tap 'Step 1' to enable in Android settings"
                tvImeStatus.setTextColor(android.graphics.Color.parseColor("#F59E0B"))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }
}
