package com.vault.recover

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

class MainActivity : FragmentActivity() {
    private var access by mutableStateOf(false)
    private var locked by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        locked = AppLock.hasPin(this)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            VaultTheme {
                if (locked) {
                    LockScreen(
                        onVerify = { AppLock.check(this@MainActivity, it) },
                        showBio = AppLock.bioEnabled(this@MainActivity) &&
                            AppLock.canBiometric(this@MainActivity),
                        onBio = { AppLock.showBiometric(this@MainActivity) { locked = false } },
                        onUnlocked = { locked = false }
                    )
                } else {
                    Screen(access) { askAccess() }
                }
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
            try {
                ContextCompat.startForegroundService(this, Intent(this, WatchService::class.java))
            } catch (e: Exception) { }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onStop() {
        super.onStop()
        if (AppLock.hasPin(this)) locked = true
    }
}

fun fmtSize(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / 1073741824.0)
    b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
    b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

enum class Src(val label: String, val types: Set<String>) {
    TRASH("Trash", setOf("FILE")),
    DEEP("Deep", setOf("DEEP", "CARVE")),
    MAIL("Email", setOf("MAIL")),
    TAKEOUT("Takeout", setOf("ZIP")),
    WA("WhatsApp", setOf("WA"))
}

enum class SortBy(val label: String) {
    NEW("Naya pehle"), OLD("Purana pehle"), BIG("Bada size"), NAME("Naam")
}

@Composable
fun Screen(access: Boolean, onAllow: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDb.get(ctx).dao() }
    val allItems by dao.all().collectAsState(initial = emptyList())
    val all = remember(allItems) { allItems.filter { it.deletedAt != null } }

