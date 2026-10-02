package com.todocalendar

import android.app.*
import android.content.*
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

    suspend fun all(ctx: Context) {
        TodayWidget().updateAll(ctx)
        LockNotifier.post(ctx)
    }
}

/** 잠금화면에 고정되는 오늘 할 일 알림 (VISIBILITY_PUBLIC + ongoing) */
object LockNotifier {
    private const val CH = "today"
    private const val ID = 1

    suspend fun post(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(
            NotificationChannel(CH, "오늘 할 일", NotificationManager.IMPORTANCE_LOW).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
            }
        )
        val dao = Db.get(ctx).dao()
        val t = LocalDate.now()
        val items = dao.on(t.toEpochDay())
        val dd = upcoming(dao.ddayList(), t).take(2)
        val todos = items.filter { !it.isEvent }
        val style = NotificationCompat.InboxStyle()
        (dd.map { ddayLabel(it, t) } + items.map(::line)).take(7).forEach { style.addLine(it) }
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle("${t.monthValue}/${t.dayOfMonth} · 할 일 ${todos.count { it.done }}/${todos.size}")
            .setContentText(dd.firstOrNull()?.let { ddayLabel(it, t) } ?: items.firstOrNull()?.let(::line) ?: "오늘 일정 없음")
            .setStyle(style)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .build()
        nm.notify(ID, n)
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
