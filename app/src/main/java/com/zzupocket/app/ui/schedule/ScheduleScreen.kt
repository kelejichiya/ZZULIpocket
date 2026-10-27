package com.zzupocket.app.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.core.ScheduleText
import com.zzupocket.app.core.WeekMath
import com.zzupocket.app.data.gms.GmsScheduleSource
import com.zzupocket.app.data.prefs.BlockBorder
import com.zzupocket.app.data.prefs.CourseColorMode
import com.zzupocket.app.data.prefs.CoursePalettePreset
import com.zzupocket.app.data.prefs.CourseTextMode
import com.zzupocket.app.data.prefs.GridDensity
import com.zzupocket.app.data.prefs.UiPrefs
import com.zzupocket.app.data.repo.ScheduleRepository
import com.zzupocket.app.domain.Course
import com.zzupocket.app.domain.Period
import com.zzupocket.app.domain.Semester
import com.zzupocket.app.domain.Session
import com.zzupocket.app.domain.toSlotLines
import java.time.LocalDate
import java.time.LocalTime

private val CELL_HEIGHT = 54.dp
private val UNIT_COL_WIDTH = 56.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    onOpenLogin: () -> Unit,
    onOpenOverview: (Int) -> Unit,
    onOpenDisplaySettings: () -> Unit,
    vm: ScheduleViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val prefs by AppGraph.uiPrefsStore.flow.collectAsStateWithLifecycle(initialValue = UiPrefs())
    var showSemesters by remember { mutableStateOf(false) }
    var showWeekPicker by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }

    Scaffold(
        // 壁纸画在外壳层，这里必须透明，否则页面自己的底色会把壁纸盖掉
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                // 左上角不显示"课表"二字，直接放学期切换；研究生视图显示研究生学期名
                title = {
                    if (state.gradMode) {
                        Text(
                            text = state.gmsSemester?.displayName ?: "研究生课表",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    } else {
                        SemesterButton(state.selectedSemester) { showSemesters = true }
                    }
                },
                actions = {
                    if (!state.gradMode) {
                        IconButton(onClick = { vm.openPlanEditor() }) {
                            Icon(Icons.Filled.Add, contentDescription = "添加日程")
                        }
                    }
                    IconButton(onClick = { if (state.gradMode) vm.loadGmsWeek(force = true) else vm.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新课表")
                    }
                    // 顶栏只留一个设置入口：教学周、显示、背景、入学年、课程总览都在里面
                    IconButton(onClick = onOpenDisplaySettings) {
                        Icon(Icons.Filled.Tune, contentDescription = "课程表设置")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 研究生账号在本科教务里本来就没有角色：那条 401/403 说明对他是噪音，
            // 而且与下面的「研究生课表」区块自相矛盾（一边说没权限、一边请他登录研究生系统）。
            // 切到研究生视图后不再显示这条错误；研究生链路的错误由 grad 区块自己的 gmsError 负责。
            state.error?.takeIf { !state.gradMode }?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            // 缓存提示：一行小灰字，点了重试。刻意不用红色 —— 红色错误会让人以为账号出问题
            // （历史 issue #2 反复重登就是这么来的）。刷新成功且内容没变时这里是 null，什么都不显示。
            state.cacheNotice?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            // 登录过期 → 点它去重新登录（重试是没用的，只会再失败一次）；
                            // 其他失败（网络/教务挂了）→ 重试
                            if (state.cacheNoticeNeedsLogin) {
                                vm.openGmsLogin()
                            } else if (state.gradMode) {
                                vm.loadGmsWeek(force = true)
                            } else {
                                vm.refresh()
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }

            state.notice?.let { msg ->
                Text(
                    text = "$msg（点一下收起）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.dismissNotice() }
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            when {
                // 研究生：整学期数据已经映射成本科的 Session/Course，直接复用同一套周网格。
                // 没有数据时才退回下面的登录/空态区块。
                state.gradMode && state.gmsSessions.isNotEmpty() -> {
                    WeekHeader(
                        week = state.gmsWeekIndex,
                        total = state.gmsWeekCount,
                        onPick = { showWeekPicker = true },
                        onWallpaper = prefs.backgroundPath != null,
                        confirmed = state.weekConfirmed,
                        onOpenSettings = onOpenDisplaySettings,
                    )
                    WeekPager(
                        weeks = state.gmsWeeks,
                        weekIndex = state.gmsWeekIndex,
                        sessionsOf = state::gmsSessionsOf,
                        periods = state.gmsPeriods,
                        weekMondayOf = { week ->
                            state.weekStart
                                ?.let { WeekMath.dateOf(it, week, 1, state.weekStartDay) }
                                ?: ScheduleText.mondayOf(state.gmsSemester?.startDate, week)
                        },
                        onWeekSelected = vm::goToGmsWeek,
                        prefs = prefs,
                        onSessionClick = vm::onSessionClick,
                    )
                }

                // gradMode 优先：从 0.31.0 覆盖升级的账号可能已有学期缓存，weeks 非空；
                // 只看 weeks 会让研究生区块和底部兜底入口都被本科周表格挡住。
                state.weeks.isEmpty() -> NoSemesterBlock(
                    loggedIn = state.loggedIn,
                    loading = state.loading,
                    gradMode = state.gradMode,
                    gmsLessons = state.gmsLessons,
                    gmsBusy = state.gmsBusy,
                    gmsError = state.gmsError,
                    gmsDialogOpen = state.gmsDialogOpen,
                    onLogin = onOpenLogin,
                    onRefresh = vm::refresh,
                    onGmsLogin = vm::openGmsLogin,
                    onGmsRefresh = { vm.loadGmsWeek(force = true) },
                    onGmsDialogDismiss = vm::closeGmsLogin,
                    onGmsAdopt = vm::adoptGmsSession,
                    onGradFallback = vm::useGradSchedule,
                )

                else -> {
                    WeekHeader(
                        week = state.weekIndex,
                        total = state.weekCount,
                        onPick = { showWeekPicker = true },
                        onWallpaper = prefs.backgroundPath != null,
                        confirmed = state.weekConfirmed,
                        onOpenSettings = onOpenDisplaySettings,
                    )
                    // 周次还没解析出来之前**不要挂翻页器**：它的初始页取自 state.weekIndex，
                    // 而 bootstrap 会先给 weeks（19 周）、后给真实周次，中间那一小段
                    // weekIndex 还是默认的 1 —— 于是翻页器从第 1 周挂载、再一路动画滚到本周，
                    // 动画每经过一页都会被写回 ViewModel（详见 WeekPager 的注释）。
                    // 等解析完再挂，初始页直接就是本周，没有"从第 1 周滚过来"这一出。
                    val weekReady = state.weekResolvedOn != null || !state.loading
                    if (weekReady) {
                        WeekPager(
                            weeks = state.weeks,
                            weekIndex = state.weekIndex,
                            sessionsOf = state::sessionsOf,
                            periods = state.periods,
                            weekMondayOf = { week ->
                                state.weekStart
                                    ?.let { WeekMath.dateOf(it, week, 1, state.weekStartDay) }
                                    ?: ScheduleText.mondayOf(state.selectedSemester?.startDate, week)
                            },
                            onWeekSelected = vm::goToWeek,
                            prefs = prefs,
                            // 点课程块 → 教务课开详情，自定义日程开编辑框
                            onSessionClick = vm::onSessionClick,
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }

    if (showSemesters) {
        SemesterSheet(
            semesters = state.semesters,
            selectedId = state.selectedSemester?.id,
            onPick = { vm.selectSemester(it); showSemesters = false },
            onDismiss = { showSemesters = false },
        )
    }

    if (showWeekPicker) {
        WeekPickerDialog(
            weeks = if (state.gradMode) state.gmsWeeks else state.weeks,
            current = if (state.gradMode) state.gmsWeekIndex else state.weekIndex,
            onPick = {
                if (state.gradMode) vm.goToGmsWeek(it) else vm.goToWeek(it)
                showWeekPicker = false
            },
            onDismiss = { showWeekPicker = false },
        )
    }

    state.detailCourse?.let { course ->
        CourseDetailSheet(course = course, onDismiss = vm::closeDetail)
    }

    if (state.planEditorOpen) {
        PlanEditDialog(
            initial = state.editingPlan,
            semesterId = state.selectedSemester?.id ?: 0,
            availableWeeks = state.weeks.ifEmpty { (1..18).toList() },
            maxUnit = state.periods.size.coerceAtLeast(10),
            currentWeek = state.weekIndex,
            onDismiss = vm::closePlanEditor,
            onSave = vm::savePlan,
            onDelete = { plan -> vm.deletePlan(plan.code) },
        )
    }

    if (state.pendingPlan != null) {
        PlanConflictDialog(
            conflicts = state.pendingConflicts,
            onConfirm = vm::confirmPendingPlan,
            onDismiss = vm::dismissPendingPlan,
        )
    }
}

@Composable
private fun SemesterButton(semester: Semester?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 4.dp),
    ) {
        Text(
            text = semester?.displayName ?: "选择学期",
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(Icons.Filled.ArrowDropDown, contentDescription = "切换学期")
    }
}

@Composable
private fun WeekHeader(
    week: Int,
    total: Int,
    onPick: () -> Unit,
    onWallpaper: Boolean = false,
    /** 周次参数是否已确认；false 时表头下面给一行小灰字提醒（不抢眼，但看得见） */
    confirmed: Boolean = true,
    onOpenSettings: (() -> Unit)? = null,
) {
    // 周次条压在照片上（或深色主题上）时用纯白：原来的 onSurface 在照片上会糊成一片。
    // 浅色主题 + 没有壁纸时仍用主题文字色 —— 白字落在近白底色上等于看不见。
    val onDarkBase = onWallpaper || MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val titleColor = if (onDarkBase) Color.White else MaterialTheme.colorScheme.onSurface
    // 白字要配深色投影（wallpaperShadow 给的是主题色投影，浅色主题下等于白字白边）
    val titleShadow = if (onWallpaper) {
        Shadow(
            color = Color.Black.copy(alpha = 0.55f),
            offset = Offset(0f, 1.5f),
            blurRadius = 4f,
        )
    } else {
        null
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable(onClick = onPick),
            ) {
                // 只留周次。原来下面还有一行「9/21 - 9/27」的日期区间，用户要求删掉 ——
                // "这是哪几天"改由星期表头下面那行日期回答（每一列都有，翻到哪一周都看得出来）。
                // 也刻意不再显示「9/7 起」：用户觉得表头上挂基准日太啰嗦，基准改到设置页里看。
                Text(
                    text = "第 $week 周 / 共 $total 周",
                    style = MaterialTheme.typography.titleMedium.copy(shadow = titleShadow),
                    fontWeight = FontWeight.SemiBold,
                    color = titleColor,
                )
            }
        }
        if (!confirmed && onOpenSettings != null) {
            // 只在"周次是自动猜的、还没确认"时出现，确认后永久消失。
            // 用一行小灰字而不是按钮：它是个提醒，不该抢周次的视觉重量。
            Text(
                text = "周次为自动推断，可在课程表设置里确认",
                style = MaterialTheme.typography.labelSmall.copy(shadow = titleShadow),
                color = if (onDarkBase) {
                    Color.White.copy(alpha = 0.78f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .clickable(onClick = onOpenSettings)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/** 壁纸开着时给直接压在照片上的字加一层极轻投影 —— 比给文字垫实色块自然得多 */
private fun wallpaperShadow(enabled: Boolean, surface: Color): Shadow? = if (enabled) {
    Shadow(
        color = surface.copy(alpha = 0.7f),
        offset = Offset(0f, 1.5f),
        blurRadius = 4f,
    )
} else {
    null
}

/** 星期表头行的高度：周几（约 16dp）+ 日期小字（约 12dp）+ 一点余量 */
private val WEEKDAY_HEADER_HEIGHT = 38.dp

@Composable
private fun WeekPager(
    weeks: List<Int>,
    weekIndex: Int,
    sessionsOf: (Int) -> List<Session>,
    periods: List<Period>,
    weekMondayOf: (Int) -> LocalDate?,
    onWeekSelected: (Int) -> Unit,
    prefs: UiPrefs,
    onSessionClick: (Session) -> Unit,
) {
    val initial = weeks.indexOf(weekIndex).coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initial, pageCount = { weeks.size })

    // ① 用户滑动 → 通知 ViewModel。
    //
    // ⚠️ 两个要点，都是真机上踩出来的（课表页停在上一周）：
    //  · 只听 **settledPage**（松手落定后的页），不听 currentPage：后者在动画过程中
    //    会逐页变化，等于把动画的中间态写回 ViewModel；
    //  · **程序化滚动期间（isScrollInProgress）一律不写**：否则"滚到本周"的动画
    //    每经过一页就改一次 weekIndex，而 weekIndex 一变又会取消正在跑的动画
    //    （下面 ② 重新执行）—— 两个 effect 互相驱动，最后稳定停在中途某一周。
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage to pagerState.isScrollInProgress }
            .collect { (page, scrolling) ->
                if (scrolling) return@collect
                weeks.getOrNull(page)?.let { if (it != weekIndex) onWeekSelected(it) }
            }
    }
    // ② ViewModel 变化（点周次选择、隔天自动回到本周）→ 滚过去。
    //    这是单向的：①不会回写中间态，所以不会再打架。
    LaunchedEffect(weekIndex, weeks) {
        val idx = weeks.indexOf(weekIndex)
        if (idx >= 0 && idx != pagerState.currentPage) pagerState.animateScrollToPage(idx)
    }

    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        val week = weeks.getOrNull(page)
        if (week != null) {
            ScheduleGrid(
                sessions = sessionsOf(week),
                periods = periods,
                prefs = prefs,
                // 星期表头下面那行日期要用它：算不出来（没学期/没起始日）就不显示日期
                weekMonday = weekMondayOf(week),
                onSessionClick = onSessionClick,
            )
        }
    }
}

@Composable
private fun ScheduleGrid(
    sessions: List<Session>,
    periods: List<Period>,
    prefs: UiPrefs,
    /** 这一周周一的日期；null = 算不出来（没选学期/没同步），表头就只显示周几 */
    weekMonday: java.time.LocalDate?,
    onSessionClick: (Session) -> Unit,
) {
    val days = 1..prefs.dayCount
    val cellH = prefs.cellHeightDp.dp
    val gap = if (prefs.blockBorder == BlockBorder.NONE) 0.75.dp else 1.dp
    // ⚠️ 必须看**主题**的深浅，不能看系统：设置里可以强制「始终深色 / 始终浅色」。
    // 之前用 isSystemInDarkTheme()，强制深色时自定义日程的底色仍按浅色算，
    // 和页面底色对不上（切深浅色时课表像没跟着切）。
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val onWallpaper = prefs.backgroundPath != null
    val todayIso = LocalDate.now().toString()
    val today = LocalDate.now()
    val now = LocalTime.now()
    // 有壁纸时，星期表头和节次时间这些直接压在照片上的小字加一层轻投影
    val labelShadow = wallpaperShadow(onWallpaper, MaterialTheme.colorScheme.surface)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    // 左侧节次列：压在照片/深色主题上时用白色（原来 onSurface 在照片上几乎看不清）。
    // 浅色主题且没有壁纸时保留主题色，避免白字落在近白底色上。
    val onDarkBase = onWallpaper || dark
    val unitColor = if (onDarkBase) Color.White else MaterialTheme.colorScheme.onSurface
    val unitTimeColor = if (onDarkBase) {
        Color.White.copy(alpha = 0.75f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val unitShadow = if (onWallpaper) {
        Shadow(
            color = Color.Black.copy(alpha = 0.55f),
            offset = Offset(0f, 1.5f),
            blurRadius = 4f,
        )
    } else {
        null
    }

    // ⚠️ 表头**不放在可滚动区里**：往下翻课表时"周几 + 日期"要一直看得见
    // （用户要求固定住；它只是一行普通 Row，不吃横向拖动，所以左右翻周的手感不受影响）。
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(WEEKDAY_HEADER_HEIGHT)) {
            Spacer(Modifier.width(UNIT_COL_WIDTH))
            for (d in days) {
                val date = weekMonday?.plusDays((d - 1).toLong())
                val isToday = date != null && date == today
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = ScheduleText.weekdayName(d).removePrefix("周"),
                        style = MaterialTheme.typography.labelMedium.copy(shadow = labelShadow),
                        color = labelColor,
                    )
                    // 日期：10-01 这种写法（带月份、补零）。今天那一列用主色 + 加粗标出来
                    if (date != null) {
                        Text(
                            text = ScheduleText.dayLabel(date),
                            fontSize = 9.sp,
                            lineHeight = 11.sp,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                            color = if (isToday) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                labelColor
                            },
                            style = LocalTextStyle.current.copy(shadow = labelShadow),
                        )
                    }
                }
            }
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            val gridLine = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
            Row(
                modifier = Modifier.fillMaxWidth().drawBehind {
                    val unitW = UNIT_COL_WIDTH.toPx()
                    val rowH = cellH.toPx()
                    val gapPx = gap.toPx()
                    val stroke = 0.7.dp.toPx()
                    val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                    // 横向虚线：每节课的上边界 + 最后一节的下边界
                    for (i in 0 until 10) {
                        val y = (rowH + gapPx) * i
                        drawLine(gridLine, Offset(0f, y), Offset(size.width, y), stroke, pathEffect = dash)
                    }
                    drawLine(
                        gridLine,
                        Offset(0f, size.height),
                        Offset(size.width, size.height),
                        stroke,
                        pathEffect = dash,
                    )
                    // 纵向虚线：时间列与各天之间
                    val dayCount = days.count()
                    val dayW = (size.width - unitW) / dayCount
                    for (i in 0..dayCount) {
                        val x = unitW + dayW * i
                        drawLine(gridLine, Offset(x, 0f), Offset(x, size.height), stroke, pathEffect = dash)
                    }
                },
            ) {
                // 左侧节次列：固定 10 格，与右侧各天严格对齐
                Column(
                    modifier = Modifier.width(UNIT_COL_WIDTH),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    for (unit in 1..10) {
                        Column(
                            modifier = Modifier.fillMaxWidth().height(cellH),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = "$unit",
                                style = MaterialTheme.typography.labelMedium.copy(shadow = unitShadow),
                                color = unitColor,
                            )
                            periods.firstOrNull { it.indexNo == unit }?.let {
                                // 起止放在同一行，避免行高不够时和时间数字叠在一起
                                Text(
                                    text = "${it.startText}-${it.endText}",
                                    fontSize = 7.sp,
                                    lineHeight = 9.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    color = unitTimeColor,
                                    style = LocalTextStyle.current.copy(shadow = unitShadow),
                                )
                            }
                        }
                    }
                }

                // 每天一列 —— 只有按列排布才可能把跨节次的课画成一个连续的整块
                for (day in days) {
                    DayColumn(
                        modifier = Modifier.weight(1f),
                        day = day,
                        sessions = sessions,
                        periods = periods,
                        prefs = prefs,
                        cellH = cellH,
                        gap = gap,
                        dark = dark,
                        todayIso = todayIso,
                        now = now,
                        onSessionClick = onSessionClick,
                    )
                }
            }
        Spacer(Modifier.height(16.dp))
        } // 关闭可滚动区（表头在上面，不跟着滚）
    } // 关闭外层 Column
} // 关闭 ScheduleGrid

/**
 * 一天的列。
 *
 * 跨节次的课只画**一个**块：块高 = 节数 × 行高 + 被吃掉的间隙，
 * 并跳过被覆盖的格子。因为 `总高 = 行高 × 节数 + 间隙 × (格数 - 1)` 在合并前后相等，
 * 各天与左侧节次列仍然严格对齐。
 */
@Composable
private fun DayColumn(
    modifier: Modifier,
    day: Int,
    sessions: List<Session>,
    periods: List<Period>,
    prefs: UiPrefs,
    cellH: Dp,
    gap: Dp,
    dark: Boolean,
    todayIso: String,
    now: LocalTime,
    onSessionClick: (Session) -> Unit,
) {
    val corner = RoundedCornerShape(6.dp)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        var unit = 1
        while (unit <= 10) {
            val session = sessions.firstOrNull { it.weekday == day && unit in it.startUnit..it.endUnit }
            if (session == null) {
                Box(Modifier.fillMaxWidth().height(cellH))
                unit += 1
                continue
            }
            val span = (session.endUnit - session.startUnit + 1).coerceIn(1, 11 - unit)
            CourseBlock(
                session = session,
                span = span,
                height = cellH * span + gap * (span - 1),
                prefs = prefs,
                corner = corner,
                periods = periods,
                dark = dark,
                live = prefs.liveOutlineEnabled && isLiveNow(session, periods, todayIso, now),
                onClick = onSessionClick,
            )
            unit += span
        }
    }
}

@Composable
private fun CourseBlock(
    session: Session,
    span: Int,
    height: Dp,
    prefs: UiPrefs,
    corner: Shape,
    periods: List<Period>,
    dark: Boolean,
    live: Boolean,
    onClick: (Session) -> Unit,
) {
    val palette = when {
        // 自定义日程用用户自己挑的颜色，不受"课程配色"设置影响 ——
        // 挑完就得一直是他挑的那个色，不能被"单色模式"改掉
        session.source == ScheduleRepository.SOURCE_CUSTOM -> {
            val bg = planBlockColor(session.color, dark)
            BlockPalette(bg, if (bg.luminance() > 0.5f) Color(0xFF1C1B1F) else Color.White)
        }
        prefs.colorMode == CourseColorMode.SINGLE -> BlockPalette(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        else -> coursePalette(session.courseCode, prefs.palettePreset, prefs.paletteBrightness)
    }
    val bg = palette.bg
    // 「正在上的课」描边（借鉴 Sked 的 liveCourseOutline）；否则按设置画实线/虚线/不画
    val decoration = when {
        live -> Modifier.border(prefs.liveOutlineWidth.dp, MaterialTheme.colorScheme.primary, corner)
        prefs.blockBorder == BlockBorder.SOLID ->
            Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, corner)
        prefs.blockBorder == BlockBorder.DASHED ->
            Modifier.dashedBorder(MaterialTheme.colorScheme.outline)
        else -> Modifier
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(bg, corner)
            .then(decoration)
            .clickable { onClick(session) },
        contentAlignment = Alignment.Center,
    ) {
        val secondary = buildList {
            // 本科格子按作者的设计只放地点（教师名长、格子小，详情页能看到）。
            // 研究生课表用户要求看到主讲老师；单节课的块太矮，只在跨节次（span >= 2）时加，
            // 避免把课名挤掉。
            if (session.source == GmsScheduleSource.SOURCE && span >= 2) {
                session.teacherName?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
            if (prefs.courseTextMode != CourseTextMode.NAME) {
                ScheduleText.tidyPlace(session.roomText)?.let { add(it) }
            }
        }
        CourseBlockText(
            name = session.courseName,
            secondary = secondary,
            prefs = prefs,
            span = span,
            foreground = palette.fg,
        )
    }
}

/**
 * 课程块里的文字：课名 + 地点（研究生还有教师）。
 *
 * **地点必须完整显示、不省略**。格子只有 40 多 dp 宽，`综合实验楼 405` 这种一行根本放不下，
 * 所以这里先按实际宽度量出折行后的高度，再挑一个能塞进块里的字号；行数不设上限、不用
 * `Ellipsis`。排不下时先缩地点字号，实在缩到底才让课名让一行 —— 详见 [fitCourseText]。
 *
 * 量字用 [TextMeasurer]（不依赖 View 层），只在文字/尺寸/设置变化时重算。
 */
@Composable
private fun CourseBlockText(
    name: String,
    secondary: List<String>,
    prefs: UiPrefs,
    span: Int,
    foreground: Color,
) {
    val measurer = rememberTextMeasurer()
    val nameMaxLines = if (span >= 2) 4 else if (prefs.density == GridDensity.COMPACT) 2 else 3
    val nameStyle = TextStyle(
        fontSize = prefs.courseFontSp.sp,
        lineHeight = (prefs.courseFontSp + 2f).sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
    )
    val secondaryText = secondary.joinToString("\n")

    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 2.dp)) {
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        val fitted = remember(
            measurer,
            name,
            secondaryText,
            nameStyle,
            nameMaxLines,
            prefs.roomFontSp,
            widthPx,
            heightPx,
        ) {
            fitCourseText(
                measurer = measurer,
                name = name,
                secondary = secondaryText,
                nameStyle = nameStyle,
                nameMaxLines = nameMaxLines,
                roomFontSp = prefs.roomFontSp,
                widthPx = widthPx,
                heightPx = heightPx,
            )
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = name,
                style = nameStyle.copy(color = foreground),
                maxLines = fitted.nameLines,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            if (secondaryText.isNotEmpty()) {
                Text(
                    text = secondaryText,
                    style = TextStyle(
                        fontSize = fitted.roomFontSp.sp,
                        lineHeight = (fitted.roomFontSp + 1.5f).sp,
                        textAlign = TextAlign.Center,
                        color = foreground.copy(alpha = 0.75f),
                    ),
                    softWrap = true,
                    // 刻意不设 maxLines / Ellipsis：字号已按可用空间选好，整段都放得下。
                    // 换行由宽度决定，长的楼栋名会折成两三行而不是被截断成 "综合实…"。
                )
            }
        }
    }
}

/** 地点字号的下限：再小就真看不清了，宁可让课名少一行 */
private const val MIN_ROOM_FONT_SP = 7.0f

/** 极端情况下（地点特别长）允许再压到这么小，保证整段不被截断 */
private const val HARD_MIN_ROOM_FONT_SP = 6.5f

private data class FittedCourseText(
    /** 课名能用几行（至少 1 行） */
    val nameLines: Int,
    /** 地点/教师这一段的字号（sp） */
    val roomFontSp: Float,
)

/**
 * 算出「课名几行 + 地点多大字号」。
 *
 * 两条路径，按优先级：
 *  ① **先保课名**：按课名自然行数留足高度，把地点字号从正文字号往下调（到 7sp），
 *     调到地点能完整折行放下为止 —— 大多数格子走这条，课名和以前显示的行数一样多。
 *  ② 地点实在太长、7sp 也放不下：改成**地点优先**（用户要求地点必须显示全），
 *     先保证地点完整，课名用剩下的高度决定行数（可能比原来少一行）。
 *
 * 这么排是因为反过来的话（一律地点优先），一个长地点会把普通课名挤成一行，
 * 满屏都是被截断的课名 —— 那是修一个毛病、添一个更大的毛病。
 */
private fun fitCourseText(
    measurer: TextMeasurer,
    name: String,
    secondary: String,
    nameStyle: TextStyle,
    nameMaxLines: Int,
    roomFontSp: Float,
    widthPx: Int,
    heightPx: Int,
): FittedCourseText {
    if (secondary.isEmpty() || widthPx <= 0 || heightPx <= 0) {
        return FittedCourseText(nameMaxLines, roomFontSp)
    }

    val constraints = Constraints(maxWidth = widthPx)
    fun layoutOf(text: String, style: TextStyle, maxLines: Int = Int.MAX_VALUE) =
        measurer.measure(
            text = AnnotatedString(text),
            style = style,
            overflow = TextOverflow.Clip,
            softWrap = true,
            maxLines = maxLines,
            constraints = constraints,
        )

    // nameOneLine 只用来在极端情况下给课名留一行；它也是「一行多高」的标尺
    val nameOneLine = layoutOf(name, nameStyle, maxLines = 1).size.height
    val nameNatural = layoutOf(name, nameStyle, maxLines = nameMaxLines)
    val nameNaturalHeight = nameNatural.size.height

    // ① 先按课名的自然高度留位，只缩地点字号
    var font = roomFontSp
    var room = layoutOf(secondary, roomStyle(font))
    val relaxedBudget = (heightPx - nameNaturalHeight).coerceAtLeast(1)
    while (room.size.height > relaxedBudget && font > MIN_ROOM_FONT_SP) {
        font = (font - 0.5f).coerceAtLeast(MIN_ROOM_FONT_SP)
        room = layoutOf(secondary, roomStyle(font))
    }
    if (room.size.height <= relaxedBudget) {
        return FittedCourseText(nameNatural.lineCount.coerceAtLeast(1), font)
    }

    // ② 课名只留一行，剩下的高度全给地点（再压到 6.5sp 兜底）
    val roomBudget = (heightPx - nameOneLine).coerceAtLeast(1)
    font = roomFontSp
    room = layoutOf(secondary, roomStyle(font))
    while (room.size.height > roomBudget && font > HARD_MIN_ROOM_FONT_SP) {
        font = (font - 0.5f).coerceAtLeast(HARD_MIN_ROOM_FONT_SP)
        room = layoutOf(secondary, roomStyle(font))
    }
    val nameBudget = (heightPx - room.size.height).coerceAtLeast(0)
    val fitLines = (nameBudget / nameOneLine.coerceAtLeast(1)).coerceAtLeast(1)
    return FittedCourseText(
        nameLines = fitLines.coerceAtMost(nameNatural.lineCount.coerceAtLeast(1)),
        roomFontSp = font,
    )
}

private fun roomStyle(fontSp: Float) = TextStyle(
    fontSize = fontSp.sp,
    lineHeight = (fontSp + 1.5f).sp,
    textAlign = TextAlign.Center,
)

/** 这节课此刻正在上 —— 供「正在上的课」描边使用。按真实日期判断，调休也正确。 */
private fun isLiveNow(
    s: Session,
    periods: List<Period>,
    todayIso: String,
    now: LocalTime,
): Boolean {
    if (s.date != todayIso) return false
    val startText = periods.firstOrNull { it.indexNo == s.startUnit }?.startText ?: s.startTime ?: return false
    val endText = periods.firstOrNull { it.indexNo == s.endUnit }?.endText ?: s.endTime ?: return false
    val start = runCatching { LocalTime.parse(startText) }.getOrNull() ?: return false
    val end = runCatching { LocalTime.parse(endText) }.getOrNull() ?: return false
    return !now.isBefore(start) && !now.isAfter(end)
}

/** 课程块配色：容器色 + 对应的前景色，保证文字对比度 */
private data class BlockPalette(val bg: Color, val fg: Color)

/**
 * 课程块配色：**参考 WakeUp 课程表那种深底高饱和色板**。
 *
 * 12 个颜色按课程代码哈希分配，一门课固定一个颜色；色板本身对比度足够，统一白字。
 * 不用主题容器色（太淡），也不用随机 HSL（容易撞色/发灰）。
 */
private val CANDY_COLORS = intArrayOf(
    0xFFD9B84A.toInt(), 0xFF45A8A0.toInt(), 0xFFA85CC2.toInt(), 0xFF8A8A95.toInt(),
    0xFFCC4A78.toInt(), 0xFF4A8CC7.toInt(), 0xFF5FA36B.toInt(), 0xFFD99A3D.toInt(),
    0xFF8A5BC2.toInt(), 0xFF3FAFA8.toInt(), 0xFFC05A96.toInt(), 0xFF9A9A3D.toInt(),
)

private val PASTEL_COLORS = intArrayOf(
    0xFFF2DFA0.toInt(), 0xFFA8DED8.toInt(), 0xFFD9B8EA.toInt(), 0xFFC4C4CE.toInt(),
    0xFFF0A8C0.toInt(), 0xFFA8C8EA.toInt(), 0xFFB4DCBA.toInt(), 0xFFF0CC96.toInt(),
    0xFFC8B0E6.toInt(), 0xFFA0DED6.toInt(), 0xFFE6B4D2.toInt(), 0xFFD8D8A0.toInt(),
)

private val VIVID_COLORS = intArrayOf(
    0xFFB08A2E.toInt(), 0xFF1E6E69.toInt(), 0xFF7A2C8C.toInt(), 0xFF55555C.toInt(),
    0xFFA61B4A.toInt(), 0xFF1F5F8B.toInt(), 0xFF2E6B3A.toInt(), 0xFFB26B14.toInt(),
    0xFF5B2A86.toInt(), 0xFF1F7A7A.toInt(), 0xFF8E2A6B.toInt(), 0xFF6B6B1F.toInt(),
)

private fun paletteOf(preset: CoursePalettePreset): IntArray = when (preset) {
    CoursePalettePreset.CANDY -> CANDY_COLORS
    CoursePalettePreset.PASTEL -> PASTEL_COLORS
    CoursePalettePreset.VIVID -> VIVID_COLORS
}

private fun coursePalette(
    code: String,
    preset: CoursePalettePreset,
    brightness: Int,
): BlockPalette {
    val hash = kotlin.math.abs(code.hashCode())
    val base = Color(paletteOf(preset)[hash % 12])
    // 深浅微调：正数向白色靠，负数向黑色靠，每档 10%
    val bg = when {
        brightness > 0 -> lerp(base, Color.White, 0.10f * brightness)
        brightness < 0 -> lerp(base, Color.Black, 0.10f * -brightness)
        else -> base
    }
    val fg = if (bg.luminance() > 0.45f) Color(0xFF1C1B1F) else Color.White
    return BlockPalette(bg, fg)
}

/**
 * 圆角虚线描边。
 *
 * Compose 没有内置虚线组件，用 drawBehind + dashPathEffect 自己画。
 * 虚线用来把相邻、颜色接近的课程块分开，避免连成一片看不出边界。
 */
private fun Modifier.dashedBorder(
    color: Color,
    dash: Dp = 4.dp,
    gap: Dp = 3.dp,
    strokeWidth: Dp = 1.dp,
    radius: Dp = 6.dp,
): Modifier = drawBehind {
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(radius.toPx(), radius.toPx()),
        style = Stroke(
            width = strokeWidth.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash.toPx(), gap.toPx())),
        ),
    )
}

