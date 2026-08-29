# KTX(코레일) 전환 — 진행 상황과 남은 일

SRT 폐지로 같은 앱을 코레일 대응으로 바꾸는 작업. 브랜치 `ktx`.

**이 문서는 작업이 끝나면 지운다.** 진행 중에만 의미가 있다.
사이트 동작에 대한 사실은 여기가 아니라 [`DESIGN.md §38`](DESIGN.md) 에 있다.

---

## 지금 어디까지 왔나

| 커밋 | 내용 |
|---|---|
| `v0.1.1-srt` (태그) | SRT 대응 마지막 버전. 옛 파서를 참고할 일이 있으면 `git show v0.1.1-srt:<경로>` |
| `2ad87cc` | 패키지 `dev.yslee.catchtrain` / 앱 이름 Catch Train / 새 서명 키 |
| `eed37c1` | `DESIGN.md §38` — 코레일 조회 결과 DOM 실측 (1차 덤프) |
| `0a1d63a` | 2차 덤프 반영. `TrainKey` 주키 교체, `SeatParser` class 판정, `KtxSelectors` 신설 |
| `6f7cb65` | 이 인수인계 문서 |
| **(이번)** | **파서·로그인 판정 교체, 2단계 예매, `Srt*` → `Ktx*` 이름 정리** |

### 코드는 전부 코레일을 본다

- **`webview/KtxParserScript.kt`** — `SrtParserScript`(1359줄) 대체.
  `<table>` 전제와 "표 헤더로 열 찾기" 휴리스틱이 통째로 사라졌다. `li.tckList` 를 읽고
  좌석 상태는 **class 로만** 판정한다. 스크립트가 아홉 개다 (목록 분석 / 재조회 버튼 /
  1단계 / 1단계 확인 / 2단계 / 예약 결과 / 탭 확인 / observer / probe / 화면 종류).
- **`webview/KtxLoginScript.kt`** — `btnGoLogin` / `btnGoLogout` 으로 판정. (§38-7)
  `logoutBtn` 과 `loginY` 는 **쓰지 않는다.** 테스트가 그 두 이름이 스크립트에
  들어가지 않는 것까지 확인한다.
- **`webview/KtxWebViewHost.kt`** — DOM 서명 대상이 `div.tckWrap`(= `SIGNATURE_SCOPES`),
  URL 기반 페이지 판정 제거, 되돌리기 뒤 **목록이 실제로 보이는지 확인**.
- **2단계 예매** — `PageHost.clickReserve` 가 `selectSeat`(1단계) + `confirmReserve`(2단계)로
  갈라졌다. 상태 표현도 함께 갈라졌다:
  `WatchState.SEAT_SELECTED`(좌석만 골라 둠) / `ReserveStage`(SELECT·CONFIRM).
- `Srt*` → `Ktx*` 이름, UI·알림 문구, `START_URL` 전부 코레일로.
  살아 있는 코드에 `Srt` 로 시작하는 이름은 없다. 남은 "SRT" 는 **비교·역사 서술**뿐이다.

단위 테스트 102건 통과. `./gradlew --offline :app:testDebugUnitTest`
debug APK 빌드도 통과.

---

## 남은 일

### 1. 실기기 확인 ★ 지금 제일 중요한 것

여기까지는 **실측 덤프를 보고 쓴 코드**다. 실제 WebView 에서 한 번도 돌려 보지 않았다.
`chrome://inspect` 로 DOM 을 보면서 순서대로 확인한다.

1. 조회 결과 화면에서 [열차 선택] 목록이 채워지는가 (편성·번호·시각·좌석 상태)
2. 감시 시작 → [열차조회] 가 눌리고 목록이 갱신되는가
   (**`다음날 조회` 가 눌리면 즉시 중단하고 `RESEARCH_TEXTS_EXACT` 부터 본다**)
3. 좌석이 열렸을 때 1단계로 그 칸이 골라지는가 (`active` 가 붙는가)
4. 하단 바가 뜨고 2단계 [예매] 가 눌리는가

로그(`RESERVE_CLICKED` / `RESERVE_SEAT_SELECTED` / `RESERVE_HANDOVER`)에 각 단계가
그대로 남는다. 실패하면 로그의 `reason` 을 보고 `KtxSelectors` 를 고친다.

**짧은 간격으로 반복하지 말 것.** 대원칙 2.

### 2. 실측이 더 필요한 것 (§38-8)

**추측으로 채우지 말 것.** 확인되면 `KtxSelectors` 의 해당 상수부터 고친다.

- 예약 실패 시 실제 문구 — `RESERVE_FAILED_MARKERS` 는 지금 **SRT 값 그대로**다
- 조회 폼의 출발일 입력 — `SEARCH_DATE_FIELDS` 를 **비워 뒀다.**
  그래서 상단 요약에 날짜가 빠진 채로 보인다 (표시용이라 감시에는 영향 없음)
- 아무 칸도 안 고른 상태에서 `div.ticket_reserv_wrap` 이 DOM 에 아예 없는지, 숨겨져만 있는지
- ITX·무궁화가 섞인 결과에서 `flag_wrap` class 가 어떻게 달라지는지
- 자유석/입석이 별도 `price_box` 로 나오는지
  (안 나온다면 `SeatClass` 를 일반실/특실 2개로 유지할 수 있다 — **큰 파급을 피한다**)
- SPA 에서 뒤로 가기가 조회 결과 화면으로 돌아가는지

### 3. 마무리

- `main` 에 머지하고 `ktx` 브랜치와 **이 문서를 지운다.**
- `docs/HISTORY.md` 는 옛 이름(`SrtPageParser`)을 그대로 둔다. 그때의 기록이다.

---

## 잊지 말 것

- **SRT IP 차단은 실제로 있었다.** 코레일도 같다고 보고, 짧은 간격으로 실제 사이트를
  반복 테스트하지 않는다. 실패 경로에 자동 재시도를 넣지 않는다 (대원칙 2)
- `DESIGN.md` 의 **§번호는 재배치하지 않는다.** 코드 주석 100여 곳이 참조한다
- 세션 밖(Android Studio)에서 파일이 바뀌는 일이 잦다. **덮어쓰기 전에 다시 읽는다**
- `WatchControllerTest` 에서 `advanceUntilIdle()` 금지. `runCurrent()` / `advanceTimeBy()`
