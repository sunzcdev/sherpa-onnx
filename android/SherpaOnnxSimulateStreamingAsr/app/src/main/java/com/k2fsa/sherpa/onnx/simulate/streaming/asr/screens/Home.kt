package com.k2fsa.sherpa.onnx.simulate.streaming.asr.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.R
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.TAG
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.BeisongAsr
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.BeisongDiagnose
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.BEISONG_TARGET_TEXT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

private var audioRecord: AudioRecord? = null
private const val sampleRateInHz = 16000
private var samplesChannel = Channel<FloatArray>(capacity = Channel.UNLIMITED)

private const val REQUEST_RECORD_AUDIO_PERMISSION = 200

// ─── Step 0: 诊断数据流修复 ───────────────────────────────────────────
// 根因: 识别循环里每个 VAD 段 createStream()→decode()→getResult()→release()，
// 段结果在循环内即被释放；停止时再建空 stream 取 tokens 恒为空 →
// 空串与目标文本做 LCS 必判"全漏字"，评分永远不准。
// 修复: 在 VAD 出段时累积最终 tokens/timestamps。时基: vad.reset() 后
// SpeechSegment.start 是全局线性样本号(含段间静音)，段内 timestamp +
// start/16000 即为整次录音的统一时间轴，跨段停顿不再被抹掉。
private val finalTokens = mutableListOf<String>()
private val finalTimestamps = mutableListOf<Float>()

/** 最近一次诊断结果，供评分面板(Step 2)/回读标注消费。 */
@Volatile
private var lastDiagnosis: BeisongDiagnose.Diagnosis? = null

private fun accumulateSegment(tokens: List<String>, timestamps: List<Float>, segStartSamples: Int) {
    if (tokens.isEmpty()) return
    val baseSec = segStartSamples.toFloat() / sampleRateInHz
    for (i in tokens.indices) {
        finalTokens.add(tokens[i])
        // timestamps 可能比 tokens 少(部分模型无时间戳) — 缺省按 0 间距续接
        val ts = if (i < timestamps.size) timestamps[i] + baseSec
        else finalTimestamps.lastOrNull() ?: baseSec
        finalTimestamps.add(ts)
    }
}

