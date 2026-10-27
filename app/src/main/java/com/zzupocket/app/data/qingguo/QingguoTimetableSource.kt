package com.zzupocket.app.data.qingguo

import com.zzupocket.app.core.AppError
import com.zzupocket.app.core.ScheduleText
import com.zzupocket.app.core.school.SchoolProfile
import com.zzupocket.app.domain.Period
import com.zzupocket.app.domain.Semester
import com.zzupocket.app.domain.Session
import java.time.LocalDate

/**
 * 青果的「学年 + 学期」。
 *
 * 青果接口用 `xn`（学年起始年，如 2025）与 `xq`（1=第一学期 / 2=第二学期），
 * 这里把它们合成一个稳定 id（20251 / 20252），用作本地学期主键。
 */
data class QingguoTerm(val year: Int, val term: Int) {
    val id: Int get() = year * 10 + term
    val schoolYear: String get() = "$year-${year + 1}"
    val displayName: String get() = "$schoolYear ${if (term == 1) "第一学期" else "第二学期"}"

    companion object {
        fun fromId(id: Int): QingguoTerm = QingguoTerm(id / 10, id % 10)
    }
}

/**
 * 青果课表数据源：负责"按学期拉 HTML → 解析 → 展开成领域 [Session]"。
 *
 * 与郑大 EAS 的差异：
 *  - 青果只给「周次 + 星期 + 节次」，**不给每条记录的真实日期**，所以 [Session.date] 为 null，
 *    周次对齐依赖 `WeekSettings`（用户可校正开学日期）；
 *  - 节次起始时间来自 [SchoolProfile.PERIODS]（教务处作息表），不从接口取。
 */
