package com.todocalendar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 데이터를 바꿀 때마다 기기의 고정 위치(Documents/TodoDayCalendar)에 JSON으로 자동 백업한다.
 * 사용자가 폴더를 고르지 않으며, 앱을 지우고 다시 설치해도 이 파일은 남는다.
 * (안드로이드는 앱 삭제 후 재설치하면 앱이 만든 공유 저장소 파일을 읽을 수 없게 하므로
 *  '모든 파일 접근' 권한을 한 번 허용해야 한다. 재설치 후에도 다시 한 번 허용하면 자동 복원된다.)
 */
object Backup {
    private const val PREF = "backup"
    /** 백업이 아닌 내용이 들어 있거나 읽을 수 없는 파일 */
    const val NOT_BACKUP = -1
    const val PATH_TEXT = "Documents/TodoDayCalendar/todo-day-calendar-backup.json"
    private val writeLock = Mutex()
    /** 서기 1년 ~ 9999년 (epochDay) */
    private const val MIN_DAY = -719_162L
    private const val MAX_DAY = 2_932_896L

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    @Suppress("DEPRECATION")
    fun file(): File = File(
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "TodoDayCalendar"),
        "todo-day-calendar-backup.json"
    )

    /** 고정 위치에 쓸 수 있는 권한(Android 11+: 모든 파일 접근, 10 이하: 저장소 쓰기) */
    fun hasAccess(c: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    // 이 설치에서 "기존 백업을 복원할지/덮어쓸지" 정리가 끝났는지. 앱을 지우면 초기화되어 재설치 후 다시 판단한다
    fun resolved(c: Context) = prefs(c).getBoolean("resolved", false)
    fun setResolved(c: Context) = prefs(c).edit().putBoolean("resolved", true).apply()
    fun prompted(c: Context) = prefs(c).getBoolean("prompted", false)
    fun setPrompted(c: Context) = prefs(c).edit().putBoolean("prompted", true).apply()

    /** 권한이 있고, 첫 연결이 끝났고, 마지막 저장이 성공했을 때만 true */
    fun isActive(c: Context): Boolean = hasAccess(c) && resolved(c) && prefs(c).getBoolean("ok", true)

    fun toJson(list: List<Entry>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("date", it.date).put("title", it.title)
                    .put("isEvent", it.isEvent).put("dday", it.dday).put("repeat", it.repeat.name)
                    .put("timeMin", it.timeMin).put("remind", it.remind).put("doneDates", it.doneDates)
            )
        }
        return JSONObject().put("app", "todocalendar").put("version", 1).put("entries", arr).toString(1)
    }

    fun fromJson(text: String): List<Entry> {
        val root = JSONObject(text)
        require(root.optString("app") == "todocalendar") { "투두데이 캘린더 백업 파일이 아닙니다" }
        val arr = root.getJSONArray("entries")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            // 손으로 고친/깨진 파일의 범위 밖 시각이 알림 예약(LocalTime.of)을 영구히 실패시키지 않도록 보정
            val timeMin = o.getInt("timeMin").let { t -> if (t in 0..1439) t else -1 }
            // 범위 밖 날짜(LocalDate.ofEpochDay 가 던짐)가 달력/위젯을 매번 죽이지 않도록 파일째 거부
            val date = o.getLong("date")
            require(date in MIN_DAY..MAX_DAY) { "날짜가 범위를 벗어났습니다" }
            Entry(
                id = o.getLong("id"), date = date, title = o.getString("title"),
                isEvent = o.getBoolean("isEvent"), dday = o.getBoolean("dday"),
                repeat = runCatching { Repeat.valueOf(o.getString("repeat")) }.getOrDefault(Repeat.NONE),
                timeMin = timeMin, remind = o.getBoolean("remind") && timeMin >= 0, doneDates = o.optString("doneDates", ""),
            )
        }
    }

    /** 현재 데이터를 고정 위치에 저장. 임시 파일에 먼저 쓰고 바꿔치기해서 중간에 끊겨도 기존 백업이 깨지지 않는다 */
    suspend fun write(ctx: Context): Boolean = writeLock.withLock {
        withContext(Dispatchers.IO) {
            val ok = try {
                val f = file()
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(toJson(Db.get(ctx).dao().all()))
                tmp.renameTo(f) || (f.delete() && tmp.renameTo(f))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
            prefs(ctx).edit().putBoolean("ok", ok).apply()
            ok
        }
    }

    /** 사용자가 데이터를 바꾼 뒤 호출: 권한이 있고 첫 연결이 끝난 경우에만 저장 */
    suspend fun autoWrite(ctx: Context) { if (hasAccess(ctx) && resolved(ctx)) write(ctx) }

    /** 고정 위치 백업의 항목 수. 파일이 없거나 비었으면 null, 백업이 아니거나 읽을 수 없으면 NOT_BACKUP */
    suspend fun peek(): Int? = withContext(Dispatchers.IO) {
        try {
            val f = file()
            if (!f.isFile) null
            else f.readText().takeIf { it.isNotBlank() }?.let { runCatching { fromJson(it).size }.getOrDefault(NOT_BACKUP) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { NOT_BACKUP }
    }

    private suspend fun replaceAll(ctx: Context, list: List<Entry>): Int {
        val db = Db.get(ctx)
        db.withTransaction { db.dao().clear(); db.dao().addAll(list) }
        return list.size
    }

    /** 고정 위치 백업으로 현재 데이터를 모두 교체. 복원한 항목 수, 실패하면 null */
    suspend fun restoreFile(ctx: Context): Int? = withContext(Dispatchers.IO) {
        try { replaceAll(ctx, fromJson(file().readText())) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
    }

    /** 사용자가 고른 다른 파일(예: 클라우드에 따로 보관한 백업)에서 복원 */
    suspend fun restore(ctx: Context, uri: Uri): Int? = withContext(Dispatchers.IO) {
        try {
            replaceAll(ctx, fromJson(ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }))
        } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
    }
}
