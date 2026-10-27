package com.zzupocket.app.core.school

import com.zzupocket.app.domain.Period

/**
 * 学校档案：整个 App 里**唯一**允许出现"学校专属常量"的地方。
 *
 * 改造自 ZZUPocket（郑州大学）后，本仓库只面向郑州轻工业大学；
 * 原先散落在 CasClient / EasClient / EcardClient / LibProbe 等处的域名、
 * 路径与作息表都收敛到这里，换校时只改这个文件 + 对应数据源实现。
 */
object SchoolProfile {

    // ── 标识 ──────────────────────────────────────────────────────────

    /** 应用内展示名（桌面图标名在 strings.xml 的 app_name） */
    const val APP_NAME = "泥轻助手"

    const val SCHOOL_NAME = "郑州轻工业大学"
    const val SCHOOL_SHORT = "轻工大"

    /** 学校代码（青果登录页 `var schoolcode`，也是喜鹊儿里的学校编号） */
    const val SCHOOL_CODE = "10462"

    // ── 域名 ──────────────────────────────────────────────────────────

    /** 青果 KINGOSOFT 教务（登录、课表、成绩、考试都在这一个域下） */
    const val JWGL_BASE = "https://jwgl.zzuli.edu.cn"

    /** 图书馆（WebView 内嵌） */

    /** 一卡通 / 电费自助服务：**内网地址（10.100.1.22），仅校园网可达** */
    const val CAMPUS_CARD_BASE = "https://ecard.zzuli.edu.cn"

    /** 研究生信息管理系统（树维 EAMS 经典版）；外网目前 503，疑似仅校内/VPN 可达 */
    const val GRAD_BASE = "http://yjsxxglxt.zzuli.edu.cn"

    /** 统一身份认证（一网通办入口，后续做 SSO 时用） */
    const val SSO_BASE = "https://kys.zzuli.edu.cn"

    // ── 青果路径 ──────────────────────────────────────────────────────
    // 登录路径来自 loginbar.js（已实测可用）；业务页面路径按青果通用结构写，
    // 具体 JSP 编号因校而异，联调时只需改这里。

    const val LOGIN_PAGE = "/cas/login.action"
    const val LOGON_ACTION = "/cas/logon.action"

    /** 统一身份认证入口（学校 CAS，页面标题「轻工业大学服务门户」）：短信/扫码都从这里进 */
    const val SSO_LOGIN_PATH = "/caslogin"

    /**
     * 学校统一身份认证的**完整登录地址**（由用户提供并实测）。
     * 登录成功后 CAS 会带 ticket 跳回 `jwgl.zzuli.edu.cn/caslogin`，再落到教务首页。
     */
    const val SSO_LOGIN_URL =
        "https://kys.zzuli.edu.cn/cas/login?service=https%3A%2F%2Fjwgl.zzuli.edu.cn%2Fcaslogin"
    const val TEMP_DESKEY = "/frame/homepage?method=getTempDeskey"
    const val TEMP_NOWTIME = "/frame/homepage?method=getTempNowtime"

    /** 课表查询页（`params=base64("xn=学年&xq=学期&xh=学号")`） */
    var timetablePath = "/student/wsxk.xskcb10319.jsp"

    /** 首页/菜单页，用来判断会话是否还有效 */
    var homePath = "/student/index.jsp"

    // ── 教务其它页面（来自 2026-10-25 抓包的学生菜单）──────────────────

    /** 学业成绩 */
    var gradePath = "/student/xscj.stuckcj.jsp"

    /** 成绩数据页（二次 POST：rxnj/nj/zx/fx/wz…） */
    var gradeDataPath = "/student/xscj.stuckcj_data.jsp"

    /** 考试安排表 */
    var examPath = "/student/ksap.ksapb.html"

    /** 考试数据页（二次 POST：xn/xq…） */
    var examDataPath = "/student/ksap.ksapb_date.jsp"

    /** 学籍档案-基本信息 */
    var profilePath = "/student/stu.xsxj.xjda.jbxx.html"

    /** 空闲教室查询 */
    var emptyRoomPath = "/public/dykb.kxjsinmain.html"

    /** 菜单 XML（含全部功能入口，诊断页可展开看） */
    var menuPath = "/taglib/PopMenuServlet.jsp"

    // ── 学号规则 ──────────────────────────────────────────────────────

    /**
     * 从学号推断入学年。
     *
     * 轻工大学号形如 `542501010101`：`54` 是学校代码，接着两位 `25` 是年级（2025 级）。
     * 也兼容 `20xx…` 这种直接以年份开头的学号。
     */
    fun enrollmentYearOf(account: String?): Int? {
        val a = account?.trim().orEmpty()
        if (a.length < 4) return null
        // 以年份开头：20xx……
        if (a.startsWith("20")) {
            val y = a.substring(0, 4).toIntOrNull() ?: return null
            if (y in 2000..2100) return y
        }
        // 轻工大格式：54 + 两位年级 + 其余
        if (a.startsWith("54")) {
            val yy = a.substring(2, 4).toIntOrNull() ?: return null
            if (yy in 0..99) return 2000 + yy
        }
        return null
    }

    // ── 作息时间（教务处《作息时间》，2026-10 抓取）──────────────────
    // 上午 4 节、下午 4 节、晚上 2 节，每两小节为一大节。

    val PERIODS: List<Period> = listOf(
        period(1, "08:00", "08:45", "MORNING", 1),
        period(2, "08:55", "09:40", "MORNING", 1),
        period(3, "10:00", "10:45", "MORNING", 2),
        period(4, "10:55", "11:40", "MORNING", 2),
        period(5, "14:30", "15:15", "AFTERNOON", 3),
        period(6, "15:25", "16:10", "AFTERNOON", 3),
        period(7, "16:30", "17:15", "AFTERNOON", 4),
        period(8, "17:25", "18:10", "AFTERNOON", 4),
        period(9, "19:30", "20:15", "EVENING", 5),
        period(10, "20:25", "21:10", "EVENING", 5),
    )

    private fun period(index: Int, start: String, end: String, part: String, segment: Int) = Period(
        indexNo = index,
        nameZh = "第${index}节",
        startTime = hhmm(start),
        endTime = hhmm(end),
        dayPart = part,
        segmentIndex = segment,
    )

    private fun hhmm(text: String): Int = text.replace(":", "").toInt()

    // ── 功能开关 ──────────────────────────────────────────────────────
    // 轻工大目前只确认了青果教务与图书馆；电费/校园卡/研究生教务/空教室
    // 在确认接口前一律关闭，避免界面上出现点进去就报错的入口。

    object Features {
        const val CAMPUS_CARD = true
        const val ELECTRICITY = true
        const val ROOM_OCCUPANCY = false
        const val GRAD_TIMETABLE = true
        const val LIBRARY = true
        const val GRADES = true
        const val EXAMS = true
        const val PROFILE = true
    }
}
