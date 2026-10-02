package com.todocalendar

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
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

    private fun prefs(c: Context) = c.getSharedPreferences("weather", Context.MODE_PRIVATE)

    private fun save(c: Context, l: Location) {
        prefs(c).edit().putString("lat", l.latitude.toString()).putString("lon", l.longitude.toString()).apply()
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
        if (Build.VERSION.SDK_INT >= 30 && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, ctx.mainExecutor) { l ->
                if (l != null) { save(ctx, l); then() }
            }
        }
    }

    /** "18°C" 형태. 위치가 없거나 조회 실패(캐시도 없음)면 null */
    suspend fun tempText(ctx: Context): String? = withContext(Dispatchers.IO) {
        val p = prefs(ctx)
        val lat = p.getString("lat", null) ?: return@withContext null
        val lon = p.getString("lon", null) ?: return@withContext null
        val now = System.currentTimeMillis()
        val cached = if (p.contains("temp")) p.getFloat("temp", 0f) else null
        if (cached != null && now - p.getLong("at", 0) < FRESH) return@withContext fmt(cached)
        if (now - p.getLong("tried", 0) < RETRY) return@withContext cached?.let(::fmt)
        p.edit().putLong("tried", now).apply()
        try {
            val conn = URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val t = try {
                JSONObject(conn.inputStream.bufferedReader().readText()).getJSONObject("current").getDouble("temperature_2m")
            } finally { conn.disconnect() }
            p.edit().putFloat("temp", t.toFloat()).putLong("at", now).apply()
            fmt(t.toFloat())
        } catch (e: Exception) {
            cached?.let(::fmt)
        }
    }

    private fun fmt(t: Float) = "${Math.round(t)}°C"
}
