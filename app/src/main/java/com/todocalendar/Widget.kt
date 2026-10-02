package com.todocalendar

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.size
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.CancellationException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle as DayStyle
import java.util.Locale

private val KEY_ID = ActionParameters.Key<Long>("id")
private val KEY_DAY = ActionParameters.Key<Long>("day")
private val KEY_DONE = ActionParameters.Key<Boolean>("done")

/** done=true: 완료 처리, false: 완료 취소 */
private fun toggle(e: Entry, day: Long, done: Boolean = true) =
    actionRunCallback(ToggleAction::class.java, actionParametersOf(KEY_ID to e.id, KEY_DAY to day, KEY_DONE to done))

/** 위젯에서 할 일을 눌렀을 때 완료 처리 후 위젯/알림 갱신 */
class ToggleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[KEY_ID] ?: return
        val day = parameters[KEY_DAY] ?: return
        Db.get(context).setDone(id, day, parameters[KEY_DONE] ?: true)
        Refresh.all(context, userEdit = true)
    }
}

/** 홈 화면 위젯: 알림창과 같은 구성 (날짜 - 기온 / 할 일 / D-day), 할 일은 눌러서 완료 */
class TodayWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val s = Summary.load(context)
        provideContent { Body(s) }
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

@Composable
private fun Body(s: Summary) {
    val white = ColorProvider(Color.White)
    val orange = ColorProvider(Color(0xFFFFB74D))
    val open = actionStartActivity(Intent(LocalContext.current, MainActivity::class.java))
    val headStyle = TextStyle(color = white, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    val todos = s.items.take(MAX_TODO)
    val dds = s.dd.take(MAX_DDAY)
    // 스크롤 목록(LazyColumn)은 바깥 터치를 막아 앱이 안 열리므로, 일반 Column + 개수 제한 + "외 N개"로 처리
    Column(
        GlanceModifier.fillMaxSize()
            .background(ColorProvider(Color(0xE61E1E2E)))
            .cornerRadius(16.dp)
            .padding(12.dp)
            .clickable(open)
    ) {
        Text(s.title, GlanceModifier.fillMaxWidth(), style = TextStyle(color = white, fontSize = 16.sp, fontWeight = FontWeight.Bold))
        Text(if (s.items.isEmpty()) "할 일 없음" else "할 일", GlanceModifier.fillMaxWidth().padding(top = 6.dp), style = headStyle)
        todos.forEach { e ->
            EntryRow(if (e.isEvent) "◆" else "☐", body(e), white, 13.sp, if (e.isEvent) null else toggle(e, s.day), open)
        }
        if (s.items.size > MAX_TODO) Text("… 외 ${s.items.size - MAX_TODO}개", style = TextStyle(color = white, fontSize = 12.sp))
        Text(if (s.dd.isEmpty()) "D-day 없음" else "D-day", GlanceModifier.fillMaxWidth().padding(top = 6.dp), style = headStyle)
        dds.forEach { (e, d) ->
            EntryRow(if (e.isEvent) "" else "☐", ddayLabel(e, s.today), orange, 13.sp, if (e.isEvent) null else toggle(e, d), open)
        }
        if (s.dd.size > MAX_DDAY) Text("… 외 ${s.dd.size - MAX_DDAY}개", style = TextStyle(color = orange, fontSize = 12.sp))
    }
}

private const val MAX_TODO = 5
private const val MAX_DDAY = 3

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = TodayWidget()
}

private const val MONTH_PREF = "month_widget"

/** 월간 위젯에서 선택한 날짜 (이번 달 안일 때만, 아니면 오늘) */
private fun selectedDay(ctx: Context, today: LocalDate): LocalDate {
    val s = LocalDate.ofEpochDay(ctx.getSharedPreferences(MONTH_PREF, Context.MODE_PRIVATE).getLong("sel", today.toEpochDay()))
    return if (YearMonth.from(s) == YearMonth.from(today)) s else today
}

/** 달력의 날짜를 눌렀을 때: 선택 날짜를 저장하고 위젯을 다시 그린다 */
class SelectDayAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val day = parameters[KEY_DAY] ?: return
        context.getSharedPreferences(MONTH_PREF, Context.MODE_PRIVATE).edit().putLong("sel", day).apply()
        MonthWidget().updateAll(context)
    }
}

/** 홈 화면 월간 달력 위젯: 날짜를 누르면 그 날의 일정/할 일이 아래에 표시되고, 일정·할 일이 있는 날은 점으로 표시 */
class MonthWidget : GlanceAppWidget() {
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
        provideContent { MonthBody(ym, today, sel, eventDays, todoDays, items, temp) }
    }
}

private val EVENT_DOT = Color(0xFFFFB74D)
private val TODO_DOT = Color(0xFF4FC3F7)

