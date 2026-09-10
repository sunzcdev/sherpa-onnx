package com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.R

// 衬线 = 宣纸读物感（正文/标题/篇目）；无衬线留给数据（字体资源仅衬线，
// 数据字号用同一 family 的常规字重，MVP 不引入第二套 20MB 字体）
val SerifSC = FontFamily(
    Font(R.font.noto_serif_sc_regular, FontWeight.Normal),
    Font(R.font.noto_serif_sc_bold, FontWeight.Bold),
)

val BeisongTypography = Typography(
    // 篇目标题（FR-001 篇目卡）
    headlineMedium = TextStyle(
        fontFamily = SerifSC, fontWeight = FontWeight.Bold,
        fontSize = 24.sp, lineHeight = 34.sp,
    ),
    // 正文背诵文本 / 篇目内容
    bodyLarge = TextStyle(
        fontFamily = SerifSC, fontWeight = FontWeight.Normal,
        fontSize = 18.sp, lineHeight = 32.sp, letterSpacing = 1.sp,
    ),
    // 辅助说明
    bodyMedium = TextStyle(
        fontFamily = SerifSC, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 24.sp,
    ),
    // 按钮/标签
    titleMedium = TextStyle(
        fontFamily = SerifSC, fontWeight = FontWeight.Bold,
        fontSize = 16.sp, lineHeight = 22.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = SerifSC, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 18.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = SerifSC, fontWeight = FontWeight.Normal,
        fontSize = 11.sp, lineHeight = 15.sp,
    ),
)
