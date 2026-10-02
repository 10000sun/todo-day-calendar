# 투두데이 캘린더
달력 + 일별 투두 + D-day 안드로이드 앱 (Kotlin / Compose / Room / Glance). 최소 Android 8.0.

## 기능
- 달력에서 날짜 선택 → `＋`로 할 일/일정 추가, 항목을 누르면 수정
- 반복(매일/매주/매월/매년), 시간 지정, 정시 알림, D-day (생일 같은 매년 반복 D-day 포함)
- 홈 화면 위젯 2종: 오늘 요약 위젯(날짜-기온 / 할 일 / D-day) · 월간 달력 위젯. 위젯에서 할 일을 눌러 바로 완료
- 알림창/잠금화면 고정 알림: 날짜-기온, 할 일, D-day. 할 일을 눌러 바로 완료
  (잠금화면에서 보이려면 설정 > 잠금화면 > 알림 내용 모두 표시)
- 한국 공휴일·대체공휴일·임시공휴일을 자동으로 받아 표시 (달력/위젯에서 빨간 날짜, 선택하면 이름 표시). 데이터 출처: [hyunbinseo/holidays-kr](https://github.com/hyunbinseo/holidays-kr) (MIT, 우주항공청 월력요항 기반), 일주일마다 갱신·오프라인 시 저장본 사용
- D-day로 지정한 할 일은 완료하면 숨겨지고, 기한이 지났는데 아직 안 끝냈다면 `D+N`으로 계속 표시됩니다 (지난 일정은 사라짐)

## 데이터 백업 / 복원 (앱을 지웠다 깔아도 복구)
1. 앱 상단 **백업** → **백업 파일 지정**: 원하는 위치(내 파일/문서 등)에 `todocalendar-backup.json` 저장.
   이후 데이터를 바꿀 때마다 이 파일이 자동으로 갱신됩니다.
2. 앱을 지웠다 다시 설치한 뒤: **백업** → **백업에서 복원** → 저장해 둔 파일 선택.

## 빌드 / 배포
- Android Studio에서 열거나 `./gradlew :app:assembleDebug` / `:app:assembleRelease`
- push마다 CI가 APK를 만듭니다 (Actions → 해당 실행 → Artifacts → `apk`)
- 서명 키(`app/debug.keystore`)가 저장소에 고정돼 있어, **새 APK를 지우지 않고 덮어 설치**해도 데이터가 유지됩니다.
- 릴리스 배포: `git tag v1.0.0 && git push origin v1.0.0` → GitHub Release에 APK 자동 첨부
- 스토어(Play) 배포용 서명은 환경변수 `RELEASE_KEYSTORE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`로 지정
