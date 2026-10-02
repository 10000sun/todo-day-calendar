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

## 데이터 백업 / 복원 (앱을 지웠다 깔아도 복구)
- 폴더를 고를 필요 없이, 데이터를 바꿀 때마다 `Documents/TodoDayCalendar/todo-day-calendar-backup.json`에 **자동 저장**됩니다.
- 처음 한 번 **"모든 파일 접근"** 권한을 허용해야 합니다 (앱 상단 **백업** 버튼 → 권한 설정 열기).
  안드로이드는 재설치한 앱이 이전 설치가 만든 파일을 읽지 못하게 막기 때문에, 이 권한이 있어야 같은 위치의 파일을 계속 쓸 수 있습니다.
- 앱을 지웠다 다시 설치한 뒤: 앱을 열고 같은 권한을 다시 허용하면, 비어 있는 앱에 백업이 **자동 복원**됩니다
  (이미 새로 입력한 데이터가 있으면 복원/덮어쓰기를 묻습니다).
- 다른 위치에 따로 보관한 백업은 **백업 → 다른 파일에서 복원**으로 불러올 수 있습니다.
- 참고: "모든 파일 접근"은 Google Play 스토어에서 별도 심사가 필요한 권한입니다. 직접 설치(APK)하는 용도에는 문제가 없습니다.

## 빌드 / 배포
- Android Studio에서 열거나 `./gradlew :app:assembleDebug` / `:app:assembleRelease`
- push마다 CI가 APK를 만듭니다 (Actions → 해당 실행 → Artifacts → `apk`)
- 서명 키(`app/debug.keystore`)가 저장소에 고정돼 있어, **새 APK를 지우지 않고 덮어 설치**해도 데이터가 유지됩니다.
- 릴리스 배포: `git tag v1.0.0 && git push origin v1.0.0` → GitHub Release에 APK 자동 첨부
- 스토어(Play) 배포용 서명은 환경변수 `RELEASE_KEYSTORE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`로 지정
