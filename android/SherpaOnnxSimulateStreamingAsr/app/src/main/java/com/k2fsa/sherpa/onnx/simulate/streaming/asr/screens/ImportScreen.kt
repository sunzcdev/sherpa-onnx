package com.k2fsa.sherpa.onnx.simulate.streaming.asr.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.BeisongPoem
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.BeisongPoemStore
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.PoemLibrary
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.TextSegmenter
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.BronzeGold
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.BronzeGoldPressed
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.CardWhite
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.DividerWarm
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.InkBlack
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.InkBlackSoft
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.InkRed
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.TagGray
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.XuanPaper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

/**
 * v0.2 内容导入: 「拍照导入」(CameraX + ML Kit 中文 OCR) / 「诗词库」(内置 90+ 首)
 * 两条路径最终都落到 BeisongPoemStore.add() — 与背诵页共享同一篇目状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(0) }
    // OCR 结果确认态: 非 null 时两页都切换到可编辑确认
    var confirm by remember { mutableStateOf<TextSegmenter.ParsedPoem?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(XuanPaper)
    ) {
        // 顶栏: 返回 + 标题
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text("← 返回", color = InkBlackSoft) }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "导入篇目",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = InkBlack,
            )
        }

        TabRow(
            selectedTabIndex = tab,
            containerColor = CardWhite,
            contentColor = BronzeGoldPressed,
        ) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0; confirm = null },
                text = { Text("拍照导入", fontSize = 15.sp) },
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1; confirm = null },
                text = { Text("诗词库", fontSize = 15.sp) },
            )
        }

        when {
            confirm != null -> {
                ConfirmImport(
                    parsed = confirm!!,
                    onCancel = { confirm = null },
                    onSave = { poem ->
                        val added = BeisongPoemStore.add(poem)
                        Toast.makeText(
                            context,
                            if (added) "已导入「${poem.title}」" else "篇目已存在，已选中",
                            Toast.LENGTH_LONG
                        ).show()
                        onClose()
                    },
                )
            }
            tab == 0 -> CameraImport(
                onOcrResult = { raw ->
                    if (raw.isBlank()) {
                        Toast.makeText(context, "未识别到文字，请对准诗文重试", Toast.LENGTH_LONG).show()
                    } else {
                        confirm = TextSegmenter.parsePoem(raw)
                    }
                },
            )
            else -> LibraryTab(
                onImport = { poem ->
                    coroutineScope.launch {
                        val added = BeisongPoemStore.add(poem)
                        Toast.makeText(
                            context,
                            if (added) "已导入「${poem.title}」" else "篇目已存在，已选中",
                            Toast.LENGTH_LONG
                        ).show()
                        onClose()
                    }
                },
            )
        }
    }
}

// ─── 拍照导入: 权限 → CameraX 预览 → 拍照 → ML Kit OCR ───────────────────

@Composable
private fun CameraImport(onOcrResult: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (!granted) {
            Toast.makeText(context, "需要相机权限才能拍照导入", Toast.LENGTH_LONG).show()
        }
    }
    DisposableEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
        onDispose { }
    }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }
    val cameraProviderRef = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    DisposableEffect(Unit) {
        onDispose { cameraProviderRef.value?.unbindAll() }
    }

    var capturing by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var previewBound by remember { mutableStateOf(false) }

    if (!hasPermission) {
        ImportHint(
            title = "需要相机权限",
            detail = "拍照导入需要使用相机。也可以切到「诗词库」直接选篇目。",
            action = "授予权限",
            onAction = { permissionLauncher.launch(Manifest.permission.CAMERA) },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 预览区 (降级: 绑定失败时显示提示, 不阻塞诗词库路径)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .background(InkBlack, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        // 异步绑定: 成功置 previewBound, 失败置 errorMsg 降级
                        try {
                            val future = ProcessCameraProvider.getInstance(ctx)
                            future.addListener({
                                try {
                                    val provider = future.get()
                                    cameraProviderRef.value = provider
                                    val preview = Preview.Builder().build()
                                    preview.setSurfaceProvider(surfaceProvider)
                                    provider.unbindAll()
                                    provider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                        preview,
                                        imageCapture,
                                    )
                                    previewBound = true
                                } catch (e: Exception) {
                                    errorMsg = "相机启动失败，可切到「诗词库」导入"
                                }
                            }, ContextCompat.getMainExecutor(ctx))
                        } catch (_: Exception) {
                            errorMsg = "相机暂不可用，可切到「诗词库」导入"
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (errorMsg != null) {
                Text(errorMsg!!, color = CardWhite, fontSize = 14.sp)
            } else if (!previewBound) {
                Text("相机预览中…", color = CardWhite, fontSize = 14.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                if (capturing) return@Button
                capturing = true
                coroutineScope.launch(Dispatchers.Main) {
                    val raw = captureAndRecognize(context, imageCapture, lifecycleOwner)
                    capturing = false
                    if (raw == null) {
                        Toast.makeText(context, "拍照或识别失败，请重试或改用诗词库", Toast.LENGTH_LONG).show()
                    } else {
                        onOcrResult(raw)
                    }
                }
            },
            enabled = !capturing && previewBound,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = BronzeGold,
                contentColor = InkBlack,
            ),
        ) {
            Text(
                if (capturing) "识别中…" else "拍 照",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            "提示: 将诗文横向拍满画面, 光线充足效果更好。识别后可逐项修改再保存。",
            fontSize = 13.sp,
            color = TagGray,
        )
    }

    // 预览兜底: 长时间未绑定成功且无错误 → 给出降级提示
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(5000)
        if (!previewBound && errorMsg == null && cameraProviderRef.value == null) {
            errorMsg = "相机暂不可用，可切到「诗词库」导入"
        }
    }
}

/**
 * 拍照 → OCR, 全程超时兜底 (拍照 12s + 识别 15s), 失败/超时返回 null 由调用方 Toast 降级。
 * 临时照片用完即删。
 */
