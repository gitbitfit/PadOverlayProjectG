package com.example.padoverlay

import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.FileWriter
import java.io.Writer
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Identity and layout of the virtual controller. */
private class HidSpec(val name: String, val vid: Int, val pid: Int, val xbox: Boolean)


/**
 * Runs in a Shizuku-started process with shell privileges.
 *
 * Gamepad modes: creates a REAL virtual HID gamepad using Android's built-in `hid`
 * shell command (Generic, Xbox One S identity, or DualShock-style name).
 * Touch mode: no gamepad is created; touches are injected instead.
 *
 * Log file: /sdcard/Download/padoverlay-log.txt
 */
class InputService() : Binder() {

    companion object {
        const val CODE_KEY = 1
        const val CODE_STICK = 2
        const val CODE_TOUCH = 3
        const val CODE_PROFILE = 4
        const val CODE_LAUNCH = 5
        const val CODE_VDTEST = 6
        const val CODE_VD_START = 7
        const val CODE_VD_SURFACE = 8
        const val CODE_VD_STOP = 9
        const val CODE_VD_KEY = 10
        const val CODE_VD_AXES = 11
        const val CODE_FAST = 12
        const val CODE_CLEANUP = 13
        const val CODE_STATUS = 14
        const val CODE_VD_MOTION = 15
        const val CODE_DESTROY = 16777114 // required by Shizuku

        const val PROFILE_GENERIC = 0
        const val PROFILE_XBOX = 1
        const val PROFILE_DS4 = 2
        const val PROFILE_DS5 = 3
        const val PROFILE_NONE = 9

        private const val LOG_FILE = "/sdcard/Download/padoverlay-log.txt"

        private val SPECS = mapOf(
            PROFILE_GENERIC to HidSpec("PadOverlay Gamepad", 4617, 1, false),   // 1209:0001
            PROFILE_XBOX to HidSpec("Xbox Wireless Controller", 1118, 736, true), // 045e:02e0
            PROFILE_DS4 to HidSpec("Wireless Controller", 4617, 2, false),      // name only
            PROFILE_DS5 to HidSpec("DualSense Wireless Controller", 4617, 3, false) // name only
        )

        // Report: 15 buttons + 1 pad bit | hat(4) + pad(4) | X Y Z Rz | Brake(LT) Gas(RT) | [Xbox: Back + 7 pad]
        private val BASE_DESCRIPTOR = intArrayOf(
            0x05, 0x01, 0x09, 0x05, 0xA1, 0x01,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x0F, 0x15, 0x00, 0x25, 0x01,
            0x75, 0x01, 0x95, 0x0F, 0x81, 0x02,
            0x95, 0x01, 0x81, 0x03,
            0x05, 0x01, 0x09, 0x39, 0x15, 0x01, 0x25, 0x08, 0x35, 0x00,
            0x46, 0x3B, 0x01, 0x65, 0x14, 0x75, 0x04, 0x95, 0x01, 0x81, 0x42,
            0x65, 0x00, 0x75, 0x04, 0x95, 0x01, 0x81, 0x03,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35,
            0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x04, 0x81, 0x02,
            0x05, 0x02, 0x09, 0xC5, 0x09, 0xC4,
            0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x02, 0x81, 0x02
        )

        // Consumer "AC Back": what a real Xbox controller's View button sends
        private val BACK_BLOCK = intArrayOf(
            0x05, 0x0C, 0x0A, 0x24, 0x02,
            0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x01, 0x81, 0x02,
            0x75, 0x07, 0x95, 0x01, 0x81, 0x03
        )

        // Android key code -> bit in the HID button field (HID button number - 1)
        private val BUTTON_BITS = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to 0,
            KeyEvent.KEYCODE_BUTTON_B to 1,
            KeyEvent.KEYCODE_BUTTON_X to 3,
            KeyEvent.KEYCODE_BUTTON_Y to 4,
            KeyEvent.KEYCODE_BUTTON_L1 to 6,
            KeyEvent.KEYCODE_BUTTON_R1 to 7,
            KeyEvent.KEYCODE_BUTTON_L2 to 8,
            KeyEvent.KEYCODE_BUTTON_R2 to 9,
            KeyEvent.KEYCODE_BUTTON_SELECT to 10,
            KeyEvent.KEYCODE_BUTTON_START to 11,
            KeyEvent.KEYCODE_BUTTON_MODE to 12,
            KeyEvent.KEYCODE_BUTTON_THUMBL to 13,
            KeyEvent.KEYCODE_BUTTON_THUMBR to 14,
        )

