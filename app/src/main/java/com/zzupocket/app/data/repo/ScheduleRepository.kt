package com.zzupocket.app.data.repo

import android.util.Log
import androidx.room.withTransaction
import com.zzupocket.app.core.school.SchoolProfile
import com.zzupocket.app.data.db.AppDatabase
import com.zzupocket.app.data.db.CourseEntity
import com.zzupocket.app.data.db.CourseSessionEntity
import com.zzupocket.app.data.db.PeriodEntity
import com.zzupocket.app.data.db.SemesterEntity
import com.zzupocket.app.data.db.RoomOccupancyEntity
import com.zzupocket.app.data.db.TimetableSyncEntity
import com.zzupocket.app.data.prefs.UiPrefsStore
import com.zzupocket.app.data.qingguo.QingguoTerm
import com.zzupocket.app.data.qingguo.QingguoTimetableSource
import com.zzupocket.app.domain.RoomOccupation
import com.zzupocket.app.domain.Course
import com.zzupocket.app.domain.Period
import com.zzupocket.app.domain.Plan
import com.zzupocket.app.domain.PlanSpec
import com.zzupocket.app.domain.Semester
import com.zzupocket.app.domain.Session
import com.zzupocket.app.domain.WeekAlignment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * 课表仓库。
 *
 * 设计要点：**拉一次整学期，之后全部本地算**。切周、课程详情、课程总览都不再发请求，
 * 所以课表页很快而且**离线可用**。
 *
 * 教学周号由服务端下发（每条上课记录自带 `weekIndex`），"今天的课"优先按**真实日期**匹配
 * ——这样学校调休/调课时不需要任何特殊处理。
 */
