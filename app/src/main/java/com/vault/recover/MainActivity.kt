package com.vault.recover

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private var access by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            VaultTheme {
                Screen(access) { askAccess() }
            }
        }
    }

    private fun askAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } else {
            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ), 2
            )
        }
    }

    private fun refresh() {
        access = Engine.hasAccess(this)
        if (access) {
            Scheduler.arm(this)
            ContextCompat.startForegroundService(this, Intent(this, WatchService::class.java))
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }
}

fun fmtSize(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / 1073741824.0)
    b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
    b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

@Composable
fun Screen(access: Boolean, onAllow: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDb.get(ctx).dao() }
    val allItems by dao.all().collectAsState(initial = emptyList())
    val all: List<Item> = allItems.filter { it.deletedAt != null }
    var tab by remember { mutableIntStateOf(0) }
    var cat by remember { mutableStateOf(Cat.ALL) }
    var busy by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Item?>(null) }
    val free = remember(all.size, busy, scanning) { Engine.freeBytes(ctx) }

    val trashCount = all.count { it.type == "FILE" }
    val deepCount = all.count { it.type == "DEEP" || it.type == "CARVE" }
    val base: List<Item> = all.filter {
        if (tab == 0) it.type == "FILE" else (it.type == "DEEP" || it.type == "CARVE")
    }
    val list: List<Item> = if (cat == Cat.ALL) base else base.filter { catOf(it) == cat }
    val frac = if (all.isEmpty()) 0.04f else 1f

    fun doRestore(item: Item) {
        scope.launch {
            val msg = try {
                Engine.restore(ctx, item)
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Ui.Bg0, Ui.Bg1, Ui.Bg2)))
    ) {
        Box(
            Modifier
                .size(380.dp)
                .offset(140.dp, (-90).dp)
                .background(Brush.radialGradient(listOf(Ui.Violet.copy(alpha = 0.35f), Color.Transparent)))
        )
        Box(
            Modifier
                .size(320.dp)
                .align(Alignment.BottomStart)
                .offset((-120).dp, 100.dp)
                .background(Brush.radialGradient(listOf(Ui.Cyan.copy(alpha = 0.18f), Color.Transparent)))
        )

        LazyColumn(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Vault Recover", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                        Text("Delete hui files wapas lao.", color = Ui.Muted, fontSize = 13.sp)
                    }
                    Row(
                        Modifier
                            .clip(CircleShape)
                            .background(Ui.Glass)
                            .border(1.dp, Ui.GlassBorder, CircleShape)
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (access) Ui.Green else Ui.Amber))
                        Spacer(Modifier.width(7.dp))
                        Text(if (access) "Live ON" else "Access chahiye", fontSize = 12.sp, color = Color.White)
                    }
                }
            }

            if (free < Engine.RESERVE) {
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text("⚠️  Phone ki storage kam hai", fontWeight = FontWeight.Bold, color = Ui.Amber, fontSize = 14.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Sirf ${fmtSize(free)} khali hai. Nayi files vault me copy nahi ho paengi. Kuch jagah khali karo.",
                                fontSize = 12.sp, color = Ui.Muted, lineHeight = 17.sp
                            )
                        }
                    }
                }
            }

            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Ring(frac, Modifier.size(132.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${all.size}", fontSize = 38.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                                Text("mili", fontSize = 12.sp, color = Ui.Muted)
                            }
                        }
                        Spacer(Modifier.width(20.dp))
                        Column {
                            Text(
                                if (all.isEmpty()) "Abhi kuch nahi mila" else "Recover ke liye ready",
                                fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Tap karo, preview dekho, phir gallery me recover karo.",
                                fontSize = 12.sp, color = Ui.Muted, lineHeight = 17.sp
                            )
                        }
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Trash me", "$trashCount", Ui.Violet, Modifier.weight(1f))
                    StatTile("Deep scan", "$deepCount", Ui.Pink, Modifier.weight(1f))
                    StatTile("Khali jagah", fmtSize(free), Ui.Green, Modifier.weight(1f))
                }
            }

            item {
                val pill = RoundedCornerShape(18.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(pill)
                        .background(Ui.Glass)
                        .border(1.dp, Ui.GlassBorder, pill)
                        .padding(4.dp)
                ) {
                    listOf("Trash ($trashCount)", "Deep scan ($deepCount)").forEachIndexed { i, t ->
                        val on = tab == i
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (on) Ui.Main
                                    else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                                )
                                .clickable { tab = i }
                                .padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                t, fontSize = 13.sp,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                                color = if (on) Color.White else Ui.Muted
                            )
                        }
                    }
                }
            }

            item {
                if (!access) {
                    GradientButton("Storage access allow karo", Modifier.fillMaxWidth(), onClick = onAllow)
                } else if (tab == 0) {
                    GradientButton(
                        if (busy) "Dhundh raha hoon..." else "🔍  Trash me dhundo",
                        Modifier.fillMaxWidth(), enabled = !busy
                    ) {
                        scope.launch {
                            busy = true
                            val n = try {
                                Engine.sync(ctx, true)
                            } catch (e: Exception) {
                                0
                            }
                            busy = false
                            Toast.makeText(
                                ctx,
                                if (n > 0) "$n nayi deleted file mili" else "Trash me koi nayi file nahi mili",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        GradientButton(
                            "🧬  Deep scan (cache, thumbnails, bachi files)",
                            Modifier.fillMaxWidth(), enabled = !scanning
                        ) {
                            scope.launch {
                                scanning = true
                                progress = "Shuru ho raha hai..."
                                val n = try {
                                    Engine.deepScan(ctx) { progress = it }
                                } catch (e: Exception) {
                                    0
                                }
                                scanning = false
                                Toast.makeText(ctx, "Deep scan: $n file mili", Toast.LENGTH_LONG).show()
                            }
                        }
                        GlassButton(
                            "🛠  Root raw scan (sirf rooted phone, advanced)",
                            Modifier.fillMaxWidth()
                        ) {
                            if (!scanning) {
                                scope.launch {
                                    scanning = true
                                    progress = "Chip scan shuru..."
                                    val msg = try {
                                        Engine.rootScan(ctx) { progress = it }
                                    } catch (e: Exception) {
                                        "Error: ${e.message}"
                                    }
                                    scanning = false
                                    progress = msg
                                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        if (progress.isNotBlank()) {
                            Text(progress, fontSize = 12.sp, color = Ui.Muted)
                        }
                        if (scanning) {
                            GlassButton("⏹  Rok do", Modifier.fillMaxWidth()) { Engine.stop = true }
                        }
                    }
                }
            }

            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Cat.entries) { c ->
                        val on = cat == c
                        val s = RoundedCornerShape(50)
                        Row(
                            Modifier
                                .clip(s)
                                .background(if (on) c.color.copy(alpha = 0.25f) else Ui.Glass)
                                .border(1.dp, if (on) c.color else Ui.GlassBorder, s)
                                .clickable { cat = c }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(c.emoji, fontSize = 13.sp)
                            Spacer(Modifier.width(6.dp))
                            Text(c.label, fontSize = 13.sp, color = Color.White)
                        }
                    }
                }
            }

            if (list.isEmpty()) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("🛡️", fontSize = 48.sp)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (tab == 0) "Trash me abhi koi deleted file nahi mili"
                            else "Deep scan chalao, bachi hui files yahan dikhengi",
                            color = Ui.Muted, fontSize = 14.sp
                        )
                    }
                }
            } else {
                items(items = list, key = { item: Item -> item.id }) { item: Item ->
                    ItemCard(
                        item = item,
                        onOpen = { preview = item },
                        onRestore = { doRestore(item) }
                    )
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }

    preview?.let { p ->
        PreviewDialog(
            item = p,
            onClose = { preview = null },
            onRestore = { doRestore(p); preview = null },
            onRemove = {
                scope.launch { Engine.removeFromVault(ctx, p) }
                preview = null
            }
        )
    }
}

