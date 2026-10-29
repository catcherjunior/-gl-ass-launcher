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
    private lateinit var titleView: TextView
    private lateinit var labelA: TextView
    private lateinit var scrollA: HorizontalScrollView
    private lateinit var rowA: LinearLayout
    private lateinit var scrollB: HorizontalScrollView
    private lateinit var rowB: LinearLayout
    private var appsKey = ""
    private var loadedPath: String? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun makeRow(): Pair<HorizontalScrollView, LinearLayout> {
        val l = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }
        val s = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            clipChildren = false
            setPadding(dp(48), dp(12), dp(48), dp(12))
            addView(l)
        }
        return s to l
    }

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

        // Dunkler Verlauf unten, damit die Reihen gut lesbar sind
        val scrim = View(this)
        scrim.background = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(0xE6000000.toInt(), 0x00000000)
        )
        root.addView(scrim, FrameLayout.LayoutParams(-1, resources.displayMetrics.heightPixels * 7 / 10, Gravity.BOTTOM))

        val clock = TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"
            textSize = 30f
            setTextColor(0xE6FFFFFF.toInt())
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        root.addView(clock, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(40); marginEnd = dp(64)
        })

        titleView = TextView(this).apply {
            textSize = 34f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            maxLines = 1
        }
        labelA = TextView(this).apply {
            text = "Favoriten & Zuletzt"
            textSize = 13f
            setTextColor(0xB3FFFFFF.toInt())
        }
        val a = makeRow(); scrollA = a.first; rowA = a.second
        val b = makeRow(); scrollB = b.first; rowB = b.second

        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(titleView, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(48) })
        panel.addView(labelA, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(48); topMargin = dp(6) })
        panel.addView(scrollA, LinearLayout.LayoutParams(-1, -2))
        panel.addView(scrollB, LinearLayout.LayoutParams(-1, -2))
        root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(16) })

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
        scrollA.smoothScrollTo(0, 0)
        scrollB.smoothScrollTo(0, 0)
        focusFirst()
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

    private fun focusFirst() {
        val target = if (rowA.childCount > 0) rowA else rowB
        target.getChildAt(0)?.requestFocus()
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
    private fun glass(focused: Boolean, r: Int) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        if (focused) intArrayOf(0x78FFFFFF, 0x3AFFFFFF) else intArrayOf(0x40FFFFFF, 0x14FFFFFF)
    ).apply {
        cornerRadius = dp(r).toFloat()
        setStroke(dp(1), 0x40FFFFFF)
    }

    private fun ring(r: Int) = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        cornerRadius = dp(r).toFloat()
        setStroke(dp(3), 0xF2FFFFFF.toInt())
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

    private fun recents(): List<String> =
        prefs.getString("recent", "")!!.split(",").filter { it.isNotEmpty() }

    private fun rebuild(force: Boolean) {
        val pm = packageManager
        val hidden = prefs.getStringSet("hidden", emptySet())!!
        val favs = prefs.getStringSet("favs", emptySet())!!
        val showA = prefs.getBoolean("rowA", true)

        val all = loadApps()
            .filter { it.activityInfo.packageName !in hidden }
            .map { it to it.loadLabel(pm).toString() }
            .sortedBy { it.second.lowercase() }
        val byPkg = all.associateBy { it.first.activityInfo.packageName }

        val featured = (all.filter { it.first.activityInfo.packageName in favs } +
            recents().mapNotNull { byPkg[it] }.filter { it.first.activityInfo.packageName !in favs })
            .take(10)

        val key = all.joinToString { it.first.activityInfo.packageName } + "|" +
            featured.joinToString { it.first.activityInfo.packageName } + showA
        if (!force && key == appsKey) return
        appsKey = key

        rowA.removeAllViews()
        rowB.removeAllViews()

        if (showA) {
            for ((ri, label) in featured) {
                val pkg = ri.activityInfo.packageName
                val banner = ri.activityInfo.loadBanner(pm) ?: ri.activityInfo.applicationInfo.loadBanner(pm)
                rowA.addView(bannerTile(label, banner, ri.loadIcon(pm), { launch(pkg) }, { appMenu(pkg, label) }))
            }
        }
        val visA = if (rowA.childCount > 0) View.VISIBLE else View.GONE
        scrollA.visibility = visA
        labelA.visibility = visA

        for ((ri, label) in all) {
            val pkg = ri.activityInfo.packageName
            rowB.addView(iconTile(label, ri.loadIcon(pm), null, { launch(pkg) }, { appMenu(pkg, label) }))
        }
        rowB.addView(iconTile("Einstellungen", null, "⚙", { settings() }, null))
        focusFirst()
    }

    private fun decorate(
        v: FrameLayout, label: String, r: Int, scale: Float,
        onClick: () -> Unit, longAction: (() -> Unit)?
    ) {
        v.isFocusable = true
        v.isClickable = true
        v.background = glass(false, r)
        v.clipToOutline = true
        v.setOnClickListener { onClick() }
        if (longAction != null) v.setOnLongClickListener { longAction(); true }
        v.setOnFocusChangeListener { view, focused ->
            view.background = glass(focused, r)
            (view as FrameLayout).foreground = if (focused) ring(r) else null
            val s = if (focused) scale else 1f
            view.animate().scaleX(s).scaleY(s).setDuration(120).start()
            if (focused) titleView.text = label
        }
    }

    // Kleine App-Kachel (abgerundetes Quadrat)
    private fun iconTile(
        label: String, icon: Drawable?, symbol: String?,
        onClick: () -> Unit, longAction: (() -> Unit)?
    ): View {
        val card = FrameLayout(this).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(76), dp(76)).apply { marginEnd = dp(16) }
        }
        if (icon != null) {
            card.addView(ImageView(this).apply { setImageDrawable(icon) }, FrameLayout.LayoutParams(-1, -1))
        } else {
            card.addView(TextView(this).apply {
                text = symbol; textSize = 32f; gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            }, FrameLayout.LayoutParams(-1, -1))
        }
        decorate(card, label, 18, 1.15f, onClick, longAction)
        return card
    }

    // Große 16:9-Kachel (App-Banner)
    private fun bannerTile(
        label: String, banner: Drawable?, icon: Drawable,
        onClick: () -> Unit, longAction: (() -> Unit)?
    ): View {
        val card = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(200), dp(113)).apply { marginEnd = dp(20) }
        }
        if (banner != null) {
            card.addView(ImageView(this).apply {
                setImageDrawable(banner); scaleType = ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(-1, -1))
        } else {
            card.addView(ImageView(this).apply { setImageDrawable(icon) },
                FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
        }
        decorate(card, label, 16, 1.08f, onClick, longAction)
        return card
    }

    private fun launch(pkg: String) {
        val i = packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: packageManager.getLaunchIntentForPackage(pkg) ?: return
        val list = recents().toMutableList()
        list.remove(pkg); list.add(0, pkg)
        prefs.edit().putString("recent", list.take(8).joinToString(",")).apply()
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
            .setItems(arrayOf(if (isFav) "Von Favoriten entfernen" else "Zu Favoriten",
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
        val showA = prefs.getBoolean("rowA", true)
        val items = arrayOf(
            "Live-Wallpaper: " + if (wp) "An" else "Aus",
            "Abdunkeln: $dim %",
            "Favoriten-Reihe: " + if (showA) "An" else "Aus",
            "Ausgeblendete Apps zurücksetzen",
            "Fire-TV-Einstellungen öffnen"
        )
        AlertDialog.Builder(this).setTitle("Einstellungen").setItems(items) { _, i ->
            when (i) {
                0 -> prefs.edit().putBoolean("wallpaper", !wp).apply()
                1 -> prefs.edit().putInt("dim", (dim + 20) % 80).apply()
                2 -> prefs.edit().putBoolean("rowA", !showA).apply()
                3 -> prefs.edit().remove("hidden").apply()
                4 -> startActivity(Intent(Settings.ACTION_SETTINGS))
            }
            applyLook()
            rebuild(true)
            if (i in 0..2) settings()
        }.show()
    }
}
