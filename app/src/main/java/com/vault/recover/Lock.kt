package com.vault.recover

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.MessageDigest

object AppLock {
    private fun prefs(c: Context) = c.getSharedPreferences("lock", Context.MODE_PRIVATE)

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("vault:$pin".toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun hasPin(c: Context): Boolean = prefs(c).getString("pin", null) != null

    fun setPin(c: Context, pin: String) {
        prefs(c).edit().putString("pin", hash(pin)).apply()
    }

    fun clearPin(c: Context) {
        prefs(c).edit().remove("pin").putBoolean("bio", false).apply()
    }

    fun check(c: Context, pin: String): Boolean = prefs(c).getString("pin", null) == hash(pin)

    fun bioEnabled(c: Context): Boolean = prefs(c).getBoolean("bio", false)

    fun setBio(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("bio", on).apply()
    }

    fun canBiometric(c: Context): Boolean =
        try {
            BiometricManager.from(c)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
                BiometricManager.BIOMETRIC_SUCCESS
        } catch (e: Exception) {
            false
        }

    fun showBiometric(activity: FragmentActivity, onOk: () -> Unit) {
        try {
            val executor = ContextCompat.getMainExecutor(activity)
            val prompt = BiometricPrompt(
                activity, executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        onOk()
                    }
                }
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Vault Recover kholo")
                .setSubtitle("Fingerprint lagao")
                .setNegativeButtonText("PIN use karo")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .build()
            prompt.authenticate(info)
        } catch (e: Exception) { }
    }
}

@Composable
private fun Key(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(72.dp).clip(CircleShape).background(Ui.Glass)
            .border(1.dp, Ui.GlassBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(label, fontSize = 24.sp, color = Color.White) }
}

@Composable
fun LockScreen(
    onVerify: (String) -> Boolean,
    showBio: Boolean,
    onBio: () -> Unit,
    onUnlocked: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (showBio) onBio() }

    fun press(d: String) {
        if (pin.length >= 4) return
        pin += d
        error = false
        if (pin.length == 4) {
            if (onVerify(pin)) onUnlocked() else {
                error = true
                pin = ""
            }
        }
    }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Ui.Bg0, Ui.Bg1, Ui.Bg2))),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.statusBarsPadding().navigationBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("🔒", fontSize = 48.sp)
            Spacer(Modifier.height(10.dp))
            Text("Vault Recover", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Spacer(Modifier.height(4.dp))
            Text(
                if (error) "Galat PIN, dobara try karo" else "4 digit PIN daalo",
                color = if (error) Ui.Pink else Ui.Muted, fontSize = 14.sp
            )
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for (i in 0 until 4) {
                    Box(
                        Modifier.size(16.dp).clip(CircleShape)
                            .background(if (i < pin.length) Ui.Violet else Ui.Glass)
                            .border(1.dp, Ui.GlassBorder, CircleShape)
                    )
                }
            }
            Spacer(Modifier.height(30.dp))
            val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))
            for (r in rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    for (k in r) Key(k) { press(k) }
                }
                Spacer(Modifier.height(16.dp))
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showBio) Key("👆") { onBio() } else Spacer(Modifier.size(72.dp))
                Key("0") { press("0") }
                Key("⌫") { if (pin.isNotEmpty()) pin = pin.dropLast(1) }
            }
        }
    }
}
