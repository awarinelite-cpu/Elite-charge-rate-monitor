package com.elite.chargerate

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : Activity() {

    private lateinit var reader: BatteryReader
    private val handler = Handler(Looper.getMainLooper())
    private var ticks = 0

    // Palette
    private val bgPage = Color.parseColor("#EAF0FE")
    private val navy = Color.parseColor("#0A2A9B")
    private val textDark = Color.parseColor("#0F1B3D")
    private val textMuted = Color.parseColor("#64748B")
    private val heroMuted = Color.parseColor("#A9BBEA")
    private val blue = Color.parseColor("#1D5BFF")
    private val green = Color.parseColor("#22C55E")

    private enum class Kind(val bg: String, val fg: String) {
        GOOD("#DCF7E4", "#16803C"),
        INFO("#DBEAFE", "#1D4ED8"),
        WARN("#FEF3C7", "#B45309"),
        BAD("#FEE2E2", "#B91C1C"),
    }

    private class Tile(val value: TextView, val pill: TextView, val card: View)

    // Dashboard views
    private lateinit var pctText: TextView
    private lateinit var statusPillText: TextView
    private lateinit var gauge: GaugeView
    private lateinit var usePill: TextView
    private lateinit var bigMa: TextView
    private lateinit var pphTitle: TextView
    private lateinit var pphSub: TextView
    private lateinit var pphWatts: TextView
    private lateinit var etaTitle: TextView
    private lateinit var etaSub: TextView
    private lateinit var lifeMax: TextView
    private lateinit var lifeMin: TextView
    private lateinit var capPill: TextView
    private lateinit var capFill: View
    private lateinit var capRest: View
    private lateinit var tVoltage: Tile
    private lateinit var tTemp: Tile
    private lateinit var tLevel: Tile
    private lateinit var tPower: Tile
    private lateinit var tStatus: Tile
    private lateinit var tPlugged: Tile
    private lateinit var tHealth: Tile
    private lateinit var tTech: Tile
    private lateinit var tCycles: Tile
    private lateinit var devAndroid: TextView
    private lateinit var devMaker: TextView
    private lateinit var devModel: TextView

    // Other tabs
    private lateinit var graph: GraphView
    private lateinit var monitorBtn: TextView
    private lateinit var alertsBadge: View
    private lateinit var content: FrameLayout
    private val pages = arrayOfNulls<View>(4)
    private val navItems = ArrayList<LinearLayout>()
    private val navIcons = ArrayList<ImageView>()
    private val navLabels = ArrayList<TextView>()
    private var tab = 0

    private val tick = object : Runnable {
        override fun run() {
            try {
                refresh()
            } catch (t: Throwable) {
                showError("The dashboard failed to update", android.util.Log.getStackTraceString(t))
                return
            }
            handler.postDelayed(this, 1000)
        }
    }

    private val prefs get() = getSharedPreferences(MonitorService.PREFS, MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        val lastCrash = CrashLog.read(this)
        if (lastCrash != null) {
            showError("The app crashed last time", lastCrash)
            return
        }
        try {
            reader = BatteryReader(this)
            window.statusBarColor = navy
            window.navigationBarColor = bgPage
            if (Build.VERSION.SDK_INT >= 26) {
                window.decorView.systemUiVisibility =
                    window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            buildRoot()
        } catch (t: Throwable) {
            showError("The dashboard failed to start", android.util.Log.getStackTraceString(t))
            return
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (prefs.getBoolean(MonitorService.KEY_MONITOR_ON, true)) startMonitor()
    }

    override fun onResume() {
        super.onResume()
        if (!::content.isInitialized) return
        ticks = 0
        handler.post(tick)
        updateMonitorButton()
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    /** Shows an error and its stack trace on screen so it can be read and shared. */
    private fun showError(title: String, trace: String) {
        handler.removeCallbacks(tick)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(24))
        }
        col.addView(TextView(this).apply {
            text = title
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        col.addView(TextView(this).apply {
            text = "Screenshot this screen and send it to get it fixed."
            textSize = 13f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, dp(6), 0, dp(12))
        })
        col.addView(TextView(this).apply {
            text = trace.take(3500)
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#FCA5A5"))
            setTextIsSelectable(true)
        })
        col.addView(TextView(this).apply {
            text = "Clear and try again"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1D5BFF"))
                cornerRadius = 24f * resources.displayMetrics.density
            }
            setOnClickListener {
                CrashLog.clear(this@MainActivity)
                recreate()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(20)
        })
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
            addView(col)
        })
    }

    // ---------------------------------------------------------------- helpers

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private fun dpf(v: Float) = v * density
    private fun c(s: String) = Color.parseColor(s)

    private fun shape(color: Int, radiusDp: Float, strokeColor: Int = 0, strokeDp: Float = 0f) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dpf(radiusDp)
            if (strokeDp > 0f) setStroke(maxOf(1, dpf(strokeDp).toInt()), strokeColor)
        }

    private fun oval(color: Int, strokeColor: Int = 0, strokeDp: Float = 0f) =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (strokeDp > 0f) setStroke(maxOf(1, dpf(strokeDp).toInt()), strokeColor)
        }

    private fun gradient(from: String, to: String, radiusDp: Float, strokeColor: Int = 0, strokeDp: Float = 0f) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c(from), c(to))).apply {
            cornerRadius = dpf(radiusDp)
            if (strokeDp > 0f) setStroke(maxOf(1, dpf(strokeDp).toInt()), strokeColor)
        }

    /** weight: 0 regular, 1 medium, 2 bold */
    private fun label(s: CharSequence, sp: Float, color: Int, weight: Int = 0) = TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(color)
        includeFontPadding = false
        typeface = when (weight) {
            2 -> Typeface.DEFAULT_BOLD
            1 -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> Typeface.DEFAULT
        }
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun icon(res: Int, tint: Int, sizeDp: Int) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(tint)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun circleIcon(res: Int, tint: Int, bg: Int, sizeDp: Int, padDp: Int) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(tint)
        background = oval(bg)
        setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun whiteCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(Color.WHITE, 24f)
        elevation = dpf(2f)
    }

    private fun stylePill(tv: TextView, text: String, kind: Kind) {
        tv.text = text
        tv.setTextColor(c(kind.fg))
        tv.background = shape(c(kind.bg), 10f)
        tv.visibility = View.VISIBLE
    }

    private fun newPill() = label("", 11f, Color.BLACK, 2).apply {
        setPadding(dp(9), dp(3), dp(9), dp(3))
        visibility = View.GONE
    }

    private fun button(text: String, filled: Boolean, onClick: () -> Unit) =
        label(text, 13f, if (filled) Color.WHITE else blue, 1).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(9), dp(16), dp(9))
            background = if (filled) shape(blue, 20f) else shape(Color.WHITE, 20f, blue, 1.2f)
            setOnClickListener { onClick() }
        }

    private fun info(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()
    }

    /** Scroll page with the blue-to-pale header wash. Returns the scroll view and its column. */
    private fun scaffold(): Pair<ScrollView, LinearLayout> {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(20))
        }
        val wash = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(c("#0A2A9B"), c("#1E4FCB"), bgPage)
            )
        }
        val frame = FrameLayout(this).apply {
            addView(wash, FrameLayout.LayoutParams(match, dp(480)))
            addView(col, FrameLayout.LayoutParams(match, wrap))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bgPage)
            clipToPadding = false
            addView(frame)
        }
        return scroll to col
    }

    private fun pageTitle(col: LinearLayout, title: String, subtitle: String) {
        col.addView(label(title, 24f, Color.WHITE, 2), lp(wrap, wrap, 0f, 4, 6, 0, 0))
        col.addView(label(subtitle, 13f, c("#C7D6FF")), lp(wrap, wrap, 0f, 4, 4, 0, 16))
    }

    // ------------------------------------------------------------------- root

    private fun buildRoot() {
        content = FrameLayout(this)

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(c("#F8FAFF"), 30f)
            elevation = dpf(8f)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val specs = listOf(
            Triple("Dashboard", R.drawable.ic_home, 0),
            Triple("History", R.drawable.ic_history, 1),
            Triple("Alerts", R.drawable.ic_bell, 2),
            Triple("Settings", R.drawable.ic_gear, 3),
        )
        for ((name, res, index) in specs) {
            val img = icon(res, textMuted, 22)
            val iconBox = FrameLayout(this)
            iconBox.addView(img, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            if (index == 2) {
                alertsBadge = View(this).apply {
                    background = oval(c("#EF4444"))
                    visibility = if (prefs.getBoolean(MonitorService.KEY_SLOW_ALERT, false)) View.GONE else View.VISIBLE
                }
                iconBox.addView(
                    alertsBadge,
                    FrameLayout.LayoutParams(dp(8), dp(8), Gravity.TOP or Gravity.END).apply {
                        topMargin = dp(0); rightMargin = dp(0)
                    }
                )
            }
            val tv = label(name, 11f, textMuted, 1)
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(8), dp(4), dp(8))
                addView(iconBox, LinearLayout.LayoutParams(dp(26), dp(24)))
                addView(tv, lp(wrap, wrap, 0f, 0, 3, 0, 0))
                setOnClickListener { selectTab(index) }
            }
            navItems.add(item)
            navIcons.add(img)
            navLabels.add(tv)
            nav.addView(item, lp(0, wrap, 1f))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgPage)
            clipChildren = false
            addView(content, lp(match, 0, 1f))
            addView(nav, lp(match, wrap, 0f, 12, 6, 12, 10))
        }
        setContentView(root)
        selectTab(0)
    }

    private fun selectTab(i: Int) {
        tab = i
        val page = pages[i] ?: when (i) {
            0 -> buildDashboard()
            1 -> buildHistory()
            2 -> buildAlerts()
            else -> buildSettings()
        }.also { pages[i] = it }
        content.removeAllViews()
        content.addView(page, FrameLayout.LayoutParams(match, match))

        for (k in navItems.indices) {
            val on = k == i
            navItems[k].background = if (on) gradient("#2F7BFF", "#1747D6", 24f) else null
            navIcons[k].setColorFilter(if (on) Color.WHITE else textMuted)
            navLabels[k].setTextColor(if (on) Color.WHITE else textMuted)
        }
        if (i == 1) graph.setData(History.load(this))
        refresh()
    }

    // -------------------------------------------------------------- dashboard

    private fun buildDashboard(): View {
        val (scroll, col) = scaffold()

        // Header
        val appIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_bolt)
            setColorFilter(Color.WHITE)
            background = gradient("#3B8BFF", "#1747D6", 16f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Charge Monitor", 20f, Color.WHITE, 2))
            addView(label("Keep track. Stay powered.", 12.5f, c("#C7D6FF")), lp(wrap, wrap, 0f, 0, 3, 0, 0))
        }
        val gear = ImageView(this).apply {
            setImageResource(R.drawable.ic_gear)
            setColorFilter(Color.WHITE)
            background = oval(c("#1B3FB0"), c("#3A62D8"), 1f)
            setPadding(dp(11), dp(11), dp(11), dp(11))
            setOnClickListener { selectTab(3) }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(appIcon, lp(dp(50), dp(50)))
            addView(titles, lp(0, wrap, 1f, 14, 0, 8, 0))
            addView(gear, lp(dp(44), dp(44)))
        }
        col.addView(header, lp(match, wrap, 0f, 0, 2, 0, 14))

        col.addView(buildHero(), lp(match, wrap, 0f, 0, 0, 0, 14))
        col.addView(buildCapacity(), lp(match, wrap, 0f, 0, 0, 0, 16))

        // Details header
        val detailsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(circleGrid(), lp(dp(20), dp(20)))
            addView(label("Battery Details", 16f, textDark, 2), lp(0, wrap, 1f, 10, 0, 0, 0))
            addView(label("Tap any card for more", 11.5f, c("#475569")))
            addView(label("›", 22f, blue, 1), lp(wrap, wrap, 0f, 8, 0, 2, 0))
        }
        col.addView(detailsHeader, lp(match, wrap, 0f, 2, 0, 2, 10))

        val row1 = tileRow(col)
        tVoltage = tileIn(row1, "Voltage", R.drawable.ic_bolt, "#7C5CE6", "#E6E1FB",
            "How hard the battery is pushing right now. Healthy Li-ion cells sit between about 3.3 V and 4.4 V.")
        tTemp = tileIn(row1, "Temperature", R.drawable.ic_thermo, "#EF4444", "#FDE0E0",
            "Battery temperature. Below 35 °C is normal; above 40 °C means the phone is working hard or sitting in heat.")
        tTemp.card.setOnClickListener { showCoolDown() }
        tLevel = tileIn(row1, "Level", R.drawable.ic_levelbars, "#16A34A", "#D5F5DF",
            "How much charge is left.")

        val row2 = tileRow(col)
        tPower = tileIn(row2, "Power", R.drawable.ic_star4, "#F59E0B", "#FDE7CB",
            "Live power: current times voltage. Negative means the battery is being drained.")
        tStatus = tileIn(row2, "Status", R.drawable.ic_powerbtn, "#2563EB", "#DCEBFE",
            "What the battery is doing right now: charging, discharging, full or not charging.")
        tPlugged = tileIn(row2, "Plugged", R.drawable.ic_plug, "#7C3AED", "#E4DEFB",
            "What the phone is plugged into, if anything.")

        val row3 = tileRow(col)
        tHealth = tileIn(row3, "Health", R.drawable.ic_heart, "#EF4444", "#FDE0E3",
            "Battery condition. Android 14 and newer can report a health percent; older versions only report Good or a fault.")
        tTech = tileIn(row3, "Technology", R.drawable.ic_chip, "#0E9AA7", "#D4F1F4",
            "The chemistry of the battery cell.")
        tCycles = tileIn(row3, "Charge cycles", R.drawable.ic_cycle, "#F97316", "#FDE8D3",
            "How many full charge cycles the battery has been through. Only Android 14 and newer devices report it.")

        col.addView(buildDevice(), lp(match, wrap, 0f, 0, 6, 0, 8))
        return scroll
    }

    private fun circleGrid(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val cols = intArrayOf(c("#2F7BFF"), c("#2F7BFF"), c("#2F7BFF"), c("#2F7BFF"))
        for (r in 0..1) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in 0..1) {
                row.addView(View(context).apply { background = shape(cols[r * 2 + k], 4f) }, lp(0, match, 1f, if (k == 1) 2 else 0, 0, 0, 0))
            }
            addView(row, lp(match, 0, 1f, 0, if (r == 1) 2 else 0, 0, 0))
        }
    }

    private fun buildHero(): View {
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = gradient("#16399F", "#0A1D5C", 28f, c("#3A5CC4"), 1f)
            elevation = dpf(6f)
            setPadding(dp(16), dp(14), dp(16), dp(12))
        }

        // Percent + status pill
        pctText = label("—", 24f, Color.WHITE, 2)
        val pctRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(icon(R.drawable.ic_battery_v, c("#BFE9FF"), 26))
            addView(pctText, lp(wrap, wrap, 0f, 8, 0, 0, 0))
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(pctRow)
            addView(label("Battery", 13f, heroMuted), lp(wrap, wrap, 0f, 4, 4, 0, 0))
        }
        statusPillText = label("Discharging", 12.5f, Color.WHITE, 1)
        val statusPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(c("#0E4A3D"), 18f, c("#1F8F65"), 1f)
            setPadding(dp(11), dp(6), dp(13), dp(6))
            addView(icon(R.drawable.ic_pulse, c("#4ADE80"), 18))
            addView(statusPillText, lp(wrap, wrap, 0f, 6, 0, 0, 0))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, lp(0, wrap, 1f))
            addView(statusPill, lp(wrap, wrap))
        }
        hero.addView(top)

        // Gauge
        gauge = GaugeView(this)
        hero.addView(gauge, lp(match, wrap, 0f, 0, 0, 0, 0))

        usePill = label("—", 12.5f, Color.WHITE, 1).apply {
            gravity = Gravity.CENTER
            background = shape(c("#1B3AA6"), 16f, c("#3558C9"), 1f)
            setPadding(dp(14), dp(5), dp(14), dp(5))
        }
        hero.addView(usePill, lp(wrap, wrap, 0f, 0, -12, 0, 0).apply { gravity = Gravity.CENTER_HORIZONTAL })

        bigMa = label("—", 34f, Color.WHITE, 2)
        hero.addView(bigMa, lp(wrap, wrap, 0f, 0, 6, 0, 12).apply { gravity = Gravity.CENTER_HORIZONTAL })

        // Rate and ETA panel
        pphTitle = label("—", 16f, Color.WHITE, 2)
        pphSub = label("—", 12f, heroMuted)
        pphWatts = label("—", 12f, heroMuted)
        etaTitle = label("—", 16f, Color.WHITE, 2)
        etaSub = label("—", 12f, heroMuted)

        val leftHalf = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(circleIcon(R.drawable.ic_clock, Color.WHITE, c("#EF4444"), 38, 8))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(pphTitle)
                addView(pphSub, lp(wrap, wrap, 0f, 0, 3, 0, 0))
                addView(pphWatts, lp(wrap, wrap, 0f, 0, 1, 0, 0))
            }, lp(0, wrap, 1f, 9, 0, 0, 0))
        }
        val rightHalf = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(circleIcon(R.drawable.ic_clock, Color.WHITE, c("#2F7BFF"), 38, 8))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(etaTitle)
                addView(etaSub, lp(wrap, wrap, 0f, 0, 3, 0, 0))
            }, lp(0, wrap, 1f, 9, 0, 0, 0))
        }
        val divider = View(this).apply { setBackgroundColor(c("#2F4FB5")) }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(c("#122F8E"), 22f, c("#2D4EB6"), 1f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(leftHalf, lp(0, wrap, 1f))
            addView(divider, lp(dp(1), dp(44), 0f, 8, 0, 8, 0))
            addView(rightHalf, lp(0, wrap, 1f))
        }
        hero.addView(panel, lp(match, wrap))

        // Lifetime peaks + reset
        lifeMax = label("", 12.5f, Color.WHITE)
        lifeMin = label("", 12.5f, Color.WHITE)
        val lifeCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(lifeMax)
            addView(lifeMin, lp(wrap, wrap, 0f, 0, 5, 0, 0))
        }
        val reset = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = shape(c("#1A47C9"), 20f, c("#3A62D8"), 1f)
            setPadding(dp(16), dp(9), dp(18), dp(9))
            addView(icon(R.drawable.ic_refresh, Color.WHITE, 16))
            addView(label("Reset", 13.5f, Color.WHITE, 1), lp(wrap, wrap, 0f, 6, 0, 0, 0))
            setOnClickListener {
                Lifetime.reset(this@MainActivity)
                refresh()
            }
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(lifeCol, lp(0, wrap, 1f))
            addView(reset, lp(wrap, wrap))
        }
        hero.addView(bottom, lp(match, wrap, 0f, 4, 14, 0, 0))
        return hero
    }

    private fun buildCapacity(): View {
        val card = whiteCard().apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }

        capPill = label("Health —", 13f, c("#15803D"), 2)
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(c("#D7F5E0"), 18f)
            setPadding(dp(14), dp(7), dp(10), dp(7))
            addView(capPill)
            addView(circleIcon(R.drawable.ic_check, Color.WHITE, c("#16A34A"), 20, 4), lp(dp(20), dp(20), 0f, 7, 0, 0, 0))
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(icon(R.drawable.ic_battery_v, c("#22C55E"), 30))
            addView(label("Battery Capacity", 16f, textDark, 2), lp(0, wrap, 1f, 12, 0, 0, 0))
            addView(pill)
        }
        card.addView(titleRow)

        capFill = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(c("#14B8A6"), c("#4ADE50"))
            ).apply { cornerRadius = dpf(6f) }
        }
        capRest = View(this)
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = shape(c("#E2E8F0"), 6f)
            addView(capFill, LinearLayout.LayoutParams(0, match, 100f))
            addView(capRest, LinearLayout.LayoutParams(0, match, 0f))
        }
        card.addView(track, lp(match, dp(10), 0f, 0, 14, 0, 0))
        return card
    }

    private fun tileRow(col: LinearLayout): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
        }
        col.addView(row, lp(match, wrap, 0f, 0, 0, 0, 10))
        return row
    }

    private fun tileIn(row: LinearLayout, name: String, res: Int, tint: String, bg: String, help: String): Tile {
        val value = label("—", 16f, textDark, 2).apply { gravity = Gravity.CENTER }
        val pill = newPill()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = shape(Color.WHITE, 20f)
            elevation = dpf(2f)
            minimumHeight = dp(92)
            setPadding(dp(6), dp(10), dp(6), dp(10))
            addView(circleIcon(res, c(tint), c(bg), 34, 8))
            addView(label(name, 12f, c("#334155")).apply { gravity = Gravity.CENTER }, lp(wrap, wrap, 0f, 0, 6, 0, 0))
            addView(value, lp(wrap, wrap, 0f, 0, 3, 0, 0))
            addView(pill, lp(wrap, wrap, 0f, 0, 5, 0, 0))
            setOnClickListener { info(name, help) }
        }
        val first = row.childCount == 0
        row.addView(card, lp(0, match, 1f, if (first) 0 else 5, 0, if (row.childCount == 2) 0 else 5, 0))
        return Tile(value, pill, card)
    }

    private fun buildDevice(): View {
        val card = whiteCard().apply { setPadding(dp(14), dp(10), dp(14), dp(12)) }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(icon(R.drawable.ic_phone, blue, 18))
            addView(label("Device", 14f, textDark, 2), lp(wrap, wrap, 0f, 8, 0, 0, 0))
        })

        fun cell(res: Int, tint: String, name: String): Pair<View, TextView> {
            val v = label("—", 14f, textDark, 2)
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(icon(res, c(tint), 28))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(name, 10.5f, textMuted))
                    addView(v, lp(wrap, wrap, 0f, 0, 2, 0, 0))
                }, lp(0, wrap, 1f, 8, 0, 0, 0))
            }
            return box to v
        }
        val a = cell(R.drawable.ic_android, "#3DDC84", "Android version")
        val m = cell(R.drawable.ic_factory, "#2563EB", "Manufacturer")
        val d = cell(R.drawable.ic_phone, "#0EA5E9", "Model")
        devAndroid = a.second; devMaker = m.second; devModel = d.second

        fun bar() = View(this).apply { setBackgroundColor(c("#E2E8F0")) }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(a.first, lp(0, wrap, 1f))
            addView(bar(), lp(dp(1), dp(30), 0f, 6, 0, 8, 0))
            addView(m.first, lp(0, wrap, 1f))
            addView(bar(), lp(dp(1), dp(30), 0f, 6, 0, 8, 0))
            addView(d.first, lp(0, wrap, 1f))
        }, lp(match, wrap, 0f, 0, 10, 0, 0))

        devAndroid.text = Build.VERSION.RELEASE
        devMaker.text = Build.MANUFACTURER
        devModel.text = Build.MODEL
        return card
    }

    // ------------------------------------------------------------------ tabs

    private fun buildHistory(): View {
        val (scroll, col) = scaffold()
        pageTitle(col, "History", "Battery current over the last few minutes")

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = gradient("#16399F", "#0A1D5C", 26f, c("#3A5CC4"), 1f)
            elevation = dpf(6f)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        card.addView(label("Current (mA)", 16f, Color.WHITE, 2))
        graph = GraphView(this)
        card.addView(graph, lp(match, dp(220), 0f, 0, 12, 0, 0))
        card.addView(
            label("Green is charging, red is discharging.", 12f, heroMuted),
            lp(wrap, wrap, 0f, 0, 10, 0, 0)
        )
        col.addView(card, lp(match, wrap, 0f, 0, 0, 0, 14))

        val clear = whiteCard().apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }
        clear.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("History log", 16f, textDark, 2))
                addView(label("Delete the saved readings", 12.5f, textMuted), lp(wrap, wrap, 0f, 0, 3, 0, 0))
            }, lp(0, wrap, 1f))
            addView(button("Clear history", false) {
                History.clear(this@MainActivity)
                graph.setData(emptyList())
            })
        })
        col.addView(clear)
        return scroll
    }

    private fun buildAlerts(): View {
        val (scroll, col) = scaffold()
        pageTitle(col, "Alerts", "Get told when something is off")

        val card = whiteCard().apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }
        val sw = Switch(this).apply {
            isChecked = prefs.getBoolean(MonitorService.KEY_SLOW_ALERT, false)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(MonitorService.KEY_SLOW_ALERT, on).apply()
                alertsBadge.visibility = if (on) View.GONE else View.VISIBLE
            }
        }
        alertsBadge.visibility = if (sw.isChecked) View.GONE else View.VISIBLE
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(circleIcon(R.drawable.ic_bell, c("#F97316"), c("#FDE8D3"), 40, 9))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("Slow charge alert", 16f, textDark, 2))
                addView(
                    label("Notify me when charging drops under ${MonitorService.SLOW_CHARGE_MA} mA", 12.5f, textMuted),
                    lp(wrap, wrap, 0f, 0, 3, 0, 0)
                )
            }, lp(0, wrap, 1f, 12, 0, 8, 0))
            addView(sw)
        })
        col.addView(card)
        return scroll
    }

    private fun buildSettings(): View {
        val (scroll, col) = scaffold()
        pageTitle(col, "Settings", "Keep the monitor running")

        monitorBtn = button("", true) { toggleMonitor() }
        col.addView(settingCard(
            R.drawable.ic_pulse, "#16A34A", "#D5F5DF", "Background monitor",
            "Keeps reading the battery with the screen off. The live rate shows in a notification.",
            monitorBtn
        ), lp(match, wrap, 0f, 0, 0, 0, 14))

        col.addView(settingCard(
            R.drawable.ic_bolt, "#2563EB", "#DCEBFE", "Keep running in background",
            "Stops Android from pausing the monitor to save battery.",
            button("Allow", false) { requestUnrestricted() }
        ))
        updateMonitorButton()
        return scroll
    }

    private fun settingCard(res: Int, tint: String, bg: String, title: String, desc: String, action: View): View {
        val card = whiteCard().apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(circleIcon(res, c(tint), c(bg), 40, 9))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(title, 16f, textDark, 2))
                addView(label(desc, 12.5f, textMuted), lp(wrap, wrap, 0f, 0, 3, 0, 0))
            }, lp(0, wrap, 1f, 12, 0, 0, 0))
        })
        card.addView(action, lp(wrap, wrap, 0f, 0, 12, 0, 0).apply { gravity = Gravity.END })
        return card
    }

    // ----------------------------------------------------------------- refresh

    private fun refresh() {
        if (pages[0] == null) return
        val r = reader.read()
        if (r == null) {
            pctText.text = "—"
            bigMa.text = "—"
            usePill.text = "Not available"
            statusPillText.text = "No data"
            return
        }
        Lifetime.update(this, r.mA)

        // Hero
        val pct = SpannableStringBuilder(
            if (r.exactKnown) String.format(Locale.US, "%.2f%%", r.percentExact)
            else String.format(Locale.US, "%.0f%%", r.percent)
        )
        pct.setSpan(ForegroundColorSpan(c("#4ADE80")), pct.length - 1, pct.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        pctText.text = pct
        statusPillText.text = r.statusText
        gauge.setValue(r.mA)
        usePill.text = useLabel(r)

        val big = SpannableStringBuilder(Fmt.signedMa(r.mA))
        val unitStart = big.length
        big.append(" mA")
        big.setSpan(AbsoluteSizeSpan(16, true), unitStart, big.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        big.setSpan(ForegroundColorSpan(heroMuted), unitStart, big.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        bigMa.text = big

        pphTitle.text = if (r.pctPerHour.isNaN()) "— per hour" else
            String.format(Locale.US, if (r.pctPerHour >= 10f) "%.0f%% per hour" else "%.1f%% per hour", r.pctPerHour)
        pphSub.text = r.statusText
        pphWatts.text = String.format(Locale.US, "%.1f W", abs(r.avgWatts))
        etaTitle.text = Fmt.duration(r.etaHours)
        etaSub.text = if (r.etaHours.isNaN() || r.etaHours.isInfinite() || r.etaHours > 99f || r.etaHours < 0f) "—" else {
            val at = SimpleDateFormat("h:mm a", Locale.getDefault())
                .format(Date(System.currentTimeMillis() + (r.etaHours * 3_600_000f).toLong()))
                .lowercase(Locale.getDefault())
            if (r.charging) "Full by $at" else "Lasts until $at"
        }

        lifeMax.text = labelValue("Lifetime max  :  ", "${Fmt.signedMa(Lifetime.maxCharge(this))} mA")
        lifeMin.text = labelValue("Lifetime min  :  ", "${Fmt.signedMa(Lifetime.maxDischarge(this))} mA")

        // Capacity
        val healthPct = if (r.soh > 0) r.soh else if (r.healthText == "Good") 100 else 0
        capPill.text = if (r.soh > 0) "Health ${r.soh}%" else "Health ${r.healthText}"
        (capFill.layoutParams as LinearLayout.LayoutParams).weight = healthPct.toFloat()
        (capRest.layoutParams as LinearLayout.LayoutParams).weight = (100 - healthPct).toFloat()
        (capFill.parent as View).requestLayout()

        // Tiles
        val volts = r.mV / 1000.0
        tVoltage.value.text = String.format(Locale.US, "%.2f V", volts)
        if (volts in 3.3..4.45) stylePill(tVoltage.pill, "Normal", Kind.GOOD)
        else stylePill(tVoltage.pill, if (volts < 3.3) "Low" else "High", Kind.WARN)

        tTemp.value.text = String.format(Locale.US, "%.1f °C", r.tempC)
        when {
            r.tempC < 15f -> stylePill(tTemp.pill, "Cool", Kind.INFO)
            r.tempC < 35f -> stylePill(tTemp.pill, "Normal", Kind.GOOD)
            r.tempC < 40f -> stylePill(tTemp.pill, "Warm", Kind.INFO)
            else -> stylePill(tTemp.pill, "Hot", Kind.BAD)
        }

        tLevel.value.text = String.format(Locale.US, "%.0f%%", r.percent)
        when {
            r.percent >= 40f -> stylePill(tLevel.pill, "Good", Kind.GOOD)
            r.percent >= 15f -> stylePill(tLevel.pill, "Low", Kind.WARN)
            else -> stylePill(tLevel.pill, "Critical", Kind.BAD)
        }

        val w = abs(r.watts)
        tPower.value.text = String.format(Locale.US, "%.2f W", r.watts)
        if (r.charging) {
            when {
                w < 5.0 -> stylePill(tPower.pill, "Slow charge", Kind.WARN)
                w < 15.0 -> stylePill(tPower.pill, "Charging", Kind.GOOD)
                else -> stylePill(tPower.pill, "Fast charge", Kind.GOOD)
            }
        } else {
            when {
                w < 1.0 -> stylePill(tPower.pill, "Light use", Kind.GOOD)
                w < 5.0 -> stylePill(tPower.pill, "Normal use", Kind.GOOD)
                else -> stylePill(tPower.pill, "Heavy use", Kind.WARN)
            }
        }

        tStatus.value.text = r.statusText
        tStatus.value.textSize = 15f
        tPlugged.value.text = r.plugName
        tPlugged.value.textSize = 15f

        if (r.soh > 0) {
            tHealth.value.text = "${r.soh}%"
            when {
                r.soh >= 80 -> stylePill(tHealth.pill, "Good", Kind.GOOD)
                r.soh >= 60 -> stylePill(tHealth.pill, "Fair", Kind.WARN)
                else -> stylePill(tHealth.pill, "Weak", Kind.BAD)
            }
        } else {
            tHealth.value.text = r.healthText
            tHealth.pill.visibility = View.GONE
        }

        tTech.value.text = r.tech
        tCycles.value.text = if (r.cycles >= 0) r.cycles.toString() else "—"

        // History tab
        if (tab == 1 && ticks % 10 == 0) graph.setData(History.load(this))
        ticks++
    }

    private fun useLabel(r: Reading): String {
        val a = abs(r.mA)
        return if (r.charging) when {
            a < 1000 -> "Slow charge"
            a < 2000 -> "Charging"
            else -> "Fast charge"
        } else when {
            a < 300 -> "Light use"
            a < 500 -> "Moderate use"
            else -> "Heavy use"
        }
    }

    private fun labelValue(label: String, value: String): CharSequence {
        val sb = SpannableStringBuilder(label)
        sb.setSpan(ForegroundColorSpan(heroMuted), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val start = sb.length
        sb.append(value)
        sb.setSpan(ForegroundColorSpan(Color.WHITE), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    // --------------------------------------------------------------- cool down

    private fun showCoolDown() {
        val temp = reader.read()?.tempC
        val verdict = when {
            temp == null -> "Temperature unavailable"
            temp < 35f -> "%.1f °C, normal".format(Locale.US, temp)
            temp < 40f -> "%.1f °C, warm".format(Locale.US, temp)
            else -> "%.1f °C, hot".format(Locale.US, temp)
        }
        val ink = Color.parseColor("#0F1B3D")
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(6))
        }
        col.addView(label("Cool down", 20f, ink, 2))
        col.addView(label(verdict, 14f, textMuted, 1), lp(wrap, wrap, 0f, 0, 4, 0, 10))
        col.addView(
            label(
                "Android does not let any app force-close other apps, so this cannot switch off " +
                    "whatever is heating the phone. It can ask Android to end other apps' background " +
                    "processes, and it shows which apps you have used most so you can stop them yourself.",
                12.5f, textMuted
            ),
            lp(wrap, wrap, 0f, 0, 0, 0, 14)
        )
        col.addView(button("Close background apps", true) {
            val n = CoolDown.closeBackgroundApps(this)
            Toast.makeText(this, "Asked Android to close background apps ($n checked)", Toast.LENGTH_LONG).show()
        }, lp(match, wrap))

        col.addView(label("Most used in the last 3 hours", 14f, ink, 2), lp(wrap, wrap, 0f, 0, 18, 0, 6))
        if (!CoolDown.hasUsageAccess(this)) {
            col.addView(
                label("Allow usage access to see which apps you have been using.", 12.5f, textMuted),
                lp(wrap, wrap, 0f, 0, 0, 0, 8)
            )
            col.addView(button("Allow usage access", false) {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }, lp(match, wrap))
        } else {
            val apps = CoolDown.mostUsedApps(this)
            if (apps.isEmpty()) {
                col.addView(label("No heavy use found.", 12.5f, textMuted))
            } else {
                col.addView(
                    label("Tap an app, then Force stop.", 12.5f, textMuted),
                    lp(wrap, wrap, 0f, 0, 0, 0, 6)
                )
                for (a in apps) {
                    col.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = shape(c("#F1F5F9"), 14f)
                        setPadding(dp(14), dp(11), dp(14), dp(11))
                        addView(label(a.label, 14f, ink, 1), lp(0, wrap, 1f))
                        addView(label("${a.minutes} min", 12.5f, textMuted))
                        setOnClickListener {
                            startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}"))
                            )
                        }
                    }, lp(match, wrap, 0f, 0, 0, 0, 6))
                }
            }
        }
        col.addView(
            label("Also try: unplug the charger, remove the case, lower the brightness.", 12f, textMuted),
            lp(wrap, wrap, 0f, 0, 14, 0, 6)
        )

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Done", null)
            .show()
    }

    // ----------------------------------------------------------------- monitor

    private fun startMonitor() {
        prefs.edit().putBoolean(MonitorService.KEY_MONITOR_ON, true).apply()
        try {
            startForegroundService(Intent(this, MonitorService::class.java))
        } catch (e: Exception) {
            Toast.makeText(this, "Could not start monitor: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopMonitor() {
        prefs.edit().putBoolean(MonitorService.KEY_MONITOR_ON, false).apply()
        stopService(Intent(this, MonitorService::class.java))
    }

    private fun toggleMonitor() {
        if (prefs.getBoolean(MonitorService.KEY_MONITOR_ON, true)) stopMonitor() else startMonitor()
        updateMonitorButton()
    }

    private fun updateMonitorButton() {
        if (!::monitorBtn.isInitialized) return
        monitorBtn.text = if (prefs.getBoolean(MonitorService.KEY_MONITOR_ON, true))
            "Stop background monitor" else "Start background monitor"
    }

    private fun requestUnrestricted() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Already unrestricted", Toast.LENGTH_SHORT).show()
        } else {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }
    }
}
