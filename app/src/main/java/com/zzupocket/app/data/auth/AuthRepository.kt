package com.zzupocket.app.data.auth

import com.zzupocket.app.core.AppError
import com.zzupocket.app.core.crypto.Crypto
import com.zzupocket.app.data.ecard.EcardClient
import com.zzupocket.app.data.qingguo.QingguoClient

/**
 * 认证中枢。
 *
 * 轻工大版的主登录方式是**青果账号密码**（[QingguoClient]）：没有 JWT、没有短信 MFA，
 * 登录成功后只保留会话 Cookie，落到 [TokenStore] 的加密存储里。
 *
 * 郑大那套 CAS 令牌代码保留着（[currentCasToken]/[submitSmsCode] 等），
 * 但本仓库不会再走那条路；一卡通若将来接回统一认证，可以复用。
 */
class AuthRepository(
    private val store: TokenStore,
    private val cas: CasClient,
    private val ecard: EcardClient,
    private val qingguo: QingguoClient,
) {

    sealed interface LoginStep {
        data class Success(val account: String) : LoginStep
        data class NeedSms(val challenge: CasClient.MfaChallenge) : LoginStep
    }

    data class TokenStatus(
        val loggedIn: Boolean,
        val casExpiresAt: Long?,
        val lastLoginAt: Long,
        val ecardTokenAt: Long,
        val account: String?,
    )

    // ── 登录态 ────────────────────────────────────────────────────────

    /** 青果会话存在（OkHttp Cookie 或 WebView 会话），或还留着可用的 CAS 令牌 */
    fun isLoggedIn(): Boolean =
        qingguo.isLoggedIn() ||
            !store.qingguoAccount.isNullOrBlank() ||
            currentCasToken() != null

    fun account(): String? = qingguo.account() ?: store.qingguoAccount

    fun displayName(): String? = qingguo.displayName()

    /** 返回可用的 CAS userToken；没有/过期返回 null */
    fun currentCasToken(): String? {
        val token = store.casUserToken ?: return null
        val expiresAt = Crypto.jwtExpiresAtMillis(token) ?: return null
        val remaining = expiresAt - System.currentTimeMillis()
        return if (remaining > CasClient.TOKEN_SAFETY_WINDOW_MS) token else null
    }

    fun casTokenExpiresAt(): Long? = store.casUserToken?.let { Crypto.jwtExpiresAtMillis(it) }

    /**
     * 诊断用：只描述登录态形态，不返回令牌值。
     *
     * 青果会话直接报"已登录 + 账号 + Cookie 数量"；CAS 分支保留原有 JWT 解析，
     * 方便排查历史数据。
     */
    fun casTokenShape(): String {
        if (qingguo.isLoggedIn()) {
            val savedAt = qingguo.savedAt().takeIf { it > 0L }
                ?.let { java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(it)) }
                ?: "未知"
            return "青果会话已建立（账号 ${qingguo.account().orEmpty()}，保存于 $savedAt）"
        }
        val token = store.casUserToken ?: return "无登录态"
        val parts = token.split('.')
        val payload = parts.getOrNull(1)?.let { part ->
            runCatching {
                String(
                    android.util.Base64.decode(
                        part,
                        android.util.Base64.URL_SAFE or
                            android.util.Base64.NO_WRAP or
                            android.util.Base64.NO_PADDING,
                    ),
                    Charsets.UTF_8,
                )
            }.getOrDefault("")
        }.orEmpty()
        val keys = Regex("\"([^\"]+)\"\\s*:").findAll(payload)
            .map { it.groupValues[1] }.take(12).joinToString(",")
        return "段数=${parts.size}，长度=${token.length}，含exp=${payload.contains("\"exp\"")}，载荷字段=$keys"
    }

    /** 取可用的 CAS userToken（郑大教务/一卡通等旧链路用） */
    fun requireCasToken(): String =
        currentCasToken() ?: throw AppError.ReLoginRequired()

    // ── 登录（青果）────────────────────────────────────────────────────

    /**
     * 账密登录。轻工大没有短信 MFA，永远返回 [LoginStep.Success]；
     * [LoginStep.NeedSms] 仅为兼容旧界面流程保留。
     */
    suspend fun beginLogin(account: String, password: String): LoginStep {
        val result = qingguo.login(account, password)
        store.lastLoginAt = System.currentTimeMillis()
        return LoginStep.Success(result.account)
    }

    suspend fun resendSmsCode(challenge: CasClient.MfaChallenge) {
        cas.sendSmsCode(challenge)
    }

    suspend fun submitSmsCode(
        account: String,
        password: String,
        challenge: CasClient.MfaChallenge,
        code: String,
    ): LoginStep.Success {
        cas.verifySmsCode(challenge, code)
        val result = cas.login(account, password, mfaState = challenge.state)
        return persist(result, account)
    }

    private fun persist(result: CasClient.LoginResult, account: String): LoginStep.Success {
        store.casUserToken = result.userToken
        store.casRefreshToken = result.refreshToken
        store.lastLoginAt = System.currentTimeMillis()
        store.ecardAccessToken = null
        store.ecardRefreshToken = null
        store.ecardTokenAt = 0L
        return LoginStep.Success(account)
    }

    fun logout() {
        qingguo.logout()
        com.zzupocket.app.data.qingguo.QingguoWebSession.clear()
        store.clearAll()
    }

    // ── 一卡通令牌（旧链路，轻工大接入前保留）──────────────────────────

    suspend fun requireEcardAccessToken(): String {
        val cached = store.ecardAccessToken
        val age = System.currentTimeMillis() - store.ecardTokenAt
        if (!cached.isNullOrEmpty() && age in 0 until EcardClient.TOKEN_TTL_MS) return cached

        val userToken = requireCasToken()
        val tid = ecard.fetchTid(userToken)
        val tokens = ecard.fetchTokens(tid)
        val now = System.currentTimeMillis()
        store.ecardAccessToken = tokens.accessToken
        store.ecardRefreshToken = tokens.refreshToken
        store.ecardTokenAt = now
        return tokens.accessToken
    }

    fun status(account: String? = null): TokenStatus = TokenStatus(
        loggedIn = isLoggedIn(),
        casExpiresAt = casTokenExpiresAt(),
        lastLoginAt = store.lastLoginAt,
        ecardTokenAt = store.ecardTokenAt,
        account = this.account() ?: account,
    )
}
