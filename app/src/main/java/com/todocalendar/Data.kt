package com.todocalendar

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** 할 일/일정 한 건. date = LocalDate.toEpochDay() */
@Entity
data class Entry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: Long,
    val title: String,
    val isEvent: Boolean = false,
    val done: Boolean = false,
    val dday: Boolean = false,
)

@Dao
interface EntryDao {
    @Query("SELECT * FROM Entry WHERE date BETWEEN :from AND :to")
    fun range(from: Long, to: Long): Flow<List<Entry>>

    @Query("SELECT * FROM Entry WHERE dday = 1 ORDER BY date")
    fun ddays(): Flow<List<Entry>>

    @Query("SELECT * FROM Entry WHERE dday = 1 ORDER BY date")
    suspend fun ddayList(): List<Entry>

    @Query("SELECT * FROM Entry WHERE date = :d ORDER BY isEvent DESC, done, id")
    suspend fun on(d: Long): List<Entry>

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

fun ddayLabel(e: Entry, today: LocalDate = LocalDate.now()): String {
    val d = e.date - today.toEpochDay()
    val tag = when { d == 0L -> "D-Day"; d > 0 -> "D-$d"; else -> "D+${-d}" }
    return "$tag ${e.title}"
}

fun line(e: Entry) = (if (e.isEvent) "◆ " else if (e.done) "☑ " else "☐ ") + e.title

/** 오늘 이후(포함)의 D-day만 */
fun upcoming(all: List<Entry>, today: LocalDate = LocalDate.now()) = all.filter { it.date >= today.toEpochDay() }
