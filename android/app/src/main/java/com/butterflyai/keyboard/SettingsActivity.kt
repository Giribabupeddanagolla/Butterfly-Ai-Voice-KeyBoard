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

        checkImeStatus()
        checkMicPermissionStatus()
    }

    override fun onResume() {
        super.onResume()
        checkImeStatus()
        checkMicPermissionStatus()
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
