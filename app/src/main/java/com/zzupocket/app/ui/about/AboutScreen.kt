package com.zzupocket.app.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zzupocket.app.BuildConfig

/**
 * 「关于」页：这个 App 是什么、数据从哪来、怎么用你的数据。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于") },
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("泥轻助手", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "郑州轻工业大学学生工具：课表、成绩、考试、学籍、空闲教室、日历与天气，" +
                            "把分散在教务和校内网页里的信息整理成一个原生 Android App。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            AboutCard("数据与隐私") {
                Bullet("不保存你的密码。登录只走学校统一身份认证，本机仅保存加密后的会话。")
                Bullet("课表、成绩、学籍等数据只存在本机数据库，没有自建服务器，也不会上传。")
                Bullet("学籍里的身份证号、考生号默认脱敏显示。")
                Bullet("退出登录会清除本机令牌；卸载 App 会一并清掉所有本地数据。")
            }

            AboutCard("数据从哪来") {
                Bullet("课表 / 成绩 / 考试 / 学籍 / 空闲教室：学校教务系统，登录后读取。")
                Bullet("天气：Open-Meteo（按定位经纬度查询）。")
                Bullet("节假日：timor.tech 节假日接口，失败时用固定节日兜底。")
            }

            AboutCard("说明") {
                Bullet("本 App 为个人学习作品，非学校官方应用。")
                Bullet("只读展示，不修改教务数据，也不做缴费、转账等操作。")
                Bullet("接口来自学校网页，学校改版后可能失效，需要更新适配。")
            }

            Text(
                text = "版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun AboutCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Text(
        text = "· $text",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
