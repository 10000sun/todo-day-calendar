# 투두데이 캘린더
달력 + 일별 투두 + D-day 안드로이드 앱 (Kotlin / Compose / Room, 위젯은 표준 RemoteViews). 최소 Android 8.0.

## 기능
- 달력에서 날짜 선택 → `＋`로 할 일/일정 추가, 항목을 누르면 수정
- 반복(매일/매주/매월/매년), 시간 지정, 정시 알림, D-day (생일 같은 매년 반복 D-day 포함)
- 홈 화면 위젯 2종: 오늘 요약 위젯(날짜-기온 / 할 일 / D-day) · 월간 달력 위젯. 위젯에서 할 일을 눌러 바로 완료
- 알림창/잠금화면 고정 알림: 날짜-기온, 할 일, D-day. 할 일을 눌러 바로 완료
  (잠금화면에서 보이려면 설정 > 잠금화면 > 알림 내용 모두 표시)
- 한국 공휴일·대체공휴일·임시공휴일을 자동으로 받아 표시 (달력/위젯에서 빨간 날짜, 선택하면 이름 표시). 데이터 출처: [hyunbinseo/holidays-kr](https://github.com/hyunbinseo/holidays-kr) (MIT, 우주항공청 월력요항 기반), 일주일마다 갱신·오프라인 시 저장본 사용
- D-day로 지정한 할 일은 완료하면 숨겨지고, 기한이 지났는데 아직 안 끝냈다면 `D+N`으로 계속 표시됩니다 (지난 일정은 사라짐)

## 데이터 백업 / 복원
- **자동**: 플레이스토어로 설치하고 구글 계정 백업이 켜져 있으면, 앱을 지웠다 다시 설치할 때 일정 데이터가 자동 복원됩니다 (안드로이드 자동 백업).
- **수동**: 상단 **백업** → *파일로 내보내기* / *파일에서 가져오기*. 클라우드 보관이나 다른 폰으로 옮길 때 사용합니다. 특수 권한이 필요 없습니다.

## 빌드 / 배포
- Android Studio에서 열거나 `./gradlew :app:assembleDebug` / `:app:assembleRelease`
- push마다 CI가 APK를 만듭니다 (Actions → 해당 실행 → Artifacts → `apk`)
- 서명 키(`app/debug.keystore`)가 저장소에 고정돼 있어, **새 APK를 지우지 않고 덮어 설치**해도 데이터가 유지됩니다.
- 릴리스 배포: `git tag v1.0.0 && git push origin v1.0.0` → GitHub Release에 APK 자동 첨부
- 스토어(Play) 배포용 서명은 환경변수 `RELEASE_KEYSTORE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`로 지정
