# Play 스토어 출시 점검표

## 코드에서 이미 처리한 것
- targetSdk/compileSdk 36 (2026-08-31부터 신규 앱 필수)
- 광고: 앱 화면 하단의 "광고" 구역 1곳만 (AdMob 배너 + UMP 동의). 위젯·알림·잠금화면에는 광고 금지(Play 정책)
- AAB 빌드(`bundleRelease`), 릴리스 서명은 업로드 키 환경변수로 (태그 푸시 시 release.yml)

## 직접 해야 하는 것
1. 업로드 키 만들기: `keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000`
   → `base64 -w0 upload.jks` 결과를 GitHub Secrets `KEYSTORE_BASE64`, 비밀번호/별칭을 `KEYSTORE_PASSWORD` `KEY_ALIAS` `KEY_PASSWORD` 로 등록. **키 파일은 안전하게 백업** (분실하면 업데이트 불가)
2. AdMob 앱/배너 ID 발급 → Secrets `ADMOB_APP_ID`, `ADMOB_BANNER_ID` (미설정 시 테스트 광고만 나옴. 테스트 ID로 출시 금지)
3. 개인정보처리방침 URL (위치, 광고 ID, 외부 서버 통신 명시) + 플레이 콘솔 데이터 보안 양식 + 콘텐츠 등급
4. 스토어 등록정보: 512px 아이콘, 1024x500 그래픽, 스크린샷 2장 이상
5. 패키지명(`com.todocalendar`)은 출시 후 변경 불가 — 본인 도메인 기반으로 지금 정할 것
6. 개인 계정은 비공개 테스트(12명 이상, 14일) 후 프로덕션 신청 가능

## 출시 전에 해결할 문제 (코드 변경 필요)
- ~~"모든 파일 접근" 권한~~ → 제거하고 안드로이드 자동 백업 + 파일 내보내기/가져오기로 교체함 (feature/backup-play)
- ~~날씨(Open-Meteo 비상업 전용)~~ → MET Norway(상업 이용 가능, CC BY 4.0)로 교체. 앱 안내 팝업에 출처 표기. 개인정보처리방침에 "위치(대략)를 api.met.no에 전송" 명시 필요
- ~~공휴일 GitHub 직접 호출~~ → 데이터를 앱에 포함(assets). 릴리스 워크플로가 `scripts/fetch-holidays.sh` 로 최신화. 라이선스는 NOTICE.md
- 16KB 페이지 크기: CI의 "16KB page-size check" 단계 결과 확인 (네이티브 라이브러리 정렬)
