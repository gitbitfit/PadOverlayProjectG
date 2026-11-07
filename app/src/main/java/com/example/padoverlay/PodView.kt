package com.example.padoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

interface PadHost {
    val editing: Boolean
    val controlsOn: Boolean
    val helperReady: Boolean // false = Shizuku helper not connected, buttons cannot work
    fun toggleControls()
    fun key(code: Int, down: Boolean)
    fun stick(id: Int, x: Float, y: Float)
    fun toggleEdit()
    fun save()
    fun close()
    fun scaleBy(delta: Float)
    fun moveBy(view: View, dx: Float, dy: Float)
}

/**
 * Each pod is its own small overlay window, so the gaps between pods (for
 * example where the game-space arrows are) stay fully touchable.
 * w/h are design units (about 1 dp at scale 1.0).
 */
enum class Pod(val id: String, val w: Float, val h: Float, val border: Int, val left: Boolean) {
    L_SHOULDER("ls", 88f, 62f, 0xFFE05A5A.toInt(), true),
    L_STICK("lst", 88f, 88f, 0xFFE05A5A.toInt(), true),
    L_DPAD("ldp", 88f, 116f, 0xFFE05A5A.toInt(), true),
    R_SHOULDER("rs", 88f, 62f, 0xFF3CC8E6.toInt(), false),
    R_FACE("rf", 88f, 88f, 0xFF3CC8E6.toInt(), false),
    R_STICK("rst", 88f, 118f, 0xFF3CC8E6.toInt(), false),
    MENU("menu", 88f, 52f, 0xFFE8B400.toInt(), false),
    TOGGLE("tg", 44f, 44f, 0xFFE8B400.toInt(), false)
}

enum class PadProfile(val label: String) {
    GENERIC("Generic"),
    XBOX("Xbox"),
    DS4("DualShock 4"),
    DS5("DualSense (PS5)")
}

