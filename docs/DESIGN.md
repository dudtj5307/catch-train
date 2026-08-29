# 설계 규칙 (Catch Train)

이 문서는 **코드가 스스로 말하지 못하는 것**만 담는다: 왜 그 구조인가, 무엇을 하면
안 되는가, 실제 사이트에서 확인한 동작. 클래스 목록과 시그니처는 코드를 보면 된다.

> **§ 번호를 다시 매기지 말 것.** 코드 KDoc 100여 곳이 `DESIGN.md §N` 으로 이 문서를
> 가리킨다. 내용은 얼마든지 고쳐도 되지만 번호는 고정이고, 절이 사라지면 번호를 비워 둔다.
> 원설계 문서 전문과 폐기된 절(§1~§4, §30~§33, §35~§37)은 [`HISTORY.md`](HISTORY.md) 에 있다.

한눈에 보는 대원칙은 [`../CLAUDE.md`](../CLAUDE.md) 에 있다. 여기는 그 근거와 세부다.

---

## §5. 기술 스택

Kotlin / Jetpack Compose (Material 3) / ViewModel + StateFlow / Coroutines /
WebView / DataStore Preferences. minSdk 26 — `java.time` 과 `NotificationChannel` 을
desugaring 없이 쓰기 위해서다.

## §6. 프로젝트 구조

[`../CLAUDE.md`](../CLAUDE.md) 의 "코드 지도" 참조. 패키지는
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
      ├ 체크한 칸이 AVAILABLE  → 알림 → [예약하기] 터치 → RESERVED (감시 종료)
      └ 아니면                 → 무작위 간격 대기 → [조회하기] 터치 → 다시 분석
```

첫 사이클을 갱신 없이 시작하는 이유: 사용자는 이미 조회 결과를 열어 둔 상태에서
[감시 시작] 을 누른다. 요청을 한 번 아끼고 즉시 피드백을 준다.

## §10. 갱신 — 버튼 좌표에 진짜 터치

**이 앱에서 가장 중요한 구현 결정이다.** 페이지 갱신 방법은 하나뿐이고 대체 경로가 없다.

실측 결과 아래는 전부 막힌다.

| 방법 | 결과 |
|---|---|
| 조회 URL(`selectScheduleList.do`) 을 `loadUrl` / `a[href]` 로 직접 호출 | 사실상 항상 차단 |
| `WebView.reload()` | POST 결과 화면이라 같은 결과를 보장하지 않고, 요청 패턴이 자동화로 보인다 |
| JS `el.click()` / `dispatchEvent` | `isTrusted=false` 합성 이벤트. 브라우저 입력 파이프라인을 거치지 않는다 |

그래서 갱신은 **사용자가 그 버튼을 손가락으로 누르는 것과 같은 입력**으로만 한다.

```
ReloadScheduler                다음 사이클까지 대기 (범위 안에서 무작위)
  → buildLocateScript(viewW, viewH)
       버튼을 찾아 화면 좌표만 계산한다. 누르지 않는다.
       - scrollIntoView 로 화면 안으로 들여놓고
       - visualViewport 기준으로 CSS px -> 위젯 px 환산
       - elementFromPoint 로 그 지점이 정말 그 버튼인지 확인
  → KtxWebViewHost.tap(x, y)
       MotionEvent(SOURCE_TOUCHSCREEN, TOOL_TYPE_FINGER)
       DOWN → 60~160ms → MOVE(±1px) → UP        (시간·좌표는 매번 조금씩 다르다)
  → buildTapConfirmScript      click 이 버튼까지 닿았는지, isTrusted 였는지 확인
  → 정착 대기                   onPageFinished 또는 MutationObserver
