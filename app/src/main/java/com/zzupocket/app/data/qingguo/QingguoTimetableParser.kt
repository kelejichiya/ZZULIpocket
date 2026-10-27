package com.zzupocket.app.data.qingguo

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 青果课表 HTML 解析。
 *
 * 页面结构（各校 JSP 编号不同，但表格骨架一致）：
 *
 * ```
 * <table id="mytable">
 *   <tr><td>节次</td><td>星期一</td>…<td>星期日</td></tr>
 *   <tr><td class="td1">1</td>
 *       <td class="td"><div style="padding-bottom:5px;clear:both;">
 *           <font style="font-weight: bolder">高等数学</font><br>
 *           张三<br>1-16周[1-2节]<br>科学校区 教三楼 201
 *       </div></td> …
 * ```
 *
 * 解析成 [QingguoCourse]（一门课可能有多个上课时段，调用方按周展开成多条 [com.zzupocket.app.domain.Session]）。
 * 对字段缺失是宽容的：拿不到周次就当作 1..18 周，拿不到节次就用行号。
 */
data class QingguoCourse(
    val name: String,
    val teacher: String?,
    val room: String?,
    val weeks: List<Int>,
    val weekday: Int,
    val startUnit: Int,
    val endUnit: Int,
)

object QingguoTimetableParser {

    private val WEEKS_RE = Regex("([0-9][0-9,\\-]*)\\s*周")
    private val PERIODS_RE = Regex("\\[\\s*([0-9][0-9,\\-]*)\\s*节?\\s*]")
    private val PERIODS_ALT_RE = Regex("第\\s*([0-9][0-9,\\-]*)\\s*节")

    fun parse(html: String): List<QingguoCourse> {
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html)
        // 青果各校模板不完全一致：优先认 id/class，认不出就选"课程格最多"的那张表
        val table = doc.selectFirst("table#mytable")
            ?: doc.selectFirst("table#kb")
            ?: doc.selectFirst("table.kbtable")
            ?: doc.select("table")
                .maxByOrNull { t -> t.select("td.td").size }
                ?.takeIf { it.select("td.td").isNotEmpty() }
            ?: return emptyList()

        val out = ArrayList<QingguoCourse>()
        val rows = table.select("tr")
        var unitByRow = 0
        rows.forEachIndexed { rowIndex, tr ->
            val cells = tr.select("td")
            if (cells.isEmpty()) return@forEachIndexed

            // 节次：优先取 td1 的文本（"第1节"/"1"），否则用数据行序号
            val unitCell = cells.firstOrNull { it.classNames().contains("td1") }
            val rowUnit = unitCell?.text()?.let { text ->
                Regex("(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
            }
            if (rowUnit != null) unitByRow = rowUnit else if (rowIndex > 0) unitByRow += 1

            // 课程格：有 td1 就按列过滤，没有就固定去掉第一列，保证星期列不错位
            val courseCells = if (unitCell != null) {
                cells.filterNot { it.classNames().contains("td1") }
            } else {
                cells.drop(1)
            }
            if (courseCells.isEmpty()) return@forEachIndexed

            courseCells.take(7).forEachIndexed { index, cell ->
                val weekday = index + 1
                val divs = cell.select("div").filter {
                    val style = it.attr("style")
                    style.contains("padding-bottom:5px") || it.selectFirst("font[style*=font-weight]") != null
                }
                divs.forEach { div ->
                    parseCourse(div, weekday, unitByRow)?.let { out += it }
                }
            }
        }
        return out.distinct()
    }

    private fun parseCourse(div: Element, weekday: Int, rowUnit: Int): QingguoCourse? {
        val lines = linesOf(div)
        if (lines.isEmpty()) return null

        val name = div.selectFirst("font[style*=font-weight]")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: lines.first().takeIf { it.length >= 2 && !it.contains("[") }
            ?: return null

        // 真实格式：`1,3-12 单 [1-2]` / `9-15[1-2]` —— 周次和节次在同一条里，
        // 且**不带"周"字**；"单/双"表示只上单周/双周。
        val info = lines.firstOrNull { it.contains('[') && it.contains(']') }
        val periods = info?.let {
            Regex("\\[\\s*([0-9][0-9,\\-]*)\\s*节?\\s*]").find(it)?.groupValues?.get(1)
        }?.let { parseInts(it) }.orEmpty()

        val weeksRaw = info?.substringBefore('[')?.trim().orEmpty()
        val odd = weeksRaw.contains("单")
        val even = weeksRaw.contains("双")
        var weeks = parseWeeks(weeksRaw)
        if (weeks.isEmpty()) weeks = (1..18).toList()
        if (odd) weeks = weeks.filter { it % 2 == 1 }
        if (even) weeks = weeks.filter { it % 2 == 0 }

        // 表格一行 = 一个大节（1..5），没有 [x-y] 时按大节换算成小节
        val fallbackStart = (rowUnit * 2 - 1).coerceAtLeast(1)
        val start = periods.firstOrNull() ?: fallbackStart
        val end = periods.lastOrNull() ?: (start + 1)

        // 其余行里挑教师与地点
        val others = lines.filter { it != name && it != info }
        val room = others.lastOrNull { line ->
            line.contains("楼") || line.contains("室") || line.contains("教") ||
                line.contains("馆") || line.contains("中心") || line.contains("区") ||
                line.any { it.isDigit() }
        }
        val teacher = others.firstOrNull { it != room && it.length in 2..12 && !it.any { c -> c.isDigit() } }

        return QingguoCourse(
            name = name,
            teacher = teacher,
            room = room,
            weeks = weeks,
            weekday = weekday,
            startUnit = start,
            endUnit = end.coerceAtLeast(start),
        )
    }

    /** 把 `<br>` 当分隔符，保留每行文本（jsoup 的 text() 会把换行压成空格） */
    private fun linesOf(div: Element): List<String> {
        val marked = div.html().replace(Regex("(?i)<br\\s*/?>"), "\u00a7")
        return Jsoup.parseBodyFragment(marked).body().text()
            .split('\u00a7')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /** "1-16" / "1,3,5" / "1-8,10-16" -> 周次列表 */
    internal fun parseWeeks(raw: String?): List<Int> {
        val text = raw.orEmpty()
            .replace("单", "")
            .replace("双", "")
            .replace("周", "")
            .replace(" ", "")
            .trim()
        if (text.isEmpty()) return emptyList()
        val out = sortedSetOf<Int>()
        text.split(',').forEach { part ->
            val p = part.trim()
            if (p.isEmpty()) return@forEach
            val dash = p.indexOf('-')
            if (dash > 0) {
                val a = p.substring(0, dash).trim().toIntOrNull()
                val b = p.substring(dash + 1).trim().toIntOrNull()
                if (a != null && b != null && a in 1..30 && b in a..30) {
                    for (w in a..b) out += w
                }
            } else {
                p.toIntOrNull()?.takeIf { it in 1..30 }?.let { out += it }
            }
        }
        return out.toList()
    }

    private fun parseInts(raw: String): List<Int> {
        val out = mutableListOf<Int>()
        raw.split(',').forEach { part ->
            val p = part.trim()
            val dash = p.indexOf('-')
            if (dash > 0) {
                val a = p.substring(0, dash).trim().toIntOrNull()
                val b = p.substring(dash + 1).trim().toIntOrNull()
                if (a != null && b != null) {
                    for (u in a..b) out += u
                }
            } else {
                p.toIntOrNull()?.let { out += it }
            }
        }
        return out
    }
}
