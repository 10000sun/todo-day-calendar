package com.todocalendar

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * 홈 화면 위젯 2종(오늘 요약 / 월간 달력). 안드로이드 표준 RemoteViews로 직접 그려서
 * AppWidgetManager에 바로 전달한다 (갱신 경로가 단순하고 즉시 반영됨).
 * 터치는 모두 PendingIntent: ☐ 는 완료(ToggleReceiver), 날짜는 선택(SelectDayReceiver), 그 외는 앱 열기.
 */

/** 위젯 공급자: 시스템이 갱신을 요청하거나 크기가 바뀌면 둘 다 다시 그린다 */
open class BaseWidgetProvider : AppWidgetProvider() {
    private fun redraw(ctx: Context) {
        val p = goAsync()
        CoroutineScope(Dispatchers.Default).launch { try { Widgets.updateAll(ctx.applicationContext) } finally { p.finish() } }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) = redraw(context)
    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: android.os.Bundle) = redraw(context)
}

class TodayWidgetReceiver : BaseWidgetProvider()
class MonthWidgetReceiver : BaseWidgetProvider()

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
                // 다른 갱신(잠금)을 기다리지 않고 바로 그린다: 날짜를 누르면 즉시 바뀌어야 한다
                Widgets.updateMonth(c.applicationContext)
            } finally { p.finish() }
        }
    }
}

object Widgets {
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val ORANGE = 0xFFFFB74D.toInt()
    private const val RED = 0xFFFF8A80.toInt()
    private const val BLUE = 0xFF82B1FF.toInt()
    private const val PURPLE = 0xFFB39DFF.toInt()
    private const val EVENT_DOT = 0xFFFFB74D.toInt()
    private const val TODO_DOT = 0xFF4FC3F7.toInt()
    private const val ROW_H = 30f   // 목록 한 줄 높이(dp) 추정치 (체크 표시를 키운 만큼 반영)
    /** 체크 표시(☐/☑/◆)를 글자보다 이만큼(sp) 더 크게 */
    private const val MARK_EXTRA = 5f

    private fun mgr(ctx: Context) = AppWidgetManager.getInstance(ctx)
    private fun ids(ctx: Context, cls: Class<*>) = mgr(ctx).getAppWidgetIds(ComponentName(ctx, cls))

