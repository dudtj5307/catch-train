# Catch Train — 작업 지침

코레일(KTX) 조회 결과 화면을 감시하다가, 사용자가 체크해 둔 칸이 열리면 알리고
그 칸을 골라 [예매] 를 눌러 주는 도구. **클라이언트가 둘 있다** —
안드로이드 앱(WebView, `android/`)과 크롬 확장(MV3, `extension/`).

```
코레일 사이트에서 사용자가 직접 조회
  → 결과 목록을 DOM 으로 읽어 [열차 선택] 목록 생성
  → 사용자가 (열차 × 좌석등급) 칸 체크
  → 감시 시작: 페이지 새로고침(F5) → DOM 분석 → 체크한 칸이 AVAILABLE?
  → 알림 → 그 칸 터치(1단계) → 하단 바 [예매] 터치(2단계) → RESERVED
     (여기서 끝. 결제는 사용자)
```

---

## 저장소 구조 (모노레포)

한 레포에 클라이언트 둘. 나누는 기준은 **"코레일이 바뀌면 같이 깨지는가"** 다.
같이 깨지는 것은 공통에 한 벌만 두고, 플랫폼 사정은 각자 폴더로.

```
docs/        ← 공통. 설계 규칙 + 코레일 실측. 양쪽 코드가 §번호로 참조
shared/      ← 공통. selector 단일 출처 (아직 계획 단계)
CLAUDE.md    ← 공통. 이 파일
android/     ← 안드로이드 앱. **Gradle 루트가 여기다**
extension/   ← 크롬 확장. [예매] 까지 누른다 (M4). 실제 사이트 확인은 아직
```

**대원칙은 안드로이드 규칙이 아니라 제품의 규칙이다.** 확장이라고 예외가 되지 않는다.
확장에서 갈리는 지점(특히 `el.click()` 의 `isTrusted` 벽은 확장에도 그대로다)은
[`extension/README.md`](extension/README.md) 에 있다.

**사이트에 대한 사실을 폴더 안으로 복사하지 말 것.** `docs/DESIGN.md` 에 한 벌만 둔다.
복사하는 순간 코레일이 바뀔 때 한쪽이 반드시 뒤처진다 — 모노레포로 간 이유가 이것뿐이다.

## 이 문서의 규칙

이 파일에는 **코드를 읽어서 알아낼 수 없는 것만** 둔다.
클래스 목록·시그니처·구현 절차는 쓰지 않는다 — 코드가 정답이고, 여기 적으면 썩는다.

여기 남는 것: 왜 그렇게 했는가, 무엇을 하면 안 되는가, 실측으로 알아낸 사이트 동작.
상세는 [`docs/`](docs/) 로 보낸다.

| 문서 | 내용 | 언제 읽나 |
|---|---|---|
| [`docs/KTX-MIGRATION.md`](docs/KTX-MIGRATION.md) | **★ 지금 진행 중인 일.** 어디까지 했고 뭐가 남았나 | **작업을 시작할 때 먼저** |
| [`docs/DESIGN.md`](docs/DESIGN.md) | 설계 규칙 + 실측 메모. **코드 KDoc 이 `§번호` 로 참조한다** | 동작을 바꾸기 전 |
| [`docs/HISTORY.md`](docs/HISTORY.md) | 원설계 의도, 폐기된 모델, 로드맵 | 거의 안 읽어도 된다 |
| [`shared/README.md`](shared/README.md) | 공통화 계획 (selector 단일 출처) | 파서를 건드릴 때 |
| [`extension/README.md`](extension/README.md) | 확장에서 갈리는 지점, 정해야 할 것 | 확장을 건드릴 때 |
| [`android/README.md`](android/README.md) | 안드로이드 빌드와 쓰는 흐름 | |
| [`android/RELEASE.md`](android/RELEASE.md) | 서명 키 / 버전 올리기 / APK 배포 | 배포할 때만 |
| [`android/INSTALL.md`](android/INSTALL.md) | 최종 사용자에게 APK 와 함께 주는 안내문 | 배포할 때만 |

**`docs/DESIGN.md` 의 § 번호는 바꾸지 않는다.** 코드 주석 100여 곳이 참조한다.
내용을 고치는 것은 자유지만 번호를 다시 매기지 말 것. 절이 없어지면 번호를 비워 둔다.
(코드 KDoc 의 `DESIGN.md §N` 은 전부 `docs/DESIGN.md` 를 가리킨다. 예전에 루트에 있었다)

