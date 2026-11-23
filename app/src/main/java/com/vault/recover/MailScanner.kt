package com.vault.recover

import android.content.Context
import android.os.Environment
import com.sun.mail.imap.IMAPFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Properties
import javax.mail.AuthenticationFailedException
import javax.mail.FetchProfile
import javax.mail.Folder
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.Store
import javax.mail.UIDFolder

/**
 * Apne email account (IMAP) me purani se purani mails ke photo/video/music attachments dhundhta hai.
 * Sirf attachment ki jankari padhta hai; file tabhi download hoti hai jab tum preview ya restore dabao.
 */
object MailScanner {
    class Creds(val email: String, val pass: String)

    private const val MAX_ITEMS = 5000
    private const val MIN_SIZE = 8_000

    private val mediaExt = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp",
        "mp4", "3gp", "mkv", "mov", "avi", "webm",
        "mp3", "m4a", "wav", "flac", "ogg", "aac", "opus"
    )

    // ---------- Login save / load (sirf is phone me) ----------
    private fun prefs(c: Context) = c.getSharedPreferences("mail_account", Context.MODE_PRIVATE)

    fun load(c: Context): Creds? {
        val p = prefs(c)
        val e = p.getString("email", null)
        val s = p.getString("pass", null)
        return if (e.isNullOrBlank() || s.isNullOrBlank()) null else Creds(e, s)
    }

    fun save(c: Context, cr: Creds) {
        prefs(c).edit().putString("email", cr.email).putString("pass", cr.pass).apply()
    }

    fun clear(c: Context) {
        prefs(c).edit().clear().apply()
    }

    // ---------- Connection ----------
    private fun hostFor(email: String): String {
        val d = email.substringAfter('@').lowercase()
        return when {
            d == "gmail.com" || d == "googlemail.com" -> "imap.gmail.com"
            d.startsWith("yahoo") || d == "ymail.com" || d == "rocketmail.com" -> "imap.mail.yahoo.com"
            d == "outlook.com" || d == "hotmail.com" || d == "live.com" || d == "msn.com" -> "outlook.office365.com"
            else -> "imap.$d"
        }
    }

    private fun connect(cr: Creds): Store {
        System.setProperty("mail.mime.decodefilename", "true")
        val host = hostFor(cr.email)
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", host)
            put("mail.imaps.port", "993")
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.connectiontimeout", "20000")
            put("mail.imaps.timeout", "90000")
            put("mail.imaps.partialfetch", "true")
        }
        val store = Session.getInstance(props).getStore("imaps")
        store.connect(host, cr.email, cr.pass)
        return store
    }

    // ---------- Helpers ----------
    private fun extFromMime(mime: String): String = when (mime) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/heic" -> "heic"
        "video/mp4" -> "mp4"
        "video/3gpp" -> "3gp"
        "video/quicktime" -> "mov"
        "audio/mpeg" -> "mp3"
        "audio/mp4", "audio/x-m4a" -> "m4a"
        "audio/wav", "audio/x-wav" -> "wav"
        "audio/ogg" -> "ogg"
        else -> ""
    }

    private fun baseMime(part: Part): String =
        try { part.contentType.substringBefore(';').trim().lowercase() } catch (e: Exception) { "" }

    /** Media attachment ka naam, ya null agar ye media nahi hai. */
    private fun mediaName(part: Part, mime: String, fallback: String): String? {
        val raw = try { part.fileName } catch (e: Exception) { null }
        val ext = raw?.substringAfterLast('.', "")?.lowercase().orEmpty()
        val isMedia = mime.startsWith("image/") || mime.startsWith("video/") ||
            mime.startsWith("audio/") || ext in mediaExt
        if (!isMedia) return null
        var name = raw?.trim().orEmpty()
        val mimeExt = extFromMime(mime)
        if (name.isBlank()) {
            name = "$fallback.${if (mimeExt.isNotEmpty()) mimeExt else "jpg"}"
        } else if (ext !in mediaExt && mimeExt.isNotEmpty()) {
            name = "$name.$mimeExt"
        }
        return name.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_")
    }

    private fun collect(part: Part, path: String, acc: MutableList<Pair<Part, String>>) {
        if (part.isMimeType("multipart/*")) {
            val mp = part.content as Multipart
            for (i in 0 until mp.count) {
                collect(mp.getBodyPart(i), if (path.isEmpty()) "$i" else "$path.$i", acc)
            }
        } else {
            acc.add(Pair(part, path))
        }
    }

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

    // ---------- Scan: sabse purani mail se shuru ----------
    suspend fun scan(c: Context, cr: Creds, onProgress: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            Engine.stop = false
            val dao = AppDb.get(c).dao()
            val known = dao.ids().toHashSet()
            val now = System.currentTimeMillis()
            var found = 0
            var mails = 0

            val store = try {
                connect(cr)
            } catch (e: AuthenticationFailedException) {
                return@withContext "Login fail: email ya app password galat hai. Gmail me normal password nahi, App password chalta hai."
            } catch (e: Exception) {
                return@withContext "Connect nahi hua: ${e.message}"
            }

            try {
                val allFolders = store.defaultFolder.list("*")
                val folders = ArrayList<Folder>()
                // Gmail me "All Mail" me sab kuch hota hai, baaki folders sirf duplicate honge
                val allMail = allFolders.firstOrNull { f ->
                    try { (f as IMAPFolder).attributes.contains("\\All") } catch (e: Exception) { false }
                }
                if (allMail != null) folders.add(allMail)
                else allFolders.filterTo(folders) { f ->
                    try { (f.type and Folder.HOLDS_MESSAGES) != 0 } catch (e: Exception) { false }
                }

                for (folder in folders) {
                    if (Engine.stop || found >= MAX_ITEMS) break
                    try { folder.open(Folder.READ_ONLY) } catch (e: Exception) { continue }
                    val total = folder.messageCount
                    val uidFolder = folder as UIDFolder
                    var start = 1

                    while (start <= total && !Engine.stop && found < MAX_ITEMS) {
                        val end = minOf(start + 49, total)
                        val msgs = folder.getMessages(start, end)
                        val fp = FetchProfile().apply {
                            add(FetchProfile.Item.ENVELOPE)
                            add(FetchProfile.Item.CONTENT_INFO)
                            add(UIDFolder.FetchProfileItem.UID)
                        }
                        try { folder.fetch(msgs, fp) } catch (e: Exception) { }

                        for (m in msgs) {
                            if (Engine.stop) break
                            mails++
                            if (mails % 20 == 0) {
                                onProgress("Email scan: $mails mails dekhi  •  $found media mili")
                            }
                            try {
                                if (!m.isMimeType("multipart/*")) continue
                                val uid = uidFolder.getUID(m)
                                val date = (m.sentDate ?: m.receivedDate)?.time ?: now
                                val hits = ArrayList<Pair<Part, String>>()
                                collect(m, "", hits)
                                for ((part, path) in hits) {
                                    val size = try { part.size } catch (e: Exception) { -1 }
                                    if (size in 0 until MIN_SIZE) continue
                                    val mime = baseMime(part)
                                    val name = mediaName(part, mime, "mail_${uid}_${path.replace('.', '_')}") ?: continue
                                    val id = "mail|${cr.email}|${folder.fullName}|$uid|$path"
                                    if (id in known) continue
                                    dao.insert(
                                        Item(
                                            id, "MAIL", name, restoredPathFor(name), "",
                                            size.toLong().coerceAtLeast(0), now, date
                                        )
                                    )
                                    known.add(id)
                                    found++
                                }
                            } catch (e: Exception) {
                                // kharab mail ko chhod do
                            }
                        }
                        start = end + 1
                    }
                    try { folder.close(false) } catch (e: Exception) { }
                }
            } catch (e: Exception) {
                return@withContext "Scan beech me ruk gaya: ${e.message}  (mile: $found)"
            } finally {
                try { store.close() } catch (e: Exception) { }
            }

            if (Engine.stop) "Roka gaya: $mails mails dekhi, $found media mili"
            else "Email scan poora: $mails mails dekhi, $found media mili"
        }

    // ---------- Ek attachment ko mail se download karo ----------
    suspend fun download(c: Context, item: Item): Item? = withContext(Dispatchers.IO) {
        if (item.backupPath.isNotBlank() && File(item.backupPath).exists()) {
            return@withContext item
        }
        val cr = load(c) ?: return@withContext null
        val p = item.id.split("|", limit = 5)
        if (p.size < 5) return@withContext null
        val folderName = p[2]
        val uid = p[3].toLongOrNull() ?: return@withContext null
        val path = p[4]
        if (Engine.freeBytes(c) - item.size < Engine.RESERVE) return@withContext null

        val store = try { connect(cr) } catch (e: Exception) { return@withContext null }
        try {
            val folder = store.getFolder(folderName) as IMAPFolder
            folder.open(Folder.READ_ONLY)
            val msg = folder.getMessageByUID(uid) ?: return@withContext null
            var part: Part = msg
            if (path.isNotEmpty()) {
                for (idx in path.split('.')) {
                    part = (part.content as Multipart).getBodyPart(idx.toInt())
                }
            }
            val dir = File(Engine.vaultDir(c), "mail").apply { mkdirs() }
            val dest = File(dir, "${System.nanoTime()}_${item.name}")
            part.inputStream.use { ins ->
                FileOutputStream(dest).use { out -> ins.copyTo(out) }
            }
            try { folder.close(false) } catch (e: Exception) { }
            AppDb.get(c).dao().setBackup(item.id, dest.absolutePath)
            item.copy(backupPath = dest.absolutePath)
        } catch (e: Exception) {
            null
        } finally {
            try { store.close() } catch (e: Exception) { }
        }
    }
}
