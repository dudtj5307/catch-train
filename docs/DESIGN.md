# 설계 규칙 (Catch Train)

이 문서는 **코드가 스스로 말하지 못하는 것**만 담는다: 왜 그 구조인가, 무엇을 하면
안 되는가, 실제 사이트에서 확인한 동작. 클래스 목록과 시그니처는 코드를 보면 된다.

> **§ 번호를 다시 매기지 말 것.** 코드 KDoc 100여 곳이 `DESIGN.md §N` 으로 이 문서를
> 가리킨다. 내용은 얼마든지 고쳐도 되지만 번호는 고정이고, 절이 사라지면 번호를 비워 둔다.
> 원설계 문서 전문과 폐기된 절(§1~§4, §30~§33, §35~§37)은 [`HISTORY.md`](HISTORY.md) 에 있다.

한눈에 보는 대원칙은 [`../CLAUDE.md`](../CLAUDE.md) 에 있다. 여기는 그 근거와 세부다.

> **이 문서는 클라이언트 양쪽(`android/` · `extension/`)의 공통 문서다.**
> 코레일 사이트에 대한 사실(§10 · §12-1 · §38 · §39)은 플랫폼과 무관하게 그대로 적용된다.
> 나머지 대부분은 안드로이드 구현 서술이다 — 확장에서 읽을 때는 §32 를 먼저 본다.

---

## §5. 기술 스택

**안드로이드 (`android/`)** — Kotlin / Jetpack Compose (Material 3) / ViewModel + StateFlow /
Coroutines / WebView / DataStore Preferences. minSdk 26 — `java.time` 과
`NotificationChannel` 을 desugaring 없이 쓰기 위해서다.

**크롬 확장 (`extension/`)** — Manifest V3. 아직 골격뿐이다 → [`../extension/README.md`](../extension/README.md)

## §6. 프로젝트 구조

저장소는 모노레포다: `android/` · `extension/` · 공통 `docs/` · `shared/` (§32).
Gradle 루트는 **`android/`** 다.

안드로이드 코드 지도는 [`../CLAUDE.md`](../CLAUDE.md) 참조. 패키지는
`ui / viewmodel / watcher / webview / parser / domain / notification / storage`.

## §7. 핵심 데이터 모델

```kotlin
Train(trainNumber, departureStation, arrivalStation, departureTime, arrivalTime,
      generalSeat: SeatStatus, firstClassSeat: SeatStatus)

SeatStatus  = AVAILABLE | WAITING | SOLD_OUT | UNKNOWN
SeatClass   = GENERAL | FIRST_CLASS

TrainKey(trainNumber, departureTime)          // 재조회 후 같은 열차를 알아보는 식별자
SeatSelection(trainKey, seatClass)
WatchSelection(seats: Set<SeatSelection>)     // 사용자가 체크한 칸들
MatchResult(matched, train, reason, matches: List<SeatMatch>)
```

`TrainKey` 의 기준은 **출발 시각**이다. 재조회하면 행 위치가 바뀌고 파서가 열차 번호를
읽지 못하는 경우도 있어서, 번호는 **양쪽 다 값이 있을 때만** 비교한다.
(한 구간의 조회 결과에서 출발 시각이 겹치는 편성은 없다는 전제)

`TrainKey` 는 **날짜를 갖지 않는다.** §19-2 에서 조회 조건을 잃으면 안 되는 이유가 이것이다.

`WatchCondition`(구간/날짜/시간범위/좌석등급) 모델은 폐기되었다. → [`HISTORY.md`](HISTORY.md)

## §8. 감시 상태

`IDLE · LOADING · ANALYZING · WAITING · MATCHED · RESERVED · ERROR · PAUSED · STOPPED`

- `RESERVED` = [예약하기] 를 눌러 결제 화면까지 갔다. 감시는 여기서 끝난다.
- `PAUSED` 는 원안에 없던 상태다. §24 의 lifecycle 일시정지를 사용자가 구분해서
  볼 수 있도록 추가했다.
- `keepsScreenOn` 은 감시 중 + `MATCHED` + `RESERVED` 에서 참이다. (§26)

UI 는 `WatchStatus` 스냅샷 하나만 보고 화면을 그린다.

## §9. 핵심 감시 Flow

```
START → (첫 사이클은 갱신 없이 현재 화면 분석)
  → DOM 분석 → Train[] → SelectionEngine
      ├ 체크한 칸이 AVAILABLE  → 알림 → 좌석 칸 터치 → [예매] 터치 → RESERVED (감시 종료)
      └ 아니면                 → 무작위 간격 대기 → 새로고침(F5) → 다시 분석
```

첫 사이클을 갱신 없이 시작하는 이유: 사용자는 이미 조회 결과를 열어 둔 상태에서
[감시 시작] 을 누른다. 요청을 한 번 아끼고 즉시 피드백을 준다.

## §10. 갱신 — 페이지 새로고침 (F5)

**이 앱에서 가장 중요한 구현 결정이다.** 페이지 갱신 방법은 하나뿐이고 대체 경로가 없다.

> **2026-08-29 변경.** 원래는 결과 화면의 [열차조회] 버튼 좌표에 진짜 터치를 내려보냈다.
> **모바일 폭에서는 그 버튼이 화면에 없다.** 근거와 실측은 §38-9.
> 아래 표의 `WebView.reload()` 줄은 SRT 시절의 판단이었고, 코레일에서는 성립하지 않는다.

여전히 막혀 있어 쓰지 않는 것들:

| 방법 | 결과 |
|---|---|
| 조회 API/URL 을 `loadUrl` / `a[href]` 로 직접 호출 | 사실상 항상 차단 |
| JS `el.click()` / `dispatchEvent` | `isTrusted=false` 합성 이벤트. 브라우저 입력 파이프라인을 거치지 않는다 |

`WebView.reload()` 를 쓸 수 있게 된 이유는 **조회 조건이 DOM 이 아니라
`localStorage["LS_TICKET_GENERAL"]` 에 있기 때문**이다. 다시 불러오면 React SPA 가
그 값으로 같은 조회를 스스로 되풀이한다. SRT 는 POST 결과 화면이라 reload 가 조건을
날렸지만, 코레일에서는 사용자가 넣어 둔 구간·날짜·시각·인원이 그대로 살아남는다.
**대원칙 4 는 그대로다** — 앱은 여전히 조회 조건을 갖지 않는다.

> **시작 페이지는 그래도 메인(`/ticket/main`)이다.** 새 문서로 `/ticket/search/list` 를
> 직접 열어도 SPA 가 `LS_TICKET_GENERAL` 로 화면을 복원하는 것은 확인했지만
> (2026-08-29 실측 — 조회 바 · `div.tckWrap` · 역 선택 버튼까지 살아난다),
> **저장된 조건이 없으면 기본값으로 그려진다.** 사용자가 넣지 않은 구간이 화면에 떠 있는
> 상태는 대원칙 4 가 피하려던 것 그대로다. 시작은 메인에서 사용자가 직접 조회한다.

```
ReloadScheduler                다음 사이클까지 대기 (범위 안에서 무작위)
  → buildPageKindScript        새로고침 전 목록 상태 기록 (rows/sig). 읽기만 한다
  → WebView.reload()           = F5
  → awaitPageFinished          onPageFinished (pageTimeoutMs)
  → buildPageKindScript 폴링    목록이 다시 그려졌는가 (researchSettleMs)
                               ← SPA 라 문서 로딩과 목록 렌더링은 별개다
```

2단계로 나뉘는 것이 핵심이다. `onPageFinished` 는 **문서를 받은 시점**일 뿐이고,
`li.tckList` 는 그 뒤에 번들이 돌고 조회 API 를 친 다음에 생긴다.
문서 로딩만 보고 분석하면 좌석이 있어도 `NO_TRAIN` 으로 읽는다.

값은 비싸졌다. 한 사이클이 **문서 + 번들 + 조회 API** 전체다. AJAX 재조회보다 요청이
크므로 **간격을 좁히지 말 것.** (대원칙 2 — 코레일은 `nf.letskorail.com`(NetFunnel)
대기열까지 물려 있다)

결과는 이렇게 나뉜다. **어느 쪽도 다른 방법으로 대체하지 않는다.**

| 결과 | 의미 | 이후 |
|---|---|---|
| `Updated` | 목록이 다시 그려졌다 | 정상 경로 |
| `Settled` | 시간 안에 목록이 안 보였다 (0건 조회일 수도 있다) | 일단 한 번 분석해 본다 (대원칙 6) |
| `REFRESH_FAILED` | 목록 화면에 닿지 못했다 (차단 안내/오류 화면 등) | 재시도 없이 즉시 중지 |
| `REFRESH_NOT_VISIBLE` | WebView 가 화면에 없다 | 요청이 나가지 않았으므로 다음 사이클에 재시도 |
| `Deferred` | 팝업이 열려 있어 일부러 건너뛰었다 | 오류가 아니다. 연속 오류로 세지 않는다 |

전제: **WebView 가 화면에 보여야 한다.** 사람이 볼 수 없는 화면은 새로고침하지 않는다.

**예매는 여전히 진짜 터치다.** 좌석 칸과 하단 [예매] 버튼은 실제로 화면에 있기 때문이다.
좌표 계산 → `MotionEvent`(DOWN → 60~160ms → MOVE ±1px → UP) → `buildTapConfirmScript`
확인의 순서는 §38-6 에 그대로 남아 있다.

## §11. ReloadScheduler

간격은 고정값이 아니라 **`[min, max]` 범위에서 사이클마다 무작위로** 뽑는다.
정확히 같은 주기로 요청이 반복되면 자동화로 판단되어 차단되기 쉬우므로,
밀리초 단위까지 흩어진 값을 쓴다. 사용자가 조정할 수 있는 범위는
`ReloadScheduler.MIN_INTERVAL_MS ~ MAX_INTERVAL_MS`.

루프는 단일 코루틴으로 유지한다(`waitForNext(interval, onRemaining)`). 취소가 명확하고
남은 시간을 UI 에 표시할 수 있다. 원안의 `schedule(action)/cancel()` 은 쓰지 않았다.

**짧은 간격을 오래 유지하면 접속이 차단된다.** 사이트 이용정책과 요청 제한을 준수할 것.

## §12. WebView 구조

`javaScriptEnabled` / `domStorageEnabled` / `javaScriptCanOpenWindowsAutomatically` /
`setSupportMultipleWindows(true)` / `CookieManager.setAcceptCookie(true)`.
User-Agent 는 기본 Android WebView 값을 그대로 쓴다.

### §12-1. 팝업 창 (`window.open`)

`setSupportMultipleWindows` 는 **반드시 true** 여야 한다.

