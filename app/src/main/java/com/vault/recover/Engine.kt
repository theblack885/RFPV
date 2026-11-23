package com.vault.recover

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

object Engine {
    private val lock = Mutex()

    fun vaultDir(c: Context): File =
        File(c.getExternalFilesDir(null), "vault").apply { mkdirs() }

    /** Naye files/apps backup karo, aur jo gayab hue unhe "deleted" mark karo. */
    suspend fun sync(c: Context): Int = lock.withLock {
        withContext(Dispatchers.IO) {
            val dao = AppDb.get(c).dao()
            val known = dao.ids().toHashSet()
            val vault = vaultDir(c)
            val now = System.currentTimeMillis()
            var added = 0

            // ---- Files: photo, video, song, documents ----
            val proj = arrayOf(
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_MODIFIED
            )
            c.contentResolver.query(
                MediaStore.Files.getContentUri("external"), proj, null, null, null
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val path = cur.getString(0) ?: continue
                    if (path.contains("/Android/data/") || path.contains("/Android/obb/")) continue
                    val f = File(path)
                    if (f.name.startsWith(".") || path.contains("/.thumbnails/")) continue
                    val size = cur.getLong(1)
                    if (size <= 0 || !f.isFile) continue
                    val id = "$path|$size|${cur.getLong(2)}"
                    if (id in known) continue
                    val dest = File(vault, "${System.nanoTime()}_${f.name}")
                    try {
                        f.copyTo(dest, true)
                        dao.insert(Item(id, "FILE", f.name, path, dest.absolutePath, size, now, null))
                        known.add(id)
                        added++
                    } catch (e: Exception) {
                        dest.delete()
                    }
                }
            }

            // ---- Installed apps (APK backup) ----
            val pm = c.packageManager
            pm.getInstalledApplications(0)
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != c.packageName }
                .forEach { ai ->
                    try {
                        val pi = pm.getPackageInfo(ai.packageName, 0)
                        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
                        val id = "app|${ai.packageName}|$code"
                        if (id in known) return@forEach
                        val dest = File(vault, "${ai.packageName}_$code.apk")
                        File(ai.sourceDir).copyTo(dest, true)
                        dao.insert(
                            Item(id, "APP", pm.getApplicationLabel(ai).toString(),
                                ai.packageName, dest.absolutePath, dest.length(), now, null)
                        )
                        known.add(id)
                        added++
                    } catch (e: Exception) { }
                }

            // ---- Jo ab phone me nahi hain unhe deleted mark karo ----
            dao.live().forEach { it ->
                val gone = if (it.type == "FILE") !File(it.originalPath).exists()
                else !isInstalled(pm, it.originalPath)
                if (gone) dao.markDeleted(it.id, now)
            }
            added
        }
    }

    private fun isInstalled(pm: PackageManager, pkg: String): Boolean =
        try { pm.getPackageInfo(pkg, 0); true } catch (e: PackageManager.NameNotFoundException) { false }

    /** Restore: file wapas uski original jagah, app ke liye install screen. */
    suspend fun restore(c: Context, item: Item): String = withContext(Dispatchers.IO) {
        val src = File(item.backupPath)
        if (!src.exists()) return@withContext "Backup file nahi mili"
        if (item.type == "APP") {
            val uri = FileProvider.getUriForFile(c, "${c.packageName}.fp", src)
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            c.startActivity(i)
            return@withContext "Install screen khul gayi"
        }
        var dst = File(item.originalPath)
        dst.parentFile?.mkdirs()
        if (dst.exists()) dst = File(dst.parentFile, "restored_${dst.name}")
        src.copyTo(dst, false)
        MediaScannerConnection.scanFile(c, arrayOf(dst.absolutePath), null, null)
        AppDb.get(c).dao().markRestored(item.id)
        "Restore hua: ${dst.absolutePath}"
    }

    suspend fun removeFromVault(c: Context, item: Item) = withContext(Dispatchers.IO) {
        File(item.backupPath).delete()
        AppDb.get(c).dao().delete(item)
    }
}
