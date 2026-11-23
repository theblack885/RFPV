package com.vault.recover

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class WatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())

    private val runSync = Runnable {
        scope.launch { Engine.sync(applicationContext) }
    }

    private fun schedule() {
        handler.removeCallbacks(runSync)
        handler.postDelayed(runSync, 1500)
    }

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = schedule()
    }

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = schedule()
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("watch", "Backup watcher", NotificationManager.IMPORTANCE_MIN)
        )
        val n = Notification.Builder(this, "watch")
            .setContentTitle("Vault Recover chal raha hai")
            .setContentText("Files live backup ho rahi hain")
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        contentResolver.registerContentObserver(
            MediaStore.Files.getContentUri("external"), true, observer
        )
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, pkgReceiver, f, ContextCompat.RECEIVER_EXPORTED)

        scope.launch { Engine.sync(applicationContext) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        unregisterReceiver(pkgReceiver)
        scope.cancel()
        super.onDestroy()
    }
}