@Composable
private fun NoSemesterBlock(
    loggedIn: Boolean,
    loading: Boolean,
    gradMode: Boolean,
    gmsLessons: List<com.zzupocket.app.data.gms.GradLesson>,
    gmsBusy: Boolean,
    gmsError: String?,
    gmsDialogOpen: Boolean,
    onLogin: () -> Unit,
    onRefresh: () -> Unit,
    onGmsLogin: () -> Unit,
    onGmsRefresh: () -> Unit,
    onGmsDialogDismiss: () -> Unit,
    onGmsAdopt: () -> Unit,
    onGradFallback: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when {
            loading -> {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(12.dp))
                Text("正在获取课表…", style = MaterialTheme.typography.bodyMedium)
            }

            !loggedIn -> {
                Text("登录后查看课表", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onLogin) {
                    Icon(Icons.Filled.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("登录")
                }
            }

            else -> {
                Text("暂时没有课表数据", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRefresh) { Text("重新获取") }
            }
        }
    }
}

/** 1..7 → 一..日 */
private fun weekdayZh(d: Int): String = when (d) {
    1 -> "一"
    2 -> "二"
    3 -> "三"
    4 -> "四"
    5 -> "五"
    6 -> "六"
    else -> "日"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CourseDetailSheet(course: Course, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(course.nameZh, style = MaterialTheme.typography.headlineSmall)
            if (course.teachers.isNotEmpty()) {
                DetailRow("教师", course.teachers.joinToString("、"))
            }
            val rooms = course.roomTexts.mapNotNull { ScheduleText.tidyPlace(it) }.distinct()
            if (rooms.isNotEmpty()) DetailRow("地点", rooms.joinToString("、"))
            val clock = course.sessions.firstOrNull()?.let { s ->
                listOfNotNull(s.startTime, s.endTime).takeIf { it.size == 2 }?.joinToString("–")
            }
            DetailRow("时间", listOfNotNull(ScheduleText.timeSlots(course.slots), clock).joinToString(" · "))
            DetailRow("周次", ScheduleText.compressWeeks(course.weeks))
            // 研究生课程没有课程号时，mapper 会用课程名当 key；别把课程名再显示成"课程代码"
            if (course.code.isNotBlank() && course.code != course.nameZh) {
                DetailRow("课程代码", course.code)
            }
            course.openDepartment?.takeIf { it.isNotBlank() }?.let { DetailRow("开课院系", it) }

            // 按「时段 → (地点, 教师)」归并：整学期都一样就只列一行，真的不同才拆开。
            // 只有一行时没必要显示（上面的汇总已经说清楚了）。
            val slotLines = course.sessions.toSlotLines()
            if (slotLines.size > 1) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("分时段明细", style = MaterialTheme.typography.titleSmall)
                slotLines.forEach { line ->
                    val unitText =
                        if (line.startUnit == line.endUnit) "${line.startUnit}节"
                        else "${line.startUnit}-${line.endUnit}节"
                    val detail = listOfNotNull(
                        line.room,
                        line.teacher,
                        ScheduleText.compressWeeks(line.weeks),
                    ).joinToString(" · ")
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text(
                            text = "${ScheduleText.weekdayName(line.weekday)} $unitText",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(104.dp),
                        )
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** "08:00" + "09:40" → 100 分钟；拿不到时间就返回 0 */
private fun minutesBetween(start: String?, end: String?): Int {
    val a = parseHhmm(start) ?: return 0
    val b = parseHhmm(end) ?: return 0
    return (b - a).coerceAtLeast(0)
}

private fun parseHhmm(text: String?): Int? {
    val raw = text?.trim().orEmpty()
    val parts = raw.split(":")
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    return h * 60 + m
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SemesterSheet(
    semesters: List<Semester>,
    selectedId: Int?,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Text(
                "选择学期",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            semesters.groupBy { it.schoolYear }.forEach { (year, list) ->
                Text(
                    text = year.ifBlank { "其他" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                list.forEach { s ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clickable { onPick(s.id) },
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = s.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (s.isCurrent) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "本学期",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            if (s.id == selectedId) {
                                Text(
                                    "当前",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekPickerDialog(
    weeks: List<Int>,
    current: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("跳转到教学周") },
        text = {
            val gap = 6.dp
            val columns = 6
            // 按弹窗实际可用宽度算格子大小：固定 44dp 时，一行 6 个加起来会超过
            // AlertDialog 的内容宽度，Row 会把最后一个（6 / 12 / 18）压扁。
            BoxWithConstraints {
                val cell = ((maxWidth - gap * (columns - 1)) / columns).coerceAtMost(44.dp)
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    weeks.chunked(columns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            row.forEach { w ->
                                val selected = w == current
                                Box(
                                    modifier = Modifier
                                        .size(cell)
                                        .background(
                                            color = if (selected) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.surfaceVariant
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                        )
                                        .clickable { onPick(w) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("$w", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            // 最后一行不足 6 个时补同尺寸占位，保持每列等宽
                            repeat(columns - row.size) {
                                Spacer(Modifier.size(cell))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
