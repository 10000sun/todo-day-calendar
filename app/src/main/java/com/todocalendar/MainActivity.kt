package com.todocalendar

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

class MainActivity : ComponentActivity() {
    /** 첫 연결 때 고정 위치에 이미 백업이 있고 현재 데이터도 있을 때, 복원/덮어쓰기를 묻기 위한 상태 (백업 항목 수) */
    private var pendingExisting by mutableStateOf<Int?>(null)
    private var showBackup by mutableStateOf(false)
    /** 처음 실행(또는 이전 버전에서 업데이트 후 처음) 때 보여주는 사용법 팝업 */
    private var showGuide by mutableStateOf(false)
    /** '?' 버튼으로 다시 열어 본 사용법 */
    private var showHelp by mutableStateOf(false)
    private val syncing = java.util.concurrent.atomic.AtomicBoolean(false)

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshAll()
        syncBackup()
    }
    private val pickRestore = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { restoreFrom(it) } }

    /** 알림/위젯 갱신 + 현재 위치 저장 후 한 번 더 갱신(기온 반영). Activity를 붙들지 않도록 applicationContext 사용 */
    private fun refreshAll() {
        val app = applicationContext
        Refresh.fire(app)
        Weather.captureLocation(app) { Refresh.fire(app) }
    }


    /** 자동 백업에 필요한 권한 설정 열기 (Android 11+: 모든 파일 접근 화면, 10 이하: 저장소 권한 요청) */
    private fun openAccessSettings() {
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            }.onFailure { runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } }
        } else askPerms.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
    }

    /**
     * 자동 백업 연결. 권한이 없으면 안내를 한 번 띄우고, 있으면 고정 위치의 기존 백업을 확인한다:
     * 백업 없음 -> 바로 백업 시작 / 현재 데이터 없음 -> 자동 복원 / 둘 다 있음 -> 사용자에게 선택 요청
     */
    private fun syncBackup() {
        val app = applicationContext
        if (!Backup.hasAccess(app)) {
            if (!Backup.prompted(app) && !showGuide) { Backup.setPrompted(app); showBackup = true }
            return
        }
        if (Backup.resolved(app)) return
        // onStart / 권한 결과 콜백이 겹쳐 호출돼도 확인은 한 번에 하나만 (복원·토스트·팝업이 중복되지 않도록)
        if (!syncing.compareAndSet(false, true)) return
        Refresh.launch {
            try {
                val n = Backup.peek()
                val count = Db.get(app).dao().all().size
                when {
                    n == Backup.NOT_BACKUP -> { pendingExisting = Backup.NOT_BACKUP }   // 읽을 수 없는 파일: 덮어쓸지 사용자에게 묻는다
                    n == null || n == 0 -> { Backup.setResolved(app); Backup.write(app) }
                    count == 0 -> restoreAuto()
                    else -> { pendingExisting = n }
                }
            } finally { syncing.set(false) }
        }
    }

    private fun restoreAuto() {
        val app = applicationContext
        Refresh.launch {
            val n = Backup.restoreFile(app)
            if (n == null) { toastApp(app, "백업을 복원하지 못했습니다"); return@launch }
            Backup.setResolved(app)
            Refresh.all(app)
            toastApp(app, "${n}개 항목을 복원했습니다")
        }
    }

    private fun overwriteBackup() {
        val app = applicationContext
        Refresh.launch {
            Backup.setResolved(app)
            toastApp(app, if (Backup.write(app)) "현재 데이터로 자동 백업을 시작했습니다" else "백업 파일에 쓰지 못했습니다")
        }
    }

    /** 사용자가 고른 다른 파일에서 복원 (복원 후 고정 위치 백업도 갱신) */
    private fun restoreFrom(uri: Uri) {
        val app = applicationContext
        Refresh.launch {
            val n = Backup.restore(app, uri)
            if (n == null) { toastApp(app, "복원하지 못했습니다. 투두데이 캘린더 백업 파일인지 확인해 주세요"); return@launch }
            // 권한이 없으면 고정 위치의 기존 백업을 아직 확인하지 못한 것이므로 '정리 완료'로 표시하지 않는다
            // (표시하면 나중에 권한을 허용해도 기존 백업을 묻지 않고 덮어쓰게 된다)
            if (Backup.hasAccess(app)) {
                Backup.setResolved(app)
                Backup.autoWrite(app)
            }
            Refresh.all(app)
            toastApp(app, "${n}개 항목을 복원했습니다")
        }
    }

    /** 요청 창을 실제로 띄웠으면 true (결과 콜백이 이어서 백업 연결을 진행한다) */
    private fun askRuntimePermissions(): Boolean {
        val need = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) askPerms.launch(need.toTypedArray())
        return need.isNotEmpty()
    }

    private fun closeGuide() {
        val first = showGuide
        showGuide = false
        showHelp = false
        if (first) {
            Guide.markSeen(applicationContext)
            // 권한 요청 창이 떠 있으면 그 결과 콜백에서 이어서 진행해 팝업이 겹치지 않게 한다
            if (!askRuntimePermissions()) syncBackup()   // 안내 때문에 미뤄 둔 백업 안내/연결을 이어서 진행
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showGuide = !Guide.seen(applicationContext)
        // 안내를 먼저 보여주고, 권한 요청은 안내를 닫은 뒤에 한다 (처음 실행 시 팝업이 겹치지 않도록)
        if (savedInstanceState == null && !showGuide) askRuntimePermissions()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    CalendarScreen(onOpenBackup = { showBackup = true }, onOpenHelp = { showHelp = true })
                    if (showGuide || showHelp) GuideDialog(onClose = { closeGuide() })
                    if (showBackup) BackupDialog(
                        onDismiss = { showBackup = false },
                        onSettings = { showBackup = false; openAccessSettings() },
                        onRestore = { showBackup = false; pickRestore.launch(arrayOf("*/*")) }
                    )
                    pendingExisting?.let { n ->
                        val unreadable = n < 0
                        AlertDialog(
                            onDismissRequest = { pendingExisting = null },
                            title = { Text(if (unreadable) "백업 파일을 읽을 수 없습니다" else "이전에 저장된 백업이 있습니다") },
                            text = {
                                Text(
                                    if (unreadable)
                                        "${Backup.PATH_TEXT}의 내용이 손상됐거나 투두데이 캘린더 백업이 아닙니다.\n\n" +
                                            "덮어쓰면 현재 데이터로 새 백업을 시작합니다. 나중에 정하려면 '나중에'를 누르세요."
                                    else
                                        "${Backup.PATH_TEXT}에 ${n}개 항목이 저장돼 있습니다.\n\n" +
                                            "• 복원: 현재 데이터를 이 백업으로 교체합니다.\n" +
                                            "• 덮어쓰기: 현재 데이터로 기존 백업을 지웁니다."
                                )
                            },
                            confirmButton = {
                                if (unreadable) TextButton({ pendingExisting = null; overwriteBackup() }) { Text("덮어쓰기") }
                                else TextButton({ pendingExisting = null; restoreAuto() }) { Text("복원") }
                            },
                            dismissButton = {
                                if (unreadable) TextButton({ pendingExisting = null }) { Text("나중에") }
                                else TextButton({ pendingExisting = null; overwriteBackup() }) { Text("덮어쓰기") }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        refreshAll()
        syncBackup()   // 권한 설정 화면에서 돌아왔을 때도 여기서 이어진다
    }
}

/** Activity를 붙들지 않는 토스트 (앱 범위 코루틴에서 호출해도 안전) */
private fun toastApp(app: Context, msg: String) {
    Handler(Looper.getMainLooper()).post { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() }
}

private val dateSaver = Saver<LocalDate, Long>(save = { it.toEpochDay() }, restore = { LocalDate.ofEpochDay(it) })
private val monthSaver = Saver<YearMonth, String>(save = { it.toString() }, restore = { YearMonth.parse(it) })

@Composable
fun CalendarScreen(onOpenBackup: () -> Unit, onOpenHelp: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    val entryDao = remember { db.dao() }
    val today = LocalDate.now()
    // 화면 회전 등으로 Activity가 다시 만들어져도 보던 달/날짜/추가창이 유지되도록
    var month by rememberSaveable(stateSaver = monthSaver) { mutableStateOf(YearMonth.from(today)) }
    var selected by rememberSaveable(stateSaver = dateSaver) { mutableStateOf(today) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Entry?>(null) }
    val entries by remember(month) { entryDao.visible(month.atDay(1).toEpochDay(), month.atEndOfMonth().toEpochDay()) }
        .collectAsState(emptyList())
    val ddays by remember { entryDao.ddays() }.collectAsState(emptyList())

    val dayEntries by remember(selected) { entryDao.visible(selected.toEpochDay(), selected.toEpochDay()) }
        .collectAsState(emptyList())
    val soon = remember(ddays, today) { upcomingDates(ddays, today) }

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
                    items(soon, key = { it.first.id }) { (e, d) ->
                        AssistChip(
                            onClick = { selected = LocalDate.ofEpochDay(d); month = YearMonth.from(selected) },
                            label = { Text(ddayLabel(e, d, today)) }
                        )
                    }
                }
                TextButton(onOpenHelp) { Text("?") }
                TextButton(onOpenBackup) { Text("백업") }
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
                        DayCell(d, d == selected, d == today, day.any { it.isEvent }, day.any { !it.isEvent }, d != null && Holidays.isHoliday(d)) { d?.let { selected = it } }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${selected.monthValue}월 ${selected.dayOfMonth}일", style = MaterialTheme.typography.titleMedium)
                Holidays.name(selected)?.let { Text(it, color = Color(0xFFE53935), style = MaterialTheme.typography.labelLarge) }
            }
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
                                if (e.repeat != Repeat.NONE) e.repeat.label else null, if (e.dday) ddayTag(sel - today.toEpochDay()) else null
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
private fun RowScope.DayCell(d: LocalDate?, sel: Boolean, isToday: Boolean, hasEvent: Boolean, hasTodo: Boolean, holiday: Boolean, onClick: () -> Unit) {
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
                    holiday || d.dayOfWeek == DayOfWeek.SUNDAY -> Color(0xFFE53935)
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
private fun BackupDialog(onDismiss: () -> Unit, onSettings: () -> Unit, onRestore: () -> Unit) {
    val ctx = LocalContext.current
    val access = Backup.hasAccess(ctx)
    val on = Backup.isActive(ctx)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("자동 백업") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        on -> "자동 백업 켜짐: 데이터를 바꿀 때마다 아래 위치에 저장됩니다."
                        access -> "자동 백업이 아직 시작되지 않았거나 마지막 저장에 실패했습니다. 앱을 다시 열어 보세요."
                        else -> "자동 백업 꺼짐: '모든 파일 접근'을 허용하면 앱이 알아서 아래 위치에 저장합니다. 폴더를 직접 고를 필요가 없습니다."
                    }
                )
                Text(Backup.PATH_TEXT, style = MaterialTheme.typography.labelMedium)
                Text(
                    "앱을 지우고 다시 설치해도 이 파일은 남습니다. 재설치 후 '모든 파일 접근'을 다시 허용하면 자동으로 복원됩니다.",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        },
        confirmButton = { if (access) TextButton(onDismiss) { Text("닫기") } else TextButton(onSettings) { Text("권한 설정 열기") } },
        dismissButton = { TextButton(onRestore) { Text("다른 파일에서 복원") } }
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
