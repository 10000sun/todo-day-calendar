package com.todocalendar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle as DayStyle
import java.util.Locale

// ───────── 공통: 위젯 안의 터치는 Glance 콜백 대신 단순한 브로드캐스트로 처리한다 ─────────

/** 할 일 완료/취소 (done=true 완료). ToggleReceiver가 처리 */
private fun toggle(ctx: Context, e: Entry, day: Long, done: Boolean = true) = actionSendBroadcast(toggleIntent(ctx, e, day, done))

private const val MONTH_PREF = "month_widget"

/** 월간 위젯에서 선택한 날짜 (오늘 고른 이번 달 날짜만, 아니면 오늘) */
private fun selectedDay(ctx: Context, today: LocalDate): LocalDate {
    val p = ctx.getSharedPreferences(MONTH_PREF, Context.MODE_PRIVATE)
    // 고른 날짜는 그날 하루만 유지: 날짜가 바뀌면 오늘로 돌아간다
    if (p.getLong("selOn", -1) != today.toEpochDay()) return today
    val s = LocalDate.ofEpochDay(p.getLong("sel", today.toEpochDay()))
    return if (YearMonth.from(s) == YearMonth.from(today)) s else today
}

/** 달력의 날짜를 눌렀을 때: 선택 날짜를 저장하고 월간 위젯만 다시 그린다 */
class SelectDayReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val day = i.getLongExtra("day", 0L)
        val p = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                c.getSharedPreferences(MONTH_PREF, Context.MODE_PRIVATE).edit()
                    .putLong("sel", day).putLong("selOn", LocalDate.now().toEpochDay()).apply()
                Refresh.updateMonthWidget(c)
            } finally { p.finish() }
        }
    }
}

/** 한 줄: 체크 표시(mark)만 누르면 완료(toggle), 나머지 영역은 앱 열기(open). toggle이 null이면 표시만 */
@Composable
private fun EntryRow(mark: String, text: String, color: ColorProvider, size: TextUnit, toggle: Action?, open: Action, strike: Boolean = false) {
    Row(GlanceModifier.fillMaxWidth().clickable(open), verticalAlignment = Alignment.CenterVertically) {
        if (mark.isNotEmpty()) {
            val m = GlanceModifier.padding(end = 10.dp, top = 4.dp, bottom = 4.dp)
            Text(mark, if (toggle != null) m.clickable(toggle) else m, style = TextStyle(color = color, fontSize = (size.value + 3).sp))
        }
        Text(
            text, GlanceModifier.defaultWeight().padding(vertical = 4.dp),
            style = TextStyle(color = color, fontSize = size, textDecoration = if (strike) TextDecoration.LineThrough else null)
        )
    }
}

private fun body(e: Entry) = (if (e.timeMin >= 0) e.timeText() + " " else "") + e.title

private val BG = Color(0xE61E1E2E)
private const val ROW_H = 26f   // 목록 한 줄 높이(dp) 추정치

// ───────── 오늘 위젯: 날짜-기온 / 할 일 / D-day ─────────

class TodayWidget : GlanceAppWidget() {
    /** 위젯 실제 크기를 알아야 들어갈 줄 수를 맞출 수 있다 */
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val s = Summary.load(context)
        provideContent { Body(s) }
    }
}

