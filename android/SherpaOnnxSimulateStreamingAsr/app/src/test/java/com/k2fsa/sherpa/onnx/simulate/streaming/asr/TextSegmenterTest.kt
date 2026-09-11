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
}