---

## 대원칙

바꾸려면 근거가 필요한 것들. 대부분 실측으로 얻었고, 어긴 흔적이 보이면 되돌린다.

### 1. 갱신은 새로고침(F5), 예매는 진짜 터치

**갱신**은 `WebView.reload()` 하나뿐이다. 모바일 폭에서는 [열차조회] 버튼이
`display:none` 인 조상(`div.btnWrap.btn_box`) 아래에 있어 **화면에 존재하지 않는다.**
`querySelector` 로는 찾아지지만 rect 가 0×0 이다. (§38-9)
조회 조건은 DOM 이 아니라 `localStorage["LS_TICKET_GENERAL"]` 에 있어서 새로고침해도
살아남고, SPA 가 그 값으로 같은 조회를 스스로 되풀이한다. 대원칙 4 는 그대로다.

**예매**는 예전 그대로 화면 좌표에 실제 `MotionEvent` 를 내려보낸다.
좌석 칸과 하단 [예매] 버튼은 실제로 화면에 보이기 때문이다.

| 안 되는 방법 | 이유 |
|---|---|
| 조회 API/URL 직접 호출 (`loadUrl`, `a[href]`) | 사람 조작에서 나올 수 없는 요청 → 사실상 항상 차단 |
| JS `el.click()` / `dispatchEvent` | `isTrusted=false` 합성 이벤트 |
| **[열차조회] 버튼 탐색을 되살리기** | 모바일에서 rect 가 0×0 이고, 옆의 `다음날 (…) 조회` 를 잘못 잡으면 **사용자가 보던 날짜가 아닌 다음날을 조회한다** |

따름정리: **WebView 가 화면에 보여야 동작한다.** 사람이 볼 수 없는 상태
(가려짐 / 화면 밖 / 백그라운드)면 앱도 갱신하지 않는다.

새로고침 한 번은 **문서 + 번들 + 조회 API** 전체다. AJAX 재조회보다 훨씬 무겁다.
간격을 좁히지 말 것 (대원칙 2). 코레일은 NetFunnel(`nf.letskorail.com`) 대기열까지 물려 있다.

**실측: 새로고침 → 목록 재렌더가 약 1초다.** 그래서 **"대기 시간 = 요청 간격" 이 아니다** —
대기가 0.3초면 사이클은 1.3초이고, 대기를 두 배로 늘려도 요청 수는 절반이 되지 않는다.
사람에게 보여 줄 값은 간격이 아니라 **분당 요청 수**다. 실제로 확장이 SRT 시절 기본값
(0.1~0.3초, 그때는 가벼운 AJAX 재조회였다)을 물려받은 채 **분당 46회**로 돌고 있었다.
**갱신 방식을 바꾸면 간격 기본값도 같이 본다.** (§38-9)

### 2. 실패 경로에 자동 재시도를 넣지 않는다

**SRT IP 차단은 실제로 일어났다. 코레일도 같다고 본다.** 재시도 한 번 = 요청 한 번이다.
실패는 "요청이 나갔는가" 로 나눠서, 나가지 않은 것만 다음 사이클에 다시 시도한다.
연속 실패 상한(`maxConsecutiveErrors`, `maxUnknownPages`, `maxSoldOutRetries`)을 늘리지 말 것.

같은 이유로 **짧은 간격으로 실제 사이트를 반복 테스트하지 않는다.**

**이 상한이 세는 것은 요청이지 판독이 아니다.** DOM 을 다시 읽는 것은 요청이 아니므로
몇 번을 해도 세지 않는다. 카운터가 하나 늘려면 새로고침이 실제로 한 번 더 나가야 한다.
(§39-5)

### 2-1. 화면이 확정되기 전에는 다음 사이클로 넘어가지 않는다

코레일에는 NetFunnel 대기열이 있다. 대기 화면은 `UNKNOWN_PAGE` 로 읽히는데,
**대기 중에 새로고침하면 대기 순번이 날아가서 대기가 영영 끝나지 않는다.**
기본 간격이 0.1~0.3초라 예전에는 대기 화면을 쉬지 않고 두들기고 있었다.

그래서 `UNKNOWN_PAGE` 가 나오면 판정하지 않고 **제자리에서 다시 읽는다**
(`WatchController.readSettledSnapshot`). 대기열인지 **알아보려 들지 않는다** —
목록이 나올 때까지 넘어가지 않으면 그것으로 충분하다. (§39)

