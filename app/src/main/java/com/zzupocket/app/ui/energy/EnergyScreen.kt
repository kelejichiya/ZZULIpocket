package com.zzupocket.app.ui.energy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.energy.CloudPaymentClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class EnergyUiState(
    val configured: Boolean = false,
    val editing: Boolean = false,
    val quantity: String = "",
    val unit: String = "度",
    val room: String = "",
    val fetchedAt: Long = 0L,
    val loading: Boolean = false,
    val error: String? = null,
)

class EnergyViewModel : ViewModel() {
    private val store = AppGraph.tokenStore
    private val _state = MutableStateFlow(
        EnergyUiState(
            configured = !store.energyCookie.isNullOrBlank() && !store.energyRoom.isNullOrBlank(),
            editing = store.energyCookie.isNullOrBlank() || store.energyRoom.isNullOrBlank(),
            room = store.energyRoomDesc.orEmpty(),
            fetchedAt = store.energyFetchedAt,
        )
    )
    val state: StateFlow<EnergyUiState> = _state.asStateFlow()

    init {
        if (_state.value.configured) refresh()
    }

    fun refresh() {
        val cookie = store.energyCookie ?: return
        val room = store.energyRoom ?: return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            CloudPaymentClient.query(cookie, room)
                .onSuccess { r ->
                    store.energyRoomDesc = r.room
                    store.energyFetchedAt = System.currentTimeMillis()
                    _state.update {
                        it.copy(
                            loading = false,
                            configured = true,
                            editing = false,
                            quantity = r.quantity,
                            unit = r.unit,
                            room = r.room,
                            fetchedAt = store.energyFetchedAt,
                            error = null,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(loading = false, error = e.message ?: "查询失败")
                    }
                }
        }
    }

    /** 保存抓包得到的请求 URL 与 Cookie，然后立即查询一次 */
    fun saveConfig(url: String, cookie: String) {
        val room = CloudPaymentClient.roomVerifyFromUrl(url)
        if (room == null) {
            _state.update { it.copy(error = "URL 里没有 roomverify，请复制 getRoomState.do 那条请求的完整地址") }
            return
        }
        if (cookie.isBlank()) {
            _state.update { it.copy(error = "请粘贴 Cookie") }
            return
        }
        store.energyCookie = cookie.trim()
        store.energyRoom = room
        _state.update { it.copy(configured = true, editing = false, error = null) }
        refresh()
    }

    fun edit() = _state.update { it.copy(editing = true) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnergyScreen(
    onBack: () -> Unit,
    vm: EnergyViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var urlInput by remember { mutableStateOf("") }
    var cookieInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("电费") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.configured) {
                        IconButton(onClick = vm::refresh) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                        }
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.configured || state.editing) {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("首次配置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "学校没有开放电费接口，只能从 i轻工大 的「宿舍购电」里取。需要你抓一次包（HttpCanary）：\n" +
                                "1. 打开宿舍购电，看到剩余电量；\n" +
                                "2. 找到 getRoomState.do 这条请求；\n" +
                                "3. 复制它的完整 URL 和请求头里的 Cookie。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            label = { Text("getRoomState.do 完整 URL") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = cookieInput,
                            onValueChange = { cookieInput = it },
                            label = { Text("Cookie（SESSION=...; sid=...）") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { vm.saveConfig(urlInput, cookieInput) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("保存并查询")
                        }
                        if (state.configured) {
                            TextButton(onClick = vm::refresh) {
                                Text("取消修改，返回余额")
                            }
                        }
                    }
                }
            } else {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("剩余电量", style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                state.quantity.ifBlank { "—" },
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(" ${state.unit}", style = MaterialTheme.typography.titleMedium)
                        }
                        if (state.room.isNotBlank()) {
                            Text("房间 ${state.room}", style = MaterialTheme.typography.bodyMedium)
                        }
                        if (state.fetchedAt > 0) {
                            Text(
                                "更新于 ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(state.fetchedAt))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = vm::refresh, enabled = !state.loading) { Text("刷新") }
                    TextButton(onClick = vm::edit) { Text("修改配置") }
                }
            }

            if (state.loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(" 查询中…", style = MaterialTheme.typography.bodySmall)
                }
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "说明：只做余额查询，不处理充值/支付；Cookie 加密保存在本机，过期后需要重新抓取。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
