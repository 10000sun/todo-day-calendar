package com.todocalendar

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 앱을 지웠다 다시 깔아도 데이터를 살릴 수 있도록 JSON 파일로 백업/복원.
 * 사용자가 고른 파일(SAF)에 데이터가 바뀔 때마다 자동으로 덮어쓴다. 파일은 앱 삭제 후에도 남는다.
 */
object Backup {
    private const val PREF = "backup"
    private const val KEY = "uri"
    /** 자동 백업/지정/복원 후 재저장이 같은 파일을 동시에 "wt"로 열어 내용이 뒤섞이지 않도록 */
    private val writeLock = Mutex()

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun target(c: Context): Uri? = prefs(c).getString(KEY, null)?.let(Uri::parse)
    fun setTarget(c: Context, uri: Uri) = prefs(c).edit().putString(KEY, uri.toString()).putBoolean("ok", true).apply()

    /** 백업 파일이 지정돼 있고, 쓰기 권한이 남아 있고, 마지막 저장이 성공했을 때만 true */
    fun isActive(c: Context): Boolean {
        val u = target(c) ?: return false
        val granted = c.contentResolver.persistedUriPermissions.any { it.uri == u && it.isWritePermission }
        return granted && prefs(c).getBoolean("ok", true)
    }

    /** 백업이 아닌 내용이 들어 있는 파일 */
    const val NOT_BACKUP = -1

    /** 파일에 이미 들어 있는 백업의 항목 수. 빈 파일이면 null, 백업이 아닌 내용이면 NOT_BACKUP */
    suspend fun peek(ctx: Context, uri: Uri): Int? = withContext(Dispatchers.IO) {
        try {
            ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?.takeIf { it.isNotBlank() }?.let { runCatching { fromJson(it).size }.getOrDefault(NOT_BACKUP) }
        } catch (e: Exception) { null }
    }

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
            Entry(
                id = o.getLong("id"), date = o.getLong("date"), title = o.getString("title"),
                isEvent = o.getBoolean("isEvent"), dday = o.getBoolean("dday"),
                repeat = runCatching { Repeat.valueOf(o.getString("repeat")) }.getOrDefault(Repeat.NONE),
                timeMin = timeMin, remind = o.getBoolean("remind") && timeMin >= 0, doneDates = o.optString("doneDates", ""),
            )
        }
    }

    /** 지정한 파일에 현재 데이터 저장 (성공 여부 반환) */
    suspend fun write(ctx: Context, uri: Uri): Boolean = writeLock.withLock {
        withContext(Dispatchers.IO) {
            try {
                val text = toJson(Db.get(ctx).dao().all())
                ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
                true
            } catch (e: Exception) { false }
        }
    }

    /** 자동 백업이 켜져 있으면 갱신 (실패해도 앱 동작에는 영향 없음) */
    suspend fun autoWrite(ctx: Context) {
        val u = target(ctx) ?: return
        prefs(ctx).edit().putBoolean("ok", write(ctx, u)).apply()
    }

    /** 백업 파일 내용으로 현재 데이터를 모두 교체. 복원한 항목 수, 실패하면 null */
    suspend fun restore(ctx: Context, uri: Uri): Int? = withContext(Dispatchers.IO) {
        try {
            val list = fromJson(ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
            val db = Db.get(ctx)
            db.withTransaction { db.dao().clear(); db.dao().addAll(list) }
            list.size
        } catch (e: Exception) { null }
    }
}
