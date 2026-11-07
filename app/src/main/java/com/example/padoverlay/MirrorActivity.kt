package com.example.padoverlay

import android.app.Activity
import android.app.AlertDialog
import android.window.OnBackInvokedDispatcher
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.SurfaceControl
import org.lsposed.hiddenapibypass.HiddenApiBypass
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Shows the game, which runs on a hidden virtual display, in the middle of the screen.
 * Touches on the picture and gamepad input are forwarded to that display.
 * The side bars stay black for the controls.
 */
class MirrorActivity : Activity(), SurfaceHolder.Callback {

    companion object {
        const val ACTION_CLOSE = "com.example.padoverlay.CLOSE_MIRROR"
        private const val TOUCH_SLOT = 20 // pointer ids 20..29, apart from the touch-mapping slots
        private val AXES = intArrayOf(
            MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS,
            MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y
        )
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var status: TextView
    private lateinit var link: ServiceLink
    private var started = false
    private var hz = 60f // what the mirror window asks the screen for
    private var vdHz = 0f // what the hidden display is asked for (0 = same as the screen)
    private var exactTouch = true
    private var component = ""
    private var pkg = ""

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        component = intent.getStringExtra("component") ?: ""
        pkg = intent.getStringExtra("pkg") ?: ""
        link = ServiceLink(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }

        val ui = getSharedPreferences("ui", MODE_PRIVATE)
        // ask for the screen's top refresh rate (or the chosen one)
        @Suppress("DEPRECATION")
        val maxHz = windowManager.defaultDisplay.supportedModes.maxOf { it.refreshRate }
        val want = ui.getInt("vd_hz", 60)
        exactTouch = ui.getInt("touch_mode", 0) == 0
        hz = if (want == 0) maxHz else minOf(want.toFloat(), maxHz)
        vdHz = if (want == 0) 0f else hz
        val wa = window.attributes
        wa.preferredRefreshRate = hz
        val bright = ui.getInt("vd_bright", 0)
        if (bright > 0) wa.screenBrightness = bright / 100f // 0 = leave to the system
        window.attributes = wa
        val (sw, sh) = screenPx()
        val rect = Win.bounds(sw, sh, Win.ratio(ui.getInt("aspect", 0)))

        val root = FrameLayout(this)
        root.background = BarStyle.drawable(this, sw, sh)
        surfaceView = SurfaceView(this)
        val sp = FrameLayout.LayoutParams(rect[2] - rect[0], rect[3] - rect[1])
        sp.leftMargin = rect[0]
        sp.topMargin = rect[1]
        root.addView(surfaceView, sp)

        // coloured lines just outside the picture edges, so you can see where the game starts
        val dpLine = when (ui.getInt("edge_line", 2)) { 1 -> 2; 2 -> 4; 3 -> 6; else -> 0 }
        val lt = (dpLine * resources.displayMetrics.density).toInt()
        if (lt > 0) {
            val red = 0xFFFF5A6E.toInt()
            val violet = 0xFFB36BFF.toInt()
            val cyan = 0xFF3CC8E6.toInt()
            fun line(x: Int, colors: IntArray) {
                val v = View(this)
                v.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors)
                val lp = FrameLayout.LayoutParams(lt, rect[3] - rect[1])
                lp.leftMargin = x
                lp.topMargin = rect[1]
                root.addView(v, lp)
            }
            if (rect[0] >= lt) line(rect[0] - lt, EdgeStyle.colors(ui, false))
            if (sw - rect[2] >= lt) line(rect[2], EdgeStyle.colors(ui, true))
        }
        // lower picture quality = smaller virtual display = less work for the phone
        val pct = ui.getInt("vd_scale", 100).coerceIn(30, 100)
        if (pct < 100) {
            val bw = ((rect[2] - rect[0]) * pct / 100) and 1.inv()
            val bh = ((rect[3] - rect[1]) * pct / 100) and 1.inv()
            surfaceView.holder.setFixedSize(bw, bh)
        }
        status = TextView(this).apply {
            text = "Starting the game on a virtual display..."
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
        }
        root.addView(
            status,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )
        setContentView(root)
        hideBars()
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                confirmExit()
            }
        }

        surfaceView.holder.addCallback(this)
        surfaceView.setOnTouchListener { _, e ->
            forwardTouch(e)
            true
        }

        val filter = IntentFilter(ACTION_CLOSE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(closeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(closeReceiver, filter)
    }

    // ------------------------------------------------------------------ screen

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

    @Suppress("DEPRECATION")
    private fun hideBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    override fun onResume() {
        super.onResume()
        // coming back (after a notification, another app...): hand the picture surface over again
        if (started) sendSurface(surfaceView.holder.surface)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideBars()
            if (started) sendSurface(surfaceView.holder.surface)
        }
    }

    private var exitDialog: AlertDialog? = null

    /** The system Back button / gesture asks before closing the game. */
    private fun confirmExit() {
        if (exitDialog?.isShowing == true) return
        exitDialog = AlertDialog.Builder(this)
            .setTitle("Exit the game?")
            .setMessage("This closes the game and the controls.")
            .setPositiveButton("Exit") { _, _ -> finish() }
            .setNegativeButton("Stay", null)
            .setOnDismissListener { hideBars() }
            .show()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        confirmExit()
    }

    // ----------------------------------------------------------------- surface

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        applyBoost()
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                holder.surface.setFrameRate(hz, android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            } catch (_: Throwable) {
            }
        }
        if (!started) {
            started = true
            startVirtualDisplay(holder, width, height)
        } else {
            sendSurface(holder.surface)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // keep the game running, just stop drawing until the picture is back
        sendSurface(null)
    }

    /**
     * Optional picture boost: multiplies the colours of the mirrored picture (brightness).
     * Uses a hidden SurfaceControl call; if the phone refuses, nothing happens.
     */
    private fun applyBoost() {
        val pct = getSharedPreferences("ui", MODE_PRIVATE).getInt("vd_boost", 100)
        if (pct <= 100 || Build.VERSION.SDK_INT < 29) return
        try {
            if (Build.VERSION.SDK_INT >= 28) HiddenApiBypass.addHiddenApiExemptions("L")
            val sc = surfaceView.surfaceControl
            if (!sc.isValid) return
            val k = pct / 100f
            val matrix = floatArrayOf(k, 0f, 0f, 0f, k, 0f, 0f, 0f, k)
            val shift = floatArrayOf(0f, 0f, 0f)
            val tx = SurfaceControl.Transaction()
            SurfaceControl.Transaction::class.java.getMethod(
                "setColorTransform", SurfaceControl::class.java, FloatArray::class.java, FloatArray::class.java
            ).invoke(tx, sc, matrix, shift)
            tx.apply()
            tx.close()
        } catch (e: Throwable) {
            Log.w("PadOverlay", "picture boost not available: ${e.cause ?: e}")
        }
    }

    private fun startVirtualDisplay(holder: SurfaceHolder, w: Int, h: Int) {
        val pct = getSharedPreferences("ui", MODE_PRIVATE).getInt("vd_scale", 100).coerceIn(30, 100)
        val dpi = (resources.displayMetrics.densityDpi * pct / 100).coerceAtLeast(120)
        val force = getSharedPreferences("ui", MODE_PRIVATE).getInt("force_close", 1)
        val surface = holder.surface
        link.with { b ->
            Thread {
                val d = Parcel.obtain()
                val r = Parcel.obtain()
                val msg = try {
                    d.writeInt(w)
                    d.writeInt(h)
                    d.writeInt(dpi)
                    d.writeString(component)
                    d.writeString(pkg)
                    d.writeInt(force)
                    d.writeFloat(vdHz)
                    d.writeInt(1)
                    surface.writeToParcel(d, 0)
                    b.transact(InputService.CODE_VD_START, d, r, 0)
                    r.readString() ?: "No reply from the helper"
                } catch (e: Exception) {
                    "Error: $e"
                } finally {
                    d.recycle()
                    r.recycle()
                }
                runOnUiThread {
                    if (msg.startsWith("OK")) {
                        status.visibility = View.GONE
                        if (Settings.canDrawOverlays(this)) {
                            startForegroundService(Intent(this, OverlayService::class.java))
                        }
                    } else {
                        status.text = msg
                    }
                    getSharedPreferences("ui", MODE_PRIVATE).edit().putString("last_launch", msg).apply()
                }
            }.start()
        }
    }

    private fun sendSurface(surface: android.view.Surface?) {
        oneway(InputService.CODE_VD_SURFACE) { d ->
            if (surface != null && surface.isValid) {
                d.writeInt(1)
                surface.writeToParcel(d, 0)
            } else {
                d.writeInt(0)
            }
        }
    }

    // ------------------------------------------------------------------- input

    private fun oneway(code: Int, fill: (Parcel) -> Unit) {
        val b: IBinder = link.binder ?: return
        val d = Parcel.obtain()
        try {
            fill(d)
            b.transact(code, d, null, IBinder.FLAG_ONEWAY)
        } catch (_: Exception) {
        } finally {
            d.recycle()
        }
    }

    private fun sendTouch(slot: Int, action: Int, x: Float, y: Float) {
        oneway(InputService.CODE_TOUCH) { d ->
            d.writeInt(slot)
            d.writeInt(action)
            d.writeFloat(x)
            d.writeFloat(y)
        }
    }

    /** The picture and the virtual display have the same size, so coordinates map 1:1. */
    /**
     * Sends the touch event exactly as the phone made it (same device, pressure, size, history),
     * only moved onto the virtual display. Some games ignore touches that look made-up.
     */
    private fun forwardTouchExact(e: MotionEvent) {
        val copy = MotionEvent.obtain(e)
        try {
            val frame = surfaceView.holder.surfaceFrame
            val kx = if (surfaceView.width > 0) frame.width() / surfaceView.width.toFloat() else 1f
            val ky = if (surfaceView.height > 0) frame.height() / surfaceView.height.toFloat() else 1f
            if (kx != 1f || ky != 1f) {
                val m = Matrix()
                m.setScale(kx, ky)
                copy.transform(m)
            }
            oneway(InputService.CODE_VD_MOTION) { d -> copy.writeToParcel(d, 0) }
        } finally {
            copy.recycle()
        }
    }

    private fun forwardTouch(e: MotionEvent) {
        if (exactTouch) {
            forwardTouchExact(e)
            return
        }
        // the picture may be drawn at a lower resolution than the view
        val frame = surfaceView.holder.surfaceFrame
        val kx = if (surfaceView.width > 0) frame.width() / surfaceView.width.toFloat() else 1f
        val ky = if (surfaceView.height > 0) frame.height() / surfaceView.height.toFloat() else 1f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                sendTouch(TOUCH_SLOT + e.getPointerId(i), 0, e.getX(i) * kx, e.getY(i) * ky)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    sendTouch(TOUCH_SLOT + e.getPointerId(i), 1, e.getX(i) * kx, e.getY(i) * ky)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                sendTouch(TOUCH_SLOT + e.getPointerId(i), 2, e.getX(i) * kx, e.getY(i) * ky)
            }
            MotionEvent.ACTION_CANCEL -> {
                for (i in 0 until e.pointerCount) {
                    sendTouch(TOUCH_SLOT + e.getPointerId(i), 2, e.getX(i) * kx, e.getY(i) * ky)
                }
            }
        }
    }

    private fun isPad(source: Int): Boolean =
        (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
                (source and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (isPad(e.source)) {
            oneway(InputService.CODE_VD_KEY) { d ->
                d.writeInt(e.deviceId)
                d.writeInt(e.keyCode)
                d.writeInt(e.action)
                d.writeInt(e.repeatCount)
                d.writeInt(e.source)
                d.writeInt(e.metaState)
            }
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if ((e.source and InputDevice.SOURCE_JOYSTICK) != 0 && e.actionMasked == MotionEvent.ACTION_MOVE) {
            oneway(InputService.CODE_VD_AXES) { d ->
                d.writeInt(e.deviceId)
                d.writeInt(e.source)
                for (a in AXES) d.writeFloat(e.getAxisValue(a))
            }
            return true
        }
        return super.dispatchGenericMotionEvent(e)
    }

    // ---------------------------------------------------------------- shutdown

    override fun onDestroy() {
        runCatching { unregisterReceiver(closeReceiver) }
        oneway(InputService.CODE_VD_STOP) { }
        link.close()
        getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("vd_mode", 0).apply()
        stopService(Intent(this, OverlayService::class.java))
        super.onDestroy()
    }
}
