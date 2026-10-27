package com.zzupocket.app.ui.profile

import android.widget.Toast
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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.profile.ProfileStore
import com.zzupocket.app.data.qingguo.QingguoProfileScraper
import com.zzupocket.app.domain.StudentProfile
import com.zzupocket.app.domain.maskExamNo
import com.zzupocket.app.domain.maskIdCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileUiState(
    val profile: StudentProfile? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val revealed: Set<String> = emptySet(),
)

class ProfileViewModel : ViewModel() {
    private val _state = MutableStateFlow(ProfileUiState(profile = ProfileStore.load()))
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        // 有缓存先显示，再在登录状态下后台刷新
        if (AppGraph.authRepository.isLoggedIn()) refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val p = QingguoProfileScraper.fetch()
                if (p == null || p.isBlank()) {
                    _state.update { it.copy(loading = false, error = "没读到学籍信息，请确认登录状态后重试") }
                } else {
                    ProfileStore.save(p)
                    _state.update { it.copy(loading = false, profile = p, error = null) }
                }
            } catch (e: Throwable) {
                _state.update { it.copy(loading = false, error = e.message ?: "学籍信息加载失败") }
            }
        }
    }

    fun toggle(key: String) = _state.update {
        it.copy(revealed = if (key in it.revealed) it.revealed - key else it.revealed + key)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    vm: ProfileViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copy: (String) -> Unit = { value ->
        clipboard.setText(AnnotatedString(value))
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("学籍信息") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = vm::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { padding ->
        val p = state.profile
        when {
            p == null && state.loading -> Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("正在从教务读取学籍信息…")
            }
            p == null -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("没有学籍信息", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    state.error ?: "登录后可自动读取，并在本地缓存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    HeaderCard(p, onCopy = copy)
                }

                Section("学籍归属") {
                    InfoRow("院(系)/部", p.department, onCopy = copy)
                    InfoRow("录取专业", p.major, onCopy = copy)
                    InfoRow("行政班级", p.className, onCopy = copy)
                    InfoRow("培养层次", p.level)
                    InfoRow("学制", p.duration)
                    InfoRow("入学时间", p.enrollDate)
                }

                Section("证件信息") {
                    val idShown = "idCard" in state.revealed
                    InfoRow(
                        label = "身份证号",
                        value = if (idShown) p.idCard else maskIdCard(p.idCard),
                        trailing = {
                            IconButton(onClick = { vm.toggle("idCard") }) {
                                Icon(
                                    if (idShown) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (idShown) "隐藏" else "显示",
                                )
                            }
                        },
                        onCopy = { copy(p.idCard) },
                    )
                    val examShown = "examNo" in state.revealed
                    InfoRow(
                        label = "考生号",
                        value = if (examShown) p.examNo else maskExamNo(p.examNo),
                        trailing = {
                            IconButton(onClick = { vm.toggle("examNo") }) {
                                Icon(
                                    if (examShown) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (examShown) "隐藏" else "显示",
                                )
                            }
                        },
                        onCopy = { copy(p.examNo) },
                    )
                    Text(
                        "证件信息默认脱敏，仅用于本人核对。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                    )
                }

                Section("其他") {
                    InfoRow("生源单位", p.sourceSchool)
                }

                if (state.loading) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.height(20.dp))
                    }
                }
                state.error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun HeaderCard(p: StudentProfile, onCopy: (String) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    p.name.ifBlank { "—" },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (p.gender.isNotBlank()) {
                    Text(p.gender, style = MaterialTheme.typography.labelLarge)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    p.studentId.ifBlank { "—" },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.TextButton(onClick = { onCopy(p.studentId) }) {
                    Text("复制学号")
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    trailing: (@Composable () -> Unit)? = null,
    onCopy: ((String) -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            value.ifBlank { "—" },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) trailing()
        if (onCopy != null) {
            androidx.compose.material3.TextButton(onClick = { onCopy(value) }) {
                Text("复制")
            }
        }
    }
}

private typealias ColumnScope = androidx.compose.foundation.layout.ColumnScope