```

누를 지점은 버튼 가운데부터 시도하되, 고정 배너 등이 덮고 있으면 버튼 안쪽의
다른 지점을 차례로 시도한다.

실패는 두 가지로 구분한다. **어느 쪽도 다른 방법으로 대체하지 않는다.**

| 결과 | 의미 | 이후 |
|---|---|---|
| `RESEARCH_BUTTON_NOT_FOUND` | 조회 결과 화면이 아니다 (차단 안내/오류 화면 등) | 재시도 없이 즉시 중지 |
| `RESEARCH_BUTTON_NOT_TAPPABLE` | 버튼은 있으나 화면에서 누를 수 없다 (가려짐/화면 밖/WebView 안 보임) | 요청이 나가지 않았으므로 다음 사이클에 재시도 |

전제: **WebView 가 화면에 보여야 한다.** 사람이 누를 수 없는 상태면 앱도 누르지 않는다.

클릭이 AJAX 로 처리되어 화면 전환이 없으면 `researchSettleMs` 후 DOM 을 한 번 분석한다.

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

**팝업이 열려 있는 동안에는 자동 조회를 하지 않는다.** 조회는 WebView 위젯 좌표에
MotionEvent 를 내려보내는 방식이라(§10) 오버레이가 덮여 있어도 뒤쪽 [조회하기] 가
그대로 눌리기 때문이다. 이 경우 `PageOutcome.Deferred` 로 이번 차례를 건너뛴다.
오류가 아니므로 연속 오류로 세지 않는다.

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

알림을 보낸 **뒤에**, 그 열차의 예매 버튼까지 눌러 준다. 누르는 방식은 재조회(§10)와
완전히 같다. 여기까지가 앱의 역할이고 **좌석 선택과 결제는 사용자가 직접 한다.**
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

헤더는 한 줄로 접혀 있고, `⌄` 로 현재 주소 / 선택 요약 / 감시 상태를 펼친다.
`⚙` 는 설정. **좌석을 발견한 카드는 접힌 상태에서도 계속 보인다.**

`열차 선택` 패널의 `다시 읽기` 는 **조회 요청을 보내지 않고 DOM 만 읽는다**
(`WatchController.scanTrains`). 몇 번을 눌러도 차단 위험이 없다.

- 한 줄이 한 편성, 오른쪽 좌석 칸은 **특실 / 일반실** 순 (사이트와 동일)
- **매진인 칸도 체크할 수 있다.** 풀리기를 기다리는 것이 이 앱의 목적이다
- 상태를 읽지 못한 칸(`UNKNOWN`, `-`)만 체크할 수 없다
- 감시 중에 체크를 바꿔도 된다 — `updateSelection()` 으로 다음 사이클부터 반영된다.
  재시작하지 않는다(재시작하면 요청이 한 번 더 나가고 알림 이력이 사라진다)

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
`LOGIN_REQUIRED` `SESSION_EXPIRED` `UNKNOWN_PAGE` `RESEARCH_BUTTON_NOT_FOUND`
`RESEARCH_BUTTON_NOT_TAPPABLE` `BLOCKED`. 각각 사용자에게 보일 `title`/`guide` 를 갖는다.

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

판정(`KtxLoginScript`)은 머리말 영역(`ul.h_top_right` → `div.header_top` → ...) 안의
**링크만** 본다. 코레일은 이 자리의 링크가 상태에 따라 통째로 바뀌어서
(`a.btnGoLogout` / `a.btnGoLogin`) 상태와 1:1 이다. 자세한 것과 함정은 §38-7.

| 결과 | 조건 | 감시 시작 |
|---|---|---|
| `LOGGED_IN` | 로그아웃 링크만 있음 | 허용 |
| `LOGGED_OUT` | 로그인 링크만 있음 | **막고 안내** |
| `UNKNOWN` | 둘 다 없거나 둘 다 있음 | 허용 |

- **문서 전체 텍스트에서 "로그아웃" 을 찾지 않는다.** 로그인 화면 본문에
  "로그인 후 1시간 동안 입력이 없을 경우 자동으로 로그아웃됩니다." 라는 안내가 있어서,
  비로그인 상태를 로그인으로 잘못 읽는다. (2026-08-23 실측)
- 문구는 공백을 지운 뒤 **완전 일치**로 비교한다. 부분 일치는 "간편로그인 설정" 같은
  메뉴에 걸린다.
- href/onclick 의 `logout` / `selectLoginForm` 도 함께 본다. 다국어 화면에서도 통한다.
- `UNKNOWN` 일 때 **막지 않는다.** 사이트 개편으로 마커를 놓친 것뿐인데 앱이 영영
  시작되지 않는 편이 더 나쁘다. (§28 과 같은 원칙)

DOM 만 읽으므로 요청이 나가지 않는다 = 차단 위험이 없다.

## §28. DOM 변경 대응

selector 를 코드 곳곳에 하드코딩하지 않는다. **`KtxSelectors` 한 곳에 모으고**,
사이트가 바뀌면 Parser 계층만 고친다. UI 와 감시 엔진은 selector 를 모른다.

판정이 애매할 때는 막지 말고 통과시킨다 (§27-1 의 `UNKNOWN` 과 같은 이유).

## §29. 로그 시스템

`[18:42:11] TRAIN_COUNT=8` 형태의 상세 로그를 링 버퍼에 쌓고 `로그 보기` 로 보여준다.
파싱이 깨졌을 때 `TRAIN_COUNT` / `DOM_WARNING` / `PAGE_STATUS` 가 첫 단서다.

## §32. PC Extension 과의 공통화 (향후)

DOM Parser 는 플랫폼마다 따로 두더라도 **`domain/` 과 `SelectionEngine` 은 그대로
공유할 수 있게** Android 의존성을 넣지 않는다. 이 제약의 실질적 이유가 여기에 있다.

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
