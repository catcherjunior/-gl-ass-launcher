package com.example.glasslauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.VideoView
import java.io.File

class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var video: VideoView
    private lateinit var dimView: View
    private lateinit var row: LinearLayout
    private lateinit var scroller: HorizontalScrollView
    private var appsKey = ""
    private var loadedPath: String? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("glass", MODE_PRIVATE)
        hideSystemUi()

        val root = FrameLayout(this)
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF22385F.toInt(), 0xFF0A0E1A.toInt())
        )

        video = VideoView(this)
        if (Build.VERSION.SDK_INT >= 26) video.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
        video.setOnPreparedListener { it.isLooping = true; it.setVolume(0f, 0f) }
        root.addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

        dimView = View(this)
        root.addView(dimView, FrameLayout.LayoutParams(-1, -1))

        val clock = TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"
            textSize = 30f
            setTextColor(0xE6FFFFFF.toInt())
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        root.addView(clock, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(40); marginEnd = dp(64)
        })

        row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }
        scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            clipChildren = false
            setPadding(dp(64), dp(28), dp(64), dp(28))
            addView(row)
        }
        root.addView(scroller, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            bottomMargin = dp(40)
        })

        setContentView(root)
        applyLook()
        rebuild(true)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        applyLook()
        rebuild(false)
    }

    // Wallpaper stoppt, sobald eine andere App im Vordergrund ist -> spart Strom
    override fun onPause() {
        video.pause()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        scroller.smoothScrollTo(0, 0)
        row.getChildAt(0)?.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) { settings(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onBackPressed() { /* Home-Screen: Zurück ignorieren */ }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    // ---------- Wallpaper & Look ----------

    private fun applyLook() {
        dimView.setBackgroundColor(Color.argb(prefs.getInt("dim", 20) * 255 / 100, 0, 0, 0))
        val f = File(getExternalFilesDir(null), "wallpaper.mp4")
        if (prefs.getBoolean("wallpaper", true) && f.exists()) {
            video.visibility = View.VISIBLE
            if (loadedPath != f.path) { video.setVideoPath(f.path); loadedPath = f.path }
            video.start()
        } else {
            video.stopPlayback(); loadedPath = null
            video.visibility = View.GONE
        }
    }

    // "Liquid Glass" ohne echten Blur (spart GPU): transparenter Verlauf + heller Rand
    private fun glass(focused: Boolean) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        if (focused) intArrayOf(0x78FFFFFF, 0x3AFFFFFF) else intArrayOf(0x40FFFFFF, 0x14FFFFFF)
    ).apply {
        cornerRadius = dp(26).toFloat()
        setStroke(dp(if (focused) 2 else 1), if (focused) 0xCCFFFFFF.toInt() else 0x40FFFFFF)
    }

    // ---------- Apps ----------

    private fun loadApps(): List<ResolveInfo> {
        val out = LinkedHashMap<String, ResolveInfo>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)
                .filter { it.activityInfo.packageName != packageName }
                .forEach { out.putIfAbsent(it.activityInfo.packageName, it) }
        }
        return out.values.toList()
    }

    private fun rebuild(force: Boolean) {
        val pm = packageManager
        val hidden = prefs.getStringSet("hidden", emptySet())!!
        val favs = prefs.getStringSet("favs", emptySet())!!
        val showNames = prefs.getBoolean("names", true)
        val apps = loadApps()
            .filter { it.activityInfo.packageName !in hidden }
            .map { it to it.loadLabel(pm).toString() }
            .sortedWith(compareBy({ it.first.activityInfo.packageName !in favs }, { it.second.lowercase() }))
        val key = apps.joinToString { it.first.activityInfo.packageName } + showNames
        if (!force && key == appsKey) return
        appsKey = key

        row.removeAllViews()
        for ((ri, label) in apps) {
            val pkg = ri.activityInfo.packageName
            row.addView(tile(label, ri.loadIcon(pm), null, showNames,
                { launch(pkg) }, { appMenu(pkg, label) }))
        }
        row.addView(tile("Einstellungen", null, "⚙", showNames, { settings() }, null))
        row.getChildAt(0)?.requestFocus()
    }

    private fun tile(
        label: String, icon: Drawable?, symbol: String?, showName: Boolean,
        onClick: () -> Unit, longAction: (() -> Unit)?
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(20), dp(18), dp(if (showName) 14 else 20))
            isFocusable = true
            isClickable = true
            background = glass(false)
            layoutParams = LinearLayout.LayoutParams(dp(140), -2).apply { marginEnd = dp(20) }
            setOnClickListener { onClick() }
            if (longAction != null) setOnLongClickListener { longAction(); true }
            setOnFocusChangeListener { v, focused ->
                v.background = glass(focused)
                val s = if (focused) 1.1f else 1f
                v.animate().scaleX(s).scaleY(s).setDuration(120).start()
            }
        }
        if (icon != null) {
            card.addView(ImageView(this).apply { setImageDrawable(icon) }, LinearLayout.LayoutParams(dp(84), dp(84)))
        } else {
            card.addView(TextView(this).apply {
                text = symbol; textSize = 48f; gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(dp(84), dp(84)))
        }
        if (showName) {
            card.addView(TextView(this).apply {
                text = label; textSize = 14f; maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setTextColor(0xEEFFFFFF.toInt())
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        return card
    }

    private fun launch(pkg: String) {
        val i = packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: packageManager.getLaunchIntentForPackage(pkg) ?: return
        try { startActivity(i) } catch (_: Exception) { }
    }

    private fun toggle(key: String, value: String) {
        val s = prefs.getStringSet(key, emptySet())!!.toMutableSet()
        if (!s.add(value)) s.remove(value)
        prefs.edit().putStringSet(key, s).apply()
    }

    private fun appMenu(pkg: String, label: String) {
        val isFav = pkg in prefs.getStringSet("favs", emptySet())!!
        AlertDialog.Builder(this).setTitle(label)
            .setItems(arrayOf(if (isFav) "Von Favoriten entfernen" else "Zu Favoriten (ganz vorne)",
                "Ausblenden", "Deinstallieren")) { _, i ->
                when (i) {
                    0 -> toggle("favs", pkg)
                    1 -> toggle("hidden", pkg)
                    2 -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
                }
                rebuild(true)
            }.show()
    }

    // ---------- Einstellungen (Menü-Taste oder Zahnrad-Kachel) ----------

    private fun settings() {
        val wp = prefs.getBoolean("wallpaper", true)
        val dim = prefs.getInt("dim", 20)
        val names = prefs.getBoolean("names", true)
        val items = arrayOf(
            "Live-Wallpaper: " + if (wp) "An" else "Aus",
            "Abdunkeln: $dim %",
            "App-Namen: " + if (names) "An" else "Aus",
            "Ausgeblendete Apps zurücksetzen",
            "Fire-TV-Einstellungen öffnen"
        )
        AlertDialog.Builder(this).setTitle("Einstellungen").setItems(items) { _, i ->
            when (i) {
                0 -> prefs.edit().putBoolean("wallpaper", !wp).apply()
                1 -> prefs.edit().putInt("dim", (dim + 20) % 80).apply()
                2 -> prefs.edit().putBoolean("names", !names).apply()
                3 -> prefs.edit().remove("hidden").apply()
                4 -> startActivity(Intent(Settings.ACTION_SETTINGS))
            }
            applyLook()
            rebuild(true)
            if (i in 0..2) settings()
        }.show()
    }
}
