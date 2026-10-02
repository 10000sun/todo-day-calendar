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
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle as DayStyle
import java.util.Locale

private val KEY_ID = ActionParameters.Key<Long>("id")
private val KEY_DAY = ActionParameters.Key<Long>("day")

private fun toggle(e: Entry, day: Long) =
    actionRunCallback(ToggleAction::class.java, actionParametersOf(KEY_ID to e.id, KEY_DAY to day))

/** 위젯에서 할 일을 눌렀을 때 완료 처리 후 위젯/알림 갱신 */
class ToggleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[KEY_ID] ?: return
        val day = parameters[KEY_DAY] ?: return
        Db.get(context).setDone(id, day, true)
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
private fun EntryRow(mark: String, text: String, color: ColorProvider, size: TextUnit, toggle: Action?, open: Action) {
    Row(GlanceModifier.fillMaxWidth().clickable(open), verticalAlignment = Alignment.CenterVertically) {
        if (mark.isNotEmpty()) {
            val m = GlanceModifier.padding(end = 10.dp, top = 4.dp, bottom = 4.dp)
            Text(mark, if (toggle != null) m.clickable(toggle) else m, style = TextStyle(color = color, fontSize = (size.value + 3).sp))
        }
        Text(text, GlanceModifier.defaultWeight().padding(vertical = 4.dp), style = TextStyle(color = color, fontSize = size))
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

/** 홈 화면 월간 달력 위젯 (바탕화면 달력 느낌) */
class MonthWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val today = LocalDate.now()
        val ym = YearMonth.from(today)
        var marked = emptySet<Int>()
        var items = emptyList<Entry>()
        var dd = emptyList<Entry>()
        try {
            val dao = Db.get(context).dao()
            val entries = dao.visibleNow(ym.atDay(1).toEpochDay(), ym.atEndOfMonth().toEpochDay())
            marked = (1..ym.lengthOfMonth()).filter { d -> entries.any { it.occursOn(ym.atDay(d).toEpochDay()) } }.toSet()
            val day = today.toEpochDay()
            items = dao.onDay(day).filter { it.isEvent || !it.isDone(day) }
            dd = upcoming(dao.ddayList(), today).take(1)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 데이터를 못 읽어도 달력 틀은 보여준다
        }
        provideContent { MonthBody(ym, today, marked, dd, items) }
    }
}

@Composable
private fun MonthBody(ym: YearMonth, today: LocalDate, marked: Set<Int>, dd: List<Entry>, items: List<Entry>) {
    val white = Color.White
    val openApp = actionStartActivity(Intent(LocalContext.current, MainActivity::class.java))
    Column(
        GlanceModifier.fillMaxSize()
            .background(ColorProvider(Color(0xE61E1E2E)))
            .cornerRadius(16.dp)
            .padding(8.dp)
            .clickable(openApp)
    ) {
        Text(
            "${ym.year}년 ${ym.monthValue}월",
            style = TextStyle(color = ColorProvider(white), fontSize = 15.sp, fontWeight = FontWeight.Bold)
        )
        Row(GlanceModifier.fillMaxWidth()) {
            listOf("일", "월", "화", "수", "목", "금", "토").forEach { Cell(it, Color(0xB3FFFFFF)) }
        }
        val cells = List(ym.atDay(1).dayOfWeek.value % 7) { 0 } + (1..ym.lengthOfMonth())
        cells.chunked(7).forEach { week ->
            Row(GlanceModifier.fillMaxWidth()) {
                for (i in 0 until 7) {
                    val d = week.getOrElse(i) { 0 }
                    if (d == 0) { Cell("", white); continue }
                    val dow = ym.atDay(d).dayOfWeek
                    val isToday = ym.atDay(d) == today
                    Cell(
                        d.toString(),
                        color = when {
                            isToday -> white
                            d in marked -> Color(0xFFFFB74D)
                            dow == DayOfWeek.SUNDAY -> Color(0xFFFF8A80)
                            dow == DayOfWeek.SATURDAY -> Color(0xFF82B1FF)
                            else -> white
                        },
                        bg = if (isToday) Color(0xFF6750A4) else null,
                        bold = d in marked || isToday
                    )
                }
            }
        }
        dd.forEach { Text(ddayLabel(it, today), style = TextStyle(color = ColorProvider(Color(0xFFFFB74D)), fontSize = 12.sp)) }
        items.take(2).forEach {
            EntryRow(if (it.isEvent) "◆" else "☐", body(it), ColorProvider(white), 12.sp, if (it.isEvent) null else toggle(it, today.toEpochDay()), openApp)
        }
    }
}

@Composable
private fun RowScope.Cell(text: String, color: Color, bg: Color? = null, bold: Boolean = false) {
    Box(GlanceModifier.defaultWeight().height(20.dp), contentAlignment = Alignment.Center) {
        val m = GlanceModifier.size(18.dp)
        Box(if (bg != null) m.background(ColorProvider(bg)).cornerRadius(9.dp) else m, contentAlignment = Alignment.Center) {
            Text(
                text,
                style = TextStyle(color = ColorProvider(color), fontSize = 11.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
            )
        }
    }
}

class MonthWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = MonthWidget()
}
