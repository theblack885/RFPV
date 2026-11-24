package com.vault.recover

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Phone restart ke baad app apne aap chalu ho jaye. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        if (!Engine.hasAccess(c)) return
        try { Scheduler.arm(c) } catch (e: Exception) { }
        try {
            ContextCompat.startForegroundService(c, Intent(c, WatchService::class.java))
        } catch (e: Exception) { }
    }
}
