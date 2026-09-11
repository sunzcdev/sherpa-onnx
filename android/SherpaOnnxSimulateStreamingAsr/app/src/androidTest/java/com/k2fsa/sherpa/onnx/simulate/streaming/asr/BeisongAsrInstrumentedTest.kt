package com.k2fsa.sherpa.onnx.simulate.streaming.asr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 设备侧 E2E — 真音频→paraformer ASR→诊断.
 *
 * 音频源 (优先级):
 * 1. /sdcard/Android/data/<pkg>/files/recite_16k.wav (adb push 覆盖, 开发期可选)
 * 2. androidTest asset recite_16k.wav (随测试 APK 装机, CI 零外部依赖)
 * (16kHz mono s16 canonical 44-byte header, edge-tts 合成的《静夜思》背诵)
 *
 * 覆盖 UI 自动化测不到的部分: native 模型加载/推理/时间戳.
 */
@RunWith(AndroidJUnit4::class)
class BeisongAsrInstrumentedTest {

    @Test
    fun asrOnRealAudioProducesDiagnosableTokens() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val override = File(context.getExternalFilesDir(null), "recite_16k.wav")
        val bytes: ByteArray = if (override.exists()) {
            override.readBytes()
        } else {
            InstrumentationRegistry.getInstrumentation().context.assets
                .open("recite_16k.wav").use { it.readBytes() }
        }

        BeisongAsr.init(context)

        val samples = readWavPcm16Mono(bytes)
        val stream = BeisongAsr.recognizer.createStream()
        val chunk = 16000 // 1s
        var off = 0
        while (off < samples.size) {
            val n = minOf(chunk, samples.size - off)
            stream.acceptWaveform(samples.copyOfRange(off, off + n), 16000)
            off += n
        }
        BeisongAsr.recognizer.decode(stream)
        val result = BeisongAsr.recognizer.getResult(stream)
        stream.release()

        val text = result.tokens.joinToString("")
        android.util.Log.i("BeisongE2E", "ASR text=$text ts=${result.timestamps.size}")

        assertTrue("ASR 应识别出≥8字, 实际=${text.length} ($text)", result.tokens.size >= 8)
        // paraformer-zh-small 原生 result.timestamps 恒空, App 全局时基由 VAD 层合成
        // (SimulateStreamingAsr.kt); 这里验证识别文本本身准确.
        val hanOnly = text.filter { it.code in 0x4E00..0x9FFF }
        assertTrue("背诵应全文命中诗句, 实际=$hanOnly",
            hanOnly == BEISONG_TARGET_TEXT.filter { it.code in 0x4E00..0x9FFF })

        // 用等间距时间戳 + 真 ASR tokens 过一遍诊断, 验证全链路不崩且高分
        val fakeTs = FloatArray(result.tokens.size) { 0.3f * (it + 1) }
        val d = BeisongDiagnose.diagnose(BEISONG_TARGET_TEXT, result.tokens.toList(), fakeTs)
        android.util.Log.i("BeisongE2E", "score=${d.score.total} acc=${d.score.accuracy} pauses=${d.pauses.size}")
        assertTrue("全文正确应≥90分, 实际=${d.score.total}", d.score.total >= 90)
    }

    private fun readWavPcm16Mono(bytes: ByteArray): FloatArray {
        // 跳 44 字节 RIFF 头（我们生成的 wav 固定; data 子块头 8 字节）
        val start = 44
        val n = (bytes.size - start) / 2
        val out = FloatArray(n)
        for (i in 0 until n) {
            val lo = bytes[start + i * 2].toInt() and 0xFF
            val hi = bytes[start + i * 2 + 1].toInt()
            out[i] = (hi shl 8 or lo) / 32768.0f
        }
        return out
    }

    /**
     * v0.2.1 R3: 英文链路 — whisper-tiny.en (带 cross-attention 导出)
     * 真音频 tokens+timestamps → 现有诊断管道 score>90。
     * 音频: edge-tts 朗读 Unit 5 主句型卡, 16k mono s16。
     */
    @Test
    fun englishWhisperProducesTimestampedDiagnosis() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("recite_en_16k.wav").use { it.readBytes() }

        // 走 activeRecognizer: 需要选中一篇 lang=en 的篇目
        BeisongPoemStore.init(context)
        val enBook = BeisongPoemStore.poems.value.firstOrNull { it.lang == "en" }
            ?: BeisongPoem(
                id = "test-en", title = "U5", author = "", dynasty = "",
                text = EN_TARGET, source = "builtin", category = "english-4a", lang = "en",
            )
        BeisongPoemStore.add(enBook)
        BeisongPoemStore.select(enBook.id)

        BeisongAsr.init(context)
        val samples = readWavPcm16Mono(bytes)
        val recognizer = BeisongAsr.activeRecognizer
        val stream = recognizer.createStream()
        val chunk = 16000
        var off = 0
        while (off < samples.size) {
            val n = minOf(chunk, samples.size - off)
            stream.acceptWaveform(samples.copyOfRange(off, off + n), 16000)
            off += n
        }
        recognizer.decode(stream)
        val result = recognizer.getResult(stream)
        stream.release()

        val text = result.tokens.joinToString("")
        android.util.Log.i("BeisongE2E", "EN ASR text=$text ts=${result.timestamps.size}")

        assertTrue("英文 ASR 应识别出≥15 token, 实际=${result.tokens.size}", result.tokens.size >= 15)
        assertTrue(
            "whisper-tiny.en (attention 导出) 必须产出 token timestamps, 实际=${result.timestamps.size}",
            result.timestamps.size == result.tokens.size,
        )

        // 真 timestamps 直接喂诊断 (与 Home.kt 生产路径一致, 非 fakeTs)
        val ts: FloatArray = result.timestamps
        val d = BeisongDiagnose.diagnose(EN_TARGET, result.tokens.toList(), ts)
        android.util.Log.i("BeisongE2E", "EN score=${d.score.total} acc=${d.score.accuracy} errs=${d.errors.size} pauses=${d.pauses.size}")
        assertTrue("英文全文背诵应≥90分, 实际=${d.score.total}", d.score.total >= 90)
    }

    companion object {
        const val EN_TARGET =
            "What's the weather like today? It's sunny and warm. Can I go outside? No, it's raining. You can't. What's the temperature? It's 20 degrees. Let's fly kites. The wind is just right for that!"
    }
}
