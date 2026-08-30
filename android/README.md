# Catch Train — 안드로이드 앱

WebView 로 코레일(KTX) 조회 결과 화면을 감시하는 앱. 제품 전체 설명과 저장소 구조는
[`../README.md`](../README.md), 대원칙은 [`../CLAUDE.md`](../CLAUDE.md) 에 있다.

**Gradle 루트는 이 폴더다.** Android Studio 에서는 저장소 루트가 아니라 `android/` 를 연다
(`Open` → 이 폴더). 아래 명령도 전부 이 폴더에서 실행한다.

---

## 빌드

| 항목 | 값 |
|---|---|
| Android Studio | Ladybug (2024.2) 이상 |
| JDK | 17 |
| Kotlin / AGP | 2.0.21 / 8.7.3 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 26 (Android 8.0) |
| UI | Jetpack Compose (Material 3) |

`minSdk 26` 인 이유: `java.time` 과 `NotificationChannel` 을 desugaring 없이 쓴다.

```bash
cd android && ./gradlew --offline :app:assembleDebug
```

```bash
cd android && ./gradlew --offline :app:testDebugUnitTest
```

release 빌드는 `--offline` 이 안 된다 (`lintVital` 이 `lint-gradle` 을 받아와야 한다)
→ [`RELEASE.md`](RELEASE.md)

## 쓰는 흐름

1. 앱 실행 → WebView 가 코레일 메인(`https://www.korail.com/ticket/main`)을 표시
2. **WebView 안에서 직접 로그인**하고, 원하는 구간/날짜/시간으로 **조회**한다
   - 비로그인 상태로 메인에 닿으면 앱이 로그인 화면으로 보낸다 (§27-2).
     앱이 스스로 URL 을 여는 유일한 자리다
3. `열차 선택` 패널의 `다시 읽기` → 지금 화면에 그려진 조회 결과를 그대로 읽어온다
   (조회 요청을 보내지 않고 DOM 만 읽으므로 몇 번을 눌러도 차단 위험이 없다)
4. 원하는 칸을 체크한다. 좌석 열은 **일반실이 왼쪽, 특실이 오른쪽** (§38-3, SRT 와 반대)
   - **매진인 칸도 체크할 수 있다.** 풀리기를 기다리는 것이 이 앱의 목적이다
   - 상태를 못 읽은 칸(`-`)만 체크할 수 없다
5. `감시 시작` — 첫 사이클은 현재 화면을 바로 분석하고, 이후 설정 범위 안에서
   **매번 무작위로 뽑은 간격**마다 새로고침 → 분석 → 판정
   - 감시 도중에 체크를 바꿔도 된다. 재시작 없이 다음 사이클부터 반영된다
   - 접속 대기열(NetFunnel)에 걸리면 화면이 확정될 때까지 기다린다 (§39)
6. 체크한 칸이 열리면 알림 → **그 칸 터치(1단계) → 하단 [예매] 터치(2단계)** → `RESERVED`
   - 2단계 버튼은 문구 `예매` **완전일치**일 때만 누른다. `예약대기신청` 이나
     `입석+좌석 예매` 가 같은 자리에 오면 좌석만 골라 둔 채(`SEAT_SELECTED`) 멈춘다 (§38-6-1)
   - 누르는 사이 남이 먼저 잡아 잔여석이 없으면 **뒤로 가기로** 되돌아가 감시를 계속한다
7. 알림을 누르면 앱으로 복귀 → 좌석 선택과 결제를 직접 진행

선택은 **저장하지 않는다.** 그 조회 결과 화면에만 의미가 있는 값이라 앱을 다시 켜면 비어 있다.

## 제한 사항

- **화면이 켜져 있고 앱이 foreground 일 때만** 감시한다. 홈으로 나가면 일시정지된다.
  WebView 가 사람 눈에 보여야 동작한다는 것이 대원칙 1의 따름정리다.
- 재조회 간격을 짧게 두면 요청이 그만큼 많아지고 **접속이 차단된다.**
  새로고침 한 번은 문서 + 번들 + 조회 API 전체라 AJAX 재조회보다 훨씬 무겁다.
- 로그인/본인확인/결제는 자동화하지 않는다. 세션이 만료되면 감시를 멈추고 알린다.
- 열차 식별 기준은 **열차 번호**다 (§38-4). 같은 출발 시각에 다른 열차가 있어서
  출발 시각을 주키로 쓸 수 없다.
- DOM selector 는 실제 사이트 구조에 맞춰야 한다. 파싱이 깨지면 고칠 곳은
  `webview/KtxSelectors.kt` 와 `webview/KtxParserScript.kt` 둘뿐이다
  → [`../docs/DESIGN.md`](../docs/DESIGN.md) §13, §38

## 이 폴더의 파일

| | |
|---|---|
| `app/` | 앱 모듈. 코드 지도는 [`../CLAUDE.md`](../CLAUDE.md) |
| `RELEASE.md` | 서명 키 / 버전 올리기 / APK 배포 |
| `INSTALL.md` | 최종 사용자에게 APK 와 함께 주는 안내문 |
| `keystore.properties.example` | 서명 설정 템플릿 |
| `keystore.properties` · `*.jks` · `dist/` | **커밋 금지.** `.gitignore` 에 있다 |
