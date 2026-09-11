package com.k2fsa.sherpa.onnx.simulate.streaming.asr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.getVadModelConfig


object BeisongAsr {
    private var _recognizer: OfflineRecognizer? = null
    val recognizer: OfflineRecognizer
        get() = _recognizer!!

    private var _recognizerEn: OfflineRecognizer? = null

    private var _vad: Vad? = null
    val vad: Vad
        get() = _vad!!

    /** v0.2.1 R3.2: 按当前选中篇目 lang 取识别器 (懒加载, 英文首次用才初始化) */
    val activeRecognizer: OfflineRecognizer
        get() = if (BeisongPoemStore.selected.lang == "en") recognizerEn else recognizer

    private val recognizerEn: OfflineRecognizer
        get() {
            _recognizerEn?.let { return it }
            synchronized(this) {
                if (_recognizerEn == null) initEn(appContextRef!!)
                return _recognizerEn!!
            }
        }

    private var appContextRef: Context? = null

    fun init(context: Context) {
        appContextRef = context.applicationContext
        synchronized(this) {
            if (_recognizer != null) return
            Log.i(TAG, "Initializing sherpa-onnx offline recognizer (paraformer-zh-small)")

            val modelDir = "sherpa-onnx-paraformer-zh-small-2024-03-09"
            val config = OfflineRecognizerConfig(
                modelConfig = com.k2fsa.sherpa.onnx.OfflineModelConfig(
                    paraformer = com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig(
                        model = copyAssetToInternalStorage("$modelDir/model.int8.onnx", context),
                    ),
                    tokens = copyAssetToInternalStorage("$modelDir/tokens.txt", context),
                    numThreads = 2,
                ),
            )

            _recognizer = OfflineRecognizer(
                assetManager = null,
                config = config,
            )
            Log.i(TAG, "sherpa-onnx offline recognizer initialized")
        }
    }

    /**
     * v0.2.1 R3.1: 英文 offline whisper tiny.en (带 cross-attention 导出,
     * enableTokenTimestamps=true 出 token 级时间戳, 直接喂现有诊断管道)。
     * int8: encoder 13MB + decoder 90MB ≈ 装机增量 ~103MB。
     */
    private fun initEn(context: Context) {
        Log.i(TAG, "Initializing sherpa-onnx offline recognizer (whisper-tiny.en)")
        val modelDir = "sherpa-onnx-whisper-tiny.en"
        val config = OfflineRecognizerConfig(
            modelConfig = com.k2fsa.sherpa.onnx.OfflineModelConfig(
                whisper = com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig(
                    encoder = copyAssetToInternalStorage("$modelDir/tiny.en-encoder.int8.onnx", context),
                    decoder = copyAssetToInternalStorage("$modelDir/tiny.en-decoder.int8.onnx", context),
                    language = "en",
                    task = "transcribe",
                    enableTokenTimestamps = true,
                ),
                tokens = copyAssetToInternalStorage("$modelDir/tiny.en-tokens.txt", context),
                numThreads = 2,
            ),
        )
        _recognizerEn = OfflineRecognizer(assetManager = null, config = config)
        Log.i(TAG, "sherpa-onnx whisper-tiny.en initialized")
    }

    fun initVad(context: Context) {
        if (_vad != null) return
        val vadModel = copyAssetToInternalStorage("silero_vad_v5.onnx", context)
        val config = com.k2fsa.sherpa.onnx.VadModelConfig(
            sileroVadModelConfig = com.k2fsa.sherpa.onnx.SileroVadModelConfig(
                model = vadModel,
                threshold = 0.5f,
                minSilenceDuration = 0.25f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
            ),
        )
        _vad = Vad(assetManager = null, config = config)
        Log.i(TAG, "sherpa-onnx vad initialized")
    }
}
