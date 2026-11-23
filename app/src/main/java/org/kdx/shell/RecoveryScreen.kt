package org.kdx.shell

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.kdx.shell.core.StartGuard

/**
 * Shown instead of KDX's desktop when KDX kept restarting even in safe mode. Plain Android views on purpose: none of
 * KDX's own screen code runs, so whatever crashed it can't crash this.
 */
object RecoveryScreen {
    fun build(a: Activity, onStart: () -> Unit): View {
        val dp = a.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val report = StartGuard.report(a)
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(36), px(20), px(28))
        }
        fun text(s: String, size: Float, color: Int, bold: Boolean = false, mono: Boolean = false) = TextView(a).apply {
            text = s; textSize = size; setTextColor(color); setTextIsSelectable(mono)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            if (mono) typeface = Typeface.MONOSPACE
            setPadding(0, px(6), 0, px(6))
        }
        fun button(label: String, onClick: () -> Unit) = Button(a).apply {
            text = label; isAllCaps = false; setOnClickListener { onClick() }
        }
        col.addView(text("KDX recovery", 24f, Color.WHITE, bold = true))
        col.addView(text("KDX kept closing and restarting – that's the screen blinking black – even in safe mode. " +
            "So this time KDX didn't open its desktop. The details below say why it kept closing: please copy them " +
            "and send them, so the cause can be fixed.", 15f, Color.rgb(0xDD, 0xE3, 0xEA)))
        col.addView(button("Copy details") {
            a.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("KDX details", report))
            Toast.makeText(a, "Copied", Toast.LENGTH_SHORT).show()
        })
        col.addView(button("Start KDX in safe mode") { onStart() })
        col.addView(button("Turn off KDE Plasma auto-start and custom wallpaper, then start") {
            a.getSharedPreferences("kdx", Activity.MODE_PRIVATE).edit()
                .putBoolean("realKde", false).putBoolean("customWallpaper", false).commit()
            onStart()
        })
        col.addView(button("Use another home screen") {
            runCatching { a.startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
                .onFailure { runCatching { a.startActivity(Intent(Settings.ACTION_SETTINGS)) } }
        })
        col.addView(text("Details", 17f, Color.WHITE, bold = true).apply { setPadding(0, px(16), 0, px(4)) })
        col.addView(text(report, 11f, Color.rgb(0xC8, 0xD0, 0xD8), mono = true))
        return ScrollView(a).apply { setBackgroundColor(Color.rgb(0x1D, 0x27, 0x33)); addView(col) }
    }
}
