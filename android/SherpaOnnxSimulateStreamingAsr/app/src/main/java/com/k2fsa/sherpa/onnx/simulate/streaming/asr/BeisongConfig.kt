package com.k2fsa.sherpa.onnx.simulate.streaming.asr

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 背诵篇目 (v0.2: 内置示例 + 用户导入, 动态列表)
 * - 内置: 静夜思 (保底, 不可删)
 * - 用户导入: OCR 拍照 / 诗词库搜索, 持久化到 SharedPreferences
 */
data class BeisongPoem(
    val id: String,
    val title: String,
    val author: String,
    val dynasty: String,
    val text: String,
    val source: String = "builtin", // builtin | ocr | library
    // v0.2.1 书库分类: poem(古诗) | yuwen-4a(四上语文) | english-4a(四上英语课文) | words-4a(四上单词表)
    val category: String = "poem",
    val lang: String = "zh", // zh | en — 决定 ASR 模型与 normalize 大小写策略
)

/** v0.1 兼容: 内置篇目常量 (JVM 单测/instrumented 测试仍引用 BEISONG_TARGET_TEXT) */
val BUILTIN_POEMS = listOf(
    BeisongPoem(
        id = "builtin-jingyesi",
        title = "静夜思",
        author = "李白",
        dynasty = "唐",
        text = "床前明月光，疑是地上霜。举头望明月，低头思故乡。",
    ),
)

/** v0.1 兼容旧引用 */
val BEISONG_TARGET_TEXT: String get() = BUILTIN_POEMS.first().text

/**
 * 篇目全局状态: 列表 + 选中项, SharedPreferences 持久化 (org.json 手写解析,
 * 与现有 BeisongConfig 硬编码方案同栈, 不引入 kotlinx-serialization)
 */
object BeisongPoemStore {
    private const val PREFS = "beisong_poems"
    private const val KEY_USER = "user_poems"
    private const val KEY_SELECTED = "selected_id"
    private const val KEY_FIRST_RUN = "seeded"

    private val _poems = MutableStateFlow(BUILTIN_POEMS)
    val poems: StateFlow<List<BeisongPoem>> = _poems.asStateFlow()

    private val _selectedId = MutableStateFlow(BUILTIN_POEMS.first().id)
    val selectedId: StateFlow<String> = _selectedId.asStateFlow()

    private var appContext: Context? = null
    private val prefs get() = appContext!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 在 Application/Activity onCreate 调用一次 */
    fun init(context: Context) {
        appContext = context.applicationContext
        load()
    }

    val selected: BeisongPoem
        get() = _poems.value.firstOrNull { it.id == _selectedId.value }
            ?: _poems.value.first()

    fun select(id: String) {
        _selectedId.value = id
        prefs.edit().putString(KEY_SELECTED, id).apply()
    }

    /** 添加新篇目并选中; 同标题+作者去重 (已存在则只选中)。返回是否真正新增 */
    fun add(poem: BeisongPoem): Boolean {
        val dup = _poems.value.firstOrNull {
            it.title == poem.title && it.author == poem.author
        }
        if (dup != null) {
            select(dup.id)
            return false
        }
        _poems.value = _poems.value + poem
        select(poem.id)
        persistUserPoems()
        return true
    }

    /** 删除; 内置篇目不可删。被删的是选中项时自动选中第一篇 */
    fun remove(id: String): Boolean {
        if (id.startsWith("builtin-")) return false
        val next = _poems.value.filterNot { it.id == id }
        if (next.size == _poems.value.size) return false
        _poems.value = next
        if (_selectedId.value == id) select(next.first().id)
        persistUserPoems()
        return true
    }

    private fun load() {
        try {
            // 首次运行播种默认选中, 避免每次启动读空
            if (!prefs.getBoolean(KEY_FIRST_RUN, false)) {
                prefs.edit().putBoolean(KEY_FIRST_RUN, true).apply()
                return
            }
            val userArr = JSONArray(prefs.getString(KEY_USER, "[]") ?: "[]")
            val user = (0 until userArr.length()).mapNotNull { i ->
                val o = userArr.getJSONObject(i)
                if (!o.has("title") || !o.has("text")) return@mapNotNull null
                BeisongPoem(
                    id = o.optString("id"),
                    title = o.getString("title"),
                    author = o.optString("author"),
                    dynasty = o.optString("dynasty"),
                    text = o.getString("text"),
                    source = o.optString("source", "ocr"),
                    category = o.optString("category", "poem"),
                    lang = o.optString("lang", "zh"),
                )
            }
            _poems.value = BUILTIN_POEMS + user
            val sel = prefs.getString(KEY_SELECTED, null)
            if (sel != null && _poems.value.any { it.id == sel }) {
                _selectedId.value = sel
            }
        } catch (_: Exception) {
            // 损坏的持久化数据不阻塞启动, 回退内置篇目
        }
    }

    private fun persistUserPoems() {
        try {
            val user = _poems.value.filterNot { it.id.startsWith("builtin-") }
            val arr = JSONArray()
            for (p in user) {
                arr.put(
                    JSONObject()
                        .put("id", p.id)
                        .put("title", p.title)
                        .put("author", p.author)
                        .put("dynasty", p.dynasty)
                        .put("text", p.text)
                        .put("source", p.source)
                        .put("category", p.category)
                        .put("lang", p.lang)
                )
            }
            prefs.edit().putString(KEY_USER, arr.toString()).apply()
        } catch (_: Exception) {
        }
    }
}