class QingguoTimetableSource(
    private val client: QingguoClient,
    private val periods: List<Period> = SchoolProfile.PERIODS,
) {

    /** 当前学期：9 月—次年 1 月为第一学期，2—8 月为第二学期（学年起始年 = 若 1 月则上一年） */
    fun currentTerm(today: LocalDate = LocalDate.now()): QingguoTerm {
        val month = today.monthValue
        return when {
            month >= 9 -> QingguoTerm(today.year, 1)
            month == 1 -> QingguoTerm(today.year - 1, 1)
            else -> QingguoTerm(today.year - 1, 2)
        }
    }

    /** 最近 [count] 个学期（从当前学期往前，跳过重复） */
    fun recentTerms(count: Int = 6, today: LocalDate = LocalDate.now()): List<QingguoTerm> {
        val current = currentTerm(today)
        return (0 until count).map { back ->
            val index = current.year * 2 + (current.term - 1) - back
            QingguoTerm(index / 2, index % 2 + 1)
        }.distinctBy { it.id }
    }

    /**
     * 调试用：把成绩 / 考试 / 学籍三个页面原始 HTML 落盘。
     * 只在 debug 包执行；用来校准解析器，不做任何上传。
     */
    suspend fun exportDebugPages() {
        if (!com.zzupocket.app.BuildConfig.DEBUG) return
        val dir = com.zzupocket.app.core.AppGraph.appContextOrNull()
            ?.getExternalFilesDir(null) ?: return
        val pages = listOf(
            "grade" to SchoolProfile.gradePath,
            "exam" to SchoolProfile.examPath,
            "profile" to SchoolProfile.profilePath,
        )
        for ((name, path) in pages) {
            val html = QingguoWebSession.fetch(path) ?: continue
            runCatching { java.io.File(dir, "$name.html").writeText(html) }
            android.util.Log.i("QingguoExport", "$name -> ${html.length}B")
        }

        // 成绩/考试是二次 POST 出来的数据表
        val enrollment = SchoolProfile.enrollmentYearOf(client.account()) ?: 2025
        val gradeData = QingguoWebSession.post(
            SchoolProfile.gradeDataPath,
            mapOf(
                "rxnj" to enrollment.toString(),
                "nj" to enrollment.toString(),
                "zx" to "1",
                "fx" to "1",
                "wz" to "0",
                "sjxz" to "",
                "ysyx" to "",
                "btnQry" to "检索",
            ),
        )
        if (gradeData != null) {
            runCatching { java.io.File(dir, "grade_data.html").writeText(gradeData) }
            android.util.Log.i("QingguoExport", "grade_data -> ${gradeData.length}B")
        }

        val term = currentTerm()
        val examData = QingguoWebSession.post(
            SchoolProfile.examDataPath,
            mapOf(
                "xn" to term.year.toString(),
                "xq" to (term.term - 1).toString(),
                "title" to "",
                "xnxq" to "",
                "kslc" to "",
                "btnQry" to "检索",
            ),
        )
        if (examData != null) {
            runCatching { java.io.File(dir, "exam_data.html").writeText(examData) }
            android.util.Log.i("QingguoExport", "exam_data -> ${examData.length}B")
        }
    }

    /** 从当前学期往前，一直到入学学年（按学号规则识别；识别不了就取最近 6 个） */
    fun termsForAccount(count: Int = 6, today: LocalDate = LocalDate.now()): List<QingguoTerm> {
        val enrollment = SchoolProfile.enrollmentYearOf(client.account())
        val all = recentTerms(count, today)
        return if (enrollment == null) all else all.filter { it.year >= enrollment }
    }

    fun semesters(
        terms: List<QingguoTerm>,
        weekOneStart: LocalDate? = null,
        totalWeeks: Int = 18,
    ): List<Semester> {
        val current = currentTerm()
        return terms.map { term ->
            Semester(
                id = term.id,
                nameZh = term.displayName,
                schoolYear = term.schoolYear,
                startDate = weekOneStart?.takeIf { term.id == current.id },
                endDate = weekOneStart?.takeIf { term.id == current.id }?.plusWeeks(totalWeeks - 1L),
                weekStartOnSunday = false,
                weekIndices = (1..totalWeeks).toList(),
                isCurrent = term.id == current.id,
            )
        }
    }

    /** 拉某学期课表原始表格 */
    suspend fun fetchCourses(term: QingguoTerm): List<QingguoCourse> {
        val account = client.account().orEmpty()
        // 实测（2026-10-25 抓包）：GET /student/wsxk.xskcb10319.jsp?params=base64("xn=2026&xq=0&xh=学号")
        // xq 从 0 起算：0=第一学期，1=第二学期；xn 是学年起始年。
        val params = client.paramsOf(
            "xn" to term.year.toString(),
            "xq" to (term.term - 1).toString(),
            "xh" to account,
        )
        val path = SchoolProfile.timetablePath + "?params=" + urlEncode(params)
        val referer = SchoolProfile.JWGL_BASE + "/student/xkjg.wdkb.jsp?menucode=S20301"
        // 优先用 WebView 会话（青果/CAS 的 Cookie 是 HttpOnly，OkHttp 带不上）；
        // 拿不到或拿到失效页时再退回 OkHttp（密码/扫码登录的路径）。
        val fromWeb = QingguoWebSession.fetch(path)
        val webWasLogin = fromWeb != null && QingguoWebSession.looksLikeLoginPage(fromWeb)
        val html = when {
            fromWeb != null && !webWasLogin -> fromWeb

            // 网页登录（短信/扫码）的会话在 WebView 里；只要 WebView 真的落到过教务域，
        // 就以它为权威。网页登录成功后 OkHttp 侧可能还残留旧的 Cookie（以前用过账密登录），
        // 不能因为有 Cookie 就直接回退 —— 那套可能早就过期了。
        QingguoWebSession.hasLanded() || client.cookieHeader().isBlank() -> {
            android.util.Log.w(
                "QingguoTimetable",
                "WebView 取课表失败（webWasLogin=$webWasLogin），尝试 CAS 静默续期",
            )
            val renewed = QingguoWebSession.renewViaCas()
            val retried = if (renewed) QingguoWebSession.fetch(path) else null
            when {
                retried != null && !QingguoWebSession.looksLikeLoginPage(retried) -> retried
                // 静默续期没成功，但 OkHttp 侧还有一套会话：再给它一次机会
                client.cookieHeader().isNotBlank() -> client.fetchText(path, referer = referer)
                retried != null || webWasLogin || QingguoWebSession.isAtLoginPage() ->
                    throw AppError.ReLoginRequired("教务会话已失效，请重新登录")
                else -> throw AppError.Network("教务页面没有加载出来，请检查网络后重试")
            }
        }

            // 青果密码 / 扫码登录：OkHttp 有会话，可以正常回退
            else -> client.fetchText(path, referer = referer)
        }
        if (QingguoWebSession.looksLikeLoginPage(html)) {
            throw AppError.ReLoginRequired("教务会话已失效，请重新登录")
        }
        // 调试用：把课表原始 HTML 落盘（带学期号），方便用真实结构校准解析
        runCatching {
            if (com.zzupocket.app.BuildConfig.DEBUG) {
                com.zzupocket.app.core.AppGraph.appContextOrNull()
                    ?.getExternalFilesDir(null)
                    ?.let { dir -> java.io.File(dir, "timetable-${term.id}.html").writeText(html) }
            }
        }
        runCatching {
            val xn = Regex("""id="xn"[^>]*value="(\d+)"""").find(html)?.groupValues?.get(1)
            val xqM = Regex("""id="xq_m"[^>]*value="(\d+)"""").find(html)?.groupValues?.get(1)
            android.util.Log.i("QingguoTimetable", "semesterId=${term.id} account=$account 请求 xn=${term.year} xq=${term.term - 1}；服务端页面 xn=$xn xq_m=$xqM")
        }
        val courses = QingguoTimetableParser.parse(html)
        if (courses.isEmpty()) {
            // 只在解析为空时记一次，用于定位"200 但只有几百字节"这类服务端提示页
            android.util.Log.w(
                "QingguoTimetable",
                "解析为空：len=${html.length} account=$account xn=${term.year} xq=${term.term - 1} " +
                    "hasTable=${html.contains("mytable")} head=" +
                    html.replace(Regex("\\s+"), " ").take(260),
            )
        }
        return courses
    }

    /** 展开成「每周一条」的上课记录，与课表网格/今天页共用同一套数据模型 */
    fun toSessions(semesterId: Int, courses: List<QingguoCourse>): List<Session> {
        val byUnit = periods.associateBy { it.indexNo }
        return courses.flatMap { course ->
            course.weeks.map { week ->
                val startTime = byUnit[course.startUnit]?.startText
                val endTime = byUnit[course.endUnit]?.endText
                Session(
                    id = "$semesterId:${course.name}:$week:${course.weekday}:${course.startUnit}",
                    semesterId = semesterId,
                    source = SOURCE,
                    courseCode = course.name,
                    courseName = course.name,
                    weekIndex = week,
                    weekday = course.weekday,
                    startUnit = course.startUnit,
                    endUnit = course.endUnit,
                    date = null,
                    startTime = startTime,
                    endTime = endTime,
                    teacherName = course.teacher,
                    roomText = ScheduleText.tidyPlace(course.room),
                    campusText = null,
                    color = null,
                )
            }
        }
    }

    private fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    companion object {
        const val SOURCE = "qingguo"
    }
}