private suspend fun captureAndRecognize(
    context: android.content.Context,
    imageCapture: ImageCapture,
    lifecycleOwner: LifecycleOwner,
): String? {
    val file = File(context.cacheDir, "ocr_${System.currentTimeMillis()}.jpg")
    try {
        // 1) 拍照 (12s 超时)
        val uri: Uri? = withTimeoutOrNull(12_000) {
            suspendCancellableCoroutine<Uri?> { cont ->
                val opts = ImageCapture.OutputFileOptions.Builder(file).build()
                imageCapture.takePicture(
                    opts,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            cont.resume(output.savedUri ?: Uri.fromFile(file))
                        }

                        override fun onError(exc: ImageCaptureException) {
                            cont.resume(null)
                        }
                    },
                )
            }
        } ?: return null

        // 2) ML Kit 中文 OCR (15s 超时; 识别器进程内复用)
        val text = withTimeoutOrNull(15_000) {
            withContext(Dispatchers.Default) {
                try {
                    val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                    try {
                        val image = InputImage.fromFilePath(context, uri!!)
                        suspendCancellableCoroutine<String?> { cont ->
                            recognizer.process(image)
                                .addOnSuccessListener { cont.resume(it.text) }
                                .addOnFailureListener { cont.resume(null) }
                        }
                    } finally {
                        recognizer.close()
                    }
                } catch (_: Exception) {
                    null
                }
            }
        } ?: return null
        return text
    } catch (_: Exception) {
        return null
    } finally {
        file.delete()
    }
}

// ─── OCR 确认页: 可编辑 → 保存 ───────────────────────────────────────────

@Composable
private fun ConfirmImport(
    parsed: TextSegmenter.ParsedPoem,
    onCancel: () -> Unit,
    onSave: (BeisongPoem) -> Unit,
) {
    var title by remember { mutableStateOf(parsed.title) }
    var author by remember { mutableStateOf(parsed.author) }
    var dynasty by remember { mutableStateOf(parsed.dynasty) }
    var body by remember { mutableStateOf(parsed.body) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            "识别结果 · 请核对",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = InkBlack,
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("标题") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row {
            OutlinedTextField(
                value = dynasty,
                onValueChange = { dynasty = it },
                label = { Text("朝代") },
                singleLine = true,
                modifier = Modifier.weight(0.4f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedTextField(
                value = author,
                onValueChange = { author = it },
                label = { Text("作者") },
                singleLine = true,
                modifier = Modifier.weight(0.6f),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            label = { Text("正文") },
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
        )

        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            ) { Text("重 拍", color = InkBlackSoft) }
            Button(
                onClick = {
                    val t = body.replace(Regex("\\s"), "")
                    if (t.isBlank()) {
                        return@Button
                    }
                    onSave(
                        BeisongPoem(
                            id = "user-${UUID.randomUUID()}",
                            title = title.ifBlank { "拍照背诵" },
                            author = author.ifBlank { "佚名" },
                            dynasty = dynasty.ifBlank { "" },
                            text = body.trim(),
                            source = "ocr",
                        )
                    )
                },
                enabled = body.replace(Regex("\\s"), "").isNotBlank(),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BronzeGold,
                    contentColor = InkBlack,
                ),
            ) { Text("保存导入", fontWeight = FontWeight.Bold) }
        }
        if (body.replace(Regex("\\s"), "").isBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text("正文为空，无法保存", fontSize = 12.sp, color = InkRed)
        }
    }
}

// ─── 诗词库: 搜索 → 点选导入 ─────────────────────────────────────────────

@Composable
private fun LibraryTab(onImport: (BeisongPoem) -> Unit) {
    val context = LocalContext.current
    val all = remember { PoemLibrary.load(context) }
    var query by remember { mutableStateOf("") }
    val results = remember(query) { PoemLibrary.search(all, query) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜索标题 / 作者 / 诗句") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "共 ${all.size} 首 · 命中 ${results.size} 首",
            fontSize = 12.sp,
            color = TagGray,
        )
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(results) { poem ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onImport(poem) },
                    colors = CardDefaults.cardColors(containerColor = CardWhite),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                poem.title,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = InkBlack,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                buildString {
                                    if (poem.dynasty.isNotBlank()) append("${poem.dynasty}·")
                                    append(poem.author)
                                },
                                fontSize = 13.sp,
                                color = BronzeGoldPressed,
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            poem.text,
                            fontSize = 13.sp,
                            color = InkBlackSoft,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (results.isEmpty()) {
                item {
                    Text(
                        "没有匹配的篇目，试试诗句里的几个字",
                        fontSize = 14.sp,
                        color = TagGray,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
        }
    }
}

// ─── 通用提示块 (权限拒绝 / 相机失败降级) ─────────────────────────────────

@Composable
private fun ImportHint(
    title: String,
    detail: String,
    action: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = InkBlack)
        Spacer(modifier = Modifier.height(8.dp))
        Text(detail, fontSize = 14.sp, color = InkBlackSoft, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(containerColor = BronzeGold, contentColor = InkBlack),
        ) { Text(action) }
    }
}
