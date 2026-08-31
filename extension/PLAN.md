# 크롬 확장 — [예매] 까지 눌러 주는 감시 (설계안)

**이 문서는 구현이 끝나면 지운다.** 진행 중에만 의미가 있다.
(`docs/KTX-MIGRATION.md` 와 같은 성격의 인수인계 문서다)

> **진행: M3+M4 코드 완료 (2026-08-30). 실제 사이트에서는 아직 한 번도 안 돌렸다.**
> 좌석 칸(1단계)과 하단 [예매](2단계)를 누른다. 자리는 `content/ktx/reserve.js` 하나이고,
> 드라이버는 **합성 클릭 하나뿐**이다 — M-a 실측을 기다리지 않고, 대신 **누른 뒤 반드시
> 확인하고 어긋나면 인계**하는 쪽으로 풀었다 (§E-2-2 개정). 실측은 이제 실사용 로그가 준다.
> 다음은 실제 사이트 확인 — 아래 §E-9 "다음 세션의 첫 걸음" 참조.

먼저 읽을 것 — **여기에 복사하지 않는다.**

| | |
|---|---|
| [`../CLAUDE.md`](../CLAUDE.md) | 대원칙 1~8. 확장이라고 예외가 되지 않는다 |
| [`../docs/DESIGN.md`](../docs/DESIGN.md) | **§38**(코레일 DOM 실측) · **§39**(대기열). 사이트에 대한 사실은 전부 여기 |
| [`README.md`](README.md) | 확장에서 갈리는 지점. 이 문서가 그 "정해야 할 것" 에 답한다 |
| [`../shared/README.md`](../shared/README.md) | selector 단일 출처 계획 |

이 문서의 절 번호는 **`§E-n`** 이다. `docs/DESIGN.md` 의 `§n` 과 섞이지 않게 하려는 것뿐이고,
사이트 동작을 인용할 때는 언제나 `§38-x` / `§39-x` 로 그쪽을 가리킨다.

---

## §E-0. 결론부터

만드는 것은 안드로이드 앱과 **같은 제품**이다. 조회는 사용자가 사이트에서 직접 하고,
확장은 체크해 둔 (열차 × 좌석등급) 칸이 열리는지만 보다가 알리고 [예매] 까지 눌러 준다.
결제는 사람이 한다. (대원칙 3)

옮겨 오면 그대로 되는 것이 90% 다. 진짜 문제는 **딱 둘**이고, 나머지 설계는 전부 이 둘에 붙는다.

| # | 문제 | 답 (§E-2, §E-3 에서 자세히) |
|---|---|---|
| 1 | **누가 클릭하는가** — `el.click()` 은 `isTrusted=false` 다 | 클릭 드라이버를 **갈아 끼울 수 있는 이음매**로 두고, 어느 것을 쓸지는 **실측으로** 정한다. 어느 드라이버든 실패하면 **사람에게 인계**(= 안드로이드의 `SEAT_SELECTED`)가 최종 안전망 |
| 2 | **감시 루프를 어디에 두는가** — 새로고침이 content script 를 죽인다 | **service worker** 에 둔다. content script 는 DOM 판독 팔일 뿐이다. SW 가 죽어도 이어갈 수 있게 상태를 `chrome.storage.session` 에 둔다 |

그리고 이 문서의 절반은 **예외 처리**(§E-6)다. 대기열과 "잔여석없음" 이 그 중심이다.

---

## §E-1. 레이어 — 안드로이드의 무엇이 무엇이 되는가

안드로이드의 경계(대원칙 8)를 그대로 옮긴다. **이름을 일부러 같게 둔다.**
한쪽을 고칠 때 다른 쪽의 대응물을 바로 찾을 수 있어야 한다.

```
popup(ui)  →  service worker                      →  content script      →  DOM
              ├ WatchController  (감시 루프 전체)      ├ ktx/parse.js        (KtxParserScript)
              ├ PageHost         (reload/goBack/클릭)  ├ ktx/login.js        (KtxLoginScript)
              ├ ClickDriver      (★ 새로 생긴 층)      ├ ktx/seat-select.js  (1단계)
              ├ SelectionEngine  (domain/, 순수)       ├ ktx/reserve.js      (2단계)
              ├ MatchNotifier                          └ ktx/tappoint.js     (좌표·hit test)
              └ WatchLog
```

| 안드로이드 | 확장 | 비고 |
|---|---|---|
| `watcher/WatchController.kt` | `background/watch-controller.js` | 루프·상한·예산. **거의 1:1 이식** |
| `watcher/WatchConfig.kt` | `background/watch-config.js` | 값과 근거 KDoc 까지 그대로 옮긴다 |
| `watcher/WatchState.kt` | `background/watch-state.js` | 상태·에러·`ReserveResult` 열거형 그대로 |
| `watcher/ReloadScheduler.kt` | `background/scheduler.js` | 사이클마다 무작위 간격 (대원칙 7) |
| `webview/PageHost.kt` | `background/page-host.js` | **인터페이스가 그대로 살아남는다.** 테스트 이음매 |
| `webview/KtxWebViewHost.kt` | `background/page-host.js` + `content/*` | 진짜 터치 자리가 `ClickDriver` 로 갈린다 |
| `webview/KtxSelectors.kt` | `content/ktx/selectors.js` | **1:1 미러.** 나중에 `shared/` 로 승격 (§E-8) |
| `webview/KtxParserScript.kt` | `content/ktx/*.js` | 문자열 생성이 아니라 그냥 함수다. 훨씬 읽기 쉬워진다 |
| `domain/` | `domain/` | chrome API 도 DOM 도 모르는 순수 코드. **규칙 유지** |
| `parser/KtxParser.kt` | `domain/` + `content/ktx/parse.js` | 판정은 순수 쪽, 추출은 content 쪽 |

**content script 는 판독과 좌표 계산만 한다.** 무엇을 누를지 정하는 것도, 언제 누를지 정하는 것도
service worker 다. 안드로이드에서 JS 가 "찾아서 좌표만 돌려주고" Kotlin 이 눌렀던 것과 같은 분업이다.

---

## §E-2. ★ 결정 1 — 누가 클릭하는가

### §E-2-1. 벽

content script 의 `el.click()` / `dispatchEvent` 는 `isTrusted = false` 다. WebView 와 같은 벽이고,
안드로이드가 `MotionEvent` 로 넘었던 그 자리에 확장에서는 대체물이 없다.

크롬에서 신뢰된 입력을 만드는 길은 **`chrome.debugger` + `Input.dispatchMouseEvent`** 하나뿐인데
대가가 둘이다.

1. **"…에서 이 브라우저를 디버깅하기 시작했습니다" 배너가 상시로 뜬다.** 사용자가 [취소] 를 누르면
   그 자리에서 detach 된다.
