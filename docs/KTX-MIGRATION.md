# KTX(코레일) 전환 — 진행 상황과 남은 일

SRT 폐지로 같은 앱을 코레일 대응으로 바꾸는 작업. 브랜치 `ktx`.

**이 문서는 작업이 끝나면 지운다.** 진행 중에만 의미가 있다.
사이트 동작에 대한 사실은 여기가 아니라 [`DESIGN.md §38`](DESIGN.md) 에 있다.

---

## 지금 어디까지 왔나

| 커밋 | 내용 |
|---|---|
| `v0.1.1-srt` (태그) | SRT 대응 마지막 버전. 파서를 참고할 일이 있으면 `git show v0.1.1-srt:<경로>` |
| `2ad87cc` | 패키지 `dev.yslee.catchtrain` / 앱 이름 Catch Train / 새 서명 키 |
| `eed37c1` | `DESIGN.md §38` — 코레일 조회 결과 DOM 실측 (1차 덤프) |
| `0a1d63a` | 2차 덤프 반영. `TrainKey` 주키 교체, `SeatParser` class 판정, `KtxSelectors` 신설 |

끝난 것:

- **`TrainKey` 주키를 출발시각 → 열차번호로 교체** (§38-4). 07:11 의 305/381 문제
- **`SeatParser.fromClassNames` / `seatClassOf` / `isSelected`** — 좌석 상태를 class 로 판정 (§38-2)
- **`webview/KtxSelectors.kt`** — 확인된 selector 전부. **아직 아무도 참조하지 않는다**

단위 테스트 89건 통과. `./gradlew --offline :app:testDebugUnitTest`

---

## 남은 일

### 1. `KtxParserScript` — `SrtParserScript`(1359줄) 대체

`webview/SrtParserScript.kt` 는 SRT 의 `<table>` 을 전제로 쓰였다.
표 헤더 텍스트로 열 위치를 찾는 휴리스틱이 절반을 차지하는데, 코레일은
`<ul><li>` 에 **헤더 행 자체가 없어서** 그 부분이 통째로 필요 없다. 훨씬 짧아진다.

읽어야 하는 것과 selector 는 `KtxSelectors` 에 다 있다. 요약하면 편성 하나가:

```
li.tckList
  .flag_wrap .blind   "KTX-산천"          ← 종류
  .flag_wrap .num     "305"               ← 번호 (식별 주키)
  .data_box h3        동탄 → 김천구미 (07:11 ~ 08:17)
  div.price_box[0]    ← 일반실   (SRT 와 순서 반대)
  div.price_box[1]    ← 특실
```

주의:

- 좌석 상태는 **class 로만** 판정한다. 텍스트로 읽으면 `특실(매진임박)` 을 매진으로 오판한다
- 등급 class(`gen`/`spe`)는 **매진 칸에 붙지 않는다.** 없으면 위치로 보정 (§38-3)
- `SeatParser` 와 규칙이 어긋나지 않게 할 것. 문자열 상수는 `KtxSelectors.SeatCellClass` 에 있다

`parser/SrtPageParser.kt` / `SrtParser.kt` 도 같이 손봐야 한다.
`PageSnapshot` / `RowRef` 구조 자체는 그대로 쓸 수 있다.

### 2. 2단계 예매 — `PageHost` / `WatchController` 설계 변경 ★ 제일 큼

지금 `PageHost.clickReserve(target)` 는 **한 방에 끝나는** 계약이다.
SRT 는 행마다 [예약하기] 가 있어서 그게 맞았지만, 코레일은 두 번 눌러야 한다 (§38-6).

```
1단계  li.tckList 안의 price_box > a 를 누른다  →  그 칸에 active 가 붙고 하단 바가 뜬다
2단계  div.reservbtnWrap button.reservbtn 을 누른다
```

**두 단계 사이에 확인이 들어가야 한다** (§38-6-1). 하나라도 어긋나면 2단계를 누르지 않는다.

