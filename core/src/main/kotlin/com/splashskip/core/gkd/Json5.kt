package com.splashskip.core.gkd

/**
 * JSON5 解析器（纯 Kotlin，无第三方依赖）。
 *
 * 为什么不用 Gson：GKD 订阅是 JSON5 —— key 可以不加引号、字符串可以用单引号、
 * 允许尾随逗号、允许行注释。Gson 的宽松模式也救不了这些。
 * 为什么不用 org.json：Android 自带的 org.json 在 JVM 单元测试里没有实现。
 *
 * 解析结果用 Kotlin 原生类型承载：
 *   · 对象 → LinkedHashMap<String, Any?>（保序，便于生成稳定的告警信息）
 *   · 数组 → List<Any?>
 *   · 字符串 → String
 *   · 整数 → Long（超出 Long 范围转 Double）
 *   · 小数 → Double
 *   · 布尔 → Boolean
 *   · 空 → null
 */
/** 解析失败。带上出错位置，方便定位订阅文件里的问题行。 */
class Json5Exception(message: String) : Exception(message)

object Json5 {

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWhitespace()
        val value = p.parseValue()
        p.skipWhitespace()
        if (!p.atEnd()) {
            p.fail("内容结束后还有多余字符")
        }
        return value
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun atEnd(): Boolean = i >= s.length

        fun fail(msg: String): Nothing {
            // 报行号：GKD 订阅经常有上万个字符，行号比列号实用
            val line = s.take(i).count { it == '\n' } + 1
            throw Json5Exception("第 $line 行：$msg")
        }

        fun skipWhitespace() {
            while (i < s.length) {
                val c = s[i]
                when {
                    c.isWhitespace() -> i++
                    // 行注释
                    c == '/' && i + 1 < s.length && s[i + 1] == '/' -> {
                        while (i < s.length && s[i] != '\n') i++
                    }
                    // 块注释
                    c == '/' && i + 1 < s.length && s[i + 1] == '*' -> {
                        val end = s.indexOf("*/", i + 2)
                        if (end < 0) fail("块注释没有闭合")
                        i = end + 2
                    }
                    else -> return
                }
            }
        }

        fun parseValue(): Any? {
            if (atEnd()) fail("内容意外结束")
            return when (val c = s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"', '\'' -> parseString()
                else -> when {
                    c == '-' || c == '+' || c.isDigit() -> parseNumber()
                    s.startsWith("true", i) -> { i += 4; true }
                    s.startsWith("false", i) -> { i += 5; false }
                    s.startsWith("null", i) -> { i += 4; null }
                    s.startsWith("Infinity", i) -> { i += 8; Double.POSITIVE_INFINITY }
                    s.startsWith("NaN", i) -> { i += 3; Double.NaN }
                    else -> fail("无法识别的值，起始字符是 '$c'")
                }
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') { i++; return out }
            while (true) {
                skipWhitespace()
                val key = parseKey()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                out[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> { i++; skipWhitespace(); if (peek() == '}') { i++; return out } }
                    '}' -> { i++; return out }
                    else -> fail("对象里期待 ',' 或 '}'，实际是 '${peek()}'")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') { i++; return out }
            while (true) {
                skipWhitespace()
                out.add(parseValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> { i++; skipWhitespace(); if (peek() == ']') { i++; return out } }
                    ']' -> { i++; return out }
                    else -> fail("数组里期待 ',' 或 ']'，实际是 '${peek()}'")
                }
            }
        }

        /** key 可以是带引号的字符串，也可以是裸标识符。 */
        private fun parseKey(): String {
            if (atEnd()) fail("对象缺少 key")
            return when (val c = s[i]) {
                '"', '\'' -> parseString()
                else -> {
                    if (!isIdentifierStart(c)) fail("非法的 key 起始字符 '$c'")
                    val start = i
                    while (i < s.length && isIdentifierPart(s[i])) i++
                    s.substring(start, i)
                }
            }
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
                        'f' -> { sb.append(''); i++ }
                        'v' -> { sb.append(''); i++ }
                        '0' -> { sb.append('\u0000'); i++ }
                        // 行续行：反斜杠后面直接换行，字符串里不含这个换行
                        '\n' -> i++
                        'x' -> {
                            i++
                            if (i + 1 >= s.length) fail("\\x 转义不完整")
                            val hex = s.substring(i, i + 2)
                            val code = hex.toIntOrNull(16) ?: fail("\\x 转义不是合法十六进制：$hex")
                            sb.append(code.toChar())
                            i += 2
                        }
                        'u' -> {
                            i++
                            if (i + 3 >= s.length) fail("\\u 转义不完整")
                            val hex = s.substring(i, i + 4)
                            val code = hex.toIntOrNull(16) ?: fail("\\u 转义不是合法十六进制：$hex")
                            sb.append(code.toChar())
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

        private fun parseNumber(): Any? {
            val start = i
            if (peek() == '-' || peek() == '+') i++
            // 十六进制
            if (peek() == '0' && i + 1 < s.length && (s[i + 1] == 'x' || s[i + 1] == 'X')) {
                i += 2
                val hexStart = i
                while (i < s.length && s[i].isHexDigit()) i++
                if (hexStart == i) fail("十六进制数字没有有效位")
                val text = s.substring(hexStart, i)
                return text.toLongOrNull(16) ?: fail("十六进制数字过大：0x$text")
            }
            if (s.startsWith("Infinity", i)) {
                i += 8
                return if (s[start] == '-') Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
            }
            if (s.startsWith("NaN", i)) { i += 3; return Double.NaN }

            var isFloat = false
            while (i < s.length) {
                val c = s[i]
                when {
                    c.isDigit() -> i++
                    c == '.' -> { isFloat = true; i++ }
                    c == 'e' || c == 'E' -> {
                        isFloat = true
                        i++
                        if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
                    }
                    else -> break
                }
            }
            val text = s.substring(start, i)
            if (text.isEmpty() || text == "-" || text == "+") fail("非法的数字")
            if (!isFloat) {
                text.toLongOrNull()?.let { return it }
            }
            return text.toDoubleOrNull() ?: fail("非法的数字：$text")
        }

        private fun peek(): Char = if (i < s.length) s[i] else ' '

        private fun expect(c: Char) {
            if (atEnd()) fail("期待 '$c' 但内容已结束")
            if (s[i] != c) fail("期待 '$c'，实际是 '${s[i]}'")
            i++
        }

        private fun Char.isHexDigit(): Boolean =
            this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

        // 标识符：允许字母、数字、_、$，以及非 ASCII 字母（中文等也能当 key）
        private fun isIdentifierStart(c: Char): Boolean =
            c == '_' || c == '$' || c.isLetter()

        private fun isIdentifierPart(c: Char): Boolean =
            c == '_' || c == '$' || c.isLetterOrDigit()
    }
}
