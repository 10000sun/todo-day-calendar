package com.todocalendar

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 사용법 안내 팝업. 처음 설치한 사람뿐 아니라 이전 버전에서 업데이트한 사람(기록이 없으므로)도 한 번 보게 된다.
 * 기능이 크게 바뀌면 VERSION을 올려서 다시 보여준다.
 */
object Guide {
    private const val VERSION = 1

    private fun prefs(c: Context) = c.getSharedPreferences("guide", Context.MODE_PRIVATE)
    fun seen(c: Context) = prefs(c).getInt("seen", 0) >= VERSION
    fun markSeen(c: Context) = prefs(c).edit().putInt("seen", VERSION).apply()
}

private val SECTIONS = listOf(
    "📅 달력과 일정" to
        "날짜를 누르고 오른쪽 아래 ＋ 버튼으로 할 일이나 일정을 추가하세요. 항목을 누르면 수정할 수 있고, 반복(매일·매주·매월·매년), 시간, 정시 알림도 정할 수 있습니다. 일정이나 할 일이 있는 날에는 점이 표시됩니다.",
    "⏳ D-day" to
        "추가할 때 'D-day로 표시'를 켜면 화면 위쪽에 D-18 처럼 남은 날이 보입니다. 기한이 지났는데 끝내지 않은 할 일은 D+3 처럼 계속 남아 있다가, 체크하면 사라집니다.",
    "🧩 홈 화면 위젯 2종" to
        "홈 화면을 길게 눌러 '위젯'에서 투두데이 캘린더를 추가하세요.\n• 오늘 위젯: 날짜·기온·오늘 할 일·D-day\n• 월간 달력 위젯: 날짜를 누르면 그날의 일정과 할 일이 아래에 나옵니다\n위젯에서 ☐를 누르면 바로 완료되고, 그 밖의 곳을 누르면 앱이 열립니다.",
    "🔔 알림창·잠금화면" to
        "오늘의 날짜·기온·할 일·D-day가 알림창에 고정됩니다. 할 일 줄을 누르면 바로 완료됩니다. 잠금화면에서도 보이려면 설정 > 잠금화면 > 알림에서 '알림 내용 모두 표시'를 선택하세요.",
    "🎌 공휴일" to
        "한국 공휴일과 대체공휴일이 자동으로 빨간 날짜로 표시되고, 날짜를 누르면 이름이 나옵니다. 인터넷으로 일주일마다 최신 정보를 받아옵니다.",
    "💾 자동 백업과 복원" to
        "상단의 '백업' 버튼에서 '모든 파일 접근'을 한 번 허용하면, 데이터를 바꿀 때마다 Documents/TodoDayCalendar 폴더에 자동으로 백업됩니다. 앱을 지웠다 다시 설치해도 이 권한만 다시 허용하면 자동으로 복원됩니다.",
    "🔄 이전 버전에서 업데이트하셨나요?" to
        "위젯 구조가 새로 바뀌었습니다. 홈 화면의 기존 위젯은 지우고 다시 추가해 주세요. 데이터는 그대로 유지됩니다.",
)

@Composable
fun GuideDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("투두데이 캘린더 사용법") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SECTIONS.forEach { (head, body) ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(head, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(body, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text("이 안내는 화면 위쪽의 ? 버튼으로 언제든 다시 볼 수 있습니다.", style = MaterialTheme.typography.labelMedium)
            }
        },
        confirmButton = { TextButton(onClose) { Text("시작하기") } }
    )
}
