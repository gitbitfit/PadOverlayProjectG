package com.example.padoverlay

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.view.View
import android.os.IBinder
import rikka.shizuku.Shizuku

/** One place for the Shizuku user-service settings (shared by the overlay and the main screen). */
object PadService {
    const val VERSION = 21

    fun args(ctx: Context): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(ComponentName(ctx.packageName, InputService::class.java.name))
            .processNameSuffix("input")
            .version(VERSION)
}

/** Game window shapes for window mode. */
object Win {
    val LABELS = arrayOf("16:9", "16:10", "4:3", "3:2", "18:9", "19:9", "19.5:9", "20:9", "18.5:9")
    val NOTES = arrayOf(
        "wide bars", "wide bars", "very wide bars", "wide bars",
        "hides camera area and curved corners", "thinner bars", "thin bars", "almost full screen",
        "hides camera area and corners, a bit thinner than 18:9"
    )
    private val RATIOS = floatArrayOf(
        16f / 9f, 16f / 10f, 4f / 3f, 3f / 2f, 18f / 9f, 19f / 9f, 19.5f / 9f, 20f / 9f, 18.5f / 9f
    )

    fun ratio(index: Int): Float = RATIOS[index.coerceIn(0, RATIOS.size - 1)]

    /** [left, top, right, bottom] of the centred game window on a screen of sw x sh pixels. */
    fun bounds(sw: Int, sh: Int, ratio: Float): IntArray {
        val w: Int
        val h: Int
        if (sw >= sh) {
            w = minOf(sw.toFloat(), sh * ratio).toInt()
            h = (w / ratio).toInt()
        } else {
            h = minOf(sh.toFloat(), sw * ratio).toInt()
            w = (h / ratio).toInt()
        }
        val l = (sw - w) / 2
        val t = (sh - h) / 2
        return intArrayOf(l, t, l + w, t + h)
    }
}

/** Keeps a connection to the Shizuku helper (used by the mirror screen). */
class ServiceLink(private val ctx: Context) {
    @Volatile
    var binder: IBinder? = null
    private val waiting = ArrayList<(IBinder) -> Unit>()
    private var binding = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, b: IBinder) {
            binder = b
            val todo = ArrayList(waiting)
            waiting.clear()
            todo.forEach { it(b) }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            binder = null
            binding = false
        }
    }

    /** Runs [block] once the helper is connected. */
    fun with(block: (IBinder) -> Unit) {
        val b = binder
        if (b != null && b.isBinderAlive) {
            block(b)
            return
        }
        waiting.add(block)
        if (!binding) {
            binding = true
            Shizuku.bindUserService(PadService.args(ctx), conn)
        }
    }

    fun close() {
        runCatching { Shizuku.unbindUserService(PadService.args(ctx), conn, false) }
    }
}

/** Colours of the lines at the picture edges (setting "Edge line color"). */
object EdgeStyle {
    /** mirrored = the right-hand line (gradient runs the other way). */
    fun colors(ui: android.content.SharedPreferences, mirrored: Boolean): IntArray {
        if (ui.getInt("edge_mode", 0) == 0) {
            val red = 0xFFFF5A6E.toInt()
            val violet = 0xFFB36BFF.toInt()
            val cyan = 0xFF3CC8E6.toInt()
            return if (!mirrored) intArrayOf(red, violet, cyan) else intArrayOf(cyan, violet, red)
        }
        val c = ui.getInt("edge_color", 0xFF3CC8E6.toInt())
        return intArrayOf(c, c)
    }
}

/** What fills the area outside the picture (the "bars"): black, a gradient, or an image. */
object BarStyle {
    fun drawable(ctx: Context, sw: Int, sh: Int): Drawable {
        val ui = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)
        when (ui.getInt("bar_mode", 0)) {
            1 -> {
                val c1 = ui.getInt("bar_c1", 0xFF2A1D5C.toInt())
                val c2 = ui.getInt("bar_c2", 0xFF0A1330.toInt())
                return GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2))
            }
            2 -> {
                val img = loadImage(ctx, ui.getString("bar_img", null), sw, sh)
                if (img != null) return img
            }
        }
        return ColorDrawable(Color.BLACK)
    }

    /** The chosen picture, scaled and centre-cropped to exactly cover the screen. */
    private fun loadImage(ctx: Context, uriStr: String?, sw: Int, sh: Int): Drawable? {
        if (uriStr == null || sw <= 0 || sh <= 0) return null
        return try {
            val uri = Uri.parse(uriStr)
            val src: Bitmap? = if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val k = maxOf(sw / info.size.width.toFloat(), sh / info.size.height.toFloat())
                    if (k < 1f) {
                        decoder.setTargetSize(
                            (info.size.width * k).toInt().coerceAtLeast(1),
                            (info.size.height * k).toInt().coerceAtLeast(1)
                        )
                    }
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= sw && bounds.outHeight / (sample * 2) >= sh) sample *= 2
                val o = BitmapFactory.Options().apply { inSampleSize = sample }
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            }
            if (src == null) return null
            val out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            val k = maxOf(sw / src.width.toFloat(), sh / src.height.toFloat())
            val dw = src.width * k
            val dh = src.height * k
            Canvas(out).drawBitmap(
                src, null, RectF((sw - dw) / 2f, (sh - dh) / 2f, (sw + dw) / 2f, (sh + dh) / 2f),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
            BitmapDrawable(ctx.resources, out)
        } catch (e: Throwable) {
            null
        }
    }
}

/** A window that shows one slice of a full-screen picture, so several windows line up as one. */
class SliceView(ctx: Context, private val fill: Drawable) : View(ctx) {
    var ox = 0
    var oy = 0
    var fw = 1
    var fh = 1

    override fun onDraw(c: Canvas) {
        c.save()
        c.translate(-ox.toFloat(), -oy.toFloat())
        fill.setBounds(0, 0, fw, fh)
        fill.draw(c)
        c.restore()
    }
}
