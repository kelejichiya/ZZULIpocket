package com.zzupocket.app.ui.web

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.zzupocket.app.core.school.SchoolProfile

/**
 * 教务内页容器：直接加载学校原版页面（成绩 / 考试 / 学籍）。
 *
 * 这些页面的数据是页面 JS 二次请求出来的（学年学期、考试轮次都是动态下拉框），
 * 用 WebView 打开学校原页最稳；Cookie 与登录用的 WebView 共用同一个 CookieManager。
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SchoolWebScreen(
    title: String,
    path: String,
    onBack: () -> Unit,
    autoQuery: Boolean = false,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(padding),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.userAgentString =
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            view?.evaluateJavascript(
                                """
                                (function(){
                                  if (${if (autoQuery) "true" else "false"}) {
                                    try {
                                      var r = document.getElementById('sjxz1');
                                      if (r) { r.checked = true; }
                                      var b = document.getElementById('btnQry');
                                      if (b) { b.click(); }
                                    } catch (e) {}
                                  }
                                  // 学校报表 iframe 是 A4 打印版式：出现表格后重排成手机宽度。
                                  // 成绩页会自动触发一次检索；考试页等用户点「检索」后再套用。
                                  var tries = 0;
                                  var timer = setInterval(function () {
                                    try {
                                      var f = document.getElementsByName('frmReport')[0];
                                      var d = f && f.contentDocument;
                                      if (d && d.body && d.querySelector('table')) {
                                        var st = d.createElement('style');
                                        st.innerHTML = [
                                          'html,body{width:100%!important;margin:0!important;padding:0!important;}',
                                          'body{zoom:1!important;font-size:13px!important;}',
                                          '*{max-width:100%!important;box-sizing:border-box!important;}',
                                          'table{width:100%!important;table-layout:auto!important;}',
                                          'td,th{font-size:13px!important;line-height:1.45!important;',
                                          'padding:4px 3px!important;white-space:normal!important;',
                                          'word-break:break-all!important;}'
                                        ].join('');
                                        d.head.appendChild(st);
                                        f.style.height = (d.body.scrollHeight + 40) + 'px';
                                        clearInterval(timer);
                                      }
                                    } catch (e) {}
                                    if (++tries > 600) clearInterval(timer);
                                  }, 300);
                                })();
                                """.trimIndent(),
                                null,
                            )
                        }
                    }
                    loadUrl(SchoolProfile.JWGL_BASE + path)
                }
            },
        )
    }
}
