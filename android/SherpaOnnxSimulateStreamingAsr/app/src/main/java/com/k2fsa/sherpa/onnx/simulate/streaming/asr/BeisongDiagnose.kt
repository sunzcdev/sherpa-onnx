package com.k2fsa.sherpa.onnx.simulate.streaming.asr

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

/**
 * 智能背诵助手 — 诊断算法 (Kotlin 移植)
 *
 * 输入: ASR 输出的 tokens + timestamps (sherpa-onnx OfflineRecognizerResult.tokens / timestamps)
 * 输出: 诊断报告 (停顿/重复/错字标记 + 评分 + 靶向复习建议)
 *
 * 算法: 纯标准库实现, 零外部依赖, 与 Python 原型 beisong_diagnose.py 一一对应。
 */
object BeisongDiagnose {

    // ─── 配置 (MVP 阈值) ───────────────────────────────────────
    private const val PAUSE_THRESHOLD_S = 1.8f
    private const val REP_MIN_LEN = 2
    private const val SCORE_ERR_PER = 3
    private const val SCORE_REP_PER = 2
    private const val SCORE_PAUSE_BASE = 1.0f
    private const val SCORE_PAUSE_PER_S = 0.5f
    private const val SCORE_BIG_GAP = 15
    private const val BIG_GAP_CHARS = 10

    // ─── 数据类 ────────────────────────────────────────────────
    data class Pause(val beforeChar: String, val afterChar: String, val gapS: Float, val penalty: Float)
    data class Repeat(val segment: String, val penalty: Int)
    data class TextError(val type: String, val expected: String, val actual: String, val penalty: Int)
    data class BigGap(val segment: String, val penalty: Int)
    data class Score(val total: Int, val accuracy: Int, val fluency: Float,
                     val pausePenalty: Float, val repeatPenalty: Int, val errorPenalty: Int, val bigGapPenalty: Int)
    data class Diagnosis(
        val score: Score,
        val pauses: List<Pause>,
        val repeats: List<Repeat>,
        val errors: List<TextError>,
        val bigGaps: List<BigGap>,
        val targetedReview: List<Any>,
        val totalIssues: Int
    )

    // ─── 停顿检测 ──────────────────────────────────────────────
    fun detectPauses(tokens: List<String>, timestamps: FloatArray): List<Pause> {
        val pauses = mutableListOf<Pause>()
        for (i in 1 until tokens.size) {
            val gap = timestamps[i] - timestamps[i - 1]
            if (gap > PAUSE_THRESHOLD_S) {
                pauses.add(Pause(
                    beforeChar = tokens[i - 1],
                    afterChar = tokens[i],
                    gapS = gap,
                    penalty = SCORE_PAUSE_BASE + (gap - PAUSE_THRESHOLD_S) * SCORE_PAUSE_PER_S
                ))
            }
        }
        return pauses
    }

    // ─── 重复/回读检测 ─────────────────────────────────────────
    fun detectRepeats(text: String): List<Repeat> {
        val found = mutableListOf<Repeat>()
        val maxLen = text.length / 2
        for (ln in maxLen downTo REP_MIN_LEN) {
            var i = 0
            while (i + 2 * ln <= text.length) {
                if (text.substring(i, i + ln) == text.substring(i + ln, i + 2 * ln)) {
                    val seg = text.substring(i, i + ln)
                    if (!found.any { it.segment.contains(seg) }) {
                        found.add(Repeat(seg, SCORE_REP_PER))
                    }
                    i += ln
                } else {
                    i += 1
                }
            }
        }
        return found
    }

    // ─── 错/漏/多字检测 (LCS 对齐) ──────────────────────────────
    fun alignText(target: String, recited: String, repeats: List<Repeat>): List<TextError> {
        val ops = mutableListOf<TextError>()
        val repeatSegs = repeats.map { it.segment }.toSet()
        val (lcs, opsRaw) = lcsDiff(target, recited)
        for ((tag, a, b) in opsRaw) {
            if (tag == "equal") continue
            if (tag == "insert" && b in repeatSegs) continue
            when (tag) {
                "replace" -> ops.add(TextError("error", a, b, a.length * SCORE_ERR_PER))
                "delete"  -> ops.add(TextError("missing", a, "", a.length * SCORE_ERR_PER))
                "insert"  -> ops.add(TextError("extra", "", b, b.length * SCORE_ERR_PER))
            }
        }
        return ops
    }

