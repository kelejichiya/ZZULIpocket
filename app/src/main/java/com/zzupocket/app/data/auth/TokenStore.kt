package com.zzupocket.app.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 令牌存储。
 *
 * ⚠️ **只存令牌，不存密码。** 代价是 userToken（约 1 个月）过期后需要重新登录一次；
 * 收益是设备上不存在可直接复用的账号密码。
 *
 * 文件名 `secure_prefs` 与 `res/xml/backup_rules.xml` / `data_extraction_rules.xml`
 * 里的排除项必须一致 —— 令牌不进任何备份。
 */
class TokenStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var casUserToken: String?
        get() = prefs.getString(KEY_CAS_USER, null)
        set(value) = prefs.edit().putString(KEY_CAS_USER, value).apply()

    var casRefreshToken: String?
        get() = prefs.getString(KEY_CAS_REFRESH, null)
        set(value) = prefs.edit().putString(KEY_CAS_REFRESH, value).apply()

    var ecardAccessToken: String?
        get() = prefs.getString(KEY_ECARD_ACCESS, null)
        set(value) = prefs.edit().putString(KEY_ECARD_ACCESS, value).apply()

    var ecardRefreshToken: String?
        get() = prefs.getString(KEY_ECARD_REFRESH, null)
        set(value) = prefs.edit().putString(KEY_ECARD_REFRESH, value).apply()

    var ecardTokenAt: Long
        get() = prefs.getLong(KEY_ECARD_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_ECARD_AT, value).apply()

    /**
     * 研究生教务（gmsstu）的**会话 Cookie**（`k=v; k2=v2` 原样字符串）。
     *
     * 那个系统只认网页 CAS 会话 Cookie、没有 JWT 可用；登录在 App 内置 WebView 里完成
     * （用户在学校自己的页面上输密码），我们只保存它落下的会话 Cookie —— 与
     * 「只存令牌、不存密码」的底线一致。
     */
    var gmsCookie: String?
        get() = prefs.getString(KEY_GMS_COOKIE, null)
        set(value) = prefs.edit().putString(KEY_GMS_COOKIE, value).apply()

    /** 上次登录研究生系统的时间（毫秒；0 = 从没登录过）。登录状态卡片展示用 */
    var gmsCookieAt: Long
        get() = prefs.getLong(KEY_GMS_COOKIE_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_GMS_COOKIE_AT, value).apply()

    /** 上次登录成功的时间，用于诊断页展示 */
    var lastLoginAt: Long
        get() = prefs.getLong(KEY_LAST_LOGIN, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_LOGIN, value).apply()

    // ── 青果（ZZULI）会话 ──────────────────────────────────────────────
    // 青果没有 JWT，登录后只发一个 JSESSIONID 会话 Cookie；这里按
    // "name=value; name2=value2" 原样保存，重启 App 后还能继续用（直到服务端超时）。

    var qingguoCookie: String?
        get() = prefs.getString(KEY_QG_COOKIE, null)
        set(value) = prefs.edit().putString(KEY_QG_COOKIE, value).apply()

    /** 保存会话的时间（毫秒） */
    var qingguoCookieAt: Long
        get() = prefs.getLong(KEY_QG_COOKIE_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_QG_COOKIE_AT, value).apply()

    /** 登录账号（学号），退出登录后清空 */
    var qingguoAccount: String?
        get() = prefs.getString(KEY_QG_ACCOUNT, null)
        set(value) = prefs.edit().putString(KEY_QG_ACCOUNT, value).apply()

    /** 学生姓名（青果首页能拿到时写入，仅展示用） */
    var qingguoName: String?
        get() = prefs.getString(KEY_QG_NAME, null)
        set(value) = prefs.edit().putString(KEY_QG_NAME, value).apply()

    /** 学籍信息缓存（JSON，含身份证号，走加密存储） */
    var profileJson: String?
        get() = prefs.getString(KEY_PROFILE_JSON, null)
        set(value) = prefs.edit().putString(KEY_PROFILE_JSON, value).apply()

    // ── 电费（完美校园云缴费）────────────────────────────────────────────
    /** 云缴费登录 Cookie（SESSION/sid），加密保存 */
    var energyCookie: String?
        get() = prefs.getString(KEY_ENERGY_COOKIE, null)
        set(value) = prefs.edit().putString(KEY_ENERGY_COOKIE, value).apply()

    /** roomverify 房间标识 */
    var energyRoom: String?
        get() = prefs.getString(KEY_ENERGY_ROOM, null)
        set(value) = prefs.edit().putString(KEY_ENERGY_ROOM, value).apply()

    /** 房间显示名（接口返回的 description） */
    var energyRoomDesc: String?
        get() = prefs.getString(KEY_ENERGY_ROOM_DESC, null)
        set(value) = prefs.edit().putString(KEY_ENERGY_ROOM_DESC, value).apply()

    var energyFetchedAt: Long
        get() = prefs.getLong(KEY_ENERGY_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_ENERGY_AT, value).apply()

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val FILE_NAME = "secure_prefs"
        private const val KEY_CAS_USER = "cas_user_token"
        private const val KEY_CAS_REFRESH = "cas_refresh_token"
        private const val KEY_ECARD_ACCESS = "ecard_access_token"
        private const val KEY_ECARD_REFRESH = "ecard_refresh_token"
        private const val KEY_ECARD_AT = "ecard_token_at"
        private const val KEY_GMS_COOKIE = "gms_cookie"
        private const val KEY_GMS_COOKIE_AT = "gms_cookie_at"
        private const val KEY_LAST_LOGIN = "last_login_at"
        private const val KEY_QG_COOKIE = "qingguo_cookie"
        private const val KEY_QG_COOKIE_AT = "qingguo_cookie_at"
        private const val KEY_QG_ACCOUNT = "qingguo_account"
        private const val KEY_QG_NAME = "qingguo_name"
        private const val KEY_PROFILE_JSON = "student_profile_json"
        private const val KEY_ENERGY_COOKIE = "energy_cookie"
        private const val KEY_ENERGY_ROOM = "energy_room"
        private const val KEY_ENERGY_ROOM_DESC = "energy_room_desc"
        private const val KEY_ENERGY_AT = "energy_fetched_at"
    }
}