/** 停止录音后对累积的真实 tokens/timestamps 做诊断。 */
private fun runDiagnosis(context: android.content.Context) {
    if (finalTokens.isEmpty()) {
        lastDiagnosis = null
        CoroutineScope(Dispatchers.Main).launch {
            Toast.makeText(context, "未识别到语音，请靠近麦克风重试", Toast.LENGTH_LONG).show()
        }
        return
    }
    val diagnosis = BeisongDiagnose.diagnose(
        BEISONG_TARGET_TEXT, finalTokens.toList(), finalTimestamps.toFloatArray()
    )
    lastDiagnosis = diagnosis
    CoroutineScope(Dispatchers.Main).launch {
        Toast.makeText(
            context,
            "评分: ${diagnosis.score.total}/100 | 错字${diagnosis.errors.size}处 | 停顿${diagnosis.pauses.size}处 | 回读${diagnosis.repeats.size}处",
            Toast.LENGTH_LONG
        ).show()
    }
}

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val activity = LocalContext.current as Activity
    val coroutineScope = rememberCoroutineScope()

    var isStarted by remember { mutableStateOf(false) }
    var isInitialized by remember { mutableStateOf(false) }
    val resultList = remember { mutableStateListOf<String>() }
    val lazyColumnListState = rememberLazyListState()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) {
            BeisongAsr.init(context)
            BeisongAsr.initVad(context)
        }
        isInitialized = true
    }

    fun onRecordingButtonClick() {
        isStarted = !isStarted
        if (isStarted) {
            if (ActivityCompat.checkSelfPermission(
                    activity,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // 仅在用户点击录音按钮时才请求权限 — 不在启动时弹窗
                val permissions = arrayOf(Manifest.permission.RECORD_AUDIO)
                ActivityCompat.requestPermissions(activity, permissions, REQUEST_RECORD_AUDIO_PERMISSION)
                return
            }

            val audioSource = MediaRecorder.AudioSource.MIC
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val numBytes = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat)
            audioRecord = AudioRecord(
                audioSource, sampleRateInHz, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, numBytes * 2
            )
            BeisongAsr.vad.reset()
            finalTokens.clear()
            finalTimestamps.clear()
            lastDiagnosis = null
            // 每次录音换一个新通道，避免上一会话的残留样本/哨兵串进本次
            samplesChannel = Channel(capacity = Channel.UNLIMITED)

            CoroutineScope(Dispatchers.IO).launch {
                val interval = 0.1
                val bufferSize = (interval * sampleRateInHz).toInt()
                val buffer = ShortArray(bufferSize)
                audioRecord?.let { it ->
                    it.startRecording()
                    while (isStarted) {
                        val ret = audioRecord?.read(buffer, 0, buffer.size)
                        ret?.let { n ->
                            val samples = FloatArray(n) { buffer[it] / 32768.0f }
                            samplesChannel.send(samples)
                        }
                    }
                    samplesChannel.send(FloatArray(0))
                }
            }

            CoroutineScope(Dispatchers.Default).launch {
                var buffer = arrayListOf<Float>()
                var offset = 0
                val windowSize = 512
                var isSpeechStarted = false
                var startTime = System.currentTimeMillis()
                var lastText = ""
                var added = false
                var speechStartOffset = 0

                while (isStarted) {
                    for (s in samplesChannel) {
                        if (s.isEmpty()) break
                        buffer.addAll(s.toList())
                        while (offset + windowSize < buffer.size) {
                            BeisongAsr.vad.acceptWaveform(
                                buffer.subList(offset, offset + windowSize).toFloatArray()
                            )
                            offset += windowSize
                            if (!isSpeechStarted && BeisongAsr.vad.isSpeechDetected()) {
                                isSpeechStarted = true
                                speechStartOffset = (offset - 6400).coerceAtLeast(0)
                                startTime = System.currentTimeMillis()
                            }
                        }
                        val elapsed = System.currentTimeMillis() - startTime
                        if (isSpeechStarted && elapsed > 200) {
                            val stream = BeisongAsr.recognizer.createStream()
                            stream.acceptWaveform(
                                buffer.subList(speechStartOffset, offset).toFloatArray(),
                                sampleRateInHz
                            )
                            BeisongAsr.recognizer.decode(stream)
                            val result = BeisongAsr.recognizer.getResult(stream)
                            stream.release()
                            lastText = result.text
                            if (lastText.isNotBlank()) {
                                if (!added || resultList.isEmpty()) {
                                    resultList.add(lastText)
                                    added = true
                                } else {
                                    resultList[resultList.size - 1] = lastText
                                }
                                coroutineScope.launch {
                                    lazyColumnListState.animateScrollToItem(resultList.size - 1)
                                }
                            }
                            startTime = System.currentTimeMillis()
                        }
                        while (!BeisongAsr.vad.empty()) {
                            val seg = BeisongAsr.vad.front()
                            val stream = BeisongAsr.recognizer.createStream()
                            stream.acceptWaveform(seg.samples, sampleRateInHz)
                            BeisongAsr.recognizer.decode(stream)
                            val result = BeisongAsr.recognizer.getResult(stream)
                            stream.release()
                            // Step 0: 段最终结果累积 — seg.start 是 VAD 内部全局
                            // 线性样本号(含段间静音)，加上段内 timestamps 即为
                            // 整次录音的统一时基，跨段停顿可被 detectPauses 看到。
                            accumulateSegment(result.tokens.toList(), result.timestamps.toList(), seg.start)
                            isSpeechStarted = false
                            BeisongAsr.vad.pop()
                            buffer = arrayListOf()
                            offset = 0
                            if (lastText.isNotBlank()) {
                                if (added && resultList.isNotEmpty()) {
                                    resultList[resultList.size - 1] = result.text
                                } else {
                                    resultList.add(result.text)
                                }
                                coroutineScope.launch {
                                    lazyColumnListState.animateScrollToItem(resultList.size - 1)
                                }
                                added = false
                            }
                        }
                    }
                }
                // 收尾: flush 未闭合的尾段(停止时最后一段往往还在 VAD 缓冲里)，
                // 排空后统一对累积结果做诊断 — 这才是有数据的 stream。
                BeisongAsr.vad.flush()
                while (!BeisongAsr.vad.empty()) {
                    val seg = BeisongAsr.vad.front()
                    val stream = BeisongAsr.recognizer.createStream()
                    stream.acceptWaveform(seg.samples, sampleRateInHz)
                    BeisongAsr.recognizer.decode(stream)
                    val result = BeisongAsr.recognizer.getResult(stream)
                    stream.release()
                    accumulateSegment(result.tokens.toList(), result.timestamps.toList(), seg.start)
                    BeisongAsr.vad.pop()
                }
                runDiagnosis(context)
            }
        } else {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            // 诊断不在这里做: 等识别协程排空通道 + flush 尾段后，
            // 由 runDiagnosis() 用累积的真实 tokens/timestamps 出分。
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(modifier = Modifier) {
            if (!isInitialized) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Text(text = "Initializing... Please wait")
                }
            }

            HomeButtonRow(
                isStarted = isStarted, isInitialized = isInitialized,
                onRecordingButtonClick = ::onRecordingButtonClick,
                onCopyButtonClick = {
                    if (resultList.isNotEmpty()) {
                        val s = resultList.mapIndexed { i, s -> "${i + 1}: $s" }
                            .joinToString(separator = "\n")
                        clipboardManager.setText(AnnotatedString(s))
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Nothing to copy", Toast.LENGTH_SHORT).show()
                    }
                },
                onClearButtonClick = { resultList.clear() }
            )

            if (resultList.size > 0) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                    contentPadding = PaddingValues(16.dp),
                    state = lazyColumnListState
                ) {
                    itemsIndexed(resultList) { index, line ->
                        Text(text = "${index + 1}: $line")
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeButtonRow(
    modifier: Modifier = Modifier,
    isStarted: Boolean,
    isInitialized: Boolean,
    onRecordingButtonClick: () -> Unit,
    onCopyButtonClick: () -> Unit,
    onClearButtonClick: () -> Unit,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Button(onClick = onRecordingButtonClick, enabled = isInitialized) {
            Text(text = stringResource(if (isStarted) R.string.stop else R.string.start))
        }
        Spacer(modifier = Modifier.width(24.dp))
        Button(onClick = onCopyButtonClick, enabled = isInitialized) {
            Text(text = stringResource(id = R.string.copy))
        }
        Spacer(modifier = Modifier.width(24.dp))
        Button(onClick = onClearButtonClick, enabled = isInitialized) {
            Text(text = stringResource(id = R.string.clear))
        }
    }
}