        // Same, for the Xbox profile (Menu = button 13, View = consumer Back, no digital L2/R2)
        private val XBOX_BITS = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to 0,
            KeyEvent.KEYCODE_BUTTON_B to 1,
            KeyEvent.KEYCODE_BUTTON_X to 3,
            KeyEvent.KEYCODE_BUTTON_Y to 4,
            KeyEvent.KEYCODE_BUTTON_L1 to 6,
            KeyEvent.KEYCODE_BUTTON_R1 to 7,
            KeyEvent.KEYCODE_BUTTON_START to 12,
            KeyEvent.KEYCODE_BUTTON_THUMBL to 13,
            KeyEvent.KEYCODE_BUTTON_THUMBR to 14,
        )
    }

    private val lock = Any()

    // Shizuku hands a Context to user services that declare this constructor.
    private var shizukuCtx: android.content.Context? = null

    constructor(context: android.content.Context) : this() {
        shizukuCtx = context
    }

    // Keep every button down for a minimum time so games that poll slowly
    // (menu / map toggles) never miss a quick tap.
    private val minHoldMs = 70L
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val downAt = HashMap<Int, Long>()
    private val pendingUp = HashMap<Int, ScheduledFuture<*>>()

    // HID gamepad state
    private var spec: HidSpec = SPECS.getValue(PROFILE_GENERIC)
    private var hidProcess: Process? = null
    private var hidOut: Writer? = null
    private var buttons = 0
    private var dUp = false
    private var dDown = false
    private var dLeft = false
    private var dRight = false
    private var lTrig = false
    private var rTrig = false
    private var back = false
    private val axes = FloatArray(4) // X, Y, Z, RZ

    // Event injection (touch, and fallback for keys)
    private var inputManager: Any? = null
    private var inject: Method? = null

    init {
        log("InputService starting, uid=${android.os.Process.myUid()}, sdk=${Build.VERSION.SDK_INT}")
        initInjection()
    }

    // ------------------------------------------------------------------ binder

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            CODE_KEY -> {
                val keyCode = data.readInt()
                val down = data.readInt() != 0
                key(keyCode, down)
                return true
            }
            CODE_STICK -> {
                val id = data.readInt()
                val x = data.readFloat()
                val y = data.readFloat()
                stick(id, x, y)
                return true
            }
            CODE_TOUCH -> {
                val slot = data.readInt()
                val action = data.readInt()
                val x = data.readFloat()
                val y = data.readFloat()
                touch(slot, action, x, y)
                return true
            }
            CODE_PROFILE -> {
                applyProfile(data.readInt())
                return true
            }
            CODE_LAUNCH -> {
                val component = data.readString() ?: ""
                val pkg = data.readString() ?: ""
                val l = data.readInt()
                val t = data.readInt()
                val r = data.readInt()
                val b = data.readInt()
                val force = data.readInt() != 0
                var status: String
                var rect: IntArray? = null
                try {
                    val res = launchWindowed(component, pkg, l, t, r, b, force)
                    status = res.first
                    rect = res.second
                } catch (e: Throwable) {
                    status = "Error: $e"
                }
                reply?.writeString(status)
                reply?.writeInt(if (rect != null) 1 else 0)
                if (rect != null) for (v in rect) reply?.writeInt(v)
                return true
            }
            CODE_VDTEST -> {
                val component = data.readString() ?: ""
                val pkg = data.readString() ?: ""
                val msg = try {
                    vdTest(component, pkg)
                } catch (e: Throwable) {
                    "Virtual display test crashed: $e"
                }
                reply?.writeString(msg)
                return true
            }
            CODE_VD_START -> {
                val w = data.readInt()
                val h = data.readInt()
                val dpi = data.readInt()
                val component = data.readString() ?: ""
                val pkg = data.readString() ?: ""
                val force = data.readInt() != 0
                val hz = data.readFloat()
                val surface = if (data.readInt() == 1) android.view.Surface.CREATOR.createFromParcel(data) else null
                val msg = try {
                    vdStart(surface, w, h, dpi, component, pkg, force, hz)
                } catch (e: Throwable) {
                    "Error: ${e.cause ?: e}"
                }
                reply?.writeString(msg)
                return true
            }
            CODE_VD_SURFACE -> {
                val surface = if (data.readInt() == 1) android.view.Surface.CREATOR.createFromParcel(data) else null
                try {
                    synchronized(vdLock) { setVdSurface(surface) }
                } catch (e: Throwable) {
                    log("setSurface failed: $e")
                }
                return true
            }
            CODE_VD_STOP -> {
                synchronized(vdLock) { vdStopLocked() }
                return true
            }
            CODE_VD_KEY -> {
                val dev = data.readInt()
                val code2 = data.readInt()
                val action = data.readInt()
                val repeat = data.readInt()
                val source = data.readInt()
                val meta = data.readInt()
                vdKey(dev, code2, action, repeat, source, meta)
                return true
            }
            CODE_VD_AXES -> {
                val dev = data.readInt()
                val source = data.readInt()
                val values = FloatArray(vdAxisIds.size) { data.readFloat() }
                vdAxes(dev, source, values)
                return true
            }
            CODE_CLEANUP -> {
                reply?.writeString(try { cleanup() } catch (e: Throwable) { "Clean-up error: $e" })
                return true
            }
            CODE_STATUS -> {
                reply?.writeString(try { statusReport() } catch (e: Throwable) { "Status error: $e" })
                return true
            }
            CODE_VD_MOTION -> {
                try {
                    vdMotion(MotionEvent.CREATOR.createFromParcel(data))
                } catch (t: Throwable) {
                    log("vd touch read failed: $t")
                }
                return true
            }
            CODE_FAST -> {
                fastInput = data.readInt() != 0
                log("fast input = $fastInput")
                return true
            }
            CODE_DESTROY -> {
                synchronized(vdLock) { vdStopLocked() }
                scheduler.shutdownNow()
                synchronized(lock) { stopHid() }
                exitProcess(0)
            }
        }
        return super.onTransact(code, data, reply, flags)
    }

    // ------------------------------------------------------------- virtual HID

    private fun applyProfile(p: Int) {
        log("profile requested: $p")
        synchronized(lock) { stopHid() }
        if (p == PROFILE_NONE) return
        val s = SPECS[p] ?: SPECS.getValue(PROFILE_GENERIC)
        if (!launchHid(s)) return
        if (p != PROFILE_GENERIC) {
            // Some identities can be claimed by a kernel driver and never show up.
            // If the device doesn't appear, fall back to the generic one.
            Thread {
                if (!waitForDevice(s.name, 2500)) {
                    log("device '${s.name}' did not appear; falling back to generic")
                    synchronized(lock) { stopHid() }
                    launchHid(SPECS.getValue(PROFILE_GENERIC))
                }
            }.start()
        }
    }

    private fun launchHid(s: HidSpec): Boolean {
        return try {
            val p = ProcessBuilder("/system/bin/hid", "-").redirectErrorStream(true).start()
            synchronized(lock) {
                spec = s
                hidProcess = p
                hidOut = p.outputStream.bufferedWriter()
                resetState()
            }
            Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { log("hid: $it") }
                } catch (_: Exception) {
                }
                log("hid process ended")
                synchronized(lock) { if (hidProcess === p) hidOut = null } // switch to fallback
            }.start()

            val desc = (BASE_DESCRIPTOR + (if (s.xbox) BACK_BLOCK else IntArray(0)) + intArrayOf(0xC0))
                .joinToString(",")
            synchronized(lock) {
                sendLine(
                    """{"id":1,"command":"register","name":"${s.name}","vid":${s.vid},"pid":${s.pid},"bus":"usb","descriptor":[$desc]}"""
                )
                sendReport()
            }
            log("hid gamepad registered: ${s.name}")
            true
        } catch (e: Exception) {
            log("hid start failed: $e")
            synchronized(lock) { stopHid() }
            false
        }
    }

    /** Caller must hold [lock]. */
    private fun stopHid() {
        hidProcess?.destroy()
        hidProcess = null
        hidOut = null
        resetState()
    }

    private fun resetState() {
        buttons = 0
        dUp = false; dDown = false; dLeft = false; dRight = false
        lTrig = false; rTrig = false; back = false
        axes.fill(0f)
        padDev = -1
    }

    /** true = seen, false = not seen in time, true also if we cannot check. */
    private fun waitForDevice(name: String, timeoutMs: Long): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            val seen = deviceVisible(name) ?: return true
            if (seen) return true
            try { Thread.sleep(200) } catch (_: InterruptedException) { return true }
        }
        return false
    }

    private fun deviceVisible(name: String): Boolean? {
        val im = inputManager ?: return null
        return try {
            val iface = Class.forName("android.hardware.input.IInputManager")
            val ids = iface.getMethod("getInputDeviceIds").invoke(im) as IntArray
            val get = iface.getMethod("getInputDevice", Int::class.javaPrimitiveType)
            ids.any { (get.invoke(im, it) as? InputDevice)?.name == name }
        } catch (e: Throwable) {
            log("device check unavailable: $e")
            null
        }
    }

    private fun sendLine(s: String) {
        val w = hidOut ?: return
        try {
            w.write(s)
            w.write("\n")
            w.flush()
        } catch (e: Exception) {
            log("hid write failed: $e")
            hidOut = null
        }
    }

    private fun axisByte(v: Float): Int = (v.coerceIn(-1f, 1f) * 127f + 128f).toInt().coerceIn(0, 255)

    /** Caller must hold [lock]. */
    private fun sendReport() {
        val hat = when {
            dUp && dRight -> 2
            dRight && dDown -> 4
            dDown && dLeft -> 6
            dLeft && dUp -> 8
            dUp -> 1
            dRight -> 3
            dDown -> 5
            dLeft -> 7
            else -> 0
        }
        val report = mutableListOf(
            buttons and 0xFF,
            (buttons shr 8) and 0x7F,
            hat,
            axisByte(axes[0]), axisByte(axes[1]), axisByte(axes[2]), axisByte(axes[3]),
            if (lTrig) 255 else 0,
            if (rTrig) 255 else 0
        )
        if (spec.xbox) report.add(if (back) 1 else 0)
        sendLine("""{"id":1,"command":"report","report":[${report.joinToString(",")}]}""")
    }

    // ------------------------------------------------------------ public input

    private fun key(keyCode: Int, down: Boolean) {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_SELECT || keyCode == KeyEvent.KEYCODE_BUTTON_START) {
            log("key ${if (keyCode == KeyEvent.KEYCODE_BUTTON_START) "START" else "SELECT"} down=$down hid=${hidOut != null}")
        }
        synchronized(lock) {
            if (down) {
                pendingUp.remove(keyCode)?.cancel(false)
                downAt[keyCode] = SystemClock.uptimeMillis()
                applyKey(keyCode, true)
            } else {
                val hold = if (targetDisplay != 0) 30L else minHoldMs
                val wait = hold - (SystemClock.uptimeMillis() - (downAt[keyCode] ?: 0L))
                if (wait > 0) {
                    pendingUp.remove(keyCode)?.cancel(false)
                    pendingUp[keyCode] = scheduler.schedule({
                        synchronized(lock) {
                            pendingUp.remove(keyCode)
                            applyKey(keyCode, false)
                        }
                    }, wait, TimeUnit.MILLISECONDS)
                } else {
                    applyKey(keyCode, false)
                }
            }
        }
    }

    private fun bitFor(code: Int): Int? = if (spec.xbox) XBOX_BITS[code] else BUTTON_BITS[code]

    /** Caller must hold [lock]. */
    private fun applyKey(keyCode: Int, down: Boolean) {
        if (fastInput && targetDisplay != 0 && hidOut != null) {
            val dev = padDeviceId()
            if (dev >= 0) {
                directKey(dev, keyCode, down)
                return
            }
        }
        if (hidOut != null) {
            if (keyCode == KeyEvent.KEYCODE_BUTTON_L2) lTrig = down
            if (keyCode == KeyEvent.KEYCODE_BUTTON_R2) rTrig = down
            if (spec.xbox && keyCode == KeyEvent.KEYCODE_BUTTON_SELECT) back = down
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> dUp = down
                KeyEvent.KEYCODE_DPAD_DOWN -> dDown = down
                KeyEvent.KEYCODE_DPAD_LEFT -> dLeft = down
                KeyEvent.KEYCODE_DPAD_RIGHT -> dRight = down
                else -> bitFor(keyCode)?.let { bit ->
                    buttons = if (down) buttons or (1 shl bit) else buttons and (1 shl bit).inv()
                }
            }
            sendReport()
            if (hidOut != null) return
        }
        injectKey(keyCode, down)
    }

    private fun stick(stickId: Int, x: Float, y: Float) {
        synchronized(lock) {
            val i = stickId.coerceIn(0, 1) * 2
            axes[i] = x
            axes[i + 1] = y
            if (fastInput && targetDisplay != 0 && hidOut != null) {
                val dev = padDeviceId()
                if (dev >= 0) {
                    directAxes(dev)
                    return
                }
            }
            if (hidOut != null) {
                sendReport()
                if (hidOut != null) return
            }
        }
        injectStick()
    }

    // ------------------------------------------------------ fallback injection

    private fun initInjection() {
        try {
            if (Build.VERSION.SDK_INT >= 28) HiddenApiBypass.addHiddenApiExemptions("L")
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java)
                .invoke(null, "input") as IBinder
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            inputManager = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            inject = Class.forName("android.hardware.input.IInputManager")
                .getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        } catch (e: Throwable) {
            log("injection init failed: $e")
        }
    }

    private fun injectKey(keyCode: Int, down: Boolean) {
        val im = inputManager ?: return
        val m = inject ?: return
        try {
            val t = SystemClock.uptimeMillis()
            val e = KeyEvent(
                t, t, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
                keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_GAMEPAD
            )
            m.invoke(im, e, 0)
        } catch (e: Throwable) {
            log("injectKey failed: $e")
        }
    }

    private fun injectStick() {
        val im = inputManager ?: return
        val m = inject ?: return
        try {
            val props = MotionEvent.PointerProperties().apply {
                this.id = 0
                toolType = MotionEvent.TOOL_TYPE_UNKNOWN
            }
            val coords = MotionEvent.PointerCoords().apply {
                setAxisValue(MotionEvent.AXIS_X, axes[0])
                setAxisValue(MotionEvent.AXIS_Y, axes[1])
                setAxisValue(MotionEvent.AXIS_Z, axes[2])
                setAxisValue(MotionEvent.AXIS_RZ, axes[3])
            }
            val t = SystemClock.uptimeMillis()
            val e = MotionEvent.obtain(
                t, t, MotionEvent.ACTION_MOVE, 1,
                arrayOf(props), arrayOf(coords),
                0, 0, 1f, 1f, -1, 0, InputDevice.SOURCE_JOYSTICK, 0
            )
            m.invoke(im, e, 0)
            e.recycle()
        } catch (e: Throwable) {
            log("injectStick failed: $e")
        }
    }

    // ------------------------------------------------------- touch injection
    private val touchLock = Any()
    private val touches = LinkedHashMap<Int, FloatArray>()
    private var touchDownTime = 0L

    /** Multi-finger touch injection. action: 0 = down, 1 = move, 2 = up. */
    private fun touch(slot: Int, action: Int, x: Float, y: Float) {
        val im = inputManager ?: return
        val m = inject ?: return
        synchronized(touchLock) {
            val now = SystemClock.uptimeMillis()
            val type: Int
            when (action) {
                0 -> {
                    touches[slot] = floatArrayOf(x, y)
                    if (touches.size == 1) touchDownTime = now
                    type = if (touches.size == 1) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN
                }
                1 -> {
                    val p = touches[slot] ?: return
                    p[0] = x
                    p[1] = y
                    type = MotionEvent.ACTION_MOVE
                }
                else -> {
                    if (!touches.containsKey(slot)) return
                    type = if (touches.size == 1) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP
                }
            }
            val slots = touches.keys.toList()
            val idx = slots.indexOf(slot)
            val props = Array(slots.size) { i ->
                MotionEvent.PointerProperties().apply {
                    this.id = slots[i]
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }
            val coords = Array(slots.size) { i ->
                val p = touches[slots[i]]!!
                MotionEvent.PointerCoords().apply {
                    this.x = p[0]
                    this.y = p[1]
                    pressure = 1f
                    size = 1f
                }
            }
            val act = if (type == MotionEvent.ACTION_POINTER_DOWN || type == MotionEvent.ACTION_POINTER_UP)
                type or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) else type
            try {
                val e = MotionEvent.obtain(
                    touchDownTime, now, act, slots.size, props, coords,
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
                )
                setDisplayCompat(e, targetDisplay)
                m.invoke(im, e, 0)
                e.recycle()
            } catch (t: Throwable) {
                log("touch failed: $t")
            }
            if (action == 2) touches.remove(slot)
        }
    }

    // -------------------------------------------------- freeform game window

    /** Runs a shell command (we are the shell user) and returns its output. */
    private fun sh(cmd: String): String {
        return try {
            val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(10, TimeUnit.SECONDS)
            out
        } catch (e: Exception) {
            "error: $e"
        }
    }

    /** Finds the task of [pkg] in the activity dump. Returns (taskId, dump line). */
    private fun findTask(pkg: String): Pair<Int, String>? {
        val dump = sh("dumpsys activity activities")
        val re = Regex("#(\\d+)\\b.*?A=(?:\\d+:)?" + Regex.escape(pkg) + "\\b")
        for (line in dump.lineSequence()) {
            if (!line.contains("Task")) continue
            val m = re.find(line) ?: continue
            return m.groupValues[1].toInt() to line
        }
        return null
    }

    private val frameRe = Regex("(?<![A-Za-z])m?[Ff]rame=\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]")
    private val captionRe = Regex("(?i)(?:captionBar|CAPTION_BAR)[^\\[]{0,40}\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]")

    /**
     * How far the game's content starts below the top of its window, in pixels.
     * Freeform windows reserve a caption bar at the top (often empty on phones); the game
     * leaves that strip blank. Reads the caption inset from the window dump, or, failing
     * that, the gap between the task top and the game's own frame.
     */
    private fun topGap(pkg: String, taskTop: Int): Int {
        var best = 0
        for (cmd in arrayOf("dumpsys window windows", "dumpsys window")) {
            val lines = sh(cmd).lines()
            for (i in lines.indices) {
                if (!lines[i].contains("Window{") || !lines[i].contains("$pkg/")) continue
                var frameTop = -1
                for (j in i until minOf(lines.size, i + 250)) {
                    val line = lines[j]
                    val cap = captionRe.find(line)
                    if (cap != null) {
                        val h = cap.groupValues[4].toInt() - cap.groupValues[2].toInt()
                        if (h > 0) {
                            log("caption inset ${h}px: ${line.trim().take(160)}")
                            return h
                        }
                    }
                    if (frameTop < 0) {
                        val f = frameRe.find(line)
                        if (f != null) {
                            frameTop = f.groupValues[2].toInt()
                            log("game frame top=$frameTop: ${line.trim().take(160)}")
                        }
                    }
                }
                if (frameTop - taskTop > best) best = frameTop - taskTop
            }
            if (best > 0) return best
        }
        return best
    }

    private fun resizeTask(id: Int, l: Int, t: Int, r: Int, b: Int) {
        val o1 = sh("am task resize $id $l $t $r $b")
        val o2 = sh("am task resize $id $l,$t,$r,$b")
        log("resize $l,$t,$r,$b: ${o1.trim().take(80)} | ${o2.trim().take(80)}")
    }

    private fun pause(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { }
    }

    private fun waitTask(pkg: String): Int? {
        for (i in 0 until 12) {
            pause(500)
            val t = findTask(pkg)
            if (t != null) return t.first
        }
        return null
    }

    /**
     * Opens the game in a freeform window. Returns a message and the "content rect"
     * [l, t, r, b] = where the game's picture really ends up (null if it failed).
     * [forceStop] closes the game first (a running game keeps its old window mode).
     */
    private fun launchWindowed(
        component: String, pkg: String, l: Int, t: Int, r: Int, b: Int, forceStop: Boolean
    ): Pair<String, IntArray?> {
        log("launchWindowed $component bounds=$l,$t,$r,$b forceStop=$forceStop")
        val before = sh("settings get global enable_freeform_support").trim()
        val feature = sh("cmd package has-feature android.software.freeform_window_management").trim()
        log("freeform setting=$before, feature=$feature")

        if (forceStop) {
            sh("am force-stop $pkg")
            pause(700)
        }
        sh("settings put global enable_freeform_support 1")
        sh("settings put global force_resizable_activities 1")
        sh("settings put secure force_resizable_activities 1")
        sh("settings put global freeform_window_management 1")
        pause(300)

        var started = sh("am start-activity --windowingMode 5 -n $component")
        log("start: ${started.trim()}")
        if (Regex("(?m)^Error").containsMatchIn(started)) {
            return ("Could not start the game: ${started.trim().take(120)}" to null)
        }

        var id = waitTask(pkg)
        if (id != null && forceStop && (findTask(pkg)?.second ?: "").contains("mode=fullscreen")) {
            // Second try: restart the game itself (-S) now that the settings are in place.
            log("fullscreen after first start, retrying with -S")
            started = sh("am start-activity -S --windowingMode 5 -n $component")
            log("retry: ${started.trim()}")
            id = waitTask(pkg)
        }
        if (id == null) return ("Game started, but its window was not found" to null)

        sh("am task resizeable $id 3")
        sh("am task resizeable $id true")
        resizeTask(id, l, t, r, b)
        pause(800)

        // A freeform window keeps a blank caption strip at the top and the system will not
        // let the window move above the screen. So shrink the window so the game's picture
        // fits exactly under that strip, and let the overlay cover the strip in black.
        val fullH = b - t
        val ratio = (r - l).toFloat() / fullH
        var rect = intArrayOf(l, t, r, b)
        var note = ""
        val gap = topGap(pkg, t)
        log("top gap = $gap")
        if (gap in 8..400) {
            val ch = fullH - gap
            val cw = (ch * ratio).toInt()
            val nl = l + ((r - l) - cw) / 2
            val nr = nl + cw
            resizeTask(id, nl, t, nr, b)
            pause(800)
            rect = intArrayOf(nl, t + gap, nr, b)
            note = " The ${gap}px strip at the top is covered in black."
        }

        val after = findTask(pkg)?.second ?: ""
        val hint = buildString {
            if (before != "1") append(" Freeform was only just switched on: reboot the phone once, then try again.")
            if (feature == "false") append(" This phone does not list freeform window support.")
            if (!forceStop) append(" If the game was already open, close it fully first.")
        }
        return when {
            after.contains("mode=freeform") ->
                ("OK: game is in a ${rect[2] - rect[0]}x${rect[3] - rect[1]} window.$note" to rect)
            after.contains("mode=fullscreen") ->
                ("Freeform did not apply (the game opened fullscreen).$hint Some games refuse windows." to null)
            else ->
                ("OK (sent).$note If the game is still fullscreen, freeform did not apply.$hint" to rect)
        }
    }

    // --------------------------------------------- virtual display test (scrcpy-style)

    /** Which display is the game's task on? (null if not found) */
    private fun taskDisplay(pkg: String): Int? {
        val lines = sh("dumpsys activity activities").lines()
        val dispRe = Regex("Display #(\\d+)")
        val taskRe = Regex("#(\\d+)\\b.*?A=(?:\\d+:)?" + Regex.escape(pkg) + "\\b")
        var cur: Int? = null
        for (line in lines) {
            dispRe.find(line)?.let { cur = it.groupValues[1].toInt() }
            if (line.contains("Task") && taskRe.containsMatchIn(line)) {
                val c = cur
                if (c != null) return c
            }
        }
        return null
    }

    /**
     * Experiment: can this phone create a virtual display from the shell side and run the
     * game on it (the way scrcpy's --new-display does)? Reports each step.
     */
    private fun vdTest(component: String, pkg: String): String {
        val out = StringBuilder()
        fun note(s: String) {
            out.append(s).append("\n")
            log("vdtest: $s")
        }

        val base = shizukuCtx ?: return "FAILED: Shizuku gave no Context (try updating the Shizuku app)."
        note("sdk=${Build.VERSION.SDK_INT} ${Build.MANUFACTURER} ${Build.MODEL}")
        if (Build.VERSION.SDK_INT >= 28) HiddenApiBypass.addHiddenApiExemptions("L")

        val tex = android.graphics.SurfaceTexture(false)
        tex.setDefaultBufferSize(1280, 720)
        val surface = android.view.Surface(tex)
        var vd: android.hardware.display.VirtualDisplay? = null
        try {
            val ctor = android.hardware.display.DisplayManager::class.java
                .getDeclaredConstructor(android.content.Context::class.java)
            ctor.isAccessible = true
            val dm = ctor.newInstance(ShellContext(base))

            // public, presentation, own content, touch, rotates, destroy content
            val basic = 1 or 2 or 8 or 64 or 128 or 256
            var full = basic
            if (Build.VERSION.SDK_INT >= 33) full = full or 1024 or 2048 or 4096 or 8192 // trusted, own group...
            if (Build.VERSION.SDK_INT >= 34) full = full or 16384 // own focus

            vd = try {
                dm.createVirtualDisplay("PadOverlay test", 1280, 720, 320, surface, full)
            } catch (e: Throwable) {
                note("full flags failed: ${e.cause ?: e}")
                null
            }
            if (vd == null) {
                vd = try {
                    dm.createVirtualDisplay("PadOverlay test", 1280, 720, 320, surface, basic)
                } catch (e: Throwable) {
                    note("basic flags failed: ${e.cause ?: e}")
                    null
                }
            }
            if (vd == null) return out.append("FAILED: could not create a virtual display.").toString()

            val id = vd.display.displayId
            note("virtual display created, id=$id")

            val started = sh("am start-activity --display $id -n $component")
            note("start: ${started.trim().take(160)}")
            var onDisplay: Int? = null
            for (i in 0 until 10) {
                pause(500)
                onDisplay = taskDisplay(pkg)
                if (onDisplay != null) break
            }
            note("game is on display: $onDisplay")
            return out.append(
                if (onDisplay == id) "OK: the game opened on the virtual display."
                else "FAILED: the game did not open on the virtual display."
            ).toString()
        } catch (e: Throwable) {
            note("error: ${e.cause ?: e}")
            return out.append("FAILED.").toString()
        } finally {
            try { vd?.release() } catch (_: Throwable) { }
            surface.release()
            tex.release()
        }
    }

    // ---------------------------------- virtual display (game runs hidden, mirrored in the app)
    private val vdLock = Any()
    private var vd: android.hardware.display.VirtualDisplay? = null

    /** Display that touch / key injection should target (0 = the normal screen). */
    @Volatile
    private var targetDisplay = 0

    /** Straight-to-game input in virtual display mode (off by default). */
    @Volatile
    private var fastInput = false
    private var directLogs = 0

    private fun setDisplayCompat(e: InputEvent, displayId: Int) {
        if (displayId == 0) return
        try {
            InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                .invoke(e, displayId)
        } catch (t: Throwable) {
            log("setDisplayId failed: $t")
        }
    }

    /** Caller holds [vdLock]. */
    private fun vdStopLocked() {
        targetDisplay = 0
        try { vd?.release() } catch (_: Throwable) { }
        vd = null
        sinkStop()
        val o = origForce
        if (o != null) {
            origForce = null
            if (o == "null" || o.isEmpty()) sh("settings delete global force_resizable_activities")
            else sh("settings put global force_resizable_activities $o")
        }
    }

    private var vdRateNote = "follows the screen's refresh rate"

    /** Creates the virtual display. hz > 0 asks for that refresh rate; 0 = same as the screen. */
    private fun createVd(
        dm: android.hardware.display.DisplayManager, w: Int, h: Int, dpi: Int,
        surface: android.view.Surface, flags: Int, hz: Float
    ): android.hardware.display.VirtualDisplay {
        vdRateNote = "follows the screen's refresh rate"
        if (hz > 1f && Build.VERSION.SDK_INT >= 34) {
            try {
                val v = createVdWithRate(dm, w, h, dpi, surface, flags, hz)
                vdRateNote = "${hz.toInt()} Hz requested"
                log("virtual display created with ${hz.toInt()} Hz requested")
                return v
            } catch (e: Throwable) {
                val why = (e.cause ?: e).toString().take(80)
                vdRateNote = "could not set ${hz.toInt()} Hz ($why); follows the screen"
                log("refresh rate request failed: ${e.cause ?: e}")
            }
        }
        // no rate asked for: the display is refreshed at the screen's own rate
        return dm.createVirtualDisplay("PadOverlay", w, h, dpi, surface, flags)
    }

    private fun createVdWithRate(
        dm: android.hardware.display.DisplayManager, w: Int, h: Int, dpi: Int,
        surface: android.view.Surface, flags: Int, hz: Float
    ): android.hardware.display.VirtualDisplay {
        val bc = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
        val ip = Int::class.javaPrimitiveType
        val b = bc.getConstructor(String::class.java, ip, ip, ip).newInstance("PadOverlay", w, h, dpi)
        bc.getMethod("setFlags", ip).invoke(b, flags)
        bc.getMethod("setSurface", android.view.Surface::class.java).invoke(b, surface)
        bc.getMethod("setRequestedRefreshRate", Float::class.javaPrimitiveType).invoke(b, hz)
        val config = bc.getMethod("build").invoke(b)
        val cc = Class.forName("android.hardware.display.VirtualDisplayConfig")

        // the entry point differs between Android versions: use any createVirtualDisplay that takes the config
        val candidates = android.hardware.display.DisplayManager::class.java.declaredMethods
            .filter { m -> m.name == "createVirtualDisplay" && m.parameterTypes.any { p -> p == cc } }
            .sortedBy { m -> m.parameterTypes.size }
        if (candidates.isEmpty()) throw NoSuchMethodException("no createVirtualDisplay(config) on this phone")
        var last: Throwable? = null
        for (m in candidates) {
            try {
                m.isAccessible = true
                val args = Array<Any?>(m.parameterTypes.size) { i -> if (m.parameterTypes[i] == cc) config else null }
                val r = m.invoke(dm, *args)
                if (r is android.hardware.display.VirtualDisplay) return r
            } catch (e: Throwable) {
                last = e
            }
        }
        throw last ?: IllegalStateException("no usable createVirtualDisplay")
    }

    private fun vdStart(
        surface: android.view.Surface?, w: Int, h: Int, dpi: Int,
        component: String, pkg: String, forceStop: Boolean, hz: Float
    ): String {
        val base = shizukuCtx ?: return "FAILED: Shizuku gave no Context (update the Shizuku app)."
        if (surface == null) return "FAILED: no surface to draw on."
        if (Build.VERSION.SDK_INT >= 28) HiddenApiBypass.addHiddenApiExemptions("L")
        synchronized(vdLock) { vdStopLocked() }

        val ctor = android.hardware.display.DisplayManager::class.java
            .getDeclaredConstructor(android.content.Context::class.java)
        ctor.isAccessible = true
        val dm = ctor.newInstance(ShellContext(base))

        val basic = 1 or 2 or 8 or 64 or 128 or 256
        var full = basic
        if (Build.VERSION.SDK_INT >= 33) full = full or 1024 or 2048 or 4096 or 8192
        if (Build.VERSION.SDK_INT >= 34) full = full or 16384

        var usedFull = true
        var d: android.hardware.display.VirtualDisplay? = try {
            createVd(dm, w, h, dpi, surface, full, hz)
        } catch (e: Throwable) {
            log("vd full flags failed: ${e.cause ?: e}")
            null
        }
        if (d == null) {
            usedFull = false
            d = try {
                createVd(dm, w, h, dpi, surface, basic, hz)
            } catch (e: Throwable) {
                log("vd basic flags failed: ${e.cause ?: e}")
                null
            }
        }
        if (d == null) return "FAILED: could not create the virtual display."
        val id = d.display.displayId
        synchronized(vdLock) { vd = d }
        log("vd created id=$id ${w}x$h dpi=$dpi")
        vdW = w
        vdH = h

        if (forceStop) {
            sh("am force-stop $pkg")
            pause(600)
        }
        if (origForce == null) origForce = sh("settings get global force_resizable_activities").trim()
        sh("settings put global force_resizable_activities 1")
        val started = sh("am start-activity --display $id -n $component")
        log("vd start: ${started.trim()}")
        if (Regex("(?m)^Error").containsMatchIn(started)) {
            return "FAILED: ${started.trim().take(120)}"
        }
        var on: Int? = null
        for (i in 0 until 12) {
            pause(500)
            on = taskDisplay(pkg)
            if (on == id) break
        }
        if (on != id) return "FAILED: the game did not open on the virtual display (it is on display $on)."
        targetDisplay = id
        return "OK: game running on virtual display $id ($vdRateNote, own focus ${if (usedFull && Build.VERSION.SDK_INT >= 34) "on" else "off"})"
    }

    /** A gamepad key forwarded from the mirror screen to the game's display. */
    private fun vdKey(dev: Int, code: Int, action: Int, repeat: Int, source: Int, meta: Int) {
        val im = inputManager ?: return
        val m = inject ?: return
        try {
            val t = SystemClock.uptimeMillis()
            val e = KeyEvent(t, t, action, code, repeat, meta, dev, 0, 0, source)
            setDisplayCompat(e, targetDisplay)
            m.invoke(im, e, 0)
        } catch (t: Throwable) {
            log("vdKey failed: $t")
        }
    }

    private val vdAxisIds = intArrayOf(
        MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
        MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS,
        MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y
    )

    /** Gamepad sticks / triggers / hat forwarded from the mirror screen. */
    private fun vdAxes(dev: Int, source: Int, values: FloatArray) {
        val im = inputManager ?: return
        val m = inject ?: return
        try {
            val props = MotionEvent.PointerProperties().apply {
                this.id = 0
                toolType = MotionEvent.TOOL_TYPE_UNKNOWN
            }
            val coords = MotionEvent.PointerCoords().apply {
                for (i in vdAxisIds.indices) setAxisValue(vdAxisIds[i], values[i])
            }
            val t = SystemClock.uptimeMillis()
            val e = MotionEvent.obtain(
                t, t, MotionEvent.ACTION_MOVE, 1,
                arrayOf(props), arrayOf(coords),
                0, 0, 1f, 1f, dev, 0, source, 0
            )
            setDisplayCompat(e, targetDisplay)
            m.invoke(im, e, 0)
            e.recycle()
        } catch (t: Throwable) {
            log("vdAxes failed: $t")
        }
    }

    // -------------------------------- virtual display: straight-to-game input (fewer hops)
    private var padDev = -1
    private var padDevCheck = 0L

    private fun deviceIdByName(name: String): Int? {
        val im = inputManager ?: return null
        return try {
            val iface = Class.forName("android.hardware.input.IInputManager")
            val ids = iface.getMethod("getInputDeviceIds").invoke(im) as IntArray
            val get = iface.getMethod("getInputDevice", Int::class.javaPrimitiveType)
            ids.firstOrNull { (get.invoke(im, it) as? InputDevice)?.name == name }
        } catch (e: Throwable) {
            log("device id lookup failed: $e")
            null
        }
    }

    /** Android's id for our virtual controller (so games see events from a real, known device). */
    private fun padDeviceId(): Int {
        if (padDev >= 0) return padDev
        val now = SystemClock.uptimeMillis()
        if (now - padDevCheck < 400) return -1
        padDevCheck = now
        padDev = deviceIdByName(spec.name) ?: -1
        if (padDev >= 0) log("pad device id = $padDev")
        return padDev
    }

    /** Key press straight to the game's display. */
    private fun directKey(dev: Int, keyCode: Int, down: Boolean) {
        if (directLogs < 8) {
            directLogs++
            log("direct key dev=$dev code=$keyCode down=$down display=$targetDisplay")
        }
        val action = if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        var axesChanged = false
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { dUp = down; axesChanged = true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { dDown = down; axesChanged = true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { dLeft = down; axesChanged = true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { dRight = down; axesChanged = true }
            KeyEvent.KEYCODE_BUTTON_L2 -> { lTrig = down; axesChanged = true }
            KeyEvent.KEYCODE_BUTTON_R2 -> { rTrig = down; axesChanged = true }
        }
        val triggerOnly = spec.xbox &&
                (keyCode == KeyEvent.KEYCODE_BUTTON_L2 || keyCode == KeyEvent.KEYCODE_BUTTON_R2)
        if (!triggerOnly) {
            val code = if (spec.xbox && keyCode == KeyEvent.KEYCODE_BUTTON_SELECT) KeyEvent.KEYCODE_BACK else keyCode
            val isDpad = keyCode >= KeyEvent.KEYCODE_DPAD_UP && keyCode <= KeyEvent.KEYCODE_DPAD_RIGHT
            val src = if (isDpad) InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_DPAD else InputDevice.SOURCE_GAMEPAD
            vdKey(dev, code, action, 0, src, 0)
        }
        if (axesChanged) directAxes(dev)
    }

    /** Sticks, triggers and D-pad hat straight to the game's display. */
    private fun directAxes(dev: Int) {
        val lt = if (lTrig) 1f else 0f
        val rt = if (rTrig) 1f else 0f
        val hx = (if (dRight) 1f else 0f) - (if (dLeft) 1f else 0f)
        val hy = (if (dDown) 1f else 0f) - (if (dUp) 1f else 0f)
        // order = vdAxisIds: X, Y, Z, RZ, LTRIGGER, RTRIGGER, BRAKE, GAS, HAT_X, HAT_Y
        vdAxes(dev, InputDevice.SOURCE_JOYSTICK, floatArrayOf(axes[0], axes[1], axes[2], axes[3], lt, rt, lt, rt, hx, hy))
    }

    // ---------------------------------------- keep the game alive while the mirror screen is away
    private var sink: android.media.ImageReader? = null
    private var sinkThread: android.os.HandlerThread? = null
    private var vdW = 0
    private var vdH = 0

    /** A surface that just throws frames away (used while the real picture surface is gone). */
    private fun sinkSurface(w: Int, h: Int): android.view.Surface {
        sinkStop()
        val t = android.os.HandlerThread("pad-sink")
        t.start()
        val r = android.media.ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ rd ->
            try {
                rd.acquireLatestImage()?.close()
            } catch (_: Throwable) {
            }
        }, android.os.Handler(t.looper))
        sink = r
        sinkThread = t
        return r.surface
    }

    private fun sinkStop() {
        try { sink?.close() } catch (_: Throwable) { }
        sink = null
        try { sinkThread?.quitSafely() } catch (_: Throwable) { }
        sinkThread = null
    }

    /**
     * Real picture surface, or - when the mirror screen is away (notification, other app, screen
     * change) - the sink, so the virtual display stays on and the game keeps running.
     */
    private fun setVdSurface(surface: android.view.Surface?) {
        val d = vd ?: return
        if (surface != null) {
            d.surface = surface
        } else if (vdW > 0 && vdH > 0) {
            if (sink == null) sinkSurface(vdW, vdH)
            d.surface = sink?.surface
            log("mirror away: virtual display kept alive")
        }
    }

    // ------------------------------------------------ clean-up and status (for troubleshooting)
    private var origForce: String? = null

    /** Stops everything we started and switches off the developer settings we turn on. */
    private fun cleanup(): String {
        synchronized(vdLock) { vdStopLocked() }
        synchronized(lock) { stopHid() }
        sh("pkill -f com.android.commands.hid.Hid")
        sh("settings put global force_resizable_activities 0")
        sh("settings put global enable_freeform_support 0")
        sh("settings put global freeform_window_management 0")
        log("cleanup done")
        return "Cleaned up: virtual display released, pad helpers stopped, freeform and " +
                "force-resizable settings switched off. If the phone still feels slow, restart it."
    }

    /** A short report of what is running. */
    private fun statusReport(): String {
        val sb = StringBuilder()
        try {
            val base = shizukuCtx
            if (base != null) {
                if (Build.VERSION.SDK_INT >= 28) HiddenApiBypass.addHiddenApiExemptions("L")
                val ctor = android.hardware.display.DisplayManager::class.java
                    .getDeclaredConstructor(android.content.Context::class.java)
                ctor.isAccessible = true
                val dm = ctor.newInstance(ShellContext(base))
                val ds = dm.displays
                sb.append("Displays: ${ds.size}\n")
                for (d in ds) sb.append(" - id ${d.displayId}: ${d.name}\n")
            }
        } catch (e: Throwable) {
            sb.append("Display list failed: ${e.cause ?: e}\n")
        }
        val hids = sh("ps -A -o PID,ARGS").lines().count { it.contains("commands.hid.Hid") }
        sb.append("Pad helper processes: $hids\n")
        sb.append("force_resizable_activities = ${sh("settings get global force_resizable_activities").trim()}\n")
        sb.append("enable_freeform_support = ${sh("settings get global enable_freeform_support").trim()}\n")
        sb.append("Virtual pad: ${if (hidOut != null) "running as '${spec.name}'" else "not running"}\n")
        val v = vd
        sb.append("Our virtual display: ").append(if (v != null) "active (id ${v.display.displayId}, ${v.display.refreshRate.toInt()} Hz)" else "none")
        return sb.toString()
    }

    // -------------------------- virtual display: exact copy of the user's touch events
    private var motionLogs = 0

    /** A real touch event (as the phone made it) re-sent to the game's display. */
    private fun vdMotion(e: MotionEvent) {
        val im = inputManager ?: return
        val m = inject ?: return
        try {
            setDisplayCompat(e, targetDisplay)
            if (motionLogs < 6) {
                motionLogs++
                log(
                    "vd touch action=${e.actionMasked} device=${e.deviceId} source=0x${Integer.toHexString(e.source)} " +
                            "pointers=${e.pointerCount} display=$targetDisplay pressure=${e.pressure} size=${e.size}"
                )
            }
            m.invoke(im, e, 0)
        } catch (t: Throwable) {
            log("vdMotion failed: ${t.cause ?: t}")
        } finally {
            e.recycle()
        }
    }

    // ----------------------------------------------------------------- logging

    private fun log(msg: String) {
        android.util.Log.i("PadOverlay", msg)
        try {
            FileWriter(LOG_FILE, true).use { it.write("${System.currentTimeMillis()} $msg\n") }
        } catch (_: Exception) {
        }
    }
}
