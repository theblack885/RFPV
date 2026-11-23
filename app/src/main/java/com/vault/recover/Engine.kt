package com.vault.recover

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

object Engine {
    private val lock = Mutex()
    @Volatile private var lastRun = 0L

    /** Scan rokne ke liye (UI ka "Rok do" button). */
    @Volatile var stop = false

    /** Phone me itni jagah hamesha khali rahegi. */
    const val RESERVE = 1_500_000_000L          // 1.5 GB
    /** Isse badi file copy nahi hogi. */
    const val MAX_FILE = 300L * 1024 * 1024     // 300 MB
    private const val MAX_DEEP = 5000           // deep scan me max itne results
    private const val MAX_CARVE = 1_500_000_000L // root scan me max itna data save

    private val trashedPrefix = Regex("^\\.trashed-(\\d+)-")
    private val trashDirName = Regex("(?i)trash|recycle|recently ?deleted|\\.deleted")

    private class Cand(val file: File, val origPath: String, val deletedAt: Long)

    fun hasAccess(c: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(c, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    fun vaultDir(c: Context): File =
        File(c.getExternalFilesDir(null), "vault").apply { mkdirs() }

    fun freeBytes(c: Context): Long =
        try { StatFs(vaultDir(c).path).availableBytes } catch (e: Exception) { Long.MAX_VALUE }

    /** Jahan restore karne par file jati hai jab uski purani jagah pata na ho. */
    private fun restoredPathFor(name: String): String {
        val cat = catOf(Item("", "FILE", name, "", "", 0, 0, null))
        val base = when (cat) {
            Cat.PHOTO -> Environment.DIRECTORY_PICTURES
            Cat.VIDEO -> Environment.DIRECTORY_MOVIES
            Cat.AUDIO -> Environment.DIRECTORY_MUSIC
            else -> Environment.DIRECTORY_DOWNLOADS
        }
        return File(File(Environment.getExternalStoragePublicDirectory(base), "Restored"), name).path
    }

    // =====================================================================
    //  LEVEL 1: Trash / Recycle folders
    // =====================================================================
    suspend fun sync(c: Context, force: Boolean = false): Int = lock.withLock {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (!force && now - lastRun < 60_000) return@withContext 0
            lastRun = now

            val dao = AppDb.get(c).dao()
            val known = dao.ids().toHashSet()
            val vault = vaultDir(c)
            val cands = ArrayList<Cand>()

            fun addTrashed(f: File) {
                val m = trashedPrefix.find(f.name) ?: return
                val cleanName = f.name.removePrefix(m.value)
                val expiry = m.groupValues[1].toLongOrNull()?.times(1000) ?: 0L
                val d = expiry - 30L * 86_400_000
                cands.add(
                    Cand(f, File(f.parentFile, cleanName).path, if (d in 1..now) d else f.lastModified())
                )
            }

            fun collectTrashDir(dir: File, depth: Int) {
                if (depth > 4) return
                val kids = dir.listFiles() ?: return
                for (k in kids) {
                    if (k.isDirectory) collectTrashDir(k, depth + 1)
                    else if (k.name != ".nomedia" && k.length() > 0) {
                        cands.add(Cand(k, restoredPathFor(k.name), now))
                    }
                }
            }

            fun walk(dir: File, depth: Int) {
                if (depth > 5) return
                val kids = dir.listFiles() ?: return
                for (k in kids) {
                    if (k.isDirectory) {
                        if (k.name == c.packageName || k.path.contains("/Android/obb")) continue
                        if (k.name.equals("cache", true) || k.name.equals("code_cache", true)) continue
                        if (trashDirName.containsMatchIn(k.name)) collectTrashDir(k, 0)
                        else walk(k, depth + 1)
                    } else if (trashedPrefix.containsMatchIn(k.name)) {
                        addTrashed(k)
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    val args = Bundle().apply {
                        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
                    }
                    c.contentResolver.query(
                        MediaStore.Files.getContentUri("external"),
                        arrayOf(MediaStore.Files.FileColumns.DATA), args, null
                    )?.use { cur ->
                        while (cur.moveToNext()) {
                            val p = cur.getString(0) ?: continue
                            addTrashed(File(p))
                        }
                    }
                } catch (e: Exception) { }
            }
            try { walk(Environment.getExternalStorageDirectory(), 0) } catch (e: Exception) { }

            var added = 0
            for (cand in cands) {
                val f = cand.file
                if (!f.isFile) continue
                val size = f.length()
                if (size <= 0 || size > MAX_FILE) continue
                val id = "trash|${f.path}|$size"
                if (id in known) continue
                if (freeBytes(c) - size < RESERVE) break

                val name = File(cand.origPath).name
                val dest = File(vault, "${System.nanoTime()}_$name")
                try {
                    f.copyTo(dest, true)
                    dao.insert(
                        Item(id, "FILE", name, cand.origPath, dest.absolutePath, size, now, cand.deletedAt)
                    )
                    known.add(id)
                    added++
                } catch (e: Exception) {
                    dest.delete()
                    if (e is IOException) break
                }
            }
            added
        }
    }

    // =====================================================================
    //  LEVEL 2: Deep scan (bina root)
    // =====================================================================
    private fun sniff(f: File): String? {
        val h = ByteArray(12)
        val n = try { f.inputStream().use { it.read(h) } } catch (e: Exception) { return null }
        if (n < 12) return null
        fun u(i: Int) = h[i].toInt() and 0xFF
        fun s(from: Int, len: Int) = String(h, from, len, Charsets.ISO_8859_1)
        return when {
            u(0) == 0xFF && u(1) == 0xD8 && u(2) == 0xFF -> "jpg"
            u(0) == 0x89 && s(1, 3) == "PNG" -> "png"
            s(0, 4) == "RIFF" && s(8, 4) == "WEBP" -> "webp"
            s(0, 4) == "RIFF" && s(8, 4) == "WAVE" -> "wav"
            s(0, 3) == "GIF" -> "gif"
            s(4, 4) == "ftyp" -> {
                val brand = s(8, 4)
                when {
                    brand == "heic" || brand == "heix" || brand == "mif1" -> "heic"
                    brand == "M4A " -> "m4a"
                    brand.startsWith("qt") -> "mov"
                    brand.startsWith("3gp") -> "3gp"
                    else -> "mp4"
                }
            }
            s(0, 3) == "ID3" -> "mp3"
            s(0, 4) == "OggS" -> "ogg"
            s(0, 4) == "fLaC" -> "flac"
            else -> null
        }
    }

    suspend fun deepScan(c: Context, onProgress: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        stop = false
        val dao = AppDb.get(c).dao()
        val known = dao.ids().toHashSet()
        val now = System.currentTimeMillis()

        val indexed = HashSet<String>()
        try {
            c.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.Files.FileColumns.DATA), null, null, null
            )?.use { cur ->
                while (cur.moveToNext()) cur.getString(0)?.let { indexed.add(it) }
            }
        } catch (e: Exception) { }

        var seen = 0
        var added = 0
        val stack = ArrayList<File>()
        stack.add(Environment.getExternalStorageDirectory())

        while (stack.isNotEmpty() && added < MAX_DEEP && !stop) {
            val d = stack.removeAt(stack.size - 1)
            val kids = d.listFiles() ?: continue
            for (k in kids) {
                if (stop) break
                if (k.isDirectory) {
                    if (k.name == c.packageName || k.path.endsWith("/Android/obb")) continue
                    if (k.path.count { it == '/' } > 14) continue
                    stack.add(k)
                    continue
                }
                seen++
                if (seen % 300 == 0) onProgress("Scan ho raha hai: $seen files dekhi  •  $added mili")

                val size = k.length()
                if (size < 8_000 || size > MAX_FILE) continue
                if (k.path in indexed) continue
                if (trashedPrefix.containsMatchIn(k.name)) continue
                val ext = sniff(k) ?: continue
                val minSize = when (ext) {
                    "jpg", "png", "webp", "gif", "heic" -> 8_000L
                    "mp3", "m4a", "ogg", "flac", "wav" -> 50_000L
                    else -> 100_000L
                }
                if (size < minSize) continue

                val id = "deep|${k.path}|$size"
                if (id in known || "trash|${k.path}|$size" in known) continue

                val have = k.extension.lowercase()
                val ok = have == ext || (ext == "jpg" && have == "jpeg")
                val name = (if (ok) k.name else "${k.name}.$ext").trimStart('.').ifBlank { "file.$ext" }
                val deletedAt = if (k.lastModified() > 0) k.lastModified() else now

                dao.insert(Item(id, "DEEP", name, restoredPathFor(name), k.path, size, now, deletedAt))
                known.add(id)
                added++
            }
        }
        onProgress("Deep scan poora: $seen files dekhi  •  $added mili")
        added
    }

