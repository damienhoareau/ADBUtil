package com.leonkernan.adbutil

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.concurrent.thread

/** Enables ADB, then network ADB on the firewall-allowed port, silently once the head unit has booted. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val appContext = context.applicationContext
        val resolver = appContext.contentResolver
        thread(name = "adbutil-boot") {
            try {
                val enabled = applyAdb(resolver)
                enabled.fold(
                    onSuccess = { Log.i(TAG, "ADB enabled at boot") },
                    onFailure = { Log.e(TAG, "Failed to enable ADB at boot", it) }
                )
                if (enabled.isSuccess) {
                    // adbd just restarted on 5555 (blocked by the car firewall): move it to the
                    // whitelisted port. The RSA key is already authorized, so no prompt is expected.
                    runCatching {
                        NetworkAdb.enable(appContext, NetworkAdb.DEFAULT_PORT, sourceWaitMs = 20_000)
                    }.fold(
                        onSuccess = { Log.i(TAG, "Network ADB listening on ${it.port}") },
                        onFailure = { Log.e(TAG, "Failed to enable network ADB at boot", it) }
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "ADBUtil"
    }
}