1. 1단계로 누른 `price_box` 에 `active` 가 붙었는가
2. `ul.reserv_first` 첫 `li` 의 문구가 고른 등급(`일반실`/`특실`)과 같은가
3. 2단계 버튼 문구가 **완전일치로 `예매`** 인가 — `예약대기신청` / `입석+좌석 예매` 는 누르지 않는다

허용목록에 없으면 **알림까지만 하고 2단계는 사람에게 넘긴다.** (대원칙 3)

설계 시 지킬 것:

- 1단계는 성공했는데 2단계에서 멈춘 상태를 `WatchState` 가 표현할 수 있어야 한다.
  지금 `ReserveResult` 는 성공/실패 이분법이라 "골라는 놨다" 가 들어갈 자리가 없다
- **1단계 실패와 2단계 실패를 구분한다.** 대원칙 2 대로, 요청이 나간 실패는 재시도하지 않는다
- `WatchControllerTest` 에서 `advanceUntilIdle()` 금지. `runCurrent()` / `advanceTimeBy()` 를 쓴다

### 3. `SrtWebViewHost` 조정

- **DOM 서명 대상**을 `KtxSelectors.SIGNATURE_SCOPES`(`div.tckWrap`)로 교체.
  머리말·광고까지 넣으면 좌석과 무관한 변화에 반응한다
- **URL 기반 페이지 판정 제거.** 코레일은 AJAX 라 조회해도 URL 이 안 바뀐다 (§38-5).
  페이지 종류는 `KtxSelectors.TRAIN_LIST_MARKERS` 로만 본다
- `awaitSettled` 자체는 **손대지 않아도 된다.** DOM 서명 변화로 `Updated` 를 돌려주게 이미 되어 있다
- `dismissReserveResult` 의 뒤로 가기는 **재검토 필요.** SPA 라 history 한 칸이
  조회 결과 화면에 대응한다는 보장이 없다 (§38-8 미확인)

### 4. 정리 작업 (파서가 돌아간 뒤에)

- `Srt*` 클래스명 → `Ktx*`, `SrtLoginScript` 의 판정을 §38-7 규칙으로
- UI 문구의 "아래 SRT 화면에서…" → 코레일 표현으로.
  **지금은 일부러 안 바꿨다** — 앱이 아직 SRT 를 보고 있어서 현재로선 사실이다
- `START_URL` 을 `KtxSelectors.START_URL` 로
- 다 되면 `main` 에 머지하고 `ktx` 브랜치와 이 문서를 지운다

---

## 실측이 더 필요한 것 (§38-8)

**추측으로 채우지 말 것.** `chrome://inspect` 로 실제 DOM 을 보고 확인한다.

- 아무 칸도 안 고른 상태에서 `div.ticket_reserv_wrap` 이 DOM 에 아예 없는지, 숨겨져만 있는지
- 매진 칸(`sold_out`)을 눌렀을 때의 반응
- 예약 실패 시 실제 문구 — `KtxSelectors.RESERVE_FAILED_MARKERS` 는 지금 **SRT 값 그대로**다
- ITX·무궁화가 섞인 결과에서 `flag_wrap` class 가 어떻게 달라지는지
- 자유석/입석이 별도 `price_box` 로 나오는지.
  안 나온다면 `SeatClass` 를 일반실/특실 2개로 유지할 수 있다 (**큰 파급을 피한다**)
- SPA 에서 뒤로 가기가 조회 결과 화면으로 돌아가는지

---

## 잊지 말 것

- **SRT IP 차단은 실제로 있었다.** 코레일도 같다고 보고, 짧은 간격으로 실제 사이트를
  반복 테스트하지 않는다. 실패 경로에 자동 재시도를 넣지 않는다 (대원칙 2)
- `DESIGN.md` 의 **§번호는 재배치하지 않는다.** 코드 주석 100여 곳이 참조한다
- 세션 밖(Android Studio)에서 파일이 바뀌는 일이 잦다. **덮어쓰기 전에 다시 읽는다**
