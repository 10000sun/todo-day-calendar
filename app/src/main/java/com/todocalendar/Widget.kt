package com.todocalendar

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
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
        Refresh.all(context, fire = false)
    }
}

/** 홈 화면 위젯: 알림창과 같은 구성 (날짜 - 기온 / 할 일 / D-day), 할 일은 눌러서 완료 */
class TodayWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val s = Summary.load(context)
        provideContent { Body(s) }
    }
}

@Composable
private fun Body(s: Summary) {
    val white = ColorProvider(Color.White)
    val orange = ColorProvider(Color(0xFFFFB74D))
    val openApp = actionStartActivity(Intent(LocalContext.current, MainActivity::class.java))
    val headStyle = TextStyle(color = white, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    val body = TextStyle(color = white, fontSize = 13.sp)
    val row = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp)
    Column(
        GlanceModifier.fillMaxSize()
            .background(ColorProvider(Color(0xE61E1E2E)))
            .cornerRadius(16.dp)
            .padding(12.dp)
            .clickable(openApp)
    ) {
        Text(s.title, style = TextStyle(color = white, fontSize = 16.sp, fontWeight = FontWeight.Bold))
        LazyColumn(GlanceModifier.fillMaxSize()) {
            item { Text(if (s.items.isEmpty()) "할 일 없음" else "할 일", GlanceModifier.padding(top = 6.dp), style = headStyle) }
            items(s.items) { e ->
                Text(line(e, s.day), if (e.isEvent) row else row.clickable(toggle(e, s.day)), style = body)
            }
            item { Text(if (s.dd.isEmpty()) "D-day 없음" else "D-day", GlanceModifier.padding(top = 6.dp), style = headStyle) }
            items(s.dd) { (e, d) ->
                Text(
                    (if (e.isEvent) "" else "☐ ") + ddayLabel(e, s.today),
                    if (e.isEvent) row else row.clickable(toggle(e, d)),
                    style = TextStyle(color = orange, fontSize = 13.sp)
                )
            }
        }
    }
}

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
            val m = if (it.isEvent) GlanceModifier else GlanceModifier.clickable(toggle(it, today.toEpochDay()))
            Text(line(it, today.toEpochDay()), m, style = TextStyle(color = ColorProvider(white), fontSize = 12.sp))
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
