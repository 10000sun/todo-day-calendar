package com.todocalendar

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    val db = remember { Db.get(ctx) }
    val entryDao = remember { db.dao() }
    val today = LocalDate.now()
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var selected by remember { mutableStateOf(today) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Entry?>(null) }
    val entries by remember(month) { entryDao.visible(month.atDay(1).toEpochDay(), month.atEndOfMonth().toEpochDay()) }
        .collectAsState(emptyList())
    val ddays by remember { entryDao.ddays() }.collectAsState(emptyList())

    val dayEntries by remember(selected) { entryDao.visible(selected.toEpochDay(), selected.toEpochDay()) }
        .collectAsState(emptyList())
    val soon = remember(ddays, today) { upcoming(ddays, today) }

    fun write(block: suspend Db.() -> Unit) {
        val app = ctx.applicationContext
        Refresh.launch { db.block(); Refresh.all(app, fire = false) }
    }

    Scaffold(
        floatingActionButton = { FloatingActionButton(onClick = { adding = true }) { Text("＋") } }
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 12.dp)) {
            if (soon.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(soon, key = { it.id }) { e ->
                        AssistChip(
                            onClick = { selected = LocalDate.ofEpochDay(e.nextOn(today.toEpochDay()) ?: e.date); month = YearMonth.from(selected) },
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
                        val day = d?.let { x -> entries.filter { it.occursOn(x.toEpochDay()) } }.orEmpty()
                        DayCell(d, d == selected, d == today, day.any { it.isEvent }, day.any { !it.isEvent }) { d?.let { selected = it } }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("${selected.monthValue}월 ${selected.dayOfMonth}일", style = MaterialTheme.typography.titleMedium)
            val sel = selected.toEpochDay()
            val list = dayEntries.filter { it.occursOn(sel) }.sortedWith(dayOrder(sel))
            LazyColumn(Modifier.weight(1f)) {
                items(list, key = { it.id }) { e ->
                    val done = e.isDone(sel)
                    Row(Modifier.fillMaxWidth().clickable { editing = e }, verticalAlignment = Alignment.CenterVertically) {
                        if (e.isEvent) Text("◆", Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.primary)
                        else Checkbox(done, { c -> write { setDone(e.id, sel, c) } })
                        Column(Modifier.weight(1f)) {
                            Text(e.title, textDecoration = if (done) TextDecoration.LineThrough else null)
                            val sub = listOfNotNull(
                                e.timeText().ifEmpty { null }, if (e.remind) "알림" else null,
                                if (e.repeat != Repeat.NONE) e.repeat.label else null, if (e.dday) "D-day" else null
                            ).joinToString(" · ")
                            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                        TextButton({ write { dao().delete(e) } }) { Text("삭제") }
                    }
                }
            }
        }
    }

    if (adding || editing != null) {
        val close = { adding = false; editing = null }
        EntryDialog(editing, selected, close) { e -> write { save(e) }; close() }
    }
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

/** 선택된 쪽만 진하게 채워서 보이고, 선택 안 된 쪽은 테두리 없는 연한 배경 */
@Composable
private fun Choice(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
        border = null,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        )
    )
}

@Composable
private fun EntryDialog(init: Entry?, date: LocalDate, onDismiss: () -> Unit, onSave: (Entry) -> Unit) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf(init?.title ?: "") }
    var event by remember { mutableStateOf(init?.isEvent ?: false) }
    var dday by remember { mutableStateOf(init?.dday ?: false) }
    var rep by remember { mutableStateOf(init?.repeat ?: Repeat.NONE) }
    var timeMin by remember { mutableStateOf(init?.timeMin ?: -1) }
    var remind by remember { mutableStateOf(init?.remind ?: false) }
    var day by remember { mutableStateOf(init?.let { LocalDate.ofEpochDay(it.date) } ?: date) }
    var menu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (init == null) "추가" else "수정") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(title, { title = it }, singleLine = true, label = { Text("제목") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice(!event, "할 일") { event = false }
                    Choice(event, "일정") { event = true }
                }
                TextButton({
                    DatePickerDialog(ctx, { _, y, m, d -> day = LocalDate.of(y, m + 1, d) }, day.year, day.monthValue - 1, day.dayOfMonth).show()
                }) { Text((if (rep == Repeat.NONE) "날짜: " else "시작일: ") + day) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton({
                        val t = if (timeMin >= 0) timeMin else 9 * 60
                        TimePickerDialog(ctx, { _, h, m -> timeMin = h * 60 + m }, t / 60, t % 60, true).show()
                    }) { Text(if (timeMin < 0) "시간 없음" else "시간: %02d:%02d".format(timeMin / 60, timeMin % 60)) }
                    if (timeMin >= 0) TextButton({ timeMin = -1; remind = false }) { Text("지움") }
                }
                if (timeMin >= 0) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(remind, { remind = it }); Text("정시에 알림")
                }
                Box {
                    TextButton({ menu = true }) { Text("반복: ${rep.label}") }
                    DropdownMenu(menu, { menu = false }) {
                        Repeat.values().forEach { r -> DropdownMenuItem({ Text(r.label) }, { rep = r; menu = false }) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(dday, { dday = it }); Text("D-day로 표시")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = {
                onSave(
                    (init ?: Entry(date = 0, title = "")).copy(
                        date = day.toEpochDay(), title = title.trim(), isEvent = event, dday = dday,
                        repeat = rep, timeMin = timeMin, remind = remind && timeMin >= 0
                    )
                )
            }) { Text(if (init == null) "추가" else "저장") }
        },
        dismissButton = { TextButton(onDismiss) { Text("취소") } }
    )
}