    // =====================================================================
    //  LEVEL 3: Root raw scan (sirf rooted phone)
    // =====================================================================
    private suspend fun carve(
        c: Context, input: InputStream, outDir: File, now: Long,
        dao: ItemDao, onProgress: (String) -> Unit
    ): Pair<Long, Int> {
        val buf = ByteArray(1 shl 20)
        var total = 0L
        var found = 0
        var savedBytes = 0L
        var out: BufferedOutputStream? = null
        var file: File? = null
        var size = 0L
        var p1 = 0
        var p2 = 0

        suspend fun finish(keep: Boolean) {
            val o = out
            val f = file
            out = null
            file = null
            if (o == null || f == null) return
            try { o.close() } catch (e: Exception) { }
            var ok = keep
            if (ok) {
                val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, opt)
                ok = opt.outWidth >= 200 && opt.outHeight >= 200
            }
            if (ok) {
                val id = "carve|${f.path}|${f.length()}"
                dao.insert(Item(id, "CARVE", f.name, restoredPathFor(f.name), f.path, f.length(), now, now))
                found++
                savedBytes += f.length()
            } else {
                f.delete()
            }
        }

        while (!stop) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n
            for (i in 0 until n) {
                val b = buf[i].toInt() and 0xFF
                val o = out
                if (o == null) {
                    if (p2 == 0xFF && p1 == 0xD8 && b == 0xFF &&
                        savedBytes < MAX_CARVE && freeBytes(c) > RESERVE
                    ) {
                        val f = File(outDir, "carved_${System.nanoTime()}.jpg")
                        file = f
                        out = BufferedOutputStream(FileOutputStream(f), 1 shl 16).also {
                            it.write(0xFF); it.write(0xD8); it.write(0xFF)
                        }
                        size = 3
                    }
                } else {
                    o.write(b)
                    size++
                    if (p1 == 0xFF && b == 0xD9 && size >= 40_000) finish(true)
                    else if (size > 12L * 1024 * 1024) finish(false)
                }
                p2 = p1
                p1 = b
            }
            onProgress("Raw scan: ${fmtSize(total)} padha  •  $found photo mili")
        }
        finish(false)
        return Pair(total, found)
    }

    suspend fun rootScan(c: Context, onProgress: (String) -> Unit): String = withContext(Dispatchers.IO) {
        stop = false
        val dao = AppDb.get(c).dao()
        val now = System.currentTimeMillis()
        val outDir = File(vaultDir(c), "carved").apply { mkdirs() }
        val devs = listOf("/dev/block/bootdevice/by-name/userdata", "/dev/block/by-name/userdata")
        var opened = false
        var found = 0

        for (dev in devs) {
            val proc = try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $dev"))
            } catch (e: IOException) {
                return@withContext "Phone root nahi hai (ya su permission nahi mili). Raw scan sirf rooted phone me chalta hai."
            }
            val (read, cnt) = try {
                carve(c, proc.inputStream, outDir, now, dao, onProgress)
            } catch (e: Exception) {
                Pair(0L, 0)
            }
            proc.destroy()
            if (read > 0) {
                opened = true
                found = cnt
                break
            }
        }
        when {
            !opened -> "Storage partition nahi khul payi (root permission ya device path ki dikkat)."
            stop -> "Scan roka gaya: $found photo mili"
            else -> "Raw scan poora: $found photo mili"
        }
    }

    // =====================================================================
    //  Restore / remove
    // =====================================================================
    private fun copyBack(c: Context, src: File, dst: File): File {
        dst.parentFile?.mkdirs()
        var d = dst
        if (d.exists()) d = File(dst.parentFile, "restored_${dst.name}")
        if (d.exists()) d = File(dst.parentFile, "restored_${System.currentTimeMillis()}_${dst.name}")
        src.copyTo(d, false)
        MediaScannerConnection.scanFile(c, arrayOf(d.absolutePath), null, null)
        return d
    }

    suspend fun restore(c: Context, item: Item): String = withContext(Dispatchers.IO) {
        val src = File(item.backupPath)
        if (!src.exists()) return@withContext "File ab nahi mili"

        if (item.type == "APP") {
            val uri = FileProvider.getUriForFile(c, "${c.packageName}.fp", src)
            c.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return@withContext "Install screen khul gayi"
        }

        val orig = File(item.originalPath)
        val target = try {
            copyBack(c, src, orig)
        } catch (e: Exception) {
            copyBack(c, src, File(restoredPathFor(orig.name)))
        }
        AppDb.get(c).dao().relocate(item.id, target.absolutePath)
        "Wapas aa gayi: ${target.absolutePath}"
    }

    suspend fun removeFromVault(c: Context, item: Item) = withContext(Dispatchers.IO) {
        if (item.type != "DEEP") File(item.backupPath).delete()
        AppDb.get(c).dao().delete(item)
    }
}