SRT 메인의 출발일 달력은 `window.open()` 으로 열리고, 팝업 페이지가
**부모 창의 폼을 직접 고쳐서** 날짜를 돌려준다.

```js
// selectCalendarInfo.do
var target = parent.g_IFrame ? parent : opener;
function selectDateInfo(date) {
    var o = target.$('search-form');   // opener 가 없으면 여기서 TypeError
    o.dptDt.value = 'YYYY.MM.DD';
    // ...
    window.close();
}
```

false 로 두면 `window.open()` 이 같은 WebView 의 일반 이동으로 처리되어 `opener` 가
null 이 되고, 날짜를 눌러도 아무 일도 일어나지 않는다. (그 줄이 try 블록 밖이라
`window.close()` 까지 가지 못한다)

`opener` 연결은 `WebChromeClient.onCreateWindow` 가 넘겨주는 `WebView.WebViewTransport` 에
자식 WebView 를 꽂아 줄 때만 유지된다. `shouldOverrideUrlLoading` 으로 URL 만 가로채
다른 WebView 에 `loadUrl` 하는 방식은 `opener` 가 여전히 null 이라 **똑같이 깨진다.**

`KtxPopupHost` 가 이 창들을 스택으로 들고 있고, 화면에는 맨 위 창만 카드로 띄운다. (§22)

**팝업이 열려 있는 동안에는 자동 조회를 하지 않는다.** 갱신은 새로고침이라(§10)
오버레이가 덮여 있어도 그대로 나가고, **사용자가 팝업에서 고르던 것을 통째로 날린다.**
이 경우 `PageOutcome.Deferred` 로 이번 차례를 건너뛴다.
오류가 아니므로 연속 오류로 세지 않는다.

> 코레일의 **역/지역 선택 창은 여기 해당하지 않는다.** 그것은 `window.open` 팝업이
> 아니라 페이지 안의 react-modal 이라 `KtxPopupHost` 가 보지 못한다. → §38-10

## §13. DOM Parser

서버 API 를 직접 호출하지 않는다. WebView 안에서 **현재 화면의 DOM 을 읽는다.**

selector 가 틀려도 최대한 버티도록 **2단 전략**을 쓴다.

1. **표 헤더 기반 열 매핑** (기본) — `thead` 텍스트에서 `일반실`·`특실`·`출발`·`도착`·
   `예약대기` 를 찾아 열 인덱스를 만든다. 열 순서가 바뀌어도 동작한다.
2. **행 텍스트 휴리스틱** (헤더를 못 찾은 경우) — 행 안에서 `HH:MM` 두 개 +
   `예약하기`/`매진`/`예약대기` 키워드를 찾는다. 좌석 칸은 왼쪽부터
   **특실 → 일반실** 순으로 본다 (실제 표의 열 순서).

파싱이 깨지면 고칠 파일은 `webview/KtxSelectors.kt` 와 `webview/KtxParserScript.kt`
**둘뿐이다.** 확인은 `chrome://inspect` → 앱 WebView → DevTools 로 실제 DOM 을 보고 한다.
앱 안에서는 `로그 보기` 의 `TRAIN_COUNT` / `DOM_WARNING` / `PAGE_STATUS` 로 빠르게 본다.

## §14. JavaScript → Android 전달

`evaluateJavascript()` 결과만 사용하는 **단방향 흐름**. `@JavascriptInterface` 브리지는
두지 않는다 — 흐름이 한 방향으로 유지되고 공격면도 작다.

## §15. Parser Layer

```
WebView → Raw JSON → KtxPageParser → PageSnapshot(페이지 종류 + Train[])
```

UI 는 selector 를 알 필요가 없다. 원안은 `List<Train>` 반환이었으나 `PageSnapshot` 으로
바꿨다 — §27 의 에러 구분(로그인 필요 / 세션 만료 / 알 수 없는 페이지)을 열차 목록만으로는
표현할 수 없기 때문이다.

## §16~§17. (폐기) 조건 엔진 / 조건 우선순위

`ConditionEngine` 은 `SelectionEngine` 으로 대체되었다. 판정은 "체크한 칸이
`AVAILABLE` 인가" 하나뿐이다. → [`HISTORY.md`](HISTORY.md)

## §18. 좌석 조건 확장 (향후)

호차 / 창가·통로 등 좌석 위치 조건. 현재는 일반실·특실 등급까지만 다룬다.

## §19. 알림 시스템

조건을 만족하면 알림을 보내고, 중복 알림을 막는다(§20).
`예약대기` 는 **항상 발견으로 보지 않는다.** 즉시 예약이 아니라 성격이 다른 신청이고,
사용자가 고른 것은 그 칸의 [예약하기] 버튼이다.

### §19-1. 발견 후 예매 클릭

알림을 보낸 **뒤에**, 그 열차의 예매 버튼까지 눌러 준다. 누르는 방식은 화면 좌표에
`MotionEvent` 를 내려보내는 진짜 터치다 — **갱신이 새로고침으로 바뀐 뒤에도 예매만은
그대로다.** 좌석 칸과 [예매] 버튼은 실제로 화면에 보이기 때문이다. (§10, §38-9)
여기까지가 앱의 역할이고 **좌석 선택과 결제는 사용자가 직접 한다.**
설정에서 끌 수 있고, 끄면 알림까지만 한다.

> **코레일에서는 이 클릭이 두 단계다.** 좌석 칸을 눌러 고르고(1단계), 화면 하단에
> 나타난 예매 바에서 [예매] 를 누른다(2단계). 두 단계 사이의 확인과, 2단계를 누르지
> 않고 사람에게 넘기는 경우는 **§38-6 / §38-6-1** 에 있다.
> 아래 제약은 두 단계 모두에 그대로 적용된다.

잘못된 좌석을 잡는 사고를 막기 위한 제약:

| 규칙 | 이유 |
|---|---|
| 탐색 범위는 매칭된 **그 편성의 그 좌석 등급 칸 안**으로 제한 | 목록 전체에서 찾으면 다른 열차의 칸을 누를 수 있다 |
| 편성은 위치가 아니라 **내용 요약값(rowKey)** 으로 다시 확인 | 분석과 클릭 사이에 목록이 갱신되면 위치가 어긋난다 |
| 그 위에 **열차 번호**를 한 번 더 대조 | 같은 시각에 다른 편성이 있다 (§38-4) |
| 좌석 칸을 특정하지 못하면 **누르지 않는다** | 일반실/특실 혼동 방지 |
| `예약대기` / 매진 칸은 자동으로 누르지 않는다 | 즉시 예약이 아니다 |
| 같은 (날짜, 열차, 좌석등급)에 **두 번 시도하지 않는다** | 같은 열차를 중복으로 잡지 않는다 |

### §19-2. [예약하기] 를 눌렀는데 "잔여석없음" 이 뜨는 경우

표에 좌석이 열려 보여서 눌러도, 그 사이에 다른 사람이 먼저 잡으면 예약 화면 대신
안내 화면이 뜬다. 취소표를 노릴 때는 드문 일이 아니다.

```html
<!-- /hpg/hra/02/confirmReservationInfo.do?pageId=TK0101030000 -->
<div class="box2 val_m tal_c"><span class="mgl20">잔여석없음</span></div>
<div class="tal_c">
  <a href="#none" onclick="... /hpg/hra/01/selectScheduleList.do ..."
     class="btn_large btn_blue val_m"><span>확인</span></a>
</div>
```

문제는 **화면 전환 자체는 정상적으로 일어난다**는 것이다. `onPageFinished` 만 보면
예약 성공과 구분되지 않는다. 전환된 화면의 본문 문구를 한 번 더 확인해야 한다.

되돌리기는 **WebView 뒤로 가기만** 쓴다. 화면의 [확인] 버튼은 누르지 않는다.
그 버튼은 `selectScheduleList.do` 를 새로 여는 링크라, **사용자가 사이트에서 직접
넣어 둔 조회 조건(구간/날짜/시간)이 전부 초기화된다.** 조건이 사라진 조회 폼에서
다음 [조회하기] 를 누르면 엉뚱한 결과를 보게 되고, `TrainKey` 는 날짜를 갖고 있지
않으므로(§7) 출발 시각이 같은 다른 날짜의 열차를 그 열차로 착각할 수도 있다.
되돌리기 한 번이 감시 전체를 망치는 셈이다.

리다이렉트로 안내 화면이 두 칸 쌓인 경우까지만(`MAX_BACK_STEPS`=2) 더 물러난다.
되돌아가지 못하면 그 화면에서 다른 것을 더 누르지 않고 **감시를 멈춘다** —
그 화면에는 [조회하기] 가 없어 더 진행해도 요청만 헛돌기 때문이다.

| 규칙 | 이유 |
|---|---|
| "발견"으로 치지 않는다 (`stopOnMatch` 여도 멈추지 않는다) | 좌석이 실제로 열린 것이 아니다 |
| 오류로 세지 않는다 | 남이 먼저 잡은 것뿐이다. 앱이 실패한 게 아니다 |
| 같은 칸을 **다음 사이클에** 다시 눌러 볼 수 있다 | 취소표 감시에서는 흔한 일이라 한 번에 포기하지 않는다 |
| 다만 `maxSoldOutRetries`(기본 3회) 까지만 | 표시가 계속 어긋나는 칸이면 요청만 늘어난다 |
| 실패한 자리에서 곧바로 다시 누르지 않는다 | 요청 폭주 = 차단 위험 (§10) |

문구 하나만 보고 단정하지 않는다. 예약 결과 URL 이거나 열차 목록 표가 사라진 화면일
때만 실패로 본다. 본문은 `innerText` 로만 읽으므로 `<script>` 안의 안내 문자열에는
걸리지 않는다.

관련 코드: `KtxSelectors.RESERVE_FAILED_MARKERS`, `KtxParserScript.buildReserveResultScript()`,
`KtxWebViewHost.dismissReserveResult()`, `WatchController.handleSoldOut()`

### §19-3. 결제 재촉 알림

[예약하기] 가 눌려 결제 화면까지 갔더라도, 사용자가 알아채지 못하면 아무 소용이 없다.
SRT 결제에는 제한 시간이 있어서 그 사이 화면을 못 보면 잡아 둔 좌석이 그대로 풀린다.
좌석 발견 알림(§19)은 **한 번 울리고 마는** 알림이라 주머니 속에서 놓치기 쉽다.

그래서 `RESERVED` 로 넘어간 순간부터 **10초마다**
(`WatchConfig.reserveReminderIntervalMs`) 소리와 진동을 다시 울린다.
다만 **10분**(`WatchConfig.reserveReminderMaxDurationMs`)까지다. SRT 가 그때 좌석을
도로 풀기 때문에, 그 뒤의 재촉은 이미 없는 표를 두고 재촉하는 꼴이 된다.

