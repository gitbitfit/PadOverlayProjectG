package com.example.padoverlay

import android.os.IBinder
import android.os.Parcel

/** App-side wrapper that talks to InputService over Binder. */
class InputClient(private val binder: IBinder) {

    fun key(keyCode: Int, down: Boolean) {
        val d = Parcel.obtain()
        try {
            d.writeInt(keyCode)
            d.writeInt(if (down) 1 else 0)
            binder.transact(InputService.CODE_KEY, d, null, IBinder.FLAG_ONEWAY)
        } catch (_: Exception) {
        } finally {
            d.recycle()
        }
    }

    fun stick(stickId: Int, x: Float, y: Float) {
        val d = Parcel.obtain()
        try {
            d.writeInt(stickId)
            d.writeFloat(x)
            d.writeFloat(y)
            binder.transact(InputService.CODE_STICK, d, null, IBinder.FLAG_ONEWAY)
        } catch (_: Exception) {
        } finally {
            d.recycle()
        }
    }

    /** action: 0 = down, 1 = move, 2 = up. x/y in screen pixels. */
    fun touch(slot: Int, action: Int, x: Float, y: Float) {
        val d = Parcel.obtain()
        try {
            d.writeInt(slot)
            d.writeInt(action)
            d.writeFloat(x)
            d.writeFloat(y)
            binder.transact(InputService.CODE_TOUCH, d, null, IBinder.FLAG_ONEWAY)
        } catch (_: Exception) {
        } finally {
            d.recycle()
        }
    }

    /** 0 = generic, 1 = Xbox, 2 = DualShock style, 3 = none (touch mode). */
    fun setProfile(profile: Int) {
        val d = Parcel.obtain()
        try {
            d.writeInt(profile)
            binder.transact(InputService.CODE_PROFILE, d, null, IBinder.FLAG_ONEWAY)
        } catch (_: Exception) {
        } finally {
            d.recycle()
        }
    }

    /** Virtual display mode: send pad input straight to the game (beta). */
    fun setFast(on: Boolean) {
        val d = Parcel.obtain()
        try {
            d.writeInt(if (on) 1 else 0)
            binder.transact(InputService.CODE_FAST, d, null, IBinder.FLAG_ONEWAY)
        } catch (_: Exception) {
        } finally {
            d.recycle()
        }
    }
}
