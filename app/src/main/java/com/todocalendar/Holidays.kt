package com.todocalendar

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * 대한민국 공휴일(대체공휴일·임시공휴일 포함). 날짜를 앱에 직접 넣지 않고
 * hyunbinseo/holidays-kr(MIT, 우주항공청 월력요항 기반)이 공개하는 연도별 JSON을 받아와 저장한다.
 * 일주일마다 자동 갱신하고, 인터넷이 없으면 저장된 데이터를 쓴다.
 */
object Holidays {
    private const val PREF = "holidays"
    private const val STALE = 7 * 24 * 3600_000L   // 이 주기로 새로 확인
    private const val RETRY = 60 * 60_000L         // 실패 후 재시도 간격
    private val SOURCES = listOf(
        "https://raw.githubusercontent.com/hyunbinseo/holidays-kr/main/public/%d.json",
        "https://holidays.hyunbin.page/%d.json",
    )

    /** 날짜(yyyy-MM-dd) -> 이름 ("추석 · 대체공휴일(…)" 처럼 여러 개면 합쳐서) */
    @Volatile private var map: Map<String, String> = emptyMap()
    /** 데이터가 바뀌면 올라가는 값: Compose가 읽으면 화면이 자동으로 다시 그려진다 */
    private var rev by mutableIntStateOf(0)
    private val lock = Mutex()

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 앱 시작 시 저장된 데이터를 메모리에 올린다 */
    fun init(ctx: Context) {
        map = loadAll(ctx)
        rev++
    }

    fun name(d: LocalDate): String? {
        rev // 상태 읽기: 데이터 갱신 시 재구성되도록
        return map[d.toString()]
    }

    fun isHoliday(d: LocalDate) = name(d) != null

    private fun parse(text: String): Map<String, String>? = try {
        val o = JSONObject(text)
        buildMap { o.keys().forEach { k -> put(k, (0 until o.getJSONArray(k).length()).joinToString(" · ") { o.getJSONArray(k).getString(it) }) } }
    } catch (e: Exception) { null }

    private fun loadAll(ctx: Context): Map<String, String> {
        val all = HashMap<String, String>()
        prefs(ctx).all.forEach { (k, v) -> if (k.startsWith("y") && v is String) parse(v)?.let(all::putAll) }
        return all
    }

    private fun fetch(year: Int): String? {
        for (src in SOURCES) {
            try {
                val c = URL(src.format(year)).openConnection() as HttpURLConnection
                c.connectTimeout = 5000
                c.readTimeout = 5000
                try {
                    if (c.responseCode != 200) continue   // 아직 공표되지 않은 해는 404
                    val text = c.inputStream.bufferedReader().readText()
                    if (parse(text) != null) return text
                } finally { c.disconnect() }
            } catch (e: Exception) { /* 다음 출처 시도 */ }
        }
        return null
    }

    /** 필요하면 지난해~내년 데이터를 받아 저장. 내용이 바뀌었으면 true (화면 갱신 필요) */
    suspend fun refreshIfStale(ctx: Context): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            val p = prefs(ctx)
            val now = System.currentTimeMillis()
            if (now - p.getLong("at", 0) < STALE || now - p.getLong("tried", 0) < RETRY) return@withContext false
            p.edit().putLong("tried", now).apply()
            val year = LocalDate.now().year
            var changed = false
            var anyOk = false
            for (y in year - 1..year + 1) {
                val text = fetch(y) ?: continue
                anyOk = true
                if (text != p.getString("y$y", null)) { p.edit().putString("y$y", text).apply(); changed = true }
            }
            if (anyOk) p.edit().putLong("at", now).apply()
            if (changed) { map = loadAll(ctx); rev++ }
            changed
        }
    }
}
