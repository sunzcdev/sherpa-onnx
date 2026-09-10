package com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

// 强制浅色宣纸主题：背诵场景是"读物"，禁 dark 跟随、禁动态取色（Material You）
private val XuanPaperColorScheme = lightColorScheme(
    primary = InkBlack,
    onPrimary = CardWhite,
    secondary = BronzeGold,
    onSecondary = InkBlack,
    tertiary = InkGreen,
    onTertiary = CardWhite,
    error = InkRed,
    onError = CardWhite,
    background = XuanPaper,
    onBackground = InkBlack,
    surface = CardWhite,
    onSurface = InkBlack,
    surfaceVariant = XuanPaper,
    onSurfaceVariant = InkBlackSoft,
    outline = DividerWarm,
    outlineVariant = DividerWarm,
)

val BeisongShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),   // 卡片统一 12dp 圆角 (NFR-006)
    large = RoundedCornerShape(16.dp),
)

@Composable
fun SimulateStreamingAsrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = XuanPaperColorScheme,
        typography = BeisongTypography,
        shapes = BeisongShapes,
        content = content,
    )
}