class ScheduleRepository(
    private val db: AppDatabase,
    private val source: QingguoTimetableSource,
    private val uiPrefs: UiPrefsStore,
) {

    // ── 同步 ─────────────────────────────────────────────────────────────

    /**
     * 生成并落库学期列表（青果没有"学期列表"接口，按当前日期推最近 6 个学期）。
     * 周次基准（开学日期）来自用户在设置页填的 [UiPrefsStore.weekSettings]。
     */
    suspend fun refreshSemesters() {
        val current = source.currentTerm()
        val weekOneStart = runCatching { uiPrefs.weekSettings(current.id).weekOneStart }.getOrNull()
        val list = source.semesters(source.termsForAccount(), weekOneStart)
        if (list.isEmpty()) return

        val now = System.currentTimeMillis()
        db.withTransaction {
            db.semesterDao().upsertAll(
                list.map { it.toEntity(isCurrent = it.id == current.id, now = now) },
            )
            db.semesterDao().deleteExcept(list.map { it.id })
            db.semesterDao().clearCurrent()
            db.semesterDao().markCurrent(current.id)
        }
    }

    /**
     * 拉整学期课表并落库（青果：一次请求拿整学期，之后全部本地算）。
     * 只清理 `source = "qingguo"` 的记录 —— **自定义日程不能被误删**。
     */
    suspend fun refreshCourseTable(semesterId: Int) {
        val term = QingguoTerm.fromId(semesterId)
        val courses = source.fetchCourses(term)
        val sessions = source.toSessions(semesterId, courses)

        val courseList = sessions.groupBy { it.courseCode }.map { (code, list) ->
            Course(
                key = courseKeyOf(semesterId, code),
                semesterId = semesterId,
                code = code,
                nameZh = code,
                credits = 0.0,
                openDepartment = null,
                teachers = list.mapNotNull { it.teacherName }.distinct(),
                sessions = list,
            )
        }
        saveTimetable(SOURCE, semesterId, sessions, courseList)
    }

    /** 节次时刻表（作息）。青果不返回，直接用教务处公布的 [SchoolProfile.PERIODS]。 */
    suspend fun refreshPeriods() {
        val now = System.currentTimeMillis()
        db.periodDao().upsertAll(
            SchoolProfile.PERIODS.map { p ->
                PeriodEntity(
                    indexNo = p.indexNo,
                    nameZh = p.nameZh,
                    startTime = p.startTime,
                    endTime = p.endTime,
                    dayPart = p.dayPart,
                    segmentIndex = p.segmentIndex,
                    updatedAt = now,
                )
            },
        )
    }

    // ── 读取 ─────────────────────────────────────────────────────────────

    fun observeSemesters(): Flow<List<Semester>> =
        db.semesterDao().observeAll().map { list -> list.map { it.toDomain() } }

    fun observeCourses(semesterId: Int): Flow<List<Course>> =
        combine(
            db.courseDao().observeBySemester(semesterId),
            db.courseSessionDao().observeBySemester(semesterId),
        ) { courses, sessions -> buildCourses(courses, sessions.map { it.toDomain() }) }

    fun observePeriods(): Flow<List<Period>> =
        db.periodDao().observeAll().map { list -> list.map { it.toDomain() } }

    /** 番茄钟标签候选：课名 */
    fun observeCourseNames(): Flow<List<String>> = db.courseDao().observeAllNames()

    suspend fun periods(): List<Period> = db.periodDao().all().map { it.toDomain() }

    suspend fun semesters(): List<Semester> = db.semesterDao().all().map { it.toDomain() }

    suspend fun semester(id: Int): Semester? =
        db.semesterDao().all().firstOrNull { it.id == id }?.toDomain()

    suspend fun currentSemester(): Semester? =
        db.semesterDao().current()?.toDomain() ?: db.semesterDao().all().firstOrNull()?.toDomain()

    suspend fun courses(semesterId: Int): List<Course> = loadCourses(semesterId)

    private suspend fun loadCourses(semesterId: Int): List<Course> =
        buildCourses(
            db.courseDao().bySemester(semesterId),
            db.courseSessionDao().bySemester(semesterId).map { it.toDomain() },
        )

    private fun buildCourses(
        courses: List<CourseEntity>,
        sessions: List<Session>,
    ): List<Course> {
        val byCode = sessions.groupBy { it.courseCode }
        return courses.map { c ->
            Course(
                key = c.key,
                semesterId = c.semesterId,
                code = c.code,
                nameZh = c.nameZh,
                credits = c.credits,
                openDepartment = c.openDepartment,
                teachers = c.teachersCsv.split(",").filter { it.isNotBlank() },
                sessions = byCode[c.code].orEmpty(),
            )
        }
    }

    // ── 本地计算 ─────────────────────────────────────────────────────────

    /**
     * 由学期起始日推算某一天属于第几教学周；落在学期外（假期）返回 null。
     *
     * 算法在 [WeekAlignment.indexOf]（纯函数、有单测）：对齐到周首再算，
     * 这样即使 startDate 是周中某天也不会错一周。
     */
    fun resolveCurrentWeek(semester: Semester, today: LocalDate = LocalDate.now()): Int? =
        WeekAlignment.indexOf(
            startDate = semester.startDate,
            weekStartOnSunday = semester.weekStartOnSunday,
            weekIndices = semester.weekIndices,
            date = today,
        )

    /**
     * 只有日期、没有周网格信息的记录（临时补课）：按日期算出「星期 + 教学周」。
     *
     * 星期直接用 ISO（周一=1…周日=7，与教务一致）；教学周用同一套对齐算法，
     * 落到学期周次之外（比如假期补课）就返回 0 —— 那种记录只能按日期匹配显示，不硬塞进某一周。
     */
    private fun weekdayAndWeekOf(semester: Semester?, date: String?): Pair<Int, Int>? {
        val d = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val week = semester?.let { resolveCurrentWeek(it, d) } ?: 0
        return d.dayOfWeek.value to week
    }

    /**
     * 某一天的课。
     *
     * **优先按真实日期匹配**（教务每条记录都带 date），这样学校调休/调课自动正确；
     * 只有少数记录没有日期时才回退到「星期 + 教学周」。
     */
    suspend fun sessionsOn(
        semesterId: Int,
        date: LocalDate,
        currentWeek: Int? = null,
    ): List<Session> {
        val all = db.courseSessionDao().bySemester(semesterId).map { it.toDomain() }
        val iso = date.toString()
        val byDate = all.filter { it.date == iso }
        val wd = date.dayOfWeek.value // ISO: 周一=1 … 周日=7，与教务的 weekday 一致
        val withoutDate = if (currentWeek == null) {
            emptyList()
        } else {
            all.filter { it.date == null && it.weekday == wd && it.weekIndex == currentWeek }
        }
        return (byDate + withoutDate).distinctBy { it.id }.sortedBy { it.startUnit }
    }

    /** 某一教学周的全部课（用于周课表网格） */
    suspend fun sessionsOfWeek(semesterId: Int, weekIndex: Int): List<Session> =
        db.courseSessionDao().bySemester(semesterId)
            .map { it.toDomain() }
            .filter { it.weekIndex == weekIndex }

    /**
     * 整学期全部课。
     * 界面一次性拿全，翻周时在内存里过滤 —— 这样滑周不会闪空白，也不发请求。
     */
    suspend fun sessionsOfSemester(semesterId: Int): List<Session> =
        db.courseSessionDao().bySemester(semesterId).map { it.toDomain() }

    fun observeSessionsOfWeek(semesterId: Int, weekIndex: Int): Flow<List<Session>> =
        db.courseSessionDao().observeBySemester(semesterId).map { list ->
            list.map { it.toDomain() }.filter { it.weekIndex == weekIndex }
        }

    suspend fun hasCourseData(semesterId: Int): Boolean =
        db.courseSessionDao().countBySemester(semesterId) > 0

    /**
     * 诊断：**同一个 (周, 星期, 起始节次) 上有多门课**的格子有多少个（`ScheduleGrid` 标签）。
     *
     * 为什么要记：课表页一个格子只会画**第一门**匹配的课（`ScheduleScreen` 里是 `firstOrNull`），
     * 而且被跨节大块覆盖的格子会被整段跳过 —— 学校新加的**补课**如果正好落在已有课的格子，
     * 在网格里就是看不见的。只记格子坐标与条数，**不含课程名/教室**。
     */
    private fun logGridCollisions(rows: List<CourseSessionEntity>) {
        runCatching {
            val dated = rows.filter { it.weekIndex > 0 && it.weekday in 1..7 }
            val collisions = dated
                .groupBy { Triple(it.weekIndex, it.weekday, it.startUnit) }
                .filter { (_, list) -> list.map { it.courseKey }.distinct().size > 1 }
            val dates = rows.mapNotNull { it.date }.distinct().sorted()
            // 星期分布：**周末有没有课**是判断"这个接口给不给补课"的关键 ——
            // 学校自己的课表能显示周六/周日的补课，如果这里一周七天里只有周一到周五，
            // 基本可以断定这个接口只返回常规周课表，补课来自别处。
            val weekdayCounts = (1..7).joinToString(" ") { d ->
                val n = dated.count { it.weekday == d }
                "周${"一二三四五六日"[d - 1]}$n"
            }
            Log.i(
                TAG_GRID,
                "课表入库：${rows.size} 条，日期 ${dates.size} 天（${dates.joinToString(",")}）；" +
                    "星期分布($weekdayCounts)；" +
                    "同格多门课（网格只会画第一门）的格子 ${collisions.size} 个：" +
                    collisions.entries.take(12).joinToString(" ") { (k, v) ->
                        "第${k.first}周${k.second}/${k.third}节×${v.map { it.courseKey }.distinct().size}"
                    },
            )
        }
    }

    // ── 自定义日程 ──────────────────────────────────────────────────────

    /**
     * 一个自定义日程 = 若干周的同一条记录（每周一行），和教务课程一个存法。
     * 这样网格、今天、翻周全都不用改；教务刷新时也只删 source = 'eas' 的行。
     */
    suspend fun customPlans(semesterId: Int): List<Plan> =
        db.courseSessionDao().bySemesterAndSource(semesterId, SOURCE_CUSTOM)
            .groupBy { it.courseKey }
            .map { (courseKey, rows) ->
                val head = rows.minByOrNull { it.weekIndex } ?: rows.first()
                Plan(
                    // 表格里没有单列 courseCode，日程标识就存在 courseKey 里
                    code = courseKey.removePrefix("$semesterId:"),
                    semesterId = semesterId,
                    name = head.courseName,
                    weekday = head.weekday,
                    startUnit = head.startUnit,
                    endUnit = head.endUnit,
                    weeks = rows.map { it.weekIndex }.distinct().sorted(),
                    roomText = head.roomText,
                    colorKey = head.color,
                )
            }
            .sortedWith(compareBy({ it.weekday }, { it.startUnit }))

    /** 新建或改写一个日程；返回它的 code（新建时是刚生成的） */
    suspend fun savePlan(spec: PlanSpec): String {
        val code = spec.code?.takeIf { it.isNotBlank() } ?: newPlanCode()
        val dao = db.courseSessionDao()
        // 先删旧行：改了周次/时段之后不能留下孤儿记录
        dao.deleteByCourseKey(courseKeyOf(spec.semesterId, code))

        val periods = periods().associateBy { it.indexNo }
        val startTime = periods[spec.startUnit]?.startText
        val endTime = periods[spec.endUnit]?.endText
        val now = System.currentTimeMillis()
        val rows = spec.weeks.distinct().sorted().map { week ->
            CourseSessionEntity(
                id = sessionIdOf(spec.semesterId, code, week, spec.weekday, spec.startUnit),
                courseKey = courseKeyOf(spec.semesterId, code),
                semesterId = spec.semesterId,
                source = SOURCE_CUSTOM,
                courseName = spec.name.trim(),
                weekIndex = week,
                weekday = spec.weekday,
                startUnit = spec.startUnit,
                endUnit = spec.endUnit,
                // 自定义日程不绑定真实日期：它按"教学周 + 星期"重复，
                // 学校调休时不会跟着跑（那本来也不是这门"课"的事）。
                date = null,
                startTime = startTime,
                endTime = endTime,
                teacherName = null,
                roomText = spec.roomText?.trim()?.takeIf { it.isNotEmpty() },
                campusText = null,
                color = spec.colorKey,
                updatedAt = now,
            )
        }
        if (rows.isNotEmpty()) dao.upsertAll(rows)
        return code
    }

    suspend fun deletePlan(semesterId: Int, code: String) =
        db.courseSessionDao().deleteByCourseKey(courseKeyOf(semesterId, code))

    /**
     * 这个日程和哪些教务课程撞了（同一天、周次有交集、节次有重叠）。
     * 只提醒不阻止 —— 有些"撞课"其实是自己想要的重叠安排。
     */
    suspend fun planConflicts(semesterId: Int, spec: PlanSpec): List<Session> {
        val weeks = spec.weeks.toSet()
        if (weeks.isEmpty()) return emptyList()
        return db.courseSessionDao().bySemester(semesterId)
            .filter { it.source == SOURCE_EAS }
            .map { it.toDomain() }
            .filter {
                it.weekday == spec.weekday &&
                    it.weekIndex in weeks &&
                    it.startUnit <= spec.endUnit &&
                    spec.startUnit <= it.endUnit
            }
            .distinctBy { it.courseName to it.startUnit }
            .sortedBy { it.startUnit }
    }

    /** 已保存的学号 —— 用来推断入学年（学号前 4 位） */
    suspend fun settingsAccount(): String? = db.settingsDao().get()?.account

    // ── 课表缓存：写入与同步状态（本科/研究生共用）──────────────────────

    /**
     * 把某个来源的课表整体写入（先按来源删、再插；**不会碰 `custom` 日程**）。
     *
     * 研究生走这条：`semesterId = GmsScheduleMapper.GRAD_SEMESTER_ID`、`source = "gms"`。
     *
     * @return true = 内容与上次不同（界面上课表确实变了）；false = 只是刷新了一遍，内容一模一样
     *   —— 后者按用户要求**界面一个字都不该动**，但"获取时间"仍会更新。
     */
    suspend fun saveTimetable(
        source: String,
        semesterId: Int,
        sessions: List<Session>,
        courses: List<Course>,
        fetchedAt: Long = System.currentTimeMillis(),
    ): Boolean {
        val hash = timetableHash(sessions)
        val existing = db.timetableSyncDao().get(source, semesterId)
        val changed = existing?.contentHash != hash
        val courseEntities = courses.map { c ->
            CourseEntity(
                key = courseKeyOf(semesterId, c.code),
                semesterId = semesterId,
                code = c.code,
                nameZh = c.nameZh,
                credits = c.credits,
                openDepartment = c.openDepartment,
                teachersCsv = c.teachers.joinToString(","),
                updatedAt = fetchedAt,
            )
        }
        val sessionEntities = sessions.map { s ->
            CourseSessionEntity(
                id = s.id,
                courseKey = courseKeyOf(semesterId, s.courseCode),
                semesterId = semesterId,
                source = source,
                courseName = s.courseName,
                weekIndex = s.weekIndex,
                weekday = s.weekday,
                startUnit = s.startUnit,
                endUnit = s.endUnit,
                date = s.date,
                startTime = s.startTime,
                endTime = s.endTime,
                teacherName = s.teacherName,
                roomText = s.roomText,
                campusText = s.campusText,
                color = s.color,
                updatedAt = fetchedAt,
            )
        }
        db.withTransaction {
            db.courseSessionDao().deleteBySemesterAndSource(semesterId, source)
            db.courseDao().deleteBySemester(semesterId)
            db.courseDao().upsertAll(courseEntities)
            db.courseSessionDao().upsertAll(sessionEntities)
            db.timetableSyncDao().upsert(
                TimetableSyncEntity(
                    source = source,
                    semesterId = semesterId,
                    fetchedAt = fetchedAt,
                    lastError = null,
                    lastErrorAt = null,
                    contentHash = hash,
                    cacheVersion = CACHE_VERSION,
                ),
            )
        }
        return changed
    }

    /**
     * 最近一次成功抓取的时间（毫秒）；null = 从没成功过，或缓存结构版本已过时。
     *
     * 版本过时返回 null 的效果是"当作没有缓存、重新拉"，比拿旧结构渲染出错更稳。
     */
    suspend fun lastFetchedAt(source: String, semesterId: Int): Long? {
        val row = db.timetableSyncDao().get(source, semesterId) ?: return null
        if (row.cacheVersion != CACHE_VERSION) return null
        return row.fetchedAt.takeIf { it > 0L }
    }

    /** 缓存结构版本；改字段就 +1，旧缓存会被当作不存在 */
    private fun timetableHash(sessions: List<Session>): String = sessions
        .map {
            "${it.weekIndex}|${it.weekday}|${it.startUnit}|${it.endUnit}|" +
                "${it.courseName}|${it.roomText.orEmpty()}|${it.teacherName.orEmpty()}"
        }
        .sorted()
        .joinToString("\n")
        .hashCode()
        .toString()

    /** 记一次失败原因（供「失败但保留缓存」的提示用；不要吞掉调用方的异常） */
    suspend fun markSyncError(source: String, semesterId: Int, error: String) {
        val now = System.currentTimeMillis()
        val existing = db.timetableSyncDao().get(source, semesterId)
        if (existing == null) {
            db.timetableSyncDao().upsert(
                TimetableSyncEntity(source, semesterId, 0L, error.take(200), now),
            )
        } else {
            db.timetableSyncDao().markError(source, semesterId, error.take(200), now)
        }
    }

    /** 某个来源已经缓存了多少节课（判断"有没有缓存可显示"用） */
    suspend fun cachedSessionCount(source: String, semesterId: Int): Int =
        db.courseSessionDao().bySemesterAndSource(semesterId, source).size

    /** 读某个来源的缓存（返回领域模型；没有缓存就是空表） */
    suspend fun cachedSessions(source: String, semesterId: Int): List<Session> =
        db.courseSessionDao().bySemesterAndSource(semesterId, source).map { it.toDomain() }

    fun observeTimetableSync(source: String, semesterId: Int): Flow<TimetableSyncEntity?> =
        db.timetableSyncDao().observe(source, semesterId)

    /**
     * 清掉某个来源的缓存（退出登录时用）。
     *
     * 课表是按账号抓的，退出后不该留在设备上；`custom` 日程不在此列（那是用户自己的数据）。
     */
    suspend fun clearTimetable(source: String, semesterId: Int) {
        db.withTransaction {
            db.courseSessionDao().deleteBySemesterAndSource(semesterId, source)
            db.courseDao().deleteBySemester(semesterId)
            db.timetableSyncDao().clearBySource(source)
        }
    }

    // ── 空教室快照（只缓存当天；跨天即失效）────────────────────────────

    /** 写某一天、某校区的教室占用快照（覆盖写，并顺手清掉更早的） */
    suspend fun saveRoomSnapshot(
        date: String,
        campusId: Long,
        rows: List<RoomCacheRow>,
        fetchedAt: Long = System.currentTimeMillis(),
    ) {
        db.withTransaction {
            db.roomOccupancyDao().deleteByDateAndCampus(date, campusId)
            db.roomOccupancyDao().upsertAll(
                rows.map { r ->
                    RoomOccupancyEntity(
                        date = date,
                        roomId = r.roomId,
                        campusId = campusId,
                        code = r.code,
                        buildingId = r.buildingId,
                        buildingName = r.buildingName,
                        campusName = r.campusName,
                        occupations = r.occupations.joinToString(",") {
                            "${it.startTime}-${it.endTime}"
                        },
                        fetchedAt = fetchedAt,
                    )
                },
            )
            // 保留窗口：今天 ~ 今天+6（用户拍定的"七天"= 今天往后看六天）。
            // ⚠️ 必须按**今天**算，不能按"这次抓取的日期"算 —— 否则切到未来某天抓一次，
            // 会把今天的快照顺手删掉（旧实现正是这样）。
            run {
                val today = java.time.LocalDate.now()
                db.roomOccupancyDao().pruneOutside(
                    minDate = today.toString(),
                    maxDate = today.plusDays(ROOM_KEEP_DAYS - 1L).toString(),
                )
            }
        }
    }

    /** 读某一天、某校区的快照；没有返回 null（调用方据此决定"没数据可用"） */
    suspend fun cachedRoomSnapshot(date: String, campusId: Long): CachedRoomSnapshot? {
        val rows = db.roomOccupancyDao().byDateAndCampus(date, campusId)
        if (rows.isEmpty()) return null
        val at = db.roomOccupancyDao().fetchedAt(date, campusId) ?: return null
        return CachedRoomSnapshot(
            fetchedAt = at,
            rows = rows.map { e ->
                RoomCacheRow(
                    roomId = e.roomId,
                    code = e.code,
                    buildingId = e.buildingId,
                    buildingName = e.buildingName,
                    campusName = e.campusName,
                    occupations = e.occupations.split(',')
                        .mapNotNull { part ->
                            val a = part.substringBefore('-').toIntOrNull()
                            val b = part.substringAfter('-', "").toIntOrNull()
                            if (a != null && b != null) RoomOccupation(a, b) else null
                        },
                )
            },
        )
    }

    companion object {
        /** 青果教务课表的来源标记（换校时只改这里与数据源实现） */
        const val SOURCE = "qingguo"

        /** 旧郑大 EAS 来源（保留常量，便于清理历史缓存数据） */
        const val SOURCE_EAS = "eas"
        const val SOURCE_CUSTOM = "custom"

        /** 课表缓存结构版本；改字段就 +1，旧缓存会被当作不存在 */
        const val CACHE_VERSION = 1

        /** 空教室快照保留天数（今天 + 往后 6 天） */
        const val ROOM_KEEP_DAYS = 7

        private const val TAG_GRID = "ScheduleGrid"

        fun courseKeyOf(semesterId: Int, courseCode: String) = "$semesterId:$courseCode"

        /**
         * 稳定主键：同一学期 + 同一门课 + 同一周 + 同一天 + 同一节 + **同一日期** = 同一节课。
         *
         * [date] 必须进主键：学校的**补课**可能和正常课落在同一周同一天同一节
         * （比如把周二的课补在某周周二），不含日期就会互相覆盖，只剩一条。
         * 自定义日程没有日期（按周重复），传 null 即可。
         */
        fun sessionIdOf(
            semesterId: Int,
            courseCode: String,
            weekIndex: Int,
            weekday: Int,
            startUnit: Int,
            date: String? = null,
        ) = "$semesterId:$courseCode:$weekIndex:$weekday:$startUnit:${date.orEmpty()}"

        fun newPlanCode(): String = "P" + System.currentTimeMillis().toString(36)
    }
}

