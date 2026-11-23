package com.vault.recover

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Ui {
    val Bg0 = Color(0xFF05060D)
    val Bg1 = Color(0xFF0C0B1E)
    val Bg2 = Color(0xFF150C2C)
    val Violet = Color(0xFF8B6CFF)
    val Cyan = Color(0xFF35E0FF)
    val Pink = Color(0xFFFF5CAA)
    val Green = Color(0xFF3DDC97)
    val Amber = Color(0xFFFFB84D)
    val Blue = Color(0xFF7FA6FF)
    val Muted = Color(0xFF9AA0BC)
    val Glass = Color.White.copy(alpha = 0.06f)
    val GlassBorder = Color.White.copy(alpha = 0.11f)
    val Main = Brush.horizontalGradient(listOf(Violet, Pink))
}

enum class Cat(val label: String, val emoji: String, val color: Color) {
    ALL("Sab", "✨", Ui.Violet),
    PHOTO("Photos", "🖼️", Ui.Pink),
    VIDEO("Videos", "🎬", Ui.Cyan),
    AUDIO("Music", "🎵", Ui.Amber),
    APP("Apps", "📦", Ui.Green),
    DOC("Docs", "📄", Ui.Blue)
}

fun catOf(i: Item): Cat {
    if (i.type == "APP") return Cat.APP
    return when (i.name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "webp", "gif", "heic", "bmp" -> Cat.PHOTO
        "mp4", "mkv", "3gp", "webm", "mov", "avi" -> Cat.VIDEO
        "mp3", "m4a", "wav", "flac", "aac", "ogg", "opus" -> Cat.AUDIO
        else -> Cat.DOC
    }
}

@Composable
fun VaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ui.Violet,
            background = Ui.Bg0,
            surface = Ui.Bg1,
            onBackground = Color(0xFFF1F2FA),
            onSurface = Color(0xFFF1F2FA)
        ),
        content = content
    )
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .clip(shape)
            .background(Ui.Glass)
            .border(1.dp, Ui.GlassBorder, shape),
        content = content
    )
}

@Composable
fun GradientButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (enabled) Ui.Main else Brush.horizontalGradient(listOf(Ui.Glass, Ui.Glass)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
fun Ring(progress: Float, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p by animateFloatAsState(progress, tween(900), label = "ring")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = 14.dp.toPx()
            val topLeft = Offset(sw / 2, sw / 2)
            val arc = Size(size.width - sw, size.height - sw)
            drawArc(
                Color.White.copy(alpha = 0.08f), 0f, 360f, false, topLeft, arc,
                style = Stroke(sw, cap = StrokeCap.Round)
            )
            drawArc(
                Brush.sweepGradient(listOf(Ui.Cyan, Ui.Violet, Ui.Pink, Ui.Cyan)),
                -90f, 360f * p, false, topLeft, arc,
                style = Stroke(sw, cap = StrokeCap.Round)
            )
        }
        content()
    }
}
