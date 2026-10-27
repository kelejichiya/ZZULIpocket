package com.zzupocket.app.ui.login

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.zzupocket.app.core.AppGraph
import kotlinx.coroutines.delay

/**
 * 学校统一身份认证的扫码登录（i轻大 APP 扫）。
 *
 * 二维码内容是 `https://kys.zzuli.edu.cn/cas/openAuth?uuid=...`，
 * 轮询授权状态，授权后用同一个 CookieJar 完成 CAS 跳转，教务会话直接进 OkHttp。
 */
@Composable
fun QingguoQrLoginDialog(
    accountHint: String?,
    onDismiss: () -> Unit,
    onSuccess: (String?) -> Unit,
) {
    var qrContent by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("正在获取二维码…") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(refreshKey) {
        error = null
        busy = true
        qrContent = null
        status = "正在获取二维码…"
        val uuid = runCatching { AppGraph.kysQrLogin.start() }.getOrNull()
        if (uuid == null) {
            busy = false
            error = "获取二维码失败，请检查网络"
            return@LaunchedEffect
        }
        qrContent = AppGraph.kysQrLogin.qrContent(uuid)
        android.util.Log.i("QingguoQr", "qrContent=$qrContent")
        busy = false
        status = "请用「i轻大」APP 扫码登录"

        var token = ""
        repeat(120) { // 约 3 分钟
            delay(1_500)
            val poll = runCatching { AppGraph.kysQrLogin.poll(uuid, token) }.getOrNull()
            if (poll == null) return@repeat
            poll.token?.takeIf { it.isNotBlank() }?.let { token = it }
            when (poll.code) {
                "waitSweep" -> status = "等待扫码…"
                "waitAuthorized" -> status = "已扫码，请在手机上确认登录"
                "alreadyAuthorized" -> {
                    status = "已授权，正在建立教务会话…"
                    busy = true
                    val completedResult = runCatching { AppGraph.kysQrLogin.complete() }
                        .getOrElse { false to "请求异常：${it.message}" }
                    val completed = completedResult.first
                    val adoptDetail = completedResult.second
                    val adopted = completed && AppGraph.qingguoClient.adoptFromJar(AppGraph.ssoCookieJar)
                    if (!adopted) {
                        busy = false
                        error = "授权成功但教务会话未建立（$adoptDetail）"
                        return@LaunchedEffect
                    }
                    if (!AppGraph.qingguoClient.isSessionValid()) {
                        busy = false
                        error = "教务不认这次会话，请刷新二维码重试"
                        return@LaunchedEffect
                    }
                    val acc = accountHint?.trim()?.takeIf { it.isNotEmpty() }
                        ?: AppGraph.qingguoClient.discoverAccount()
                    busy = false
                    onSuccess(acc)
                    return@LaunchedEffect
                }
                "cancel" -> { error = "已取消授权"; return@LaunchedEffect }
                "invalid", "error" -> { error = "二维码已失效，请刷新"; return@LaunchedEffect }
            }
        }
        error = "二维码已过期，请刷新"
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("扫码登录", style = MaterialTheme.typography.titleLarge)
                Text(
                    "打开「i轻大」→ 扫一扫",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))

                val qr: Bitmap? = remember(qrContent) {
                    qrContent?.let { runCatching { qrBitmap(it) }.getOrNull() }
                }
                if (qr != null) {
                    Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = "登录二维码",
                        modifier = Modifier.size(240.dp),
                    )
                } else {
                    CircularProgressIndicator(Modifier.size(64.dp))
                }

                Text(status, style = MaterialTheme.typography.bodyMedium)
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp))
                        Text("  处理中…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { refreshKey++ }) { Text("刷新二维码") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

private fun qrBitmap(content: String, size: Int = 512): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
