package com.todocalendar

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
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

/** 파일 선택 시 읽기/쓰기 권한을 함께 받아 두어, 복원한 파일에 자동 백업을 계속 쓸 수 있게 한다 */
private class OpenWritable : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
}

/** 백업 파일 지정 시에도 영구 접근 권한(재부팅 후에도 유지)을 함께 요청한다 */
private class CreateWritable : ActivityResultContracts.CreateDocument("application/json") {
    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
}

class MainActivity : ComponentActivity() {
    /** 선택한 파일에 이미 백업이 있을 때 사용자에게 복원/덮어쓰기를 묻기 위한 상태 (파일, 항목 수) */
    private var pendingExisting by mutableStateOf<Pair<Uri, Int>?>(null)

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshAll() }
    private val pickBackup = registerForActivityResult(CreateWritable()) { uri -> uri?.let { setBackupTarget(it) } }
    private val pickRestore = registerForActivityResult(OpenWritable()) { uri -> uri?.let { restoreFrom(it) } }

    /** 알림/위젯 갱신 + 현재 위치 저장 후 한 번 더 갱신(기온 반영). Activity를 붙들지 않도록 applicationContext 사용 */
    private fun refreshAll() {
        val app = applicationContext
        Refresh.fire(app)
        Weather.captureLocation(app) { Refresh.fire(app) }
    }

    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    /** 영구 접근 권한 저장. 실패하면(재부팅 후 백업이 조용히 멈출 위치) false */
    private fun persist(uri: Uri): Boolean = runCatching {
        contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    }.isSuccess

    private fun setBackupTarget(uri: Uri) {
        if (!persist(uri)) { toast("이 위치는 자동 백업에 쓸 수 없습니다. 다른 위치를 선택해 주세요"); return }
        val app = applicationContext
        Refresh.launch {
            // 이미 백업이 들어 있는 파일이면 덮어쓰기 전에 사용자에게 먼저 묻는다
            val n = Backup.peek(app, uri)
            if (n != null && n > 0) runOnUiThread { pendingExisting = uri to n } else writeTarget(uri)
        }
    }

    private fun writeTarget(uri: Uri) {
        val app = applicationContext
        Refresh.launch {
            if (Backup.write(app, uri)) {
                Backup.setTarget(app, uri)
                toast("백업 파일을 지정했습니다. 이제 데이터를 바꿀 때마다 자동으로 저장됩니다.")
            } else toast("백업 파일에 쓰지 못했습니다")
        }
    }

    private fun restoreFrom(uri: Uri) {
        val persisted = persist(uri)
        val app = applicationContext
        Refresh.launch {
            val n = Backup.restore(app, uri)
            if (n == null) { toast("복원하지 못했습니다. 투두데이 캘린더 백업 파일인지 확인해 주세요"); return@launch }
            val auto = persisted && Backup.write(app, uri)
            if (auto) Backup.setTarget(app, uri)
            Refresh.all(app)
            toast("${n}개 항목을 복원했습니다" + if (auto) "" else ". 자동 백업은 '백업 파일 지정'으로 다시 켜 주세요")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val need = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) askPerms.launch(need.toTypedArray())
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    CalendarScreen(
                        onPickBackup = { pickBackup.launch("todocalendar-backup.json") },
                        onRestore = { pickRestore.launch(arrayOf("*/*")) }
                    )
                    pendingExisting?.let { (uri, n) ->
                        AlertDialog(
                            onDismissRequest = { pendingExisting = null },
                            title = { Text("이미 백업이 들어 있는 파일입니다") },
                            text = {
                                Text(
                                    "이 파일에는 ${n}개 항목이 저장돼 있습니다.\n\n" +
                                        "• 복원: 현재 데이터를 이 백업으로 교체합니다.\n" +
                                        "• 덮어쓰기: 현재 데이터로 이 파일의 기존 백업을 지웁니다."
                                )
                            },
                            confirmButton = { TextButton({ pendingExisting = null; restoreFrom(uri) }) { Text("복원") } },
                            dismissButton = { TextButton({ pendingExisting = null; writeTarget(uri) }) { Text("덮어쓰기") } }
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        refreshAll()
    }
}

private val dateSaver = Saver<LocalDate, Long>(save = { it.toEpochDay() }, restore = { LocalDate.ofEpochDay(it) })
private val monthSaver = Saver<YearMonth, String>(save = { it.toString() }, restore = { YearMonth.parse(it) })

@Composable
fun CalendarScreen(onPickBackup: () -> Unit, onRestore: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    val entryDao = remember { db.dao() }
    val today = LocalDate.now()
    // 화면 회전 등으로 Activity가 다시 만들어져도 보던 달/날짜/추가창이 유지되도록
    var month by rememberSaveable(stateSaver = monthSaver) { mutableStateOf(YearMonth.from(today)) }
    var selected by rememberSaveable(stateSaver = dateSaver) { mutableStateOf(today) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var showBackup by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Entry?>(null) }
    val entries by remember(month) { entryDao.visible(month.atDay(1).toEpochDay(), month.atEndOfMonth().toEpochDay()) }
        .collectAsState(emptyList())
    val ddays by remember { entryDao.ddays() }.collectAsState(emptyList())

    val dayEntries by remember(selected) { entryDao.visible(selected.toEpochDay(), selected.toEpochDay()) }
        .collectAsState(emptyList())
    val soon = remember(ddays, today) { upcoming(ddays, today) }

    fun write(block: suspend Db.() -> Unit) {
        val app = ctx.applicationContext
        Refresh.launch { db.block(); Refresh.all(app, userEdit = true) }
    }

    Scaffold(
        floatingActionButton = { FloatingActionButton(onClick = { adding = true }) { Text("＋") } }
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(soon, key = { it.id }) { e ->
                        AssistChip(
                            onClick = { selected = LocalDate.ofEpochDay(e.nextOn(today.toEpochDay()) ?: e.date); month = YearMonth.from(selected) },
                            label = { Text(ddayLabel(e, today)) }
                        )
                    }
                }
                TextButton({ showBackup = true }) { Text("백업") }
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

    if (showBackup) BackupDialog({ showBackup = false }, { showBackup = false; onPickBackup() }, { showBackup = false; onRestore() })

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

@Composable
private fun BackupDialog(onDismiss: () -> Unit, onPick: () -> Unit, onRestore: () -> Unit) {
    val ctx = LocalContext.current
    val has = Backup.target(ctx) != null
    val on = Backup.isActive(ctx)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("백업 / 복원") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        on -> "자동 백업 켜짐: 데이터를 바꿀 때마다 백업 파일에 저장됩니다."
                        has -> "자동 백업 중단됨: 백업 파일에 쓰지 못하고 있습니다(권한을 잃었거나 저장 공간 부족). 백업 파일을 다시 지정해 주세요."
                        else -> "자동 백업 꺼짐: 백업 파일을 지정하면 데이터를 바꿀 때마다 자동 저장됩니다."
                    }
                )
                Text(
                    "앱을 지우고 다시 설치했다면 '백업에서 복원'으로 저장해 둔 파일을 선택하세요. 현재 데이터는 백업 내용으로 교체됩니다.",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        },
        confirmButton = { TextButton(onPick) { Text(if (on) "백업 파일 변경" else if (has) "백업 파일 다시 지정" else "백업 파일 지정") } },
        dismissButton = { TextButton(onRestore) { Text("백업에서 복원") } }
    )
}

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
