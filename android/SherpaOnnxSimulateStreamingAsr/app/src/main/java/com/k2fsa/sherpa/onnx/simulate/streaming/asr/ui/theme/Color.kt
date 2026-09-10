package com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme

import androidx.compose.ui.graphics.Color

// ─── 宣纸读物设计系统 (SRD v1.1 §2) ───────────────────────────────
// Flat Minimal + Editorial: 墨黑 / 宣纸 / 白卡 / 古铜金 / 墨红 / 暖黄 / 墨绿

val InkBlack = Color(0xFF2C2416)      // Primary: 标题、正文、激活态
val InkBlackSoft = Color(0xFF5C5142)  // 次要文字（对比度 ≥4.5:1 on 宣纸）
val XuanPaper = Color(0xFFF5F0E8)     // 全局背景
val CardWhite = Color(0xFFFFFFFF)     // 浮层卡片
val BronzeGold = Color(0xFFC8A45C)    // Accent: CTA、高亮
val BronzeGoldPressed = Color(0xFFA8873F)
val InkRed = Color(0xFFC0392B)        // Error: 错字标注
val WarmYellow = Color(0xFFD4A017)    // Warning: 停顿标记
val InkGreen = Color(0xFF4A7C59)      // Success: 正确/通过
val DividerWarm = Color(0xFFE3DACB)   // 分隔线（宣纸上的淡墨）
val TagGray = Color(0xFF8A7F70)       // 禁用态/提示文字

// 标注图例（与 Home 页内回读下划线一致，古铜色系）
val RepeatUnderline = BronzeGold