지켜야 할 선: **`PageStatus.isSettled` 인 상태는 절대 기다리지 않는다.**
차단·로그인·세션만료를 3분씩 붙들면 안 되고, 목록이 보이는 정상 상황은
예전과 똑같은 시간에 끝나야 한다. 기다림이 붙는 것은 `UNKNOWN_PAGE` 하나뿐이다.

### 3. 자동 클릭은 [예매] 까지

좌석 선택·결제·결제정보 입력·CAPTCHA 우회·로그인 자동화는 전부 범위 밖이다.
`RESERVED` 로 넘어가면 앱의 역할은 끝난다.

2단계 버튼 문구는 **완전일치 허용목록(`예매`)** 으로만 고른다. `예약대기신청` 이나
`입석+좌석 예매` 가 같은 자리에 오는데, 그건 사용자가 고른 것이 아니다.
허용목록에 없으면 좌석만 골라 둔 채 멈추고(`SEAT_SELECTED`) 사람에게 넘긴다. (§38-6-1)

### 4. 조회 조건은 앱이 갖지 않는다

구간/날짜/시간은 사용자가 사이트에서 직접 넣는다. 앱이 감시하는 것은
**"화면에서 체크한 그 칸"**(`WatchSelection` = `(TrainKey, SeatClass)` 집합)이다.
조건을 두 곳에 입력하면 어긋났을 때 원인을 찾을 수 없고, 자동 클릭이 엉뚱한 칸을 잡는다.

이 선택은 **저장하지 않는다.** 그 조회 결과 화면에만 의미가 있는 값이다.

### 5. 되돌리기는 WebView 뒤로 가기만

안내 화면의 [확인] 버튼을 누르면 조회 폼이 새로 열려서
**사용자가 직접 넣어 둔 조회 조건이 전부 초기화된다.** 되돌리기 한 번이 감시 전체를 망친다.

### 6. 판정이 애매하면 막지 말고 통과시킨다

`UNKNOWN` 은 허용한다 (로그인 판정, 페이지 종류 판정 모두). 사이트 개편으로 마커 하나를
놓쳤을 뿐인데 앱이 영영 시작되지 않는 편이 더 나쁘다.

### 7. 감시 주기는 감시 대상 페이지 **밖**에서 관리한다

JS `setInterval` 은 쓰지 않는다. 간격은 `[min, max]` 범위에서 **사이클마다 무작위**로
뽑는다 — 정확히 같은 주기의 반복 요청은 자동화로 판단되어 차단된다.

알맹이는 셋이다. **(a) 타이머가 감시 대상 페이지 안에 있으면 안 된다,
(b) 간격은 사이클마다 무작위, (c) 중지가 즉시 먹혀야 한다.**
안드로이드에서는 Kotlin 코루틴이, 확장에서는 service worker 의 `await sleep(random)` +
`AbortController` 가 그 자리다 — content script 에 타이머를 두지 않는 한 (a) 는 지켜진다.
(이 문장은 안드로이드만 있을 때 "Kotlin 코루틴이 관리한다" 였다)

### 8. 레이어 경계

```
UI → ViewModel → WatchController → PageHost(=WebView)
                              ↘ KtxPageParser → domain
                              ↘ SelectionEngine → domain
                              ↘ MatchNotifier
```

- `domain/` 과 `SelectionEngine` 은 **Android 의존성 0**. 단위 테스트 대상이므로 유지할 것.
- **DOM selector 는 `webview/KtxSelectors.kt` 한 곳에만.** UI 도 컨트롤러도 selector 를 모른다.
- `evaluateJavascript()` 결과만 쓰는 단방향 흐름. `@JavascriptInterface` 브리지는 두지 않는다.

---

## 이 저장소에서 일할 때

- **git 이 있다.** origin = `https://github.com/dudtj5307/catch-train.git`.
  `main` = 통합 브랜치. `ktx` 는 KTX 전환 작업 브랜치였고 **`main` 과 같아졌다**(머지 완료) —
  지워도 된다.
  SRT 사이트 대응 마지막 버전은 태그 **`v0.1.1-srt`** 에 있다 (`git show v0.1.1-srt:<경로>`).
  SRT 는 폐지되었으므로 **SRT 코드를 살아있는 브랜치로 유지하지 않는다.** 태그면 충분하다.
