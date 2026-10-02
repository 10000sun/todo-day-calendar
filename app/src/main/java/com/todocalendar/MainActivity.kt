package com.todocalendar

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

class MainActivity : ComponentActivity() {
    private val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { Refresh.fire(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS) else Refresh.fire(this)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { CalendarScreen() }
            }
        }
    }
}

@Composable
fun CalendarScreen() {
    val ctx = LocalContext.current
    val dao = remember { Db.get(ctx).dao() }
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var selected by remember { mutableStateOf(today) }
    var adding by remember { mutableStateOf(false) }
    val entries by remember(month) { dao.range(month.atDay(1).toEpochDay(), month.atEndOfMonth().toEpochDay()) }
        .collectAsState(emptyList())
    val ddays by remember { dao.ddays() }.collectAsState(emptyList())

    fun write(block: suspend EntryDao.() -> Unit) { scope.launch { dao.block(); Refresh.all(ctx) } }

    Scaffold(
        floatingActionButton = { FloatingActionButton(onClick = { adding = true }) { Text("＋") } }
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 12.dp)) {
            val upcoming = upcoming(ddays, today)
            if (upcoming.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(upcoming, key = { it.id }) { e ->
                        AssistChip(
                            onClick = { selected = LocalDate.ofEpochDay(e.date); month = YearMonth.from(selected) },
                            label = { Text(ddayLabel(e, today)) }
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ month = month.minusMonths(1) }) { Text("<") }
                Text("${month.year}년 ${month.monthValue}월", style = MaterialTheme.typography.titleLarge)
                Row {
                    TextButton({ month = YearMonth.from(today); selected = today }) { Text("오늘") }
                    TextButton({ month = month.plusMonths(1) }) { Text(">") }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                listOf("일", "월", "화", "수", "목", "금", "토").forEach {
                    Text(it, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelMedium)
                }
            }
            val cells = List(month.atDay(1).dayOfWeek.value % 7) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    for (i in 0 until 7) {
                        val d = week.getOrNull(i)
                        val day = d?.let { x -> entries.filter { it.date == x.toEpochDay() } }.orEmpty()
                        DayCell(d, d == selected, d == today, day.any { it.isEvent }, day.any { !it.isEvent }) { d?.let { selected = it } }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("${selected.monthValue}월 ${selected.dayOfMonth}일", style = MaterialTheme.typography.titleMedium)
            val list = entries.filter { it.date == selected.toEpochDay() }
                .sortedWith(compareByDescending<Entry> { it.isEvent }.thenBy { it.done }.thenBy { it.id })
            LazyColumn(Modifier.weight(1f)) {
                items(list, key = { it.id }) { e ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (e.isEvent) Text("◆", Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.primary)
                        else Checkbox(e.done, { c -> write { update(e.copy(done = c)) } })
                        Text(
                            e.title, Modifier.weight(1f),
                            textDecoration = if (e.done) TextDecoration.LineThrough else null
                        )
                        if (e.dday) Text("D-day", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                        TextButton({ write { delete(e) } }) { Text("삭제") }
                    }
                }
            }
        }
    }

    if (adding) AddDialog(selected, { adding = false }) { e -> write { add(e) }; adding = false }
}

@Composable
private fun RowScope.DayCell(d: LocalDate?, sel: Boolean, isToday: Boolean, hasEvent: Boolean, hasTodo: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).height(52.dp).padding(2.dp).clip(RoundedCornerShape(8.dp))
            .background(if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(enabled = d != null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (d != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                d.dayOfMonth.toString(),
                color = when {
                    isToday -> MaterialTheme.colorScheme.primary
                    d.dayOfWeek == DayOfWeek.SUNDAY -> Color(0xFFE53935)
                    d.dayOfWeek == DayOfWeek.SATURDAY -> Color(0xFF1E88E5)
                    else -> MaterialTheme.colorScheme.onSurface
                },
                fontWeight = if (isToday) FontWeight.Bold else null
            )
            Row {
                if (hasEvent) Dot(MaterialTheme.colorScheme.primary)
                if (hasTodo) Dot(MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
private fun Dot(c: Color) = Box(Modifier.padding(1.dp).size(5.dp).background(c, CircleShape))

@Composable
private fun AddDialog(date: LocalDate, onDismiss: () -> Unit, onSave: (Entry) -> Unit) {
    var title by remember { mutableStateOf("") }
    var event by remember { mutableStateOf(false) }
    var dday by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${date.monthValue}월 ${date.dayOfMonth}일 추가") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, singleLine = true, label = { Text("제목") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!event, { event = false }, { Text("할 일") })
                    FilterChip(event, { event = true }, { Text("일정") })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(dday, { dday = it }); Text("D-day로 표시")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = {
                onSave(Entry(date = date.toEpochDay(), title = title.trim(), isEvent = event, dday = dday))
            }) { Text("추가") }
        },
        dismissButton = { TextButton(onDismiss) { Text("취소") } }
    )
}
