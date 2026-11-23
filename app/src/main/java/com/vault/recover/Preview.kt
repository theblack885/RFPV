package com.vault.recover

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

object PreviewLoader {
    fun load(item: Item): Bitmap? = try {
        when (catOf(item)) {
            Cat.PHOTO -> {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(item.backupPath, o)
                var s = 1
                while (o.outWidth / s > 1200 || o.outHeight / s > 1200) s *= 2
                BitmapFactory.decodeFile(
                    item.backupPath, BitmapFactory.Options().apply { inSampleSize = s }
                )
            }
            Cat.VIDEO -> {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(item.backupPath)
                    r.getFrameAtTime(1_000_000)
                } finally {
                    r.release()
                }
            }
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

@Composable
fun GlassButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.clip(shape).background(Ui.Glass)
            .border(1.dp, Ui.GlassBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
}

@Composable
fun PreviewDialog(
    item: Item,
    onClose: () -> Unit,
    onRestore: () -> Unit,
    onRemove: () -> Unit
) {
    val ctx = LocalContext.current
    val cat = catOf(item)
    val isDeleted = item.deletedAt != null
    val bmp by produceState<Bitmap?>(null, item.id) {
        value = withContext(Dispatchers.IO) { PreviewLoader.load(item) }
    }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { try { player?.release() } catch (e: Exception) { } }
    }

    fun togglePlay() {
        try {
            val p = player
            if (p == null) {
                val mp = MediaPlayer().apply {
                    setDataSource(item.backupPath)
                    prepare()
                    setOnCompletionListener { playing = false }
                    start()
                }
                player = mp
                playing = true
            } else if (playing) {
                p.pause(); playing = false
            } else {
                p.start(); playing = true
            }
        } catch (e: Exception) {
            Toast.makeText(ctx, "Play nahi ho paya", Toast.LENGTH_SHORT).show()
        }
    }

    fun openExternal() {
        try {
            val f = File(item.backupPath)
            val ext = File(item.name).extension.lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fp", f)
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Toast.makeText(ctx, "Is file ko kholne wala app nahi mila", Toast.LENGTH_SHORT).show()
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(28.dp)
        Column(
            Modifier.padding(16.dp).fillMaxWidth()
                .clip(shape).background(Ui.Bg1)
                .border(1.dp, Ui.GlassBorder, shape)
                .padding(18.dp)
        ) {
            // ---------- Preview area ----------
            Box(
                Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(20.dp))
                    .background(Brush.linearGradient(listOf(cat.color.copy(alpha = 0.30f), Ui.Bg2))),
                contentAlignment = Alignment.Center
            ) {
                val b = bmp
                if (b != null) {
                    Image(
                        bitmap = b.asImageBitmap(), contentDescription = null,
                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit
                    )
                    if (cat == Cat.VIDEO) {
                        Box(
                            Modifier.size(64.dp).clip(RoundedCornerShape(50))
                                .background(Color.Black.copy(alpha = 0.55f)).clickable { openExternal() },
                            contentAlignment = Alignment.Center
                        ) { Text("▶", fontSize = 26.sp, color = Color.White) }
                    }
                } else {
                    Text(cat.emoji, fontSize = 72.sp)
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                item.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Text(
                fmtSize(item.size) + "  •  " + (
                    if (isDeleted)
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(Date(item.deletedAt!!))
                    else "Vault me safe"
                ),
                fontSize = 12.sp, color = Ui.Muted
            )
            if (item.type == "FILE") {
                Text(
                    "Purani jagah: ${item.originalPath}",
                    fontSize = 11.sp, color = Ui.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(16.dp))

            // ---------- Actions ----------
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (cat == Cat.AUDIO) {
                    GlassButton(
                        if (playing) "⏸  Pause" else "▶  Play",
                        Modifier.weight(1f)
                    ) { togglePlay() }
                } else if (cat != Cat.APP) {
                    GlassButton("Open", Modifier.weight(1f)) { openExternal() }
                }
                GradientButton(
                    if (item.type == "APP") "↩  Install karo" else "↩  Gallery me recover karo",
                    Modifier.weight(2f), onClick = onRestore
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "Vault se hatao", color = Ui.Pink, fontSize = 13.sp,
                    modifier = Modifier.clickable(onClick = onRemove).padding(8.dp)
                )
                Text(
                    "Band karo", color = Ui.Muted, fontSize = 13.sp,
                    modifier = Modifier.clickable(onClick = onClose).padding(8.dp)
                )
            }
        }
    }
}
