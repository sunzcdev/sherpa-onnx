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

private var audioRecord: AudioRecord? = null
private const val sampleRateInHz = 16000
private var samplesChannel = Channel<FloatArray>(capacity = Channel.UNLIMITED)

private const val REQUEST_RECORD_AUDIO_PERMISSION = 200

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
                            val stream = BeisongAsr.recognizer.createStream()
                            stream.acceptWaveform(BeisongAsr.vad.front().samples, sampleRateInHz)
                            BeisongAsr.recognizer.decode(stream)
                            val result = BeisongAsr.recognizer.getResult(stream)
                            stream.release()
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
            }
        } else {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            if (resultList.isNotEmpty()) {
                coroutineScope.launch(Dispatchers.Default) {
                    val stream = BeisongAsr.recognizer.createStream()
                    val result = BeisongAsr.recognizer.getResult(stream)
                    stream.release()
                    val tokens = result.tokens.toList()
                    val timestamps = result.timestamps.toList()
                    val diagnosis = BeisongDiagnose.diagnose(
                        BEISONG_TARGET_TEXT, tokens, timestamps.toFloatArray()
                    )
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "评分: ${diagnosis.score.total}/100 | 错字${diagnosis.errors.size}处 | 停顿${diagnosis.pauses.size}处 | 回读${diagnosis.repeats.size}处",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
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
                onRecordingButtonClick = onRecordingButtonClick,
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