2. **코레일에는 개발자도구 감지가 있다** (`CODE : -8002`, §38-10). 디버거를 붙이는 것이
   그 감지에 걸리는지 **아직 아무도 모른다.** 걸리면 화면 전체를 덮는 모달이 뜨고 아무것도 눌리지 않는다.

즉 "디버거로 하면 된다" 가 아니라 **"디버거가 오히려 감시를 죽일 수 있다"** 가 실제 위험이다.

### §E-2-2. 그래서 드라이버를 갈아 끼울 수 있게 둔다

> **개정 (2026-08-30, M3 구현).** 드라이버를 실측 **전에** 정하지 않는다는 원칙은
> 유지하되, 그 방법을 바꿨다. 인터페이스를 미리 세 벌 만드는 대신 **`synthetic` 하나만
> 만들고, 누른 뒤 반드시 확인해서 어긋나면 인계**한다. 확인이 있으면 "잘못 눌린 채로
> 지나가는" 경로가 없으므로, 실측을 기다리지 않고도 안전하게 켤 수 있다.
> `debugger` 드라이버는 실사용 로그에 `SEAT_NOT_SELECTED` 가 쌓일 때 비로소 만든다.
> 아래 표는 그대로 유효하다 — `manual`(인계)이 최종 안전망인 것도 그대로다.

`ClickDriver` 인터페이스 하나에 구현 셋. `PageHost` 가 이 중 하나를 들고 있고,
**어느 것을 쓸지는 설정이 아니라 실측 결과가 정한다.**

```js
// background/click/driver.js
// tap(tabId, point) -> { delivered: bool, how: 'debugger'|'synthetic'|'manual', detail }
```

| 드라이버 | isTrusted | 배너 | 언제 쓰나 |
|---|---|---|---|
| `synthetic` | ✕ | 없음 | 사이트가 `isTrusted` 를 보지 않는 것이 **실측되면** 1순위 |
| `debugger` | ○ | 상시 | synthetic 이 막히고, **-8002 가 안 뜨는 것이 실측되면** |
| `manual` | — | 없음 | 위 둘이 다 막혔을 때. **누르지 않고 사람에게 넘긴다** |

`manual` 은 실패 상태가 아니라 **정상 종착지 중 하나**다. 안드로이드의 `WatchState.SEAT_SELECTED`
(§38-6-1) 와 정확히 같다 — 좌석 칸은 골라 둔 채 알림을 울리고 감시를 끝낸다.

### §E-2-3. 절대 하지 않는 것 — 드라이버 폴백

**한 사이클 안에서 드라이버를 바꿔 다시 누르지 않는다.** synthetic 으로 눌러 보고 안 되면
디버거로 또 누르는 것은 **재시도**이고, 대원칙 2 가 금지하는 바로 그것이다.
(게다가 첫 클릭이 서버에 닿았는지 아닌지를 우리는 모른다)

폴백의 방향은 "다른 방법으로 한 번 더" 가 아니라 **"사람에게 넘김"** 하나뿐이다.

### §E-2-4. 실측 프로토콜 — 추측으로 정하지 않는다

`ktxArmConfirm`(`KtxParserScript.kt:661`)이 이미 `e.isTrusted` 를 기록한다. 확장에서도 같은 훅을 쓴다.
**순서대로, 각각 한 번씩만** 한다. (대원칙 2 — 짧은 간격으로 되풀이하지 않는다)

| 측정 | 무엇을 | 요청이 나가나 | 판단 |
|---|---|---|---|
| **M-a** | 조회 결과 화면에서 좌석 칸 `a` 에 `armConfirm` → `el.click()` | 아마 안 나감 (화면 내 동작). **진단이 세어 준다** | `active` 가 붙으면 → React 는 합성 이벤트를 받는다 = 1단계 synthetic 가능 |
| **M-b** | `chrome.debugger.attach` 만 하고 아무것도 안 누른 채 조회 결과 화면을 5분 둔다 | 안 나감 | `-8002` 모달이 뜨면 **디버거 드라이버는 폐기**다 |
| **M-c** | 잔여석이 넉넉한 열차로 2단계까지 실제로 눌러 본다 | **나간다. 예매가 실제로 걸린다** | 사용자가 표를 살 의사가 있을 때 **한 번**. 결제하지 않으면 시간이 지나 자동으로 풀린다 |

M-c 전에 M-a·M-b 를 끝낸다. M-a 가 통하면 M-c 도 synthetic 으로 하고, 그것이 통하면
**디버거는 영영 안 써도 된다** — 배너도 -8002 위험도 없는 가장 좋은 결말이다.

#### M-a 를 재는 것은 만들어 두었다 — [클릭 진단]

팝업 **[로그 보기] → [클릭 진단]** (`content/ktx/probe.js`). 안드로이드의 [역 진단] 과
같은 자리, 같은 성격이다. **이 저장소에서 확장이 페이지를 누르는 유일한 코드**이고,
감시 루프는 부르지 않는다 — 감시 중이면 아예 거절한다.

- **두 번 눌러야 돈다.** 실수로 한 번 눌린 것과 구분되어야 한다.
- 대상은 **`AVAILABLE` 이고 아직 안 골라진 첫 칸**을 확장이 고르고 결과에 적어 준다.
  사용자가 체크해 둔 칸은 쓰지 않는다 — 그 칸은 매진인 것이 정상이라 실측이 무의미해진다.
- **[예매] 는 누르지 않는다.** 되돌릴 수 있는 자리까지만 간다 (대원칙 3).
  진단이 끝나면 좌석 칸 하나가 골라진 상태로 남는다. 되돌리기는 사용자가 새로고침.
- **devtools 를 열지 않는다.** 나간 요청은 `PerformanceObserver` 로 세고 URL 은
  쿼리스트링을 잘라서 남긴다 (조회 조건은 앱이 갖지 않는다 — 대원칙 4).
  §38-10 의 개발자도구 감지를 건드리지 않는 것이 이렇게 만든 이유다.
- **`hit`(좌표로 닿는가)은 판정에 섞지 않는다.** 합성 클릭은 좌표를 쓰지 않으므로
  섞으면 통한 클릭을 "닿지 않았다" 로 읽는다. 그 값이 필요한 것은 M-b 쪽이다.

읽는 법은 `docs/DESIGN.md §38-8` 에 있다.

> 결과를 `docs/DESIGN.md §38-8`("확인하지 못한 것") 옆에 적는다. 이 문서에 적지 말 것 —
> 이 문서는 지워질 것이고 사이트에 대한 사실은 `DESIGN.md` 에 한 벌만 있어야 한다.

### §E-2-5. 좌표 계산은 안드로이드보다 쉽다 (하지만 함정이 둘)

