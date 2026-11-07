package com.example.padoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/** Controls that can be mapped onto a spot on the game's own on-screen buttons. */
enum class TouchTarget(val label: String, val key: Int) {
    A("A", KeyEvent.KEYCODE_BUTTON_A),
    B("B", KeyEvent.KEYCODE_BUTTON_B),
    X("X", KeyEvent.KEYCODE_BUTTON_X),
    Y("Y", KeyEvent.KEYCODE_BUTTON_Y),
    L1("L1", KeyEvent.KEYCODE_BUTTON_L1),
    R1("R1", KeyEvent.KEYCODE_BUTTON_R1),
    L2("L2", KeyEvent.KEYCODE_BUTTON_L2),
    R2("R2", KeyEvent.KEYCODE_BUTTON_R2),
    START("ST", KeyEvent.KEYCODE_BUTTON_START),
    SELECT("SL", KeyEvent.KEYCODE_BUTTON_SELECT),
    UP("\u2191", KeyEvent.KEYCODE_DPAD_UP),
    DOWN("\u2193", KeyEvent.KEYCODE_DPAD_DOWN),
    LEFT("\u2190", KeyEvent.KEYCODE_DPAD_LEFT),
    RIGHT("\u2192", KeyEvent.KEYCODE_DPAD_RIGHT)
}

/** Draggable marker. Drop it on the matching button in the game, then save. */
class MarkerView(
    ctx: Context,
    private val target: TouchTarget,
    private val onDrag: (View, Float, Float) -> Unit
) : View(ctx) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3202024.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFFE8B400.toInt(); strokeWidth = 4f
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private var lastX = 0f
    private var lastY = 0f

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 3f
        c.drawCircle(cx, cy, r, fill)
        c.drawCircle(cx, cy, r, ring)
        text.textSize = width * 0.36f
        c.drawText(target.label, cx, cy + text.textSize * 0.35f, text)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.rawX; lastY = e.rawY }
            MotionEvent.ACTION_MOVE -> {
                onDrag(this, e.rawX - lastX, e.rawY - lastY)
                lastX = e.rawX
                lastY = e.rawY
            }
        }
        return true
    }
}