    var src by remember { mutableStateOf(Src.TRASH) }
    var cat by remember { mutableStateOf(Cat.ALL) }
    var sort by remember { mutableStateOf(SortBy.NEW) }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Item?>(null) }
    var creds by remember { mutableStateOf(MailScanner.load(ctx)) }
    var emailInput by remember { mutableStateOf("") }
    var passInput by remember { mutableStateOf("") }
    var showTools by remember { mutableStateOf(false) }
    var confirmOld by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    val selected = remember { mutableStateListOf<String>() }
    val selecting = selected.isNotEmpty()

    val free = remember(all.size, busy, scanning, tick) { Engine.freeBytes(ctx) }
    val pinSet = remember(tick) { AppLock.hasPin(ctx) }
    val bioOn = remember(tick) { AppLock.bioEnabled(ctx) }
    val vaultOnSd = remember(tick) { Engine.useSd(ctx) }
    val sdAvailable = remember(tick) { Engine.sdAppDir(ctx) != null }
    val counts = remember(all) { Src.values().associateWith { s -> all.count { it.type in s.types } } }

    val list = remember(all, src, cat, sort, query) {
        val filtered = all.filter {
            it.type in src.types &&
                (cat == Cat.ALL || catOf(it) == cat) &&
                (query.isBlank() || it.name.contains(query.trim(), true))
        }
        when (sort) {
            SortBy.NEW -> filtered.sortedByDescending { it.deletedAt ?: 0L }
            SortBy.OLD -> filtered.sortedBy { it.deletedAt ?: 0L }
            SortBy.BIG -> filtered.sortedByDescending { it.size }
            SortBy.NAME -> filtered.sortedBy { it.name.lowercase() }
        }
    }

    fun toggle(id: String) {
        if (id in selected) selected.remove(id) else selected.add(id)
    }

    fun doRestore(item: Item) {
        scope.launch {
            val msg = Engine.restoreAny(ctx, item)
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }

    fun restoreSelected() {
        val items = all.filter { it.id in selected }
        selected.clear()
        scope.launch {
            busy = true
            var ok = 0
            for ((i, x) in items.withIndex()) {
                progress = "Restore ho raha hai: ${i + 1}/${items.size}"
                val r = Engine.restoreAny(ctx, x)
                if (r.startsWith("Wapas") || r.startsWith("Install")) ok++
            }
            busy = false
            progress = "Restore poora: $ok/${items.size} wapas aayi"
            Toast.makeText(ctx, "$ok/${items.size} files gallery me wapas aayi", Toast.LENGTH_LONG).show()
        }
    }

    fun openItem(item: Item) {
        val needsLocal = (item.type == "MAIL" || item.type == "ZIP") &&
            (item.backupPath.isBlank() || !File(item.backupPath).exists())
        if (!needsLocal) {
            preview = item
            return
        }
        scope.launch {
            Toast.makeText(ctx, "Nikal raha hoon...", Toast.LENGTH_SHORT).show()
            val real = try { Engine.ensureLocal(ctx, item) } catch (e: Exception) { null }
            if (real != null) preview = real
            else Toast.makeText(ctx, "Nahi nikal paya (internet, login ya jagah check karo)", Toast.LENGTH_LONG).show()
        }
    }

    fun runScan(start: String, block: suspend () -> String) {
        if (scanning) return
        scope.launch {
            scanning = true
            progress = start
            val msg = try { block() } catch (e: Exception) { "Error: ${e.message}" }
            scanning = false
            progress = msg
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }

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
            contentPadding = PaddingValues(
                start = 18.dp, end = 18.dp, top = 14.dp, bottom = if (selecting) 150.dp else 20.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ---------- Header ----------
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Vault Recover", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                        Text("Delete hui files wapas lao.", color = Ui.Muted, fontSize = 13.sp)
                    }
                    Row(
                        Modifier.clip(CircleShape).background(Ui.Glass)
                            .border(1.dp, Ui.GlassBorder, CircleShape)
                            .clickable { showTools = !showTools }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (access) Ui.Green else Ui.Amber))
                        Spacer(Modifier.width(7.dp))
                        Text(if (showTools) "✕ Band" else "⚙️ Tools", fontSize = 12.sp, color = Color.White)
                    }
                }
            }

            // ---------- Tools & Settings ----------
            if (showTools) {
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("⚙️  Tools aur Settings", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)

                            // ---- PIN lock ----
                            Text("🔒  PIN lock", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            if (!pinSet) {
                                OutlinedTextField(
                                    value = pinInput,
                                    onValueChange = { v -> pinInput = v.filter { it.isDigit() }.take(4) },
                                    label = { Text("4 digit PIN") },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                GradientButton("PIN set karo", Modifier.fillMaxWidth(), enabled = pinInput.length == 4) {
                                    AppLock.setPin(ctx, pinInput)
                                    pinInput = ""
                                    tick++
                                    Toast.makeText(ctx, "PIN set ho gaya", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Text("PIN ON hai. App background me jaane par lock ho jati hai.", fontSize = 12.sp, color = Ui.Muted)
                                if (AppLock.canBiometric(ctx)) {
                                    GlassButton(
                                        if (bioOn) "👆  Fingerprint: ON (band karo)" else "👆  Fingerprint ON karo",
                                        Modifier.fillMaxWidth()
                                    ) {
                                        AppLock.setBio(ctx, !bioOn)
                                        tick++
                                    }
                                }
                                GlassButton("PIN hatao", Modifier.fillMaxWidth()) {
                                    AppLock.clearPin(ctx)
                                    tick++
                                }
                            }

                            // ---- Safai ----
                            Text("🧹  Safai", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            GlassButton("Duplicate files hatao", Modifier.fillMaxWidth()) {
                                if (!busy) scope.launch {
                                    busy = true
                                    val n = try { Engine.dedupe(ctx) } catch (e: Exception) { 0 }
                                    busy = false
                                    tick++
                                    Toast.makeText(ctx, "$n duplicate hataye", Toast.LENGTH_LONG).show()
                                }
                            }
                            GlassButton("Recover ho chuki items ki copy hatao", Modifier.fillMaxWidth()) {
                                if (!busy) scope.launch {
                                    busy = true
                                    val n = try { Engine.cleanupRecovered(ctx) } catch (e: Exception) { 0 }
                                    busy = false
                                    tick++
                                    Toast.makeText(ctx, "$n copy hatayi, jagah khali hui", Toast.LENGTH_LONG).show()
                                }
                            }
                            GlassButton("30 din se purani copies hatao", Modifier.fillMaxWidth()) {
                                confirmOld = true
                            }

                            // ---- Vault ki jagah ----
                            Text(
                                "💾  Vault abhi: " + if (vaultOnSd) "SD card me" else "Phone me",
                                color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                            )
                            if (sdAvailable || vaultOnSd) {
                                GlassButton(
                                    if (vaultOnSd) "Vault ko phone me wapas lao" else "Vault ko SD card me move karo",
                                    Modifier.fillMaxWidth()
                                ) {
                                    runScan("Vault move ho raha hai...") {
                                        val r = Engine.moveVault(ctx, !vaultOnSd) { progress = it }
                                        tick++
                                        r
                                    }
                                }
                            } else {
                                Text("SD card nahi mila.", fontSize = 12.sp, color = Ui.Muted)
                            }

                            Text(
                                "🔁  Phone restart ke baad app apne aap chalu hoti hai. Xiaomi/Redmi me Settings → Apps → Vault Recover → Autostart ON karo.",
                                fontSize = 12.sp, color = Ui.Muted, lineHeight = 17.sp
                            )
                        }
                    }
                }
            }

            // ---------- Low storage warning ----------
            if (free < Engine.RESERVE) {
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text("⚠️  Storage kam hai", fontWeight = FontWeight.Bold, color = Ui.Amber, fontSize = 14.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Sirf ${fmtSize(free)} khali hai. Nayi files vault me copy nahi ho paengi. Tools me safai karo ya vault SD card me bhejo.",
                                fontSize = 12.sp, color = Ui.Muted, lineHeight = 17.sp
                            )
                        }
                    }
                }
            }

            // ---------- Hero ring ----------
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Ring(if (all.isEmpty()) 0.04f else 1f, Modifier.size(132.dp)) {
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
                                "Tap karo: preview. Lamba dabao: kai files chuno aur ek saath restore karo.",
                                fontSize = 12.sp, color = Ui.Muted, lineHeight = 17.sp
                            )
                        }
                    }
                }
            }

            // ---------- Stats ----------
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Trash + Deep", "${(counts[Src.TRASH] ?: 0) + (counts[Src.DEEP] ?: 0)}", Ui.Violet, Modifier.weight(1f))
                    StatTile("Email + Takeout", "${(counts[Src.MAIL] ?: 0) + (counts[Src.TAKEOUT] ?: 0)}", Ui.Pink, Modifier.weight(1f))
                    StatTile("Khali jagah", fmtSize(free), Ui.Green, Modifier.weight(1f))
                }
            }

            // ---------- Source pills ----------
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Src.values().toList()) { s ->
                        val on = src == s
                        val shape = RoundedCornerShape(50)
                        Box(
                            Modifier.clip(shape)
                                .background(if (on) Ui.Main else Brush.horizontalGradient(listOf(Ui.Glass, Ui.Glass)))
                                .border(1.dp, if (on) Color.Transparent else Ui.GlassBorder, shape)
                                .clickable {
                                    src = s
                                    selected.clear()
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Text(
                                "${s.label} (${counts[s] ?: 0})", fontSize = 13.sp,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                                color = if (on) Color.White else Ui.Muted
                            )
                        }
                    }
                }
            }

            // ---------- Actions per source ----------
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when {
                        src == Src.MAIL -> {
                            val cr = creds
                            if (cr == null) {
                                GlassCard(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("📧  Apna email scan karo", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                                        Text(
                                            "Gmail ke liye normal password nahi, App password chahiye: myaccount.google.com/apppasswords. Password sirf is phone me save hota hai.",
                                            fontSize = 12.sp, color = Ui.Muted, lineHeight = 17.sp
                                        )
                                        OutlinedTextField(
                                            value = emailInput, onValueChange = { emailInput = it },
                                            label = { Text("Email") }, singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        OutlinedTextField(
                                            value = passInput, onValueChange = { passInput = it },
                                            label = { Text("App password") }, singleLine = true,
                                            visualTransformation = PasswordVisualTransformation(),
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                                GradientButton(
                                    "📧  Login aur scan shuru karo", Modifier.fillMaxWidth(),
                                    enabled = !scanning && emailInput.contains("@") && passInput.length >= 6
                                ) {
                                    val nc = MailScanner.Creds(emailInput.trim(), passInput.replace(" ", ""))
                                    MailScanner.save(ctx, nc)
                                    creds = nc
                                    passInput = ""
                                    runScan("Email se connect ho raha hai...") {
                                        MailScanner.scan(ctx, nc) { progress = it }
                                    }
                                }
                            } else {
                                Text("Account: ${cr.email}", fontSize = 13.sp, color = Ui.Muted)
                                GradientButton(
                                    if (scanning) "Scan chal raha hai..." else "📧  Email scan karo",
                                    Modifier.fillMaxWidth(), enabled = !scanning
                                ) {
                                    runScan("Email se connect ho raha hai...") {
                                        MailScanner.scan(ctx, cr) { progress = it }
                                    }
                                }
                                GlassButton("Logout (password hatao)", Modifier.fillMaxWidth()) {
                                    if (!scanning) {
                                        MailScanner.clear(ctx)
                                        creds = null
                                        progress = ""
                                    }
                                }
                            }
                        }

                        !access -> {
                            GradientButton("Storage access allow karo", Modifier.fillMaxWidth(), onClick = onAllow)
                        }

                        src == Src.TRASH -> {
                            GradientButton(
                                if (busy) "Dhundh raha hoon..." else "🔍  Trash me dhundo",
                                Modifier.fillMaxWidth(), enabled = !busy
                            ) {
                                scope.launch {
                                    busy = true
                                    val n = try { Engine.sync(ctx, true) } catch (e: Exception) { 0 }
                                    busy = false
                                    Toast.makeText(
                                        ctx,
                                        if (n > 0) "$n nayi deleted file mili" else "Trash me koi nayi file nahi mili",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }

                        src == Src.DEEP -> {
                            GradientButton(
                                "🧬  Deep scan (phone + SD card)", Modifier.fillMaxWidth(), enabled = !scanning
                            ) {
                                runScan("Shuru ho raha hai...") {
                                    val n = Engine.deepScan(ctx) { progress = it }
                                    "Deep scan: $n file mili"
                                }
                            }
                            GlassButton("🛠  Root raw scan (sirf rooted phone)", Modifier.fillMaxWidth()) {
                                runScan("Chip scan shuru...") { Engine.rootScan(ctx) { progress = it } }
                            }
                        }

                        src == Src.TAKEOUT -> {
                            Text(
                                "takeout.google.com se Photos/Drive ki zip download karke phone me rakho (Downloads folder). App zip ke andar ki purani photos, videos, music dhundhegi. Extract tabhi hoga jab tum dekho ya restore karo.",
                                fontSize = 12.sp, color = Ui.Muted, lineHeight = 17.sp
                            )
                            GradientButton("📦  Takeout zip dhundo", Modifier.fillMaxWidth(), enabled = !scanning) {
                                runScan("Takeout zip dhundh raha hoon...") {
                                    TakeoutScanner.scan(ctx) { progress = it }
                                }
                            }
                        }

                        src == Src.WA -> {
                            Text(
                                "WhatsApp ke Media folder (photos, videos, voice notes, status) ki files dikhata hai. Jo chat se hat gayi par phone me bachi hain, wo bhi yahan milengi.",
                                fontSize = 12.sp, color = Ui.Muted, lineHeight = 17.sp
                            )
                            GradientButton("💬  WhatsApp media dhundo", Modifier.fillMaxWidth(), enabled = !scanning) {
                                runScan("WhatsApp scan shuru...") {
                                    WhatsAppScanner.scan(ctx) { progress = it }
                                }
                            }
                        }
                    }
                    if (progress.isNotBlank()) Text(progress, fontSize = 12.sp, color = Ui.Muted)
                    if (scanning) GlassButton("⏹  Rok do", Modifier.fillMaxWidth()) { Engine.stop = true }
                }
            }

            // ---------- Search ----------
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("🔎  Naam se dhundo") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ---------- Sort ----------
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(SortBy.values().toList()) { s ->
                        val on = sort == s
                        val shape = RoundedCornerShape(50)
                        Box(
                            Modifier.clip(shape)
                                .background(if (on) Ui.Violet.copy(alpha = 0.25f) else Ui.Glass)
                                .border(1.dp, if (on) Ui.Violet else Ui.GlassBorder, shape)
                                .clickable { sort = s }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) { Text(s.label, fontSize = 12.sp, color = Color.White) }
                    }
                }
            }

            // ---------- Category chips ----------
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Cat.values().toList()) { c ->
                        val on = cat == c
                        val shape = RoundedCornerShape(50)
                        Row(
                            Modifier.clip(shape)
                                .background(if (on) c.color.copy(alpha = 0.25f) else Ui.Glass)
                                .border(1.dp, if (on) c.color else Ui.GlassBorder, shape)
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
                        Text("🛡️", fontSize = 48.sp)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            when (src) {
                                Src.TRASH -> "Trash me abhi koi deleted file nahi mili"
                                Src.DEEP -> "Deep scan chalao, bachi hui files yahan dikhengi"
                                Src.MAIL -> "Email scan chalao, purane attachments yahan dikhenge"
                                Src.TAKEOUT -> "Takeout zip scan karo, purani photos yahan dikhengi"
                                Src.WA -> "WhatsApp scan karo, media yahan dikhega"
                            },
                            color = Ui.Muted, fontSize = 14.sp
                        )
                    }
                }
            } else {
                items(list, key = { it.id }) { item ->
                    ItemCard(
                        item = item,
                        selected = item.id in selected,
                        selecting = selecting,
                        onTap = { if (selecting) toggle(item.id) else openItem(item) },
                        onLong = { toggle(item.id) },
                        onOpen = { openItem(item) },
                        onRestore = { doRestore(item) }
                    )
                }
            }
        }

        // ---------- Selection bar ----------
        if (selecting) {
            val shape = RoundedCornerShape(24.dp)
            Column(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                    .padding(16.dp).fillMaxWidth()
                    .clip(shape).background(Ui.Bg1).border(1.dp, Ui.GlassBorder, shape)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("${selected.size} chune hue", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton("Sab chuno", Modifier.weight(1f)) {
                        selected.clear()
                        selected.addAll(list.map { it.id })
                    }
                    GlassButton("✕", Modifier.weight(0.5f)) { selected.clear() }
                }
                GradientButton("↩  ${selected.size} files gallery me restore karo", Modifier.fillMaxWidth(), enabled = !busy) {
                    restoreSelected()
                }
            }
        }
    }

    // ---------- Confirm: purani copies hatao ----------
    if (confirmOld) {
        AlertDialog(
            onDismissRequest = { confirmOld = false },
            title = { Text("Purani copies hatao?") },
            text = { Text("30 din se purani mili hui files ki vault copies hat jayengi. Ye wapas nahi aayengi.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmOld = false
                    scope.launch {
                        val n = try { Engine.cleanupOld(ctx, 30) } catch (e: Exception) { 0 }
                        tick++
                        Toast.makeText(ctx, "$n purani copy hatayi", Toast.LENGTH_LONG).show()
                    }
                }) { Text("Haan, hatao") }
            },
            dismissButton = { TextButton(onClick = { confirmOld = false }) { Text("Nahi") } }
        )
    }

    // ---------- Preview popup ----------
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
            Text(label, fontSize = 11.sp, color = Ui.Muted, maxLines = 1)
        }
    }
}

