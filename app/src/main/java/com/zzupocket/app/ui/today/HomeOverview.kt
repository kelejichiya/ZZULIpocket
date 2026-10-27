package com.zzupocket.app.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zzupocket.app.data.home.HolidayClient
import com.zzupocket.app.data.home.LocationClient
import com.zzupocket.app.data.home.WeatherClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/** 首页额外数据：天气（按定位）+ 节假日 */
class HomeExtrasViewModel(app: android.app.Application) : AndroidViewModel(app) {
    data class State(
        val weather: WeatherClient.Weather? = null,
        val holidays: Map<String, HolidayClient.Holiday> = emptyMap(),
        val weatherLoading: Boolean = true,
        val city: String = "郑州",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(weatherLoading = true) }
        viewModelScope.launch {
            val fresh = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { LocationClient.locate(getApplication()) }.getOrNull()
            }
            // 定位失败时保留上一次成功定位，不要退回默认的郑州
            val cached = cachedPlace()
            val place = when {
                fresh == null -> cached
                fresh.city == "当前位置" ->
                    // 坐标拿到了但区名没解析出来：用新坐标 + 上一次的城市名，别把好名字覆盖掉
                    cached?.let { LocationClient.Place(fresh.lat, fresh.lon, it.city) } ?: fresh
                else -> fresh
            }
            if (place != null && place.city != "当前位置") savePlace(place)
            val w = runCatching {
                if (place != null) WeatherClient.fetch(place.lat, place.lon) else WeatherClient.fetch()
            }.getOrNull()
            _state.update {
                it.copy(
                    weather = w,
                    weatherLoading = false,
                    city = place?.city ?: "郑州",
                )
            }
        }
        viewModelScope.launch {
            val year = LocalDate.now().year
            val merged = buildMap {
                // 固定日期的法定假日先垫底，接口失败也不会一个红字都没有
                putAll(fallbackHolidays())
                putAll(runCatching { HolidayClient.fetch(year) }.getOrDefault(emptyMap()))
                putAll(runCatching { HolidayClient.fetch(year + 1) }.getOrDefault(emptyMap()))
            }
            if (merged.isNotEmpty()) _state.update { it.copy(holidays = merged) }
        }
    }

    private val locationPrefs
        get() = getApplication<android.app.Application>()
            .getSharedPreferences("home_location", android.content.Context.MODE_PRIVATE)

    private fun cachedPlace(): LocationClient.Place? {
        val p = locationPrefs
        val lat = p.getString("lat", null)?.toDoubleOrNull() ?: return null
        val lon = p.getString("lon", null)?.toDoubleOrNull() ?: return null
        val city = p.getString("city", null)?.takeIf { it.isNotBlank() } ?: return null
        return LocationClient.Place(lat, lon, city)
    }

    private fun savePlace(place: LocationClient.Place) {
        locationPrefs.edit()
            .putString("lat", place.lat.toString())
            .putString("lon", place.lon.toString())
            .putString("city", place.city)
            .apply()
    }
}

private val WEEK_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")
private const val HOLIDAY_FMT = "MM-dd"

