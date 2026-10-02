package com.todocalendar

import android.app.*
import android.content.*
import android.net.Uri
import android.widget.RemoteViews
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.glance.appwidget.updateAll
import androidx.work.*
import kotlinx.coroutines.*
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** 위젯 + 잠금화면 알림을 한 번에 갱신 */
object Refresh {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    fun fire(ctx: Context) { scope.launch { all(ctx) } }

    /** 화면(컴포지션)이 사라져도 취소되지 않도록 앱 범위에서 실행 */
    fun launch(block: suspend () -> Unit) { scope.launch { block() } }

    /** fire=false: 사용자가 방금 직접 수정한 경우 — 이미 지난 알림 시각은 울리지 않고 건너뜀 */
    suspend fun all(ctx: Context, fire: Boolean = true) {
        step { TodayWidget().updateAll(ctx) }
        step { MonthWidget().updateAll(ctx) }
        step { Reminders.run(ctx, fire) }
        step { LockNotifier.post(ctx) }
    }

    /** 한 단계가 실패해도 나머지(특히 알람 재예약)는 계속 진행 */
    private suspend fun step(b: suspend () -> Unit) {
        try { b() } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w("Refresh", e) }
    }
}

/** 잠금화면/알림창에 고정되는 오늘 요약 (VISIBILITY_PUBLIC + ongoing). 할 일은 줄을 눌러 바로 완료 */
object LockNotifier {
    private const val CH = "today"
    private const val ID = 1
    private const val MAX_TODO = 8
    private const val MAX_DDAY = 5

    suspend fun post(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(
            NotificationChannel(CH, "오늘 요약", NotificationManager.IMPORTANCE_LOW).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
            }
        )
        val dao = Db.get(ctx).dao()
        val t = LocalDate.now()
        val day = t.toEpochDay()
        // 완료한 할 일은 알림에서 사라진다
        val items = dao.onDay(day).filter { it.isEvent || !it.isDone(day) }
        val dd = upcomingDates(dao.ddayList(), t).take(MAX_DDAY)
        val title = "${t.monthValue}/${t.dayOfMonth}" + (Weather.tempText(ctx)?.let { " - $it" } ?: "")

        val big = RemoteViews(ctx.packageName, R.layout.notif_big)
        big.setTextViewText(R.id.title, title)
        fun add(layout: Int, text: String, click: PendingIntent? = null) {
            val v = RemoteViews(ctx.packageName, layout)
            v.setTextViewText(R.id.t, text)
            if (click != null) v.setOnClickPendingIntent(R.id.t, click)
            big.addView(R.id.rows, v)
        }
        add(R.layout.notif_head, if (items.isEmpty()) "할 일 없음" else "할 일")
        items.take(MAX_TODO).forEach { add(R.layout.notif_row, line(it, day), if (it.isEvent) null else toggle(ctx, it, day)) }
        if (items.size > MAX_TODO) add(R.layout.notif_row, "… 외 ${items.size - MAX_TODO}개")
        add(R.layout.notif_head, if (dd.isEmpty()) "D-day 없음" else "D-day")
        // D-day로 지정한 할 일도 줄을 눌러 완료 (해당 D-day 날짜 기준)
        dd.forEach { (e, d) ->
            add(R.layout.notif_row, (if (e.isEvent) "" else "☐ ") + ddayLabel(e, t), if (e.isEvent) null else toggle(ctx, e, d))
        }

        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val todoCount = items.count { !it.isEvent }
        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle(title)
            .setContentText("할 일 ${if (todoCount == 0) "없음" else "${todoCount}개"}" + (dd.firstOrNull()?.let { " · " + ddayLabel(it.first, t) } ?: ""))
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomBigContentView(big)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .build()
        nm.notify(ID, n)
    }

    private fun toggle(ctx: Context, e: Entry, day: Long) = PendingIntent.getBroadcast(
        ctx, e.id.toInt(),
        Intent(ctx, ToggleReceiver::class.java)
            .setData(Uri.parse("todo://${e.id}/$day"))
            .putExtra("id", e.id).putExtra("day", day),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}

/** 알림에서 할 일을 눌렀을 때 완료 처리 */
class ToggleReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getLongExtra("id", 0L)
        val day = i.getLongExtra("day", 0L)
        val p = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                Db.get(c).setDone(id, day, true)
                Refresh.all(c, fire = false)
            } finally { p.finish() }
        }
    }
}

/** 날짜가 바뀌어도 위젯/알림이 최신이 되도록 15분마다 갱신 */
class RefreshWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result { Refresh.all(applicationContext); return Result.success() }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val p = goAsync()
        CoroutineScope(Dispatchers.Default).launch { try { Refresh.all(c) } finally { p.finish() } }
    }
}

class TodoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "refresh", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES).build()
        )
    }
}
