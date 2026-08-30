# shared/ — 두 클라이언트가 같이 쓰는 것

플랫폼과 무관하고 **코레일이 바뀌면 양쪽이 동시에 깨지는 것**만 여기 둔다.

지금은 비어 있다. 이 폴더가 있는 이유는 아래 계획을 잊지 않기 위해서다.

---

## 여기 들어올 것

### `ktx-selectors.json` — selector 단일 출처

`android/app/src/main/java/dev/yslee/catchtrain/webview/KtxSelectors.kt` (454줄) 의
selector·URL·키워드 상수를 옮긴 것. 확장은 직접 import 하고,
안드로이드는 **`KtxSelectors.kt` 가 이 JSON 과 일치하는지 검사하는 단위 테스트**를 둔다.

코드 생성까지는 하지 않는다. 테스트 하나면 "한쪽만 고쳤다" 가 바로 잡히고,
그게 이 파일이 막으려는 사고의 전부다.

**아직 옮기지 않았다.** 확장 쪽 파서가 실제로 돌기 시작한 뒤에 한다 —
쓰는 데가 한 곳뿐인 상태에서 미리 쪼개면 형식만 정하고 검증은 못 한다.

지금은 **`extension/src/content/ktx/selectors.js` 가 손으로 맞춘 미러**다.
selector 를 고치면 두 파일을 같이 봐야 한다 (`KtxSelectors.kt` ↔ `selectors.js`).
승격은 `extension/PLAN.md` §E-9 의 M5.

### `ktx-parse.js` (그럴 값어치가 있으면)

`KtxParserScript.kt` 의 추출 로직. 안드로이드는 `assets/` 에서 읽어
`evaluateJavascript` 로 넣고, 확장은 그대로 import 한다.
1600줄짜리 리팩터링이라 **확장이 돌아간 뒤에** 판단한다.

## 여기 들어오지 않는 것

- **사이트 동작에 대한 서술** → [`../docs/DESIGN.md`](../docs/DESIGN.md) §38·§39.
  사실은 문서에 한 벌만 있고, 여기에는 그 사실을 코드로 옮긴 값만 둔다.
- **대원칙** → [`../CLAUDE.md`](../CLAUDE.md)
- 판정 로직 (`SelectionEngine` 등). 300줄뿐이라 Kotlin/JS 각자 두는 편이 싸다.
  대신 **규칙이 같아야 한다** — DESIGN.md §34-2.
