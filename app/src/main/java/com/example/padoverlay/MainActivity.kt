package com.example.padoverlay

import android.app.Activity
import android.app.StatusBarManager
import android.graphics.drawable.Icon
import android.app.AlertDialog
import android.graphics.Color
import android.widget.SeekBar
import android.content.pm.ApplicationInfo
import android.widget.FrameLayout
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.GridLayout
import android.widget.ImageView
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.DisplayMetrics
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var keyText: TextView
    private lateinit var axisText: TextView
    private lateinit var scroll: ScrollView
    private lateinit var resultText: TextView
    private lateinit var gridBox: FrameLayout
    private var fgColor = 0

    // 0 = follow system, 1 = light, 2 = dark
    private fun themeMode() = getSharedPreferences("ui", MODE_PRIVATE).getInt("theme", 0)

    private fun isNight(): Boolean = when (themeMode()) {
        1 -> false
        2 -> true
        else -> Build.VERSION.SDK_INT >= 29 &&
                (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(
            when (themeMode()) {
                1 -> R.style.AppThemeLight
                2 -> R.style.AppThemeDark
                else -> R.style.AppTheme
            }
        )
        super.onCreate(savedInstanceState)

        val night = isNight()
        val bg = if (night) 0xFF121212.toInt() else 0xFFFFFFFF.toInt()
        val fg = if (night) 0xFFEDEDED.toInt() else 0xFF1B1B1B.toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 48)
        }
        fun btn(label: String, action: () -> Unit) {
            col.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { action() }
            })
        }
        fun title(t: String) {
            col.addView(TextView(this).apply {
                text = t
                textSize = 16f
                setTextColor(fg)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 48, 0, 12)
            })
        }
        fun body(t: String) {
            col.addView(TextView(this).apply {
                text = t
                textSize = 14f
                setTextColor(fg)
            })
        }

        // ------------------------------------------------------ appearance
        // ------------------------------------------------------------ games grid
        title("GAMES")
        val modeNames = arrayOf("Virtual display", "Freeform window", "Normal")
        val launchMode = getSharedPreferences("ui", MODE_PRIVATE).getInt("launch_mode", 0).coerceIn(0, 2)
        body("Tap a game to launch it with the settings below (launch mode: ${modeNames[launchMode]}). " +
                "Games are found automatically. Long-press a game for other options, or tap + to add one by hand.")
        fgColor = fg
        gridBox = FrameLayout(this)
        gridBox.addView(buildGrid(fg))
        col.addView(gridBox)
        val autoOn = getSharedPreferences("ui", MODE_PRIVATE).getInt("auto_games", 1) == 1
        btn("Find games now") { autoScan(true) }
        btn("Auto-detect games: ${if (autoOn) "On" else "Off"}  (tap to change)") {
            getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("auto_games", if (autoOn) 0 else 1).apply()
            recreate()
        }
        if (autoOn) autoScan(false)

        title("TROUBLESHOOTING")
        body("If the phone feels slow or the keyboard stops working, tap Clean up. " +
                "Check shows what is running (displays, pad helpers, changed settings).")
        btn("Clean up (stop everything, undo system settings)") { cleanUp() }
        btn("Check displays and helpers") { helperCall(InputService.CODE_STATUS, false) }

        title("APPEARANCE")
        val label = when (themeMode()) { 1 -> "Light"; 2 -> "Dark"; else -> "Auto (follow system)" }
        btn("Theme: $label  (tap to change)") {
            getSharedPreferences("ui", MODE_PRIVATE).edit()
                .putInt("theme", (themeMode() + 1) % 3).apply()
            recreate()
        }

        // ------------------------------------------------------ input mode
        title("INPUT MODE")
        val ui = getSharedPreferences("ui", MODE_PRIVATE)
        btn("On-screen controls: ${if (ui.getInt("controls_on", 1) == 1) "On" else "Off (real controller; Back button exits)"}  (tap to change)") {
            ui.edit().putInt("controls_on", 1 - ui.getInt("controls_on", 1)).apply()
            toast("Stop and start the overlay to apply")
            recreate()
        }
        val sizeNames = arrayOf("Auto", "Small", "Medium", "Large", "Extra large")
        btn("Controls size: ${sizeNames[ui.getInt("ctrl_size", 0).coerceIn(0, 4)]}  (tap to change)") {
            ui.edit().putInt("ctrl_size", (ui.getInt("ctrl_size", 0) + 1) % 5).apply()
            // positions are re-spaced for the new size
            getSharedPreferences("pad", MODE_PRIVATE).edit().clear().apply()
            toast("Applies the next time you launch")
            recreate()
        }
        val modeLabel = if (ui.getInt("mode", 0) == 1) "Touch mapping" else "Gamepad (HID)"
        btn("Mode: $modeLabel  (tap to change)") {
            ui.edit().putInt("mode", 1 - ui.getInt("mode", 0)).apply()
            toast("Stop and start the overlay to apply")
            recreate()
        }
        body(
            "Gamepad: acts as a real controller (works in games with controller support).\n" +
            "Touch mapping: taps the game's own on-screen buttons. Use it for games that ignore " +
            "the gamepad, like Dead Cells. Start the overlay, tap EDIT, drag each round marker " +
            "onto the matching button in the game, then tap the save icon. The left stick acts " +
            "as the four arrow markers."
        )
        val profLabel = PadProfile.values()[ui.getInt("profile", 0).coerceIn(0, PadProfile.values().size - 1)].label
        btn("Controller type: $profLabel  (tap to change)") {
            ui.edit().putInt("profile", (ui.getInt("profile", 0) + 1) % PadProfile.values().size).apply()
            toast("Stop and start the overlay to apply")
            recreate()
        }
        body(
            "Controller type (Gamepad mode only):\n" +
            "Generic: plain controller.\n" +
            "Xbox: appears as an Xbox One S controller; labels A B X Y, LB RB LT RT, VIEW, MENU.\n" +
            "DualShock 4: PlayStation symbols and SHARE / OPTIONS labels, appears as 'Wireless Controller'.\n" +
            "DualSense (PS5): same symbols, CREATE / OPTIONS labels, appears as 'DualSense Wireless Controller'.\n" +
            "If Xbox is not accepted by the phone it falls back to Generic by itself."
        )

        // ---------------------------------------------------------- setup
        title("SETUP")
        btn("1. Allow overlay") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        btn("2. Allow Shizuku") {
            if (!Shizuku.pingBinder()) toast("Start Shizuku first")
            else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
                toast("Already granted")
            else Shizuku.requestPermission(1)
        }
        btn("3. Start overlay") { startOverlay() }
        btn("Stop overlay") { stopService(Intent(this, OverlayService::class.java)) }
        btn("Add quick setting tile") { addTile() }
        btn("Reset layout") {
            getSharedPreferences("pad", MODE_PRIVATE).edit().clear().apply()
            toast("Layout reset. Stop and start the overlay again.")
        }

        // ------------------------------------------------------ game window
        title("GAME WINDOW (WORKS ON ANY PHONE)")
        body(
            "Runs the game in a centred freeform window so the empty sides are free for the controls. " +
            "Before you start: Shizuku running, phone in landscape, and the game fully closed. " +
            "A black surround covers everything outside the game so stray taps do nothing; " +
            "close the overlay with the X on the right menu."
        )
        btn("Launch mode: ${modeNames[launchMode]}  (tap to change)") {
            ui.edit().putInt("launch_mode", (launchMode + 1) % modeNames.size).apply()
            recreate()
        }
        btn("Game: ${ui.getString("game_name", "none")}  (tap to pick)") { pickGame() }
        btn("Window shape: ${Win.LABELS[ui.getInt("aspect", 0).coerceIn(0, Win.LABELS.size - 1)]}  (tap to change)") {
            // listed from the widest bars to the thinnest
            val order = Win.LABELS.indices.sortedBy { Win.ratio(it) }
            val items = Array(order.size) { k -> "${Win.LABELS[order[k]]}  -  ${Win.NOTES[order[k]]}" }
            AlertDialog.Builder(this)
                .setTitle("Window shape (narrower = more black at the sides)")
                .setItems(items) { _, k ->
                    ui.edit().putInt("aspect", order[k]).remove("win_cr").apply()
                    recreate()
                }
                .show()
        }
        btn("Layout: ${if (ui.getInt("win_mode", 0) == 1) "Window bars" else "Fixed bars (Game Space)"}  (tap to change)") {
            ui.edit().putInt("win_mode", 1 - ui.getInt("win_mode", 0)).apply()
            toast("Stop and start the overlay to apply")
            recreate()
        }
        btn("Black surround: ${if (ui.getInt("backdrop", 1) == 1) "On" else "Off"}  (tap to change)") {
            ui.edit().putInt("backdrop", 1 - ui.getInt("backdrop", 1)).apply()
            toast("Stop and start the overlay to apply")
            recreate()
        }
        btn("Edge lines: ${arrayOf("Off", "Thin", "Normal", "Thick")[ui.getInt("edge_line", 2).coerceIn(0, 3)]}  (tap to change)") {
            ui.edit().putInt("edge_line", (ui.getInt("edge_line", 2) + 1) % 4).apply()
            toast("Applies the next time you launch")
            recreate()
        }
        btn("Edge line color: ${if (ui.getInt("edge_mode", 0) == 0) "Gradient" else String.format("#%06X", ui.getInt("edge_color", 0xFF3CC8E6.toInt()) and 0xFFFFFF)}  (tap to change)") {
            pickEdgeColor()
        }
        btn("Bar background: ${arrayOf("Black", "Gradient", "Image")[ui.getInt("bar_mode", 0).coerceIn(0, 2)]}  (tap to change)") {
            pickBarBackground()
        }
        btn("Close game first: ${if (ui.getInt("force_close", 1) == 1) "On" else "Off"}  (tap to change)") {
            ui.edit().putInt("force_close", 1 - ui.getInt("force_close", 1)).apply()
            recreate()
        }
        btn("Launch game in window + start overlay") { launchWindowed() }
        resultText = TextView(this).apply {
            text = ui.getString("last_launch", "") ?: ""
            textSize = 13f
            setTextColor(fg)
            setPadding(0, 12, 0, 0)
        }
        col.addView(resultText)
        btn("Picture quality (virtual display): ${ui.getInt("vd_scale", 100)}%  (tap to change)") {
            val cur = ui.getInt("vd_scale", 100)
            ui.edit().putInt("vd_scale", when (cur) { 100 -> 75; 75 -> 50; else -> 100 }).apply()
            recreate()
        }
        btn("Refresh rate (virtual display): ${ui.getInt("vd_hz", 60).let { if (it == 0) "Max (same as screen)" else "$it Hz" }}  (tap to change)") {
            val cur = ui.getInt("vd_hz", 60)
            ui.edit().putInt("vd_hz", when (cur) { 60 -> 90; 90 -> 120; 120 -> 0; else -> 60 }).apply()
            recreate()
        }
        btn("Screen brightness (virtual display): ${ui.getInt("vd_bright", 0).let { if (it == 0) "System" else "$it%" }}  (tap to change)") {
            val cur = ui.getInt("vd_bright", 0)
            ui.edit().putInt("vd_bright", when (cur) { 0 -> 100; 100 -> 85; 85 -> 70; else -> 0 }).apply()
            recreate()
        }
        btn("Picture boost (virtual display): ${ui.getInt("vd_boost", 100).let { if (it <= 100) "Off" else "${it}%" }}  (tap to change)") {
            val cur = ui.getInt("vd_boost", 100)
            ui.edit().putInt("vd_boost", when (cur) { 100 -> 115; 115 -> 130; 130 -> 150; else -> 100 }).apply()
            recreate()
        }
        btn("Touch forwarding (virtual display): ${if (ui.getInt("touch_mode", 0) == 0) "Exact copy" else "Simple"}  (tap to change)") {
            ui.edit().putInt("touch_mode", 1 - ui.getInt("touch_mode", 0)).apply()
            recreate()
        }
        btn("Fast input (virtual display, beta): ${if (ui.getInt("fast_input", 0) == 1) "On" else "Off"}  (tap to change)") {
            ui.edit().putInt("fast_input", 1 - ui.getInt("fast_input", 0)).apply()
            toast("Stop and start the overlay to apply")
            recreate()
        }
        btn("Launch game on virtual display (beta)") { launchVirtual() }
        btn("Test virtual display (experimental)") { testVirtualDisplay() }

        // ------------------------------------------------------- MacroDroid
        title("MACRODROID: AUTO START / STOP")
        body(
            "1. New macro. Trigger: Applications > Application Launched, pick your game.\n" +
            "2. Action: Applications > Send Intent.\n" +
            "3. Target: Broadcast Receiver.\n" +
            "4. Action, Package and Class: tap the copy buttons below, then paste each one into MacroDroid.\n" +
            "5. Make a second macro. Trigger: Application Closed, same game. Same intent, but copy the STOP action.\n\n" +
            "If nothing happens, set Target to Activity and use the activity class instead. " +
            "Also turn off battery optimization for this app, and keep Shizuku running."
        )
        btn("Copy START action") { copy(OverlayControl.ACTION_START) }
        btn("Copy STOP action") { copy(OverlayControl.ACTION_STOP) }
        btn("Copy package") { copy(packageName) }
        btn("Copy receiver class") { copy(ControlReceiver::class.java.name) }
        btn("Copy activity class (backup)") { copy(ControlActivity::class.java.name) }
        btn("Battery optimization settings") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        // ------------------------------------------------------- input test
        title("INPUT TEST")
        body("With the overlay running, press the on-screen controls while this screen is open.")
        keyText = TextView(this).apply { text = "key: -"; setTextColor(fg); setPadding(0, 12, 0, 0) }
        axisText = TextView(this).apply { text = "axes: -"; setTextColor(fg) }
        col.addView(keyText)
        col.addView(axisText)

        // Scrollable, and kept clear of the status bar / camera cutout / nav bar
        scroll = ScrollView(this).apply {
            addView(col)
            clipToPadding = false
            setBackgroundColor(bg)
            setOnApplyWindowInsetsListener { v, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val i = insets.getInsets(
                        WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                    )
                    v.setPadding(i.left, i.top, i.right, i.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    v.setPadding(
                        insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight, insets.systemWindowInsetBottom
                    )
                }
                insets
            }
        }
        setContentView(scroll)
        applySystemBars(night, bg)

        // Buttons that refresh the screen (recreate) used to jump back to the top.
        // Put the scroll position back once the layout is ready.
        val savedY = savedInstanceState?.getInt("scrollY", 0) ?: 0
        if (savedY > 0) {
            scroll.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    scroll.viewTreeObserver.removeOnPreDrawListener(this)
                    scroll.scrollTo(0, savedY)
                    return true
                }
            })
        }
    }

    /** Dark status/navigation bar icons on a light page, light icons on a dark page. */
    @Suppress("DEPRECATION")
    private fun applySystemBars(night: Boolean, bg: Int) {
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = bg
            window.navigationBarColor = bg
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (night) 0 else mask, mask)
        } else {
            val light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            val v = window.decorView
            v.systemUiVisibility =
                if (night) v.systemUiVisibility and light.inv() else v.systemUiVisibility or light
        }
    }

    // ------------------------------------------------------------ test screen
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::scroll.isInitialized) outState.putInt("scrollY", scroll.scrollY)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.repeatCount == 0) {
            keyText.text = "key: ${if (e.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"} " +
                    "${KeyEvent.keyCodeToString(e.keyCode)}  src=0x${Integer.toHexString(e.source)}  " +
                    "dev=${e.device?.name ?: "none"}"
        }
        return super.dispatchKeyEvent(e)
    }

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_MOVE && e.source and 0x10 != 0) {
            fun a(axis: Int) = String.format("%.2f", e.getAxisValue(axis))
            axisText.text = "axes: X=${a(MotionEvent.AXIS_X)} Y=${a(MotionEvent.AXIS_Y)} " +
                    "Z=${a(MotionEvent.AXIS_Z)} RZ=${a(MotionEvent.AXIS_RZ)}\n" +
                    "LT=${a(MotionEvent.AXIS_LTRIGGER)} RT=${a(MotionEvent.AXIS_RTRIGGER)} " +
                    "BRAKE=${a(MotionEvent.AXIS_BRAKE)} GAS=${a(MotionEvent.AXIS_GAS)} " +
                    "HAT=${a(MotionEvent.AXIS_HAT_X)},${a(MotionEvent.AXIS_HAT_Y)}"
        }
        return super.dispatchGenericMotionEvent(e)
    }

    // ---------------------------------------------------------------- helpers
    // ------------------------------------------------------- game window helpers
    private var svc: IBinder? = null
    private var pending: ((IBinder) -> Unit)? = null
    private val svcConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            svc = binder
            pending?.invoke(binder)
            pending = null
        }
        override fun onServiceDisconnected(name: ComponentName) {
            svc = null
        }
    }

    private fun withService(block: (IBinder) -> Unit) {
        val b = svc
        if (b != null && b.isBinderAlive) {
            block(b)
            return
        }
        pending = block
        Shizuku.bindUserService(PadService.args(this), svcConn)
    }

    override fun onDestroy() {
        runCatching { Shizuku.unbindUserService(PadService.args(this), svcConn, false) }
        super.onDestroy()
    }

    private fun screenPx(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) {
            val b = windowManager.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }

    // ------------------------------------------------------------------ games grid
    private fun installed(pkg: String): Boolean =
        try {
            packageManager.getApplicationInfo(pkg, 0)
            true
        } catch (e: Exception) {
            false
        }

    private fun loadGrid(): MutableList<String> {
        val raw = getSharedPreferences("ui", MODE_PRIVATE).getString("grid", "") ?: ""
        return raw.split(",").filter { it.isNotBlank() && installed(it) }.toMutableList()
    }

    private fun saveGrid(list: List<String>) {
        getSharedPreferences("ui", MODE_PRIVATE).edit().putString("grid", list.joinToString(",")).apply()
    }

    private fun gridTile(
        icon: Drawable?, label: String, fg: Int, click: () -> Unit, onLong: (() -> Unit)?
    ): View {
        val d = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt())
            val tv = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            isClickable = true
            setOnClickListener { click() }
            if (onLong != null) setOnLongClickListener {
                onLong()
                true
            }
        }
        val size = (52 * d).toInt()
        if (icon != null) {
            box.addView(ImageView(this).apply { setImageDrawable(icon) }, LinearLayout.LayoutParams(size, size))
        } else {
            box.addView(
                TextView(this).apply {
                    text = "+"
                    textSize = 30f
                    gravity = Gravity.CENTER
                    setTextColor(fg)
                },
                LinearLayout.LayoutParams(size, size)
            )
        }
        box.addView(TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(fg)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        return box
    }

    private fun buildGrid(fg: Int): GridLayout {
        val d = resources.displayMetrics.density
        val cols = ((resources.displayMetrics.widthPixels / d - 32) / 84).toInt().coerceIn(3, 8)
        val grid = GridLayout(this)
        grid.columnCount = cols

        fun add(v: View) {
            val lp = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f))
            lp.width = 0
            lp.setGravity(Gravity.FILL_HORIZONTAL)
            grid.addView(v, lp)
        }

        val modeNow = getSharedPreferences("ui", MODE_PRIVATE).getInt("launch_mode", 0)
        for (pkg in loadGrid()) {
            val info = packageManager.getApplicationInfo(pkg, 0)
            val name = packageManager.getApplicationLabel(info).toString()
            add(gridTile(packageManager.getApplicationIcon(info), name, fg,
                { launchGame(pkg, name, modeNow) },
                { gameMenu(pkg, name) }))
        }
        add(gridTile(null, "Add", fg, { pickApp("Add a game to the grid") { pkg, _ ->
            val list = loadGrid()
            if (pkg !in list) list.add(pkg)
            saveGrid(list)
            setRemoved(removedSet() - pkg)
            recreate()
        } }, null))
        return grid
    }

    // ------------------------------------------------------------ finding games automatically
    private val engineLibs = setOf(
        "libunity.so", "libil2cpp.so", "libUE4.so", "libUnreal.so", "libgodot_android.so",
        "libcocos2dcpp.so", "libcocos2djs.so", "libcocos.so", "libgdx.so", "libdmengine.so",
        "libyoyo.so", "libhl.so", "libSDL2.so", "libgame.so", "libEngine.so"
    )

    private fun removedSet(): Set<String> =
        (getSharedPreferences("ui", MODE_PRIVATE).getString("grid_removed", "") ?: "")
            .split(",").filter { it.isNotBlank() }.toSet()

    private fun setRemoved(set: Set<String>) {
        getSharedPreferences("ui", MODE_PRIVATE).edit().putString("grid_removed", set.joinToString(",")).apply()
    }

    private fun refreshGrid() {
        if (isDestroyed || isFinishing) return
        gridBox.removeAllViews()
        gridBox.addView(buildGrid(fgColor))
    }

    /** Looks inside the app's files for a well-known game engine library. */
    private fun hasGameEngine(ai: ApplicationInfo): Boolean {
        val paths = ArrayList<String>()
        ai.sourceDir?.let { paths.add(it) }
        ai.splitSourceDirs?.let { paths.addAll(it) }
        for (p in paths) {
            try {
                java.util.zip.ZipFile(p).use { z ->
                    val entries = z.entries()
                    while (entries.hasMoreElements()) {
                        val n = entries.nextElement().name
                        if (n.startsWith("lib/") && n.substringAfterLast('/') in engineLibs) return true
                    }
                }
            } catch (_: Exception) {
            }
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun isLikelyGame(ai: ApplicationInfo): Boolean {
        if (ai.category == ApplicationInfo.CATEGORY_GAME) return true
        if ((ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0) return true
        val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
        return !system && hasGameEngine(ai)
    }

    /** Installed apps that look like games (declared as a game, or built with a known engine). */
    private fun detectGames(): List<String> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found = ArrayList<String>()
        for (ri in pm.queryIntentActivities(intent, 0)) {
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName || pkg in found) continue
            val ai = try {
                pm.getApplicationInfo(pkg, 0)
            } catch (e: Exception) {
                continue
            }
            if (isLikelyGame(ai)) found.add(pkg)
        }
        return found
    }

    /**
     * Scans in the background and adds new games to the grid.
     * Automatic scans skip games you removed; "Find games now" brings those back too.
     */
    private fun autoScan(manual: Boolean) {
        if (manual) toast("Looking for games...")
        Thread {
            val found = detectGames()
            if (manual) setRemoved(emptySet())
            val removed = removedSet()
            val list = loadGrid()
            val added = found.filter { it !in list && it !in removed }
            if (added.isNotEmpty()) {
                list.addAll(added)
                saveGrid(list)
            }
            runOnUiThread {
                if (added.isNotEmpty()) refreshGrid()
                if (manual) {
                    toast(
                        if (added.isEmpty()) "No new games found (${found.size} games detected)"
                        else "Added ${added.size} game(s)",
                        long = true
                    )
                }
            }
        }.start()
    }

    private fun pickApp(title: String, onPick: (String, String) -> Unit) {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .filter { it.second != packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
        if (apps.isEmpty()) {
            toast("No apps found")
            return
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(apps.map { it.first }.toTypedArray()) { _, i -> onPick(apps[i].second, apps[i].first) }
            .show()
    }

    private fun gameMenu(pkg: String, name: String) {
        val items = arrayOf(
            "Launch: Virtual display", "Launch: Freeform window", "Launch: Normal", "Remove from grid"
        )
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(items) { _, i ->
                when (i) {
                    0, 1, 2 -> launchGame(pkg, name, i)
                    else -> {
                        val list = loadGrid()
                        list.remove(pkg)
                        saveGrid(list)
                        setRemoved(removedSet() + pkg)
                        recreate()
                    }
                }
            }
            .show()
    }

    /** mode: 0 = virtual display, 1 = freeform window, 2 = normal (just start the overlay). */
    private fun launchGame(pkg: String, name: String, mode: Int) {
        getSharedPreferences("ui", MODE_PRIVATE).edit()
            .putString("game_pkg", pkg).putString("game_name", name).apply()
        when (mode) {
            0 -> launchVirtual()
            1 -> launchWindowed()
            else -> launchNormal(pkg)
        }
    }

    private fun launchNormal(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            toast("That app has no launcher entry")
            return
        }
        getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("vd_mode", 0).apply()
        stopService(Intent(this, OverlayService::class.java))
        Handler(Looper.getMainLooper()).postDelayed({
            startOverlay()
            startActivity(intent)
        }, 500)
    }

    private val edgeNames = arrayOf(
        "Gradient (default)", "Red", "Orange", "Yellow", "Green", "Cyan", "Blue", "Purple", "Pink", "White", "Custom..."
    )
    private val edgeColors = intArrayOf(
        0, 0xFFFF3B30.toInt(), 0xFFFF9500.toInt(), 0xFFFFD60A.toInt(), 0xFF34C759.toInt(),
        0xFF3CC8E6.toInt(), 0xFF0A84FF.toInt(), 0xFFAF52DE.toInt(), 0xFFFF2D92.toInt(), 0xFFFFFFFF.toInt()
    )

    private fun pickEdgeColor() {
        val ui = getSharedPreferences("ui", MODE_PRIVATE)
        AlertDialog.Builder(this)
            .setTitle("Edge line color")
            .setItems(edgeNames) { _, i ->
                when (i) {
                    0 -> {
                        ui.edit().putInt("edge_mode", 0).apply()
                        recreate()
                    }
                    edgeNames.size - 1 -> customEdgeColor()
                    else -> {
                        ui.edit().putInt("edge_mode", 1).putInt("edge_color", edgeColors[i]).apply()
                        recreate()
                    }
                }
            }
            .show()
    }

    /** Three sliders (red, green, blue) with a live preview. */
    private fun customEdgeColor() {
        val ui = getSharedPreferences("ui", MODE_PRIVATE)
        val start = ui.getInt("edge_color", 0xFF3CC8E6.toInt())
        val d = resources.displayMetrics.density
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding((24 * d).toInt(), (16 * d).toInt(), (24 * d).toInt(), 0)

        val swatch = View(this)
        swatch.setBackgroundColor(start)
        box.addView(swatch, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (40 * d).toInt()))

        val names = arrayOf("Red", "Green", "Blue")
        val bars = Array(3) { i ->
            SeekBar(this).apply {
                max = 255
                progress = (start shr (16 - 8 * i)) and 0xFF
            }
        }
        fun current() = Color.rgb(bars[0].progress, bars[1].progress, bars[2].progress)
        for (i in 0..2) {
            box.addView(TextView(this).apply {
                text = names[i]
                setPadding(0, (12 * d).toInt(), 0, 0)
            })
            bars[i].setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    swatch.setBackgroundColor(current())
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            box.addView(bars[i])
        }
        AlertDialog.Builder(this)
            .setTitle("Custom color")
            .setView(box)
            .setPositiveButton("Use") { _, _ ->
                ui.edit().putInt("edge_mode", 1).putInt("edge_color", current()).apply()
                recreate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------ what fills the bars beside the picture
    private val gradNames = arrayOf("Sunset", "Ocean", "Neon", "Aurora", "Purple haze", "Ember", "Midnight", "Candy")
    private val gradColors = arrayOf(
        intArrayOf(0xFFFF5E62.toInt(), 0xFFFF9966.toInt()),
        intArrayOf(0xFF2193B0.toInt(), 0xFF6DD5ED.toInt()),
        intArrayOf(0xFFFF00CC.toInt(), 0xFF333399.toInt()),
        intArrayOf(0xFF00C9FF.toInt(), 0xFF92FE9D.toInt()),
        intArrayOf(0xFF41295A.toInt(), 0xFF2F0743.toInt()),
        intArrayOf(0xFFF12711.toInt(), 0xFFF5AF19.toInt()),
        intArrayOf(0xFF0F2027.toInt(), 0xFF2C5364.toInt()),
        intArrayOf(0xFFD53369.toInt(), 0xFFCBAD6D.toInt())
    )

    private fun pickBarBackground() {
        val ui = getSharedPreferences("ui", MODE_PRIVATE)
        val items = arrayOf("Black", "Gradient (presets)", "Gradient (my own colors)", "Image from gallery")
        AlertDialog.Builder(this)
            .setTitle("Fill the bars beside the picture with")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> {
                        ui.edit().putInt("bar_mode", 0).apply()
                        recreate()
                    }
                    1 -> pickGradientPreset()
                    2 -> customGradient()
                    else -> pickBarImage()
                }
            }
            .show()
    }

    private fun pickGradientPreset() {
        val ui = getSharedPreferences("ui", MODE_PRIVATE)
        AlertDialog.Builder(this)
            .setTitle("Gradient")
            .setItems(gradNames) { _, i ->
                ui.edit().putInt("bar_mode", 1).putInt("bar_c1", gradColors[i][0])
                    .putInt("bar_c2", gradColors[i][1]).apply()
                recreate()
            }
            .show()
    }

    private fun customGradient() {
        val ui = getSharedPreferences("ui", MODE_PRIVATE)
        rgbDialog("First color", ui.getInt("bar_c1", 0xFF2A1D5C.toInt())) { c1 ->
            rgbDialog("Second color", ui.getInt("bar_c2", 0xFF0A1330.toInt())) { c2 ->
                ui.edit().putInt("bar_mode", 1).putInt("bar_c1", c1).putInt("bar_c2", c2).apply()
                recreate()
            }
        }
    }

    private fun pickBarImage() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "image/*"
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(i, 77)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 77 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Throwable) {
            }
            getSharedPreferences("ui", MODE_PRIVATE).edit()
                .putInt("bar_mode", 2).putString("bar_img", uri.toString()).apply()
            recreate()
        }
    }

    /** Three sliders with a live preview; calls [onUse] with the chosen colour. */
    private fun rgbDialog(title: String, start: Int, onUse: (Int) -> Unit) {
        val d = resources.displayMetrics.density
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding((24 * d).toInt(), (16 * d).toInt(), (24 * d).toInt(), 0)
        val swatch = View(this)
        swatch.setBackgroundColor(start)
        box.addView(swatch, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (40 * d).toInt()))
        val names = arrayOf("Red", "Green", "Blue")
        val bars = Array(3) { i ->
            SeekBar(this).apply {
                max = 255
                progress = (start shr (16 - 8 * i)) and 0xFF
            }
        }
        fun current() = Color.rgb(bars[0].progress, bars[1].progress, bars[2].progress)
        for (i in 0..2) {
            box.addView(TextView(this).apply {
                text = names[i]
                setPadding(0, (12 * d).toInt(), 0, 0)
            })
            bars[i].setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    swatch.setBackgroundColor(current())
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            box.addView(bars[i])
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Use") { _, _ -> onUse(current()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickGame() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .filter { it.second != packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
        if (apps.isEmpty()) return toast("No apps found")
        AlertDialog.Builder(this)
            .setTitle("Pick the game")
            .setItems(apps.map { it.first }.toTypedArray()) { _, i ->
                getSharedPreferences("ui", MODE_PRIVATE).edit()
                    .putString("game_pkg", apps[i].second)
                    .putString("game_name", apps[i].first)
                    .apply()
                recreate()
            }
            .show()
    }

    private fun launchWindowed() {
        val ui = getSharedPreferences("ui", MODE_PRIVATE)
        val pkg = ui.getString("game_pkg", null) ?: return toast("Pick a game first")
        if (!Settings.canDrawOverlays(this)) return toast("Allow overlay first (step 1)")
        if (!Shizuku.pingBinder()) return toast("Start Shizuku first")
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
            return toast("Allow Shizuku first (step 2)")
        val comp = packageManager.getLaunchIntentForPackage(pkg)?.component
            ?: return toast("That app has no launcher entry")
        val (sw, sh) = screenPx()
        if (sh > sw) return toast("Rotate the phone to landscape first")
        val bnd = Win.bounds(sw, sh, Win.ratio(ui.getInt("aspect", 0)))
        ui.edit().putInt("win_mode", 1).putInt("vd_mode", 0).apply()
        toast("Launching ${ui.getString("game_name", pkg)}...")
        withService { b ->
            Thread {
                val d = Parcel.obtain()
                val r = Parcel.obtain()
                val status = try {
                    d.writeString(comp.flattenToString())
                    d.writeString(pkg)
                    d.writeInt(bnd[0]); d.writeInt(bnd[1]); d.writeInt(bnd[2]); d.writeInt(bnd[3])
                    d.writeInt(ui.getInt("force_close", 1))
                    b.transact(InputService.CODE_LAUNCH, d, r, 0)
                    val msg = r.readString() ?: "No reply from the helper"
                    if (r.dataAvail() > 0 && r.readInt() == 1) {
                        val cl = r.readInt()
                        val ct = r.readInt()
                        val cr = r.readInt()
                        val cb = r.readInt()
                        ui.edit()
                            .putInt("win_cl", cl).putInt("win_ct", ct)
                            .putInt("win_cr", cr).putInt("win_cb", cb)
                            .putInt("win_sw", sw).putInt("win_sh", sh)
                            .apply()
                    }
                    msg
                } catch (e: Exception) {
                    "Error: $e"
                } finally {
                    d.recycle()
                    r.recycle()
                }
                runOnUiThread {
                    toast(status, long = true)
                    resultText.text = status
                    ui.edit().putString("last_launch", status).apply()
                    if (status.startsWith("OK")) restartOverlay()
                }
            }.start()
        }
    }

    /** Asks the Shizuku helper to do something and shows its answer under the Launch button. */
    private fun helperCall(code: Int, killAfter: Boolean) {
        if (!Shizuku.pingBinder()) return toast("Start Shizuku first")
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
            return toast("Allow Shizuku first (step 2)")
        withService { b ->
            Thread {
                val d = Parcel.obtain()
                val r = Parcel.obtain()
                val msg = try {
                    b.transact(code, d, r, 0)
                    r.readString() ?: "No reply from the helper"
                } catch (e: Exception) {
                    "Error: $e"
                } finally {
                    d.recycle()
                    r.recycle()
                }
                runOnUiThread {
                    resultText.text = msg
                    getSharedPreferences("ui", MODE_PRIVATE).edit().putString("last_launch", msg).apply()
                    if (killAfter) {
                        // stopping the helper process also removes anything it still owned
                        runCatching { Shizuku.unbindUserService(PadService.args(this), svcConn, true) }
                        svc = null
                    }
                    toast("Done")
                }
            }.start()
        }
    }

    private fun cleanUp() {
        stopService(Intent(this, OverlayService::class.java))
        sendBroadcast(Intent(MirrorActivity.ACTION_CLOSE).setPackage(packageName))
        helperCall(InputService.CODE_CLEANUP, true)
    }

    private fun launchVirtual() {
        val ui = getSharedPreferences("ui", MODE_PRIVATE)
        val pkg = ui.getString("game_pkg", null) ?: return toast("Pick a game first")
        if (!Settings.canDrawOverlays(this)) return toast("Allow overlay first (step 1)")
        if (!Shizuku.pingBinder()) return toast("Start Shizuku first")
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
            return toast("Allow Shizuku first (step 2)")
        val comp = packageManager.getLaunchIntentForPackage(pkg)?.component
            ?: return toast("That app has no launcher entry")
        val (sw, sh) = screenPx()
        if (sh > sw) return toast("Rotate the phone to landscape first")
        ui.edit().putInt("vd_mode", 1).putInt("win_mode", 1).remove("win_cr").apply()
        stopService(Intent(this, OverlayService::class.java))
        startActivity(
            Intent(this, MirrorActivity::class.java)
                .putExtra("component", comp.flattenToString())
                .putExtra("pkg", pkg)
        )
    }

    private fun testVirtualDisplay() {
        val ui = getSharedPreferences("ui", MODE_PRIVATE)
        val pkg = ui.getString("game_pkg", null) ?: return toast("Pick a game first")
        if (!Shizuku.pingBinder()) return toast("Start Shizuku first")
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
            return toast("Allow Shizuku first (step 2)")
        val comp = packageManager.getLaunchIntentForPackage(pkg)?.component
            ?: return toast("That app has no launcher entry")
        toast("Testing virtual display... (about 10 seconds)", long = true)
        withService { b ->
            Thread {
                val d = Parcel.obtain()
                val r = Parcel.obtain()
                val msg = try {
                    d.writeString(comp.flattenToString())
                    d.writeString(pkg)
                    b.transact(InputService.CODE_VDTEST, d, r, 0)
                    r.readString() ?: "No reply from the helper"
                } catch (e: Exception) {
                    "Error: $e"
                } finally {
                    d.recycle()
                    r.recycle()
                }
                runOnUiThread {
                    resultText.text = msg
                    ui.edit().putString("last_launch", msg).apply()
                    toast("Test finished", long = true)
                }
            }.start()
        }
    }

    private fun restartOverlay() {
        stopService(Intent(this, OverlayService::class.java))
        Handler(Looper.getMainLooper()).postDelayed({ startOverlay() }, 700)
    }

    private fun addTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            val sbm = getSystemService(StatusBarManager::class.java)
            sbm.requestAddTileService(
                ComponentName(this, OverlayTileService::class.java),
                "Pad overlay",
                Icon.createWithResource(this, R.drawable.ic_stat_pad),
                mainExecutor
            ) { result ->
                val added = result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                        result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                toast(if (added) "Tile added" else "Tile not added")
            }
        } else {
            toast("Open Quick Settings, tap the pencil icon and drag 'Pad overlay' in", long = true)
        }
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) return toast("Allow overlay first (step 1)")
        if (!Shizuku.pingBinder()) return toast("Start Shizuku first")
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
            return toast("Allow Shizuku first (step 2)")
        startForegroundService(Intent(this, OverlayService::class.java))
    }

    private fun copy(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("PadOverlay", text))
        toast("Copied: $text")
    }

    private fun toast(msg: String, long: Boolean = false) =
        Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}