`Input.dispatchMouseEvent` 의 좌표는 **뷰포트 기준 CSS px** 이라, 안드로이드가 하던
`visualViewport → 위젯 픽셀` 환산(`ktxViewport`)이 통째로 필요 없다.
`getBoundingClientRect()` 값을 거의 그대로 쓴다.

그래도 `ktxTapPoint` / `ktxHitAt`(가운데부터 시도하고 `elementFromPoint` 로 "정말 이게 눌리는가"
확인)는 **그대로 옮긴다.** 가려진 요소를 누르는 사고는 플랫폼과 무관하다.

함정 둘:

- **페이지 줌.** `chrome.tabs.getZoom()` 이 1 이 아니면 좌표가 어긋난다.
  → 1 이 아니면 **누르지 않고** `NOT_TAPPABLE` 로 인계한다. (자동으로 줌을 바꾸지 않는다 —
  사용자 화면을 건드리는 짓이다)
- **배경 탭.** 비활성 탭에 보내는 입력은 렌더러가 처리하지 않을 수 있다.
  → **클릭 직전에만** `tabs.update({active:true})` + `windows.update({focused:true})` 로 앞으로 낸다.
  좌석을 잡는 순간에 화면을 뺏는 것은 오히려 사용자가 원하는 동작이다. (§E-6 의 예외 22)

사람 흉내는 안드로이드 값을 그대로 쓴다 — hold `60~160ms`, 뗄 때 `±1px` 흔들기
(`KtxWebViewHost.kt:1126` 의 `TAP_HOLD_*` / `TAP_JITTER_PX`).
`mouseMoved → mousePressed → (hold) → mouseMoved(jitter) → mouseReleased` 순으로 보낸다.

---

## §E-3. ★ 결정 2 — 감시 주기를 어디서 관리하나

`README.md` 의 "정해야 할 것 4번" 에 대한 답이다.

### §E-3-1. 후보와 탈락 이유

| 후보 | 탈락 이유 |
|---|---|
| content script 의 타이머 | **새로고침이 content script 를 죽인다.** 루프의 핵심 동작이 새로고침인데 그 동작이 루프 자신을 죽인다 |
| `chrome.alarms` | 최소 30초. 간격을 상한(3초)으로 잡아도 주 타이머가 될 수 없다 |
| offscreen document | 가능은 하다. 다만 통신 계층이 하나 더 늘 뿐 문제를 옮기기만 한다 |
| **service worker** | **채택.** 새로고침을 견디고, 탭 수명 이벤트를 받고, `chrome.debugger` 를 붙일 수 있는 유일한 자리 |

### §E-3-2. SW 가 죽는 문제는 "안 죽게" 가 아니라 "죽어도 되게" 로 푼다

MV3 SW 는 30초 놀면 죽는다. 다만 **감시 중에는 사실상 놀지 않는다** — 사이클마다,
그리고 대기 중에는 500ms 마다 content script 와 메시지가 오간다(유휴 타이머가 초기화된다).
위험 구간은 **새로고침 직후 content script 가 아직 없는 몇 초**뿐이다.

그래도 죽는 것을 전제로 만든다.

1. **상태는 `chrome.storage.session` 에 둔다.** 디스크에 남지 않고 브라우저를 닫으면 사라진다 —
   "이 선택은 저장하지 않는다"(대원칙 4)와 어긋나지 않는다.
   예외는 **감시 간격 하나뿐**이고 그것만 `storage.local` 이다. 감시 상태가 아니라 설정이라
   브라우저를 닫았다 열면 되돌아가면 안 된다. 대원칙 4 가 금지한 것은 조회 조건과
   체크한 칸이지 설정이 아니다 (안드로이드도 간격은 설정에 저장한다).
2. **`chrome.alarms` 30초 그물.** 주 타이머가 아니라 **부활 장치**다. 깨어나서 상태를 보고
   "감시 중인데 루프가 없네" 면 이어간다.
3. **클릭 도중에 죽었다면 절대 자동으로 다시 누르지 않는다.** 부활한 SW 는 `RESERVING` 상태를 보면
   곧바로 **인계 상태로 확정**하고 알림을 울린다. 눌렀는지 아닌지 모르는 채로 또 누르는 것이
   가장 나쁜 결과다.

### §E-3-3. 대원칙 7 은 그대로다

"JS `setInterval` 을 쓰지 않는다" 의 알맹이는 셋이다 —
**(a) 타이머가 감시 대상 페이지 안에 있으면 안 된다, (b) 간격은 사이클마다 무작위,
(c) 중지가 즉시 먹혀야 한다.**

확장에서는 (a) 를 "content script 에 두지 않는다" 로 읽는다. SW 의 `await sleep(random)` 은
페이지 밖이고, `AbortController` 로 즉시 끊긴다. 셋 다 지켜진다.

> **반영됨** — `CLAUDE.md` 대원칙 7 에 이 문장이 들어가 있다.
> 구현은 `background/scheduler.js` 다 (`sleep(ms, signal)` + `ReloadScheduler`).

---

## §E-4. 갱신은 새로고침이다 — PC 라고 [열차조회] 를 누르지 않는다

`README.md` 2번은 "PC 폭에서는 [열차조회] 가 보이니 누를 수도 있다" 로 열어 두었다.
**닫는다. 새로고침(`chrome.tabs.reload`)으로 간다.**

이유가 클릭 드라이버 때문만이 아니다.

| | 새로고침 (`tabs.reload`) | [열차조회] 클릭 |
|---|---|---|
| 조회 조건의 출처 | `localStorage["LS_TICKET_GENERAL"]` = **마지막으로 실제 조회한 조건** | 화면 폼의 **지금 값** |
| 사용자가 폼만 만지작거려 둔 경우 | 영향 없음 | **사용자가 조회하지 않은 조건으로 조회가 나간다** |
| `다음날 (…) 조회` 오폭 (§38-5) | 불가능 | 완전일치로 걸러야 한다. 틀리면 **다른 날짜를 조회한다** |
| 무게 | 문서+번들+조회 API | 조회 API 하나 |
| content script | 죽었다 살아난다 | 살아 있다 |

가벼운 쪽이 매력적이지만, **대원칙 4 를 어길 수 있는 쪽**이다. 무게는 간격으로 조절하면 되고
조건이 어긋나는 것은 조절할 수 없다. 새로고침으로 간다.
(가벼운 경로가 정말 필요해지면 §E-10 에 부록으로 남겨 뒀다)

`history.scrollRestoration = 'manual'` 은 §38-9 대로 **세 자리**에 그대로 건다.
확장에서의 대응물은 새로고침 직전 요청(나가는 이력 항목) / `document_start` content script(새 문서) /
목록이 그려진 뒤다. 한 자리라도 빼면 화면이 맨 밑으로 튄다.

---

## §E-5. 한 사이클

