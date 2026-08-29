# Catch Train — 작업 지침

Android WebView 로 코레일(KTX) 조회 결과 화면을 감시하다가, 사용자가 체크해 둔 칸이
열리면 알리고 그 칸을 골라 [예매] 를 눌러 주는 앱.

```
코레일 사이트에서 사용자가 직접 조회
  → 결과 목록을 DOM 으로 읽어 [열차 선택] 목록 생성
  → 사용자가 (열차 × 좌석등급) 칸 체크
  → 감시 시작: [열차조회] 실제 터치 → DOM 분석 → 체크한 칸이 AVAILABLE?
  → 알림 → 그 칸 터치(1단계) → 하단 바 [예매] 터치(2단계) → RESERVED
     (여기서 끝. 결제는 사용자)
```

---

## 이 문서의 규칙

이 파일에는 **코드를 읽어서 알아낼 수 없는 것만** 둔다.
클래스 목록·시그니처·구현 절차는 쓰지 않는다 — 코드가 정답이고, 여기 적으면 썩는다.

여기 남는 것: 왜 그렇게 했는가, 무엇을 하면 안 되는가, 실측으로 알아낸 사이트 동작.
상세는 [`docs/`](docs/) 로 보낸다.

| 문서 | 내용 | 언제 읽나 |
|---|---|---|
| [`docs/KTX-MIGRATION.md`](docs/KTX-MIGRATION.md) | **★ 지금 진행 중인 일.** 어디까지 했고 뭐가 남았나 | **작업을 시작할 때 먼저** |
| [`docs/DESIGN.md`](docs/DESIGN.md) | 설계 규칙 + 실측 메모. **코드 KDoc 이 `§번호` 로 참조한다** | 동작을 바꾸기 전 |
| [`docs/RELEASE.md`](docs/RELEASE.md) | 서명 키 / 버전 올리기 / APK 배포 | 배포할 때만 |
| [`docs/HISTORY.md`](docs/HISTORY.md) | 원설계 의도, 폐기된 모델, 로드맵 | 거의 안 읽어도 된다 |
| [`INSTALL.md`](INSTALL.md) | 최종 사용자에게 APK 와 함께 주는 안내문 | 배포할 때만 |

**`docs/DESIGN.md` 의 § 번호는 바꾸지 않는다.** 코드 주석 100여 곳이 참조한다.
내용을 고치는 것은 자유지만 번호를 다시 매기지 말 것. 절이 없어지면 번호를 비워 둔다.
(코드 KDoc 의 `DESIGN.md §N` 은 전부 `docs/DESIGN.md` 를 가리킨다. 예전에 루트에 있었다)

---

## 대원칙

바꾸려면 근거가 필요한 것들. 대부분 실측으로 얻었고, 어긴 흔적이 보이면 되돌린다.

### 1. 갱신은 "사람이 누르는 것과 같은 입력" 뿐이다

화면의 [열차조회] 버튼 좌표에 실제 `MotionEvent` 를 내려보낸다. **대체 경로는 없다.**

| 안 되는 방법 | 이유 |
|---|---|
| 조회 URL 직접 호출 (`loadUrl`, `a[href]`) | 사람 조작에서 나올 수 없는 요청 → 사실상 항상 차단 |
| `WebView.reload()` | AJAX 로 그린 화면이라 같은 결과를 보장하지 않는다 (사용자가 넣은 조건도 날아간다) |
| JS `el.click()` / `dispatchEvent` | `isTrusted=false` 합성 이벤트 |

따름정리: **WebView 가 화면에 보여야 동작한다.** 사람이 누를 수 없는 상태
(가려짐 / 화면 밖 / 백그라운드)면 앱도 누르지 않는다.

### 2. 실패 경로에 자동 재시도를 넣지 않는다

**SRT IP 차단은 실제로 일어났다. 코레일도 같다고 본다.** 재시도 한 번 = 요청 한 번이다.
실패는 "요청이 나갔는가" 로 나눠서, 나가지 않은 것만 다음 사이클에 다시 시도한다.
연속 실패 상한(`maxConsecutiveErrors`, `maxUnknownPages`, `maxSoldOutRetries`)을 늘리지 말 것.

같은 이유로 **짧은 간격으로 실제 사이트를 반복 테스트하지 않는다.**

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

### 7. 감시 주기는 Kotlin 코루틴이 관리한다

JS `setInterval` 은 쓰지 않는다. 간격은 `[min, max]` 범위에서 **사이클마다 무작위**로
뽑는다 — 정확히 같은 주기의 반복 요청은 자동화로 판단되어 차단된다.

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
  `main` = 통합 브랜치, `ktx` = KTX(코레일) 전환 작업 브랜치.
  전환이 끝나면 `main` 에 머지하고 `ktx` 는 지운다.
  SRT 사이트 대응 마지막 버전은 태그 **`v0.1.1-srt`** 에 있다 (`git show v0.1.1-srt:<경로>`).
  SRT 는 폐지되었으므로 **SRT 코드를 살아있는 브랜치로 유지하지 않는다.** 태그면 충분하다.
- `keystore.properties` · `*.jks` · `dist/` 는 `.gitignore` 에 있다.
  **절대 커밋하지 말 것** — 실제 서명 비밀번호가 평문으로 들어 있다.