| 규칙 | 이유 |
|---|---|
| 첫 재촉은 10초 **뒤에** 온다 | 넘어간 그 순간에는 좌석 발견 알림이 방금 울렸다 |
| 알림 ID 하나(`ALERT_NOTIFICATION_ID`)를 계속 갱신한다 | 알림 줄이 쌓이지 않게. `setOnlyAlertOnce(false)` 라 갱신마다 다시 울린다 |
| 채널을 따로 둔다 (`srt_watcher_reserve_alert`) | 알람 소리를 쓰고, 사용자가 이 알림만 따로 조절할 수 있게 |
| 알림과 별개로 `Vibrator` 로도 진동한다 | 제조사에 따라 같은 ID 갱신 시 소리/진동을 생략한다. 알림 권한이 없어도 진동은 간다 |
| `setOngoing(true)` | 스치기만 해도 사라지면 재촉이 안 된다 |
| 횟수가 아니라 시간으로 끊는다 (10분) | 좌석이 풀리는 기준이 시간이다. 간격을 바꿔도 멈추는 시각은 그대로여야 한다 |
| 마지막 재촉은 9분 50초 | 10분에 걸리는 회차는 보내지 않고 멈춘다. 이미 풀린 좌석을 재촉할 이유가 없다 |
| 만료로 멈출 때도 알림을 걷는다 | `setOngoing(true)` 라 손으로 지울 수 없다. 남겨 두면 영영 붙어 있는 알림이 된다 |
| **`pause()` 로는 멈추지 않는다** | 앱을 벗어나 있을 때야말로 이 알림이 필요한 순간이다 |

멈추는 경로는 넷뿐이다: [감시 종료] / [알림 끄기](재촉만 중단, 예약 상태는 유지) /
[계속 감시] / 감시 재시작. **알림을 그냥 탭해서 돌아오는 것은 멈춤이 아니다** —
화면만 보고 다시 딴짓을 할 수 있기 때문이다.
(예약 직후 화면에서 사용자가 실제로 누를 수 있는 것은 [알림 끄기] 와 감시 재시작 둘이다.
그 상태에서 [감시 종료] 는 이미 멈춘 감시를 멈추는 버튼이라 화면에서 뺐다 — §21)

재촉 루프(`reminderJob`)는 감시 루프(`loopJob`)와 별개다. [예약하기] 뒤 감시 루프는
끝나지만 재촉은 그때부터 시작되고, `pause()` 에도 살아남아야 하기 때문이다.

**한계:** `viewModelScope` 코루틴이라 앱 프로세스가 살아 있는 동안에만 돈다.
`RESERVED` 는 화면을 켜 두므로(§26) 보통은 괜찮지만, 사용자가 다른 앱을 오래 쓰면
시스템이 프로세스를 얼릴 수 있다. 확실히 하려면 foreground service 나 `AlarmManager`
가 필요하다 — 범위 밖이다.

관련 코드: `MatchNotifier.notifyReserveReminder()`, `NotificationHelper.ALERT_CHANNEL_ID`,
`WatchController.startReserveReminder()`, `WatchStatus.reserveAlerting`,
`MainActivity.handleNotificationIntent()`

## §20. 중복 알림 방지

`MatchKey(date, trainNumber, seatClass)` 로 최근 발견을 기억한다.
같은 칸이 계속 열려 있어도 다시 알리지 않고, `SOLD_OUT` 으로 바뀌었다가 다시
`AVAILABLE` 이 되면 새 알림을 보낸다.

## §21. 감시 화면 UI

헤더는 한 줄로 접혀 있고(`CATCH TRAIN` · 상태등 · 상태 이름 · 감시 간격),
`⌄` 로 주소 줄 / 선택 요약 / 감시 상태를 펼친다. `⚙` 는 설정.
**좌석을 발견한 카드는 접힌 상태에서도 계속 보인다.**

펼침 줄은 `[←] 주소 입력칸 [⟳]` 이다. 주소칸은 편집할 수 있고 키보드의 [이동] 으로만
페이지를 옮긴다. 화면 주소를 따라가되 **편집 중에는 따라가지 않는다** — 감시 중에는
새로고침마다 주소가 다시 흘러들어와 입력 중인 글자를 덮어쓴다. 초점을 잃으면 고치던
것을 버린다.

`←` 와 주소 이동은 **조회 결과 화면을 떠나면 사용자가 넣어 둔 조회 조건을 날린다**
(대원칙 4·5). 그래도 두는 이유는 사용자가 누른 것이기 때문이다. 앱이 스스로 부르는
자리는 여전히 `guardMainPageLogin` 하나뿐이다(§27-2). `⟳` 는 감시 루프와 같은
`WebView.reload()` 라 조회 조건이 살아남는다(§38-9).

`열차 선택` 패널의 `다시 읽기` 는 **조회 요청을 보내지 않고 DOM 만 읽는다**
(`WatchController.scanTrains`). 몇 번을 눌러도 차단 위험이 없다.

- 한 줄이 한 편성, 오른쪽 좌석 칸은 **특실 / 일반실** 순 (사이트와 동일)
- **매진인 칸도 체크할 수 있다.** 풀리기를 기다리는 것이 이 앱의 목적이다
- 상태를 읽지 못한 칸(`UNKNOWN`, `-`)만 체크할 수 없다
- 감시 중에 체크를 바꿔도 된다 — `updateSelection()` 으로 다음 사이클부터 반영된다.
  재시작하지 않는다(재시작하면 요청이 한 번 더 나가고 알림 이력이 사라진다)

### §21-1. 화면에 두지 않는 것

앱 화면은 바로 아래 코레일 WebView 를 덮는 자리다. **코레일 화면이 이미 보여 주는 것과
지금 누를 수 없는 것은 두지 않는다.** 자리를 먹는 만큼 정작 봐야 할 페이지가 밀린다.

| 뺀 것 | 이유 |
|---|---|
| 예매 뒤 "[예매] 를 눌렀습니다 / 열차·좌석 등급 / 직접 진행하세요" 안내 | 넘어간 예약 화면이 그대로 보여 준다. 남긴 것은 코레일 화면에 없는 [🔕 알림 끄기] 하나뿐이고, 재촉이 울리지 않으면(§19-3) 카드 자체가 안 뜬다 |
| 예매 뒤 [감시 종료] | 그 시점에 감시 루프는 이미 끝나 있다. 다시 시작하는 것은 아래 조작 바의 [감시 시작] 이 맡는다 |
| `ERROR` 상태의 [중지] | 오류가 나면 루프는 이미 멈춘다. 남는 선택은 [다시 시도] 뿐이다 |

## §22. WebView 화면

감시 중에도 사용자가 실제 SRT 페이지를 본다. 상단에 상태바.

`window.open()` 으로 열리는 창(§12-1)은 화면 전체를 덮는 카드로 띄운다.
어두운 배경은 터치를 삼키기만 하고 **눌러도 닫히지 않는다** — 날짜를 고르다 잘못 눌러
닫히면 곤란하기 때문이다. 닫는 길은 [닫기], 뒤로가기, 페이지 스스로의 `window.close()` 셋뿐.

## §23. 설정 저장

DataStore Preferences 에 저장하는 것은 **감시 동작뿐**이다:
재조회 간격 범위 / 알림 사용 / [예약하기] 자동 클릭 / 발견 시 감시 중지.

**감시 대상(`WatchSelection`)은 저장하지 않는다.** 그 조회 결과 화면에만 의미가 있는
값이라 앱을 다시 켜면 비어 있다. `다시 읽기` 로 목록을 새로 받으면 그 결과에 없는
열차의 체크는 자동으로 정리된다.

## §24. 감시 Lifecycle

`Activity ON_STOP → WatchController.pause()`, `ON_START → resume()`.
단, §19-3 의 결제 재촉만은 `pause()` 로 멈추지 않는다.

## §25. 백그라운드 감시 (미지원)

일반 Activity/WebView 를 백그라운드에서 1~2초 간격으로 계속 돌리는 것은 안정적인
구조가 아니다. 필요해지면 Foreground Service 를 검토한다 — Android 버전별 백그라운드
실행 제한과 배터리 정책을 함께 고려해야 한다.

## §26. 화면이 꺼진 상태

**감시 중에는 화면이 꺼지지 않게 유지한다** (`FLAG_KEEP_SCREEN_ON`).
화면 시간 초과로 감시가 조용히 멈추는 것을 막기 위해서다.
`RESERVED` 에서도 유지하는 이유는 예약 화면에 제한 시간이 있기 때문이다.
사용자가 홈으로 나가면(ON_STOP) 감시는 §25 대로 그대로 멈춘다.

## §27. 에러 처리

`WatchError` 로 구분한다: `NETWORK_ERROR` `PAGE_LOAD_ERROR` `DOM_PARSE_ERROR`
`LOGIN_REQUIRED` `SESSION_EXPIRED` `UNKNOWN_PAGE` `REFRESH_FAILED`
`REFRESH_NOT_VISIBLE` `BLOCKED`. 각각 사용자에게 보일 `title`/`guide` 를 갖는다.

### §27-1. 감시 시작 전 로그인 확인

**SRT 는 비로그인 상태에서도 조회가 된다.** 조회 결과 표도, 표 안의 `예약하기` 버튼도
그대로 보인다. 로그인을 요구하는 시점은 `예약하기` 를 누른 **뒤**다. (2026-08-23 실측)

그래서 §13 파서의 `LOGIN_REQUIRED` 로는 부족하다. 그 값은 "지금 보고 있는 화면이 로그인
화면인가" 를 뜻하고, 비로그인 사용자가 조회 결과 화면에 있으면 멀쩡히 `TRAIN_LIST` 가
나온다. 그대로 감시를 시작하면 이렇게 된다.

```
좌석이 열림 → 알림 → [예약하기] 클릭 → 로그인 화면으로 튕김 → 좌석은 남이 가져감
```

몇 시간을 기다린 그 한 번에서 실패하므로, **감시 시작 버튼을 누른 시점에** 한 번
확인하고 아니면 시작하지 않는다.

### 감시 중에도 사이클마다 확인한다

세션은 서버에서 풀린다. 시작할 때 로그인되어 있었다는 것이 한 시간 뒤에도 그렇다는
뜻이 아니다. 풀린 채로 감시가 계속 돌면 화면상 조회도 되고 좌석도 보이지만 —
**시작 전에 막으려던 그 실패가 그대로 일어난다.**

그래서 사이클마다 DOM 분석 직후에 한 번 더 본다(`ensureStillLoggedIn`).
`LOGGED_OUT` 이면 **즉시 멈추고** 화면 오류(`SESSION_EXPIRED`)와 알림
(`MatchNotifier.notifyWatchStopped`)으로 알린다. `UNKNOWN` 은 그대로 통과시킨다.

