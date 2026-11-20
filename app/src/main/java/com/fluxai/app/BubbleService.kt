package com.fluxai.app

import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.Button
import android.widget.PopupWindow

class BubbleService : Service() {
    private var windowManager: WindowManager? = null
    private var bubble: View? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel("flux_overlay", "FluxAI yüzen menü", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            val notification = Notification.Builder(this, "flux_overlay")
                .setContentTitle("FluxAI aktif").setContentText("Yüzen kısayol hazır")
                .setSmallIcon(android.R.drawable.ic_menu_camera).build()
            startForeground(21, notification)
        }
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = TextView(this).apply {
            text = "FLUX"
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF9F0018.toInt())
            setOnClickListener { showMenu(this) }
        }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(64, 64, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16; y = 220
        }
        view.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
            override fun onTouch(v: View, e: android.view.MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx=e.rawX; sy=e.rawY; ox=params.x; oy=params.y; moved=false; return true }
                    MotionEvent.ACTION_MOVE -> {
                        if (kotlin.math.abs(e.rawX-sx)>8 || kotlin.math.abs(e.rawY-sy)>8) moved=true
                        params.x=ox-(e.rawX-sx).toInt(); params.y=oy+(e.rawY-sy).toInt()
                        windowManager?.updateViewLayout(view, params); return true
                    }
                    MotionEvent.ACTION_UP -> { if (!moved) v.performClick(); return true }
                }
                return false
            }
        })
        bubble = view
        windowManager?.addView(view, params)
    }


    private fun showMenu(anchor: View) {
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 14, 18, 14)
            setBackgroundColor(0xFF211116.toInt())
        }
        fun action(label: String, openMode: Boolean) {
            menu.addView(Button(this).apply {
                text = label
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFF9F0018.toInt())
                setOnClickListener {
                    val i = Intent(this@BubbleService, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    if (openMode) i.putExtra("open_mode", true) else i.putExtra("quick_capture", true)
                    startActivity(i)
                }
            })
        }
        action("Hızlı cevap / alan seç", false)
        action("Konuşma modunu değiştir", true)
        PopupWindow(menu, 230, WindowManager.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            showAtLocation(anchor, Gravity.TOP or Gravity.END, 16, 290)
        }
    }

    override fun onDestroy() { bubble?.let { windowManager?.removeView(it) }; bubble=null; super.onDestroy() }
    override fun onBind(intent: Intent?) = null
}
