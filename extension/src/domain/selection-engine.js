// 선택 판정 엔진. (android: domain/SelectionEngine.kt)
//
// DOM 도 chrome API 도 모르는 순수 로직이다. **이 규칙은 안드로이드와 같아야 한다** —
// 같은 입력에 같은 답이 나오지 않으면 두 클라이언트가 다른 제품이 된다. (DESIGN.md §34-2)
//
// 규칙은 단순하다.
//   1. 사용자가 체크한 (열차, 좌석등급) 칸만 본다.
//   2. 그 칸이 **지금 바로 예약 가능**하면 발견이다.
//
// 예약대기(WAITING)는 발견으로 보지 않는다. 즉시 예약이 아니라 대기 신청이고,
// 사용자가 고른 것은 그 칸의 [예매] 다. 대기 신청은 화면에서 직접 하면 된다. (§18)

import { SEAT_CLASSES, seatClassOrdinal } from './seat-class.js';
import { SeatStatus } from './seat-status.js';
import { keyOf, seatStatusOf } from './train.js';
import { selectionContains, selectionIsEmpty } from './watch-selection.js';
import { noMatch } from './match.js';

export function match(trains, selection) {
  if (selectionIsEmpty(selection)) {
    return noMatch('선택된 열차 없음');
  }
  if (!trains || trains.length === 0) {
    return noMatch('조회된 열차 없음');
  }

  const selected = [];
  for (const train of trains) {
    const key = keyOf(train);
    for (const seatClass of SEAT_CLASSES) {
      if (selectionContains(selection, key, seatClass)) selected.push({ train, seatClass });
    }
  }
  if (selected.length === 0) {
    return noMatch('선택한 열차가 이번 조회 결과에 없음');
  }

  const matches = selected
    .filter((m) => seatStatusOf(m.train, m.seatClass) === SeatStatus.AVAILABLE)
    // 같은 사이클에 여러 칸이 열리면 먼저 출발하는 열차, 그다음 일반실을 먼저 본다.
    .sort((a, b) => (
      a.train.departureTime < b.train.departureTime ? -1 :
      a.train.departureTime > b.train.departureTime ? 1 :
      seatClassOrdinal(a.seatClass) - seatClassOrdinal(b.seatClass)
    ));

  if (matches.length === 0) {
    return noMatch('선택한 좌석이 아직 나오지 않음');
  }
  return {
    matched: true,
    train: matches[0].train,
    reason: '선택한 좌석 발견',
    matches,
  };
}
