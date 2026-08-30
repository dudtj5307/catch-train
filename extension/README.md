# Catch Train — 크롬 확장

같은 목적의 PC 판. 코레일 조회 결과 화면을 감시하다가 체크해 둔 칸이 열리면 알리고
그 칸을 골라 [예매] 를 눌러 준다.

**지금은 M1(판독)까지 되어 있다.** 팝업이 지금 보고 있는 코레일 탭의 조회 결과를 읽어
[열차 선택] 목록을 띄우고, 칸을 체크해 둘 수 있다.
**사이트로 나가는 요청은 하나도 없다** — 새로고침도 클릭도 아직 없다.
남은 단계는 [`PLAN.md`](PLAN.md) §E-9.

---

## 먼저 읽을 것

플랫폼과 무관한 것은 전부 저장소 공통 문서에 있다. **여기에 복사하지 말 것.**

| | |
|---|---|
| [`../CLAUDE.md`](../CLAUDE.md) | **대원칙.** 재시도 금지 · [예매]까지만 · 조회 조건은 앱이 안 갖는다 |
| [`../docs/DESIGN.md`](../docs/DESIGN.md) | 특히 **§38 (코레일 DOM 실측)** 과 **§39 (대기열)**. 확장에도 그대로 적용된다 |
| [`PLAN.md`](PLAN.md) | **★ 지금 진행 중인 일.** 확장 설계와 마일스톤. 구현이 끝나면 지운다 |
| [`../shared/`](../shared/) | selector 단일 출처 (승격은 M5) |

대원칙은 안드로이드 규칙이 아니라 **제품의 규칙**이다. 확장이라고 예외가 되지 않는다.

## 쓰는 법

`chrome://extensions` → `개발자 모드` → `압축해제된 확장 프로그램을 로드` → 이 폴더.

1. 코레일에서 **직접** 원하는 조건으로 조회한다. (조회 조건은 확장이 갖지 않는다 — 대원칙 4)
2. 툴바의 확장 아이콘을 눌러 팝업을 연다. 그 탭의 조회 결과가 목록으로 뜬다.
3. 원하는 (열차 × 좌석등급) 칸을 체크한다. 매진인 칸도 체크할 수 있다 —
   **지금 매진인 좌석이 풀리기를 기다리는 것이 이 도구의 목적이다.**

확장을 새로 로드했다면 코레일 탭도 한 번 새로고침해야 content script 가 붙는다.

테스트는 Node 내장 러너로 돈다. 의존성이 0 이라 `npm install` 이 없다.

```bash
cd extension && node --test test/
```

## 구조

```
src/
├── background/   service worker — 상태와 결정. index.js(라우팅) · store.js(storage.session)
├── content/      DOM 판독 팔. index.js(진입) · ktx/{selectors,dom,parse,page-kind}.js
├── domain/       ★ chrome API 도 DOM 도 모르는 순수 코드 (안드로이드 domain/ 과 같은 규칙)
└── ui/           팝업
test/             node --test. 안드로이드 단위 테스트를 그대로 옮긴 것이다 (§34-2)
```

- **content script 는 판독만 한다.** 무엇을 언제 할지는 service worker 가 정한다.
- **selector 는 `content/ktx/selectors.js` 한 곳에만.** 팝업도 SW 도 selector 를 모른다.
- 선언된 content script 는 ES 모듈이 아니라서 `content/index.js` 가 `import()` 로 나머지를
  불러온다. 그래서 그 모듈들이 manifest 의 `web_accessible_resources` 에 있다.
- **빌드 도구를 두지 않는다.** 순수 ES 모듈이면 번들러 없이 돈다.
- 권한은 **그 단계에서 실제로 쓰는 것만** 넣는다. 지금은 `storage` 와 코레일 host permission
  뿐이다. `notifications`·`alarms`·`offscreen` 은 감시(M2)와 함께, `debugger` 는 실측 뒤에
  **선택 권한**으로 (PLAN.md §E-2, §E-8).

## 안드로이드와 갈리는 지점

### 정해진 것

| | 결정 | 근거 |
|---|---|---|
| 감시 주기 | **service worker** 에서. 상태는 `chrome.storage.session` | PLAN.md §E-3 |
| 갱신 | **`chrome.tabs.reload`.** PC 폭에서 [열차조회] 가 보여도 누르지 않는다 | PLAN.md §E-4 |
| 클릭 | `ClickDriver` 를 갈아 끼우는 이음매로 두고 **실측으로** 고른다. 다 막히면 **사람에게 인계** | PLAN.md §E-2 |
| 빌드 도구 | 두지 않는다 | 위 구조 |
| selector 공유 | 지금은 이 폴더에 미러, `shared/` 승격은 M5 | PLAN.md §E-8 |

### 남은 것

- [ ] **클릭 드라이버 실측** (M-a / M-b / M-c). 결과는 `docs/DESIGN.md §38-8` 에 적는다 —
      이 문서에는 적지 않는다 (사이트에 대한 사실은 한 벌만)
- [ ] 웹스토어에 올릴지, 압축해제 로드로만 쓸지
      (안드로이드는 Play 스토어를 포기했다 → [`../android/RELEASE.md`](../android/RELEASE.md))
- [ ] 아이콘. `chrome.notifications` 가 요구한다 (M2)

### 안 가져오는 것

- **비로그인일 때 로그인 화면으로 스스로 이동**(안드로이드 §27-2). 사용자의 탭은 사용자의
  것이고, 확장이 조용히 주소를 갈아 끼우는 것은 브라우저에서 명백히 적대적인 동작이다.
  팝업의 버튼으로만 연다. (PLAN.md §E-6-5)
- **`100vh` 보정**(§38-10). WebView 가 높이를 얻기 전에 문서를 받는 안드로이드 고유의 사고다.

### 제약은 약해져도 풀지 않는다

확장은 백그라운드 탭에서도 DOM 을 읽을 수 있다. **그렇다고 제약을 풀지 말 것** —
사람이 보지 않는 채로 도는 감시가 바로 대원칙 2 가 막으려던 그 요청이다.
탭이 살아 있어야 한다는 선은 유지한다.
