package com.k2fsa.sherpa.onnx.simulate.streaming.asr.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.DividerWarm
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.InkBlack
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.InkBlackSoft
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.InkGreen
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.InkRed
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.TagGray
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.WarmYellow

/**
 * FR-012/013 使用指南: 四步上手 + 标注图例 + 评分口径
 */
@Composable
fun HelpScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("怎么用", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = InkBlack)
        Spacer(modifier = Modifier.height(12.dp))
        StepLine("1", "看", "顶部卡片是今天背的篇目，先读两遍。")
        StepLine("2", "背", "点「开始」，对着手机背。说完一句会自动断句。")
        StepLine("3", "停", "背完(或背不下去)点「停止」，等两秒出评分。")
        StepLine("4", "补", "按报告里的红字错字、黄字停顿、金色回读，重点补最薄弱的两句。")

        Spacer(modifier = Modifier.height(20.dp))
        Divider(color = DividerWarm)
        Spacer(modifier = Modifier.height(20.dp))

        Text("报告标注怎么看", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = InkBlack)
        Spacer(modifier = Modifier.height(12.dp))
        LegendLine(InkRed, "红字", "错字 / 漏字 —— 读错了或跳过去没背")
        LegendLine(TagGray, "灰删除线", "多字 —— 背出了原文没有的字")
        LegendLine(WarmYellow, "黄字[停Xs]", "停顿 —— 卡壳超过 1.8 秒")
        LegendLine(InkGreen, "金下划线", "回读 —— 同一句重复背了两遍")

        Spacer(modifier = Modifier.height(20.dp))
        Divider(color = DividerWarm)
        Spacer(modifier = Modifier.height(20.dp))

        Text("评分怎么算", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = InkBlack)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "满分 100。错字每个字扣 3 分，漏一大段(超过 10 字)扣 15 分，" +
                    "停顿每次扣 1 分起(每多 1 秒加扣 0.5)，回读每次扣 2 分。" +
                    "准确率只看字对不对，流利度只看顺不顺，两边分开看才知道该练哪。",
            fontSize = 15.sp,
            lineHeight = 26.sp,
            color = InkBlackSoft,
        )
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun StepLine(no: String, word: String, desc: String) {
    Column(modifier = Modifier.padding(vertical = 5.dp)) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = WarmYellow, fontWeight = FontWeight.Bold)) { append("$no ") }
                withStyle(SpanStyle(color = InkBlack, fontWeight = FontWeight.Bold)) { append("$word　") }
                withStyle(SpanStyle(color = InkBlackSoft)) { append(desc) }
            },
            fontSize = 15.sp,
            lineHeight = 26.sp,
        )
    }
}

@Composable
private fun LegendLine(color: androidx.compose.ui.graphics.Color, label: String, desc: String) {
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append("$label　") }
            withStyle(SpanStyle(color = InkBlackSoft)) { append(desc) }
        },
        fontSize = 15.sp,
        lineHeight = 28.sp,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}
