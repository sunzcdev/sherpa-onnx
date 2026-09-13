package com.k2fsa.sherpa.onnx.simulate.streaming.asr.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageCapture.Metadata
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.BeisongPoem
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.BeisongPoemStore
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.BronzeGold
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.CardWhite
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.InkBlack
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.InkBlackSoft
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.TagGray
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.TextSegmenter
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
 * v0.2.2 HOTFIX: 拍照导入独立全屏页.
 *
 * 根因: CameraX PreviewView 内嵌 ImportScreen 的 Tab 布局时, 相机 surface
 * 在合成层压过 TabRow (嵌入式预览经典坑), 遮挡标签选择区.
 * 解法: 预览独占全屏路由, 不与任何 Compose 层共存, z-order 问题连根消失.
 *
 * OCR 链路 (captureAndRecognize/ML Kit/确认页) 全部自 ImportScreen 原样迁入,
 * 逻辑零改动; 确认页复用 ImportScreen 的 ConfirmImport。
 */
@Composable
fun CameraScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    // OCR 确认态: 非 null 时页面切换到可编辑确认 (与 v0.2.1 交互一致)
    var confirm by remember { mutableStateOf<TextSegmenter.ParsedPoem?>(null) }

    if (confirm != null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(XuanPaper)
        ) {
            ConfirmImport(
                parsed = confirm!!,
                onCancel = { confirm = null },   // 回取景重拍
                onSave = { poem ->
                    val added = BeisongPoemStore.add(poem)
                    android.widget.Toast.makeText(
                        context,
                        if (added) "已导入「${poem.title}」" else "篇目已存在，已选中",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    onClose()
                },
            )
        }
        return
    }

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
            android.widget.Toast.makeText(context, "需要相机权限才能拍照导入", android.widget.Toast.LENGTH_LONG).show()
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
        // 全屏降级: 权限拒绝
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(XuanPaper)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("需要相机权限", fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = InkBlack)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "拍照导入需要使用相机。可返回改用「书库」直接选篇目。",
                fontSize = 14.sp,
                color = InkBlackSoft,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                colors = ButtonDefaults.buttonColors(containerColor = BronzeGold, contentColor = InkBlack),
            ) { Text("授予权限") }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onClose) { Text("← 返回", color = InkBlackSoft) }
        }
        return
    }

    // ── 全屏取景器 ──
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(InkBlack)
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
                                errorMsg = "相机启动失败，请返回改用「书库」导入"
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                    } catch (_: Exception) {
                        errorMsg = "相机暂不可用，请返回改用「书库」导入"
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // 顶栏: 返回 (半透明浮层, 取景器之上)
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
        ) {
            TextButton(onClick = onClose) {
                Text("← 返回", color = CardWhite, fontSize = 15.sp)
            }
        }

        when {
            errorMsg != null -> Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(errorMsg!!, color = CardWhite, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onClose,
                        colors = ButtonDefaults.buttonColors(containerColor = BronzeGold, contentColor = InkBlack),
                    ) { Text("返回导入页") }
                }
            }
            !previewBound -> Box(
                modifier = Modifier.align(Alignment.Center),
                contentAlignment = Alignment.Center,
            ) {
                Text("相机启动中…", color = CardWhite, fontSize = 14.sp)
            }
        }

        // 底部操作区: 取景提示 + 大快门键
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "将课本页对准取景框 · 光线充足效果更好",
                fontSize = 13.sp,
                color = CardWhite,
            )
            Spacer(modifier = Modifier.height(14.dp))
            Button(
                onClick = {
                    if (capturing) return@Button
                    capturing = true
                    coroutineScope.launch(Dispatchers.Main) {
                        val raw = captureAndRecognize(context, imageCapture, lifecycleOwner)
                        capturing = false
                        if (raw == null) {
                            android.widget.Toast.makeText(context, "拍照或识别失败，请重试或改用书库", android.widget.Toast.LENGTH_LONG).show()
                        } else if (raw.isBlank()) {
                            android.widget.Toast.makeText(context, "未识别到文字，请对准诗文重试", android.widget.Toast.LENGTH_LONG).show()
                        } else {
                            confirm = TextSegmenter.parsePoem(raw)
                        }
                    }
                },
                enabled = !capturing && previewBound,
                modifier = Modifier
                    .width(220.dp)
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BronzeGold,
                    contentColor = InkBlack,
                ),
            ) {
                Text(
                    if (capturing) "识别中…" else "拍 照",
                    fontSize = 18.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                )
            }
        }
    }

    // 预览兜底: 长时间未绑定成功且无错误 → 给出降级提示 (redroid 无 camera provider 走此路)
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(5000)
        if (!previewBound && errorMsg == null && cameraProviderRef.value == null) {
            errorMsg = "相机暂不可用，请返回改用「书库」导入"
        }
    }
}

/**
 * 拍照 → OCR, 全程超时兜底 (拍照 12s + 识别 15s), 失败/超时返回 null 由调用方 Toast 降级。
 * 临时照片用完即删。(自 ImportScreen 原样迁入, 逻辑零改动)
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
