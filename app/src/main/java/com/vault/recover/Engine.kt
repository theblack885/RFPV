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
import java.io.RandomAccessFile
import java.security.MessageDigest

object Engine {
    private val lock = Mutex()
    @Volatile private var lastRun = 0L

    /** Scan rokne ke liye (UI ka "Rok do" button). */
    @Volatile var stop = false

    const val RESERVE = 1_000_000_000L          // phone me 1 GB khali jagah hamesha bachi rahe
    const val VAULT_LIMIT = 1_099_511_627_776L  // app ka vault max 1 TB
    const val MAX_FILE = 300L * 1024 * 1024     // 300 MB se badi file copy nahi hogi
    private const val MAX_DEEP = 5000
    private const val MAX_CARVE = 1_500_000_000L

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

    // ---------------- Vault ki jagah (phone ya SD card) ----------------
    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun sdAppDir(c: Context): File? =
        c.getExternalFilesDirs(null).drop(1).firstOrNull {
            it != null && Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED
        }

    fun useSd(c: Context): Boolean = prefs(c).getBoolean("vault_sd", false) && sdAppDir(c) != null

    fun vaultRoot(c: Context, sd: Boolean): File {
        val base = if (sd) sdAppDir(c) else c.getExternalFilesDir(null)
        return File(base ?: c.filesDir, "vault").apply { mkdirs() }
    }

    fun vaultDir(c: Context): File = vaultRoot(c, useSd(c))

    /** Phone ya SD card me asli khali jagah. */
    fun phoneFree(c: Context): Long =
        try { StatFs(vaultDir(c).path).availableBytes } catch (e: Exception) { Long.MAX_VALUE }

    @Volatile private var usedCache = 0L
    @Volatile private var usedAt = 0L

    /** Vault abhi kitni jagah le raha hai (har 3 second me ek baar hi ginta hai). */
    fun vaultUsed(c: Context): Long {
        val now = System.currentTimeMillis()
        if (now - usedAt > 3000) {
            usedCache = try {
                vaultDir(c).walkTopDown().filter { it.isFile }.sumOf { it.length() }
            } catch (e: Exception) {
                0L
            }
            usedAt = now
        }
        return usedCache
    }

    /** Copy ke liye kitni jagah milegi: phone ki khali jagah aur 1 TB limit me se jo kam ho. */
    fun freeBytes(c: Context): Long = minOf(phoneFree(c), VAULT_LIMIT - vaultUsed(c))

    /** Phone ki internal storage + SD card ke roots. */
    fun storageRoots(c: Context): List<File> {
        val roots = ArrayList<File>()
        roots.add(Environment.getExternalStorageDirectory())
        c.getExternalFilesDirs(null).forEach { d ->
            val p = d?.absolutePath ?: return@forEach
            val i = p.indexOf("/Android/")
            if (i > 0) {
                val r = File(p.substring(0, i))
                if (roots.none { it.absolutePath == r.absolutePath }) roots.add(r)
            }
        }
        return roots
    }

