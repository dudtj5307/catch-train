// selector 사본이 어긋나지 않는지 지킨다.
//
// 좌석 칸 class 토큰은 두 곳에 있다.
//   - `content/ktx/selectors.js`  — 사이트를 아는 쪽. 단일 출처이고 나중에 shared/ 로 간다
//   - `domain/seat-status.js`     — 판정 규칙. **사이트를 몰라야 하므로** 사본을 들고 있다
//
// 안드로이드도 같은 구조다(`KtxSelectors.SeatCellClass` vs `SeatParser`). 거기서는
// 한쪽만 고쳐도 아무도 모르지만, 여기서는 이 테스트가 잡는다.
// (shared/ 승격 뒤에는 안드로이드 쪽에도 같은 성격의 테스트가 하나 더 생긴다 — §E-8)

import test from 'node:test';
import assert from 'node:assert/strict';

import { SeatCellClass } from '../src/content/ktx/selectors.js';
import { SEAT_CLASS_TOKENS } from '../src/domain/seat-status.js';

test('좌석 칸 class 토큰은 두 곳이 같아야 한다', () => {
  assert.deepEqual({ ...SEAT_CLASS_TOKENS }, { ...SeatCellClass });
});