안드로이드 `WatchController.runLoop` 와 **같은 순서**다. 달라진 자리에 ★ 를 붙였다.

```
0) 시작 전 1회 : 로그인 확인 (LOGGED_OUT 이면 시작을 막는다, §27-1)
                 ★ 조회 서명 기록 (LS_TICKET_GENERAL 해시)

── 사이클 ────────────────────────────────────────────────────────
1) 갱신    첫 사이클은 건너뛴다 (사용자가 이미 보고 있는 화면)
           ★ 탭이 살아 있는가 → 아니면 중지
           chrome.tabs.reload(tabId)
           → onPageFinished 대응 = tabs.onUpdated status === 'complete'
           → 목록 렌더링 대기 (researchSettleMs 12초, 읽기만 한다)

2) 분석    ★ readSettledSnapshot — 화면이 확정될 때까지 제자리에서 다시 읽는다 (§39)
           ★ content script 가 아직 없으면 "확정 안 됨" 으로 본다 (오류가 아니다)
           예산: hasSeenList ? 3분 : 10초
           이 동안 새로고침은 한 번도 나가지 않는다

2-1) 로그인이 아직 살아 있는가 (사이클마다, DOM 읽기라 요청 없음, §27-1)
     ★ 조회 서명이 그대로인가 — 바뀌었으면 선택을 버리고 멈춘다 (§E-6-3 예외 17)

3) 판정    SelectionEngine.match(trains, selection)   ← 순수 로직, 이식만
4) 알림    새로 열린 칸만 (notifiedKeys, §20)
4-1) 자동 예매 ★ 탭 활성화 → 1단계 → 확인 → 2단계 → 결과 판정
5) 대기    [min, max] 무작위
```

**첫 사이클에 새로고침하지 않는 규칙(`skipFirstRefresh`)은 확장에서 더 중요하다.**
사용자는 방금 자기 손으로 조회한 화면을 보고 있고, 새로고침은 그 화면을 통째로 다시 만든다.

### 메시지 프로토콜 (SW → content script)

전부 **읽기**이고, 유일하게 페이지를 건드리는 것은 `SCROLL_TOP`(스크롤 위치)과,
나중에 붙을 `TAP_SYNTHETIC` 하나다. **✅ 는 M2 에서 구현된 것.**

| 메시지 | 안드로이드 대응 | 돌려주는 것 |
|---|---|---|
| ✅ `PARSE` | `KtxParserScript.build()` | `PageSnapshot` (status·trains·rowRefs·warnings) |
| ✅ `PAGE_KIND` | `buildPageKindScript` | `{list, rows, sig}` — 가벼운 판정 + DOM 서명 |
| ✅ `LOGIN` | `KtxLoginScript.build()` | `{state, detail}` |
| ✅ `QUERY_SIG` | (없음, ★ 신규) | `LS_TICKET_GENERAL` **해시만.** 원문도 안 보내고 쓰지도 않는다 |
| ✅ `SCROLL_TOP` | `buildScrollTopScript` | scrollRestoration 끄기 + 맨 위로. (설계 때 `PREP_RELOAD` 라 부르던 것. 새로고침 **직전**과 목록이 그려진 **뒤** 두 번 쓰므로 이름을 바꿨다 — §38-9 의 세 자리 중 둘이고, 나머지 하나가 `content/early.js` 다) |
| `SELECT_LOCATE` | `buildSelectScript` | `{found, tappable, x, y, reason…}` |
| `SELECT_CONFIRM` | `buildSelectConfirmScript` | `{selected, activeCount, detail}` |
| `RESERVE_LOCATE` | `buildReserveScript` | `{found, tappable, x, y, label, barLabel}` |
| `RESERVE_RESULT` | `buildReserveResultScript` | `{soldOut, detail}` |
| `TAP_CONFIRM` | `buildTapConfirmScript` | `{fired, trusted, onTarget}` ← **드라이버 검증의 핵심** |
| `TAP_SYNTHETIC` | (없음, ★ synthetic 드라이버 전용) | `el.click()` 실행 |

---

## §E-6. 예외 처리 — 이 문서의 절반

원칙 셋을 먼저 못 박는다. 아래는 전부 이 셋의 따름정리다.

1. **요청이 나갔는가로 나눈다.** 안 나간 실패만 다음 사이클에 다시 시도한다. (대원칙 2)
2. **애매하면 막지 말고 통과시킨다.** 단, **되돌릴 수 없는 동작(클릭) 앞에서는 반대다** —
   애매하면 누르지 않는다. (대원칙 6 + 대원칙 3)
3. **판정할 수 없는 것은 판정하지 않는다.** 대기열을 알아보려 들지 않는다. (§39-2)

### §E-6-1. 대기열 / 로딩 중 (★)

`docs/DESIGN.md §39` 를 그대로 옮기되, 확장에서 **새로 생기는 경로가 셋** 있다.
안드로이드에서 `UNKNOWN_PAGE` 하나로 뭉뚱그려지던 것이 확장에서는 이렇게 들어온다.

| 확장에서 관찰되는 것 | 뜻이 될 수 있는 것 | 처리 |
|---|---|---|
| content script 가 `TRAIN_LIST` 를 돌려줌 | 목록이 그려졌다 | **확정.** 그대로 진행 |
| content script 가 `UNKNOWN_PAGE` 를 돌려줌 | 대기 화면 / 아직 그리는 중 / 엉뚱한 화면 | **기다린다** (예산 소진까지) |
| ★ `sendMessage` 가 "Could not establish connection" | 문서 로딩 중 / **NetFunnel 이 다른 오리진으로 튕김** / 사용자가 딴 데로 감 | **기다린다.** 오류로 세지 않는다 |
| ★ 탭은 살아 있는데 계속 응답 없음 | 위 셋을 구분할 수 없다 | 예산 소진 → `UNKNOWN_PAGE` **1회** 소모 |
| ★ 탭이 사라짐 (`onRemoved`) | 확정 | 즉시 중지 + 알림 |

**"content script 없음" 을 오류로 세면 안 된다.** 이것이 확장에서 가장 쉽게 저지를 실수다.
새로고침 직후에는 **항상** 몇 초 동안 이 상태가 되고, 그것을 `maxConsecutiveErrors` 로 세면
정상 동작만으로 3사이클 만에 감시가 죽는다.

지켜야 할 선 (§39 그대로):

