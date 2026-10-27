package com.zzupocket.app.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.core.LoginInput
import com.zzupocket.app.core.toUserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LoginPhase { Credentials, Done }

data class LoginUiState(
    val account: String = "",
    val password: String = "",
    val phase: LoginPhase = LoginPhase.Credentials,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    /** 密码的输入体检结果：非 null 时禁止密码登录，并把原因显示在密码框下面 */
    val inputIssue: LoginInput.Issue? = null,
    /** 网页登录（扫码/短信）成功，但没识别出学号：此时要求补填学号 */
    val needAccount: Boolean = false,
)

/**
 * 登录：两条路。
 *
 * 1. **网页登录（主）**：内置 WebView 打开学校登录页，用户扫码或走统一身份认证，
 *    成功后只取会话 Cookie —— 适合没有青果密码的同学。
 * 2. **密码登录（备）**：直接走青果账密接口。
 */
class LoginViewModel : ViewModel() {

    private val auth = AppGraph.authRepository

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onAccountChange(v: String) = _state.update {
        // 注意：不要在这里把 needAccount 置回 false。
        // 网页登录成功后正处于"补填学号"状态，用户每输入一位都会走这里，
        // 一旦清掉 needAccount，「保存学号并完成登录」按钮会立刻消失，导致永远存不上。
        it.copy(account = v.trim(), error = null)
    }

    fun onPasswordChange(v: String) = _state.update {
        it.copy(password = v, error = null, inputIssue = LoginInput.inspect(v))
    }

    /**
     * 用户确认后把全角字符转成半角。
     *
     * 刻意**不做自动转换**：万一真有人把全角字符设进了密码，自动转换会把正确的密码改坏。
     */
    fun fixFullWidth() {
        val fixed = LoginInput.toHalfWidth(_state.value.password)
        _state.update { it.copy(password = fixed, error = null, inputIssue = LoginInput.inspect(fixed)) }
    }

    // ── 网页登录（扫码 / 短信）──────────────────────────────────────────

    /**
     * 网页登录完成（扫码/短信）后调用。
     *
     * 不读 Cookie（HttpOnly 读不到），而是直接验证并沿用 WebView 会话。
     */
    fun loginWithWebSession(accountHint: String?) {
        _state.update { it.copy(busy = true, error = null, notice = null, needAccount = false) }
        viewModelScope.launch {
            try {
                val valid = com.zzupocket.app.data.qingguo.QingguoWebSession.isValid()
                if (!valid) {
                    _state.update {
                        it.copy(
                            busy = false,
                            error = "还没真正进入教务首页：请等页面跳转完成后再点「已完成登录」。",
                        )
                    }
                    return@launch
                }
                // 优先：直接打开学籍页，从渲染后的 DOM 文本里抓学号（最稳）
                val fromDom = com.zzupocket.app.data.qingguo.QingguoReportScraper.discoverAccount()
                android.util.Log.i("QingguoAccount", "login discovered=$fromDom")
                val html = com.zzupocket.app.data.qingguo.QingguoWebSession
                    .fetch("/frame/homes.action").orEmpty()
                val profile = com.zzupocket.app.data.qingguo.QingguoWebSession
                    .fetch("/student/stu.xsxj.xjda.jbxx.html").orEmpty()
                val discovered = fromDom
                    ?: Regex("""[?&]xh=(\d{10,14})""").find(html)?.groupValues?.get(1)
                    ?: Regex("""xh["']?\s*[:=]\s*["']?(\d{10,14})""").find(html)?.groupValues?.get(1)
                    ?: Regex("""学\s*号[^0-9]{0,80}(\d{10,14})""").find(html)?.groupValues?.get(1)
                    ?: Regex("""xh["']?\s*[:=]\s*["']?(\d{10,14})""").find(profile)?.groupValues?.get(1)
                    ?: Regex("""学\s*号[^0-9]{0,80}(\d{10,14})""").find(profile)?.groupValues?.get(1)
                val hint = accountHint?.trim()?.takeIf { Regex("""\d{10,14}""").matches(it) }
                val acc = hint ?: discovered
                if (acc.isNullOrBlank()) {
                    _state.update {
                        it.copy(
                            busy = false,
                            needAccount = true,
                            error = "已登录，但没能识别学号。请填写学号后点「保存学号并完成登录」。",
                        )
                    }
                } else {
                    AppGraph.qingguoClient.setAccount(acc)
                    // 网页登录的会话在 WebView 里；清掉账密链路可能残留的旧 Cookie，
                    // 避免课表取数时误用那套过期会话
                    AppGraph.qingguoClient.clearCookies()
                    _state.update { it.copy(busy = false, account = acc) }
                    finishLogin()
                }
            } catch (e: Throwable) {
                _state.update { it.copy(busy = false, error = e.msg()) }
            }
        }
    }