    /** 위젯의 현재 높이(dp): 세로 모드는 최대, 가로 모드는 최소 높이 */
    private fun heightDp(ctx: Context, id: Int): Int {
        val o = mgr(ctx).getAppWidgetOptions(id)
        val land = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val h = o.getInt(if (land) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        return if (h > 0) h else 250
    }

    suspend fun updateAll(ctx: Context) {
        updateToday(ctx)
        updateMonth(ctx)
    }

    suspend fun updateToday(ctx: Context) {
        val ids = ids(ctx, TodayWidgetReceiver::class.java)
        if (ids.isEmpty()) return
        val s = Summary.load(ctx)
        ids.forEach { id -> push(ctx, id) { today(ctx, s, heightDp(ctx, id)) } }
    }

    suspend fun updateMonth(ctx: Context) {
        val ids = ids(ctx, MonthWidgetReceiver::class.java)
        if (ids.isEmpty()) return
        val m = MonthData.load(ctx)
        ids.forEach { id -> push(ctx, id) { month(ctx, m, heightDp(ctx, id)) } }
    }

    /** 한 위젯을 그리다 실패해도 다른 위젯/다음 갱신에는 영향이 없도록 */
    private fun push(ctx: Context, id: Int, build: () -> RemoteViews) {
        try {
            mgr(ctx).updateAppWidget(id, build())
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            android.util.Log.w("Widgets", "widget $id update failed", e)
        }
    }

    // ───────── PendingIntent 도우미 ─────────

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun toggle(ctx: Context, e: Entry, day: Long, done: Boolean = true): PendingIntent = PendingIntent.getBroadcast(
        ctx, (e.id * 31 + day).toInt() + if (done) 0 else 1, toggleIntent(ctx, e, day, done),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun select(ctx: Context, day: Long): PendingIntent = PendingIntent.getBroadcast(
        ctx, day.toInt(),
        Intent(ctx, SelectDayReceiver::class.java).setData(Uri.parse("todo://select/$day")).putExtra("day", day),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    // ───────── 공통 줄 만들기 ─────────

    private fun addHead(ctx: Context, parent: RemoteViews, container: Int, text: String) {
        val r = RemoteViews(ctx.packageName, R.layout.widget_head)
        r.setTextViewText(R.id.w_text, text)
        parent.addView(container, r)
    }

    /** 한 줄: mark(☐/☑/◆)만 누르면 toggle, 나머지 영역은 앱 열기 */
    private fun addRow(
        ctx: Context, parent: RemoteViews, container: Int, mark: String, text: CharSequence,
        color: Int, sizeSp: Float, toggle: PendingIntent?, open: PendingIntent, keepHeight: Boolean = false
    ) {
        val r = RemoteViews(ctx.packageName, R.layout.widget_row)
        if (mark.isEmpty()) {
            // keepHeight: 표시는 숨기되 자리(높이)는 유지 -> 줄 높이가 항상 같다
            if (keepHeight) { r.setTextViewText(R.id.w_mark, "☐"); r.setTextViewTextSize(R.id.w_mark, TypedValue.COMPLEX_UNIT_SP, sizeSp + MARK_EXTRA); r.setViewVisibility(R.id.w_mark, View.INVISIBLE) }
            else r.setViewVisibility(R.id.w_mark, View.GONE)
        }
        else {
            r.setTextViewText(R.id.w_mark, mark)
            r.setTextColor(R.id.w_mark, color)
            r.setTextViewTextSize(R.id.w_mark, TypedValue.COMPLEX_UNIT_SP, sizeSp + MARK_EXTRA)
        }
        r.setTextViewText(R.id.w_text, text)
        r.setTextColor(R.id.w_text, color)
        r.setTextViewTextSize(R.id.w_text, TypedValue.COMPLEX_UNIT_SP, sizeSp)
        r.setOnClickPendingIntent(R.id.w_row, open)
        if (toggle != null) r.setOnClickPendingIntent(R.id.w_mark, toggle)
        parent.addView(container, r)
    }

    private fun body(e: Entry) = (if (e.timeMin >= 0) e.timeText() + " " else "") + e.title

    private fun struck(text: String): CharSequence =
        SpannableString(text).apply { setSpan(StrikethroughSpan(), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }

    // ───────── 오늘 위젯: 날짜-기온 / 할 일 / D-day ─────────

    private fun today(ctx: Context, s: Summary, heightDp: Int): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_today)
        val open = openApp(ctx)
        v.setOnClickPendingIntent(R.id.w_root, open)
        v.setTextViewText(R.id.w_title, s.title)
        v.setOnClickPendingIntent(R.id.w_title, open)

        // 높이에 맞춰 보여줄 줄 수: 패딩 24 + 제목 24 + 소제목 2개 44
        val capacity = ((heightDp - 92f) / ROW_H).toInt().coerceAtLeast(2)
        val ddReserve = if (s.dd.isEmpty()) 0 else minOf(s.dd.size, maxOf(1, capacity / 3))
        var todoRows = minOf(s.items.size, capacity - ddReserve)
        var ddRows = minOf(s.dd.size, capacity - todoRows)   // 할 일이 적으면 남는 자리는 D-day가 사용
        val todoMore = s.items.size > todoRows
        val ddMore = s.dd.size > ddRows
        if (todoMore) todoRows = maxOf(0, todoRows - 1)   // "… 외 N개" 줄 자리
        if (ddMore) ddRows = maxOf(0, ddRows - 1)

        // 갱신은 기존 화면 위에 덧붙여지므로, 줄을 추가하기 전에 이전 줄을 먼저 비운다 (안 그러면 갱신할 때마다 쌓임)
        v.removeAllViews(R.id.w_rows)
        addHead(ctx, v, R.id.w_rows, if (s.items.isEmpty()) "할 일 없음" else "할 일")
        s.items.take(todoRows).forEach { e ->
            addRow(ctx, v, R.id.w_rows, if (e.isEvent) "◆" else "☐", body(e), WHITE, 13f, if (e.isEvent) null else toggle(ctx, e, s.day), open)
        }
        if (todoMore) addRow(ctx, v, R.id.w_rows, "", "… 외 ${s.items.size - todoRows}개", WHITE, 12f, null, open)
        addHead(ctx, v, R.id.w_rows, if (s.dd.isEmpty()) "D-day 없음" else "D-day")
        s.dd.take(ddRows).forEach { (e, d) ->
            addRow(ctx, v, R.id.w_rows, if (e.isEvent) "" else "☐", ddayLabel(e, s.today), ORANGE, 13f, if (e.isEvent) null else toggle(ctx, e, d), open)
        }
        if (ddMore) addRow(ctx, v, R.id.w_rows, "", "… 외 ${s.dd.size - ddRows}개", ORANGE, 12f, null, open)
        return v
    }

    // ───────── 월간 달력 위젯 ─────────

    /** 월간 위젯이 그릴 데이터 */
    class MonthData(
        val ym: YearMonth, val today: LocalDate, val sel: LocalDate,
        val eventDays: Set<Int>, val todoDays: Set<Int>, val items: List<Entry>,
        val temp: String, val holidayDays: Set<Int>, val selHoliday: String?
    ) {
        companion object {
            suspend fun load(ctx: Context): MonthData {
                val today = LocalDate.now()
                val ym = YearMonth.from(today)
                val sel = selectedDay(ctx, today)
                var eventDays = emptySet<Int>()
                var todoDays = emptySet<Int>()
                var items = emptyList<Entry>()
                try {
                    val dao = Db.get(ctx).dao()
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
                val holidayDays = (1..ym.lengthOfMonth()).filter { Holidays.isHoliday(ym.atDay(it)) }.toSet()
                return MonthData(ym, today, sel, eventDays, todoDays, items, Weather.cachedLabel(ctx), holidayDays, Holidays.name(sel))
            }
        }
    }

    private fun month(ctx: Context, m: MonthData, heightDp: Int): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_month)
        val open = openApp(ctx)
        v.setOnClickPendingIntent(R.id.w_root, open)
        v.setTextViewText(R.id.w_title, "${m.ym.year}년 ${m.ym.monthValue}월")
        v.setTextViewText(R.id.w_temp, m.temp)

        val cells = List(m.ym.atDay(1).dayOfWeek.value % 7) { 0 } + (1..m.ym.lengthOfMonth())
        val weeks = cells.chunked(7)
        v.removeAllViews(R.id.w_grid)   // 이전에 그린 주(週) 줄을 비우고 새로 그린다
        weeks.forEach { week ->
            val row = RemoteViews(ctx.packageName, R.layout.widget_week)
            for (i in 0 until 7) row.addView(R.id.w_week, dayCell(ctx, m, week.getOrElse(i) { 0 }))
            v.addView(R.id.w_grid, row)
        }

        // 아래 구역: 달력 칸이 최소 28dp는 되도록 남는 높이만큼의 줄 수로 고정 (패딩16 + 제목22 + 요일16 / 머리글22 + 구역 여백10)
        val listRows = (((heightDp - 54f - 32f - weeks.size * 28f) / ROW_H).toInt()).coerceIn(0, 3)
        v.removeAllViews(R.id.w_list)
        if (listRows == 0) {
            v.setViewVisibility(R.id.w_list, View.GONE)
        } else {
            v.setViewVisibility(R.id.w_list, View.VISIBLE)
            val selDay = m.sel.toEpochDay()
            val dow = m.sel.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)
            addHead(
                ctx, v, R.id.w_list,
                "${m.sel.monthValue}월 ${m.sel.dayOfMonth}일 ($dow)" + (m.selHoliday?.let { " · $it" } ?: "") + if (m.items.isEmpty()) " · 없음" else ""
            )
            val more = m.items.size > listRows
            val shown = m.items.take(if (more) listRows - 1 else listRows)
            shown.forEach { e ->
                val done = e.isDone(selDay)
                addRow(
                    ctx, v, R.id.w_list, if (e.isEvent) "◆" else if (done) "☑" else "☐",
                    if (done) struck(body(e)) else body(e), WHITE, 12f,
                    if (e.isEvent) null else toggle(ctx, e, selDay, !done), open, keepHeight = true
                )
            }
            if (more) addRow(ctx, v, R.id.w_list, "", "… 외 ${m.items.size - (listRows - 1)}개", WHITE, 12f, null, open, keepHeight = true)
            // 남는 줄은 빈 줄로 채워서, 항목이 몇 개든 아래 구역(과 위의 달력) 높이가 변하지 않게 한다
            repeat(maxOf(0, listRows - shown.size - if (more) 1 else 0)) {
                addRow(ctx, v, R.id.w_list, "", " ", WHITE, 12f, null, open, keepHeight = true)
            }
        }
        return v
    }