- `android/keystore.properties` · `*.jks` · `android/dist/` 는 `.gitignore` 에 있다.
  **절대 커밋하지 말 것** — 실제 서명 비밀번호가 평문으로 들어 있다.
  (`.gitignore` 패턴에 슬래시가 없어 깊이와 무관하게 걸린다. 옮겨도 계속 무시된다)
- 세션 밖에서 파일이 바뀌어 있는 경우가 잦으므로, 덮어쓰기 전에 **반드시 다시 읽는다.**
  (git 이전에 실제로 코드가 날아간 적 있다)
- **Gradle 루트는 `android/` 다.** gradle 명령은 전부 그 폴더에서 실행하고,
  Android Studio 도 저장소 루트가 아니라 `android/` 를 연다.
- 툴체인은 로컬에 다 있다. `--offline` 로 컴파일·단위테스트·debug APK 빌드가 전부 된다.
  단 `assembleRelease` 는 `lintVital` 이 의존성을 받아와야 해서 `--offline` 이면 실패한다.
- `WatchControllerTest` 에서 **`advanceUntilIdle()` 을 쓰지 않는다.**
  `backgroundScope` 의 감시 루프가 돌지 않아 테스트가 멈춘다. `runCurrent()` / `advanceTimeBy()` 를 쓴다.
- 파싱이 깨졌을 때 고칠 곳은 두 파일뿐이다:
  `webview/KtxSelectors.kt`(selector·키워드), `webview/KtxParserScript.kt`(추출 로직).
  확인은 `chrome://inspect` 로 실제 WebView DOM 을 보고 한다.
  **selector 를 고치면 확장 쪽도 같이 봐야 한다** → [`shared/README.md`](shared/README.md)

```bash
cd android && ./gradlew --offline :app:testDebugUnitTest
```

```bash
cd android && ./gradlew --offline :app:assembleDebug
```

---

## 코드 지도 (안드로이드)

`android/app/src/main/java/dev/yslee/catchtrain/` — 이름으로 알 수 없는 것만 적는다.
(아래 표의 경로는 이 디렉터리 기준. 확장 쪽 구조는 [`extension/README.md`](extension/README.md))

| 위치 | 역할 |
|---|---|
| `watcher/WatchController.kt` | 감시 루프 전체. 조회→분석→판정→알림→(좌석 선택→예매)→대기. `readSettledSnapshot` 이 화면이 확정될 때까지 붙잡는다 (§39) |
| `watcher/WatchConfig.kt` | 루프 파라미터 (간격·타임아웃·재시도 상한). 값마다 근거가 KDoc 에 있다 |
| `watcher/WatchState.kt` | `WatchState` / `WatchError` / `ReserveStage` / `ReserveResult` / `WatchStatus`. UI 는 `WatchStatus` 만 본다 |
| `webview/PageHost.kt` | 감시 엔진이 보는 페이지 인터페이스. WebView 를 여기서 끊는다 (테스트 이음매). **예매는 `selectSeat`(1단계) + `confirmReserve`(2단계)** |
| `webview/KtxWebViewHost.kt` | `PageHost` 구현. 갱신=`reload()`, 예매=좌표 계산 → `MotionEvent` 터치 |
| `webview/KtxParserScript.kt` | WebView 안에서 실행할 JS 를 문자열로 생성 (최대 파일) |
| `webview/KtxSelectors.kt` | ★ selector / URL / 키워드 상수. 사이트가 바뀌면 여기부터 |
| `webview/KtxLoginScript.kt` | 머리말 링크로 로그인 여부 판정 (§27-1) |
| `webview/KtxPopupHost.kt` | `window.open` 자식 WebView 스택 (달력 팝업, §12-1) + JS 콘솔 오류 수집 (§38-10) |
| `domain/SelectionEngine.kt` | 순수 판정 로직. Android 없음 |
| `domain/TrainKey.kt` | 재조회 후에도 같은 열차를 알아보는 식별자 — **기준은 열차 번호** (§38-4) |

---

## 알아두면 헛수고를 막는 것

