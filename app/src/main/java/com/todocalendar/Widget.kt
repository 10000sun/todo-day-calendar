package com.todocalendar

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import java.time.LocalDate
import java.time.format.TextStyle as DayStyle
import java.util.Locale

/** 홈 화면 위젯: 오늘 날짜 + D-day + 오늘 할 일 */
class TodayWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val dao = Db.get(context).dao()
        val today = LocalDate.now()
        val items = dao.on(today.toEpochDay())
        val dd = upcoming(dao.ddayList(), today).take(2)
        provideContent { Body(today, dd, items) }
    }
}

@Composable
private fun Body(today: LocalDate, dd: List<Entry>, items: List<Entry>) {
    val white = ColorProvider(Color.White)
    Column(
        GlanceModifier.fillMaxSize()
            .background(ColorProvider(Color(0xE61E1E2E)))
            .cornerRadius(16.dp)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>())
    ) {
        Text(
            "${today.monthValue}월 ${today.dayOfMonth}일 (${today.dayOfWeek.getDisplayName(DayStyle.SHORT, Locale.KOREAN)})",
            style = TextStyle(color = white, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        )
        dd.forEach { Text(ddayLabel(it, today), style = TextStyle(color = ColorProvider(Color(0xFFFFB74D)), fontSize = 13.sp)) }
        if (items.isEmpty()) Text("오늘은 비어 있어요", style = TextStyle(color = white, fontSize = 13.sp))
        items.take(7).forEach { Text(line(it), style = TextStyle(color = white, fontSize = 13.sp)) }
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = TodayWidget()
}
