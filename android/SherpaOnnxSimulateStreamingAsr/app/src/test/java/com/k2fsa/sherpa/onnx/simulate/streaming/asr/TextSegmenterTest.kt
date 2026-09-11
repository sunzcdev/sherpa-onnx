package com.k2fsa.sherpa.onnx.simulate.streaming.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TextSegmenter JVM 单测 — 断句/合并/兜底/标题作者解析
 */
class TextSegmenterTest {

    // ── 标点切句 ──
    @Test
    fun splitSentencesByPunctuation() {
        val input = "床前明月光，疑是地上霜。举头望明月，低头思故乡。"
        val sentences = TextSegmenter.splitSentences(input)
        // 逗号不切句，只按句末标点（。！？；）切 → 「床前明月光，疑是地上霜。」+「举头望明月，低头思故乡。」
        assertEquals(2, sentences.size)
        assertEquals("床前明月光，疑是地上霜。", sentences[0])
        assertEquals("举头望明月，低头思故乡。", sentences[1])
    }

    // ── 短句合并为背诵单元 ──
    @Test
    fun toUnitsMergesShortSentences() {
        val input = "床前明月光，疑是地上霜。举头望明月，低头思故乡。"
        val units = TextSegmenter.toUnits(input)
        // 每句 5-6 字，两句合并 ≈ 11-12 字 ≤ 24 上限，应合并为 1-2 个单元
        assertTrue("短句应合并", units.size <= 2)
        assertTrue("合并后长度应 ≤ 36", units.all { it.length <= 36 })
    }

    // ── 无标点兜底硬切 ──
    @Test
    fun noPunctuationFallbackHardSplit() {
        val input = "一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五"
        val units = TextSegmenter.toUnits(input)
        assertTrue("无标点长文本应硬切", units.size > 1)
        assertTrue("每段长度应 ≤ 36", units.all { it.length <= 36 })
    }

    // ── 标题/作者解析 ──
    @Test
    fun parsePoemTitleAndAuthor() {
        val input = "静夜思\n唐·李白\n床前明月光，疑是地上霜。举头望明月，低头思故乡。"
        val poem = TextSegmenter.parsePoem(input)
        assertEquals("静夜思", poem.title)
        assertEquals("李白", poem.author)
        assertEquals("唐", poem.dynasty)
        assertTrue(poem.body.contains("床前明月光"))
    }

    // ── OCR 噪声清洗 ──
    @Test
    fun cleanRemovesNoise() {
        val input = "床前明月光  疑是地上霜\n\u00A0 1 \n"
        val cleaned = TextSegmenter.clean(input)
        assertTrue("应去除页码/装饰符", !cleaned.contains("\u00A0"))
        assertTrue("应保留正文", cleaned.contains("床前明月光"))
    }

    // ── v0.2.1 R1.2 连续重复行去重 ──
    @Test
    fun cleanDedupesConsecutiveLines() {
        val input = "床前明月光，疑是地上霜。\n床前明月光，疑是地上霜。\n举头望明月，低头思故乡。"
        val cleaned = TextSegmenter.clean(input)
        assertEquals("重复行应被去掉", 2, cleaned.lines().size)
    }

    // ── v0.2.1 R1.3 中文行半角标点统一 ──
    @Test
    fun normalizePunctChineseLine() {
        val r = TextSegmenter.normalizePunct("床前明月光, 疑是地上霜. 夜来风雨声!")
        assertTrue("逗号转全角", r.contains("，"))
        assertTrue("句号转全角", r.contains("。"))
        assertTrue("感叹号转全角", r.contains("！"))
    }

    @Test
    fun normalizePunctKeepsEnglishLineHalfwidth() {
        val r = TextSegmenter.normalizePunct("What's the weather like today? It's sunny.")
        assertTrue("英文行保留半角", r.contains("?") && r.contains("."))
        assertTrue("无全角污染", !r.contains("？") && !r.contains("。"))
    }

    // ── v0.2.1 R3.3 英文断句: 保留词间空格 ──
    @Test
    fun splitSentencesEnglishKeepsSpaces() {
        val input = "Who is your best friend? He is Zhang Peng. He's tall and strong."
        val sents = TextSegmenter.splitSentences(input)
        assertEquals(3, sents.size)
        assertEquals("Who is your best friend?", sents[0])
        assertTrue("单词不得粘连", sents[1].contains(" is "))
    }

    @Test
    fun toUnitsEnglishCutsWordBoundary() {
        // 无标点长英文 → 兜底硬切必须落在词边界
        val input = "the farmer nurse doctor office worker factory cook teacher sing help clean sweep"
        val units = TextSegmenter.toUnits(input)
        assertTrue(units.isNotEmpty())
        units.forEach { u ->
            assertTrue("单元不应以空格结尾/开头", u == u.trim())
            // 切点两侧不是单词内部 (除首尾单元)
            u.split(" ").forEach { w -> assertTrue("无半截单词", w.none { it == '~' }) }
        }
        // 单词完整性: 全部单元拼回应覆盖全部原词 (等长词边界切, 不吞词)
        val allWords = units.joinToString(" ").split(" ").filter { it.isNotEmpty() }
        assertEquals(input.split(" ").size, allWords.size)
    }

    // ── v0.2.1 detectLang ──
    @Test
    fun detectLangByLatinRatio() {
        assertEquals("zh", TextSegmenter.detectLang("床前明月光，疑是地上霜。"))
        assertEquals("en", TextSegmenter.detectLang("What's the weather like today?"))
        assertEquals("zh", TextSegmenter.detectLang(""))
    }

    // ── v0.2.1 R1.5 diff ──
    @Test
    fun diffMarksChangedLines() {
        val raw = "1. 床前明月光, 疑是地上霜。\n1. 床前明月光, 疑是地上霜。\n举头望明月，低头思故乡。"
        val d = TextSegmenter.diff(raw)
        assertTrue("有清洗改动应产出多行 diff", d.size > 1)
        assertTrue("应含 raw 行", d.any { it.kind == "raw" })
        assertTrue("应含 clean 行", d.any { it.kind == "clean" })
    }
}