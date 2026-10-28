package com.splashskip.core.gkd

import com.splashskip.core.model.*

/** 选择器解析失败。消息里带上出错位置。 */
class GkdParseException(message: String) : Exception(message)

/**
 * GKD 选择器解析器。
 *
 * 递归下降，直接在字符流上走，不单独做词法分析 ——
 * 因为这套语法里 `[` `]`、`(` `)`、引号各自成域，切词反而容易出错。
 *
 * 语法（来自 gkd.li/guide/selector）：
 *
 *   选择器     = 属性选择器 (关系算符 属性选择器)*
 *   属性选择器 = '@'? 类名? ('[' 表达式 ']')*
 *   表达式     = 或表达式
 *   或表达式   = 与表达式 ('||' 与表达式)*
 *   与表达式   = 一元表达式 ('&&' 一元表达式)*
 *   一元表达式 = '!' '(' 表达式 ')' | 比较表达式
 *   比较表达式 = 值表达式 比较运算符 值表达式
 *   值表达式   = 字面量 | 标识符 ('.' 标识符)* | 标识符 '(' 参数* ')'
 *
 * 两个实现上的取舍：
 *   · 官方要求属性选择器与关系算符之间必须有空白，这里放宽成「可以没有」，
 *     少一类因为作者手误而整条规则失效的情况；
 *   · 顶层逻辑表达式 `(A + B) || (C + B)` 不支持 —— 实测 4000+ 条真实规则里
 *     没有一条用到，而它会让 AST 从「一条链」变成「表达式树」，匹配器复杂度翻倍。
 *     遇到这种写法会解析失败，该规则被跳过并记告警，不影响整包订阅。
 */
object GkdSelectorParser {

    fun parse(source: String): GkdSelector = SelectorParser(source).parseSelector()

    /**
     * 解析一条可能含顶层 `||` 的选择器。
     *
     * GKD 允许 `(A) || (B)` 表示两条链任一命中即可（订阅里实测 5 处）。
     * 本项目的 AST 是「一条链」，所以这里按顶层 `||` 拆成多条独立的链，
     * 由调用方展开成多个 step —— 引擎本来就是「按顺序尝试、第一个命中生效」，
     * 天然就是 OR 语义，不需要把 AST 改成表达式树。
     *
     * 拆分只认括号深度为 0 的 `||`，`[text="a" || text="b"]` 这种属性级
     * 的或仍由 parseExpr 处理，不会被切碎。
     */
    fun parseAll(source: String): List<GkdSelector> {
        val parts = splitTopLevelOr(source)
        if (parts.isEmpty()) throw GkdParseException("选择器为空")
        return parts.map { parse(stripOuterParens(it)) }
    }

    /** 解析失败时返回 null，供「尽力而为」的批量场景使用。 */
    fun parseOrNull(source: String): GkdSelector? = runCatching { parse(source) }.getOrNull()

    /** 同上，但支持顶层 `||`。 */
    fun parseAllOrNull(source: String): List<GkdSelector>? =
        runCatching { parseAll(source) }.getOrNull()

