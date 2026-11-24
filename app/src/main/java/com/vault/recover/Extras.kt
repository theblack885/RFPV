package com.vault.recover

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

val MEDIA_EXT = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp",
    "mp4", "3gp", "mkv", "mov", "avi", "webm",
    "mp3", "m4a", "wav", "flac", "ogg", "aac", "opus"
)

// ------------------------------------------------------------------
//  Notification: nayi deleted file milne par
// ------------------------------------------------------------------
object Notifier {
    fun found(c: Context, n: Int) {
        try {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel("alerts", "Deleted files alerts", NotificationManager.IMPORTANCE_DEFAULT)
            )
            val pi = PendingIntent.getActivity(
                c, 0, Intent(c, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val nt = Notification.Builder(c, "alerts")
                .setContentTitle("Nayi deleted files mili")
                .setContentText("$n nayi file recover ke liye ready hain")
                .setSmallIcon(android.R.drawable.ic_menu_save)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(2, nt)
        } catch (e: Exception) { }
    }
}

// ------------------------------------------------------------------
//  Chhote thumbnails (list me)
// ------------------------------------------------------------------
object Thumbs {
    private val cache = LruCache<String, Bitmap>(80)

    fun load(item: Item): Bitmap? {
        val path = item.backupPath
        if (path.isBlank()) return null
        cache.get(path)?.let { return it }
        val bmp: Bitmap? = try {
            when (catOf(item)) {
                Cat.PHOTO -> {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, o)
                    var s = 1
                    while (o.outWidth / (s * 2) >= 160 && o.outHeight / (s * 2) >= 160) s *= 2
                    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
                }
                Cat.VIDEO -> {
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(path)
                        val f = r.getFrameAtTime(1_000_000)
                        if (f == null) null
                        else Bitmap.createScaledBitmap(f, 160, maxOf(1, f.height * 160 / f.width), true)
                    } finally {
                        r.release()
                    }
                }
                else -> null
            }
        } catch (e: Throwable) {
            null
        }
        if (bmp != null) cache.put(path, bmp)
        return bmp
    }
}

// ------------------------------------------------------------------
//  WhatsApp media (photos, videos, voice notes, status)
// ------------------------------------------------------------------
object WhatsAppScanner {
    suspend fun scan(c: Context, onProgress: (String) -> Unit): String = withContext(Dispatchers.IO) {
        Engine.stop = false
        val dao = AppDb.get(c).dao()
        val known = dao.ids().toHashSet()
        val now = System.currentTimeMillis()

        val stack = ArrayList<File>()
        for (r in Engine.storageRoots(c)) {
            listOf(
                "WhatsApp/Media",
                "Android/media/com.whatsapp/WhatsApp/Media",
                "WhatsApp Business/Media",
                "Android/media/com.whatsapp.w4b/WhatsApp Business/Media"
            ).forEach { rel ->
                val f = File(r, rel)
                if (f.isDirectory) stack.add(f)
            }
        }
        if (stack.isEmpty()) return@withContext "WhatsApp ka Media folder nahi mila"

        var seen = 0
        var added = 0
        while (stack.isNotEmpty() && !Engine.stop) {
            val d = stack.removeAt(stack.size - 1)
            val kids = d.listFiles() ?: continue
            for (k in kids) {
                if (Engine.stop) break
                if (k.isDirectory) {
                    stack.add(k)
                    continue
                }
                seen++
                if (seen % 200 == 0) onProgress("WhatsApp scan: $seen files dekhi  •  $added mili")
                if (k.extension.lowercase() !in MEDIA_EXT) continue
                val size = k.length()
                if (size < 8_000) continue
                val id = "wa|${k.path}|$size"
                if (id in known) continue
                val t = if (k.lastModified() > 0) k.lastModified() else now
                dao.insert(Item(id, "WA", k.name, Engine.restoredPathFor(k.name), k.path, size, now, t))
                known.add(id)
                added++
            }
        }
        "WhatsApp scan poora: $added media mili"
    }
}

// ------------------------------------------------------------------
//  Google Takeout zip import (zip ke andar ki purani photos/videos/music)
//  Zip extract tabhi hota hai jab tum preview ya restore dabao.
// ------------------------------------------------------------------
object TakeoutScanner {
    private const val MAX_ZIP = 20000

    suspend fun scan(c: Context, onProgress: (String) -> Unit): String = withContext(Dispatchers.IO) {
        Engine.stop = false
        val zips = ArrayList<File>()
        val stack = ArrayList<File>()
        stack.addAll(Engine.storageRoots(c))
        while (stack.isNotEmpty() && !Engine.stop) {
            val d = stack.removeAt(stack.size - 1)
            val kids = d.listFiles() ?: continue
            for (k in kids) {
                if (k.isDirectory) {
                    if (k.name == c.packageName || k.path.endsWith("/Android/obb") ||
                        k.path.endsWith("/Android/data")
                    ) continue
                    if (k.path.count { it == '/' } > 10) continue
                    stack.add(k)
                } else if (k.name.endsWith(".zip", true) && k.name.contains("takeout", true)) {
                    zips.add(k)
                }
            }
        }
        if (zips.isEmpty()) {
            return@withContext "Phone me koi Takeout zip nahi mili. takeout.google.com se download karke Downloads folder me rakho."
        }

        val dao = AppDb.get(c).dao()
        val known = dao.ids().toHashSet()
        val now = System.currentTimeMillis()
        var added = 0

        for ((zi, zf) in zips.withIndex()) {
            if (Engine.stop) break
            try {
                ZipFile(zf).use { z ->
                    val en = z.entries()
                    while (en.hasMoreElements() && added < MAX_ZIP && !Engine.stop) {
                        val e = en.nextElement()
                        if (e.isDirectory) continue
                        val ext = e.name.substringAfterLast('.', "").lowercase()
                        if (ext !in MEDIA_EXT) continue
                        val sz = if (e.size >= 0) e.size else e.compressedSize
                        if (sz in 0 until 8_000) continue
                        val id = "zip|${zf.path}|${e.name}"
                        if (id in known) continue
                        val name = e.name.substringAfterLast('/')
                        val t = if (e.time > 0) e.time else now
                        dao.insert(
                            Item(id, "ZIP", name, Engine.restoredPathFor(name), "", sz.coerceAtLeast(0), now, t)
                        )
                        known.add(id)
                        added++
                        if (added % 100 == 0) {
                            onProgress("Takeout: zip ${zi + 1}/${zips.size}  •  $added media mili")
                        }
                    }
                }
            } catch (e: Exception) {
                // kharab zip chhod do
            }
        }
        "Takeout scan poora: ${zips.size} zip, $added media mili"
    }

    suspend fun extract(c: Context, item: Item): Item? = withContext(Dispatchers.IO) {
        if (item.backupPath.isNotBlank() && File(item.backupPath).exists()) return@withContext item
        val p = item.id.split("|", limit = 3)
        if (p.size < 3) return@withContext null
        if (Engine.freeBytes(c) - item.size < Engine.RESERVE) return@withContext null
        try {
            ZipFile(File(p[1])).use { z ->
                val e = z.getEntry(p[2]) ?: return@withContext null
                val dir = File(Engine.vaultDir(c), "takeout").apply { mkdirs() }
                val dest = File(dir, "${System.nanoTime()}_${item.name}")
                z.getInputStream(e).use { ins ->
                    FileOutputStream(dest).use { out -> ins.copyTo(out) }
                }
                AppDb.get(c).dao().setBackup(item.id, dest.absolutePath)
                item.copy(backupPath = dest.absolutePath)
            }
        } catch (e: Exception) {
            null
        }
    }
}
