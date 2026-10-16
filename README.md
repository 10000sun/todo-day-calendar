# 투두데이 캘린더
달력 + 일별 투두 + D-day 안드로이드 앱 (Kotlin / Jetpack Compose / Room, 위젯은 표준 RemoteViews). 최소 Android 8.0(minSdk 26).

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

## 기술 스택
- Kotlin 2.0.20, Jetpack Compose(Material3), Room 2.6.1(KSP), WorkManager(15분 주기 갱신)
- AGP 8.5.2, JDK 17, compileSdk·targetSdk 36, Gradle 8.9 (`gradlew` 래퍼 포함)
- Google Mobile Ads(AdMob) 24.4.0 + UMP 동의 SDK 3.2.0

## 빌드 / 실행
- Android Studio에서 열거나 `./gradlew :app:assembleDebug` / `:app:assembleRelease`
- 별도 키 설정 없이 그대로 빌드·실행됩니다.
- 앱 화면 하단 AdMob 배너는 기본값이 **Google 공식 테스트 광고 ID**입니다. 실제 광고 ID는 소스에 넣지 않고 **빌드 환경변수**로 지정합니다.

| 환경변수 | 용도 | 없으면 |
|---|---|---|
| `ADMOB_APP_ID` | AdMob 앱 ID (매니페스트 placeholder) | 테스트 앱 ID |
| `ADMOB_BANNER_ID` | 배너 광고 단위 ID (`BuildConfig.ADMOB_BANNER_ID`) | 테스트 배너 ID |
| `RELEASE_KEYSTORE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` | 스토어 배포용 서명 | `app/debug.keystore`로 서명 |
| `VERSION_CODE`, `VERSION_NAME` | 릴리스 버전 | `1` / `1.0.0` |

예: `ADMOB_APP_ID=ca-app-pub-... ADMOB_BANNER_ID=ca-app-pub-... ./gradlew :app:assembleRelease`

## CI / 배포
- `.github/workflows/build.yml`: push마다 debug·release APK와 release AAB를 만들어 Artifacts(`apk`)로 올립니다. 16KB 페이지 크기 점검 단계 포함(참고용).
- `.github/workflows/release.yml`: `v*` 태그를 push하면(`git tag v1.0.0 && git push origin v1.0.0`) AAB·APK를 빌드하고 GitHub Release에 APK를 첨부합니다. 저장소 Secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`(업로드 키)와 `ADMOB_APP_ID`, `ADMOB_BANNER_ID`가 필요하며, `KEYSTORE_BASE64`가 없으면 실패하도록 되어 있습니다.
- 서명 키(`app/debug.keystore`, 비밀번호 `android`인 표준 Android 디버그 키)가 저장소에 고정돼 있어, 어느 빌드든 서명이 같고 **새 APK를 지우지 않고 덮어 설치**해도 데이터가 유지됩니다. 다만 이 키는 공개되어 있으므로 **스토어 배포나 타인에게 배포하는 용도로 쓰면 안 되며**, 배포용은 위 `RELEASE_*` 환경변수(별도 업로드 키)로 서명하세요.

## 프로젝트 구조
```
app/src/main/java/com/todocalendar/
  MainActivity.kt   Compose UI(달력, 목록, 편집, 백업/도움말 팝업), 권한 요청
  Data.kt           Room 엔티티·DAO·DB, 반복/D-day 계산
  Reminders.kt      정시 알림 예약(알람)
  Refresh.kt        위젯·고정 알림 갱신, 주기 작업(WorkManager)
  Widgets.kt        오늘 요약 / 월간 달력 위젯
  Summary.kt        위젯·알림용 요약 텍스트
  Weather.kt        현재 기온 조회(Open-Meteo) 및 위치 캐시
  Holidays.kt       한국 공휴일 데이터 내려받기·캐시
  Backup.kt         JSON 자동 백업/복원
  Ads.kt            AdMob 배너 + UMP 동의
  Guide.kt          사용법 안내 팝업
.github/workflows/  build.yml, release.yml
PLAY_RELEASE.md     Play 스토어 출시 점검표
```

## 권한 / 외부 통신 / 개인정보
`AndroidManifest.xml`에서 실제로 선언한 권한입니다.
- `POST_NOTIFICATIONS`, `USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM`(Android 12 이하), `RECEIVE_BOOT_COMPLETED`: 정시 알림과 부팅·시간 변경 후 알람 재설정
- `MANAGE_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`(Android 10 이하): `Documents/TodoDayCalendar/` 자동 백업 (위 "데이터 백업" 참고)
- `ACCESS_COARSE_LOCATION`: 날씨(기온) 표시용 대략적 위치. 거부하면 IP 기반 위치로 대체됩니다.
- `INTERNET`, `com.google.android.gms.permission.AD_ID`: 날씨·공휴일 조회, 광고

외부 통신:
- 날씨: `api.open-meteo.com`에 좌표를 보내 현재 기온을 받습니다. 위치 권한이 없으면 `ipwho.is`에 요청해 IP 기반 대략 위치를 얻습니다(이 경우 기기 IP가 해당 서비스에 전달됩니다).
- 공휴일: `raw.githubusercontent.com`(실패 시 `holidays.hyunbin.page`)에서 연도별 JSON을 받습니다.
- 광고: Google AdMob (EEA 등은 UMP 동의 후 요청). 광고는 앱 화면 하단에만 표시되고 위젯·알림에는 없습니다.
- 할 일 데이터는 기기 안(Room)과 위 백업 파일에만 저장되며, 개발자 서버로 전송하지 않습니다.

## 상태 / 한계
- 개인용으로 개발 중인 앱이며 Play 스토어에는 출시되지 않았습니다. 출시 전 해결할 항목(전체 파일 접근 권한, Open-Meteo 비상업 약관, 개인정보처리방침 등)은 [`PLAY_RELEASE.md`](PLAY_RELEASE.md)에 정리돼 있습니다.
- 개인정보처리방침 문서는 아직 없습니다.
- 자동화 테스트는 없습니다.