@Composable
fun StatTile(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    GlassCard(modifier) {
        Column(Modifier.padding(14.dp)) {
            Box(Modifier.size(width = 22.dp, height = 4.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.height(10.dp))
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
            Text(label, fontSize = 12.sp, color = Ui.Muted)
        }
    }
}

@Composable
fun ItemCard(item: Item, onOpen: () -> Unit, onRestore: () -> Unit) {
    val c = catOf(item)
    val (badge, badgeColor) = when (item.type) {
        "DEEP" -> "Cache" to Ui.Amber
        "CARVE" -> "Raw" to Ui.Cyan
        else -> "Deleted" to Ui.Pink
    }
    GlassCard(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(Brush.linearGradient(listOf(c.color.copy(alpha = 0.35f), c.color.copy(alpha = 0.10f)))),
                    contentAlignment = Alignment.Center
                ) { Text(c.emoji, fontSize = 22.sp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                        color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        fmtSize(item.size) + "  •  " +
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                .format(Date(item.deletedAt ?: item.backedUpAt)),
                        fontSize = 12.sp, color = Ui.Muted, maxLines = 1
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(badgeColor.copy(alpha = 0.18f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(badge, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = badgeColor)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassButton("👁  Dekho", Modifier.weight(1f), onClick = onOpen)
                GradientButton("↩  Restore", Modifier.weight(1f), onClick = onRestore)
            }
        }
    }
}