    /**
     * 按顶层 `||` 切分。字符串字面量与 `[...]` / `(...)` 内部的 `||` 不切。
     */
    private fun splitTopLevelOr(source: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var quote: Char? = null
        var start = 0
        var i = 0
        while (i < source.length) {
            val c = source[i]
            if (quote != null) {
                when {
                    c == '\\' -> i++
                    c == quote -> quote = null
                }
                i++
                continue
            }
            when (c) {
                '\'', '"', '`' -> quote = c
                '(', '[' -> depth++
                ')', ']' -> if (depth > 0) depth--
                '|' -> if (depth == 0 && i + 1 < source.length && source[i + 1] == '|') {
                    out.add(source.substring(start, i))
                    i += 2
                    start = i
                    continue
                }
            }
            i++
        }
        out.add(source.substring(start))
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * 去掉整体包裹的括号：`([a] > [b])` → `[a] > [b]`。
     * 只去掉「首尾配对、且中间没有提前闭合」的那种，`([a]) > ([b])` 不动。
     */
    private fun stripOuterParens(raw: String): String {
        var t = raw.trim()
        while (t.length >= 2 && t.first() == '(' && t.last() == ')' && wrapsWhole(t)) {
            t = t.substring(1, t.length - 1).trim()
        }
        return t
    }

    private fun wrapsWhole(t: String): Boolean {
        var depth = 0
        var quote: Char? = null
        for (i in t.indices) {
            val c = t[i]
            if (quote != null) {
                if (c == '\\') continue
                if (c == quote) quote = null
                continue
            }
            when (c) {
                '\'', '"', '`' -> quote = c
                '(', '[' -> depth++
                ')', ']' -> {
                    depth--
                    // 第一个字符的配平括号出现在末尾之前 → 不是整体包裹
                    if (depth == 0 && i != t.lastIndex) return false
                }
            }
        }
        return depth == 0
    }

    private class SelectorParser(private val s: String) {
        private var i = 0
        private var targetIndex = -1

        fun parseSelector(): GkdSelector {
            skipWs()
            if (atEnd()) fail("选择器为空")

            val chain = mutableListOf<GkdAttr>()
            val relations = mutableListOf<GkdRelation>()

            while (true) {
                chain.add(parseAttr(chain.size))
                skipWs()
                val op = peekRelationOp()
                if (op == null) {
                    // 省略关系算符时按官方简写规则当作 `>n`：
                    // `A B` ≡ `A >n B`（A 是 B 的任意深度祖先）。
                    // 订阅里 `ViewFactoryHolder FrameLayout[...]` 这种写法很常见，
                    // 不认就会整条丢掉。
                    if (looksLikeAttrStart()) {
                        relations.add(
                            GkdRelation(
                                GkdRelOp.ANCESTOR,
                                GkdOffsets(infinite = true, start = 1, step = 1),
                            ),
                        )
                        continue
                    }
                    break
                }
                if (startsWith("->")) {
                    // `->` 是「回到已查询过的节点重新展开」，需要保存查询路径，
                    // 当前的从右往左回溯模型表达不了，明确报错而不是解析成 `-` + `>`。
                    fail("暂不支持 `->` 关系算符")
                }
                i += op.text.length
                val offsets = parseOffsets()
                relations.add(GkdRelation(op, offsets))
                skipWs()
            }

            if (i < s.length) {
                fail("解析到第 ${i + 1} 个字符处无法继续：'${s.substring(i).take(20)}'")
            }

            val finalTarget = if (targetIndex >= 0) targetIndex else chain.size - 1
            return GkdSelector(chain, relations, finalTarget)
        }

        /** 属性选择器：`@?` 类名? `[表达式]*`。 */
        private fun parseAttr(chainPos: Int): GkdAttr {
            skipWs()
            var at = false
            if (peek() == '@') {
                at = true
                i++
                skipWs()
            }

            var className: String? = null
            if (peek() == '*') {
                className = "*"
                i++
            } else if (peek() != '[' && isIdentStart(peek())) {
                val start = i
                // 允许全限定名：android.widget.TextView
                while (i < s.length && (isIdentPart(s[i]) || s[i] == '.')) i++
                className = s.substring(start, i).removeSuffix(".")
            }

            // `@(text="a" || text="b")` —— 括号里包的是属性表达式，不带类名
            if (className == null && peek() == '(') {
                i++
                val e = parseExpr()
                skipWs()
                expect(')')
                if (at) {
                    // 同上：多个 @ 时最后一个生效
                    targetIndex = chainPos
                }
                return GkdAttr(null, e)
            }

            // 多个 [] 组之间是 && 的关系。组与组之间允许有空白 ——
            // 真实订阅里 `[vid="fl_main"] [vid="img_close"]` 这种写法很常见，
            // 不跳过空白就会把后半截当成「解析不了的关系算符」整条丢掉。
            var cond: GkdExpr? = null
            while (true) {
                skipWs()
                if (peek() != '[') break
                i++
                skipWs()
                val e = parseExpr()
                skipWs()
                expect(']')
                cond = if (cond == null) e else GkdExpr.And(cond, e)
            }

            if (className == null && cond == null) {
                fail("属性选择器既没有类名也没有属性条件")
            }
            if (at) {
                // GKD 里多个 @ 时最后一个生效，不是语法错误
                targetIndex = chainPos
            }
            return GkdAttr(className, cond)
        }

        // ---------------- 属性表达式 ----------------

        private fun parseExpr(): GkdExpr {
            var left = parseAnd()
            while (true) {
                skipWs()
                if (startsWith("||")) {
                    i += 2
                    left = GkdExpr.Or(left, parseAnd())
                } else {
                    return left
                }
            }
        }

        private fun parseAnd(): GkdExpr {
            var left = parseUnary()
            while (true) {
                skipWs()
                if (startsWith("&&")) {
                    i += 2
                    left = GkdExpr.And(left, parseUnary())
                } else {
                    return left
                }
            }
        }

        private fun parseUnary(): GkdExpr {
            skipWs()
            if (peek() == '!' && !startsWith("!=")) {
                i++
                skipWs()
                expect('(')
                val inner = parseExpr()
                skipWs()
                expect(')')
                return GkdExpr.Not(inner)
            }
            return parseCmp()
        }

        private fun parseCmp(): GkdExpr {
            val left = parseValue()
            skipWs()
            val op = peekCmpOp() ?: fail("期待一个比较运算符，实际是 '${s.substring(i).take(12)}'")
            i += op.text.length
            skipWs()
            val right = parseValue()
            return GkdExpr.Cmp(op, left, right)
        }

        /** 比较运算符按长度从长到短匹配，否则 `>=` 会被当成 `>` 再接一个 `=`。 */
        private fun peekCmpOp(): GkdCmpOp? {
            val candidates = listOf(
                "!^=", "!*=", "!$=", "!~=", "!=",
                ">=", "<=", "^=", "*=", "$=", "~=",
                "=", ">", "<",
            )
            for (c in candidates) {
                if (startsWith(c)) return GkdCmpOp.entries.first { it.text == c }
            }
            return null
        }

        private fun parseValue(): GkdValue {
            skipWs()
            if (atEnd()) fail("期待一个值，但内容已结束")
            val c = peek()

            if (c == '\'' || c == '"' || c == '`') {
                return GkdValue.Lit(parseString())
            }

            if (c == '-' || c == '+' || c.isDigit()) {
                val start = i
                if (c == '-' || c == '+') i++
                while (i < s.length && s[i].isDigit()) i++
                val text = s.substring(start, i)
                val v = text.toIntOrNull() ?: fail("非法的整数字面量：$text")
                return GkdValue.Lit(v)
            }

            if (!isIdentStart(c)) fail("非法的值起始字符 '$c'")

            // null / true / false 是字面量，不是变量
            val nameStart = i
            while (i < s.length && isIdentPart(s[i])) i++
            val name = s.substring(nameStart, i)

            var value: GkdValue = when (name) {
                "null" -> GkdValue.Lit(null)
                "true" -> GkdValue.Lit(true)
                "false" -> GkdValue.Lit(false)
                else -> GkdValue.Var(name)
            }

            // 调用参数
            if (peek() == '(') {
                i++
                val args = mutableListOf<GkdValue>()
                skipWs()
                if (peek() != ')') {
                    while (true) {
                        args.add(parseValue())
                        skipWs()
                        if (peek() == ',') { i++; skipWs(); continue }
                        break
                    }
                }
                expect(')')
                value = GkdValue.Call(value, args)
            }

            // 成员链：a.b.c，以及 a.b.c(d)
            while (peek() == '.') {
                i++
                if (!isIdentStart(peek())) fail("'.' 后面期待一个属性名")
                val pStart = i
                while (i < s.length && isIdentPart(s[i])) i++
                value = GkdValue.Member(value, s.substring(pStart, i))
                if (peek() == '(') {
                    i++
                    val args = mutableListOf<GkdValue>()
                    skipWs()
                    if (peek() != ')') {
                        while (true) {
                            args.add(parseValue())
                            skipWs()
                            if (peek() == ',') { i++; skipWs(); continue }
                            break
                        }
                    }
                    expect(')')
                    value = GkdValue.Call(value, args)
                }
            }
            return value
        }

        private fun parseString(): String {
            val quote = s[i]
            i++
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("字符串没有闭合")
                val c = s[i]
                if (c == quote) { i++; return sb.toString() }
                if (c == '\\') {
                    i++
                    if (atEnd()) fail("转义符后面没有内容")
                    when (val e = s[i]) {
                        'n' -> { sb.append('\n'); i++ }
                        'r' -> { sb.append('\r'); i++ }
                        't' -> { sb.append('\t'); i++ }
                        'b' -> { sb.append('\b'); i++ }
                        'f' -> { sb.append('\u000C'); i++ }
                        'v' -> { sb.append('\u000B'); i++ }
                        '0' -> { sb.append('\u0000'); i++ }
                        '\n' -> i++
                        'x' -> {
                            i++
                            if (i + 1 >= s.length) fail("\\x 转义不完整")
                            val hex = s.substring(i, i + 2)
                            sb.append((hex.toIntOrNull(16) ?: fail("\\x 不是合法十六进制：$hex")).toChar())
                            i += 2
                        }
                        'u' -> {
                            i++
                            if (i + 3 >= s.length) fail("\\u 转义不完整")
                            val hex = s.substring(i, i + 4)
                            sb.append((hex.toIntOrNull(16) ?: fail("\\u 不是合法十六进制：$hex")).toChar())
                            i += 4
                        }
                        else -> { sb.append(e); i++ }
                    }
                    continue
                }
                sb.append(c)
                i++
            }
        }

        // ---------------- 关系算符 ----------------

        /** 下一个 token 是否像一个新的属性选择器（用于识别省略的关系算符）。 */
        private fun looksLikeAttrStart(): Boolean {
            if (atEnd()) return false
            val c = peek()
            return c == '@' || c == '*' || c == '[' || c == '(' || isIdentStart(c)
        }

        /** `<<` 必须排在 `<` 前面，否则会被解析成 `<` 加一个 `<`。 */
        private fun peekRelationOp(): GkdRelOp? {
            if (startsWith("<<")) return GkdRelOp.DESCENDANT
            return when (peek()) {
                '>' -> GkdRelOp.ANCESTOR
                '<' -> GkdRelOp.CHILD
                '+' -> GkdRelOp.PREV_SIBLING
                '-' -> GkdRelOp.NEXT_SIBLING
                else -> null
            }
        }

        /**
         * 关系表达式。
         *
         * 官方：元组 `(a1,a2,...)` 与多项式 `(an+b)` 等价，
         * 多项式展开为 `{a*n+b | n>=1, a*n+b>=1}`；a>0 是无限集，a<=0 是有限集。
         * 省略时默认 `(1)`。
         */
        private fun parseOffsets(): GkdOffsets {
            skipWs()
            if (peek() == '(') {
                i++
                val off = parseOffsetBody()
                skipWs()
                expect(')')
                return off
            }
            // 简写形式：`+3`（= (0n+3)）、`+3n`（= (3n+0)）
            val save = i
            tryParsePolynomial(save)?.let { return it }
            i = save
            tryParseIntList(save)?.let { return it }
            i = save
            return GkdOffsets.ONE
        }

        private fun parseOffsetBody(): GkdOffsets {
            skipWs()
            val save = i
            tryParsePolynomial(save)?.let { return it }
            i = save
            tryParseIntList(save)?.let { return it }
            i = save
            fail("偏移量表达式无法解析：'${s.substring(save).take(16)}'")
        }

        /**
         * 尝试按多项式解析偏移量。支持三种写法：
         *   2n+1  → a=2, b=1
         *   -n+4  → a=-1, b=4
         *   12-n  → a=-1, b=12（真实规则里出现过 `<<(12-n)`）
         * 不是多项式时返回 null 并把游标复位。
         */
        private fun tryParsePolynomial(start: Int): GkdOffsets? {
            i = start
            var sign = 1
            if (peek() == '-' || peek() == '+') {
                if (peek() == '-') sign = -1
                i++
            }
            val ds = i
            while (i < s.length && s[i].isDigit()) i++
            val hasCoef = ds != i
            val coef = if (hasCoef) sign * s.substring(ds, i).toInt() else sign

            skipWs()
            if (peek() == 'n') {
                i++
                var const = 0
                skipWs()
                if (peek() == '+' || peek() == '-') {
                    val csign = if (peek() == '-') -1 else 1
                    i++
                    val cd = i
                    while (i < s.length && s[i].isDigit()) i++
                    if (cd == i) { i = start; return null }
                    const = csign * s.substring(cd, i).toInt()
                }
                return expandPolynomial(coef, const)
            }

            // b-n 形式：12-n
            if (hasCoef && peek() == '-') {
                val save = i
                i++
                skipWs()
                if (peek() == 'n') {
                    i++
                    return expandPolynomial(-1, coef)
                }
                i = save
            }

            i = start
            return null
        }

        /** 纯整数或整数列表：1 / 1,2,3 */
        private fun tryParseIntList(start: Int): GkdOffsets? {
            i = start
            val nums = mutableListOf<Int>()
            while (true) {
                skipWs()
                val st = i
                if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
                val digitStart = i
                while (i < s.length && s[i].isDigit()) i++
                if (digitStart == i) { i = start; return null }
                val v = s.substring(st, i).toIntOrNull()
                if (v == null) { i = start; return null }
                nums.add(v)
                skipWs()
                if (peek() == ',') { i++; continue }
                break
            }
            return GkdOffsets(finite = nums.sorted())
        }

        /** `{a*n+b | n>=1, a*n+b>=1}`：a>0 无限，a<=0 有限。 */
        private fun expandPolynomial(a: Int, b: Int): GkdOffsets {
            if (a > 0) {
                return GkdOffsets(infinite = true, start = a * 1 + b, step = a)
            }
            if (a == 0) return GkdOffsets(finite = listOf(b))
            val out = mutableListOf<Int>()
            var n = 1
            while (true) {
                val v = a * n + b
                if (v < 1) break
                out.add(v)
                n++
                if (n > 10000) break
            }
            return GkdOffsets(finite = out.sorted())
        }

        // ---------------- 基础工具 ----------------

        private fun peek(): Char = if (i < s.length) s[i] else ' '

        private fun atEnd(): Boolean = i >= s.length

        private fun startsWith(text: String): Boolean = s.startsWith(text, i)

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun expect(c: Char) {
            if (atEnd()) fail("期待 '$c' 但内容已结束")
            if (s[i] != c) fail("期待 '$c'，实际是 '${s[i]}'")
            i++
        }

        private fun fail(msg: String): Nothing =
            throw GkdParseException("第 ${i + 1} 个字符处：$msg")

        // 标识符按官方定义：^[_a-zA-Z][a-zA-Z0-9_]*$
        // 刻意不含 '$' —— 否则 `text$="x"` 里的 '$' 会被吞进标识符，
        // 比较运算符就再也认不出来了。
        // 也刻意不用 isLetter()：那会把中文文本当成合法类名，
        // `这段不是选择器` 会被静默解析成「类名匹配」，垃圾进垃圾出更难排查。
        private fun isIdentStart(c: Char): Boolean =
            c == '_' || (c in 'a'..'z') || (c in 'A'..'Z')

        private fun isIdentPart(c: Char): Boolean =
            isIdentStart(c) || (c in '0'..'9')
    }
}
