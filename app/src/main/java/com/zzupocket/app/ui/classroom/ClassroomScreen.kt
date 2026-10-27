package com.zzupocket.app.ui.classroom

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zzupocket.app.data.qingguo.QingguoClassroomSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ClassroomUiState(
    /** 校区代码：1=东风，2=科学，3=禹州；教室类型 05=多媒体教室 */
    val campus: String = "2",
    val roomType: String = "05",
    val week: Int = 1,
    val weekday: Int = 1,
    val periodStart: Int = 1,
    val periodEnd: Int = 2,
    val loading: Boolean = false,
    val rows: List<QingguoClassroomSource.RoomItem> = emptyList(),
    val error: String? = null,
    val queried: Boolean = false,
)

class ClassroomViewModel : ViewModel() {
    private val _state = MutableStateFlow(ClassroomUiState())
    val state: StateFlow<ClassroomUiState> = _state.asStateFlow()

    fun setCampus(v: String) = _state.update { it.copy(campus = v) }
    fun setRoomType(v: String) = _state.update { it.copy(roomType = v) }
    fun setWeek(v: Int) = _state.update { it.copy(week = v.coerceIn(1, 20)) }
    fun setWeekday(v: Int) = _state.update { it.copy(weekday = v.coerceIn(1, 7)) }
    fun setPeriodStart(v: Int) = _state.update {
        val s = v.coerceIn(1, 10)
        it.copy(periodStart = s, periodEnd = maxOf(s, it.periodEnd))
    }
    fun setPeriodEnd(v: Int) = _state.update {
        val e = v.coerceIn(1, 10)
        it.copy(periodEnd = e, periodStart = minOf(it.periodStart, e))
    }

    fun query() {
        val s = _state.value
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val (year, term) = QingguoClassroomSource.defaultYearTerm()
                val rows = QingguoClassroomSource.query(
                    year = year,
                    term = term,
                    campus = s.campus,
                    roomType = s.roomType,
                    week = s.week,
                    weekday = s.weekday,
                    periodStart = s.periodStart,
                    periodEnd = s.periodEnd,
                )
                _state.update { it.copy(loading = false, rows = rows, queried = true) }
            } catch (e: Throwable) {
                _state.update {
                    it.copy(loading = false, error = e.message ?: "查询失败", queried = true)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ClassroomScreen(
    onBack: () -> Unit,
    vm: ClassroomViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val weekdays = listOf("一", "二", "三", "四", "五", "六", "日")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("空闲教室") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        // 整页一个 LazyColumn：条件区是第 0 项，往下滚会跟着结果一起上去
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "filters") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = state.campus == "1",
                                onClick = { vm.setCampus("1") },
                                label = { Text("东风校区") },
                            )
                            FilterChip(
                                selected = state.campus == "2",
                                onClick = { vm.setCampus("2") },
                                label = { Text("科学") },
                            )
                            FilterChip(
                                selected = state.campus == "3",
                                onClick = { vm.setCampus("3") },
                                label = { Text("禹州") },
                            )
                        }
                        Stepper("周次", state.week, 1, 20, vm::setWeek)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            weekdays.forEachIndexed { i, label ->
                                FilterChip(
                                    selected = state.weekday == i + 1,
                                    onClick = { vm.setWeekday(i + 1) },
                                    label = { Text(label) },
                                )
                            }
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            listOf(
                                "05" to "多媒体",
                                "" to "全部",
                                "04" to "语音室",
                                "07" to "智慧教室",
                                "08" to "计算机房",
                            ).forEach { (value, label) ->
                                FilterChip(
                                    selected = state.roomType == value,
                                    onClick = { vm.setRoomType(value) },
                                    label = { Text(label) },
                                )
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Stepper("起始节", state.periodStart, 1, 10, vm::setPeriodStart, Modifier.weight(1f))
                            Stepper("结束节", state.periodEnd, 1, 10, vm::setPeriodEnd, Modifier.weight(1f))
                        }
                        Button(
                            onClick = vm::query,
                            enabled = !state.loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("查询空闲教室")
                        }
                    }
                }
            }

            when {
                state.loading -> item(key = "loading") {
                    Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                state.error != null -> item(key = "error") {
                    Text(
                        state.error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                state.queried && state.rows.isEmpty() -> item(key = "empty") {
                    Text(
                        "没有查到空闲教室",
                        modifier = Modifier.padding(8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> items(state.rows, key = { it.name }) { room -> ClassroomCard(room) }
            }
        }
    }
}

@Composable
private fun Stepper(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("$label $value", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(4.dp))
        StepButton("−", enabled = value > min) { onChange(value - 1) }
        StepButton("+", enabled = value < max) { onChange(value + 1) }
    }
}

/** 紧凑的加减按钮：装在 Stepper 里，避免两列并排时被挤出屏幕 */
@Composable
private fun StepButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (enabled) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        },
        modifier = Modifier.size(30.dp),
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(text, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun ClassroomCard(room: QingguoClassroomSource.RoomItem) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                room.name.ifBlank { "—" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (room.capacity.isNotBlank()) {
                Text(
                    "容量 ${room.capacity} 人",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
