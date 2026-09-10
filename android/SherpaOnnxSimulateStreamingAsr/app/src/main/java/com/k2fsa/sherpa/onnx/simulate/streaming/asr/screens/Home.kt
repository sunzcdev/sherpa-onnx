package com.k2fsa.sherpa.onnx.simulate.streaming.asr.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Divider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.BEISONG_POEMS
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.BeisongAsr
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.BeisongDiagnose
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.R
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.BronzeGold
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.BronzeGoldPressed
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.BeisongShapes
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.CardWhite
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.DividerWarm
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.InkBlack
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.InkBlackSoft
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.InkGreen
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.InkRed
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.TagGray
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.WarmYellow
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.XuanPaper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

private fun accumulateSegment(tokens: List<String>, timestamps: List<Float>, segStartSamples: Int) {
    if (tokens.isEmpty()) return
    val baseSec = segStartSamples.toFloat() / sampleRateInHz
    for (i in tokens.indices) {
        finalTokens.add(tokens[i])
        // timestamps 可能比 tokens 少(部分模型无时间戳) — 缺省续接上一时间
        val ts = if (i < timestamps.size) timestamps[i] + baseSec
        else finalTimestamps.lastOrNull() ?: baseSec
        finalTimestamps.add(ts)
    }
}

/** 停止录音后对累积的真实 tokens/timestamps 做诊断，结果回调到主线程。 */
private fun runDiagnosis(
    context: Context,
    onDone: (BeisongDiagnose.Diagnosis?, AnnotatedString?) -> Unit,
) {
    if (finalTokens.isEmpty()) {
        CoroutineScope(Dispatchers.Main).launch {
            Toast.makeText(context, "未识别到语音，请靠近麦克风重试", Toast.LENGTH_LONG).show()
            onDone(null, null)
        }
        return
    }
    val tokens = finalTokens.toList()
    val timestamps = finalTimestamps.toFloatArray()
    val target = BEISONG_POEMS.first().text
    val diagnosis = BeisongDiagnose.diagnose(target, tokens, timestamps)
    val report = BeisongDiagnose.renderReport(target, tokens, timestamps, diagnosis)
    CoroutineScope(Dispatchers.Main).launch {
        Toast.makeText(
            context,
            "评分: ${diagnosis.score.total}/100 | 错字${diagnosis.errors.size}处 | 停顿${diagnosis.pauses.size}处 | 回读${diagnosis.repeats.size}处",
            Toast.LENGTH_LONG
        ).show()
        onDone(diagnosis, report)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val activity = LocalContext.current as Activity
    val coroutineScope = rememberCoroutineScope()

    val poem = remember { BEISONG_POEMS.first() }
    var isStarted by remember { mutableStateOf(false) }
    var isInitialized by remember { mutableStateOf(false) }
    var isRecordingSession by remember { mutableStateOf(false) }   // 录音线程活着(区分"等权限")
    val resultList = remember { mutableStateListOf<String>() }
    val lazyColumnListState = rememberLazyListState()

    // Step 2 消费: 诊断产物上界面
    var diagnosis by remember { mutableStateOf<BeisongDiagnose.Diagnosis?>(null) }
    var reportText by remember { mutableStateOf<AnnotatedString?>(null) }
    var showScoreSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) {
            BeisongAsr.init(context)
            BeisongAsr.initVad(context)
        }
        isInitialized = true
    }

    fun startRecording() {
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
        isRecordingSession = true
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
                        // Step 0: 段最终结果累积(全局时基, 见文件头注释)
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
            // 收尾: flush 未闭合的尾段，排空后统一诊断 — 用真实累积数据出分
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
            runDiagnosis(context) { d, report ->
                diagnosis = d
                reportText = report
                isRecordingSession = false
                if (d != null) showScoreSheet = true
            }
        }
    }

    // Bug 2 修复: 授权回调里自动续录，不再需要用户点第二次
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startRecording()
        } else {
            isStarted = false
            Toast.makeText(context, "需要录音权限才能使用背诵助手", Toast.LENGTH_LONG).show()
        }
    }

    fun onRecordingButtonClick() {
        if (isRecordingSession) return   // 停止收尾(尾段解码+诊断)期间防重入
        isStarted = !isStarted
        if (isStarted) {
            diagnosis = null
            reportText = null
            if (ActivityCompat.checkSelfPermission(
                    activity,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // 仅在用户点击录音按钮时才请求权限；授权成功后 launcher 回调自动开录
                ActivityCompat.requestPermissions(
                    activity, arrayOf(Manifest.permission.RECORD_AUDIO),
                    REQUEST_RECORD_AUDIO_PERMISSION
                )
                return
            }
            startRecording()
        } else {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            // 诊断由识别协程收尾时执行(排空通道+flush尾段后)，见 startRecording()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // ── FR-001 篇目卡: 白卡 + 衬线 + 可滚动 ──
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            colors = CardDefaults.cardColors(containerColor = CardWhite),
            shape = BeisongShapes.medium,
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Text(
                    text = "${poem.title} · ${poem.dynasty} · ${poem.author}",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = InkBlack,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = poem.text,
                    fontSize = 18.sp,
                    lineHeight = 32.sp,
                    color = InkBlackSoft,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (!isInitialized) {
            Text(
                text = "模型初始化中，请稍候…",
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = TagGray,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        } else if (isStarted) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .width(8.dp)
                        .height(8.dp)
                        .background(InkRed, androidx.compose.foundation.shape.CircleShape)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("正在背诵…点「停止」出评分", color = InkRed, fontWeight = FontWeight.Bold)
            }
        }

        HomeButtonRow(
            isStarted = isStarted, isInitialized = isInitialized,
            onRecordingButtonClick = ::onRecordingButtonClick,
            onCopyButtonClick = {
                val src = if (resultList.isNotEmpty()) resultList else listOf()
                if (src.isNotEmpty()) {
                    val s = src.mapIndexed { i, t -> "${i + 1}: $t" }.joinToString(separator = "\n")
                    clipboardManager.setText(AnnotatedString(s))
                    Toast.makeText(context, "转写已复制", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "暂无转写内容", Toast.LENGTH_SHORT).show()
                }
            },
            onClearButtonClick = {
                resultList.clear(); reportText = null; diagnosis = null
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ── FR-008 背诵报告(停止后) / 实时转写(录音中) ──
        val report = reportText
        if (report != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = CardWhite),
                shape = BeisongShapes.medium,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    Text("背诵报告", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = InkBlack)
                    Spacer(modifier = Modifier.height(4.dp))
                    LegendRow()
                    Divider(color = DividerWarm, modifier = Modifier.padding(vertical = 10.dp))
                    Text(text = report, fontSize = 18.sp, lineHeight = 34.sp, color = InkBlack)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { showScoreSheet = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("查看评分", color = BronzeGoldPressed) }
                }
            }
        } else if (resultList.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                contentPadding = PaddingValues(8.dp),
                state = lazyColumnListState,
            ) {
                itemsIndexed(resultList) { index, line ->
                    Text(
                        text = line,
                        fontSize = 16.sp,
                        lineHeight = 28.sp,
                        color = InkBlack,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        }
    }

    // ── FR-007/009 评分面板 ──
    if (showScoreSheet && diagnosis != null) {
        ModalBottomSheet(
            onDismissRequest = { showScoreSheet = false },
            sheetState = sheetState,
            containerColor = CardWhite,
        ) {
            ScorePanel(diagnosis!!) { showScoreSheet = false }
        }
    }
}

/** 标注图例 (FR-008): 红=错/漏字 灰删除线=多字 黄=停顿 金下划线=回读 */
@Composable
private fun LegendRow() {
    val sample = remember {
        AnnotatedString.Builder().apply {
            pushStyle(SpanStyle(color = InkRed, fontWeight = FontWeight.Bold)); append("错/漏")
            pop()
            append("  ")
            pushStyle(SpanStyle(color = TagGray)); append("多")
            pop()
            append("  ")
            pushStyle(SpanStyle(color = WarmYellow, fontWeight = FontWeight.Bold)); append("停顿")
            pop()
            append("  ")
            pushStyle(SpanStyle(color = InkBlack)); append("回读")
            pop()
        }.toAnnotatedString()
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("标注:", fontSize = 12.sp, color = TagGray)
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = sample, fontSize = 12.sp)
    }
}

/** FR-007 总分/准确率/流利度/三类计数 + FR-009 Top2 复习卡 */
@Composable
private fun ScorePanel(diagnosis: BeisongDiagnose.Diagnosis, onClose: () -> Unit) {
    val sc = diagnosis.score
    val scoreColor = when {
        sc.total >= 90 -> InkGreen
        sc.total >= 60 -> WarmYellow
        else -> InkRed
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("本次评分", fontSize = 14.sp, color = TagGray)
        Text(
            text = "${sc.total}",
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            color = scoreColor,
            lineHeight = 56.sp,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            MetricCell("准确率", "${sc.accuracy}")
            MetricCell("流利度", "${sc.fluency.toInt()}")
            MetricCell("错/漏", "${diagnosis.errors.size}")
            MetricCell("停顿", "${diagnosis.pauses.size}")
            MetricCell("回读", "${diagnosis.repeats.size}")
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (diagnosis.targetedReview.isNotEmpty()) {
            Text(
                "重点复习",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = InkBlack,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            for ((idx, item) in diagnosis.targetedReview.take(2).withIndex()) {
                ReviewCard(idx + 1, item)
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onClose,
            colors = ButtonDefaults.buttonColors(
                containerColor = BronzeGold,
                contentColor = InkBlack,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("知道了", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = InkBlack)
        Text(label, fontSize = 11.sp, color = TagGray)
    }
}

@Composable
private fun ReviewCard(rank: Int, item: Any) {
    val (label, detail, color) = when (item) {
        is BeisongDiagnose.Pause -> Triple(
            "卡顿",
            "「${item.beforeChar}」后停了 ${"%.1f".format(item.gapS)} 秒",
            WarmYellow,
        )
        is BeisongDiagnose.Repeat -> Triple("回读", "重复背了「${item.segment}」", BronzeGoldPressed)
        is BeisongDiagnose.TextError -> when (item.type) {
            "error" -> Triple("错字", "「${item.expected}」读成了「${item.actual}」", InkRed)
            "missing" -> Triple("漏字", "漏了「${item.expected}」", InkRed)
            else -> Triple("多字", "多了「${item.actual}」", TagGray)
        }
        is BeisongDiagnose.BigGap -> Triple("大段遗忘", "漏背「${item.segment}」", InkRed)
        else -> Triple("提示", item.toString(), InkBlackSoft)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = XuanPaper),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("#$rank", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TagGray)
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = CardWhite,
                modifier = Modifier
                    .background(color, androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(detail, fontSize = 14.sp, color = InkBlack)
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Button(
            onClick = onRecordingButtonClick,
            enabled = isInitialized,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isStarted) InkRed else BronzeGold,
                contentColor = if (isStarted) CardWhite else InkBlack,
            ),
            modifier = Modifier
                .height(48.dp)
                .weight(1.4f),
        ) {
            Text(
                text = stringResource(if (isStarted) R.string.stop else R.string.start),
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        OutlinedButton(
            onClick = onCopyButtonClick,
            enabled = isInitialized,
            modifier = Modifier
                .height(48.dp)
                .weight(1f),
        ) {
            Text(text = stringResource(id = R.string.copy), color = InkBlackSoft)
        }
        Spacer(modifier = Modifier.width(12.dp))
        OutlinedButton(
            onClick = onClearButtonClick,
            enabled = isInitialized,
            modifier = Modifier
                .height(48.dp)
                .weight(1f),
        ) {
            Text(text = stringResource(id = R.string.clear), color = InkBlackSoft)
        }
    }
}