@Composable
fun ThumbBox(item: Item, cat: Cat) {
    val bmp by produceState<Bitmap?>(null, item.backupPath) {
        value = withContext(Dispatchers.IO) { Thumbs.load(item) }
    }
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(15.dp))
            .background(Brush.linearGradient(listOf(cat.color.copy(alpha = 0.35f), cat.color.copy(alpha = 0.10f)))),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(), contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
            )
        } else {
            Text(cat.emoji, fontSize = 22.sp)
        }
    }
}

@Composable
fun ItemCard(
    item: Item,
    selected: Boolean,
    selecting: Boolean,
    onTap: () -> Unit,
    onLong: () -> Unit,
    onOpen: () -> Unit,
    onRestore: () -> Unit
) {
    val c = catOf(item)
    val (badge, badgeColor) = when (item.type) {
        "DEEP" -> "Cache" to Ui.Amber
        "CARVE" -> "Raw" to Ui.Cyan
        "MAIL" -> "Mail" to Ui.Blue
        "ZIP" -> "Takeout" to Ui.Blue
        "WA" -> "WhatsApp" to Ui.Green
        else -> "Deleted" to Ui.Pink
    }
    GlassCard(
        Modifier.fillMaxWidth()
            .then(if (selected) Modifier.border(2.dp, Ui.Violet, RoundedCornerShape(24.dp)) else Modifier)
            .pointerInput(item.id, selecting) {
                detectTapGestures(onLongPress = { onLong() }, onTap = { onTap() })
            }
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ThumbBox(item, c)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        (if (selected) "✓ " else "") + item.name,
                        fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
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
                    Modifier.clip(CircleShape)
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
