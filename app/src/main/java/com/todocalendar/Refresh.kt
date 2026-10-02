package com.todocalendar

import android.app.*
import android.content.*
import android.net.Uri
import android.text.Spannable
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.widget.RemoteViews
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** 위젯 + 잠금화면 알림을 한 번에 갱신 */
object Refresh {
    /** 처리되지 않은 예외로 앱이 죽지 않도록 기록만 한다 (백그라운드 갱신/백업 실패가 앱 종료로 이어지지 않게) */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.w("Refresh", e) })
    /** 갱신이 겹치면 오래된 요약이 최신 알림을 덮어쓰거나 알림이 중복 발송되므로 한 번에 하나씩 */
    private val lock = Mutex()
    fun fire(ctx: Context) { scope.launch { all(ctx) } }

    /**
     * 데이터가 바뀌는 순간마다 위젯을 다시 그린다. 앱 화면/알림/위젯/복원 등 어떤 경로로 바뀌어도 위젯이 낡은 채로 남지 않도록
     * 개별 호출에 의존하지 않고 DB 변경 자체를 감시한다. (연속 변경은 마지막 것만 반영)
     */
    fun watchData(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch {
            Db.get(app).dao().observeAll().collectLatest {
                delay(100)
                widgetLock.withLock { step { withTimeoutOrNull(WIDGET_TIMEOUT) { Widgets.updateAll(app) } } }
            }
        }
    }

    /** 화면(컴포지션)이 사라져도 취소되지 않도록 앱 범위에서 실행 */
    fun launch(block: suspend () -> Unit) { scope.launch { block() } }

    /** 위젯 갱신이 오래 걸려도(최대 8초) 다른 작업이 영원히 막히지 않도록 */
    private const val WIDGET_TIMEOUT = 8_000L
    /** 위젯 갱신끼리는 한 번에 하나씩 */
    private val widgetLock = Mutex()

    private suspend fun updateWidgets(ctx: Context) = widgetLock.withLock {
        step { withTimeoutOrNull(WIDGET_TIMEOUT) { Widgets.updateAll(ctx) } }
    }

    /**
     * userEdit=true: 사용자가 데이터를 바꾼 경우 — 끝나면 자동 백업 파일도 갱신.
     * 알림/알람/백업과 위젯 갱신은 서로 기다리지 않고 동시에 진행한다 (한쪽이 느려도 다른 쪽이 막히지 않음).
     * awaitNetwork=true: 기온/공휴일 조회가 끝날 때까지 기다린다 (백그라운드 워커처럼 끝난 뒤 프로세스가 멈출 수 있는 곳에서 사용)
     */
    suspend fun all(ctx: Context, userEdit: Boolean = false, awaitNetwork: Boolean = false) {
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
        val net = refreshWeather(ctx)
        if (awaitNetwork) net.join()
    }

    /** 기온/공휴일 조회는 따로: 느려도 다른 갱신을 막지 않고, 새 값이 있을 때만 화면을 한 번 더 그린다 */
    private fun refreshWeather(ctx: Context): Job {
        return scope.launch {
            // 두 조회는 서로 무관하므로 동시에 (한쪽 네트워크가 느려도 다른 쪽이 기다리지 않도록)
            val (w, h) = coroutineScope {
                val dw = async { Weather.refreshIfStale(ctx) }
                val dh = async { Holidays.refreshIfStale(ctx) }
                dw.await() to dh.await()
            }
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
        big.removeAllViews(R.id.rows)   // 알림 갱신도 기존 화면 위에 덧붙여질 수 있으므로 먼저 비운다
        fun add(layout: Int, text: String, click: PendingIntent? = null) {
            val v = RemoteViews(ctx.packageName, layout)
            // 줄 맨 앞의 체크 표시(☐/☑/◆)만 크게: 누르기 쉽고 한눈에 보이도록
            val styled = SpannableString(text)
            if (text.isNotEmpty() && text[0] in "☐☑◆") styled.setSpan(RelativeSizeSpan(1.45f), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            v.setTextViewText(R.id.t, styled)
            if (click != null) v.setOnClickPendingIntent(R.id.t, click)
            big.addView(R.id.rows, v)
        }
        add(R.layout.notif_head, if (items.isEmpty()) "할 일 없음" else "할 일")
        items.take(MAX_TODO).forEach { add(R.layout.notif_row, line(it, day), if (it.isEvent) null else toggle(ctx, it, day)) }
        if (items.size > MAX_TODO) add(R.layout.notif_row, "… 외 ${items.size - MAX_TODO}개")
        add(R.layout.notif_head, if (dd.isEmpty()) "D-day 없음" else "D-day")
        // D-day로 지정한 할 일도 줄을 눌러 완료 (해당 D-day 날짜 기준)
        dd.forEach { (e, d) ->
            add(R.layout.notif_row, (if (e.isEvent) "" else "☐ ") + ddayLabel(e, d, t), if (e.isEvent) null else toggle(ctx, e, d))
        }

        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val todoCount = items.count { !it.isEvent }
        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle(title)
            .setContentText("할 일 ${if (todoCount == 0) "없음" else "${todoCount}개"}" + (dd.firstOrNull()?.let { (e, d) -> " · " + ddayLabel(e, d, t) } ?: ""))
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
        ctx, e.id.toInt(), toggleIntent(ctx, e, day),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}

/** 알림/위젯에서 할 일을 눌렀을 때 보내는 인텐트 (done=true 완료, false 완료 취소). 두 곳이 같은 형식을 쓰도록 한 곳에서 만든다 */
fun toggleIntent(ctx: Context, e: Entry, day: Long, done: Boolean = true): Intent =
    Intent(ctx, ToggleReceiver::class.java)
        .setData(Uri.parse("todo://${e.id}/$day/$done"))   // 항목/날짜/동작마다 서로 다른 PendingIntent가 되도록
        .putExtra("id", e.id).putExtra("day", day).putExtra("done", done)

/** 알림/위젯에서 할 일을 눌렀을 때 완료 처리 */
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
    override suspend fun doWork(): Result { Refresh.all(applicationContext, awaitNetwork = true); return Result.success() }
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
        Refresh.watchData(this)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "refresh", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES).build()
        )
    }
}
