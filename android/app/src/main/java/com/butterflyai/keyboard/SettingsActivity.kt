package com.butterflyai.keyboard

import android.content.Context
import android.content.Intent
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
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*

class SettingsActivity : AppCompatActivity() {

    private val activityScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var networkService: NetworkService

    private lateinit var etServerUrl: EditText
    private lateinit var btnSaveUrl: Button
    private lateinit var btnTestConnection: Button
    private lateinit var tvTestResult: TextView
    private lateinit var tvImeStatus: TextView
    private lateinit var btnEnableIme: Button
    private lateinit var btnSelectIme: Button
    private lateinit var spinnerKeyboardTheme: Spinner

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

        val prefs = getSharedPreferences("butterfly_prefs", Context.MODE_PRIVATE)
        val currentUrl = prefs.getString("server_url", "http://192.168.1.105:8000")
        etServerUrl.setText(currentUrl)

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
        spinnerKeyboardTheme.dropDownHeight = 160

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
                Toast.makeText(this, "Please enter a valid HTTP/HTTPS URL (e.g. http://192.168.1.105:8000)", Toast.LENGTH_LONG).show()
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
    }

    override fun onResume() {
        super.onResume()
        checkImeStatus()
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