매 사이클 해도 되는 이유는 **비용이 사실상 없기 때문**이다. 이 확인은 머리말 링크
몇 개를 보는 DOM 읽기 하나라 **요청이 나가지 않는다.** 한 사이클의 진짜 비용은
새로고침(문서 + 번들 + 조회 API)이고, 그 옆에서 JS 한 번은 없는 것이나 같다.
차단 위험(대원칙 2)도 늘지 않는다.

재확인(2회 연속 확인 후 중지)은 **하지 않는다.** 머리말은 서버가 그려 보내므로
어중간한 중간 상태가 없고, 이 확인은 화면이 조회 결과 화면으로 판정된 **뒤에만**
불린다. 대기열·오류 화면이면 머리말이 통째로 없어 `UNKNOWN` 이 된다.

판정(`KtxLoginScript`)은 머리말 영역(`ul.h_top_right` → `div.header_top` → ...) 안의
**링크만** 본다. 코레일은 이 자리의 링크가 상태에 따라 통째로 바뀌어서
(`a.btnGoLogout` / `a.btnGoLogin`) 상태와 1:1 이다. 자세한 것과 함정은 §38-7.

| 결과 | 조건 | 감시 시작 |
|---|---|---|
| `LOGGED_IN` | 로그아웃 링크만 있음 | 허용 |
| `LOGGED_OUT` | 로그인 링크만 있음 | **막고 안내** |
| `UNKNOWN` | 둘 다 없거나 둘 다 있음 | 허용 |

- **화면에 보이는지는 따지지 않는다.** 폰 폭에서는 머리말이 통째로 `display:none` 이라
  보이는 로그인 표시가 하나도 없다. 가시성으로 거르면 언제나 `UNKNOWN` 이다. (§38-7)
- **문서 전체 텍스트에서 "로그아웃" 을 찾지 않는다.** 로그인 화면 본문에
  "로그인 후 1시간 동안 입력이 없을 경우 자동으로 로그아웃됩니다." 라는 안내가 있어서,
  비로그인 상태를 로그인으로 잘못 읽는다. (2026-08-23 실측)
- 문구는 공백을 지운 뒤 **완전 일치**로 비교한다. 부분 일치는 "간편로그인 설정" 같은
  메뉴에 걸린다.
- href/onclick 의 `logout` / `selectLoginForm` 도 함께 본다. 다국어 화면에서도 통한다.
- `UNKNOWN` 일 때 **막지 않는다.** 사이트 개편으로 마커를 놓친 것뿐인데 앱이 영영
  시작되지 않는 편이 더 나쁘다. (§28 과 같은 원칙)

DOM 만 읽으므로 요청이 나가지 않는다 = 차단 위험이 없다.

### §27-2. 메인 화면에서 비로그인이면 로그인 화면으로 보낸다

§27-1 이 **감시 시작 시점**의 확인이라면, 이쪽은 **메인 화면에 닿은 시점**의 확인이다.
같은 스크립트(`KtxLoginScript`)를 쓰지만 부르는 곳도 결과도 다르다.

| | §27-1 | §27-2 |
|---|---|---|
| 언제 | 사람이 [감시 시작] 을 눌렀을 때 | 메인 문서의 `onPageFinished` 마다 |
| `LOGGED_OUT` 이면 | 시작하지 않고 안내 | `/ticket/login` 으로 보낸다 |
| 로그 | `LOGIN_STATE` | `LOGIN_REDIRECT` |

로그인은 예매를 누른 **뒤에야** 요구되기 때문에(§27-1), 비로그인인 줄 모른 채 조회부터
하다가 좌석이 열린 그 순간에 튕기는 것이 가장 비싼 실패다. 아직 아무것도 하지 않은
메인에서 미리 보내면 그 실패가 아예 생기지 않는다.

구현은 `KtxWebViewHost.guardMainPageLogin`. 지키는 선이 넷이다.

- **메인에서만 한다.** `KtxSelectors.isMainPage` 가 참일 때만 부른다 —
  경로 끝(`/ticket/main`)으로만 판정하고 쿼리·해시는 떼어 낸다.
  조회 결과 화면에서 URL 을 갈아타면 사용자가 넣어 둔 조회 조건이 통째로 날아간다
  (대원칙 4·5). 메인이더라도 **목록이 그려져 있으면 그만둔다.**
- **확실할 때만 보낸다.** `LOGGED_OUT` 하나에만 반응한다. `UNKNOWN` 은 그대로 둔다
  (§27-1 과 같은 원칙).
- **판정을 되풀이해 읽는다.** React SPA 라 `onPageFinished` 시점에는 머리말이 아직
  없을 수 있다 (§38-9 와 같은 함정). 0.2초 간격으로 최대 10번, 결론이 나면 즉시 멈춘다.
  한 번만 보면 언제나 `UNKNOWN` 이라 확인이 통째로 죽는다.
- **되튐을 끊는다.** 로그인 직후 사이트가 메인으로 돌려보냈는데 머리말이 아직 비로그인인
  채라면 main↔login 을 무한히 오갈 수 있다. 보낸 지 5초 안이면 한 번 건너뛴다.

새 문서가 시작되면(`onPageStarted`) 앞 문서의 확인은 취소한다. 늦게 온 판정으로 사용자가
이미 옮겨 간 화면을 끌고 가면 안 된다. 확인 자체는 DOM 만 읽으므로 요청이 늘지 않고,
실제로 여는 것은 사용자가 눌러도 갈 수 있는 로그인 화면 한 번뿐이다.

## §28. DOM 변경 대응

selector 를 코드 곳곳에 하드코딩하지 않는다. **`KtxSelectors` 한 곳에 모으고**,
사이트가 바뀌면 Parser 계층만 고친다. UI 와 감시 엔진은 selector 를 모른다.

판정이 애매할 때는 막지 말고 통과시킨다 (§27-1 의 `UNKNOWN` 과 같은 이유).

## §29. 로그 시스템

`[18:42:11] TRAIN_COUNT=8` 형태의 상세 로그를 링 버퍼(300줄)에 쌓고 `로그 보기` 로 보여준다.
파싱이 깨졌을 때 `TRAIN_COUNT` / `DOM_WARNING` / `PAGE_STATUS` 가 첫 단서다.

### §29-1. 로그는 폰 밖으로 나갈 수 있어야 한다

실기기에서만 나는 문제를 고치려면 로그를 **PC 로 옮겨야** 한다. 그래서 두 가지를 둔다.

- **[복사]** — 버퍼 전체를 클립보드에 담는다. (`WatchLogger.dump()` 와 같은 순서)
- **끌어서 고르기** — 일부만 옮길 때.

두 번째 때문에 로그 목록은 `LazyColumn` + 줄마다 `Text` 가 **아니다.** 그 구조는 화면 밖
줄이 조합에서 빠져서 고를 수 있는 범위가 보이는 화면까지로 잘리고, 줄 경계에서 선택이
끊긴다. 전체를 문자열 하나로 만들어 `SelectionContainer` 안의 `Text` 하나에 담고
스크롤은 바깥에서 준다. 버퍼가 300줄로 묶여 있어 한 번에 배치해도 부담이 없다.

새 줄이 붙으면 맨 아래로 따라가되, **사용자가 위로 올려 둔 동안에는 따라가지 않는다** —
끌어서 고르는 중에 화면이 움직이면 선택이 끊긴다.

## §32. 크롬 확장과의 공통화

`domain/` 과 `SelectionEngine` 에 Android 의존성을 넣지 않는 제약의 실질적 이유가 여기에 있다.
DOM Parser 는 플랫폼마다 따로 두더라도 **판정 로직은 규칙이 같아야 한다.**

2026-08-30, 저장소를 모노레포로 바꾸면서 확장 작업이 시작됐다
(`android/` · `extension/` · `shared/`). 나누는 기준은 **"코레일이 바뀌면 같이 깨지는가"**:

- **같이 깨지는 것** = 이 문서(§10 · §12-1 · §38 · §39)와 selector → `docs/` 와 `shared/`.
  한 벌만 두고 양쪽이 참조한다. 복사하면 반드시 한쪽이 뒤처진다.
- **플랫폼 사정** = 그 밖의 절 대부분(§5 · §21~§26 등은 Android 구현 서술이다) → 각 폴더.

확장에서 그대로 옮겨오면 안 되는 것은 [`../extension/README.md`](../extension/README.md)
"안드로이드와 갈리는 지점" 에 있다. 특히 **`el.click()` 의 `isTrusted` 벽은 확장에도 그대로**라
`MotionEvent` 자리는 다시 설계해야 한다.

## §34. 가장 중요한 설계 원칙

전문은 [`../CLAUDE.md`](../CLAUDE.md) "대원칙". 코드가 참조하는 번호만 남긴다.

| | 원칙 |
|---|---|
| §34-1 | **WebView 와 감시 엔진 분리.** WebView 는 페이지를 보여주고 DOM 을 줄 뿐, 감시 로직은 Native 가 갖는다 |
| §34-2 | **DOM Parser 와 판정 엔진 분리.** Parser = HTML→Data, SelectionEngine = Data→Match |
| §34-3 | **주기는 Native 가 관리.** JS `setInterval` 을 쓰지 않는다 |
| §34-4 | **조건 만족 시 즉시 감시 중지.** 불필요하게 계속 요청하지 않는다 |
| §34-5 | **자동 예약보다 "감지"가 핵심.** [예약하기] 까지만 누르고, 좌석 선택·결제·CAPTCHA·로그인 자동화는 범위 밖 |

---

## §38. 코레일(KTX) 조회 결과 DOM — 실측

2026-08-29, `https://www.korail.com/ticket/search/list` 의 실제 DOM 을 받아 확인했다.
(동탄 → 김천구미, KTX-산천 10편성, 로그인 상태, 한 칸이 이미 선택된 화면)

### §38-1. 표가 아니라 리스트다

SRT 는 `<table>` 이었지만 코레일은 `<ul><li>` 다. **헤더 행이 없다.**
SRT 파서의 "표 헤더 텍스트로 열 위치를 찾는" 휴리스틱(§13)은 여기서 쓸 수 없다.

```
div.tckWrap > ul > li.tckList.clear          ← 한 편성
  div.tck_inner
    div.info_inner.fl-l > div.info_box
      div.tit_box > div.flag_wrap
        span.train_sancheon_ticket > span.blind   "KTX-산천"   ← 열차 종류
        span.num                                  "305"        ← 열차 번호
      div.data_box.right
        h3.txt_bk  <span>동탄</span> → <span>김천구미</span> <span>(07:11 ~ 08:17)</span>
        p.s_txt    "소요시간: 1시간 6분"
    div.price_box.fl-l.<등급/상태>   ← 좌석 칸 (편성마다 2개)
      div.inner.type02 > a[href="#none"]
        p.txt_ch     "일반실"
        p.txt_price  "25,700원"
```