    /** 扫码登录成功后调用（会话已由 QingguoClient 写入） */
    fun onQrLoginSuccess(account: String?) {
        if (!account.isNullOrBlank()) {
            AppGraph.qingguoClient.setAccount(account)
            _state.update { it.copy(account = account) }
            finishLogin()
            return
        }
        // 扫码会话在 OkHttp 里；学籍页是 Ajax 渲染的，必须把 Cookie 注入 WebView 再从 DOM 抓学号
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            // 先走 OkHttp 的 Ajax 接口（快），拿不到再退回 WebView（慢）
            val found = runCatching { AppGraph.qingguoClient.discoverAccount() }.getOrNull()
            if (!found.isNullOrBlank()) {
                AppGraph.qingguoClient.setAccount(found)
                _state.update { it.copy(busy = false, account = found) }
                finishLogin()
                return@launch
            }
            val detail = runCatching {
                val ctx = AppGraph.appContextOrNull()
                if (ctx != null) {
                    com.zzupocket.app.data.qingguo.QingguoWebSession.attach(ctx)
                    com.zzupocket.app.data.qingguo.QingguoWebSession.injectCookies(
                        AppGraph.qingguoClient.cookieHeader(),
                    )
                    // 把 KYS 的会话 Cookie 也带上，WebView 被 CAS 拦下时能自己跳回来
                    runCatching {
                        com.zzupocket.app.data.qingguo.QingguoWebSession.injectCookies(
                            AppGraph.ssoCookieJar.cookieHeaderFor("kys.zzuli.edu.cn"),
                            "https://kys.zzuli.edu.cn",
                        )
                    }
                    // CookieManager.setCookie 是异步的：先等它落盘，否则第一次请求会跳登录页
                    kotlinx.coroutines.delay(800)
                    var result = com.zzupocket.app.data.qingguo.QingguoReportScraper.discoverAccountDetailed()
                    if (result.first == null) {
                        com.zzupocket.app.data.qingguo.QingguoWebSession.injectCookies(
                            AppGraph.qingguoClient.cookieHeader(),
                        )
                        kotlinx.coroutines.delay(500)
                        result = com.zzupocket.app.data.qingguo.QingguoReportScraper.discoverAccountDetailed()
                    }
                    result
                } else Triple(null, 0, "没有 App 上下文")
            }.getOrElse { Triple(null, 0, "异常：${it.message}") }
            val webFound = detail.first

            if (!webFound.isNullOrBlank()) {
                AppGraph.qingguoClient.setAccount(webFound)
                _state.update { it.copy(busy = false, account = webFound) }
                finishLogin()
            } else {
                _state.update {
                    it.copy(
                        busy = false,
                        needAccount = true,
                        error = "扫码成功，但没识别到学号（页面 ${detail.third.take(80)}，文本 ${detail.second} 字）。" +
                            "请填写学号后点「保存学号并完成登录」。",
                    )
                }
            }
        }
    }

    /** 兼容旧调用 */
    fun onSessionEstablished() {
        finishLogin()
    }

    /** 网页登录成功但缺学号时，用它补上并完成登录 */
    fun saveAccountAndFinish() {
        val acc = _state.value.account.trim()
        if (acc.isEmpty()) {
            _state.update { it.copy(error = "请填写学号") }
            return
        }
        if (!Regex("""\d{10,14}""").matches(acc)) {
            _state.update { it.copy(error = "学号格式不对：应为 10–14 位数字") }
            return
        }
        AppGraph.qingguoClient.setAccount(acc)
        AppGraph.qingguoClient.clearCookies()
        // 网页登录的会话在 WebView 里，OkHttp 侧不一定是"已登录"；
        // 只要账号已落库 + WebView 会话有效，就算登录成功。
        if (AppGraph.authRepository.isLoggedIn()) {
            finishLogin()
        } else {
            _state.update { it.copy(error = "登录会话已失效，请重新用网页登录") }
        }
    }

    // ── 密码登录（备选）────────────────────────────────────────────────

    fun submitCredentials() {
        val s = _state.value
        if (s.account.isEmpty() || s.password.isEmpty()) {
            _state.update { it.copy(error = "请填写学号和密码") }
            return
        }
        LoginInput.inspect(s.password)?.let { issue ->
            _state.update { it.copy(inputIssue = issue, error = issue.message) }
            return
        }
        LoginInput.inspect(s.account)?.let { issue ->
            _state.update { it.copy(error = "学号里有异常字符：${issue.message}") }
            return
        }

        _state.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch {
            try {
                auth.beginLogin(s.account, s.password)
                finishLogin()
            } catch (e: Throwable) {
                _state.update { it.copy(busy = false, error = e.msg()) }
            }
        }
    }

    private fun finishLogin() {
        if (auth.isLoggedIn()) {
            _state.update { it.copy(busy = false, phase = LoginPhase.Done, needAccount = false) }
        } else {
            _state.update {
                it.copy(
                    busy = false,
                    phase = LoginPhase.Credentials,
                    error = "登录成功，但会话无法使用（${auth.casTokenShape()}）。请把这句话反馈给开发者。",
                )
            }
        }
    }

    /** 把异常翻译成用户看得懂的一句话 */
    private fun Throwable.msg(): String = toUserMessage()
}
