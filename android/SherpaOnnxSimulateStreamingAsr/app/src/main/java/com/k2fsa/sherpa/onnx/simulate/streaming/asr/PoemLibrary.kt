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

    /** 从 assets/poems.json 加载 (进程内缓存); 失败回退内置静夜思 */
    fun load(context: Context): List<BeisongPoem> {
        cache?.let { return it }
        return try {
            val json = context.assets.open("poems.json").bufferedReader().use { it.readText() }
            val arr = org.json.JSONArray(json)
            val poems = ArrayList<BeisongPoem>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                poems.add(
                    BeisongPoem(
                        id = "lib-${i + 1}",
                        title = o.getString("title"),
                        author = o.optString("author", "佚名"),
                        dynasty = o.optString("dynasty", ""),
                        text = o.getString("text"),
                        source = "library",
                    )
                )
            }
            if (poems.isEmpty()) throw IllegalStateException("empty poems.json")
            cache = poems
            poems
        } catch (_: Exception) {
            // assets 损坏不阻塞: 回退内置篇目
            val fallback = listOf(BUILTIN_POEMS.first())
            cache = fallback
            fallback
        }
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

    /** JSON 数组 → 列表 (导出给 JVM 测试用) */
    fun parsePoemsJson(json: String): List<BeisongPoem> {
        val arr = org.json.JSONArray(json)
        val out = ArrayList<BeisongPoem>(arr.length())
        for (i in 0 until arr.length()) {
            val o: JSONObject = arr.getJSONObject(i)
            out.add(
                BeisongPoem(
                    id = "lib-${i + 1}",
                    title = o.getString("title"),
                    author = o.optString("author", "佚名"),
                    dynasty = o.optString("dynasty", ""),
                    text = o.getString("text"),
                    source = "library",
                )
            )
        }
        return out
    }
}
