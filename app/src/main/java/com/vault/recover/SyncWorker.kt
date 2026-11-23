package com.vault.recover

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.work.*
import java.util.concurrent.TimeUnit

class SyncWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        if (Environment.isExternalStorageManager()) Engine.sync(applicationContext)
        Scheduler.arm(applicationContext)
        return Result.success()
    }
}

object Scheduler {
    fun arm(c: Context) {
        val wm = WorkManager.getInstance(c)

        // Har 15 minute me safety scan
        wm.enqueueUniquePeriodicWork(
            "periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
        )

        // Jaise hi koi nayi file aaye, turant backup
        val cons = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Files.getContentUri("external"), true)
            .setTriggerContentUpdateDelay(5, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(30, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniqueWork(
            "trigger", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(cons).build()
        )
    }
}
