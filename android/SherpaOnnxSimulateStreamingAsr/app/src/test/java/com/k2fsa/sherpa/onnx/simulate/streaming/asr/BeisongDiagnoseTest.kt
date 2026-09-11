package com.k2fsa.sherpa.onnx.simulate.streaming.asr

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 0 验收 — 诊断算法层（设备无关）：
 * 错字/停顿/回读三类信号必须能在真实数据流上同时非零。
 */
class BeisongDiagnoseTest {

    private fun tokenize(text: String): List<String> = text.map { it.toString() }

    @Test
    fun allThreeSignalsFire() {
        val target = BEISONG_TARGET_TEXT // 床前明月光，疑是地上霜。举头望明月，低头思故乡。
        // 模拟背诵: 「明」读成「名」(错字)；「上霜」后停 2.5s；「举头望明月」回读一遍
        val recited = "床前名月光，疑是地上霜。举头望明月，举头望明月，低头思故乡。"
        val tokens = tokenize(recited)
        // 时间轴: 每字 0.3s，「霜。」后跳 2.5s（停顿）
        val ts = FloatArray(tokens.size)
        var t = 0.3f
        for (i in tokens.indices) {
            if (i > 0 && tokens[i - 1] == "霜") t += 2.5f
            ts[i] = t
            t += 0.3f
        }
        val d = BeisongDiagnose.diagnose(target, tokens, ts)
        assertTrue("错字应>0", d.errors.isNotEmpty())
        assertTrue("停顿应>0", d.pauses.isNotEmpty())
        assertTrue("回读应>0", d.repeats.isNotEmpty())
        assertTrue("总分应<100", d.score.total < 100)
        assertTrue("复习建议应>0", d.targetedReview.isNotEmpty())
    }

    @Test
    fun perfectRecitationScoresHigh() {
        val target = BEISONG_TARGET_TEXT
        val tokens = tokenize(target)
        val ts = FloatArray(tokens.size) { 0.3f * (it + 1) }
        val d = BeisongDiagnose.diagnose(target, tokens, ts)
        assertTrue("全文正确应满分", d.score.total == 100)
        assertTrue(d.errors.isEmpty() && d.pauses.isEmpty() && d.repeats.isEmpty())
    }

    @Test
    fun punctuationInTargetNeverPenalized() {
        // 回归: ASR tokens 永远不含标点，若目标文本「，。」不剥离会被判漏字，
        // 全文背诵最高只剩 88 分（设备注入测试 2026-09-11 抓到）。
        val target = BEISONG_TARGET_TEXT // 带标点
        val tokens = tokenize(target).filter { it.single().code in 0x4E00..0x9FFF }
        val ts = FloatArray(tokens.size) { 0.3f * (it + 1) }
        val d = BeisongDiagnose.diagnose(target, tokens, ts)
        assertTrue("无标点 tokens 对带标点目标应满分, 实际=${d.score.total}", d.score.total == 100)
    }

    @Test
    fun renderReportAnnotatesAllLegendTypes() {
        val target = BEISONG_TARGET_TEXT
        val recited = "床前名月光，疑是地上霜。举头望明月，举头望明月，低头思故乡。"
        val tokens = tokenize(recited)
        val ts = FloatArray(tokens.size)
        var t = 0.3f
        for (i in tokens.indices) {
            if (i > 0 && tokens[i - 1] == "霜") t += 2.5f
            ts[i] = t
            t += 0.3f
        }
        val d = BeisongDiagnose.diagnose(target, tokens, ts)
        val report = BeisongDiagnose.renderReport(target, tokens, ts, d)
        // 标注文本必须包含: 停顿标记 + 回读(原文子串出现) + 错字「名」
        assertTrue(report.text.contains("[停"))
        assertTrue(report.text.contains("名"))
        assertTrue(report.text.contains("举头望明月"))
    }
}
