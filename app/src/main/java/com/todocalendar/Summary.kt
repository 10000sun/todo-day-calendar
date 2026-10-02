package com.todocalendar

import android.content.Context
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

/** 알림창과 위젯이 같이 쓰는 "오늘 요약" */
data class Summary(
    val today: LocalDate,
    val title: String,                       // "10/2 - 18°C"
    val items: List<Entry>,                  // 오늘의 일정 + 아직 안 끝낸 할 일
    val dd: List<Pair<Entry, Long>>,         // D-day와 해당 날짜, 가까운 순 (완료한 할 일 제외)
) {
    val day get() = today.toEpochDay()

    companion object {
        suspend fun load(ctx: Context): Summary {
            val t = LocalDate.now()
            val head = "${t.monthValue}/${t.dayOfMonth}"
            return try {
                val dao = Db.get(ctx).dao()
                val day = t.toEpochDay()
                Summary(
                    t, "$head - ${Weather.cachedLabel(ctx)}",
                    dao.onDay(day).filter { it.isEvent || !it.isDone(day) },
                    upcomingDates(dao.ddayList(), t)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Summary(t, head, emptyList(), emptyList())
            }
        }
    }
}
