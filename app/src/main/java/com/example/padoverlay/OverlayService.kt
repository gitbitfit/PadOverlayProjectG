package com.example.padoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import rikka.shizuku.Shizuku

class OverlayService : Service(), PadHost {

    companion object {
        // Layout tuning for "free display" black bars (fractions of the landscape screen).
        // Bar width on each side, and the vertical band where the game-space arrow sits.
        private const val BAR_FRAC = 0.097f
        private const val ARROW_TOP = 0.457f
        private const val ARROW_BOT = 0.543f

        @Volatile
        var running = false
    }

    override var editing = false
    override var controlsOn = true
    override val helperReady: Boolean get() = input != null

    private class Slot(val pod: Pod, val view: PodView, var fx: Float, var fy: Float)
    private class MSlot(val target: TouchTarget, val view: MarkerView)

    private val slots = mutableListOf<Slot>()
    private val mslots = mutableListOf<MSlot>()
    private val markerPos = HashMap<TouchTarget, FloatArray>() // top-left, as fractions of the screen
    private val holds = HashMap<TouchTarget, Int>()
    private val stickDir = BooleanArray(4) // up, down, left, right (left stick as D-pad)

    private var touchMode = false
    private var profile = PadProfile.GENERIC
    private var winMode = false
    private var winRatio = 16f / 9f
    private var useBackdrop = true
    private var vdMode = false // game runs on a virtual display shown by MirrorActivity
    private var fastInput = false
    private val backdrop = ArrayList<View>() // black windows around the game window

    // window-mode layouts are saved separately from the fixed-bar layout
    private fun kp() = if (winMode) "w_" else ""
    private var input: InputClient? = null
    private var scale = 0.8f
    private var scaleTouched = false // resized with - / + in edit mode
    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences

    private val args by lazy { PadService.args(this) }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            input = InputClient(binder).also {
                // no virtual pad while the controls are hidden, so a real controller is the only pad
                it.setProfile(if (touchMode || !controlsOn) InputService.PROFILE_NONE else profile.ordinal)
                it.setFast(vdMode && fastInput)
            }
            refresh()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            input = null
            bound = false
            refresh()
        }
    }

    private var bound = false
    private var warnedPerm = false
    private val handler = Handler(Looper.getMainLooper())

    // While the helper is not connected (Shizuku stopped, e.g. after a restart) keep trying.
    private val retry = object : Runnable {
        override fun run() {
            if (input == null) {
                try {
                    if (Shizuku.pingBinder()) bindInput()
                } catch (_: Throwable) {
                }
                refresh()
                handler.postDelayed(this, 2000)
            }
        }
    }

    // Fires right away if Shizuku is already connected, or later once it is.
    private val binderListener = Shizuku.OnBinderReceivedListener { bindInput() }

    private fun bindInput() {
        if (bound) return
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            if (!warnedPerm) {
                warnedPerm = true
                Toast.makeText(this, "Open PadOverlay and tap 'Allow Shizuku', then the controls work", Toast.LENGTH_LONG).show()
            }
            return
        }
        bound = true
        Shizuku.bindUserService(args, conn)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("pad", MODE_PRIVATE)
        scale = prefs.getFloat("scale", 0.8f)
        val ui = getSharedPreferences("ui", MODE_PRIVATE)
        touchMode = ui.getInt("mode", 0) == 1
        profile = PadProfile.values()[ui.getInt("profile", 0).coerceIn(0, PadProfile.values().size - 1)]
        winMode = ui.getInt("win_mode", 0) == 1
        winRatio = Win.ratio(ui.getInt("aspect", 0))
        vdMode = ui.getInt("vd_mode", 0) == 1
        fastInput = ui.getInt("fast_input", 0) == 1
        controlsOn = ui.getInt("controls_on", 1) == 1
        useBackdrop = ui.getInt("backdrop", 1) == 1 && !vdMode
        var autoScale = 0.8f
        if (winMode) {
            // shrink the pods if the side bars are narrow
            val (a, b) = screenPx()
            val sw = maxOf(a, b)
            val sh = minOf(a, b)
            val bnd = contentRect(sw, sh)
            val bar = minOf(bnd[0], sw - bnd[2])
            if (bar > 0) {
                val fit = (bar * 0.94f) / (Pod.L_STICK.w * resources.displayMetrics.density)
                autoScale = minOf(0.8f, fit).coerceAtLeast(0.45f)
            }
        }
        // Controls size: a fixed size from the app, else the size saved in edit mode, else automatic
        val sizes = floatArrayOf(0f, 0.6f, 0.8f, 1.0f, 1.25f)
        val choice = ui.getInt("ctrl_size", 0).coerceIn(0, sizes.size - 1)
        scale = when {
            choice != 0 -> sizes[choice]
            prefs.contains("scale2") -> prefs.getFloat("scale2", 0.8f)
            else -> autoScale
        }
        startAsForeground()
        running = true

        Shizuku.addBinderReceivedListenerSticky(binderListener)
        if (!Shizuku.pingBinder()) {
            Toast.makeText(
                this, "Shizuku is not running. Start it and the controls connect by themselves.",
                Toast.LENGTH_LONG
            ).show()
        }
        handler.postDelayed(retry, 2000)

        for (t in TouchTarget.values()) {
            val d = defaultMarker(t)
            markerPos[t] = floatArrayOf(
                prefs.getFloat("m_${t.name}_x", d[0]),
                prefs.getFloat("m_${t.name}_y", d[1])
            )
        }

        // black surround first, so it sits below the pods
        if (winMode && useBackdrop) setupBackdrop()
        if (winMode && !vdMode) setupEdgeLines() // (virtual display mode draws them in MirrorActivity)

        val def = defaults()
        for (pod in Pod.values()) {
            // the HIDE/SHOW button is gone; with controls off nothing is drawn (Back exits)
            if (pod == Pod.TOGGLE || !controlsOn) continue
            val d = def.getValue(pod)
            val slot = Slot(
                pod,
                PodView(this, pod, this, profile),
                prefs.getFloat("${kp()}${pod.id}_x", d.first),
                prefs.getFloat("${kp()}${pod.id}_y", d.second)
            )
            slots += slot
            val lp = newParams(0, 0)
            place(lp, slot)
            wm.addView(slot.view, lp)
        }
        if (!controlsOn) applyControlsVisibility()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyAll() // re-place pods after rotation
        if (backdrop.isNotEmpty()) layoutBackdrop()
        layoutEdgeLines()
        for (m in mslots) {
            val lp = m.view.layoutParams as WindowManager.LayoutParams
            placeMarker(lp, m.target)
            wm.updateViewLayout(m.view, lp)
        }
    }

    // ------------------------------------------------------------ geometry

    private fun unit() = resources.displayMetrics.density * scale

    private fun markerSize() = (40 * resources.displayMetrics.density).toInt()

    private fun screenPx(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }

    private fun newParams(w: Int, h: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // let windows go under the status bar and camera cutout, right to the screen edge
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

    /** Default spots: pods stacked in the black bars, split around the arrow band. */
    private fun defaults(): Map<Pod, Pair<Float, Float>> {
        val (a, b) = screenPx()
        val sw = maxOf(a, b).toFloat()
        val sh = minOf(a, b).toFloat()
        val u = unit()
        var barL = sw * BAR_FRAC
        var barR = barL
        if (winMode) {
            // bars = whatever is left on each side of the centred game window
            val bnd = contentRect(sw.toInt(), sh.toInt())
            barL = bnd[0].toFloat()
            barR = sw - bnd[2]
        }
        val out = HashMap<Pod, Pair<Float, Float>>()

        fun stack(pods: List<Pod>, top: Float, bottom: Float) {
            val total = pods.sumOf { (it.h * u).toDouble() }.toFloat()
            val gap = ((bottom - top - total) / (pods.size + 1)).coerceAtLeast(0f)
            var y = top + gap
            for (p in pods) {
                val w = p.w * u
                val x = if (p.left) barL / 2f - w / 2f else sw - barR / 2f - w / 2f
                out[p] = (x.coerceAtLeast(0f) / sw) to (y / sh)
                y += p.h * u + gap
            }
        }

        val topA = sh * 0.02f
        val topB = sh * (ARROW_TOP - 0.03f)
        val botA = sh * (ARROW_BOT + 0.03f)
        val botB = sh * 0.98f
        if (winMode) {
            // no arrows to avoid: one continuous column per side
            val a2 = sh * 0.03f
            val b2 = sh * 0.97f
            stack(listOf(Pod.L_SHOULDER, Pod.L_STICK, Pod.L_DPAD), a2, b2)
            stack(listOf(Pod.MENU, Pod.R_SHOULDER, Pod.R_FACE, Pod.R_STICK), a2, b2)
        } else {
            stack(listOf(Pod.L_SHOULDER, Pod.L_STICK), topA, topB)
            stack(listOf(Pod.L_DPAD), botA, botB)
            stack(listOf(Pod.MENU, Pod.R_SHOULDER, Pod.R_FACE), topA, topB)
            stack(listOf(Pod.R_STICK), botA, botB)
        }
        return out
    }

    private fun place(lp: WindowManager.LayoutParams, s: Slot) {
        val (sw, sh) = screenPx()
        lp.width = (s.pod.w * unit()).toInt()
        lp.height = (s.pod.h * unit()).toInt()
        lp.x = (s.fx * sw).toInt().coerceIn(0, maxOf(0, sw - lp.width))
        lp.y = (s.fy * sh).toInt().coerceIn(0, maxOf(0, sh - lp.height))
    }

    private fun applyAll() {
        for (s in slots) {
            val lp = s.view.layoutParams as WindowManager.LayoutParams
            place(lp, s)
            wm.updateViewLayout(s.view, lp)
        }
    }

    private fun refresh() {
        slots.forEach { it.view.invalidate() }
    }

    // --------------------------------------------------------- touch markers

    // ------------------------------------------------------- black surround

    /** Four rectangles [x, y, w, h] covering everything outside the game window. */
    private fun backdropRects(): List<IntArray> {
        val (sw, sh) = screenPx()
        val bnd = contentRect(sw, sh)
        val l = bnd[0]
        val t = bnd[1]
        val r = bnd[2]
        val b = bnd[3]
        return listOf(
            intArrayOf(0, 0, l, sh),             // left
            intArrayOf(r, 0, sw - r, sh),        // right
            intArrayOf(l, 0, r - l, t),          // top
            intArrayOf(l, b, r - l, sh - b)      // bottom
        )
    }

    /** Where the game's picture is: saved by the launcher, or worked out from the window shape. */
    private fun contentRect(sw: Int, sh: Int): IntArray {
        val ui = getSharedPreferences("ui", MODE_PRIVATE)
        if (ui.getInt("win_sw", -1) == sw && ui.getInt("win_sh", -1) == sh && ui.getInt("win_cr", 0) > 0) {
            return intArrayOf(
                ui.getInt("win_cl", 0), ui.getInt("win_ct", 0),
                ui.getInt("win_cr", 0), ui.getInt("win_cb", 0)
            )
        }
        return Win.bounds(sw, sh, winRatio)
    }

    // ------------------------------------------------------- coloured lines at the picture edges
    private val edgeLines = ArrayList<View>()

    private fun lineThicknessPx(): Int {
        val lvl = getSharedPreferences("ui", MODE_PRIVATE).getInt("edge_line", 2)
        val dp = when (lvl) { 1 -> 2; 2 -> 4; 3 -> 6; else -> 0 }
        return (dp * resources.displayMetrics.density).toInt()
    }

    /** Two thin gradient lines just outside the picture, so you can see where the game starts. */
    private fun setupEdgeLines() {
        if (lineThicknessPx() <= 0) return
        val red = 0xFFFF5A6E.toInt()
        val violet = 0xFFB36BFF.toInt()
        val cyan = 0xFF3CC8E6.toInt()
        for (i in 0 until 2) {
            val colors = EdgeStyle.colors(getSharedPreferences("ui", MODE_PRIVATE), i == 1)
            val v = View(this)
            v.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors)
            edgeLines += v
            val lp = newParams(1, 1)
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            wm.addView(v, lp)
        }
        layoutEdgeLines()
    }

    private fun layoutEdgeLines() {
        if (edgeLines.isEmpty()) return
        val (sw, sh) = screenPx()
        val r = contentRect(sw, sh)
        val t = lineThicknessPx()
        val xs = intArrayOf(r[0] - t, r[2])
        for (i in 0 until 2) {
            val v = edgeLines[i]
            val lp = v.layoutParams as WindowManager.LayoutParams
            if (xs[i] >= 0 && xs[i] + t <= sw && r[3] > r[1]) {
                lp.x = xs[i]
                lp.y = r[1]
                lp.width = t
                lp.height = r[3] - r[1]
                v.visibility = View.VISIBLE
            } else {
                lp.x = 0; lp.y = 0; lp.width = 1; lp.height = 1
                v.visibility = View.GONE
            }
            wm.updateViewLayout(v, lp)
        }
    }

    private fun setupBackdrop() {
        val (sw0, sh0) = screenPx()
        val fill = BarStyle.drawable(this, sw0, sh0)
        for (i in 0 until 4) {
            val v = SliceView(this, fill).apply {
                setOnTouchListener { _, _ -> true } // swallow stray touches
            }
            backdrop += v
            wm.addView(v, newParams(1, 1))
        }
        layoutBackdrop()
    }

    private fun layoutBackdrop() {
        val rects = backdropRects()
        val (sw, sh) = screenPx()
        for (i in backdrop.indices) {
            val v = backdrop[i]
            val rc = rects[i]
            val lp = v.layoutParams as WindowManager.LayoutParams
            if (rc[2] > 0 && rc[3] > 0) {
                lp.x = rc[0]; lp.y = rc[1]; lp.width = rc[2]; lp.height = rc[3]
                v.visibility = View.VISIBLE
                (v as? SliceView)?.let {
                    it.ox = rc[0]
                    it.oy = rc[1]
                    it.fw = sw
                    it.fh = sh
                    it.invalidate()
                }
            } else {
                lp.x = 0; lp.y = 0; lp.width = 1; lp.height = 1
                v.visibility = View.GONE
            }
            wm.updateViewLayout(v, lp)
        }
    }

    private fun defaultMarker(t: TouchTarget): FloatArray {
        val i = t.ordinal
        return floatArrayOf(0.28f + (i % 7) * 0.06f, 0.30f + (i / 7) * 0.12f)
    }

    private fun placeMarker(lp: WindowManager.LayoutParams, t: TouchTarget) {
        val (sw, sh) = screenPx()
        val p = markerPos.getValue(t)
        lp.width = markerSize()
        lp.height = markerSize()
        lp.x = (p[0] * sw).toInt().coerceIn(0, maxOf(0, sw - lp.width))
        lp.y = (p[1] * sh).toInt().coerceIn(0, maxOf(0, sh - lp.height))
    }

    private fun showMarkers(show: Boolean) {
        for (m in mslots) runCatching { wm.removeView(m.view) }
        mslots.clear()
        if (!show) return
        for (t in TouchTarget.values()) {
            val v = MarkerView(this, t) { view, dx, dy -> moveMarker(view, dx, dy) }
            val lp = newParams(0, 0)
            placeMarker(lp, t)
            wm.addView(v, lp)
            mslots += MSlot(t, v)
        }
    }

    private fun moveMarker(view: View, dx: Float, dy: Float) {
        val m = mslots.firstOrNull { it.view === view } ?: return
        val (sw, sh) = screenPx()
        val lp = view.layoutParams as WindowManager.LayoutParams
        lp.x = (lp.x + dx.toInt()).coerceIn(0, maxOf(0, sw - lp.width))
        lp.y = (lp.y + dy.toInt()).coerceIn(0, maxOf(0, sh - lp.height))
        markerPos[m.target] = floatArrayOf(lp.x / sw.toFloat(), lp.y / sh.toFloat())
        wm.updateViewLayout(view, lp)
    }

    /** Centre of a marker, in screen pixels (where the touch is injected). */
    private fun center(t: TouchTarget): Pair<Float, Float> {
        val (sw, sh) = screenPx()
        val p = markerPos.getValue(t)
        val half = markerSize() / 2f
        return (p[0] * sw + half).coerceIn(half, sw - half) to (p[1] * sh + half).coerceIn(half, sh - half)
    }

    private fun press(t: TouchTarget, down: Boolean) {
        val before = holds[t] ?: 0
        val after = (before + if (down) 1 else -1).coerceAtLeast(0)
        holds[t] = after
        val (x0, y0) = center(t)
        var x = x0
        var y = y0
        if (vdMode) {
            // the virtual display is the mirrored picture, so use picture coordinates
            val (sw, sh) = screenPx()
            val r = contentRect(sw, sh)
            val k = getSharedPreferences("ui", MODE_PRIVATE).getInt("vd_scale", 100) / 100f
            x = (x0 - r[0]) * k
            y = (y0 - r[1]) * k
        }
        if (down && before == 0) input?.touch(t.ordinal, 0, x, y)
        if (!down && after == 0 && before > 0) input?.touch(t.ordinal, 2, x, y)
    }

    private fun stickAsDpad(x: Float, y: Float) {
        val th = 0.45f
        val want = booleanArrayOf(y < -th, y > th, x < -th, x > th)
        val targets = arrayOf(TouchTarget.UP, TouchTarget.DOWN, TouchTarget.LEFT, TouchTarget.RIGHT)
        for (i in 0..3) {
            if (want[i] != stickDir[i]) {
                stickDir[i] = want[i]
                press(targets[i], want[i])
            }
        }
    }

    // ----------------------------------------------------------------- PadHost

    override fun key(code: Int, down: Boolean) {
        if (touchMode) {
            TouchTarget.values().firstOrNull { it.key == code }?.let { press(it, down) }
            return
        }
        input?.key(code, down)
    }

    override fun stick(id: Int, x: Float, y: Float) {
        if (touchMode) {
            if (id == 0) stickAsDpad(x, y)
            return
        }
        input?.stick(id, x, y)
    }

    private fun applyControlsVisibility() {
        for (s in slots) {
            if (s.pod == Pod.TOGGLE) continue
            val lp = s.view.layoutParams as WindowManager.LayoutParams
            lp.flags = if (controlsOn) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            s.view.visibility = if (controlsOn) View.VISIBLE else View.INVISIBLE
            wm.updateViewLayout(s.view, lp)
        }
    }

    override fun toggleControls() {
        controlsOn = !controlsOn
        getSharedPreferences("ui", MODE_PRIVATE).edit()
            .putInt("controls_on", if (controlsOn) 1 else 0).apply()
        applyControlsVisibility()
        input?.setProfile(if (controlsOn && !touchMode) profile.ordinal else InputService.PROFILE_NONE)
        refresh()
    }

    override fun toggleEdit() {
        editing = !editing
        refresh()
        if (touchMode) showMarkers(editing)
    }

    override fun save() {
        val e = prefs.edit()
        if (scaleTouched) {
            // your own size from edit mode wins over the Controls size setting
            e.putFloat("scale2", scale)
            getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("ctrl_size", 0).apply()
        }
        for (s in slots) {
            e.putFloat("${kp()}${s.pod.id}_x", s.fx).putFloat("${kp()}${s.pod.id}_y", s.fy)
        }
        for ((t, p) in markerPos) {
            e.putFloat("m_${t.name}_x", p[0]).putFloat("m_${t.name}_y", p[1])
        }
        e.apply()
        editing = false
        refresh()
        if (touchMode) showMarkers(false)
    }

    override fun close() {
        stopSelf()
    }

    override fun scaleBy(delta: Float) {
        scaleTouched = true
        scale = (scale + delta).coerceIn(0.4f, 2.0f)
        applyAll()
    }

    override fun moveBy(view: View, dx: Float, dy: Float) {
        val s = slots.firstOrNull { it.view === view } ?: return
        val (sw, sh) = screenPx()
        val lp = view.layoutParams as WindowManager.LayoutParams
        lp.x = (lp.x + dx.toInt()).coerceIn(0, maxOf(0, sw - lp.width))
        lp.y = (lp.y + dy.toInt()).coerceIn(0, maxOf(0, sh - lp.height))
        s.fx = lp.x / sw.toFloat()
        s.fy = lp.y / sh.toFloat()
        wm.updateViewLayout(view, lp)
    }

    // -------------------------------------------------------------- foreground

    private fun startAsForeground() {
        val channel = "pad_overlay"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channel, "Gamepad overlay", NotificationManager.IMPORTANCE_LOW)
        )
        val n: Notification = Notification.Builder(this, channel)
            .setContentTitle("Gamepad overlay running")
            .setSmallIcon(R.drawable.ic_stat_pad)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    override fun onDestroy() {
        // lift any finger still held in touch mode
        for ((t, n) in holds) if (n > 0) input?.touch(t.ordinal, 2, 0f, 0f)
        holds.clear()
        showMarkers(false)
        slots.forEach { runCatching { wm.removeView(it.view) } }
        slots.clear()
        backdrop.forEach { runCatching { wm.removeView(it) } }
        backdrop.clear()
        edgeLines.forEach { runCatching { wm.removeView(it) } }
        edgeLines.clear()
        runCatching { Shizuku.removeBinderReceivedListener(binderListener) }
        runCatching { Shizuku.unbindUserService(args, conn, true) }
        if (vdMode) sendBroadcast(Intent(MirrorActivity.ACTION_CLOSE).setPackage(packageName))
        handler.removeCallbacksAndMessages(null)
        running = false
        super.onDestroy()
    }
}
