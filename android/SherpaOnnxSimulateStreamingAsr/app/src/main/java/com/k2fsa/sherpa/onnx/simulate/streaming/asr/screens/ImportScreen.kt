package com.k2fsa.sherpa.onnx.simulate.streaming.asr.screens

import android.widget.Toast
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.BeisongPoem
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.BeisongPoemStore
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.PoemLibrary
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.TextSegmenter
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.BronzeGold
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.BronzeGoldPressed
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.CardWhite
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.InkBlack
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.InkBlackSoft
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.InkRed
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.TagGray
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.XuanPaper
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * v0.2 内容导入: 「拍照导入」(CameraX + ML Kit 中文 OCR) / 「书库」(内置 90+ 首)
 * 两条路径最终都落到 BeisongPoemStore.add() — 与背诵页共享同一篇目状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(onClose: () -> Unit, onOpenCamera: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(0) }

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
                onClick = { tab = 0 },
                text = { Text("拍照导入", fontSize = 15.sp) },
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text("书 库", fontSize = 15.sp) },
            )
        }

        when (tab) {
            // v0.2.2: 相机预览搬独立全屏页 (CameraScreen), 此处纯入口 —
            // 修复 PreviewView 内嵌 Tab 布局遮挡标签区 (surface z-order)
            0 -> CameraEntry(
                onOpenCamera = onOpenCamera,
                onPickFromLibrary = { tab = 1 },
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

// ─── 拍照导入入口 (v0.2.2: 预览在独立全屏页, 此处纯引导) ─────────────────

@Composable
private fun CameraEntry(onOpenCamera: () -> Unit, onPickFromLibrary: () -> Unit) {
    Column(
        modifier = Modifier
            // fillMaxHeight: Column 子项约束=剩余屏高 (此前 CameraEntry 内容
            // 垂直居中用 Center + 空间估计不足, 次按钮被底栏裁掉)
            .fillMaxWidth()
            .fillMaxHeight(0.999f)
            .verticalScroll(rememberScrollState())   // 小屏防溢出 (redroid 720p 实测按钮被裁)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "拍照导入课本",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = InkBlack,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "全屏取景 · 对准书页拍照 · 识别后可核对修改再导入。\n现代文篇目走这条路, 内置书库只收公版内容。",
            fontSize = 14.sp,
            color = InkBlackSoft,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = onOpenCamera,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BronzeGold, contentColor = InkBlack),
        ) { Text("打开相机", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = onPickFromLibrary,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("改用书库选篇目", color = InkBlackSoft) }
    }
}

// ─── OCR 确认页: 可编辑 → 保存 ───────────────────────────────────────────

@Composable
fun ConfirmImport(
    parsed: TextSegmenter.ParsedPoem,
    onCancel: () -> Unit,
    onSave: (BeisongPoem) -> Unit,
) {
    var title by remember { mutableStateOf(parsed.title) }
    var author by remember { mutableStateOf(parsed.author) }
    var dynasty by remember { mutableStateOf(parsed.dynasty) }
    var body by remember { mutableStateOf(parsed.body) }
    // R1.5: 清洗有改动时默认展示 diff，用户可展开/收起
    val diffLines = remember { if (parsed.raw.isNotBlank()) TextSegmenter.diff(parsed.raw) else emptyList() }
    var showDiff by remember { mutableStateOf(diffLines.size > 1) }

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

        if (diffLines.size > 1) {
            TextButton(
                onClick = { showDiff = !showDiff },
            ) {
                Text(
                    if (showDiff) "收起原文对照" else "查看原文对照 (${diffLines.count { it.kind == "raw" }} 处清洗)",
                    fontSize = 13.sp,
                    color = BronzeGoldPressed,
                )
            }
            if (showDiff) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardWhite),
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        diffLines.forEach { d ->
                            when (d.kind) {
                                "raw" -> Text("− ${d.text}", fontSize = 12.sp, color = InkRed)
                                "clean" -> Text("+ ${d.text}", fontSize = 12.sp, color = InkBlackSoft)
                                else -> Text("  ${d.text}", fontSize = 12.sp, color = TagGray)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
        }

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
                            // 自动判定语言: 拉丁字母占比 >60% → en (英文课本拍照走 whisper 评分)
                            lang = TextSegmenter.detectLang(body),
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

// ─── 书库: 搜索 → 点选导入 ─────────────────────────────────────────────

@Composable
private fun LibraryTab(onImport: (BeisongPoem) -> Unit) {
    val context = LocalContext.current
    val all = remember { PoemLibrary.load(context) }
    var query by remember { mutableStateOf("") }
    var catIdx by remember { mutableStateOf(0) }
    val catPrefix = PoemLibrary.LIBRARY_TABS[catIdx].second
    val scoped = remember(catPrefix) { PoemLibrary.filterCategory(all, catPrefix) }
    val results = remember(query, scoped) { PoemLibrary.search(scoped, query) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // v0.2.1 分类过滤 chips: 全部/诗词/语文/英语/单词表
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PoemLibrary.LIBRARY_TABS.forEachIndexed { i, (label, _) ->
                val sel = i == catIdx
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (sel) BronzeGold else CardWhite)
                        .border(1.dp, if (sel) BronzeGold else TagGray, RoundedCornerShape(14.dp))
                        .clickable { catIdx = i }
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                ) {
                    Text(label, fontSize = 13.sp, color = if (sel) InkBlack else InkBlackSoft)
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜索标题 / 作者 / 正文") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "共 ${scoped.size} 篇 · 命中 ${results.size} 篇",
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