- **비로그인 상태에서도 조회가 되고 좌석 선택까지 된다.** 로그인을 요구하는 시점은
  예매를 누른 **뒤**다. 그래서 감시 시작 시점에 머리말 링크(`btnGoLogin`/`btnGoLogout`)로
  따로 확인한다. 본문 텍스트에서 "로그아웃" 을 찾으면 오판하고(로그인 화면 안내문에 그
  단어가 있다), `button.logoutBtn` 은 **클래스 이름이 고정이고 문구만 바뀌어** 항상
  로그인으로 읽힌다. (§38-7)
  - **메인 화면에 닿았을 때도 같은 판정을 돌려, 비로그인이면 로그인 화면으로 보낸다.**
    (§27-2, `KtxWebViewHost.guardMainPageLogin`) 앱이 스스로 URL 을 여는 유일한 자리다.
    **메인(`KtxSelectors.isMainPage`)에서만, `LOGGED_OUT` 이 확실할 때만** 한다 —
    조회 결과 화면에서 URL 을 갈아타면 사용자가 넣어 둔 조회 조건이 통째로 날아간다
    (대원칙 4·5). SPA 라 판정은 되풀이해 읽어야 하고, main↔login 되튐도 끊어야 한다.
- **로그인 표시는 폰 폭에서 화면에 하나도 없다.** 머리말(`ul.h_top_right`)이 통째로
  `display:none` 이라 `a.btnGoLogin` 의 rect 가 0×0 이다. [열차조회] 버튼과 같은
  함정이라, 로그인 판정은 **보이는지를 따지지 않고 DOM 에 있는지만** 본다.
  가시성으로 거르면 앱에서는 언제나 `UNKNOWN` 이 되어 확인이 통째로 죽는다. (§38-7)
- **좌석 칸 순서는 일반실이 왼쪽(`[0]`), 특실이 오른쪽(`[1]`)** 이다. **SRT 와 반대다.**
  매진 칸에는 등급 class(`gen`/`spe`)가 붙지 않아 위치 말고는 등급을 알 방법이 없다. (§38-3)
- **좌석 상태는 텍스트가 아니라 class 로 읽는다.** `sold_out_soon`(매진임박)은 **살 수
  있는 칸**인데 문구가 `특실(매진임박)` 이라 "매진" 부분일치에 걸린다. (§38-2)
- **모바일 폭에는 [열차조회] 버튼이 없다.** DOM 에는 있지만 조상이 `display:none` 이라
  rect 가 0×0 이다. 그래서 갱신이 새로고침(F5)이 되었다. 실제로 보이는 조회 버튼은
  절대 눌러선 안 되는 `다음날 (…) 조회` 하나뿐이다. (§38-9)
- **조회 조건은 `localStorage["LS_TICKET_GENERAL"]` 에 있다.** URL 에는 아무것도 없다.
  그래서 새로고침해도 사용자가 넣은 구간·날짜·시각·인원이 그대로 살아난다.
  **그래도 시작 페이지는 메인이다.** 결과 화면을 직접 열면 조회 조건이 없을 때
  사용자가 넣지 않은 기본값이 떠 버린다 (대원칙 4). 한 번 시도했다가 되돌렸다. (§10) (§38-9)
- **`onPageFinished` 는 목록이 그려진 시점이 아니다.** React SPA 라 문서를 받은 뒤에
  번들이 돌고 조회 API 를 쳐야 `li.tckList` 가 생긴다. 거기서 바로 분석하면
  좌석이 있어도 `NO_TRAIN` 으로 읽는다. (§38-9)
- **새로고침은 스크롤을 되살려서 화면을 맨 밑으로 튕긴다.** 되살릴 때의 문서에는
  아직 목록이 없어 짧고, 예전 오프셋이 문서 끝에 잘려 붙는다. 그래서
  `history.scrollRestoration='manual'` + 맨 위로를 **세 자리**에서 건다 —
  새로고침 직전(나가는 이력 항목) / `onPageFinished`(새 문서) / 목록이 그려진 뒤
  (짧던 문서에서 올린 것만으로는 부족하다). 한 자리라도 빼면 다시 튄다. (§38-9)
- **역/지역 선택 창은 `window.open` 팝업이 아니다.** `document.body` 에 붙는
  react-modal 이라 `KtxPopupHost` 가 관여하지 않고, 앱이 "위로 띄워" 줄 수도 없다.
  안 뜨면 로그 창의 **[역 진단]**(`STATION_PROBE`) 과 `PAGE_CONSOLE` 을 본다.
  순서는 **진단 → 역 버튼을 손으로 눌러 봄 → 진단**. (§38-10)
