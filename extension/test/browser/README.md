# 브라우저에서 도는 검사

`test/*.mjs` 는 `node --test` 로 도는 순수 로직 테스트다. **이 폴더는 다르다** —
DOM 이 있어야 하는 것들(`content/ktx/*`)과 모듈 임포트 전체를 브라우저에서 확인한다.
이 개발 머신에는 Node 가 없어서, 실제로 돌려 볼 수 있는 검사는 지금 이쪽뿐이다.

```bash
cd extension && py test/browser/serve.py
```

```bash
chrome --headless=new --disable-gpu --virtual-time-budget=25000 --dump-dom "http://127.0.0.1:8731/test/browser/unit.html"
```

브라우저에서 직접 열어도 된다. 결과는 `<pre>` 하나에 다 나오고, **`<title>` 만 봐도 된다.**
(`python -m http.server` 를 그냥 쓰면 안 된다 — `.mjs` 를 모듈로 안 준다. `serve.py` 가 그 한 줄이다)

| 파일 | 무엇을 보나 | 통과 |
|---|---|---|
| `unit.html` | **`test/*.mjs` 를 그대로 돌린다.** `node:test`/`node:assert` 를 import map 으로 갈아 끼운다 | `ALL-PASS` |
| `modules.html` | **모든 모듈이 임포트되는가.** `chrome.*` 는 shim 이다 | `ALL-OK` |
| `reserve.html` | **1·2단계 클릭 로직.** 코레일 목록 모형 DOM 위에서 `reserve.js` 를 실제로 돌린다 | `DONE` (내용을 읽는다) |

`unit.html` 이 도는 것은 **`test/*.mjs` 원본 그대로다.** 테스트를 브라우저용으로 고쳐 쓰지
않는다 — 두 벌이 되면 반드시 한쪽만 고치게 된다. Node 가 생기면 `node --test` 가 같은
파일을 돌린다.

`reserve.html` 이 지키는 것 (하나라도 깨지면 실제 사이트에서 사고가 난다):

- `예약대기신청` / `입석+좌석 예매` / 비활성 버튼은 **눌리지 않는다** (§38-6-1, 대원칙 3)
- 매진 칸은 누르지 않는다 — 판정 뒤에 그새 바뀐 경우 (§E-6-2 의 3번)
- **`sold_out_soon`(매진임박) 칸은 누른다.** 문구에 "매진" 이 들어 있지만 살 수 있는 칸이고,
  등급 class(`gen`/`spe`)가 **붙지 않아** 위치로만 가려진다. 하단 바가 `일반실(매진임박)` 처럼
  꼬리를 달고 나와도 등급 확인을 통과해야 한다 (§38-2, §38-3). 편성 `411` 이 그 경우다
- 하단 바 등급이 고른 등급과 다르면 누르지 않는다
- 같은 07:11 의 다른 편성(305 / 381)을 헷갈리지 않는다 — **주키는 열차 번호다** (§38-4)
- **합성 클릭을 무시하는 사이트에서는 `SEAT_NOT_SELECTED` 로 떨어진다.**
  `window.__acceptSynthetic = false` 가 그 사이트 흉내다 (PLAN.md §E-2-1)
- 이미 골라진 칸은 다시 누르지 않는다 (누르면 선택이 풀린다)
