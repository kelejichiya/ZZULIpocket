package com.zzupocket.app.ui.diag

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zzupocket.app.BuildConfig
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.core.net.HttpClients
import com.zzupocket.app.data.auth.AuthRepository
import com.zzupocket.app.data.gms.GmsProbe
import com.zzupocket.app.data.lib.LibProbe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val FULL_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

/**
 * 正式包（release）是否也显示下面那批「自检」卡片。
 *
 * 历史：2026-10-15 为了让用户能自己跑自检报障，这里临时改成 true；
 * **2026-10-20 恢复为 false** —— 研究生链路已经跑通，普通用户的界面上不该再出现
 * 「一卡通/教室/鉴权/研究生探测/图书馆」这些内部工具。
 *
 * 排障时怎么办：让用户装 debug 包（自检卡片仍在 debug 里），或者临时发一个 true 的包。
 * 登录状态页里的「研究生教务」卡片（已登录/检查会话/重新登录/退出）**不受此开关影响**，
 * 那是给用户用的正式功能，不是自检。
 */
private const val SHOW_PROBES_IN_RELEASE = false

data class DiagState(
    val status: AuthRepository.TokenStatus? = null,
    val lightRoomId: String = "",
    val acRoomId: String = "",
    val readingCount: Int = 0,
    val probing: Boolean = false,
    /** 一卡通接口自检的结果（原始文本，含字段结构），空串 = 还没跑过 */
    val probeReport: String = "",
    val probeError: String = "",
    val probingRoom: Boolean = false,
    /** 教务教室接口自检结果 */
    val roomReport: String = "",
    val roomError: String = "",
    val probingLib: Boolean = false,
    /** 图书馆传输层自检结果 */
    val libReport: String = "",
    val libError: String = "",
    val probingEasAuth: Boolean = false,
    /** 教务鉴权自检结果（令牌形态 + 各接口状态码 + 换鉴权形状的对照） */
    val easAuthReport: String = "",
    val easAuthError: String = "",
    val probingGms: Boolean = false,
    /** 研究生系统探测报告（登录器记录到的接口）或身份类型自检结果 */
    val gmsReport: String = "",
    val gmsError: String = "",
    /** 研究生系统登录器是否打开（状态放这儿，转屏/重组都不丢） */
    val gmsDialogOpen: Boolean = false,
    // ── 研究生教务会话（固定登录位那张卡片）────────────────────────────
    val gmsLoggedIn: Boolean = false,
    /** 上次登录研究生系统的时间（0 = 没登录过） */
    val gmsLoginAt: Long = 0L,
    /** null = 还没检查；true/false = 「检查会话」的结果 */
    val gmsAlive: Boolean? = null,
    /** 自检工具是否展开（默认收起，别挡住日常信息） */
    val probesOpen: Boolean = false,
)

class DiagnosticsViewModel : ViewModel() {
    private val auth: AuthRepository = AppGraph.authRepository
    private val repo = AppGraph.electricityRepository

