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

    // v0.2.1 R3.3: 补半角 '.' — 英文句号 (原集合只有全角'。', 英文整句不切)
    private val sentenceEndChars = charArrayOf('。', '！', '？', '；', '!', '?', ';', '…', '.')

    /**
     * v0.2.1 R1 清洗: 统一空白 / 去 OCR 行号页码 / 去竖排装饰线 /
     * 连续重复行去重 / 全半角标点统一 / 非中文噪声过滤。
     */
    fun clean(raw: String): String {
        if (raw.isBlank()) return ""
        val stage1 = raw
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
        // R1.4 杂符过滤 → R1.3 标点统一 → R1.1 英文形近字
        val stage2 = stage1
            .map { l -> l.filter { keepChar(it) } }
            .map { normalizePunct(it) }
            .map { fixOcrLookalikes(it) }
        // R1.2: 连续相同行去重 (OCR 双采样/跨栏常见)
        return stage2
            .filterIndexed { i, l -> i == 0 || l != stage2[i - 1] }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    private fun keepChar(c: Char): Boolean =
        c.code in 0x4E00..0x9FFF ||            // CJK
            c.code in 0x3000..0x303F ||        // CJK 标点
            c.code in 0xFF01..0xFF65 ||        // 全角
            c.isLetterOrDigit() ||
            c in "，。！？；：、·—…\"'“”‘’,.!?;:%()-–— " ||
            c == '\n'

    /** R1.3: 中文语境标点统一 (全角逗号句号为主, 英文 token 内保留半角) */
    fun normalizePunct(line: String): String {
        // 行内含 ≥2 个拉丁单词 → 视为英文行, 标点保持半角
        val latinWords = Regex("[A-Za-z]{2,}").findAll(line).count()
        val cjk = Regex("[\\u4E00-\\u9FFF]").containsMatchIn(line)
        if (latinWords >= 2 && !cjk) {
            return line.replace('，', ',').replace('。', '.').replace('？', '?').replace('！', '!')
                .replace('；', ';').replace('：', ':')
        }
        // 中文行: 半角标点后跟/前是汉字 → 转全角 (保护数字小数点)
        var s = line
        s = s.replace(Regex(",(?=[\\u4E00-\\u9FFF])"), "，")
        s = s.replace(Regex("(?<=[\\u4E00-\\u9FFF]),"), "，")
        s = s.replace(Regex("\\.(?=[\\u4E00-\\u9FFF])"), "。")
        s = s.replace(Regex("(?<=[\\u4E00-\\u9FFF])\\.(?![0-9])"), "。")
        s = s.replace(Regex("!(?=[\\u4E00-\\u9FFF])"), "！")
        s = s.replace(Regex("(?<=[\\u4E00-\\u9FFF])!"), "！")
        s = s.replace(Regex("\\?(?=[\\u4E00-\\u9FFF])"), "？")
        s = s.replace(Regex("(?<=[\\u4E00-\\u9FFF])\\?"), "？")
        return s
    }

    /**
     * R1.1: 确定性 OCR 置换错字修复 (英文常见形近, 只在纯英文单词内生效;
     * 中文 己/已/巳 歧义大不做, 依赖确认页人工编辑)。
     */
    fun fixOcrLookalikes(line: String): String {
        val latinWords = Regex("[A-Za-z]{2,}").findAll(line).count()
        val cjk = Regex("[\\u4E00-\\u9FFF]").containsMatchIn(line)
        if (latinWords < 2 || cjk) return line // 仅英文行
        return line
            .replace(Regex("\\b0(?=[a-z])"), "o")   // 0pen -> open (词中0)
            .replace(Regex("(?<=[A-Za-z])l(?=[a-z])"), "l") // 保留: l 合法, 不做歧义替换
    }

    /** R1.5: 原文 → 清洗后 的行级 diff (确认页展示) */
    data class DiffLine(val kind: String, val text: String) // "same" | "raw" | "clean"

    /** 语言判定: 拉丁字母占比 (非空白字符中) >60% → "en", 否则 "zh" */
    fun detectLang(text: String): String {
        val meaningful = text.filter { !it.isWhitespace() }
        if (meaningful.isEmpty()) return "zh"
        val latin = meaningful.count { it.code in 'a'.code..'z'.code || it.code in 'A'.code..'Z'.code }
        return if (latin.toFloat() / meaningful.length > 0.6f) "en" else "zh"
    }

    fun diff(raw: String): List<DiffLine> {
        val cleaned = clean(raw)
        if (raw.trim() == cleaned.trim()) {
            return listOf(DiffLine("same", cleaned))
        }
        val rawLines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val cleanLines = cleaned.lines().filter { it.isNotBlank() }
        val out = ArrayList<DiffLine>()
        if (rawLines.size == cleanLines.size) {
            for (i in rawLines.indices) {
                if (rawLines[i] == cleanLines[i]) out.add(DiffLine("same", cleanLines[i]))
                else { out.add(DiffLine("raw", rawLines[i])); out.add(DiffLine("clean", cleanLines[i])) }
            }
        } else {
            rawLines.forEach { out.add(DiffLine("raw", it)) }
            out.add(DiffLine("clean", "—— 清洗后 ——"))
            cleanLines.forEach { out.add(DiffLine("clean", it)) }
        }
        return out
    }

    /**
     * 按句末标点切句。标点归属句尾 (保留原文标点用于展示)。
     * 无标点兜底: 每 [fallbackLen] 字硬切 (OCR 拍到的无标点竖排文本)。
     * v0.2.1 R3.3: 英文文本保留词间空格 (旧实现全删, 英文单词会粘连);
     * 英文兜底硬切落到词边界, 不从单词中间切。
     */
    fun splitSentences(text: String, fallbackLen: Int = 20): List<String> {
        val isEn = detectLang(text) == "en"
        val flat = if (isEn) {
            text.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
        } else {
            text.replace("\n", " ").replace(" ", "").replace("\u3000", "")
        }
        if (flat.isEmpty()) return emptyList()

        val sentences = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in flat) {
            sb.append(ch)
            if (ch in sentenceEndChars) {
                val s = sb.toString().trim()
                if (s.isNotEmpty()) sentences.add(s)
                sb.clear()
            }
        }
        if (sb.toString().trim().isNotEmpty()) sentences.add(sb.toString().trim())

        // 无标点长句再切
        val result = mutableListOf<String>()
        for (s in sentences) {
            if (s.length > fallbackLen && s.none { it in sentenceEndChars }) {
                var i = 0
                while (i < s.length) {
                    var end = (i + fallbackLen).coerceAtMost(s.length)
                    if (isEn && end < s.length) {
                        // 回退到词边界 (至少前进 1 字, 防空格死循环)
                        while (end > i + 1 && s[end - 1] != ' ') end--
                        if (end <= i + 1) end = (i + fallbackLen).coerceAtMost(s.length)
                    }
                    result.add(s.substring(i, end).trim())
                    i = end
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
        val isEn = detectLang(text) == "en"

        val units = mutableListOf<String>()
        val sb = StringBuilder()
        for (s in sentences) {
            if (sb.isNotEmpty() && sb.length + s.length > maxChars) {
                units.add(sb.toString().trim())
                sb.clear()
            }
            // 英文兜底切块 trim 后拼接需补词间空格 (标点句自带前导空格, 不重复加)
            if (isEn && sb.isNotEmpty() && s.isNotEmpty() && s[0] != ' ') sb.append(' ')
            sb.append(s)
            if (sb.length >= maxChars) {
                units.add(sb.toString().trim())
                sb.clear()
            }
        }
        if (sb.isNotEmpty()) units.add(sb.toString().trim())

        // 超硬上限的单元 (如整页无标点) 再按等长切; 英文落词边界
        val final = mutableListOf<String>()
        for (u in units) {
            if (u.length <= HARD_MAX_CHARS) {
                final.add(u)
            } else {
                val isEn = detectLang(u) == "en"
                var i = 0
                while (i < u.length) {
                    var end = (i + maxChars).coerceAtMost(u.length)
                    if (isEn && end < u.length) {
                        while (end > i + 1 && u[end - 1] != ' ') end--
                        if (end <= i + 1) end = (i + maxChars).coerceAtMost(u.length)
                    }
                    final.add(u.substring(i, end).trim())
                    i = end
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
        if (lines.isEmpty()) return ParsedPoem("", "", "", cleaned, raw)

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
            return ParsedPoem(title, author, dynasty, cleaned, raw)
        }

        val body = lines.drop(bodyStart).joinToString("")
        return ParsedPoem(title, author, dynasty, body.ifBlank { cleaned }, raw)
    }

    data class ParsedPoem(
        val title: String,
        val author: String,
        val dynasty: String,
        val body: String,
        val raw: String = "",   // v0.2.1 R1.5: 原始 OCR 文本, 确认页 diff 对照用
    )
}
