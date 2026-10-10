package com.todocalendar

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import java.util.Locale
import android.location.LocationListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 현재 기온 (Open-Meteo, 키 불필요).
 * 백그라운드에서는 위치를 읽을 수 없으므로 앱을 열 때 마지막 위치를 저장해 두고 그 좌표로 조회한다.
 */
object Weather {
    private const val FRESH = 30 * 60_000L   // 이 시간 안의 캐시는 그대로 사용
    private const val RETRY = 5 * 60_000L    // 실패 후 재시도 간격
    /** 동시 갱신이 겹쳐도 네트워크 조회는 한 번만 하고, 나머지는 그 결과(캐시)를 쓴다 */
    private val lock = Mutex()

    private fun prefs(c: Context) = c.getSharedPreferences("weather", Context.MODE_PRIVATE)

    /** 좌표가 바뀌어 저장했으면 true */
    private fun save(c: Context, l: Location): Boolean {
        val p = prefs(c)
        val lat = l.latitude.toString()
        val lon = l.longitude.toString()
        // 같은 좌표면 캐시/재시도 간격을 그대로 둔다 (앱을 열 때마다 네트워크 조회하지 않도록)
        if (p.getString("lat", null) == lat && p.getString("lon", null) == lon) return false
        // 위치가 바뀌면 기온도 다음 갱신 때 새로 받도록 캐시 시각을 지운다
        p.edit().putString("lat", lat).putString("lon", lon).remove("at").remove("tried").apply()
        return true
    }

    /** 앱이 화면에 있을 때 호출: 현재(마지막) 위치를 저장하고 끝나면 then 실행 */
    @SuppressLint("MissingPermission")
    fun captureLocation(ctx: Context, then: () -> Unit) {
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = ctx.getSystemService(LocationManager::class.java)
        val last = lm.getProviders(true)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        // 좌표가 그대로면 다시 그릴 이유가 없다 (호출한 쪽이 이미 한 번 갱신을 시작했다)
        if (last != null) { if (save(ctx, last)) then(); return }
        if (!lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) return
        if (Build.VERSION.SDK_INT >= 30) {
            lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, ctx.mainExecutor) { l ->
                if (l != null && save(ctx, l)) then()
            }
        } else {
            @Suppress("DEPRECATION")
            lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, object : LocationListener {
                override fun onLocationChanged(l: Location) { if (save(ctx, l)) then() }
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }, Looper.getMainLooper())
        }
    }

    fun hasLocationPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * "18°C" 형태 (MET Norway, 상업 이용 가능·출처 표기 필요). 네트워크 실패 시 이전 캐시, 그것도 없으면 null
     */
    private suspend fun tempText(ctx: Context): String? = withContext(Dispatchers.IO) { lock.withLock { fetch(ctx) } }

    /** 필요하면 네트워크로 기온을 새로 받아 저장. 새 값이 저장됐으면 true (화면 갱신 필요) */
    suspend fun refreshIfStale(ctx: Context): Boolean {
        val p = prefs(ctx)
        val before = p.getLong("at", 0)
        tempText(ctx)
        return p.getLong("at", 0) != before
    }

    private fun fetch(ctx: Context): String? {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        val cached = if (p.contains("temp")) p.getFloat("temp", 0f) else null
        if (cached != null && now - p.getLong("at", 0) < FRESH) return fmt(cached)
        if (now - p.getLong("tried", 0) < RETRY) return cached?.let(::fmt)
        // 저장된 위치가 없으면 조회하지 않는다 (IP 기반 추정은 제3자에게 IP를 보내야 해서 쓰지 않음)
        val lat = p.getString("lat", null)?.toDoubleOrNull() ?: return cached?.let(::fmt)
        val lon = p.getString("lon", null)?.toDoubleOrNull() ?: return cached?.let(::fmt)
        p.edit().putLong("tried", now).apply()
        return try {
            // MET Norway 약관: 좌표는 소수 4자리까지, 앱을 식별하는 User-Agent 필수, 같은 요청을 자주 보내지 말 것(30분 캐시)
            val url = "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=%.4f&lon=%.4f".format(Locale.US, lat, lon)
            val t = getJson(url).getJSONObject("properties").getJSONArray("timeseries").getJSONObject(0)
                .getJSONObject("data").getJSONObject("instant").getJSONObject("details").getDouble("air_temperature").toFloat()
            p.edit().putFloat("temp", t).putLong("at", now).apply()
            fmt(t)
        } catch (e: Exception) {
            cached?.let(::fmt)
        }
    }

    private fun getJson(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("User-Agent", "TodoDayCalendar/1.0 github.com/10000sun/todo-day-calendar")
        try { return JSONObject(conn.inputStream.bufferedReader().readText()) } finally { conn.disconnect() }
    }

    /** 제목용: 저장된 기온만 즉시 반환(네트워크 대기 없음). 새 기온은 refreshIfStale 이후 화면에 반영된다 */
    fun cachedLabel(ctx: Context): String {
        val p = prefs(ctx)
        return when {
            p.contains("temp") -> fmt(p.getFloat("temp", 0f))
            p.getLong("tried", 0) > 0 -> "기온 확인 불가"
            !p.contains("lat") && !hasLocationPermission(ctx) -> "위치 권한 필요"
            else -> "기온 확인 중"
        }
    }

    private fun fmt(t: Float) = "${Math.round(t)}°C"
}
