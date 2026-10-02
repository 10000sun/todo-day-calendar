package com.todocalendar

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

enum class Repeat(val label: String) { NONE("반복 없음"), DAILY("매일"), WEEKLY("매주"), MONTHLY("매월"), YEARLY("매년") }

/** 할 일/일정 한 건. date = 시작일(LocalDate.toEpochDay), timeMin = 0..1439 또는 -1(시간 없음) */
@Entity
data class Entry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: Long,
    val title: String,
    val isEvent: Boolean = false,
    val dday: Boolean = false,
    val repeat: Repeat = Repeat.NONE,
    val timeMin: Int = -1,
    val remind: Boolean = false,
    /** 완료한 날짜(epochDay) 콤마 목록 — 반복 할 일도 날짜별로 완료 */
    val doneDates: String = "",
)

@Dao
interface EntryDao {
    @Query("SELECT * FROM Entry WHERE date <= :to AND (date >= :from OR repeat != 'NONE')")
    fun visible(from: Long, to: Long): Flow<List<Entry>>

    @Query("SELECT * FROM Entry WHERE date <= :to AND (date >= :from OR repeat != 'NONE')")
    suspend fun visibleNow(from: Long, to: Long): List<Entry>

    @Query("SELECT * FROM Entry WHERE date <= :d AND (date = :d OR repeat != 'NONE')")
    suspend fun candidates(d: Long): List<Entry>

    @Query("SELECT * FROM Entry WHERE dday = 1")
    fun ddays(): Flow<List<Entry>>

    @Query("SELECT * FROM Entry WHERE dday = 1")
    suspend fun ddayList(): List<Entry>

    @Query("SELECT * FROM Entry WHERE remind = 1 AND timeMin >= 0")
    suspend fun reminders(): List<Entry>

    @Insert suspend fun add(e: Entry)
    @Update suspend fun update(e: Entry)
    @Delete suspend fun delete(e: Entry)
}

@Database(entities = [Entry::class], version = 1, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun dao(): EntryDao

    companion object {
        @Volatile private var inst: Db? = null
        fun get(c: Context): Db = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, Db::class.java, "cal.db").build().also { inst = it }
        }
    }
}

fun Entry.occursOn(day: Long): Boolean {
    if (day < date) return false
    if (repeat == Repeat.NONE) return day == date
    val s = LocalDate.ofEpochDay(date)
    val x = LocalDate.ofEpochDay(day)
    val dom = minOf(s.dayOfMonth, x.lengthOfMonth())
    return when (repeat) {
        Repeat.DAILY -> true
        Repeat.WEEKLY -> (day - date) % 7 == 0L
        Repeat.MONTHLY -> x.dayOfMonth == dom
        Repeat.YEARLY -> x.month == s.month && x.dayOfMonth == dom
        Repeat.NONE -> false
    }
}

/** from 이후(포함) 첫 발생일. 지난 단발 일정은 null */
fun Entry.nextOn(from: Long): Long? {
    val start = maxOf(from, date)
    if (repeat == Repeat.NONE) return if (start == date) date else null
    for (i in 0 until 1500) if (occursOn(start + i)) return start + i
    return null
}

fun Entry.isDone(day: Long) = day.toString() in doneDates.split(',')

fun Entry.withDone(day: Long, v: Boolean): Entry {
    val kept = doneDates.split(',').filter { it.isNotEmpty() && it != day.toString() }
    return copy(doneDates = (if (v) kept + day.toString() else kept).joinToString(","))
}

fun Entry.timeText() = if (timeMin < 0) "" else "%02d:%02d".format(timeMin / 60, timeMin % 60)

fun dayOrder(day: Long) = compareByDescending<Entry> { it.isEvent }
    .thenBy { it.isDone(day) }
    .thenBy { if (it.timeMin < 0) Int.MAX_VALUE else it.timeMin }
    .thenBy { it.id }

suspend fun EntryDao.onDay(day: Long) = candidates(day).filter { it.occursOn(day) }.sortedWith(dayOrder(day))

fun ddayLabel(e: Entry, today: LocalDate = LocalDate.now()): String {
    val t = today.toEpochDay()
    val d = (e.nextOn(t) ?: e.date) - t
    val tag = when { d == 0L -> "D-Day"; d > 0 -> "D-$d"; else -> "D+${-d}" }
    return "$tag ${e.title}"
}

fun line(e: Entry, day: Long) =
    (if (e.isEvent) "◆ " else if (e.isDone(day)) "☑ " else "☐ ") + (if (e.timeMin >= 0) e.timeText() + " " else "") + e.title

/** 앞으로 다가오는(또는 오늘) D-day만, 가까운 순 */
fun upcoming(all: List<Entry>, today: LocalDate = LocalDate.now()): List<Entry> {
    val t = today.toEpochDay()
    return all.filter { it.nextOn(t) != null }.sortedBy { it.nextOn(t) }
}
