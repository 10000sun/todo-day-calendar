package com.todocalendar

import android.app.*
import android.content.*
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.time.*

/** 시간 지정 알림: 다음 알림 시각 하나만 알람으로 걸고, 울릴 때 다음 것을 다시 건다. */
object Reminders {
    private const val CH = "reminder"
    /** 이 시간 안에 지난 알림만 늦게라도 발송 (Doze 지연 대비) */
    private const val MAX_LATE = 60 * 60_000L

    private fun at(e: Entry, day: Long) =
        LocalDateTime.of(LocalDate.ofEpochDay(day), LocalTime.of(e.timeMin / 60, e.timeMin % 60))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    suspend fun run(ctx: Context, fire: Boolean = true) {
        val list = Db.get(ctx).dao().reminders()
        val now = System.currentTimeMillis()
        val prefs = ctx.getSharedPreferences("rem", Context.MODE_PRIVATE)
        val last = maxOf(prefs.getLong("last", now), now - 24 * 3600_000L)
        val today = LocalDate.now().toEpochDay()

        // 마지막 실행 이후 도래한 알림 발송 (너무 오래된 것은 무시)
        if (fire) for (e in list) for (day in today - 1..today) {
            if (!e.occursOn(day) || e.isDone(day)) continue
            val t = at(e, day)
            if (t > last && t <= now && now - t < MAX_LATE) notify(ctx, e)
        }
        prefs.edit().putLong("last", now).apply()

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