    /** 날짜 칸: 선택한 날은 배경색, 오늘은 보라색 글자, 공휴일은 빨강, 일정은 주황 점 / 할 일은 하늘색 점 */
    private fun dayCell(ctx: Context, m: MonthData, day: Int): RemoteViews {
        val c = RemoteViews(ctx.packageName, R.layout.widget_day)
        if (day == 0) { c.setViewVisibility(R.id.w_dots, View.INVISIBLE); c.setTextViewText(R.id.w_num, ""); return c }
        val d = m.ym.atDay(day)
        val isSel = d == m.sel
        val isToday = d == m.today
        val color = when {
            isSel -> WHITE
            isToday -> PURPLE
            day in m.holidayDays || d.dayOfWeek == DayOfWeek.SUNDAY -> RED
            d.dayOfWeek == DayOfWeek.SATURDAY -> BLUE
            else -> WHITE
        }
        val num = SpannableString(day.toString())
        if (isToday || isSel) num.setSpan(StyleSpan(Typeface.BOLD), 0, num.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        c.setTextViewText(R.id.w_num, num)
        c.setTextColor(R.id.w_num, color)

        // 일정이 있으면 주황 점, 할 일이 있으면 하늘색 점. 없는 날도 같은 높이를 차지하도록 투명 점
        val dots = SpannableStringBuilder()
        fun dot(col: Int) { val s = dots.length; dots.append("●"); dots.setSpan(ForegroundColorSpan(col), s, dots.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
        if (day in m.eventDays) dot(EVENT_DOT)
        if (day in m.todoDays) dot(TODO_DOT)
        if (dots.isEmpty()) dot(0x00000000)
        c.setTextViewText(R.id.w_dots, dots)

        if (isSel) c.setInt(R.id.w_cell, "setBackgroundResource", R.drawable.widget_sel_bg)
        c.setOnClickPendingIntent(R.id.w_cell, select(ctx, d.toEpochDay()))
        return c
    }
}