class PodView(
    ctx: Context,
    private val pod: Pod,
    private val host: PadHost,
    private val profile: PadProfile = PadProfile.GENERIC
) : View(ctx) {

    // ----------------------------------------------------------------- paints
    private fun fill(col: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = col }
    private fun line(col: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = col; strokeWidth = w; strokeCap = Paint.Cap.ROUND
    }
    private fun txt(col: Int, size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = col; textSize = size; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }

    private val AMBER = 0xFFE8B400.toInt()
    private val DARK = 0xFF1B1B1F.toInt()
    private val WHITE = 0xFFFFFFFF.toInt()

    private val bg = fill(0xB31E1E22.toInt())
    private val border = line(pod.border, 2f)
    private val editBorder = line(AMBER, 2.5f)
    private val btnFill = fill(0xCC3A3A40.toInt())
    private val btnFillDown = fill(0xFFE8E8F0.toInt())
    private val btnStroke = line(0xFFC8C8D0.toInt(), 1.2f)
    private val ring = line(0xFFB4B4BC.toInt(), 1.6f)
    private val knob = fill(0xFF4A90D9.toInt())
    private val amberFill = fill(AMBER)
    private val iconStroke = line(WHITE, 1.6f)
    private val iconFill = fill(WHITE)
    private val iconDarkFill = fill(DARK)
    private val glyphBlue = line(0xFF4D8DFF.toInt(), 2.4f)
    private val glyphRed = line(0xFFFF5A5A.toInt(), 2.4f)
    private val glyphPink = line(0xFFE07BC5.toInt(), 2.4f)
    private val glyphGreen = line(0xFF3CC98B.toInt(), 2.4f)

    private val label = txt(WHITE, 10f)
    private val labelDark = txt(DARK, 10f)
    private val amberText = txt(AMBER, 8f)
    private val darkText8 = txt(DARK, 8f)
    private val whiteText16 = txt(WHITE, 16f)
    private val darkText16 = txt(DARK, 16f)
    private val warnFill = fill(0xCCB00020.toInt())
    private val warnText = txt(WHITE, 9f)

    // --------------------------------------------------------------- controls
    private abstract inner class Ctl {
        abstract fun hit(x: Float, y: Float): Boolean
        abstract fun draw(c: Canvas)
        open fun down(x: Float, y: Float) {}
        open fun move(x: Float, y: Float) {}
        open fun up(cancel: Boolean) {}
    }

    /** Button: rounded rectangle, circle, or D-pad arrow (0 up, 1 down, 2 left, 3 right). */
    private inner class Btn(
        val text: String,
        val r: RectF,
        val key: Int,
        val circle: Boolean = false,
        val arrow: Int = -1,
        val glyph: Int = 0 // 1 cross, 2 circle, 3 square, 4 triangle (DualShock face buttons)
    ) : Ctl() {
        var pressed = false

        override fun hit(x: Float, y: Float) =
            if (circle) hypot(x - r.centerX(), y - r.centerY()) <= r.width() / 2f + 2f
            else r.contains(x, y)

        override fun down(x: Float, y: Float) { pressed = true; host.key(key, true) }
        override fun up(cancel: Boolean) { pressed = false; host.key(key, false) }

        override fun draw(c: Canvas) {
            val f = if (pressed) btnFillDown else btnFill
            if (circle) {
                c.drawCircle(r.centerX(), r.centerY(), r.width() / 2f, f)
                c.drawCircle(r.centerX(), r.centerY(), r.width() / 2f, btnStroke)
            } else {
                c.drawRoundRect(r, 4f, 4f, f)
                c.drawRoundRect(r, 4f, 4f, btnStroke)
            }
            if (arrow >= 0) drawArrow(c, r, arrow, if (pressed) iconDarkFill else iconFill)
            else if (glyph > 0) drawGlyph(c, r, glyph)
            else c.drawText(text, r.centerX(), r.centerY() + 3.6f, if (pressed) labelDark else label)
        }
    }

    private fun drawArrow(c: Canvas, r: RectF, dir: Int, p: Paint) {
        val cx = r.centerX(); val cy = r.centerY(); val a = 6f; val b = a * 0.8f
        val path = Path()
        when (dir) {
            0 -> { path.moveTo(cx, cy - a); path.lineTo(cx - a, cy + b); path.lineTo(cx + a, cy + b) }
            1 -> { path.moveTo(cx, cy + a); path.lineTo(cx - a, cy - b); path.lineTo(cx + a, cy - b) }
            2 -> { path.moveTo(cx - a, cy); path.lineTo(cx + b, cy - a); path.lineTo(cx + b, cy + a) }
            else -> { path.moveTo(cx + a, cy); path.lineTo(cx - b, cy - a); path.lineTo(cx - b, cy + a) }
        }
        path.close()
        c.drawPath(path, p)
    }

    private fun drawGlyph(c: Canvas, r: RectF, g: Int) {
        val cx = r.centerX(); val cy = r.centerY(); val k = r.width() * 0.22f
        when (g) {
            1 -> {
                c.drawLine(cx - k, cy - k, cx + k, cy + k, glyphBlue)
                c.drawLine(cx + k, cy - k, cx - k, cy + k, glyphBlue)
            }
            2 -> c.drawCircle(cx, cy, k * 1.15f, glyphRed)
            3 -> c.drawRect(cx - k, cy - k, cx + k, cy + k, glyphPink)
            else -> {
                val path = Path()
                path.moveTo(cx, cy - k * 1.2f)
                path.lineTo(cx - k * 1.15f, cy + k * 0.85f)
                path.lineTo(cx + k * 1.15f, cy + k * 0.85f)
                path.close()
                c.drawPath(path, glyphGreen)
            }
        }
    }

    /** Analog stick: ring plus blue knob. */
    private inner class Stick(val cx: Float, val cy: Float, val r: Float, val id: Int) : Ctl() {
        var nx = 0f
        var ny = 0f
        private val travel = r * 0.65f

        override fun hit(x: Float, y: Float) = hypot(x - cx, y - cy) <= r + 4f
        override fun down(x: Float, y: Float) = move(x, y)
        override fun move(x: Float, y: Float) {
            var dx = (x - cx) / travel
            var dy = (y - cy) / travel
            val len = hypot(dx, dy)
            if (len > 1f) { dx /= len; dy /= len }
            nx = dx; ny = dy
            host.stick(id, nx, ny)
        }
        override fun up(cancel: Boolean) { nx = 0f; ny = 0f; host.stick(id, 0f, 0f) }

        override fun draw(c: Canvas) {
            c.drawCircle(cx, cy, r, ring)
            c.drawCircle(cx + nx * travel, cy + ny * travel, r * 0.42f, knob)
        }
    }

    /** Menu action: kind 0 = text (EDIT), 1 = save icon, 2 = close X, 3 = boxed text (- / +). */
    private inner class Tap(
        val r: RectF,
        val kind: Int,
        val text: String = "",
        val editOnly: Boolean = false,
        val action: () -> Unit
    ) : Ctl() {
        var pressed = false
        private fun active() = !editOnly || host.editing

        override fun hit(x: Float, y: Float) = active() && r.contains(x, y)
        override fun down(x: Float, y: Float) { pressed = true }
        override fun up(cancel: Boolean) { pressed = false; if (!cancel) action() }

        override fun draw(c: Canvas) {
            if (!active()) return
            val cx = r.centerX(); val cy = r.centerY()
            when (kind) {
                0 -> if (host.editing) {
                    c.drawRoundRect(r, 4f, 4f, amberFill)
                    c.drawText(text, cx, cy + 2.8f, darkText8)
                } else c.drawText(text, cx, cy + 2.8f, amberText)
                1 -> {
                    c.drawRoundRect(RectF(cx - 7, cy - 7, cx + 7, cy + 7), 2f, 2f, iconStroke)
                    c.drawRect(cx - 4, cy - 7, cx + 3, cy - 2, iconFill)
                    c.drawRect(cx - 5, cy + 1, cx + 5, cy + 6, iconStroke)
                }
                2 -> {
                    c.drawLine(cx - 6, cy - 6, cx + 6, cy + 6, iconStroke)
                    c.drawLine(cx + 6, cy - 6, cx - 6, cy + 6, iconStroke)
                }
                4 -> {
                    c.drawRoundRect(r, 4f, 4f, if (pressed) btnFillDown else btnFill)
                    c.drawRoundRect(r, 4f, 4f, btnStroke)
                    c.drawText(if (host.controlsOn) "HIDE" else "SHOW", cx, cy + 3.6f, if (pressed) labelDark else label)
                }
                else -> {
                    c.drawRoundRect(r, 4f, 4f, if (pressed) btnFillDown else btnFill)
                    c.drawRoundRect(r, 4f, 4f, btnStroke)
                    c.drawText(text, cx, cy + 5.5f, if (pressed) darkText16 else whiteText16)
                }
            }
        }
    }

    private fun lbl(generic: String, xbox: String, ds4: String, ds5: String = ds4) = when (profile) {
        PadProfile.GENERIC -> generic
        PadProfile.XBOX -> xbox
        PadProfile.DS4 -> ds4
        PadProfile.DS5 -> ds5
    }

    private fun g(n: Int) = if (profile == PadProfile.DS4 || profile == PadProfile.DS5) n else 0

    private val ctls = mutableListOf<Ctl>()
    private val active = mutableMapOf<Int, Ctl>()
    private var dragging = false
    private var lastRawX = 0f
    private var lastRawY = 0f

    init {
        when (pod) {
            Pod.L_SHOULDER -> {
                ctls += Btn(lbl("L1", "LB", "L1"), RectF(4f, 4f, 84f, 29f), KeyEvent.KEYCODE_BUTTON_L1)
                ctls += Btn(lbl("L2", "LT", "L2"), RectF(4f, 33f, 84f, 58f), KeyEvent.KEYCODE_BUTTON_L2)
            }
            Pod.L_STICK -> {
                ctls += Stick(44f, 44f, 40f, 0)
            }
            Pod.L_DPAD -> {
                ctls += Btn("", RectF(31f, 4f, 57f, 30f), KeyEvent.KEYCODE_DPAD_UP, arrow = 0)
                ctls += Btn("", RectF(4f, 31f, 30f, 57f), KeyEvent.KEYCODE_DPAD_LEFT, arrow = 2)
                ctls += Btn("", RectF(58f, 31f, 84f, 57f), KeyEvent.KEYCODE_DPAD_RIGHT, arrow = 3)
                ctls += Btn("", RectF(31f, 58f, 57f, 84f), KeyEvent.KEYCODE_DPAD_DOWN, arrow = 1)
                ctls += Btn(lbl("SEL", "VIEW", "SHARE", "CREATE"), RectF(4f, 90f, 84f, 112f), KeyEvent.KEYCODE_BUTTON_SELECT)
            }
            Pod.R_SHOULDER -> {
                ctls += Btn(lbl("R1", "RB", "R1"), RectF(4f, 4f, 84f, 29f), KeyEvent.KEYCODE_BUTTON_R1)
                ctls += Btn(lbl("R2", "RT", "R2"), RectF(4f, 33f, 84f, 58f), KeyEvent.KEYCODE_BUTTON_R2)
            }
            Pod.R_FACE -> {
                ctls += Btn(lbl("Y", "Y", ""), RectF(31f, 3f, 57f, 29f), KeyEvent.KEYCODE_BUTTON_Y, circle = true, glyph = g(4))
                ctls += Btn(lbl("X", "X", ""), RectF(3f, 31f, 29f, 57f), KeyEvent.KEYCODE_BUTTON_X, circle = true, glyph = g(3))
                ctls += Btn(lbl("B", "B", ""), RectF(59f, 31f, 85f, 57f), KeyEvent.KEYCODE_BUTTON_B, circle = true, glyph = g(2))
                ctls += Btn(lbl("A", "A", ""), RectF(31f, 59f, 57f, 85f), KeyEvent.KEYCODE_BUTTON_A, circle = true, glyph = g(1))
            }
            Pod.R_STICK -> {
                ctls += Stick(44f, 44f, 40f, 1)
                ctls += Btn(lbl("START", "MENU", "OPTIONS"), RectF(4f, 92f, 84f, 114f), KeyEvent.KEYCODE_BUTTON_START)
            }
            Pod.MENU -> {
                ctls += Tap(RectF(2f, 4f, 32f, 26f), 0, "EDIT") { host.toggleEdit() }
                ctls += Tap(RectF(34f, 4f, 58f, 26f), 1) { host.save() }
                ctls += Tap(RectF(60f, 4f, 86f, 26f), 2) { host.close() }
                // shown only in edit mode: shrink / enlarge all pods
                ctls += Tap(RectF(4f, 29f, 42f, 49f), 3, "-", editOnly = true) { host.scaleBy(-0.1f) }
                ctls += Tap(RectF(46f, 29f, 84f, 49f), 3, "+", editOnly = true) { host.scaleBy(0.1f) }
            }
            Pod.TOGGLE -> {
                // hide / show all the on-screen controls (e.g. when using a real controller)
                ctls += Tap(RectF(8f, 8f, 36f, 36f), 4) { host.toggleControls() }
            }
        }
    }

    // ---------------------------------------------------------------- drawing
    private fun unit() = if (width > 0) width / pod.w else 1f

    override fun onDraw(c: Canvas) {
        c.save()
        c.scale(unit(), unit())
        val rect = RectF(1f, 1f, pod.w - 1f, pod.h - 1f)
        c.drawRoundRect(rect, 8f, 8f, bg)
        c.drawRoundRect(rect, 8f, 8f, if (host.editing) editBorder else border)
        ctls.forEach { it.draw(c) }
        if (!host.helperReady && pod != Pod.MENU) {
            c.drawRoundRect(rect, 8f, 8f, warnFill)
            c.drawText("NO", pod.w / 2f, pod.h / 2f - 2f, warnText)
            c.drawText("SHIZUKU", pod.w / 2f, pod.h / 2f + 9f, warnText)
        }
        c.restore()
    }

    // ------------------------------------------------------------------ touch
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val s = unit()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val x = e.getX(i) / s
                val y = e.getY(i) / s
                val c = ctls.firstOrNull { it.hit(x, y) }
                if (host.editing && c !is Tap) {
                    // edit mode: drag this pod
                    dragging = true
                    lastRawX = e.rawX
                    lastRawY = e.rawY
                } else if (c != null) {
                    active[e.getPointerId(i)] = c
                    c.down(x, y)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    host.moveBy(this, e.rawX - lastRawX, e.rawY - lastRawY)
                    lastRawX = e.rawX
                    lastRawY = e.rawY
                } else {
                    for (i in 0 until e.pointerCount) {
                        active[e.getPointerId(i)]?.move(e.getX(i) / s, e.getY(i) / s)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                active.remove(e.getPointerId(e.actionIndex))?.up(false)
                if (e.actionMasked == MotionEvent.ACTION_UP) dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                active.values.forEach { it.up(true) }
                active.clear()
                dragging = false
            }
        }
        invalidate()
        return true
    }
}