### §38-2. 좌석 상태는 텍스트가 아니라 `price_box` 의 class 로 읽는다

실측에서 나온 class 조합 전수 (`price_box fl-l` 뒤에 붙는 것):

| class | 뜻 | 예약 가능? |
|---|---|---|
| `gen` | 일반실, 예약 가능 | ○ |
| `spe` | 특실, 예약 가능 | ○ |
| `sold_out_soon` | **매진임박 — 아직 살 수 있다** | ○ |
| `sold_out` | 매진 | ✕ |
| `sold_out_wait` | 매진 (같은 편성에 예약대기가 있는 경우) | ✕ |
| `wait` | 예약대기 | 발견으로 보지 않는다 (§18 과 동일) |
| `active` | **사용자가 지금 선택한 칸** | 상태가 아니라 선택 표시 |

`spe sold_out_soon` 처럼 등급과 상태가 함께 붙는다.

> **텍스트로 판정하면 안 된다.** `sold_out_soon` 칸의 문구는 `특실(매진임박) 37,200원`
> 이라서 "매진" 을 부분일치로 찾으면 **살 수 있는 칸을 매진으로 오판한다.**
> SRT 파서의 텍스트 기반 `SeatParser` 를 그대로 가져오면 여기서 깨진다.

### §38-3. 등급은 위치로 판정한다 — `[0]=일반실, [1]=특실`

`sold_out` / `sold_out_wait` / `wait` 에는 등급 class(`gen`/`spe`)가 **붙지 않는다.**
매진이면 등급 문구도 없어서(`매진` 한 단어) 그 칸이 일반실인지 특실인지 알 방법이 없다.

그래서 등급은 `li` 안의 `price_box` **순서**로 정한다.

**SRT 와 순서가 반대다.** SRT 는 특실이 왼쪽이었지만 코레일은 **일반실이 왼쪽**이다.

`li.tckList` 와 `price_box` 에 `data-*` 속성은 **하나도 없다.** 안정적인 식별 훅이 없어서
클래스·텍스트·위치 말고 기댈 것이 없다.

### §38-4. 같은 출발 시각에 다른 열차가 있다 — `TrainKey` 전제가 깨진다

실측 10편성 중 두 쌍이 시각이 겹쳤다.

| 출발 | 열차 |
|---|---|
| 07:11 | KTX-산천 **305**, KTX-산천 **381** |
| 18:55 | KTX-산천 **353**, KTX-산천 **397** |

`TrainKey` 는 출발 시각을 주키로 쓰고 열차번호는 보조 확인용이며, 한쪽이 비면 시각만으로
같다고 본다. 그 규칙이면 305 와 381 이 같은 열차가 된다. **주키를 열차번호로 뒤집어야 한다.**
코레일은 `span.num` 에 번호가 항상 나오므로 번호를 못 읽을 걱정은 SRT 보다 적다.

### §38-5. `<form>` 이 없다 — 페이지 전환이 일어나지 않는다

문서 전체에 `<form>` 이 **0개**다. SRT 는 `form#search-form` 이 같은 URL 로 POST 해서
화면이 통째로 바뀌었지만(§10), 코레일은 AJAX 로 리스트만 갈아끼운다.

- URL 이 바뀌지 않는다 → **URL 힌트로 페이지 종류를 판정할 수 없다.**
  `SCHEDULE_URL_HINTS` / `LOGIN_URL_HINTS` 방식은 버리고 DOM 마커로만 판정한다
- `onPageFinished` 가 오지 않는다 → 재조회 결과는 항상 `PageOutcome.Updated` 로만 온다

> 재조회 자체는 이미 이 구조를 견딘다. `KtxWebViewHost.awaitSettled` 는 화면 전환이
> 없으면 **DOM 서명(baselineSig) 변화**로 갱신을 감지하고 `Updated` 를 돌려주게 되어 있다.
> AJAX 는 이미 상정된 경로이므로 여기를 다시 쓸 필요는 없다.
> 바꿔야 하는 것은 **서명을 뜨는 대상**(SRT 표 → `div.tckWrap`)과 URL 기반 판정뿐이다.

반면 `dismissReserveResult` 의 **뒤로 가기는 다시 봐야 한다.** SPA 라 history 한 칸이
"조회 결과 화면"에 대응한다는 보장이 없다. (§38-8 미확인)

재조회 버튼은 `button.btn_bn-blue`(문구 `열차조회`)이고, 클래스가 표현용이라 문구로 찾는 편이
안전하다. 옆에 `button.btn_bn-blue.btn_lookup`(`다음날 (26년09월02일) 조회`)이 있으므로
문구 부분일치로 찾으면 **엉뚱한 날짜를 조회한다.** 완전일치로 비교할 것.

> **이 문단은 데스크톱 폭 기준이었다. 모바일에서는 그 버튼이 화면에 없다.** → §38-9

### §38-6. 예매는 2단계다 (SRT 와 가장 큰 차이)

SRT 는 행마다 [예약하기] 버튼이 있어 한 번 누르면 끝이었다. 코레일은:

1. **1단계** — `li.tckList` 안의 `price_box > a` 를 누른다.
   그 `price_box` 에 `active` 가 붙고, 화면 최하단에 예매 바가 나타난다.
2. **2단계** — 최하단 예매 바의 버튼을 누른다.

```
div.ticket_reserv_wrap > div.ticket_reserv_inner > div.ticket_reserv.clear.oneline
  ul.reserv_first > li            "일반실"        ← 1단계에서 고른 등급이 그대로 뜬다 (검증 훅)
  ul.reserv_center                열차시각 / 운임요금 / 좌석선택
  div.reservbtnWrap
    button.reservbtn ...                                    ← 2단계 버튼 (개수·문구가 상태마다 다르다)
```

**하단 바는 1단계에서 무엇을 골랐느냐에 따라 통째로 달라진다.** 두 상태를 실측했다.

| 1단계 선택 | `reservbtnWrap` class | 버튼 |
|---|---|---|
| `gen` (일반실 예약가능) | `reservbtnWrap one_btn` | **`예매`** 하나. `disabled` 아님 |
| `wait` (예약대기) | `reservbtnWrap` | `입석+좌석 예매`(**`disabled`**) + `예약대기신청` |

부수 관찰:
- `gen` 을 고르면 `ul.reserv_first` 에 `button.tck-trn-select-info__free-seat-info-opener`
  (`자유석1량>`)가 하나 더 붙는다. 자유석 **안내 팝업 opener** 일 뿐 좌석 칸이 아니다.
- `wait` 상태에서는 `ul.reserv_center` 의 `좌석선택` 이 `li.nodata` 로 죽어 있고,
  `gen` 상태에서는 살아 있다.

### §38-6-1. 2단계에서 누를 버튼을 고르는 규칙

`div.reservbtnWrap button.reservbtn` 중에서 **문구 완전일치 허용목록**으로만 고른다.
표현용 클래스(`btn_by-blue02` / `btn_bn-blue02`)는 버튼마다 달라서 기대지 않는다.

| 문구 | 누르나 | 이유 |
|---|---|---|
| `예매` | **누른다** | 정상 경로 |
| `예약대기신청` | **누르지 않는다** | 예약대기는 발견으로 보지 않는다 (§18). 누르면 원치 않는 대기가 걸린다 |
| `입석+좌석 예매` | **누르지 않는다** | 사용자가 체크한 것은 좌석이다. 입석을 대신 잡아 주지 않는다 |
| 그 밖 / `disabled` | 누르지 않는다 | |

허용목록에 없으면 **알림까지만 하고 2단계는 사람에게 넘긴다.** 대원칙 3(자동 클릭은
예매 버튼까지)과 대원칙 2(실패 경로에 자동 재시도 없음)를 함께 지키는 쪽이다.

누르기 **전에** 두 가지를 확인한다. 하나라도 어긋나면 누르지 않는다.

1. 1단계에서 누른 `price_box` 에 `active` 가 붙었는가
2. `ul.reserv_first li` 첫 항목의 문구가 고른 등급(`일반실`/`특실`)과 같은가

§19-2 의 "잔여석없음" 확인과 같은 역할이다. **화면 전환만으로는 성공을 알 수 없다.**

### §38-7. 로그인 판정 — 클래스 **이름**을 믿으면 안 된다

두 덤프가 우연히 로그인 / 비로그인 상태였던 덕에 확인됐다.
(1차 = 로그인, 2차 = 비로그인. 둘 다 조회 결과와 좌석 선택이 정상 동작했다 —
**비로그인에서도 조회된다는 SRT 의 성질이 코레일에도 그대로 있다.** §27-1)

머리말 `ul.h_top_right` 안의 링크가 상태에 따라 **통째로 바뀐다.**

| 상태 | 머리말 링크 |
|---|---|
| 로그인 | `a.btnGoLogout` (문구 `로그아웃`, href `#none`) |
| 비로그인 | `a.btnGoLogin` (문구 `로그인`, href `/ticket/login`) |

이 두 클래스는 상태와 1:1 이라 판정 근거로 쓸 수 있다.

> **함정:** 모바일 메뉴의 `button.logoutBtn` 은 **클래스 이름이 고정이고 문구만 바뀐다.**
> 로그인 상태에서 `로그아웃`, 비로그인 상태에서 **`로그인`** 이 들어 있었다.
> 클래스 이름만 보고 "logoutBtn 이 있으니 로그인 상태" 로 읽으면 **항상 로그인으로 오판한다.**
> 판정은 `ul.h_top_right` 안의 `btnGoLogin` / `btnGoLogout` 으로만 한다.

`li.loginY`(장바구니 / 마이페이지)도 두 상태 모두 DOM 에 있었다. 표시 여부는 CSS 로
갈리는 것으로 보이므로 **존재 여부로 판정하면 안 된다.**

대원칙 6 대로 둘 다 못 찾으면 `UNKNOWN` 으로 두고 감시를 막지 않는다.

#### 폰 폭에서는 로그인 표시가 **화면에 없다** (2026-08-29, 실제 사이트 실측)

`www.korail.com/ticket/search/list` 를 375px 폭으로 열고 잰 값이다.

| 요소 | DOM | 화면 |
|---|---|---|
| `ul.h_top_right` | 1개 | `display:none` |
| `div.header_top` (그 조상) | 1개 | `display:none` |
| `a.btnGoLogin` (그 안) | 1개, `href=/ticket/login`, 문구 `로그인` | rect 0×0, `offsetParent` 없음 |
| `div.allmenu` (전체메뉴) | 1개 | `display:none` (햄버거를 눌러야 열린다) |
| 문구가 `로그인`/`로그아웃` 인 **보이는** 요소 | — | **0개** |

