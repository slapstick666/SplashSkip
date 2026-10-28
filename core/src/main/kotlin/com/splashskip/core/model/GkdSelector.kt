package com.splashskip.core.model

/**
 * GKD 选择器的抽象语法树。
 *
 * 整棵 AST 的形状对应 GKD 官方文档里的三层结构：
 *
 *   选择器      = 属性选择器 (关系算符 属性选择器)*
 *   属性选择器  = ['@'] [类名] ['[' 表达式 ']']
 *   表达式      = 逻辑表达式 / 取反表达式 / 比较表达式
 *
 * 方向是这套语法里最容易搞错的地方，官方原文（gkd.li/guide/selector）：
 *
 *   A >  B   →  A 是 B 的祖先节点（B 是 A 的后代）
 *   A <  B   →  A 是 B 的直接子节点
 *   A +  B   →  A 是 B 的前置兄弟节点（B 在 A 后面）
 *   A -  B   →  A 是 B 的后置兄弟节点（A 在 B 后面）
 *   A << B   →  A 是 B 的子孙节点（深度先序）
 *
 * 注意 `>` 与 `<` 相对 CSS 是**反的**：CSS 里 `A > B` 表示 B 是 A 的子，
 * GKD 里 `A > B` 表示 A 是 B 的祖先。匹配又是**从右往左**做的，
 * 所以从右侧节点回溯时，`>` 要往上找祖先，`<` 要往下找子。
 * 这条如果记错，整个匹配会静默地全不命中。
 */

/** 一条完整的选择器（属性选择器链 + 关系算符链）。 */
data class GkdSelector(
    /** 从左到右的属性选择器，至少一个。 */
    val chain: List<GkdAttr>,
    /** chain[i] 与 chain[i+1] 之间的关系算符，长度 = chain.size - 1。 */
    val relations: List<GkdRelation>,
    /**
     * `@` 标记所在的链上位置（0 基）。
     * GKD 规则：一条选择器最后属性选择器上的 `@` 生效；没有 `@` 时取链尾。
     */
    val targetIndex: Int,
) {
    init {
        require(chain.isNotEmpty()) { "选择器至少要有一个属性选择器" }
    }
}

/**
 * 属性选择器：`ClassName[条件]`。
 *
 * @param className 类名（短名，如 "TextView"）。null 表示不限类名。
 *                  官方语义：类名等价于 `[name='TextView'||name$='.TextView']`，
 *                  即短名相等或全名以点+短名结尾。
 * @param cond 属性条件。null 表示无条件（此时只有类名约束）。
 */
data class GkdAttr(
    val className: String?,
    val cond: GkdExpr?,
)

/** 关系算符。 */
enum class GkdRelOp(val text: String) {
    /** `>`：左侧是右侧的祖先。回溯时向上找。 */
    ANCESTOR(">"),

    /** `<`：左侧是右侧的直接子节点。回溯时向下找。 */
    CHILD("<"),

    /** `+`：左侧是右侧的前置兄弟（右侧在左后）。回溯时向前找。 */
    PREV_SIBLING("+"),

    /** `-`：左侧是右侧的后置兄弟（左侧在右后）。回溯时向后找。 */
    NEXT_SIBLING("-"),

    /** `<<`：左侧是右侧的子孙（深度先序）。回溯时在子树里按 DFS 序取。 */
    DESCENDANT("<<"),
}

/** 关系算符 + 关系表达式（偏移量集合）。 */
data class GkdRelation(
    val op: GkdRelOp,
    val offsets: GkdOffsets,
)

/**
 * 关系表达式的偏移量集合。
 *
 * GKD 的元组表达式 `(a1,a2,...,an)` 与多项式表达式 `(an+b)` 等价：
 * 多项式展开为 `{a*n+b | n>=1, a*n+b>=1}`。a>0 时是无限集，a<=0 时是有限集。
 * 例如 `(n)` → 1,2,3,... ；`(2n-1)` → 1,3,5,... ；`(-n+4)` → 1,2,3 。
 */
data class GkdOffsets(
    /** 有限集合里的具体值，升序。 */
    val finite: List<Int> = emptyList(),
    /** 无限序列的首项。 */
    val start: Int = 0,
    /** 无限序列的公差（正数）。 */
    val step: Int = 0,
    /** 是否为无限序列。 */
    val infinite: Boolean = false,
) {
    companion object {
        /** 省略关系表达式时的默认值：`(1)`。 */
        val ONE = GkdOffsets(finite = listOf(1))
    }

    /** 取第 index 个偏移量（0 基）。有限集越界返回 null；无限集按公差外推。 */
    fun at(index: Int): Int? = when {
        infinite -> if (index < 0) null else start + index * step
        else -> finite.getOrNull(index)
    }

    /** 该集合是否包含指定偏移量。 */
    fun contains(value: Int): Boolean = when {
        infinite -> value >= start && (value - start) % step == 0
        else -> finite.contains(value)
    }

    /** 有限集里最大的偏移量，用来界定查找范围。无限集返回 Int.MAX_VALUE。 */
    val max: Int get() = if (infinite) Int.MAX_VALUE else (finite.maxOrNull() ?: 0)
}

/** 属性表达式。 */
sealed interface GkdExpr {
    /** 比较表达式：`left op right`。 */
    data class Cmp(val op: GkdCmpOp, val left: GkdValue, val right: GkdValue) : GkdExpr

    /** 逻辑与，优先级高于 [Or]。 */
    data class And(val left: GkdExpr, val right: GkdExpr) : GkdExpr

    /** 逻辑或。 */
    data class Or(val left: GkdExpr, val right: GkdExpr) : GkdExpr

    /** 取反：`!(...)`。 */
    data class Not(val inner: GkdExpr) : GkdExpr
}

/**
 * 比较运算符。
 *
 * 类型约束（官方表格）：`=`/`!=` 通用；`>`/`>=`/`<`/`<=` 只对整数；
 * `^=`/`*=`/`$=` 及其取反只对字符串；`~=`/`!~=` 左侧字符串、右侧正则字面量。
 *
 * 另有一条重要规则：**除 `=`/`!=` 外，属性为 null 时表达式结果为 false。**
 */
enum class GkdCmpOp(val text: String) {
    EQ("="),
    NE("!="),
    GT(">"),
    GE(">="),
    LT("<"),
    LE("<="),
    STARTS("^="),
    NOT_STARTS("!^="),
    CONTAINS("*="),
    NOT_CONTAINS("!*="),
    ENDS("$="),
    NOT_ENDS("!$="),
    MATCHES("~="),
    NOT_MATCHES("!~="),
}

/** 值表达式。 */
sealed interface GkdValue {
    /** 字面量：null / 布尔 / 整数 / 字符串。 */
    data class Lit(val value: Any?) : GkdValue

    /** 变量标识符，如 `text`、`clickable`。 */
    data class Var(val name: String) : GkdValue

    /** 成员表达式，如 `text.length`。 */
    data class Member(val base: GkdValue, val property: String) : GkdValue

    /**
     * 调用表达式。
     *
     * caller 是任意值表达式，不只是标识符 ——
     * GKD 允许 `parent.childCount.minus(1)` 这种链式调用。
     */
    data class Call(val caller: GkdValue, val args: List<GkdValue>) : GkdValue
}