    private val _state = MutableStateFlow(DiagState())
    val state: StateFlow<DiagState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = repo.settings()
            // 房间号要显示**实际生效**的那两个：用户可能在房间表里选过（写在 DataStore 的
            // roomLabel / 偏好里），而 settings 里那两串是更早的手填值，可能一直是空的。
            // 直接用 settings 会出现「照明房间号：」后面空着、但电费页明明查得到读数这种怪状态。
            val (light, ac) = repo.effectiveRooms()
            _state.value = DiagState(
                status = auth.status(settings.account),
                lightRoomId = light.ifBlank { settings.lightRoomId.orEmpty() },
                acRoomId = ac.ifBlank { settings.acRoomId.orEmpty() },
                readingCount = repo.readingCount(),
                gmsLoggedIn = AppGraph.gmsScheduleSource.hasSession(),
                gmsLoginAt = AppGraph.gmsScheduleSource.loginAt(),
            )
        }
    }

    /**
     * 一卡通新接口自检：把电费明细、交易流水、消费汇总这些接口挨个调一遍，
     * 打出状态码和**字段结构**，用来确认响应字段名（不打印任何令牌/密码）。
     */
    fun runEcardProbe() {
        if (_state.value.probing) return
        _state.update { it.copy(probing = true, probeReport = "", probeError = "") }
        viewModelScope.launch {
            val result = runCatching { AppGraph.ecardProbe.run() }
            _state.update {
                it.copy(
                    probing = false,
                    probeReport = result.getOrDefault(""),
                    probeError = result.exceptionOrNull()?.let { e -> e.message ?: e.toString() }
                        .orEmpty(),
                )
            }
        }
    }

    fun clearProbe() = _state.update { it.copy(probeReport = "", probeError = "") }

    /**
     * 教务「教室」接口自检：定位"空教室 / 教室字典"的真实路径。
     * 目的是做"教室占用表"（一行一间教室、一排节次格子、有课涂色）。
     */
    fun runRoomProbe() {
        if (_state.value.probingRoom) return
        _state.update { it.copy(probingRoom = true, roomReport = "", roomError = "") }
        viewModelScope.launch {
            val result = runCatching { AppGraph.roomProbe.run() }
            _state.update {
                it.copy(
                    probingRoom = false,
                    roomReport = result.getOrDefault(""),
                    roomError = result.exceptionOrNull()?.let { e -> e.message ?: e.toString() }
                        .orEmpty(),
                )
            }
        }
    }

    /**
     * 图书馆「传输层」自检：校外只能走明文 HTTP:80，先确认手机上通不通，
     * 以及需登录的接口是不是 cookie 会话（决定 WebView 登录器方案能否落地）。
     */
    fun runLibProbe() {
        if (_state.value.probingLib) return
        _state.update { it.copy(probingLib = true, libReport = "", libError = "") }
        viewModelScope.launch {
            val result = runCatching { LibProbe.run() }
            _state.update {
                it.copy(
                    probingLib = false,
                    libReport = result.getOrDefault(""),
                    libError = result.exceptionOrNull()?.let { e -> e.message ?: e.toString() }
                        .orEmpty(),
                )
            }
        }
    }

    /**
     * 教务「鉴权」自检：定位"一卡通正常、唯独教务 401/403"。
     *
     * 起因是 2026-10-14 的用户报告 —— 统一身份认证与一卡通都正常，课表却始终
     * 「教务登录态已失效，请重新登录」，重新登录仍复现。教务网关拒绝时响应体是**空的**
     * （实测五种鉴权写法一律 401 + content-length 0），本地看不出原因，所以这里把原始事实摊开：
     * 令牌形态（长度 / exp / alg，**不含令牌值**）、每个在用接口的状态码与响应头、
     * 以及把同一条令牌换几种鉴权形状时的对照。**结果可以整段复制发给开发者。**
     */
    fun runEasAuthProbe() {
        if (_state.value.probingEasAuth) return
        _state.update { it.copy(probingEasAuth = true, easAuthReport = "", easAuthError = "") }
        viewModelScope.launch {
            // 课表接口要学期 id —— 那正是用户报告里失败的那个接口
            val semesterId = runCatching {
                AppGraph.scheduleRepository.semesters().firstOrNull()?.id
            }.getOrNull()
            val result = runCatching { AppGraph.easAuthProbe.run(semesterId) }
            _state.update {
                it.copy(
                    probingEasAuth = false,
                    easAuthReport = result.getOrDefault(""),
                    easAuthError = result.exceptionOrNull()?.let { e -> e.message ?: e.toString() }
                        .orEmpty(),
                )
            }
        }
    }

    /**
     * 身份类型自检：「按学籍自动路由数据源」的前提 —— 先确认学校认证服务会不会告诉我们
     * 这个账号是本科还是研究生。四种鉴权写法都试一遍，哪种能用一目了然。
     */
    fun runIdentityCheck() {
        if (_state.value.probingGms) return
        _state.update { it.copy(probingGms = true, gmsReport = "", gmsError = "") }
        viewModelScope.launch {
            val result = runCatching {
                val token = auth.requireCasToken()
                // ⚠️ 必须在 IO 线程：GmsProbe.identityCheck 里是同步 OkHttp 调用，
                // 放在主线程会抛 NetworkOnMainThreadException（2026-10-15 踩过：
                // 六种写法全报"网络失败"，其实是这个）。
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    GmsProbe.identityCheck(token, HttpClients.eas)
                }
            }
            _state.update {
                it.copy(
                    probingGms = false,
                    gmsReport = result.getOrDefault(""),
                    gmsError = result.exceptionOrNull()?.let { e -> e.message ?: e.toString() }
                        .orEmpty(),
                )
            }
        }
    }

    fun openGmsDialog() = _state.update { it.copy(gmsDialogOpen = true) }

    fun closeGmsDialog() = _state.update { it.copy(gmsDialogOpen = false) }

    // ── 研究生教务会话（固定登录位）────────────────────────────────────

    /** 自检工具展开/收起（默认收起，避免挡住日常信息） */
    fun toggleProbes() = _state.update { it.copy(probesOpen = !it.probesOpen) }

    /**
     * 采用 WebView 登录后的会话（与课表页同一套逻辑）。
     *
     * 从 WebView 的 CookieManager 取会话 → 交给数据源落盘。密码始终输在学校页面上，我们不接触。
     * @param onDone 回调结果（true = 真的拿到了会话）
     */
    fun adoptGmsSession(onDone: (Boolean) -> Unit = {}) {
        _state.update { it.copy(gmsDialogOpen = false, gmsError = "") }
        viewModelScope.launch {
            val cookie = runCatching {
                android.webkit.CookieManager.getInstance()
                    .getCookie(com.zzupocket.app.data.gms.GmsScheduleSource.COOKIE_URL)
            }.getOrNull()
            val ok = AppGraph.gmsScheduleSource.onWebViewLogin(cookie)
            refreshGmsState()
            if (!ok) {
                _state.update {
                    it.copy(gmsError = "没拿到研究生系统的登录会话 —— 可能还没登录成功")
                }
            }
            onDone(ok)
        }
    }

    /** 刷新卡片上的会话状态（是否已登录 + 登录时间） */
    fun refreshGmsState() {
        val src = AppGraph.gmsScheduleSource
        _state.update {
            it.copy(gmsLoggedIn = src.hasSession(), gmsLoginAt = src.loginAt(), gmsAlive = null)
        }
    }

    /** 「检查会话」：真发一次请求确认还有效 */
    fun checkGmsSession() {
        viewModelScope.launch {
            val alive = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    AppGraph.gmsScheduleSource.sessionAlive()
                }
            }.getOrDefault(false)
            _state.update { it.copy(gmsAlive = alive) }
        }
    }

    /** 只退出研究生教务会话，不动统一认证登录 */
    fun logoutGms() {
        AppGraph.gmsScheduleSource.clear()
        refreshGmsState()
    }

    /**
     * WebView 登录器把报告交回来。
     *
     * 报告由 [GmsProbe.Recorder] 生成：只有请求路径、参数名、响应字段名与类型，
     * **不含任何字段值与令牌** —— 因为这份东西是要贴到公开 issue 上的。
     */
    fun acceptGmsReport(text: String) = _state.update {
        it.copy(gmsDialogOpen = false, gmsReport = text, gmsError = "")
    }

    fun logout(onDone: () -> Unit) {
        // ⚠️ 统一认证退出时，研究生教务的会话必须一起清 ——
        // 否则会出现"已经退出登录、但研究生课表还能看"的怪状态。
        runCatching { AppGraph.gmsScheduleSource.clear() }
        // 研究生课表缓存也一起清：它是按账号抓的，退出后不该留在设备上
        // （本科课表随学期走、且与账号绑定方式不同，不在此处处理）
        viewModelScope.launch {
            runCatching {
                AppGraph.scheduleRepository.clearTimetable(
                    source = com.zzupocket.app.data.gms.GmsScheduleSource.SOURCE,
                    semesterId = com.zzupocket.app.data.gms.GmsScheduleMapper.GRAD_SEMESTER_ID,
                )
            }
        }
        auth.logout()
        onDone()
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    onLoggedOut: () -> Unit,
    vm: DiagnosticsViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val status = state.status

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("登录状态") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            StatusRow(
                label = "统一身份认证（CAS）",
                ok = status?.loggedIn == true,
                detail = when {
                    status == null -> "读取中…"
                    status.loggedIn -> "有效期至 ${status.casExpiresAt?.let { FULL_FMT.format(Date(it)) } ?: "未知"}"
                    else -> "未登录或令牌已过期，需要重新登录"
                },
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("数据", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "本机设备的统一认证标识：${AppGraph.deviceId}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "这个标识只用于在本机登录时表明设备身份，保存在本机，不会上传到其它服务器。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "本 App 只保存登录令牌（加密存储），不保存你的密码。\n" +
                        "代价是令牌过期（约 1 个月）后需要重新登录一次。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }

            OutlinedButton(
                onClick = { vm.logout(onLoggedOut) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("退出登录并清除本地令牌")
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, detail: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
