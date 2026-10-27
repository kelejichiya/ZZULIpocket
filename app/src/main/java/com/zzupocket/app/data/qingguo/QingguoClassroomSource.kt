package com.zzupocket.app.data.qingguo

import com.zzupocket.app.core.school.SchoolProfile
import org.jsoup.Jsoup

/**
 * 空闲教室查询。
 *
 * 抓包确认（青果「公共查询 → 分周次/节次查看空闲教室」）：
 *   POST /kbbp/dykb.fzcjcckkxjsi_data.jsp
 *   xnxq=2026,0 & xn=2026 & xq_m=0 & selXQ=2 & txtZC=1-18
 *   selZ/selZ2=起止星期 & txtXJXL=起止节次 & hidMIN/hidMAX=容量
 *   menucode_current=SB11
 */
object QingguoClassroomSource {

    /** 一间空闲教室 */
    data class RoomItem(val name: String, val capacity: String)

    suspend fun query(
        year: String,
        term: String,
        campus: String,
        roomType: String,
        week: Int,
        weekday: Int,
        periodStart: Int,
        periodEnd: Int,
    ): List<RoomItem> {
        val weekText = if (week <= 0) "1-18" else "$week-$week"
        val form = linkedMapOf(
            "xnxq" to "$year,$term",
            "hidJS" to "",
            "hidDJ" to "",
            "hidMIN" to "1",
            "hidMAX" to "100",
            "xn" to year,
            "xn1" to "",
            "_xq" to "",
            "xq_m" to term,
            "selXQ" to campus,
            "txtZC" to weekText,
            "seldsz" to "",
            "selZ" to weekday.toString(),
            "selZ2" to weekday.toString(),
            "selDJ" to "",
            "hidselZ" to weekday.toString(),
            "txtXJXL" to "$periodStart-$periodEnd",
            "selJSLX" to roomType,
            "sel_lf" to "",
            "menucode_current" to "SB11",
        )
        val path = "/kbbp/dykb.fzcjcckkxjsi_data.jsp"
        // 抓包里这条是 GET/POST 都可能；先 POST，拿不到表再按 GET 重试一次
        var html = QingguoWebSession.post(path, form).orEmpty()
        var rows = parse(html)
        if (rows.isEmpty()) {
            val qs = form.entries.joinToString("&") { (k, v) ->
                "$k=" + java.net.URLEncoder.encode(v, "UTF-8")
            }
            html = QingguoWebSession.fetch("$path?$qs").orEmpty()
            rows = parse(html)
        }
        return rows
    }

    /**
     * 解析返回的表。学校这个表是「教室、容量、教室、容量…」一行多组，
     * 所以按 2 个单元格一组拆成独立教室，而不是把整行拼成一条。
     */
    private fun parse(html: String): List<RoomItem> {
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html)
        val table = doc.select("table").maxByOrNull { it.select("tr").size } ?: return emptyList()
        val rooms = mutableListOf<RoomItem>()
        table.select("tr").forEach { tr ->
            val cells = tr.select("td,th").map { it.text().trim() }
            var i = 0
            while (i + 1 < cells.size) {
                val name = cells[i]
                val cap = cells[i + 1]
                val isHeader = name.isEmpty() || name == "教室" || cap == "容量"
                if (!isHeader && cap.isNotEmpty() && cap.all { it.isDigit() }) {
                    rooms.add(RoomItem(name, cap))
                }
                i += 2
            }
        }
        return rooms
    }

    /** 当前学年/学期（从课表页地址里能拿到，这里用系统日期兜底） */
    fun defaultYearTerm(): Pair<String, String> {
        val year = java.time.LocalDate.now().year
        // 9 月之后算新学年；学期 0=第一学期，1=第二学期
        val academicYear = if (java.time.LocalDate.now().monthValue >= 9) year else year - 1
        val term = if (java.time.LocalDate.now().monthValue in 2..7) "1" else "0"
        return academicYear.toString() to term
    }
}