    // ─── LCS 差分 (Longest Common Subsequence → opcodes) ───────
    private fun lcsDiff(a: String, b: String): Pair<String, List<Triple<String, String, String>>> {
        val m = a.length
        val n = b.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in m - 1 downTo 0) {
            for (j in n - 1 downTo 0) {
                if (a[i] == b[j]) dp[i][j] = dp[i + 1][j + 1] + 1
                else dp[i][j] = maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        // 回溯生成 opcodes
        val ops = mutableListOf<Triple<String, String, String>>()
        var i = 0; var j = 0
        while (i < m && j < n) {
            when {
                a[i] == b[j] -> { ops.add(Triple("equal", a[i].toString(), b[j].toString())); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> { ops.add(Triple("delete", a[i].toString(), "")); i++ }
                else -> { ops.add(Triple("insert", "", b[j].toString())); j++ }
            }
        }
        while (i < m) { ops.add(Triple("delete", a[i].toString(), "")); i++ }
        while (j < n) { ops.add(Triple("insert", "", b[j].toString())); j++ }
        // 合并相邻同类型 opcode
        val merged = mutableListOf<Triple<String, String, String>>()
        for (op in ops) {
            if (merged.isNotEmpty() && merged.last().first == op.first) {
                merged[merged.lastIndex] = Triple(op.first, merged.last().second + op.second, merged.last().third + op.third)
            } else {
                merged.add(op)
            }
        }
        return Pair("", merged)
    }

    // ─── 大段遗忘 ──────────────────────────────────────────────
    fun detectBigGap(errors: List<TextError>): List<BigGap> {
        return errors.filter { it.type == "missing" && it.expected.length > BIG_GAP_CHARS }
            .map { BigGap(it.expected, SCORE_BIG_GAP) }
    }

    // ─── 评分 ──────────────────────────────────────────────────
    fun score(pauses: List<Pause>, repeats: List<Repeat>, errors: List<TextError>, bigGaps: List<BigGap>): Score {
        val pausePenalty = pauses.sumOf { it.penalty.toDouble() }.toFloat()
        val repeatPenalty = repeats.sumOf { it.penalty }
        val errorPenalty = errors.sumOf { it.penalty }
        val bigGapPenalty = bigGaps.sumOf { it.penalty }
        val total = (100 - pausePenalty - (repeatPenalty + errorPenalty + bigGapPenalty)).toInt().coerceAtLeast(0)
        return Score(
            total = total,
            accuracy = (100 - errorPenalty - bigGapPenalty).coerceAtLeast(0),
            fluency = (100 - pausePenalty - repeatPenalty).coerceAtLeast(0.0f),
            pausePenalty = pausePenalty,
            repeatPenalty = repeatPenalty,
            errorPenalty = errorPenalty,
            bigGapPenalty = bigGapPenalty
        )
    }

    // ─── 靶向复习 Top N ────────────────────────────────────────
    fun topTargets(pauses: List<Pause>, repeats: List<Repeat>, errors: List<TextError>, bigGaps: List<BigGap>, topN: Int = 2): List<Any> {
        val all = mutableListOf<Any>()
        all.addAll(pauses)
        all.addAll(repeats)
        all.addAll(errors)
        all.addAll(bigGaps)
        all.sortByDescending { penaltyOf(it) }
        return all.take(topN)
    }

    private fun penaltyOf(item: Any): Int = when (item) {
        is Pause -> item.penalty.toInt()
        is Repeat -> item.penalty
        is TextError -> item.penalty
        is BigGap -> item.penalty
        else -> 0
    }

    // ─── 归一化 ────────────────────────────────────────────────
    // ASR tokens 只含汉字/字词，目标篇目带「，。、」等标点。不归一化会让
    // 标点被 LCS 判成 missing（每个扣 3 分），全文背诵最高只能拿 88 分。
    // 真机同样命中此 bug（设备注入测试 2026-09-11 实测抓到）。
    // v0.2.1 R3.3: 英文适配 — lowercase 折叠 (whisper "Hello" vs 书本 "hello")；
    // 撇号变体 (’'′) 非字母数字已被 filter 剥掉, don't/don’t → dont 自动对齐。
    fun normalize(text: String): String =
        text.filter { it.code in 0x4E00..0x9FFF || it.isLetterOrDigit() }.lowercase()

    // ─── 主入口 ────────────────────────────────────────────────
    fun diagnose(targetText: String, tokens: List<String>, timestamps: FloatArray): Diagnosis {
        val target = normalize(targetText)
        val recited = normalize(tokens.joinToString(""))
        val pauses = detectPauses(tokens, timestamps)
        val repeats = detectRepeats(recited)
        val errors = alignText(target, recited, repeats)
        val bigGaps = detectBigGap(errors)
        val sc = score(pauses, repeats, errors, bigGaps)
        val targets = topTargets(pauses, repeats, errors, bigGaps)
        return Diagnosis(
            score = sc,
            pauses = pauses,
            repeats = repeats,
            errors = errors,
            bigGaps = bigGaps,
            targetedReview = targets,
            totalIssues = pauses.size + repeats.size + errors.size + bigGaps.size
        )
    }

    // ─── 渲染带标注的背诵报告 (Compose AnnotatedString) ───────
    // FR-008 标注图例: 红=错字/漏字, 灰删除线=多字, 黄=停顿, 金下划线=回读
    fun renderReport(targetText: String, tokens: List<String>, timestamps: FloatArray,
                     diagnosis: Diagnosis): AnnotatedString {
        val target = normalize(targetText)
        val recited = normalize(tokens.joinToString(""))
        // 每个字符的时间戳(由 token 展开; token 可能是子词多字符)
        val charTs = HashMap<Int, Float>()
        run {
            var pos = 0
            for (i in tokens.indices) {
                val t = if (i < timestamps.size) timestamps[i] else 0f
                for (k in tokens[i].indices) charTs[pos + k] = t
                pos += tokens[i].length
            }
        }
        // 回读片段在转写文本中的字符区间
        val repeatRanges = mutableListOf<IntRange>()
        for (rep in diagnosis.repeats) {
            var idx = recited.indexOf(rep.segment)
            while (idx >= 0) {
                repeatRanges.add(idx until idx + rep.segment.length)
                idx = recited.indexOf(rep.segment, idx + 1)
            }
        }
        val pauseStyle = SpanStyle(color = Color(0xFFD4A017), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        val errorStyle = SpanStyle(color = Color(0xFFC0392B), fontWeight = FontWeight.Bold)
        val extraStyle = SpanStyle(color = Color(0xFF8A7F70), textDecoration = TextDecoration.LineThrough)
        val repeatStyle = SpanStyle(color = Color(0xFF2C2416), textDecoration = TextDecoration.Underline, background = Color(0x33C8A45C))
        val (_, ops) = lcsDiff(target, recited)
        return buildAnnotatedString {
            var rIdx = 0
            fun pauseBefore(): Float {
                if (rIdx == 0) return 0f
                val cur = charTs[rIdx] ?: return 0f
                val prev = charTs[rIdx - 1] ?: return 0f
                return cur - prev
            }
            fun emitRecitedChar(c: Char) {
                val gap = pauseBefore()
                if (gap > PAUSE_THRESHOLD_S) {
                    withStyle(pauseStyle) { append("[停${"%.1f".format(gap)}s]") }
                }
                if (repeatRanges.any { rIdx in it }) {
                    withStyle(repeatStyle) { append(c) }
                } else {
                    append(c)
                }
                rIdx++
            }
            for ((tag, a, b) in ops) {
                when (tag) {
                    "equal" -> for (c in b) emitRecitedChar(c)
                    "delete" -> withStyle(errorStyle) { append(a) }   // 漏字: 标红期望字符
                    "replace" -> { for (c in b) { withStyle(errorStyle) { append(c) }; rIdx++ } }
                    "insert" -> for (c in b) { withStyle(extraStyle) { append(c) }; rIdx++ }
                }
            }
        }
    }
}