    /** Restore karne par file jaha jati hai jab uski purani jagah pata na ho. */
    fun restoredPathFor(name: String): String {
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
            for (r in storageRoots(c)) {
                try { walk(r, 0) } catch (e: Exception) { }
            }

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
            if (added > 0 && !force) Notifier.found(c, added)
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
        stack.addAll(storageRoots(c))          // phone + SD card

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

    /** Mail ya Takeout ki file ho to pehle download/extract karo. */
    suspend fun ensureLocal(c: Context, item: Item): Item? = when (item.type) {
        "MAIL" -> MailScanner.download(c, item)
        "ZIP" -> TakeoutScanner.extract(c, item)
        else -> item
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

        val phoneFreeNow = try {
            StatFs(Environment.getExternalStorageDirectory().path).availableBytes
        } catch (e: Exception) {
            Long.MAX_VALUE
        }
        if (phoneFreeNow < item.size + 50L * 1024 * 1024) {
            return@withContext "Phone me jagah kam hai. ${fmtSize(item.size)} wapas laane ke liye thodi jagah khali karo."
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

    /** Kisi bhi type ki item ko recover karo (mail/zip ho to download karke). */
    suspend fun restoreAny(c: Context, item: Item): String {
        val real = try { ensureLocal(c, item) } catch (e: Exception) { null }
            ?: return "Download nahi ho paya (internet ya login check karo)"
        return try { restore(c, real) } catch (e: Exception) { "Error: ${e.message}" }
    }

    suspend fun removeFromVault(c: Context, item: Item) = withContext(Dispatchers.IO) {
        // DEEP aur WA ki file kisi aur app ki hoti hai, use delete nahi karna
        if (item.type in setOf("FILE", "CARVE", "MAIL", "ZIP") && item.backupPath.isNotBlank()) {
            File(item.backupPath).delete()
        }
        AppDb.get(c).dao().delete(item)
    }

    // =====================================================================
    //  Duplicate hatao
    // =====================================================================
    private fun quickHash(f: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        val chunk = 4L * 1024 * 1024
        RandomAccessFile(f, "r").use { r ->
            val len = r.length()
            val buf = ByteArray(1 shl 16)
            fun feed(from: Long, count: Long) {
                r.seek(from)
                var left = count
                while (left > 0) {
                    val n = r.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n <= 0) break
                    md.update(buf, 0, n)
                    left -= n
                }
            }
            feed(0, minOf(len, chunk))
            if (len > chunk * 2) feed(len - chunk, chunk)
            md.update(len.toString().toByteArray())
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun dedupe(c: Context): Int = withContext(Dispatchers.IO) {
        val dao = AppDb.get(c).dao()
        val items = dao.snapshot().filter {
            it.deletedAt != null && it.backupPath.isNotBlank() && File(it.backupPath).isFile
        }
        var removed = 0
        val groups = items.groupBy { File(it.backupPath).length() }.values.filter { it.size > 1 }
        for (grp in groups) {
            val seen = HashSet<String>()
            for (x in grp.sortedBy { it.deletedAt ?: 0L }) {
                val h = try { quickHash(File(x.backupPath)) } catch (e: Exception) { continue }
                if (!seen.add(h)) {
                    removeFromVault(c, x)
                    removed++
                }
            }
        }
        removed
    }

    // =====================================================================
    //  Vault saaf karna
    // =====================================================================
    /** Jo items recover ho chuki hain unki vault copy hatao. */
    suspend fun cleanupRecovered(c: Context): Int = withContext(Dispatchers.IO) {
        var n = 0
        for (x in AppDb.get(c).dao().snapshot()) {
            if (x.deletedAt == null) {
                removeFromVault(c, x)
                n++
            }
        }
        n
    }

    /** `days` din se purani mili hui items ki vault copy hatao. */
    suspend fun cleanupOld(c: Context, days: Int): Int = withContext(Dispatchers.IO) {
        val dao = AppDb.get(c).dao()
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        var n = 0
        for (x in dao.snapshot()) {
            if (x.backedUpAt >= cutoff) continue
            when (x.type) {
                "FILE", "CARVE" -> {
                    removeFromVault(c, x)
                    n++
                }
                "MAIL", "ZIP" -> if (x.backupPath.isNotBlank()) {
                    File(x.backupPath).delete()
                    dao.setBackup(x.id, "")
                    n++
                }
            }
        }
        n
    }

    // =====================================================================
    //  Vault ko SD card me / phone me move karo
    // =====================================================================
    suspend fun moveVault(c: Context, toSd: Boolean, onProgress: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            if (toSd && sdAppDir(c) == null) return@withContext "SD card nahi mila"
            val oldRoot = vaultDir(c)
            val newRoot = vaultRoot(c, toSd)
            if (oldRoot.absolutePath == newRoot.absolutePath) {
                prefs(c).edit().putBoolean("vault_sd", toSd).apply()
                return@withContext "Vault pehle se wahin hai"
            }
            val dao = AppDb.get(c).dao()
            var moved = 0
            var failed = 0
            for (x in dao.snapshot()) {
                if (x.backupPath.isBlank()) continue
                val src = File(x.backupPath)
                if (!src.absolutePath.startsWith(oldRoot.absolutePath + File.separator) || !src.exists()) continue
                val dst = File(newRoot, src.relativeTo(oldRoot).path)
                try {
                    dst.parentFile?.mkdirs()
                    src.copyTo(dst, true)
                    src.delete()
                    dao.setBackup(x.id, dst.absolutePath)
                    moved++
                    if (moved % 10 == 0) onProgress("Vault move: $moved files ho gayi")
                } catch (e: Exception) {
                    dst.delete()
                    failed++
                }
            }
            prefs(c).edit().putBoolean("vault_sd", toSd).apply()
            "Vault move poora: $moved files, $failed fail"
        }
}
