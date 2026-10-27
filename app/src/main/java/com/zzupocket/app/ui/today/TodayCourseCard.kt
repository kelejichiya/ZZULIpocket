package com.zzupocket.app.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zzupocket.app.ui.today.TimelineKind

/**
 * 今天课程竖排：
 *   刚刚上完的一节（只保留最近一节，缩小变浅） → 正在上 → 还没上的，按时间顺序。
 */
@Composable
fun TodayCourseCard(
    state: TodayTimelineUi,
    onOpenSchedule: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val courses = state.entries
        .filter { it.kind == TimelineKind.COURSE }
        .sortedBy { it.startMin }

    val finished = courses.filter { it.endMin <= state.nowMin }
    val current = courses.firstOrNull { it.startMin <= state.nowMin && state.nowMin < it.endMin }
    val upcoming = courses.filter { it.startMin > state.nowMin }

    val lastFinished = finished.lastOrNull()

    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onOpenSchedule),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "今天",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    buildString {
                        state.week?.let { append("第 $it 周 · ") }
                        append(state.dateText)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (courses.isEmpty()) {
                Text(
                    "今天没有课",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            lastFinished?.let { CourseRow(it, finished = true) }
            current?.let { CourseRow(it, current = true) }
            upcoming.forEach { CourseRow(it) }

            if (finished.isNotEmpty() && current == null && upcoming.isEmpty()) {
                Text(
                    "今天的课已结束",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CourseRow(
    entry: TimelineEntry,
    finished: Boolean = false,
    current: Boolean = false,
) {
    val color = entry.colorArgb?.let { Color(it.toInt()) } ?: MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (finished) 0.55f else 1f)
            .background(
                if (current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = if (finished) 8.dp else 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧色条
        Column(
            Modifier
                .size(width = 3.dp, height = if (finished) 28.dp else 36.dp)
                .background(color, RoundedCornerShape(2.dp)),
        ) {}
        Spacer(Modifier.size(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                entry.title,
                style = if (finished) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleSmall,
                fontWeight = if (finished) FontWeight.Normal else FontWeight.SemiBold,
            )
            Text(
                buildString {
                    append(clock(entry.startMin))
                    append("–")
                    append(clock(entry.endMin))
                    if (entry.subtitle.isNotBlank()) {
                        append("  ")
                        append(entry.subtitle)
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (current) {
            Text(
                "上课中",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun clock(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