즉 폰 폭에서 눈에 보이는 머리말은 로고와 [전체메뉴열기] 버튼뿐이다.
**[열차조회] 버튼과 똑같은 함정이다 (§38-9).**

그래서 판정은 **DOM 에 있는가**만 본다. 예전처럼 `getClientRects()`/`offsetParent` 로
거르면 링크도 문구도 못 찾아 **항상 `UNKNOWN`** 이 되고 — 앱의 WebView 는 언제나 폰
폭이므로 — 로그인 확인이 통째로 죽는다. 숨어 있었다는 사실은 로그의 `detail` 에
`(숨김)` 으로만 남긴다.

전체메뉴 안의 `div.bottom_menu_choose > button.logoutBtn` 은 문구 판정(2순위)의
보조 범위다. 비로그인에서 `<button class="logoutBtn">로그인</button>` 이었다 —
**문구만** 쓰고 클래스 이름은 위 함정대로 쓰지 않는다.

### §38-8. 이 덤프에서 확인하지 못한 것

두 번의 덤프(선택 = `wait` / 선택 = `gen`)로도 남은 것들이다.
**추측으로 채우지 말고 실측될 때까지 비워 둔다.**

- 자유석 / 입석이 별도 `price_box` 로 나오는지. 두 덤프 모두 편성마다 `price_box` 가
  정확히 2개였고 자유석은 하단 바의 안내 팝업(`자유석1량>`)으로만 나왔다.
  **입석·자유석은 좌석 칸이 아니라 예매 버튼·안내 종류**로 보이지만 확정은 아니다.
  → 이것이 확정되면 `SeatClass` 를 일반실/특실 2개로 유지할 수 있다 (큰 파급을 피한다)
- ITX·무궁화가 섞인 결과에서 `flag_wrap` 의 class 가 어떻게 달라지는지
  (두 덤프 모두 `train_sancheon_ticket` 하나뿐)
- 선택 **전**(아무 칸도 안 고른 상태) 하단 예매 바가 DOM 에 아예 없는지, 숨겨져만 있는지.
  두 덤프 모두 이미 선택된 상태라 `ticket_reserv_wrap` 이 항상 존재했다
- 매진된 칸(`sold_out`)을 눌렀을 때의 반응
  → 지금은 **누르지 않는다.** 파서가 `AVAILABLE` 인 칸만 1단계 대상으로 삼는다
- **`dismissReserveResult` 의 뒤로 가기가 SPA 에서 조회 결과 화면으로 돌아가는지** (§38-5)
  → 지금은 물러난 뒤 목록이 실제로 보이는지 확인하고, 안 보이면 성공으로 치지 않는다
- **예약 실패 안내의 실제 문구.** `KtxSelectors.RESERVE_FAILED_MARKERS` 는 아직 SRT 값이다
- **조회 폼의 출발일 입력.** `KtxSelectors.SEARCH_DATE_FIELDS` 를 비워 두었다.
  화면 상단의 "구간 · 날짜" 요약에서 날짜만 빠진 채로 보인다 (표시용이라 감시에는 영향 없음)

---

### §38-9. 모바일 폭에는 [열차조회] 버튼이 없다 — 갱신을 새로고침으로 바꾼 이유

2026-08-29, `www.korail.com/ticket/search/list` 를 **375×812(모바일)** 로 재실측했다.
§38-5 까지의 실측은 데스크톱 폭이었고, 거기서 결론이 갈린다.

**1. `열차조회` 는 DOM 에 있지만 화면에는 없다.**

```
div.ticketSrchWrap > div.selectAreaWrap > div.left_wrap
  > div.inner.minner        display:flex   341×55
    > div.btnWrap.btn_box   display:none   ← 여기서 끊긴다
      > button.btn_bn-blue  "열차조회"      getBoundingClientRect() = 0×0
```

버튼 자신의 `display` 는 `inline-flex` 라 **버튼만 보면 멀쩡해 보인다.** 조상이
`display:none` 이다. `querySelector` 로는 찾아지고 `elementFromPoint` 로는 절대
잡히지 않는다 — "selector 로 찾아진다"가 "누를 수 있다"가 아닌 전형적인 경우다.

같은 화면에서 실제로 보이는 조회 버튼은 하나뿐이다:

```
button.btn_bn-blue.btn_lookup  "다음날 (26년08월30일) 조회"  rect = [77, 822, 220, 35]
```

**절대 후보로 삼으면 안 되는 그 버튼이다.** (§38-5) 즉 모바일 레이아웃에서
"조회 버튼을 찾아 누른다"는 방법은 **최선의 경우 아무것도 못 찾고, 최악의 경우
사용자가 보던 날짜가 아닌 다음날을 조회한다.**

**2. 조회 조건은 localStorage 에 있다 — 그래서 새로고침해도 안 날아간다.**

```json
localStorage["LS_TICKET_GENERAL"] = {
  "txtGoStart": {"stn_nm": "서울",  "stn_cd": "0001"},
  "txtGoEnd":   {"stn_nm": "부산",  "stn_cd": "0020"},
  "txtGoAbrdDt": "20260829", "txtGoHour": "140200",
  "txtPsgFlg_1": 1, ...
}
```

`location.reload()` 로 확인했다. URL(`/ticket/search/list`)에는 아무 파라미터도 없지만,
새로고침 뒤 **같은 조건(서울→부산 14:04)으로 10건이 그대로 다시 그려졌다.**
SPA 가 이 값을 읽어 조회 API 를 다시 친다.

그래서 §10 의 갱신을 `WebView.reload()` 로 바꿨다. 사용자가 사이트에 직접 넣은 조건을
앱이 다시 입력하지 않으므로 **대원칙 4 는 깨지지 않는다.**

**3. 대가: 한 사이클이 무거워졌다.**

AJAX 재조회는 조회 API 하나였지만 새로고침은 문서 + 번들 + 조회 API 전체다.
게다가 세션 스토리지에 `_nf_wt:service_1:act_7` / `_nf_edomain:...nf.letskorail.com`
— **NetFunnel(대기열/부하분산)** 이 물려 있다. 간격을 좁히지 말 것. (대원칙 2)

**4. 문서 로딩과 목록 렌더링은 별개다.**

`onPageFinished` 시점에는 `li.tckList` 가 아직 0개다. 그래서 `awaitReloaded` 가
2단계로 기다린다. `researchSettleMs` 를 6초에서 12초로 늘린 것도 이 때문이다.
**이 대기 동안 요청은 나가지 않는다** — DOM 을 읽기만 한다.

**5. 새로고침하면 화면이 맨 밑으로 튄다 — 스크롤 되살리기를 끈다.**

브라우저는 새로고침할 때 직전 스크롤 위치를 되살린다(`history.scrollRestoration`,
기본값 `auto`). 여기서 4번과 겹쳐 사고가 난다:

```
사용자가 목록을 아래로 내려 둠 (scrollY ≈ 1800)
  → reload → onPageFinished 시점의 문서에는 li.tckList 가 0개 = 문서가 짧다
  → 1800 을 되살릴 자리가 없어 문서 끝으로 잘려 붙는다
  → 뒤이어 목록이 그려져 높이가 늘어나도, 스크롤 앵커링이 그 자리를 붙든다
  → 사용자 눈에는 갱신할 때마다 화면이 맨 밑으로 튀는 것으로 보인다
```

되살리기를 끄고(`history.scrollRestoration = 'manual'`) 맨 위로 올린다
(`KtxParserScript.buildScrollTopScript`). 거는 자리가 셋인 이유는 각각이 다른
구멍을 막기 때문이다:

| 자리 | 막는 것 |
|---|---|
| 새로고침 **직전** (`requery`) | 되살리기는 **나가는 이력 항목**에 붙는다. 시작한 뒤에는 늦다 |
| `onPageFinished` | 새 문서의 `history` 는 초기화되어 있다. 다음 사이클을 위해 다시 건다 |
| 목록이 그려진 **뒤** (`awaitReloaded`) | 문서가 짧던 시점의 스크롤만으로는 부족하다 |

읽기와 스크롤뿐이라 **요청은 늘지 않는다.** 뷰의 스크롤(`WebView.scrollTo`)도 함께
0 으로 둔다 — WebView 가 자체적으로 되살려 둔 오프셋이 남을 수 있다.

### §38-10. 역/지역 선택 창은 `window.open` 팝업이 아니다

같은 실측에서 확인했다. 주요역/지역별 선택 창(`a.btn_pop-openStationPop`,
메인 화면은 `a.btn_pop.btn_start` / `a.btn_end`)은 **`window.open` 을 부르지 않는다.**
`window.open` 을 가로채 두고 눌러 봐도 호출 기록이 0건이다.

실제 구조는 `document.body` 끝에 붙는 **react-modal 포털**이다:

```
div.ReactModalPortal
  > div.ReactModal__Overlay.modal.layerPopup.m-full   position:fixed  inset:0  z-index:1005
    > div.ReactModal__Content
      > div.layerWrap.type_tranin-station-pop_wrap    overflow:hidden auto
        > div.tit_wrap    position:sticky  "기차역 조회" + button.btn_close
        > div.con_Wrap    검색창 + [주요역]/[지역별] 탭 + 역 목록(a)
```

375×812 에서 오버레이 rect 는 `[0, 0, 375, 812]` 로 정상이다. 즉 **사이트 쪽 문제는 없다.**

여기서 나오는 결론이 중요하다:

- 이 창은 **페이지 안에서** 그려진다. [KtxPopupHost] 도 `PopupOverlay` 도 관여하지 않는다.
  이 창이 안 뜰 때 **앱이 "위로 띄워" 줄 방법은 없다.** 원인은 WebView 안의 JS 다.
- 그래서 `WebChromeClient.onConsoleMessage` 로 **JS 오류/경고를 로그 창(`PAGE_CONSOLE`)에
  흘린다.** `chrome://inspect` 없이 원인을 보기 위한 것이다.
  콘솔 훅은 반드시 `KtxPopupHost` 의 ChromeClient 안에 둘 것 — WebView 당 ChromeClient 는
  하나뿐이라, 다른 곳에서 새로 걸면 `onCreateWindow` 가 죽어 진짜 팝업(달력 등)이 사라진다.
- 모달 CSS 에 `dvh` / `:has()` / `@container` 는 **쓰이지 않는다.** (확인함)
  즉 WebView 의 CSS 지원 범위 문제로 높이가 0 이 되는 경로는 아니다.

#### 사이트 쪽 핸들러 (2026-08-29, `bundle.546f16d8…js` 실측)

버튼은 `<a href="#none">` 이고 React `onClick` 하나가 전부다. 압축을 풀면 이렇다:

