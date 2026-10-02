package com.todocalendar

import android.app.*
import android.content.*
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.*

/** 시간 지정 알림: 다음 알림 시각 하나만 알람으로 걸고, 울릴 때 다음 것을 다시 건다. */
object Reminders {
    private const val CH = "reminder"
    /** 이 시간 안에 지난 알림만 늦게라도 발송 (Doze 지연 대비) */
    private const val MAX_LATE = 60 * 60_000L

    private fun sig(e: Entry) = "${e.id}|${e.date}|${e.timeMin}|${e.repeat}"

    private fun at(e: Entry, day: Long) =
        LocalDateTime.of(LocalDate.ofEpochDay(day), LocalTime.of(e.timeMin / 60, e.timeMin % 60))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** 알람 수신(ReminderReceiver)과 Refresh가 동시에 돌아 같은 알림을 두 번 보내지 않도록 */
    private val runLock = Mutex()

    suspend fun run(ctx: Context) = runLock.withLock { runLocked(ctx) }

    private suspend fun runLocked(ctx: Context) {
        val list = Db.get(ctx).dao().reminders()
        val now = System.currentTimeMillis()
        val prefs = ctx.getSharedPreferences("rem", Context.MODE_PRIVATE)
        val last = maxOf(prefs.getLong("last", now), now - 24 * 3600_000L)
        val today = LocalDate.now().toEpochDay()

        // 직전 실행 때 이미 등록돼 있던 항목만 발송한다.
        // 방금 새로 저장/수정한 항목의 이미 지난 시각은 울리지 않고, Doze 등으로 늦어진 알림은 놓치지 않는다.
        val known = prefs.getStringSet("sig", null)
        if (known != null) for (e in list) for (day in today - 1..today) {
            if (sig(e) !in known || !e.occursOn(day) || e.isDone(day)) continue
            val t = at(e, day)
            if (t > last && t <= now && now - t < MAX_LATE) notify(ctx, e)
        }
        prefs.edit().putLong("last", now).putStringSet("sig", list.map(::sig).toSet()).apply()

        // 다음 알림 예약
        var next = Long.MAX_VALUE
        for (e in list) {
            var d = e.nextOn(today) ?: continue
            if (at(e, d) <= now) d = e.nextOn(d + 1) ?: continue
            next = minOf(next, at(e, d))
        }
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 7, Intent(ctx, ReminderReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        if (next == Long.MAX_VALUE) am.cancel(pi)
        else if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
    }

    private fun notify(ctx: Context, e: Entry) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(NotificationChannel(CH, "일정 알림", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        nm.notify(
            1000 + e.id.toInt(),
            NotificationCompat.Builder(ctx, CH)
                .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                .setContentTitle(e.title)
                .setContentText(e.timeText() + if (e.repeat != Repeat.NONE) " · ${e.repeat.label}" else "")
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
        )
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val p = goAsync()
        CoroutineScope(Dispatchers.Default).launch { try { Reminders.run(c) } finally { p.finish() } }
    }
}
