package com.todocalendar

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * 광고는 앱 화면 하단의 "광고" 구역 한 곳에만 둔다. (위젯·알림창·잠금화면에는 Play 정책상 광고를 넣을 수 없다.)
 * EEA/영국 등에서는 구글 UMP 동의 창을 먼저 거친 뒤에만 광고를 요청한다.
 */
object Ads {
    /** 광고 SDK가 준비되면 true -> 광고 구역이 나타난다 */
    var ready by mutableStateOf(false)
        private set
    private var requested = false

    fun start(activity: Activity) {
        if (requested) return
        requested = true
        val info = UserMessagingPlatform.getConsentInformation(activity)
        info.requestConsentInfoUpdate(
            activity, ConsentRequestParameters.Builder().build(),
            { UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { initIfAllowed(activity, info) } },
            { initIfAllowed(activity, info) }
        )
    }

    private fun initIfAllowed(ctx: Context, info: ConsentInformation) {
        if (ready || !info.canRequestAds()) return
        MobileAds.initialize(ctx.applicationContext) { ready = true }
    }
}

/** 하단 광고 구역: 본문과 구분선 + "광고" 표시 + 다른 배경색으로 분리 (광고를 콘텐츠로 오인해 누르지 않도록) */
@Composable
fun AdSection() {
    if (!Ads.ready) return
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        HorizontalDivider()
        Text("광고", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 2.dp))
        AndroidView(
            factory = { ctx ->
                AdView(ctx).apply {
                    setAdSize(AdSize.BANNER)
                    adUnitId = BuildConfig.ADMOB_BANNER_ID
                    loadAd(AdRequest.Builder().build())
                }
            },
            onRelease = { it.destroy() },
            modifier = Modifier.padding(bottom = 4.dp)
        )
    }
}