@Composable
private fun Body(s: Summary) {
    val ctx = LocalContext.current
    val white = ColorProvider(Color.White)
    val orange = ColorProvider(Color(0xFFFFB74D))
    val open = actionStartActivity(Intent(ctx, MainActivity::class.java))
    val headStyle = TextStyle(color = white, fontSize = 13.sp, fontWeight = FontWeight.Bold)

    // 높이에 맞춰 보여줄 줄 수 계산: 패딩 24 + 제목 24 + 소제목 2개 44
    val capacity = ((LocalSize.current.height.value - 92f) / ROW_H).toInt().coerceAtLeast(2)
    val ddReserve = if (s.dd.isEmpty()) 0 else minOf(s.dd.size, maxOf(1, capacity / 3))
    var todoRows = minOf(s.items.size, capacity - ddReserve)
    var ddRows = minOf(s.dd.size, capacity - todoRows)   // 할 일이 적으면 남는 자리는 D-day가 사용
    val todoMore = s.items.size > todoRows
    val ddMore = s.dd.size > ddRows
    if (todoMore) todoRows = maxOf(0, todoRows - 1)   // "… 외 N개" 줄 자리
    if (ddMore) ddRows = maxOf(0, ddRows - 1)

    Column(GlanceModifier.fillMaxSize().background(ColorProvider(BG)).cornerRadius(16.dp).padding(12.dp).clickable(open)) {
        Text(s.title, GlanceModifier.fillMaxWidth(), style = TextStyle(color = white, fontSize = 16.sp, fontWeight = FontWeight.Bold))
        Text(if (s.items.isEmpty()) "할 일 없음" else "할 일", GlanceModifier.fillMaxWidth().padding(top = 6.dp), style = headStyle)
        s.items.take(todoRows).forEach { e ->
            EntryRow(if (e.isEvent) "◆" else "☐", body(e), white, 13.sp, if (e.isEvent) null else toggle(ctx, e, s.day), open)
        }
        if (todoMore) Text("… 외 ${s.items.size - todoRows}개", style = TextStyle(color = white, fontSize = 12.sp))
        Text(if (s.dd.isEmpty()) "D-day 없음" else "D-day", GlanceModifier.fillMaxWidth().padding(top = 6.dp), style = headStyle)
        s.dd.take(ddRows).forEach { (e, d) ->
            EntryRow(if (e.isEvent) "" else "☐", ddayLabel(e, s.today), orange, 13.sp, if (e.isEvent) null else toggle(ctx, e, d), open)
        }
        if (ddMore) Text("… 외 ${s.dd.size - ddRows}개", style = TextStyle(color = orange, fontSize = 12.sp))
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = TodayWidget()
}

// ───────── 월간 달력 위젯: 날짜를 누르면 그 날의 일정/할 일 표시, 있는 날은 점 ─────────

class MonthWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val today = LocalDate.now()
        val ym = YearMonth.from(today)
        val sel = selectedDay(context, today)
        var eventDays = emptySet<Int>()
        var todoDays = emptySet<Int>()
        var items = emptyList<Entry>()
        try {
            val dao = Db.get(context).dao()
            val entries = dao.visibleNow(ym.atDay(1).toEpochDay(), ym.atEndOfMonth().toEpochDay())
            val days = (1..ym.lengthOfMonth())
            eventDays = days.filter { d -> entries.any { it.isEvent && it.occursOn(ym.atDay(d).toEpochDay()) } }.toSet()
            todoDays = days.filter { d -> entries.any { !it.isEvent && it.occursOn(ym.atDay(d).toEpochDay()) } }.toSet()
            items = dao.onDay(sel.toEpochDay())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 데이터를 못 읽어도 달력 틀은 보여준다
        }
        val temp = Weather.cachedLabel(context)
        val holidayDays = (1..ym.lengthOfMonth()).filter { Holidays.isHoliday(ym.atDay(it)) }.toSet()
        val selHoliday = Holidays.name(sel)
        provideContent { MonthBody(ym, today, sel, eventDays, todoDays, items, temp, holidayDays, selHoliday) }
    }
}

private val EVENT_DOT = Color(0xFFFFB74D)
private val TODO_DOT = Color(0xFF4FC3F7)
private const val MIN_CELL = 26f
private const val MAX_CELL = 44f