- 세션 밖에서 파일이 바뀌어 있는 경우가 잦으므로, 덮어쓰기 전에 **반드시 다시 읽는다.**
  (git 이전에 실제로 코드가 날아간 적 있다)
- 툴체인은 로컬에 다 있다. `--offline` 로 컴파일·단위테스트·debug APK 빌드가 전부 된다.
  단 `assembleRelease` 는 `lintVital` 이 의존성을 받아와야 해서 `--offline` 이면 실패한다.
- `WatchControllerTest` 에서 **`advanceUntilIdle()` 을 쓰지 않는다.**
  `backgroundScope` 의 감시 루프가 돌지 않아 테스트가 멈춘다. `runCurrent()` / `advanceTimeBy()` 를 쓴다.
- 파싱이 깨졌을 때 고칠 곳은 두 파일뿐이다:
  `webview/KtxSelectors.kt`(selector·키워드), `webview/KtxParserScript.kt`(추출 로직).
  확인은 `chrome://inspect` 로 실제 WebView DOM 을 보고 한다.

```bash
./gradlew --offline :app:testDebugUnitTest
```

```bash
./gradlew --offline :app:assembleDebug
```

---

## 코드 지도

`app/src/main/java/dev/yslee/catchtrain/` — 이름으로 알 수 없는 것만 적는다.

| 위치 | 역할 |
|---|---|
| `watcher/WatchController.kt` | 감시 루프 전체. 조회→분석→판정→알림→(좌석 선택→예매)→대기 |
| `watcher/WatchConfig.kt` | 루프 파라미터 (간격·타임아웃·재시도 상한). 값마다 근거가 KDoc 에 있다 |
| `watcher/WatchState.kt` | `WatchState` / `WatchError` / `ReserveStage` / `ReserveResult` / `WatchStatus`. UI 는 `WatchStatus` 만 본다 |
| `webview/PageHost.kt` | 감시 엔진이 보는 페이지 인터페이스. WebView 를 여기서 끊는다 (테스트 이음매). **예매는 `selectSeat`(1단계) + `confirmReserve`(2단계)** |
| `webview/KtxWebViewHost.kt` | `PageHost` 구현. 좌표 계산 → `MotionEvent` 터치 → 정착 대기 |
| `webview/KtxParserScript.kt` | WebView 안에서 실행할 JS 를 문자열로 생성 (최대 파일) |
| `webview/KtxSelectors.kt` | ★ selector / URL / 키워드 상수. 사이트가 바뀌면 여기부터 |
| `webview/KtxLoginScript.kt` | 머리말 링크로 로그인 여부 판정 (§27-1) |
| `webview/KtxPopupHost.kt` | `window.open` 자식 WebView 스택 (달력 팝업, §12-1) |
| `domain/SelectionEngine.kt` | 순수 판정 로직. Android 없음 |
| `domain/TrainKey.kt` | 재조회 후에도 같은 열차를 알아보는 식별자 — **기준은 열차 번호** (§38-4) |

---

## 알아두면 헛수고를 막는 것

- **비로그인 상태에서도 조회가 되고 좌석 선택까지 된다.** 로그인을 요구하는 시점은
  예매를 누른 **뒤**다. 그래서 감시 시작 시점에 머리말 링크(`btnGoLogin`/`btnGoLogout`)로
  따로 확인한다. 본문 텍스트에서 "로그아웃" 을 찾으면 오판하고(로그인 화면 안내문에 그
  단어가 있다), `button.logoutBtn` 은 **클래스 이름이 고정이고 문구만 바뀌어** 항상
  로그인으로 읽힌다. (§38-7)
- **좌석 칸 순서는 일반실이 왼쪽(`[0]`), 특실이 오른쪽(`[1]`)** 이다. **SRT 와 반대다.**
  매진 칸에는 등급 class(`gen`/`spe`)가 붙지 않아 위치 말고는 등급을 알 방법이 없다. (§38-3)
- **좌석 상태는 텍스트가 아니라 class 로 읽는다.** `sold_out_soon`(매진임박)은 **살 수
  있는 칸**인데 문구가 `특실(매진임박)` 이라 "매진" 부분일치에 걸린다. (§38-2)
- **재조회 버튼 문구는 완전일치로 찾는다.** 옆에 `다음날 (…) 조회` 가 있어서 "조회"
  부분일치로 고르면 사용자가 보던 날짜가 아닌 다음날을 조회한다. (§38-5)
- **AJAX 라 조회해도 URL 이 바뀌지 않고 `onPageFinished` 도 오지 않는다.** 페이지 종류
  판정도 갱신 감지도 전부 DOM 으로 한다. (§38-5)
- `window.open` 팝업은 `setSupportMultipleWindows=true` + `onCreateWindow` 로만
  `opener` 가 살아 있다. URL 만 가로채는 방식은 똑같이 깨진다.
- [예매] 를 눌러도 "잔여석없음" 이 뜰 수 있다. 화면 전환만으로는 성공과 구분되지 않으므로
  본문 문구를 한 번 더 확인해야 한다. (코레일 실제 문구는 아직 실측 전 — §38-8)
