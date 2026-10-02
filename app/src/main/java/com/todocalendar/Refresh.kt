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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** 위젯 + 잠금화면 알림을 한 번에 갱신 */
object Refresh {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** 갱신이 겹치면 오래된 요약이 최신 알림을 덮어쓰거나 알림이 중복 발송되므로 한 번에 하나씩 */
    private val lock = Mutex()
    fun fire(ctx: Context) { scope.launch { all(ctx) } }

    /** 화면(컴포지션)이 사라져도 취소되지 않도록 앱 범위에서 실행 */
    fun launch(block: suspend () -> Unit) { scope.launch { block() } }

    /** 위젯 한 개 갱신이 오래 걸려도(최대 10초) 다른 작업이 영원히 막히지 않도록 */
    private const val WIDGET_TIMEOUT = 10_000L
    /** 위젯 갱신끼리는 한 번에 하나씩 */
    private val widgetLock = Mutex()

    suspend fun updateMonthWidget(ctx: Context) = widgetLock.withLock {
        step { withTimeoutOrNull(WIDGET_TIMEOUT) { MonthWidget().updateAll(ctx) } }
    }

    private suspend fun updateWidgets(ctx: Context) = widgetLock.withLock {
        step { withTimeoutOrNull(WIDGET_TIMEOUT) { TodayWidget().updateAll(ctx) } }
        step { withTimeoutOrNull(WIDGET_TIMEOUT) { MonthWidget().updateAll(ctx) } }
    }

    /**
     * userEdit=true: 사용자가 데이터를 바꾼 경우 — 끝나면 자동 백업 파일도 갱신.
     * 알림/알람/백업과 위젯 갱신은 서로 기다리지 않고 동시에 진행한다 (한쪽이 느려도 다른 쪽이 막히지 않음).
     */
    suspend fun all(ctx: Context, userEdit: Boolean = false) {
        coroutineScope {
            launch {
                lock.withLock {
                    step { Reminders.run(ctx) }
                    step { LockNotifier.post(ctx) }
                    if (userEdit) step { Backup.autoWrite(ctx) }
                }
            }
            launch { updateWidgets(ctx) }
        }
        refreshWeather(ctx)
    }

    /** 기온/공휴일 조회는 따로: 느려도 다른 갱신을 막지 않고, 새 값이 있을 때만 화면을 한 번 더 그린다 */
    private fun refreshWeather(ctx: Context) {
        scope.launch {
            val w = Weather.refreshIfStale(ctx)
            val h = Holidays.refreshIfStale(ctx)
            if (w || h) coroutineScope {
                launch { lock.withLock { step { LockNotifier.post(ctx) } } }
                launch { updateWidgets(ctx) }
            }
        }
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
        val sum = Summary.load(ctx)
        val t = sum.today
        val day = sum.day
        val items = sum.items
        val dd = sum.dd.take(MAX_DDAY)
        val title = sum.title

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
            .setShowWhen(false)
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
                Db.get(c).setDone(id, day, i.getBooleanExtra("done", true))
                Refresh.all(c, userEdit = true)
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
        Holidays.init(this)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "refresh", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES).build()
        )
    }
}
