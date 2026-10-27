package com.zzupocket.app.ui.login

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zzupocket.app.data.qingguo.QingguoWebSession
import kotlinx.coroutines.delay

/**
 * 学校统一身份认证网页登录（内含「扫码登录」标签，用 i轻大 APP 扫）。
 *
 * 登录完成后**不抓 Cookie**（HttpOnly 读不到），而是保留这个 WebView 会话，
 * 后续课表/成绩都用它内部 fetch 拉取。
 */
@Composable
fun QingguoWebLoginDialog(
    onDismiss: () -> Unit,
    onLoggedIn: () -> Unit,
) {
    val context = LocalContext.current
    val webView = remember { QingguoWebSession.attach(context) }
    var landed by remember { mutableStateOf(false) }

    // 轮询当前 URL：真正回到教务内页才算登录完成
    LaunchedEffect(webView) {
        repeat(300) {
            delay(1_000)
            val url = webView.url.orEmpty()
            if (url.contains("homes.action") || url.contains("/frame14/")) {
                landed = true
                runCatching { android.webkit.CookieManager.getInstance().flush() }
                return@LaunchedEffect
            }
        }
    }

    LaunchedEffect(landed) {
        if (landed) {
            delay(800) // 给页面一点时间把会话 Cookie 写完
            onLoggedIn()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "登录教务",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onLoggedIn) { Text("已完成登录") }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭")
                    }
                }
                Text(
                    "用手机号获取验证码登录；也可以点页面上的「扫码登录」用 i轻大 APP 扫。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { webView },
                )
            }
        }
    }
}
