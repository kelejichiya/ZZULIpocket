package com.zzupocket.app.domain

/** 成绩单里的一门课（从学校报表表格里映射出来） */
data class GradeItem(
    val course: String,
    val credits: String,
    val category: String,
    val usual: String,
    val mid: String,
    val final: String,
    val total: String,
    val remark: String,
)

/** 考试安排里的一条 */
data class ExamItem(
    val course: String,
    val time: String,
    val place: String,
    val seat: String,
    val extra: String,
)

/** 按学期分组后的成绩 */
data class GradeTerm(val term: String, val items: List<GradeItem>)

/** 把「学年学期：2025-2026学年第一学期」整理成「2025-2026 第一学期」 */
fun cleanTermLabel(raw: String): String {
    val text = raw.replace("学年学期", "").replace("：", "").replace(":", "").trim()
    val m = Regex("""(\d{4}-\d{4})\s*学年\s*(第?[一二三]学期)""").find(text)
    if (m != null) {
        val term = m.groupValues[2].let { if (it.startsWith("第")) it else "第$it" }
        return "${m.groupValues[1]} $term"
    }
    return text.ifBlank { "未知学期" }
}

/**
 * 把学校报表的二维表格映射成可读条目。
 *
 * 不依赖固定列号：先找表头行，再按表头文字定位列，学校调列序也不会错位。
 */
object ReportParser {

    fun grades(rows: List<List<String>>): List<GradeItem> {
        val headerIndex = rows.indexOfFirst { r ->
            r.any { it.contains("课程") } && r.any { it.contains("学分") || it.contains("成绩") }
        }
        if (headerIndex < 0) return emptyList()
        val header = rows[headerIndex]
        fun col(vararg keys: String): Int =
            header.indexOfFirst { h -> keys.any { h.contains(it) } }

        val courseCol = col("课程")
        val creditCol = col("学分")
        val categoryCol = col("类别")
        val usualCol = col("平时")
        val midCol = col("期中")
        val finalCol = col("期末")
        val totalCol = col("综合")
        val remarkCol = col("备注")

        fun cell(row: List<String>, index: Int): String =
            if (index in row.indices) row[index].trim() else ""

        return rows.drop(headerIndex + 1).mapNotNull { row ->
            val course = cell(row, courseCol)
            if (course.isBlank() || course.contains("成绩明细") || course == "课程/环节") return@mapNotNull null
            if (course.toIntOrNull() != null) return@mapNotNull null
            GradeItem(
                course = course,
                credits = cell(row, creditCol),
                category = cell(row, categoryCol),
                usual = cell(row, usualCol),
                mid = cell(row, midCol),
                final = cell(row, finalCol),
                total = cell(row, totalCol),
                remark = cell(row, remarkCol),
            )
        }
    }

    fun exams(rows: List<List<String>>): List<ExamItem> {
        val headerIndex = rows.indexOfFirst { r ->
            r.any { it.contains("课程") } && r.any { it.contains("时间") || it.contains("地点") }
        }
        val header = if (headerIndex >= 0) rows[headerIndex] else emptyList()
        fun col(vararg keys: String): Int =
            header.indexOfFirst { h -> keys.any { h.contains(it) } }

        val courseCol = if (headerIndex >= 0) col("课程") else 1
        val timeCol = if (headerIndex >= 0) col("时间") else 2
        val placeCol = if (headerIndex >= 0) col("地点", "教室") else 3
        val seatCol = if (headerIndex >= 0) col("座位") else 4

        fun cell(row: List<String>, index: Int): String =
            if (index in row.indices) row[index].trim() else ""

        return rows.drop(if (headerIndex >= 0) headerIndex + 1 else 0).mapNotNull { row ->
            val course = cell(row, courseCol)
            if (course.isBlank() || course.toIntOrNull() != null) return@mapNotNull null
            ExamItem(
                course = course,
                time = cell(row, timeCol),
                place = cell(row, placeCol),
                seat = cell(row, seatCol),
                extra = row.filterIndexed { i, _ -> i != courseCol && i != timeCol && i != placeCol && i != seatCol }
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
            )
        }
    }
}