- **예산 안에서 새로고침은 단 한 번도 나가지 않는다.** 대기 중 새로고침 = 대기 순번 소멸.
- **`isSettled` 인 상태는 기다리지 않는다.** 차단·로그인·세션만료·`NO_TRAIN` 은 첫 판독에서 즉시 나간다.
- **예산은 둘.** 목록을 본 적 있으면 3분, 없으면 10초. 짧은 쪽을 지우면 그것이 "멍때림" 이다.
- **상한이 세는 것은 요청이지 판독이 아니다.** 판독을 몇 번 하든 카운터는 안 늘어난다. (§39-5)
- 로그 `PAGE_WAIT_START/TICK/DONE/TIMEOUT` 그대로. **정상이면 한 줄도 안 나온다.** (§39-7)
- 기다리는 동안 팝업에 경과 시간을 흘려 준다. 멈춘 것처럼 보이면 사용자는 죽은 줄 안다.

★ 확장 고유의 판단 하나: **탭 URL 을 보고 대기열인지 알아보려 하지 않는다.**
그러려면 `tabs` 권한(모든 탭의 URL)이나 `nf.letskorail.com` host permission 이 필요한데,
얻는 것은 진단 문구 한 줄뿐이고 §39-2 가 "알아볼 필요가 없다" 고 이미 결론 낸 문제다.
**권한을 넓히지 않는 대가로 "대기열" 과 "사용자가 딴 데로 갔다" 를 구분하지 않는다.**
둘 다 예산을 다 쓰고 멈춘다 — 어느 쪽이든 사람이 봐야 하는 상황이라 결말이 같다.

### §E-6-2. 빈 좌석 없음 (★)

"빈 좌석이 없다" 는 **네 가지 다른 사건**이고 처리가 전부 다르다. 뭉뚱그리면 안 된다.

| # | 무엇 | 화면 | 요청이 나갔나 | 처리 |
|---|---|---|---|---|
| 1 | 체크한 칸이 아직 매진/예약대기 | 목록 정상 | — | **정상이다.** `noMatch` 로 다음 사이클. 오류도 알림도 없다 |
| 2 | 조회 결과가 0건 (`NO_TRAIN`) | 결과 컨테이너는 있고 목록이 빔 | — | **확정 상태다.** 기다리지 않고 다음 사이클. 감시는 계속 |
| 3 | 1단계를 누르려는데 그새 매진으로 바뀜 | 목록 정상 | 안 나감 | `CELL_NOT_FOUND`. 알림만 남기고 그 자리에서 다시 누르지 않는다 |
| 4 | **2단계를 눌렀더니 "잔여석없음"** | 안내 화면/모달 | **나갔다** | §19-2 경로 (아래) |

1번과 2번은 **오류가 아니다.** 확장에서 이걸 오류로 처리하면 취소표 감시가 성립하지 않는다 —
대부분의 사이클은 1번으로 끝나는 것이 정상이다.

4번이 진짜 예외 처리다. **남이 먼저 잡은 것**이고, 취소표를 노릴 때는 드문 일이 아니다.

```
2단계 클릭 → 화면 변화 관찰 → 실패 문구 확인
   ├ 실패 문구 없음 → CLICKED. 감시 종료 + 결제 재촉 알림 (§19-3)
   └ 실패 문구 있음 → SOLD_OUT
        soldOutCounts[key]++
        ├ < maxSoldOutRetries(3) → reserveAttemptedKeys 에서 뺀다 (다음 사이클에 다시 눌러 볼 수 있게)
        └ ≥ 3                    → 그 칸은 더 누르지 않고 알림만
        chrome.tabs.goBack(tabId)      ← ★ WebView.goBack() 의 대응물
        └ 목록이 실제로 보이는가 확인 (SPA 라 한 칸이 목록이라는 보장이 없다, §38-8)
             ├ 보임   → 감시 계속 (발견으로 치지 않는다)
             └ 안 보임 → 감시 중지. 그 화면에서 다른 것을 더 누르지 않는다
```

**되돌리기는 뒤로 가기만.** 안내 화면의 [확인] 은 조회 폼을 새로 열어 사용자의 조회 조건을
초기화한다. 되돌리기 한 번이 감시 전체를 망친다. (대원칙 5)

**재시도는 다음 사이클에 좌석이 다시 열려 보일 때만.** 실패한 자리에서 곧바로 다시 누르지 않는다.

★ 확장에서 다시 봐야 할 두 가지 (실측 필요, §E-7):

- `RESERVE_FAILED_MARKERS` 는 **아직 SRT 문구 그대로**다 (§38-8). 코레일 실제 문구를 모른다.
- 안드로이드의 실패 판정은 "본문에 실패 문구 **+ 열차 목록이 사라짐**" 이다.
  코레일이 이 안내를 **모달로** 띄우면 목록이 뒤에 남아 있어 **판정이 통째로 빗나간다.**
  브라우저에서는 이 확인이 쉽다 — 실측해서 조건을 보정할 것.

### §E-6-3. 전체 예외 목록

**A. 페이지·판정** (안드로이드와 같음, 이식만)

| 상황 | `WatchError` | 처리 |
|---|---|---|
| 차단 문구 (`BLOCKED_MARKERS`) | `BLOCKED` | **즉시 중지.** 재시도 없음 |
| `CODE : -8002` 매크로/개발자도구 감지 모달 (§38-10) | `BLOCKED` | 즉시 중지. ★ **디버거 드라이버를 쓰고 있었다면 그것부터 의심** (§E-2-1) |
| 로그인 화면 / 세션 만료 | `LOGIN_REQUIRED` / `SESSION_EXPIRED` | 중지 + 알림. `isSettled` 라 기다리지 않는다 |
| 감시 중 로그아웃 (사이클마다 확인) | `SESSION_EXPIRED` | 중지 + 알림. 좌석이 열린 순간 튕기는 것보다 낫다 (§27-1) |
| DOM 판독 실패 | `DOM_PARSE_ERROR` | 연속 `maxConsecutiveErrors`(3)회면 중지 |
| 문서 로딩 타임아웃 | — | `Settled` 로 보고 **분석은 한 번 해 본다** (대원칙 6) |
| 오프라인 / reload 실패 | `NETWORK_ERROR` | 연속 3회면 중지 |

**B. 예매** (안드로이드와 같음)

| 상황 | 결과 | 처리 |
|---|---|---|
| 편성을 다시 못 찾음 (목록이 갱신됨) | `ROW_NOT_FOUND` | 알림만. 안 누른다 |
| 칸을 특정 못 함 / 그새 매진 | `CELL_NOT_FOUND` | 알림만 |
| 1단계 눌렀는데 `active` 안 붙음 | `SEAT_NOT_SELECTED` | **그 자리에서 다시 안 누른다** (요청이 나갔을 수 있다) |
| `active` 가 **여러 칸**에 붙음 | `SEAT_NOT_SELECTED` | 무엇을 고른 건지 확신할 수 없으면 2단계로 안 간다 |
| 2단계 버튼이 `예약대기신청` / `입석+좌석 예매` | `NOT_ALLOWED` | **인계.** 좌석은 골라 둔 채 사람에게 (§38-6-1) |
| 하단 바 등급이 고른 등급과 다름 | `MISMATCH` | 인계 |
| 눌렀는데 아무 변화 없음 | `NO_CHANGE` | 인계 |
| 잔여석없음 | `SOLD_OUT` | §E-6-2 |
| 되돌리기 실패 | — | 중지 + 안내 |

