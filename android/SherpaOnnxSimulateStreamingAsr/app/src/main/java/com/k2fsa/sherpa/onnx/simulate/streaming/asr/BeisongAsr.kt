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

    private var _vad: Vad? = null
    val vad: Vad
        get() = _vad!!

    fun init(context: Context) {
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