@Composable
private fun MonthBody(ym: YearMonth, today: LocalDate, sel: LocalDate, eventDays: Set<Int>, todoDays: Set<Int>, items: List<Entry>, temp: String) {
    val white = ColorProvider(Color.White)
    val open = actionStartActivity(Intent(LocalContext.current, MainActivity::class.java))
    val selDay = sel.toEpochDay()
    Column(
        GlanceModifier.fillMaxSize()
            .background(ColorProvider(Color(0xE61E1E2E)))
            .cornerRadius(16.dp)
            .padding(8.dp)
            .clickable(open)
    ) {
        Row(GlanceModifier.fillMaxWidth().padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${ym.year}년 ${ym.monthValue}월", GlanceModifier.defaultWeight(),
                style = TextStyle(color = white, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            )
            Text(temp, style = TextStyle(color = white, fontSize = 13.sp))
        }
        Row(GlanceModifier.fillMaxWidth()) {
            listOf("일", "월", "화", "수", "목", "금", "토").forEach {
                Box(GlanceModifier.defaultWeight().height(18.dp), contentAlignment = Alignment.Center) {
                    Text(it, style = TextStyle(color = ColorProvider(Color(0xB3FFFFFF)), fontSize = 11.sp))
                }
            }
        }
        val cells = List(ym.atDay(1).dayOfWeek.value % 7) { 0 } + (1..ym.lengthOfMonth())
        cells.chunked(7).forEach { week ->
            Row(GlanceModifier.fillMaxWidth()) {
                for (i in 0 until 7) {
                    val d = week.getOrElse(i) { 0 }
                    DayCell(if (d == 0) null else ym.atDay(d), today, sel, d in eventDays, d in todoDays)
                }
            }
        }
        Text(
            "${sel.monthValue}월 ${sel.dayOfMonth}일 (${sel.dayOfWeek.getDisplayName(DayStyle.SHORT, Locale.KOREAN)})" +
                if (items.isEmpty()) " · 없음" else "",
            GlanceModifier.fillMaxWidth().padding(top = 6.dp),
            style = TextStyle(color = white, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        )
        items.take(MONTH_ROWS).forEach {
            val done = it.isDone(selDay)
            EntryRow(
                if (it.isEvent) "◆" else if (done) "☑" else "☐", body(it), white, 12.sp,
                if (it.isEvent) null else toggle(it, selDay, !done), open, strike = done
            )
        }
        if (items.size > MONTH_ROWS) Text("… 외 ${items.size - MONTH_ROWS}개", style = TextStyle(color = white, fontSize = 11.sp))
    }
}

private const val MONTH_ROWS = 3

/** 날짜 칸: 선택한 날은 배경색, 오늘은 보라색 글자, 일정은 주황 점 / 할 일은 하늘색 점 */
@Composable
private fun RowScope.DayCell(d: LocalDate?, today: LocalDate, sel: LocalDate, hasEvent: Boolean, hasTodo: Boolean) {
    val cell = GlanceModifier.defaultWeight().height(32.dp).padding(1.dp)
    if (d == null) { Box(cell) {}; return }
    val isSel = d == sel
    val isToday = d == today
    val color = when {
        isSel -> Color.White
        isToday -> Color(0xFFB39DFF)
        d.dayOfWeek == DayOfWeek.SUNDAY -> Color(0xFFFF8A80)
        d.dayOfWeek == DayOfWeek.SATURDAY -> Color(0xFF82B1FF)
        else -> Color.White
    }
    val select = actionRunCallback(SelectDayAction::class.java, actionParametersOf(KEY_DAY to d.toEpochDay()))
    val inner = GlanceModifier.fillMaxSize()
    Box(cell.clickable(select), contentAlignment = Alignment.Center) {
        Column(
            if (isSel) inner.background(ColorProvider(Color(0xFF6750A4))).cornerRadius(8.dp) else inner,
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                d.dayOfMonth.toString(),
                style = TextStyle(color = ColorProvider(color), fontSize = 12.sp, fontWeight = if (isToday || isSel) FontWeight.Bold else FontWeight.Normal)
            )
            Row {
                if (hasEvent) Text("●", style = TextStyle(color = ColorProvider(EVENT_DOT), fontSize = 7.sp))
                if (hasTodo) Text("●", style = TextStyle(color = ColorProvider(TODO_DOT), fontSize = 7.sp))
                // 점이 없는 날도 같은 높이를 차지하도록 투명 점 하나
                if (!hasEvent && !hasTodo) Text("●", style = TextStyle(color = ColorProvider(Color.Transparent), fontSize = 7.sp))
            }
        }
    }
}

class MonthWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = MonthWidget()
}
