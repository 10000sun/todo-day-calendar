package com.todocalendar

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 백업은 두 겹이다:
 * 1) 안드로이드 자동 백업(구글 계정): 플레이스토어로 설치하면 앱을 지웠다 다시 설치할 때 자동 복원된다 (AndroidManifest의 dataExtractionRules)
 * 2) 사용자가 직접 고르는 파일로 내보내기/가져오기 (아래) — 특수 권한 없이 저장소 접근 프레임워크만 사용
 */
object Backup {
    /** 서기 1년 ~ 9999년 (epochDay) */
    private const val MIN_DAY = -719_162L
    private const val MAX_DAY = 2_932_896L

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

    /** 현재 데이터를 사용자가 고른 파일에 저장 */
    suspend fun export(ctx: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val text = toJson(Db.get(ctx).dao().all())
            ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
            true
        } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
    }

    /** 사용자가 고른 파일에서 복원: 현재 데이터를 모두 교체. 복원한 항목 수, 실패하면 null */
    suspend fun restore(ctx: Context, uri: Uri): Int? = withContext(Dispatchers.IO) {
        try {
            val list = fromJson(ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
            val db = Db.get(ctx)
            db.withTransaction { db.dao().clear(); db.dao().addAll(list) }
            list.size
        } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
    }
}
