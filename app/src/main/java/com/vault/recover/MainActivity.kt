package com.vault.recover

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private var access by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF8B6CFF),
                    background = Color(0xFF0A0C14),
                    surface = Color(0xFF151827),
                    onBackground = Color(0xFFEDEFF7),
                    onSurface = Color(0xFFEDEFF7)
                )
            ) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Screen(access) {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        access = Environment.isExternalStorageManager()
        if (access) Scheduler.arm(this)
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
    var busy by remember { mutableStateOf(false) }
    val list = if (tab == 0) all.filter { it.deletedAt != null } else all

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Vault Recover", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            "${all.count { it.deletedAt != null }} deleted  •  ${all.size} backed up  •  ${fmtSize(all.sumOf { it.size })}",
            color = Color(0xFF9AA0B8), fontSize = 13.sp
        )
        Spacer(Modifier.height(12.dp))

        if (!access) {
            Button(onClick = onAllow, modifier = Modifier.fillMaxWidth()) {
                Text("All files access allow karo")
            }
        } else {
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        val n = Engine.sync(ctx)
                        busy = false
                        Toast.makeText(ctx, "$n naye item backup hue", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "Backup ho raha hai..." else "Abhi scan & backup karo") }
        }

        Spacer(Modifier.height(8.dp))
        TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Deleted") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Sab backup") })
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list, key = { it.id }) { item ->
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            (if (item.type == "APP") "📦 " else "📄 ") + item.name,
                            fontWeight = FontWeight.SemiBold, maxLines = 1
                        )
                        Text(
                            fmtSize(item.size) + "  •  " +
                                if (item.deletedAt != null)
                                    "Deleted: " + DateFormat.getDateTimeInstance().format(Date(item.deletedAt))
                                else "Safe in vault",
                            color = Color(0xFF9AA0B8), fontSize = 12.sp
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = {
                                scope.launch { Engine.removeFromVault(ctx, item) }
                            }) { Text("Vault se hatao") }
                            Button(onClick = {
                                scope.launch {
                                    val msg = try { Engine.restore(ctx, item) } catch (e: Exception) { "Error: ${e.message}" }
                                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                                }
                            }) { Text("Restore") }
                        }
                    }
                }
            }
        }
    }
}
