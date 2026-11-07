package com.example.padoverlay

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast

/** Start / stop the overlay from other apps (MacroDroid, Tasker, adb ...). */
object OverlayControl {
    const val ACTION_START = "com.example.padoverlay.START"
    const val ACTION_STOP = "com.example.padoverlay.STOP"
    const val ACTION_TOGGLE = "com.example.padoverlay.TOGGLE"

    fun handle(ctx: Context, action: String?) {
        when (action) {
            ACTION_START -> start(ctx)
            ACTION_STOP -> stop(ctx)
            ACTION_TOGGLE -> if (OverlayService.running) stop(ctx) else start(ctx)
        }
    }

    fun start(ctx: Context) {
        if (!Settings.canDrawOverlays(ctx)) {
            Toast.makeText(ctx, "PadOverlay: allow overlay permission first", Toast.LENGTH_SHORT).show()
            return
        }
        if (OverlayService.running) return
        try {
            ctx.startForegroundService(Intent(ctx, OverlayService::class.java))
        } catch (e: Exception) {
            Log.w("PadOverlay", "start blocked: $e")
            Toast.makeText(ctx, "PadOverlay: start blocked, see battery setting", Toast.LENGTH_LONG).show()
        }
    }

    fun stop(ctx: Context) {
        ctx.stopService(Intent(ctx, OverlayService::class.java))
    }
}

/** Target for MacroDroid "Send Intent" > Broadcast. */
class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        OverlayControl.handle(context, intent.action)
    }
}

/** Fallback target for "Send Intent" > Activity. Opens invisibly and closes at once. */
class ControlActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OverlayControl.handle(this, intent.action ?: intent.getStringExtra("action"))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
