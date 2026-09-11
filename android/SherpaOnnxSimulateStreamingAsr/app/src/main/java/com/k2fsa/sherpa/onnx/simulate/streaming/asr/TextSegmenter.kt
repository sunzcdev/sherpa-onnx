package com.k2fsa.sherpa.onnx.simulate.streaming.asr

/**
 * v0.2 断句/清洗: OCR 识别出的原始文本 → 背诵单元
 * 纯 Kotlin, JVM 可单测。
 *
 * 设计要点 (与 BeisongDiagnose 对齐):
 * - 背诵单元保留标点用于展示; 诊断时 normalize() 会剥掉标点, 不影响评分
 * - 汉字一行一般 20~28 字, OCR 行断点噪声大, 不信任原始换行, 全文重新断句
 */
object TextSegmenter {

    /** 单元目标长度 (字符数): 约 1~2 个五言句 + 标点 */
    const val DEFAULT_MAX_CHARS = 24

    /** 诊断算法的单段上限: 单元长度不要超过它, 否则 LCS 对齐误差放大 */
    private const val HARD_MAX_CHARS = 36

    private val sentenceEndChars = charArrayOf('。', '！', '？', '；', '!', '?', ';', '…')

    /** 清洗: 统一空白 / 去 OCR 行号页码 / 去竖排装饰线, 保留标点与换行结构 */
    fun clean(raw: String): String {
        if (raw.isBlank()) return ""
        val out = raw
            .replace('\u00A0', ' ')      // nbsp
            .replace('\u3000', ' ')      // 全角空格
            .replace(Regex("[|｜丨¦﹂]+"), "") // 竖排装饰/分隔线
            .lines()
            .map { line ->
                // 去 "1." "12、" "页 3/12" 之类行首行尾噪声
                line.trim()
                    .replace(Regex("^[0-9０-９]{1,3}[.、．]\\s*"), "")
                    .replace(Regex("^\\[[0-9]{1,3}]\\s*"), "")
                    .replace(Regex("\\s*第\\s*[0-9０-９]+\\s*页.*$"), "")
            }
            .filter { it.isNotBlank() }
        return out.joinToString("\n")
    }

    /**
     * 按句末标点切句。标点归属句尾 (保留原文标点用于展示)。
     * 无标点兜底: 每 [fallbackLen] 字硬切 (OCR 拍到的无标点竖排文本)。
     */
    fun splitSentences(text: String, fallbackLen: Int = 20): List<String> {
        val flat = text.replace("\n", " ").replace(" ", "").replace("\u3000", "")
        if (flat.isEmpty()) return emptyList()

        val sentences = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in flat) {
            sb.append(ch)
            if (ch in sentenceEndChars) {
                sentences.add(sb.toString())
                sb.clear()
            }
        }
        if (sb.isNotEmpty()) sentences.add(sb.toString())

        // 无标点长句再切
        val result = mutableListOf<String>()
        for (s in sentences) {
            if (s.length > fallbackLen && s.none { it in sentenceEndChars }) {
                var i = 0
                while (i < s.length) {
                    result.add(s.substring(i, (i + fallbackLen).coerceAtMost(s.length)))
                    i += fallbackLen
                }
            } else {
                result.add(s)
            }
        }
        return result.filter { it.isNotBlank() }
    }

    /**
     * 断句成背诵单元: 相邻短句合并 (≤maxChars), 过长单元再按无标点位置微切。
     * 例: "床前明月光，疑是地上霜。" → 1 个单元; 8 句律诗 → 3~4 个单元。
     */
    fun toUnits(text: String, maxChars: Int = DEFAULT_MAX_CHARS): List<String> {
        val sentences = splitSentences(text)
        if (sentences.isEmpty()) return emptyList()

        val units = mutableListOf<String>()
        val sb = StringBuilder()
        for (s in sentences) {
            if (sb.isNotEmpty() && sb.length + s.length > maxChars) {
                units.add(sb.toString())
                sb.clear()
            }
            sb.append(s)
            if (sb.length >= maxChars) {
                units.add(sb.toString())
                sb.clear()
            }
        }
        if (sb.isNotEmpty()) units.add(sb.toString())

        // 超硬上限的单元 (如整页无标点) 再按等长切
        val final = mutableListOf<String>()
        for (u in units) {
            if (u.length <= HARD_MAX_CHARS) {
                final.add(u)
            } else {
                var i = 0
                while (i < u.length) {
                    final.add(u.substring(i, (i + maxChars).coerceAtMost(u.length)))
                    i += maxChars
                }
            }
        }
        return final
    }

    // ─── 标题/朝代/作者启发式 ──────────────────────────────────────

    private val dynastyChars =
        "汉魏晋南北朝隋唐五代宋辽金元明清朝民国近代现代当代"

    /** "唐·李白" / "【唐】李白" / "（唐）李白" / "唐 李白" */
    private val authorLineRegex = Regex(
        """^\s*[\[〔【（(（]?\s*([汉魏晋南北朝隋唐五代宋辽金元明清]{1,4}|近代|现代|当代|民国|近现代)\s*[\]〕】)）]?\s*[·•:：\s]\s*([\u4e00-\u9fff]{1,8})\s*[\]〕】)）]?\s*$"""
    )

    /** 无朝代前缀的纯人名行, 如 "李白" (2~4 字, 无标点) */
    private val bareAuthorRegex = Regex("""^\s*([\u4e00-\u9fff]{2,4})\s*$""")

    /** 词牌/题目带 "·" 的行, 如 "水调歌头·明月几时有" */
    private val titleWithDotRegex = Regex("""^\s*([\u4e00-\u9fff]{1,12})\s*[·•]\s*([\u4e00-\u9fff]{1,20})\s*$""")

    /**
     * OCR 文本 → (标题, 作者, 朝代, 正文)。
     * 规则:
     *  - 首行: "静夜思" → 标题; "水调歌头·明月几时有" → 词牌+副题
     *  - 次行: 命中 authorLineRegex / bareAuthorRegex → 作者行
     *  - 其余行为正文
     *  - 单行文本无启发式: 以行首 N 字为标题 (编辑可改)
     */
    fun parsePoem(raw: String): ParsedPoem {
        val cleaned = clean(raw)
        val lines = cleaned.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return ParsedPoem("", "", "", cleaned)

        var title = ""
        var author = ""
        var dynasty = ""
        var bodyStart = 0

        // 标题行
        val first = lines[0]
        if (first.length <= 20) {
            val dotMatch = titleWithDotRegex.matchEntire(first)
            if (dotMatch != null) {
                title = first
            } else {
                title = first.removeSuffix("。").trim()
            }
            bodyStart = 1
        }

        // 作者行 (紧跟标题)
        if (lines.size > bodyStart) {
            val second = lines[bodyStart]
            val m = authorLineRegex.matchEntire(second)
            when {
                m != null -> {
                    dynasty = m.groupValues[1]
                    author = m.groupValues[2]
                    bodyStart += 1
                }
                bareAuthorRegex.matches(second) && second.length <= 4 -> {
                    author = bareAuthorRegex.matchEntire(second)!!.groupValues[1]
                    bodyStart += 1
                }
            }
        }

        // 单行兜底: 全文当正文, 标题取前 12 字
        if (lines.size == 1 && title.isEmpty()) {
            title = first.take(12)
            return ParsedPoem(title, author, dynasty, cleaned)
        }

        val body = lines.drop(bodyStart).joinToString("")
        return ParsedPoem(title, author, dynasty, body.ifBlank { cleaned })
    }

    data class ParsedPoem(
        val title: String,
        val author: String,
        val dynasty: String,
        val body: String,
    )
}
