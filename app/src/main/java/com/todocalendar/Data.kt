package com.todocalendar

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.YearMonth

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

    @Query("SELECT * FROM Entry WHERE id = :id")
    suspend fun get(id: Long): Entry?

    @Query("SELECT * FROM Entry ORDER BY id")
    suspend fun all(): List<Entry>

    @Query("DELETE FROM Entry")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun addAll(list: List<Entry>)

    @Insert suspend fun add(e: Entry)
    @Update suspend fun update(e: Entry)
    @Delete suspend fun delete(e: Entry)
}

@Database(entities = [Entry::class], version = 2, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun dao(): EntryDao

    companion object {
        /** v1(done 컬럼)에서 v2로: 완료 표시를 doneDates로 옮기고 새 컬럼 추가 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE Entry_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, date INTEGER NOT NULL, " +
                        "title TEXT NOT NULL, isEvent INTEGER NOT NULL, dday INTEGER NOT NULL, repeat TEXT NOT NULL, " +
                        "timeMin INTEGER NOT NULL, remind INTEGER NOT NULL, doneDates TEXT NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO Entry_new (id, date, title, isEvent, dday, repeat, timeMin, remind, doneDates) " +
                        "SELECT id, date, title, isEvent, dday, 'NONE', -1, 0, CASE WHEN done = 1 THEN CAST(date AS TEXT) ELSE '' END FROM Entry"
                )
                db.execSQL("DROP TABLE Entry")
                db.execSQL("ALTER TABLE Entry_new RENAME TO Entry")
            }
        }

        @Volatile private var inst: Db? = null
        fun get(c: Context): Db = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, Db::class.java, "cal.db").addMigrations(MIGRATION_1_2).build().also { inst = it }
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

/** from 이후(포함) 첫 발생일(상수 시간). 지난 단발 일정은 null */
fun Entry.nextOn(from: Long): Long? {
    val start = maxOf(from, date)
    val s = LocalDate.ofEpochDay(date)
    val f = LocalDate.ofEpochDay(start)
    fun inMonth(ym: YearMonth) = ym.atDay(minOf(s.dayOfMonth, ym.lengthOfMonth())).toEpochDay()
    return when (repeat) {
        Repeat.NONE -> if (start == date) date else null
        Repeat.DAILY -> start
        Repeat.WEEKLY -> start + (7 - (start - date) % 7) % 7
        Repeat.MONTHLY -> (0L..1L).map { inMonth(YearMonth.from(f).plusMonths(it)) }.first { it >= start }
        Repeat.YEARLY -> (0..1).map { inMonth(YearMonth.of(f.year + it, s.month)) }.first { it >= start }
    }
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

/**
 * 표시할 D-day와 그 날짜. 날짜 순(지난 것이 먼저, 그다음 가까운 순).
 * - 오늘 이후(포함)의 D-day: 일정은 항상, 할 일은 완료하지 않은 것만
 * - 지난 D-day: 아직 완료하지 않은 할 일만 D+N 으로 계속 표시 (일정은 지나면 사라짐)
 */
fun upcomingDates(all: List<Entry>, today: LocalDate = LocalDate.now()): List<Pair<Entry, Long>> {
    val t = today.toEpochDay()
    return all.mapNotNull { e ->
        val next = e.nextOn(t)
        when {
            next != null -> if (!e.isEvent && e.isDone(next)) null else e to next
            !e.isEvent && !e.isDone(e.date) -> e to e.date   // 지났는데 안 끝낸 할 일
            else -> null
        }
    }.sortedBy { it.second }
}

fun upcoming(all: List<Entry>, today: LocalDate = LocalDate.now()): List<Entry> = upcomingDates(all, today).map { it.first }

/** 체크/저장은 DB의 최신 값을 읽어 합쳐서 쓴다 (이전에 캡처한 값으로 덮어쓰지 않도록) */
suspend fun Db.setDone(id: Long, day: Long, v: Boolean) = withTransaction {
    dao().get(id)?.let { dao().update(it.withDone(day, v)) }
}

suspend fun Db.save(e: Entry) = withTransaction {
    if (e.id == 0L) dao().add(e) else {
        val cur = dao().get(e.id)
        // 날짜/반복을 바꾸면 이전 완료 표시는 의미가 없으므로 초기화
        val keep = cur != null && cur.date == e.date && cur.repeat == e.repeat
        dao().update(e.copy(doneDates = if (keep) cur!!.doneDates else ""))
    }
}
