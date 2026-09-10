package com.k2fsa.sherpa.onnx.simulate.streaming.asr

/**
 * 背诵目标篇目 (MVP: 硬编码演示篇目, FR-001)
 * TODO: 后续版本从 UI 选择/导入篇目 (SRD 非范围)
 */
data class BeisongPoem(val title: String, val author: String, val dynasty: String, val text: String)

val BEISONG_POEMS = listOf(
    BeisongPoem(
        title = "静夜思",
        author = "李白",
        dynasty = "唐",
        text = "床前明月光，疑是地上霜。举头望明月，低头思故乡。",
    ),
)

// 兼容旧引用
val BEISONG_TARGET_TEXT: String get() = BEISONG_POEMS.first().text