/** 概览卡：日期 + 天气 */
@Composable
fun OverviewCard(city: String, weather: WeatherClient.Weather?, loading: Boolean, onRetry: () -> Unit, today: LocalDate = LocalDate.now()) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${today.monthValue}月${today.dayOfMonth}日  ${weekdayCn(today.dayOfWeek)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            if (weather != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$city  ${weather.text}",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${weather.temp}°",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    "最高 ${weather.high}°  最低 ${weather.low}° · 体感 ${weather.feelsLike}° · 湿度 ${weather.humidity}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "天气加载中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 日历卡：默认近三周，可展开整月；节假日红色、调休标注“班” */
@Composable
fun CalendarCard(
    holidays: Map<String, HolidayClient.Holiday>,
    today: LocalDate = LocalDate.now(),
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val baseMonth = today.withDayOfMonth(1)
    var monthOffset by rememberSaveable { mutableStateOf(0) }
    val shownMonth = baseMonth.plusMonths(monthOffset.toLong())

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "日历",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "${shownMonth.year}年${shownMonth.monthValue}月",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (expanded) {
                    TextButton(onClick = { monthOffset -= 1 }) { Text("‹") }
                    TextButton(onClick = { monthOffset += 1 }) { Text("›") }
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起" else "展开")
                }
            }

            if (!expanded) {
                // 折叠态：只显示三行，不写"上周/本周/下周"，本周在中间
                Row(Modifier.fillMaxWidth()) {
                    WEEK_LABELS.forEach {
                        Text(
                            it,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                compactWeek(monday.minusWeeks(1), today, holidays)
                compactWeek(monday, today, holidays)
                compactWeek(monday.plusWeeks(1), today, holidays)
                nextHoliday(holidays, today)?.let { (date, h) ->
                    Text(
                        "下一个假期：${h.name} ${date.monthValue}月${date.dayOfMonth}日",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                var dragAcc by remember { mutableStateOf(0f) }
                val dragState = rememberDraggableState { delta -> dragAcc += delta }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .draggable(
                            state = dragState,
                            orientation = Orientation.Horizontal,
                            onDragStopped = {
                                if (dragAcc > 60f) monthOffset -= 1
                                else if (dragAcc < -60f) monthOffset += 1
                                dragAcc = 0f
                            },
                        ),
                ) {
                    monthGrid(month = shownMonth, today = today, holidays = holidays)
                }
            }
        }
    }
}

@Composable
private fun compactWeek(
    monday: LocalDate,
    today: LocalDate,
    holidays: Map<String, HolidayClient.Holiday>,
) {
    Row(Modifier.fillMaxWidth()) {
        repeat(7) { i ->
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                DayCell(monday.plusDays(i.toLong()), today, holidays, showWeekday = false)
            }
        }
    }
}

@Composable
private fun weekRow(
    label: String,
    monday: LocalDate,
    today: LocalDate,
    holidays: Map<String, HolidayClient.Holiday>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.size(8.dp))
            Text(
                "${monday.monthValue}.${monday.dayOfMonth} - ${monday.plusDays(6).monthValue}.${monday.plusDays(6).dayOfMonth}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            repeat(7) { i ->
                val d = monday.plusDays(i.toLong())
                DayCell(d, today, holidays)
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    today: LocalDate,
    holidays: Map<String, HolidayClient.Holiday>,
    showWeekday: Boolean = true,
) {
    val h = holidays[date.format(DateTimeFormatter.ofPattern(HOLIDAY_FMT))]
    val isToday = date == today
    val color = when {
        h?.isOff == true -> MaterialTheme.colorScheme.error
        isToday -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.height(if (showWeekday) 56.dp else 44.dp),
    ) {
        if (showWeekday) {
            Text(
                WEEK_LABELS[date.dayOfWeek.value - 1],
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.height(14.dp),
            )
        }
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(
                    if (isToday) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = color,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            )
        }
        Text(
            when {
                h?.isOff == true -> "休"
                h != null -> "班"
                else -> ""
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (h?.isOff == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.height(14.dp),
        )
    }
}

@Composable
private fun monthGrid(month: LocalDate, today: LocalDate, holidays: Map<String, HolidayClient.Holiday>) {
    val first = month.withDayOfMonth(1)
    val firstMonday = first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            WEEK_LABELS.forEach {
                Text(
                    it,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        repeat(6) { row ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { col ->
                    val d = firstMonday.plusDays((row * 7 + col).toLong())
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (d.monthValue != month.monthValue) {
                            Spacer(Modifier.height(44.dp))
                        } else {
                            DayCell(d, today, holidays, showWeekday = false)
                        }
                    }
                }
            }
        }
    }
}

/** 接口不可用时的兜底：只覆盖固定日期的法定假日 */
private fun fallbackHolidays(): Map<String, HolidayClient.Holiday> = mapOf(
    "01-01" to HolidayClient.Holiday("元旦", true),
    "05-01" to HolidayClient.Holiday("劳动节", true),
    "10-01" to HolidayClient.Holiday("国庆节", true),
    "10-02" to HolidayClient.Holiday("国庆节", true),
    "10-03" to HolidayClient.Holiday("国庆节", true),
)

/** 本月假期摘要，例如「国庆节 10.1-10.7」 */
private fun monthHolidayText(
    holidays: Map<String, HolidayClient.Holiday>,
    today: LocalDate,
): String? {
    val prefix = "%02d-".format(today.monthValue)
    val days = holidays.entries
        .filter { it.key.startsWith(prefix) && it.value.isOff }
        .mapNotNull { e ->
            val day = e.key.substringAfter("-").toIntOrNull() ?: return@mapNotNull null
            e.value.name to day
        }
        .groupBy { it.first }
        .map { (name, list) ->
            val ds = list.map { it.second }.sorted()
            "$name ${today.monthValue}.${ds.first()}-${ds.last()}"
        }
    return days.takeIf { it.isNotEmpty() }?.joinToString("、")
}

/** 从节假日表里找今天之后最近的一个放假日期 */
private fun nextHoliday(
    holidays: Map<String, HolidayClient.Holiday>,
    today: LocalDate,
): Pair<LocalDate, HolidayClient.Holiday>? {
    val candidates = holidays.mapNotNull { (key, h) ->
        if (!h.isOff) return@mapNotNull null
        val md = runCatching { java.time.MonthDay.parse(key, DateTimeFormatter.ofPattern(HOLIDAY_FMT)) }.getOrNull()
            ?: return@mapNotNull null
        var date = md.atYear(today.year)
        if (date < today) date = md.atYear(today.year + 1)
        date to h
    }
    return candidates.minByOrNull { it.first }
}

private fun weekdayCn(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "周一"
    DayOfWeek.TUESDAY -> "周二"
    DayOfWeek.WEDNESDAY -> "周三"
    DayOfWeek.THURSDAY -> "周四"
    DayOfWeek.FRIDAY -> "周五"
    DayOfWeek.SATURDAY -> "周六"
    DayOfWeek.SUNDAY -> "周日"
}