```js
// 출발역
onClick: function (e) {
  e.preventDefault();
  props.stationDisabled || (setStationPopOpen(true), setField("txtGoStart"));
}
```

여기서 두 가지가 나온다.

1. **핸들러가 돌기만 하면 `preventDefault()` 는 무조건 불린다.** 그래서 바깥에서
   `event.defaultPrevented` 를 보면 "이벤트가 React 까지 갔는가" 를 알 수 있다.
2. **`stationDisabled` 면 아무 일도 일어나지 않는다.** 오류도 로그도 없이 조용하다.
   조건은 `"C" === etrPath || bizNcardSpecificStnSelected || assignType ∈ {NCARD, REGULAR}`
   이고, 이때도 클래스는 `btn_pop-openStationPop` 그대로라 **DOM 만 봐서는 구분되지 않는다.**
   (`btn-disabled` 가 붙는 것은 `etrPath === "C"` 인 경우와 KTX_BUX 뿐이다)

그리고 조회 결과 화면에서는 출발역 쪽 `<a>` 에도 `btn_end` 가 붙는다. 사이트 쪽 오타지만
selector 는 실측대로 둔다.

#### "아무 반응 없음" 을 넷으로 가른다 — `STATION_PROBE`

원인이 넷인데 화면에서는 전부 똑같이 보인다. 그래서 로그 창에 **[역 진단]** 을 두었다.
([KtxParserScript.buildStationProbeScript] → [KtxWebViewHost.probeStationPopup])
누르는 순서는 **진단 → 역 버튼을 손으로 눌러 봄 → 진단** 이다.
첫 번째가 `document` 에 캡처 단계 click 리스너를 하나 걸고, 두 번째가 결과를 읽는다.

| 로그 | 어디서 끊겼나 |
|---|---|
| `탭 기록 없음` | 손가락이 그 `<a>` 에 닿지 않았다. `가려짐(…)` 이 함께 나오면 덮은 요소가 범인 |
| `역버튼 … 핸들러=안돎` | 이벤트가 React 까지 못 갔다 |
| `핸들러=돎 모달=0→0` | **사이트가 일부러 막았다** (`stationDisabled`). 앱이 고칠 것이 없다 |
| `모달=0→1` | 창은 만들어졌다. 남은 것은 그리기/보이기 문제다 |

`맨위="…"` 에 안내 문구가 찍히면 그 모달이 화면을 덮고 있다는 뜻이다.
코레일에는 매크로/개발자도구 감지가 있고(`CODE : -8002`), 걸리면 전체를 덮는 안내
모달을 띄운다. 그 상태에서는 역 버튼뿐 아니라 **아무것도 눌리지 않는다.**

진단은 DOM 을 읽기만 한다. 조회 요청이 나가지 않으므로 대원칙 2 와 무관하고
감시 중에 눌러도 된다.

#### 실기기 1차 진단 결과 (2026-08-29) — **4번이다**

```
STATION_PROBE=rows=6 모달=1 맨위="기차역 조회 레이어닫기 검색 서울 → 부산 주요역 지역별
서울 용산 광명 수서 영등포 수원 평택 천안아산 천안 오송 조치원 대전 서대전 김천구미"
뷰=411x449 | 출발역 선택 [26,179,156,31] | 도착역 선택 [229,179,156,31] | 탭 기록 없음
```

여기서 확정된 것:

- **모달은 만들어진다.** `모달=1` 이고 본문에 역 목록까지 다 들어차 있다.
- 따라서 1·2·3번은 전부 아니다. **`stationDisabled` 도 아니다.**
- 남은 것은 **그려지지 않는 것뿐이다.** WebView 는 411×449 CSS px 로, 브라우저에서
  재현했던 375×812 보다 **훨씬 납작하다.** 앱이 WebView 위아래로 UI 를 얹기 때문이다.

(`탭 기록 없음` 은 [역 진단] 을 누르기 **전에** 역 버튼을 눌렀다는 뜻이다. 리스너는
진단을 처음 누를 때 걸린다. 순서를 지키면 탭 기록도 함께 나온다)

그래서 진단이 맨 위 모달의 **기하와 조상 사슬**까지 뜬다. 보이지 않게 만들 수 있는
것만 본다 — 상자 / `position`·`z-index`·`opacity`·`visibility`·`overflow`·`transform`
/ 한가운데를 hit-test 했을 때 정말 맨 위인지(`덮임(…)`) / `scrollX,Y` / 조상 3대.

조상까지 보는 이유는 `position:fixed` 다. 조상에 `transform`·`filter`·`clip-path` 가
있으면 **기준이 뷰포트가 아니라 그 조상**이 되어 화면 밖으로 나갈 수 있다.
`ReactModalPortal` 은 `document.body` 에 붙으므로 사슬은 `body` → `html` 뿐이지만,
거기에 스크롤 잠금이 걸리는 사이트가 흔해서 확인 대상이다.

#### 2차 진단 — **높이가 0 이다. 원인은 `100vh`**

```
모달 [0,0,411,0] fixed z=1005 op=1 of=hidden  내용[0,0,411,40] static op=1 of=auto
덮임(none)  조상 div.ReactModalPortal[static] < body.normalmode.popupoff[static] < html.ticketdiv[static]
뷰=411x460
```

폭은 411 로 꽉 차는데 **높이만 0** 이다. `덮임` 도 아니고 `op=1` 이니 가려진 것도
투명한 것도 아니다. 조상 셋 다 `static` — `position:fixed` 를 가로채는 `transform` 도 없다.

사이트 CSS 는 이렇다 (번들 인라인):

```css
.layerPopup { width:100%; height:100vh; position:fixed; background:transparent;
              z-index:1005; top:0; left:0 }
```

**데스크톱 Chrome 을 같은 411×460 으로 맞춰 재현했다:**

| | Chrome 411×460 | 앱 WebView 411×460 |
|---|---|---|
| 오버레이 높이 | **460** | **0** |
| 내용 높이 | 40 | 40 |
| position / overflow / z-index | fixed / hidden / 1005 | 같음 |

내용이 40px 인 것은 양쪽 같다. **다른 것은 `100vh` 하나뿐이다.**
`window.innerHeight` 는 460 으로 멀쩡한데 `100vh` 만 0 이 되었다. 그래서 진단이
`100vh=…` 와 `cH=…`(`documentElement.clientHeight`)을 따로 잰다 — 세 값이 어긋나는
지점이 곧 원인이다.

#### 3차: 덤프로 확인한 것 — **대체 경로가 없다**

2026-08-29, 크롬에서 역 선택 창을 연 상태의 `document.body` 를 통째로 받아
닫힌 상태와 비교했다. 열렸을 때 늘어나는 것은 이것뿐이다:

```
body                   + class "ReactModal__Body--open"
div#wrap               + aria-hidden="true"
body 끝                + div.ReactModalPortal          ← 새로 생긴다
                       + div[data-react-modal-body-trap] × 2
```

포털 안쪽:

```html
<div class="ReactModal__Overlay ReactModal__Overlay--after-open modal layerPopup m-full my_modal_layer9999"
     style="background-color: rgba(0, 0, 0, 0.5);">        ← 인라인 스타일이 이 한 줄뿐이다
  <div class="ReactModal__Content ReactModal__Content--after-open" role="dialog" aria-modal="true"
       style="position: static; inset: 40px; …; overflow: auto; padding: 20px;">
```

**오버레이에 기하가 하나도 없다.** react-modal 은 `overlayClassName` 을 받으면
기본 인라인 오버레이 스타일(`position:fixed; top/left/right/bottom:0`)을 **아예 붙이지
않는데**, 코레일이 정확히 그렇게 쓴다. 반대로 `className` 은 넘기지 않아서 안쪽
`Content` 에는 기본값이 그대로 붙어 있다(`inset:40px`) — 위 덤프가 그 증거다.
그리고 그 `Content` 는 `position:static` 으로 덮여 있어 크기를 만들지 못한다
(실측 40px, Chrome 에서도 같다).

정리하면 이 창의 크기는 **`.layerPopup { height:100vh }` 하나에 전적으로 달려 있고
대체 경로가 없다.** `100vh` 가 0 이면 오버레이는 높이 0 이 되고, 오버레이가
`overflow:hidden` 이라 안쪽 패널까지 통째로 잘린다. 2차 진단의 측정과 정확히 맞는다.

같은 덤프가 배제해 주는 것도 있다. 메인/조회결과 두 화면 모두 버튼과 모달 마크업이
문서대로였고(§38-10 앞부분), 열린 상태에도 `stationDisabled` 를 시사하는 표시가 없다.
**사이트 쪽은 멀쩡하다.**

#### 고친 것 (1) — WebView 가 높이를 얻은 뒤에 문서를 연다

`MainActivity` 는 `onCreate` 에서 곧바로 `loadStartUrl()` 을 불렀다. 그 시점의 WebView 는
아직 `setContent` 전이라 **어디에도 붙지 않았고 높이가 0** 이다. 크기 0 인 채로 문서를
받으면 뷰포트 단위가 0 으로 잡히고, `height:100vh` 인 레이어는 통째로 납작해진다.
열려 있는데 높이가 0 이니 사람 눈에는 "아무 반응 없음" 이다.

기다리는 코드는 **[KtxWebViewHost.loadStartUrl] 안에 있다.** 처음에는 Activity 쪽
(`loadStartUrlWhenSized()`)에 두었는데, 부르는 곳이 하나가 아니라서 새는 곳이 남았다:

- 첫 실행 — `setContent` 전이라 붙지 않았다.
- **설정 화면의 [시작 페이지로]** — `MainScreen` 이 설정 화면에서 일찍 `return` 하므로
  그동안 WebView 는 **화면에서 떼어져 있다.** 여기서 `loadUrl` 이 나가면 같은 고장이
  그대로 재현된다. (지금은 설정 화면을 먼저 닫고 부른다)

그래서 대기는 호출부가 아니라 **호스트 안**에 둔다. `isAttachedToWindow && height > 0`
이 될 때까지 배치를 기다리고, 3초 안에 크기가 안 생기면 그냥 연다 (대원칙 6).

> WebView 를 화면에 붙기 전에 만들어 두는 것 자체는 그대로다 (§12, §24 — 화면 전환에도
> 세션이 유지되어야 한다). 바뀐 것은 **문서를 언제 받느냐** 뿐이다.

#### 고친 것 (2) — `100vh` 가 깨졌으면 되살린다

(1) 은 "문서를 받는 시점" 만 고친다. `100vh` 가 0 이 되는 경로가 그것 하나라는 보장이
없고, 한 번 어긋나면 화면에는 여전히 아무 반응이 없다. 그래서 **결과를 직접 확인하고
어긋났을 때만 되돌린다.** ([KtxParserScript.buildViewportFixScript], 문서마다 한 번,
`onPageFinished` 에서)

