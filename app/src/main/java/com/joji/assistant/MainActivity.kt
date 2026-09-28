package com.joji.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            val acc = if (JojiAccessibility.instance != null) "روشن" else "خاموش"
            statusView.text = "وضعیت: ${WakeService.status}\nدسترسی‌پذیری: $acc"
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("joji", MODE_PRIVATE)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(40, 60, 40, 40)
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL

        fun button(label: String, onClick: () -> Unit) {
            val b = Button(this)
            b.text = label
            b.setOnClickListener { onClick() }
            root.addView(b)
        }

        val title = TextView(this)
        title.text = "جوجی"
        title.textSize = 26f
        root.addView(title)

        val keyField = EditText(this)
        keyField.hint = "کلید Gemini API"
        keyField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        keyField.setText(prefs.getString("key", ""))
        root.addView(keyField)

        button("۱. ذخیره کلید") {
            prefs.edit().putString("key", keyField.text.toString().trim()).apply()
            Toast.makeText(this, "ذخیره شد", Toast.LENGTH_SHORT).show()
        }
        button("۲. مجوز میکروفون و اعلان") {
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            requestPermissions(perms.toTypedArray(), 1)
        }
        button("۳. روشن کردن دسترسی‌پذیری") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("۴. تنظیمات باتری (بدون محدودیت)") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        button("۵. شروع گوش دادن") {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "اول مجوز میکروفون را بده", Toast.LENGTH_LONG).show()
            } else {
                startForegroundService(Intent(this, WakeService::class.java))
            }
        }
        button("توقف") {
            stopService(Intent(this, WakeService::class.java))
        }

        val testField = EditText(this)
        testField.hint = "تست: یک دستور متنی بنویس"
        root.addView(testField)
        button("اجرای دستور متنی") {
            val k = prefs.getString("key", "") ?: ""
            val cmd = testField.text.toString().trim()
            if (k.isBlank() || cmd.isBlank()) {
                Toast.makeText(this, "کلید و دستور لازم است", Toast.LENGTH_SHORT).show()
            } else {
                Thread { Agent(this, k).handle(cmd) }.start()
            }
        }

        statusView = TextView(this)
        statusView.setPadding(0, 30, 0, 0)
        root.addView(statusView)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }
}