**C. 확장 고유** (★ 새로 설계해야 하는 것)

| # | 상황 | 처리 | 왜 |
|---|---|---|---|
| 14 | content script 미주입 / 통신 실패 | **대기**로 취급. 오류로 세지 않는다 | 새로고침마다 정상적으로 겪는 상태다 (§E-6-1) |
| 15 | 탭이 다른 오리진(NetFunnel 등)으로 이동 | 대기 | 판정하지 않는다 (§39-2) |
| 16 | 탭 닫힘 / `onReplaced` / discard | **즉시 중지** + 알림 | 감시 대상이 없어졌다 |
| 17 | **사용자가 조회 조건을 바꿔 다시 조회함** | 선택을 **버리고** 중지 + 안내 | 체크는 "그 조회 결과 화면" 에만 의미가 있다 (대원칙 4). 판정은 `LS_TICKET_GENERAL` **해시 비교**로만 하고, **절대 쓰지 않는다** |
| 18 | SW 종료 후 부활 | `storage.session` 에서 복구. **`RESERVING` 이었으면 인계로 확정** | 눌렀는지 모르는 채 또 누르지 않는다 (§E-3-2) |
| 19 | 확장 재로드/업데이트 중 감시 | 중지 처리 + 알림 | 조용히 사라지면 사용자는 감시 중인 줄 안다 |
| 20 | 디버거 detach (사용자가 배너 [취소]) / devtools 이미 열림 | 그 드라이버를 **사용 불가로 표시**하고 인계 | `attach` 실패는 재시도 대상이 아니다 |
| 21 | 페이지 줌 ≠ 100% | **누르지 않고** 인계 | 좌표가 어긋난다. 사용자 줌을 확장이 바꾸지 않는다 |
| 22 | 탭 비활성 / 창 최소화 | **클릭 직전에만** 앞으로 낸다 | 배경 탭 입력은 신뢰할 수 없다. 갱신은 배경에서도 한다 |
| 23 | 코레일 탭이 여럿 / 감시 중복 시작 | **전역 1개만.** 다른 탭의 팝업은 "다른 탭에서 감시 중" | 두 탭이면 요청이 두 배다 (대원칙 2) |
| 24 | 알림 권한 없음 / 방해 금지 모드 | 로그에 남기고 계속. 소리는 offscreen 으로 (§E-6-4) | 알림이 안 되는 것이 감시를 멈출 이유는 아니다 |

### §E-6-4. 결제 재촉 알림 (§19-3)

좌석을 잡은 뒤 사용자가 못 보고 지나가면 잡은 좌석을 그대로 잃는다.
`reserveReminderIntervalMs`(10초)마다, `reserveReminderMaxDurationMs`(10분)까지 되풀이한다.

확장에서의 차이 하나: **MV3 SW 는 소리를 낼 수 없다** (DOM 이 없다).
`chrome.offscreen` 문서를 `AUDIO_PLAYBACK` 사유로 하나 띄워서 거기서 울린다.
알림 자체는 `chrome.notifications` + `requireInteraction: true` + 버튼 `[알림 끄기]` `[탭 열기]`.

멈추는 경로 넷은 안드로이드와 같다 — 감시 종료 / 알림 끄기 / 계속 감시 / 감시 재시작.
여기에 10분 시간 상한이 더해진다. (코레일이 그때 좌석을 도로 푼다)

### §E-6-5. 안드로이드에서 일부러 안 가져오는 것 — §27-2 자동 로그인 이동

안드로이드는 메인 화면에서 비로그인이 확실하면 **스스로 `/ticket/login` 으로 보낸다.**
그 앱에서는 WebView 가 앱의 것이라 정당했다.

**확장에서는 하지 않는다.** 사용자의 탭은 사용자의 것이고, 확장이 조용히 주소를 갈아 끼우는 것은
브라우저에서 명백히 적대적인 동작이다. 대신 팝업에
`로그인이 필요합니다 [로그인 화면 열기]` 를 띄우고 **사용자가 눌렀을 때만** 이동한다.

로그인 **판정** 자체는 그대로다 — 머리말 `btnGoLogin`/`btnGoLogout`, **가시성은 보지 않고 DOM 존재만**,
`button.logoutBtn` 과 본문 텍스트 "로그아웃" 은 쓰지 않는다. (§38-7)
PC 폭에서는 머리말이 실제로 보이지만, 판정 규칙을 폭에 따라 나누면 두 벌이 되고 반드시 어긋난다.

---

## §E-7. 실측이 필요한 것

**추측으로 채우지 말 것.** 확인되면 `content/ktx/selectors.js` 와 `docs/DESIGN.md §38-8` 을 고친다.

| 무엇 | 어디에 반영 | 지금 상태 |
|---|---|---|
| §E-2-4 의 M-a / M-b / M-c | `ClickDriver` 기본값 | **아무것도 모른다.** 첫 번째로 할 일 |
| 예약 실패 실제 문구 | `RESERVE_FAILED_MARKERS` | SRT 값 그대로 |
| 실패 안내가 모달인가 화면 전환인가 | 실패 판정 조건 | 모달이면 판정이 빗나간다 (§E-6-2) |
| 아무 칸도 안 고른 상태의 `ticket_reserv_wrap` | 2단계 탐색 | DOM 에 없는지 숨겨져만 있는지 모름 |
| SPA 뒤로 가기가 조회 결과로 돌아가는가 | `goBack` 처리 | 확인 후 목록 유무로 검증 중 |
| 조회 폼 출발일 입력 | `SEARCH_DATE_FIELDS` | 비어 있음 (표시용이라 감시엔 영향 없음) |
| ITX·무궁화 섞인 결과의 `flag_wrap` | `TRAIN_TYPE` | KTX-산천만 실측됨 |
| 자유석/입석이 별도 `price_box` 인가 | `SeatClass` 유지 여부 | 아니면 2개 유지 가능 (큰 파급 회피) |

**브라우저 확장은 이 실측을 안드로이드보다 훨씬 싸게 할 수 있다.** `chrome://inspect` 없이
devtools 를 바로 열 수 있다 — 다만 §38-10 의 개발자도구 감지가 있으므로
**devtools 를 연 채로 예매를 시도하지 말 것.** 판독 실측과 클릭 실측을 분리한다.

**그리고 짧은 간격으로 실제 사이트를 반복하지 않는다.** (대원칙 2, SRT IP 차단은 실제로 있었다)

---