// ── 实体 ↔ 领域模型 ─────────────────────────────────────────────────────

private fun Semester.toEntity(isCurrent: Boolean, now: Long) = SemesterEntity(
    id = id,
    code = nameZh,
    nameZh = nameZh,
    schoolYear = schoolYear,
    startDate = startDate?.toString(),
    endDate = endDate?.toString(),
    weekStartOnSunday = weekStartOnSunday,
    weekIndicesCsv = weekIndices.joinToString(","),
    isCurrent = isCurrent,
    updatedAt = now,
)

private fun SemesterEntity.toDomain() = Semester(
    id = id,
    nameZh = nameZh,
    schoolYear = schoolYear,
    startDate = startDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    endDate = endDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    weekStartOnSunday = weekStartOnSunday,
    weekIndices = weekIndicesCsv.split(",").mapNotNull { it.trim().toIntOrNull() }.sorted(),
    isCurrent = isCurrent,
)

private fun CourseSessionEntity.toDomain() = Session(
    id = id,
    semesterId = semesterId,
    source = source,
    courseCode = courseKey.substringAfterLast(':'),
    courseName = courseName,
    weekIndex = weekIndex,
    weekday = weekday,
    startUnit = startUnit,
    endUnit = endUnit,
    date = date,
    startTime = startTime,
    endTime = endTime,
    teacherName = teacherName,
    roomText = roomText,
    campusText = campusText,
    color = color,
)

private fun PeriodEntity.toDomain() = Period(
    indexNo = indexNo,
    nameZh = nameZh,
    startTime = startTime,
    endTime = endTime,
    dayPart = dayPart,
    segmentIndex = segmentIndex,
)

/** 空教室快照里的一行（原始占用时间段，展示时再按节次表算格子） */
data class RoomCacheRow(
    val roomId: Long,
    val code: String,
    val buildingId: Long,
    val buildingName: String,
    val campusName: String,
    val occupations: List<RoomOccupation>,
)

/** 某天某校区的教室占用快照 */
data class CachedRoomSnapshot(
    val fetchedAt: Long,
    val rows: List<RoomCacheRow>,
)
