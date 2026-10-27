package com.zzupocket.app.ui.grades

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zzupocket.app.data.qingguo.QingguoReportScraper
import com.zzupocket.app.domain.GradeItem
import com.zzupocket.app.domain.GradeTerm
import com.zzupocket.app.domain.ReportParser
import com.zzupocket.app.domain.cleanTermLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GradesUiState(
    val loading: Boolean = true,
    val groups: List<GradeTerm> = emptyList(),
    val error: String? = null,
    /** true = 正序（第一学年第一学期 → 现在），false = 倒序 */
    val ascending: Boolean = true,
)

/** 学期排序键：先学年，再第几学期；未知学期排最后 */
private fun termSortKey(term: String): Pair<Int, Int> {
    val m = Regex("""(\d{4})-\d{4}\s*第?([一二三123])学期""").find(term) ?: return Int.MAX_VALUE to 9
    val year = m.groupValues[1].toIntOrNull() ?: Int.MAX_VALUE
    val no = when (m.groupValues[2]) {
        "一", "1" -> 1
        "二", "2" -> 2
        "三", "3" -> 3
        else -> 9
    }
    return year to no
}

class GradesViewModel : ViewModel() {
    private val _state = MutableStateFlow(GradesUiState())
    val state: StateFlow<GradesUiState> = _state.asStateFlow()

    init { refresh() }

    /** 切换学期排序：正序（从入学第一学期到现在）/ 倒序 */
    fun toggleOrder() = _state.update { it.copy(ascending = !it.ascending) }

    fun refresh() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val sections = QingguoReportScraper.grades()
                // 同一学期可能被报表分页/标题差异拆成多段，按清洗后的学期名合并
                val groups = sections
                    .groupBy { cleanTermLabel(it.term) }
                    .map { (term, secs) ->
                        GradeTerm(
                            term,
                            secs.flatMap { ReportParser.grades(it.rows) }
                                .distinctBy { it.course to it.total },
                        )
                    }
                    .filter { it.items.isNotEmpty() }
                    .sortedWith(compareBy({ termSortKey(it.term).first }, { termSortKey(it.term).second }))
                _state.value = GradesUiState(loading = false, groups = groups)
            } catch (e: Throwable) {
                _state.value = GradesUiState(loading = false, error = e.message ?: "成绩加载失败")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GradesScreen(
    onBack: () -> Unit,
    vm: GradesViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // 切换正序/倒序后回到列表顶部，避免停在原来的位置、看到的还是同一批课程
    androidx.compose.runtime.LaunchedEffect(state.ascending) {
        listState.scrollToItem(0)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("成绩查询") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    androidx.compose.material3.TextButton(onClick = vm::toggleOrder) {
                        Text(if (state.ascending) "正序" else "倒序")
                    }
                    IconButton(onClick = vm::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("正在从教务读取成绩…", style = MaterialTheme.typography.bodyMedium)
            }
            state.error != null -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("读取失败", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
            }
            state.groups.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("没有查到成绩", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "可能是登录会话已失效，到「更多 → 登录」重新登录后再点右上角刷新。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                val shown = if (state.ascending) state.groups else state.groups.reversed()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    shown.forEach { group ->
                        item(key = "term-${group.term}") {
                            TermHeader(group)
                        }
                        items(group.items, key = { "${group.term}-${it.course}-${it.total}" }) { item ->
                            GradeCard(item)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TermHeader(group: GradeTerm) {
    val credits = group.items.mapNotNull { it.credits.toDoubleOrNull() }.sum()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            group.term,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.weight(1f))
        Text(
            "${group.items.size} 门 · ${"%.1f".format(credits)} 学分",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GradeCard(item: GradeItem) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.course,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (item.total.isNotBlank()) {
                    Text(
                        item.total,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            val meta = listOfNotNull(
                item.credits.takeIf { it.isNotBlank() }?.let { "学分 $it" },
                item.category.takeIf { it.isNotBlank() },
                item.remark.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val detail = listOfNotNull(
                item.usual.takeIf { it.isNotBlank() }?.let { "平时 $it" },
                item.mid.takeIf { it.isNotBlank() }?.let { "期中 $it" },
                item.final.takeIf { it.isNotBlank() }?.let { "期末 $it" },
            ).joinToString("   ")
            if (detail.isNotBlank()) {
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