## §E-8. 파일 구조와 권한

```
extension/
├── manifest.json
├── src/
│   ├── background/
│   │   ├── index.js            SW 진입. 메시지 라우팅 · 탭 수명 · alarms 그물
│   │   ├── watch-controller.js ★ 감시 루프 (watcher/WatchController.kt)
│   │   ├── watch-config.js     (watcher/WatchConfig.kt) — 값과 근거 KDoc 이식
│   │   ├── watch-state.js      (watcher/WatchState.kt)
│   │   ├── scheduler.js        (watcher/ReloadScheduler.kt)
│   │   ├── page-host.js        (webview/PageHost.kt + KtxWebViewHost.kt)
│   │   ├── click/
│   │   │   ├── driver.js       ClickDriver 인터페이스 + 선택 (§E-2)
│   │   │   ├── debugger.js     chrome.debugger + Input.dispatchMouseEvent
│   │   │   ├── synthetic.js    el.click()  — isTrusted=false
│   │   │   └── manual.js       누르지 않고 인계
│   │   ├── notifier.js         (notification/MatchNotifier.kt) + offscreen 소리
│   │   ├── logger.js           (watcher/WatchLog.kt) — LogCode 이름 그대로
│   │   └── store.js            chrome.storage.session
│   ├── content/
│   │   ├── early.js            document_start: scrollRestoration='manual'
│   │   ├── index.js            document_idle: 메시지 핸들러
│   │   └── ktx/
│   │       ├── selectors.js    ★ (webview/KtxSelectors.kt) 1:1 미러
│   │       ├── tappoint.js     ktxTapPoint / ktxHitAt / armConfirm
│   │       ├── parse.js        목록 분석
│   │       ├── page-kind.js    화면 종류 + DOM 서명
│   │       ├── login.js        (webview/KtxLoginScript.kt)
│   │       ├── seat-select.js  1단계 탐색·확인
│   │       └── reserve.js      2단계 탐색·확인·결과
│   ├── domain/                 ★ chrome API 도 DOM 도 모른다
│   │   ├── seat-class.js  seat-status.js  train.js  train-key.js
│   │   └── watch-selection.js  selection-engine.js  match.js
│   └── ui/
│       └── popup.html  popup.js  popup.css
└── test/                       node --test (의존성 0)
    ├── selection-engine.test.mjs
    ├── train-key.test.mjs
    └── watch-controller.test.mjs   ← PageHost 를 가짜로 갈아 끼운다
```

**빌드 도구는 두지 않는다** (`README.md` "정해야 할 것" 4번째에 대한 답).
순수 ES 모듈이면 번들러 없이 돈다. 정적 선언된 content script 는 ES 모듈이 아니므로,
`content/index.js` 하나를 진입점으로 두고 필요하면 `import()` 로 나눈다.
테스트는 Node 내장 `node --test` — 의존성이 0 이라 `npm install` 조차 필요 없다.

### 권한

```jsonc
// M2 현재 (실제 manifest)
"permissions":  ["storage", "notifications", "alarms"],
"host_permissions": ["https://www.korail.com/*"]

// 나중에 붙는 것
// "offscreen"            결제 재촉 알림의 소리 (M4, §E-6-4)
// "scripting"            content script 를 뒤늦게 꽂아야 할 때만. 지금은 필요 없다
// "optional_permissions": ["debugger"]   ★ 실측(M2.5) 뒤에, 그것도 선택 권한으로
```

`alarms` 는 **감시 주기가 아니다.** 최소 30초라 그럴 수 없고, service worker 가 죽었을
때를 위한 부활 그물이다 (§E-3-2). 감시 중에만 걸고 루프가 끝나면 걷는다.

- **`debugger` 는 `optional_permissions` 다.** 설치할 때부터 "브라우저 디버깅" 권한을 요구하면
  대부분의 사용자가 거기서 멈춘다. synthetic 이 통하면 영영 안 물어봐도 된다 (§E-2-4).
- **`tabs` 는 넣지 않는다.** 모든 탭의 URL 을 읽는 권한이고, 얻는 것은 진단 문구 한 줄뿐이다. (§E-6-1)
- `chrome.storage.session` 은 기본이 신뢰 컨텍스트 전용이다. content script 가 읽어야 하면
  `setAccessLevel` 이 필요한데 — **필요 없게 설계한다.** 상태는 SW 만 갖는다.
- MAIN world 주입은 필요 없다. `localStorage` 도 React 핸들러도 isolated world 에서 닿는다.
- 아이콘이 아직 없다. `chrome.notifications` 는 아이콘을 요구한다.

### `shared/` 승격

`shared/README.md` 의 계획대로, **확장 파서가 실제로 돌기 시작한 뒤에** 한다.
`content/ktx/selectors.js` → `shared/ktx-selectors.json` 으로 옮기고,
안드로이드에 **`KtxSelectors.kt` 가 그 JSON 과 일치하는지 검사하는 단위 테스트**를 둔다.
지금 미리 쪼개면 형식만 정하고 검증은 못 한다.

---

## §E-9. 마일스톤

각 단계는 **그 자체로 쓸 수 있는 상태**로 끝난다. 중간에 멈춰도 반쪽이 남지 않게.

| | 무엇 | 끝났다고 할 수 있는 조건 |
|---|---|---|
| **M1** ✅ | 판독만. 팝업에 [열차 선택] 목록이 뜬다 | 조회 결과 화면에서 편성·번호·시각·좌석 상태가 안드로이드와 **같은 값**으로 읽힌다. **아무것도 누르지 않고 새로고침도 안 한다** |
| **M2** ✅ | 감시 루프 + 알림 | 체크한 칸이 열리면 알림. 대기열이 걸리면 `PAGE_WAIT_*` 가 쌓이고 **그 사이 새로고침이 한 줄도 안 나간다** (§E-6-1) |
| **M2.5** | **실측 M-a / M-b** (§E-2-4) | 도구는 둘이다 — 팝업 [클릭 진단], 그리고 **그냥 감시했을 때의 로그**. <br>후자가 `RESERVE_SEAT_SELECTED` 면 `SYNTHETIC_OK` 와 같은 뜻이다. 결과는 `DESIGN.md §38-8` 에 |
| **M3** ✅ | 1단계 (좌석 칸 고르기) | 좌석이 열리면 그 칸에 `active` 가 붙고 하단 바가 뜬다. 안 붙으면 **멈추고 사람을 부른다** |
| **M4** ✅ | 2단계 [예매] + 잔여석없음 경로 | `goBack` 후 목록 복귀까지 구현. **실측 M-c 는 아직** — 실제 문구를 몰라 `RESERVE_NOTICE` 로 남기고 인계한다 |
| **M5** | `shared/` 승격 + 안드로이드 일치 테스트 | selector 를 한쪽만 고치면 테스트가 깨진다 |

