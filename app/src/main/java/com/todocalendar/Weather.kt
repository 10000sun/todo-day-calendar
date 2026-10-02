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

    private fun save(c: Context, l: Location) {
        val p = prefs(c)
        val lat = l.latitude.toString()
        val lon = l.longitude.toString()
        // 같은 좌표면 캐시/재시도 간격을 그대로 둔다 (앱을 열 때마다 네트워크 조회하지 않도록)
        if (p.getString("lat", null) == lat && p.getString("lon", null) == lon) return
        // 위치가 바뀌면 기온도 다음 갱신 때 새로 받도록 캐시 시각을 지운다
        p.edit().putString("lat", lat).putString("lon", lon).remove("at").remove("tried").apply()
    }

    /** 앱이 화면에 있을 때 호출: 현재(마지막) 위치를 저장하고 끝나면 then 실행 */
    @SuppressLint("MissingPermission")
    fun captureLocation(ctx: Context, then: () -> Unit) {
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = ctx.getSystemService(LocationManager::class.java)
        val last = lm.getProviders(true)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null) { save(ctx, last); then(); return }
        if (!lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) return
        if (Build.VERSION.SDK_INT >= 30) {
            lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, ctx.mainExecutor) { l ->
                if (l != null) { save(ctx, l); then() }
            }
        } else {
            @Suppress("DEPRECATION")
            lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, object : LocationListener {
                override fun onLocationChanged(l: Location) { save(ctx, l); then() }
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }, Looper.getMainLooper())
        }
    }

    fun hasLocationPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * "18°C" 형태. 저장된 위치가 없으면 IP 기반 대략적 위치로 대신 조회한다.
     * 네트워크 실패 시 이전 캐시, 그것도 없으면 null
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
        p.edit().putLong("tried", now).apply()
        return try {
            var lat = p.getString("lat", null)
            var lon = p.getString("lon", null)
            if (lat == null || lon == null) {
                lat = p.getString("iplat", null)
                lon = p.getString("iplon", null)
                if (lat == null || lon == null) {
                    val j = getJson("https://ipwho.is/")
                    lat = j.getDouble("latitude").toString()
                    lon = j.getDouble("longitude").toString()
                    p.edit().putString("iplat", lat).putString("iplon", lon).apply()
                }
            }
            val t = getJson("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m")
                .getJSONObject("current").getDouble("temperature_2m").toFloat()
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
        try { return JSONObject(conn.inputStream.bufferedReader().readText()) } finally { conn.disconnect() }
    }

    /** 제목용: 저장된 기온만 즉시 반환(네트워크 대기 없음). 새 기온은 refreshIfStale 이후 화면에 반영된다 */
    fun cachedLabel(ctx: Context): String {
        val p = prefs(ctx)
        return when {
            p.contains("temp") -> fmt(p.getFloat("temp", 0f))
            p.getLong("tried", 0) > 0 -> "기온 확인 불가"
            else -> "기온 확인 중"
        }
    }

    private fun fmt(t: Float) = "${Math.round(t)}°C"
}
