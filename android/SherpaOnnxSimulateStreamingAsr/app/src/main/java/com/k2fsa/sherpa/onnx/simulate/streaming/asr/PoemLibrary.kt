package com.k2fsa.sherpa.onnx.simulate.streaming.asr

import android.content.Context
import org.json.JSONObject

/**
 * v0.2 内置诗词库: 从 assets/poems.json 加载, 按标题/作者/正文包含匹配搜索。
 * 纯数据 + 纯函数; assets 读取依赖 Context, 搜索逻辑可 JVM 单测。
 */
object PoemLibrary {

    @Volatile
    private var cache: List<BeisongPoem>? = null

    /** v0.2.1 书库分类 Tab 定义 (展示名, category 前缀; poem 为默认/古诗) */
    val LIBRARY_TABS: List<Pair<String, String>> = listOf(
        "全部" to "",
        "诗词" to "poem",
        "语文" to "yuwen",
        "英语" to "english",
        "单词表" to "words",
    )

    /** 按 Tab 前缀过滤 (category 以 prefix 开头; 空前缀=全部) */
    fun filterCategory(poems: List<BeisongPoem>, prefix: String): List<BeisongPoem> {
        if (prefix.isEmpty()) return poems
        return poems.filter { it.category.startsWith(prefix) }
    }

    /** 从 assets 加载 poems.json + books.json (v0.2.1 新增), 合并缓存 */
    fun load(context: Context): List<BeisongPoem> {
        cache?.let { return it }
        val poems = ArrayList<BeisongPoem>()
        for ((file, defaultCat) in listOf("poems.json" to "poem", "books.json" to "yuwen-4a")) {
            try {
                val json = context.assets.open(file).bufferedReader().use { it.readText() }
                poems.addAll(parsePoemsJson(json, idPrefix = if (file == "poems.json") "lib" else "book", defaultCategory = defaultCat))
            } catch (_: Exception) {
                // books.json 缺失不致命; poems.json 缺失由下方兜底
            }
        }
        if (poems.isEmpty()) {
            val fallback = listOf(BUILTIN_POEMS.first())
            cache = fallback
            return fallback
        }
        cache = poems
        return poems
    }

    /**
     * 包含匹配搜索 (标题/作者/正文), 不区分空白。空查询返回全部。
     * 纯 JVM 函数, TextSegmenterTest 可直接覆盖。
     */
    fun search(poems: List<BeisongPoem>, query: String): List<BeisongPoem> {
        val q = query.replace(Regex("\\s+"), "")
        if (q.isEmpty()) return poems
        return poems.filter {
            it.title.contains(q) || it.author.contains(q) ||
                it.text.replace(Regex("\\s+"), "").contains(q)
        }
    }

    /** 便捷: load + search */
    fun search(context: Context, query: String): List<BeisongPoem> =
        search(load(context), query)

    /** JSON 数组 → 列表 (导出给 JVM 测试用); 缺省字段向后兼容 v0.2 */
    fun parsePoemsJson(
        json: String,
        idPrefix: String = "lib",
        defaultCategory: String = "poem",
    ): List<BeisongPoem> {
        val arr = org.json.JSONArray(json)
        val out = ArrayList<BeisongPoem>(arr.length())
        for (i in 0 until arr.length()) {
            val o: JSONObject = arr.getJSONObject(i)
            out.add(
                BeisongPoem(
                    id = "$idPrefix-${i + 1}",
                    title = o.getString("title"),
                    author = o.optString("author", "佚名"),
                    dynasty = o.optString("dynasty", ""),
                    text = o.getString("text"),
                    source = "library",
                    category = o.optString("category", defaultCategory),
                    lang = o.optString("lang", "zh"),
                )
            )
        }
        return out
    }
}