```
1. 자(<div style="height:100vh">)를 하나 붙였다 떼어 `100vh` 가 몇 px 인지 잰다.
   getComputedStyle 로는 알 수 없다 — 그 규칙이 어느 요소에 걸렸는지 모르기 때문이다.
2. window.innerHeight 와 비교한다. 4px 안쪽이면 정상 → 아무것도 하지 않는다.
3. 어긋났으면 <style> 한 장을 넣는다:
     .layerPopup { height: var(--catchtrain-vh, 100vh) !important }
   그리고 --catchtrain-vh 를 innerHeight 픽셀값으로 채운다.
4. resize / orientationchange 에 다시 채운다 (앱 패널을 여닫으면 WebView 높이가 바뀐다).
```

**멀쩡한 WebView 에서는 아무것도 하지 않는다.** 사이트 함수를 부르지 않고 요청도 내지
않으며, 건드리는 것은 `KtxSelectors.VIEWPORT_HEIGHT_LAYER` 하나뿐이다. 이 앱이 페이지에
CSS 를 넣는 유일한 자리이므로, 늘리기 전에 정말 필요한지 다시 따질 것.

결과는 로그에 `VIEWPORT_FIX` 로 남는다.

| 로그 | 뜻 |
|---|---|
| `보정함 100vh=0 → 460px` | 이 WebView 의 `vh` 가 깨져 있었고 되살렸다 |
| `손대지 않음(ok) 100vh=460 innerHeight=460` | 멀쩡하다. **역 창이 여전히 안 뜨면 원인은 다른 곳** |
| `손대지 않음(noHeight) …` | 문서를 받은 시점에 WebView 크기가 아직 0 이다 → (1) 이 새고 있다 |

확인 순서는 그대로 **[역 진단] → 역 버튼 → [역 진단]** 이고, `100vh=` 가 `뷰=` 의
높이와 같아지면 맞은 것이다.

---

## §39. 접속 대기열(NetFunnel)에 걸린 화면 — 기다림을 사이클 안으로

코레일은 `nf.letskorail.com`(NetFunnel) 대기열이 물려 있다. 새로고침한 뒤 목록 대신
접속 대기 화면이 몇 분씩 떠 있을 수 있다. 그 화면은 `li.tckList` 도 로그인 마커도
차단 문구도 없으므로 `PageStatus.UNKNOWN_PAGE` 로 읽힌다.

### §39-1. 무엇이 잘못돼 있었나

한 사이클은 `새로고침 → 12초 안에 목록이 그려지길 기다림 → **한 번** 분석 → 판정`
이었고, 판정이 끝나면 다음 사이클로 넘어갔다. **다음 사이클의 첫 동작이 새로고침이다.**

```
새로고침 ──▶ 대기 화면 ──▶ 12초 대기 ──▶ 1회 분석 ──▶ UNKNOWN
   ▲                                                    │
   └──────────── 0.1~0.3초 뒤 ◀───────────────────────────┘
```

기본 간격이 0.1~0.3초(`ReloadScheduler.DEFAULT_*`)라 사실상 대기 화면을 쉬지 않고
두들겼다. **대기 중에 새로고침하면 대기 순번이 날아간다** — 그래서 대기가 영영
끝나지 않고, 그 사이 `maxUnknownPages`(2) 만 소진하고 감시가 죽었다.
사용자가 겪은 증상 셋이 전부 이 그림 하나에서 나온다.

| 증상 | 이 그림의 어느 부분 |
|---|---|
| 대기가 안 끝났는데 새로고침한다 | 되돌아가는 화살표 |
| 새로고침하고 분석을 한 번만 해서 얼탄다 | `1회 분석` |
| 대기 중인데 3번만 시도하고 포기한다 | `UNKNOWN` 이 곧바로 카운터를 먹는다 |

### §39-2. 고친 방향 — 대기열을 **판정하지 않는다**

NetFunnel 마커를 찾아 대기 화면을 알아보는 길도 있었지만 택하지 않았다. 실측이 없어
selector 를 추정해야 하고, 추정이 틀리면 조용히 실패한다. 그리고 **알아볼 필요가 없다.**

> **화면이 확정될 때까지 다음 사이클로 넘어가지 않는다.**
> 넘어가지 않으면 새로고침도 나가지 않는다.

기다리는 자리를 `KtxWebViewHost` 가 아니라 `WatchController` 의 분석 단계
(`readSettledSnapshot`)에 둔다. 호스트의 가벼운 `pageKind` 는 목록의 유무만 알지만,
컨트롤러의 full parse 는 **차단·로그인·세션만료까지 구분**하기 때문이다.

```
새로고침 ──▶ onPageFinished ──▶ ┌─ parse ─┐
                                │         │ isSettled?  ──예──▶ 그대로 진행
                                │  0.5초  │
                                └─◀───────┘ 아니오 (요청 안 나감)
                                     │
                              예산 소진 ──▶ 그제서야 UNKNOWN 1회
```

### §39-3. `PageStatus.isSettled` — 기다릴 값어치가 있는 상태는 하나뿐

`UNKNOWN_PAGE` 만 `false` 다. 나머지 다섯은 화면에서 무언가를 **확실히 알아본** 결과라
더 기다려도 답이 바뀌지 않는다.

이것이 **부작용을 막는 핵심 장치**다. 첫 판독이 확정이면 그 자리에서 돌려주므로,
목록이 보이는 정상 상황에서는 예전과 **완전히 같은 경로로 같은 시간에** 끝난다.
차단 화면을 3분씩 붙들고 있는 일도 없다 — `BLOCKED` 는 첫 판독에서 즉시 빠져나간다.

| 상태 | 기다리나 | 이유 |
|---|---|---|
| `TRAIN_LIST` | 아니오 | 목록을 봤다. 목적 달성 |
| `NO_TRAIN` | 아니오 | 결과 컨테이너는 있고 0건이다. 확정된 답이다 |
| `LOGIN_REQUIRED` / `SESSION_EXPIRED` | 아니오 | 사람이 개입해야 한다. 늦출수록 손해 |
| `BLOCKED` | 아니오 | 즉시 멈춰야 한다 (대원칙 2) |
| `UNKNOWN_PAGE` | **예** | 엉뚱한 화면인지 전이 중인지 구분이 안 된다 |

### §39-4. 예산을 둘로 나눈 이유 — `hasSeenList`

`UNKNOWN_PAGE` 의 원인은 둘인데 화면만 보고는 못 가른다.

- **이번 감시에서 목록을 본 적이 있다** → 지금의 `UNKNOWN` 은 전이 중(대기열·렌더링)일
  가능성이 높다 → `pageWaitMs` (3분) 로 길게.
- **아직 한 번도 못 봤다** → 화면 자체가 엉뚱한 곳(메인 화면 등)일 가능성이 높다 →
  `pageWaitFirstMs` (10초) 로 짧게 끊고 안내를 띄운다.

짧은 쪽이 없으면, 조회 결과 화면이 아닌 곳에서 감시를 시작한 사용자를 3분씩 세워 둔다.
**그것이 정확히 사용자가 없애 달라고 한 "멍때림"이다.**

`hasSeenList` 는 `start()` 에서만 지운다. `pause`/`resume` 로 지우지 않는다 —
앱을 잠깐 벗어났다 돌아온 것뿐인데 이미 알고 있던 사실을 잊을 이유가 없다.

### §39-5. 상한이 세는 것은 판독이 아니라 요청이다

`maxUnknownPages` 와 `maxConsecutiveErrors` 는 **값을 바꾸지 않았다.**
바뀐 것은 *무엇을 세는가* 다.

- 예산 안에서 몇 번을 다시 읽든 그것은 **DOM 읽기**라 요청이 아니다. 하나도 안 센다.
- 카운터가 하나 늘려면 **새로고침이 실제로 한 번 더 나가야** 한다.

대원칙 2 가 지키려는 것은 요청 횟수지 판독 횟수가 아니다. 오히려 고치기 전 쪽이
대원칙 2 를 어기고 있었다 — 대기 중에 0.2초마다 새로고침을 쏘고 있었으니까.

### §39-6. 예매 2단계만 예산이 따로다

대기열은 조회보다 **[예매] 를 누른 직후**에 잘 붙는다. 조회는 사람이 많아도 통과되지만
예매는 그 순간 트래픽이 몰린다. 그래서 2단계에만 별도 예산을 둔다.

| | 값 | 왜 |
|---|---|---|
| `confirmTimeoutMs` | 90초 | 전환이 시작됐다 = **요청이 이미 나갔다.** 여기서 포기하면 몇 시간 기다린 좌석을 대기창 앞에서 버린다 |
| `confirmSettleMs` | 20초 | 전환조차 없다 = 헛방일 가능성이 높다. 다만 대기 안내가 겹쳐 뜨는 형태면 DOM 만 바뀌므로 6초보다는 길게 |

1단계(좌석 고르기)와 되돌리기는 **그대로 6초**다. 화면 안에서 끝나는 동작이라
오래 기다려 봐야 얻는 것이 없다. (`awaitSeatSelected` 의 KDoc 참조)

### §39-7. 기다리는 동안 화면과 로그가 살아 있어야 한다

멈춘 것처럼 보이면 사용자는 앱이 죽은 줄 안다. 상태 메시지에 경과 시간을 계속
흘려 주고(`화면을 기다리는 중입니다… 24초`), 로그에는 다음이 남는다.

| 로그 | 뜻 |
|---|---|
| `PAGE_WAIT_START` | 기다리기 시작했다. **이 줄이 보이면 그 사이 새로고침은 나가지 않았다** |
| `PAGE_WAIT_TICK` | 대략 5초마다. 경과 시간과 판독 횟수 |
| `PAGE_WAIT_DONE` | 무엇으로 확정됐는지와 걸린 시간 |
| `PAGE_WAIT_TIMEOUT` | 예산을 다 썼다. 여기서 비로소 `UNKNOWN` 을 한 번 센다 |

정상 상황에서는 **이 네 줄이 하나도 안 나온다.** 나오기 시작하면 그때부터가 대기다.

### §39-8. 시간 예산을 테스트하려면 시계를 갈아 끼워야 한다

`WatchController` 의 `clock` 기본값은 `System.currentTimeMillis` 다. `advanceTimeBy` 는
**가상 시간만** 밀기 때문에, 그대로 두면 예산이 영원히 만료되지 않고 테스트가
`delay` 만 무한히 소비한다. 실제로 이 함정을 한 번 밟았다.

예산으로 끝나는 동작을 검사할 때는 `clock = { testScheduler.currentTime }` 을 준다.
(`advanceUntilIdle()` 은 여전히 금지 — `backgroundScope` 의 감시 루프가 멈춘다)