M3·M4 는 코드가 있을 뿐 **실제 코레일에 대고는 한 번도 안 눌러 봤다.**
검증은 `test/browser/reserve.html`(모형 DOM)까지다 — 거기서 지키는 것은 그 폴더의 README 에.

M3 이 "항상 인계로 끝난다" 인 것이 중요하다. **1단계는 되돌릴 수 있고 2단계는 없다.**
1단계까지만 자동화해도 제품으로서 쓸모가 있고(좌석을 골라 놓고 알려 준다),
2단계를 붙이기 전에 실제 사용으로 검증할 시간을 번다.

### 다음 세션의 첫 걸음

**M4 까지 코드가 있다.** 다만 **누르는 코드는 실제 코레일에 대고 한 번도 안 돌렸다.**
아래 3번(M2 확인)까지는 실사용 로그로 확인됐고, **4번부터가 남은 것**이다.

0. ★ **확장을 다시 불러온다.** `chrome://extensions` 새로고침(↻) **+ 코레일 탭 F5.**
   팝업 HTML/JS 는 열 때마다 디스크에서 새로 읽히지만 **service worker 와 content
   script 는 아니다.** 그래서 "팝업에는 새 버튼이 있는데 누르면 아무 반응이 없고,
   로그는 이전 커밋 그대로" 인 상태가 만들어진다 — 2026-08-30 에 실제로 여기서 헤맸다.
   **구분법: 로그에 `RESERVE_START` 가 있으면 새 코드다.** 없이 `MATCH_DETAIL` →
   `WATCH_STOP` 으로 바로 넘어가면 M2 빌드가 돌고 있는 것이다.
1. 이 문서와 `docs/DESIGN.md §38·§39` 를 읽는다.
2. **M1 확인 (요청이 나가지 않는다)** — 실제 조회 결과 화면에서 팝업 목록이 안드로이드와
   **같은 값**으로 뜨는지. 특히 `sold_out_soon`(매진임박) 칸이 **예약가능**으로, 같은 시각의
   두 편성이 **다른 열차**로 읽히는지. 어긋나면 고칠 곳은 `content/ktx/selectors.js` 와
   `content/ktx/parse.js` 뿐이다.
3. **M2 확인 (여기서부터 요청이 나간다 — 대원칙 2, 짧은 간격으로 되풀이하지 말 것)**
   - 로그아웃 상태로 [감시 시작] → 막히고 [로그인 화면 열기] 가 뜨는가.
   - 감시 시작 → `RESEARCH_TRIGGERED` 가 사이클마다 **한 줄씩만** 나오는가.
     조회 조건(구간·날짜·시각·인원)이 새로고침 뒤에도 그대로인가.
     → 아니면 `localStorage["LS_TICKET_GENERAL"]` 부터 본다.
     **2026-08-30 실사용 로그로 확인됨** — 사이클당 한 줄, 조회 조건 유지, 대기열 없음,
     `CONTENT_ABSENT` 없음. 같은 로그에서 **간격이 SRT 시절 값(0.1~0.3초)이라 분당 46회로
     돌고 있던 것**이 드러나 기본값을 0.3~0.5초로 올리고 팝업에 조절 UI 를 넣었다 (§38-9).
   - `researchSettleMs`(12초) 안에 목록이 다시 그려지는가. `PAGE_SETTLE_TIMEOUT` 이 계속
     뜨면 **간격이 아니라 이 값을** 늘린다.
   - **접속 대기열에 걸렸을 때** — `PAGE_WAIT_START` → `PAGE_WAIT_TICK` 이 쌓이는 동안
     `RESEARCH_TRIGGERED` 가 **한 줄도 없어야 한다.** 있으면 대기 순번이 날아간다. (§39-7)
   - 좌석이 열리면 알림이 뜨고 감시가 멈추는가.
   - 사이트에서 조회 조건을 바꿔 보기 → `QUERY_CHANGED` 로 멈추고 체크가 비워지는가.
4. **M3 확인 (여기서부터 페이지를 누른다).**
   **devtools 를 연 채로 예매를 시도하지 말 것** (§38-10 매크로 감지).
   - 좌석이 열리면 로그에 `RESERVE_START` → `RESERVE_CLICKED` → 아래 중 하나가 나온다.
     **끝나는 줄이 없으면 누르다 만 것이다.**

   | 끝나는 줄 | 뜻 | 다음에 할 일 |
   |---|---|---|
   | `RESERVE_SEAT_SELECTED` → `RESERVE_SUCCEEDED` | 다 됐다. 결제만 사람이 | — |
   | `RESERVE_SEAT_SELECTED` → `RESERVE_HANDOVER` | 1단계는 통했고 2단계에서 멈췄다 | `RESERVE_NOTICE` 와 하단바 버튼 문구를 본다 |
   | `RESERVE_FAILED=SELECT/SEAT_NOT_SELECTED` | **합성 클릭이 안 통한다** (= `NO_EFFECT`) | 그제서야 M-b(디버거) |
   | `RESERVE_SOLD_OUT` | 남이 먼저 잡았다 | `RESERVE_NOTICE` 문구를 `RESERVE_FAILED_MARKERS` 에 반영 |

   - `RESERVE_NOTICE` 는 **§38-8 의 M-c 기록 자리에 옮겨 적는다.** 지금 마커는 SRT 값이다.
   - `RESERVE_CLICKED` 의 `trusted=false` 는 **정상이다.** 확장의 클릭은 원래 그렇다.
   - **누르는 것을 끄는 스위치는 없다.** 감시를 시작하면 반드시 여기까지 온다 —
     "알림만 받고 싶다" 는 경로를 두지 않았다 (`background/watch-config.js` 머리말).

---

## §E-10. 부록 — 가벼운 갱신 경로 (지금은 안 한다)

§E-4 에서 접어 둔 것. 클릭 드라이버가 검증된 **뒤에만** 다시 볼 것.

PC 폭에서는 [열차조회](`button.btn_bn-blue`, 문구 **완전일치**)가 실제로 보인다. 이것을 누르면
조회 API 하나만 나가고 content script 도 살아남는다. NetFunnel 대기열에도 덜 걸릴 가능성이 있다.

그래도 기본값으로 삼지 않는 이유는 §E-4 의 표에 있다 — **화면 폼의 지금 값으로 조회가 나간다.**
사용자가 폼을 만지작거려 두었으면 사용자가 조회하지 않은 조건으로 조회된다.
쓴다면 반드시:

- 문구 **완전일치** (`다음날 (…) 조회` 오폭 방지, §38-5)
- 누르기 전 `LS_TICKET_GENERAL` 해시가 감시 시작 때와 같은지 확인
- 어긋나면 그냥 새로고침으로 떨어진다
