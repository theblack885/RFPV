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

private fun fmtSize(b: Long): String = when {
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
    val all by dao.all().collectAsState(initial = emptyList())
    var tab by remember { mutableIntStateOf(0) }
    var cat by remember { mutableStateOf(Cat.ALL) }
    var busy by remember { mutableStateOf(false) }

    val deleted = all.count { it.deletedAt != null }
    val base = if (tab == 0) all.filter { it.deletedAt != null } else all
    val list = if (cat == Cat.ALL) base else base.filter { catOf(it) == cat }
    val frac = if (all.isEmpty()) 0f else (deleted.toFloat() / all.size).coerceAtLeast(0.04f)

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Ui.Bg0, Ui.Bg1, Ui.Bg2)))
    ) {
        Box(
            Modifier.size(380.dp).offset(140.dp, (-90).dp)
                .background(Brush.radialGradient(listOf(Ui.Violet.copy(alpha = 0.35f), Color.Transparent)))
        )
        Box(
            Modifier.size(320.dp).align(Alignment.BottomStart).offset((-120).dp, 100.dp)
                .background(Brush.radialGradient(listOf(Ui.Cyan.copy(alpha = 0.18f), Color.Transparent)))
        )

        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ---------- Header ----------
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Vault Recover", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                        Text("Delete hua? Ab koi tension nahi.", color = Ui.Muted, fontSize = 13.sp)
                    }
                    Row(
                        Modifier.clip(CircleShape).background(Ui.Glass)
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

            // ---------- Hero ring ----------
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Ring(frac, Modifier.size(132.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("$deleted", fontSize = 38.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                                Text("deleted", fontSize = 12.sp, color = Ui.Muted)
                            }
                        }
                        Spacer(Modifier.width(20.dp))
                        Column {
                            Text(
                                if (deleted == 0) "Sab kuch safe hai" else "Recover ke liye ready",
                                fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Delete ya trash khali hone ke baad bhi files vault me original quality me rehti hain.",
                                fontSize = 12.sp, color = Ui.Muted, lineHeight = 17.sp
                            )
                        }
                    }
                }
            }

            // ---------- Stats ----------
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Backup", "${all.size}", Ui.Violet, Modifier.weight(1f))
                    StatTile("Storage", fmtSize(all.sumOf { it.size }), Ui.Cyan, Modifier.weight(1f))
                    StatTile("Apps", "${all.count { it.type == "APP" }}", Ui.Green, Modifier.weight(1f))
                }
            }

            // ---------- Action ----------
            item {
                if (!access) {
                    GradientButton("Storage access allow karo", Modifier.fillMaxWidth(), onClick = onAllow)
                } else {
                    GradientButton(
                        if (busy) "Backup ho raha hai..." else "⚡  Abhi scan & backup karo",
                        Modifier.fillMaxWidth(), enabled = !busy
                    ) {
                        scope.launch {
                            busy = true
                            val n = Engine.sync(ctx)
                            busy = false
                            Toast.makeText(ctx, "$n naye item backup hue", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            // ---------- Pill tabs ----------
            item {
                val pill = RoundedCornerShape(18.dp)
                Row(
                    Modifier.fillMaxWidth().clip(pill).background(Ui.Glass)
                        .border(1.dp, Ui.GlassBorder, pill).padding(4.dp)
                ) {
                    listOf("Deleted ($deleted)", "Sab backup (${all.size})").forEachIndexed { i, t ->
                        val on = tab == i
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                                .background(if (on) Ui.Main else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)))
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

            // ---------- Category chips ----------
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Cat.values().toList()) { c ->
                        val on = cat == c
                        val s = RoundedCornerShape(50)
                        Row(
                            Modifier.clip(s)
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

            // ---------- List ----------
            if (list.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(if (tab == 0) "🛡️" else "📭", fontSize = 48.sp)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (tab == 0) "Abhi koi deleted item nahi" else "Abhi kuch backup nahi hua",
                            color = Ui.Muted, fontSize = 14.sp
                        )
                    }
                }
            } else {
                items(list, key = { it.id }) { item ->
                    ItemCard(item,
                        onRestore = {
                            scope.launch {
                                val msg = try { Engine.restore(ctx, item) } catch (e: Exception) { "Error: ${e.message}" }
                                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                            }
                        },
                        onRemove = { scope.launch { Engine.removeFromVault(ctx, item) } }
                    )
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
fun StatTile(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    GlassCard(modifier) {
        Column(Modifier.padding(14.dp)) {
            Box(Modifier.size(width = 22.dp, height = 4.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.height(10.dp))
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
            Text(label, fontSize = 12.sp, color = Ui.Muted)
        }
    }
}

@Composable
fun ItemCard(item: Item, onRestore: () -> Unit, onRemove: () -> Unit) {
    val c = catOf(item)
    val isDeleted = item.deletedAt != null
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(15.dp))
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
                        fmtSize(item.size) + "  •  " + (
                            if (isDeleted)
                                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                    .format(Date(item.deletedAt!!))
                            else "Vault me safe"
                        ),
                        fontSize = 12.sp, color = Ui.Muted, maxLines = 1
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.clip(CircleShape)
                        .background((if (isDeleted) Ui.Pink else Ui.Green).copy(alpha = 0.18f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        if (isDeleted) "Deleted" else "Safe",
                        fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        color = if (isDeleted) Ui.Pink else Ui.Green
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                        .background(Ui.Glass)
                        .border(1.dp, Ui.GlassBorder, RoundedCornerShape(14.dp))
                        .clickable(onClick = onRemove)
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Vault se hatao", fontSize = 13.sp, color = Ui.Muted) }
                GradientButton("↩  Restore", Modifier.weight(1f), onClick = onRestore)
            }
        }
    }
}
