package com.k2fsa.sherpa.onnx.simulate.streaming.asr

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.getVadModelConfig
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream


fun assetExists(assetManager: AssetManager, path: String): Boolean {
    val dir = path.substringBeforeLast('/', "")
    val fileName = path.substringAfterLast('/')
    val files = assetManager.list(dir) ?: return false
    return files.contains(fileName)
}


fun copyAssetToInternalStorage(path: String, context: Context): String {
    val targetRoot = context.filesDir
    val outFile = File(targetRoot, path)

    if (!assetExists(context.assets, path = path)) {
        outFile.parentFile?.mkdirs()
        Log.i(TAG, "$path does not exist, return ${outFile.absolutePath}")
        return outFile.absolutePath
    }

    if (outFile.exists()) {
        val assetSize = context.assets.open(path).use { it.available() }
        if (outFile.length() == assetSize.toLong()) {
            Log.i(TAG, "$targetRoot/$path already exists, skip copying")
            return "$targetRoot/$path"
        }
    }

    outFile.parentFile?.mkdirs()

    context.assets.open(path).use { input: InputStream ->
        FileOutputStream(outFile).use { output: OutputStream ->
            input.copyTo(output)
        }
    }
    Log.i(TAG, "Copied $path to $targetRoot/$path")

    return "$targetRoot/$path"
}


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