@Composable
private fun MonthBody(
    ym: YearMonth, today: LocalDate, sel: LocalDate, eventDays: Set<Int>, todoDays: Set<Int>,
    items: List<Entry>, temp: String, holidayDays: Set<Int>, selHoliday: String?
) {
    val ctx = LocalContext.current
    val white = ColorProvider(Color.White)
    val open = actionStartActivity(Intent(ctx, MainActivity::class.java))
    val selDay = sel.toEpochDay()
    val cells = List(ym.atDay(1).dayOfWeek.value % 7) { 0 } + (1..ym.lengthOfMonth())
    val weeks = cells.chunked(7)

    // 위젯 높이에 맞춰 달력 칸 높이와 아래 목록 줄 수를 정한다 (고정 높이면 작은 위젯에서 잘림)
    // 높이 = 패딩 16 + 제목 22 + 요일 16 + 날짜 머리글 22 + 달력 + 목록
    val avail = LocalSize.current.height.value - 16f - 22f - 16f
    var listRows = (((avail - 22f - weeks.size * MIN_CELL) / ROW_H).toInt()).coerceIn(0, 3)
    var cellH = (avail - (if (listRows > 0) 22f + listRows * ROW_H else 0f)) / weeks.size
    if (cellH < MIN_CELL) { listRows = 0; cellH = avail / weeks.size }   // 너무 작으면 달력만
    val cellDp = cellH.coerceIn(18f, MAX_CELL).dp
    val showDots = cellH >= 24f

    Column(GlanceModifier.fillMaxSize().background(ColorProvider(BG)).cornerRadius(16.dp).padding(8.dp).clickable(open)) {
        Row(GlanceModifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${ym.year}년 ${ym.monthValue}월", GlanceModifier.defaultWeight(),
                style = TextStyle(color = white, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            )
            Text(temp, style = TextStyle(color = white, fontSize = 13.sp))
        }
        Row(GlanceModifier.fillMaxWidth()) {
            listOf("일", "월", "화", "수", "목", "금", "토").forEach {
                Box(GlanceModifier.defaultWeight().height(16.dp), contentAlignment = Alignment.Center) {
                    Text(it, style = TextStyle(color = ColorProvider(Color(0xB3FFFFFF)), fontSize = 11.sp))
                }
            }
        }
        weeks.forEach { week ->
            Row(GlanceModifier.fillMaxWidth()) {
                for (i in 0 until 7) {
                    val d = week.getOrElse(i) { 0 }
                    DayCell(if (d == 0) null else ym.atDay(d), today, sel, d in eventDays, d in todoDays, d in holidayDays, cellDp, showDots)
                }
            }
        }
        if (listRows > 0) {
            Text(
                "${sel.monthValue}월 ${sel.dayOfMonth}일 (${sel.dayOfWeek.getDisplayName(DayStyle.SHORT, Locale.KOREAN)})" +
                    (selHoliday?.let { " · $it" } ?: "") + if (items.isEmpty()) " · 없음" else "",
                GlanceModifier.fillMaxWidth().height(22.dp).padding(top = 4.dp),
                style = TextStyle(color = white, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            )
            val more = items.size > listRows
            items.take(if (more) listRows - 1 else listRows).forEach {
                val done = it.isDone(selDay)
                EntryRow(
                    if (it.isEvent) "◆" else if (done) "☑" else "☐", body(it), white, 12.sp,
                    if (it.isEvent) null else toggle(ctx, it, selDay, !done), open, strike = done
                )
            }
            if (more) Text("… 외 ${items.size - (listRows - 1)}개", style = TextStyle(color = white, fontSize = 11.sp))
        }
    }
}

/** 날짜 칸: 선택한 날은 배경색, 오늘은 보라색 글자, 공휴일은 빨강, 일정은 주황 점 / 할 일은 하늘색 점 */
@Composable
private fun RowScope.DayCell(
    d: LocalDate?, today: LocalDate, sel: LocalDate, hasEvent: Boolean, hasTodo: Boolean,
    holiday: Boolean, height: Dp, showDots: Boolean
) {
    val cell = GlanceModifier.defaultWeight().height(height).padding(1.dp)
    if (d == null) { Box(cell) {}; return }
    val ctx = LocalContext.current
    val isSel = d == sel
    val isToday = d == today
    val color = when {
        isSel -> Color.White
        isToday -> Color(0xFFB39DFF)
        holiday || d.dayOfWeek == DayOfWeek.SUNDAY -> Color(0xFFFF8A80)
        d.dayOfWeek == DayOfWeek.SATURDAY -> Color(0xFF82B1FF)
        else -> Color.White
    }
    val select = actionSendBroadcast(
        Intent(ctx, SelectDayReceiver::class.java).setData(Uri.parse("todo://select/${d.toEpochDay()}")).putExtra("day", d.toEpochDay())
    )
    val inner = GlanceModifier.fillMaxSize()
    Box(cell.clickable(select), contentAlignment = Alignment.Center) {
        Column(
            if (isSel) inner.background(ColorProvider(Color(0xFF6750A4))).cornerRadius(8.dp) else inner,
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                d.dayOfMonth.toString(),
                style = TextStyle(color = ColorProvider(color), fontSize = 11.sp, fontWeight = if (isToday || isSel) FontWeight.Bold else FontWeight.Normal)
            )
            if (showDots) Row {
                if (hasEvent) Text("●", style = TextStyle(color = ColorProvider(EVENT_DOT), fontSize = 6.sp))
                if (hasTodo) Text("●", style = TextStyle(color = ColorProvider(TODO_DOT), fontSize = 6.sp))
                // 점이 없는 날도 같은 높이를 차지하도록 투명 점 하나
                if (!hasEvent && !hasTodo) Text("●", style = TextStyle(color = ColorProvider(Color.Transparent), fontSize = 6.sp))
            }
        }
    }
}

class MonthWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = MonthWidget()
}