- **역 버튼이 반응하지 않는 원인은 넷인데 화면에서는 다 똑같아 보인다.**
  안 닿음 / React 까지 못 감 / 사이트가 막음(`stationDisabled`, 오류도 안 남는다) /
  창은 떴는데 안 보임. 넷을 가르기 전에 고치려 들지 말 것. (§38-10)
- **WebView 가 높이를 얻기 전에 문서를 받으면 `100vh` 가 0 으로 굳는다.** 코레일의
  역/날짜/인원 선택 창은 `.layerPopup { height: 100vh }` **하나로** 화면을 채우고
  오버레이에 인라인 기하가 없어 **대체 경로가 없다** — 그래서 통째로 납작해진다.
  열려 있는데 높이 0 이라 사람 눈에는 "아무 반응 없음" 이다. (§38-10)
  - 기다리는 코드는 **`KtxWebViewHost.loadStartUrl` 안**에 있다. Activity 쪽으로
    되돌리지 말 것 — 설정 화면의 [시작 페이지로] 는 **WebView 가 떼어진 상태**에서
    부르는 또 하나의 경로다. 호출부마다 두면 반드시 새는 곳이 생긴다.
  - 그래도 어긋나면 `buildViewportFixScript()` 가 `100vh` 를 직접 재서 픽셀값으로
    덮어쓴다. **어긋났을 때만** 손대고, 결과는 로그 `VIEWPORT_FIX` 에 남는다.
    이 앱이 페이지에 CSS 를 넣는 **유일한 자리**다. 대상을 늘리지 말 것.
- **코레일에는 매크로·개발자도구 감지가 있다** (`CODE : -8002`). 걸리면 화면 전체를
  덮는 안내 모달이 뜨고 **아무 버튼도 눌리지 않는다.** 실측 중 실제로 걸렸다. (§38-10)
- `window.open` 팝업(달력 등)은 `setSupportMultipleWindows=true` + `onCreateWindow` 로만
  `opener` 가 살아 있다. URL 만 가로채는 방식은 똑같이 깨진다.
  **`WebChromeClient` 는 WebView 당 하나뿐이므로 다른 곳에서 새로 걸지 말 것** —
  콘솔 훅도 `KtxPopupHost` 안에 있다. (§38-10)
- [예매] 를 눌러도 "잔여석없음" 이 뜰 수 있다. 화면 전환만으로는 성공과 구분되지 않으므로
  본문 문구를 한 번 더 확인해야 한다. (코레일 실제 문구는 아직 실측 전 — §38-8)
- **접속 대기열은 `UNKNOWN_PAGE` 로 읽힌다.** 대기 화면에는 목록도 로그인 마커도
  차단 문구도 없다. 그래서 대기열을 따로 판정하지 않고, `UNKNOWN_PAGE` 가 나오면
  목록이 나타날 때까지 **제자리에서 다시 읽는다.** 그동안 새로고침은 나가지 않는다. (§39)
  - 예산은 둘이다. 목록을 본 적 있으면 3분(`pageWaitMs`), 아직 못 봤으면
    10초(`pageWaitFirstMs`). **짧은 쪽을 지울 것** — 조회 결과 화면이 아닌 곳에서
    감시를 시작한 사용자를 3분씩 세워 두면 그게 사용자가 말한 "멍때림" 이다. (§39-4)
  - **대기열은 [예매] 를 누른 직후에 가장 잘 붙는다.** 2단계만 예산이 따로다
    (`confirmTimeoutMs`/`confirmSettleMs`). 1단계와 되돌리기는 화면 안에서 끝나는
    동작이라 6초 그대로다. (§39-6)
- **`WatchControllerTest` 에서 시간 예산을 검사하려면 `clock` 을 갈아 끼워야 한다.**
  기본값이 `System.currentTimeMillis` 라 `advanceTimeBy` 로 민 가상 시간이 안 보이고,
  예산이 영원히 만료되지 않아 `delay` 만 무한히 돈다. `clock = { testScheduler.currentTime }`.
  (`advanceUntilIdle()` 금지는 그대로) (§39-8)
- **대기 중인지 아닌지는 로그로 갈린다.** `PAGE_WAIT_START` 가 보이면 그 사이
  새로고침은 나가지 않았다. 정상이면 `PAGE_WAIT_*` 가 **한 줄도 안 나온다.** (§39-7)
