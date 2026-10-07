package com.leonkernan.adbutil

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.concurrent.thread

/** Enables ADB silently (no UI) once the head unit has finished booting. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val resolver = context.applicationContext.contentResolver
        thread(name = "adbutil-boot") {
            try {
                applyAdb(resolver).fold(
                    onSuccess = { Log.i(TAG, "ADB enabled at boot") },
                    onFailure = { Log.e(TAG, "Failed to enable ADB at boot", it) }
                )
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "ADBUtil"
    }
}
