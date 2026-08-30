// DOM 에서 추출한 한 편성. (android: domain/Train.kt, DESIGN.md §7)
//
// 이 모델은 DOM selector 도 chrome API 도 모른다. SelectionEngine 과 화면은
// 이 모델만 알면 된다.
//
// 안드로이드의 `data class Train` 에 대응하지만 **평범한 객체**로 둔다.
// service worker → popup 으로 그대로 건너가야 하는 값이라, 메서드를 달면
// 경계를 넘는 순간 사라진다. 동작은 아래 함수들이 갖는다.

import { SeatClass } from './seat-class.js';
import { SeatStatus } from './seat-status.js';
import { trainKey } from './train-key.js';

/**
 * 재조회 후에도 같은 열차를 알아보기 위한 식별자. (§38-4)
 * 사용자 선택을 유지하는 데 쓴다.
 */
export function keyOf(train) {
  return trainKey(train.trainNumber, train.departureTime);
}

export function seatStatusOf(train, seatClass) {
  if (!train) return SeatStatus.UNKNOWN;
  return seatClass === SeatClass.GENERAL ? train.generalSeat : train.firstClassSeat;
}

/** "305  동탄 → 김천구미  07:11 → 08:17" */
export function trainSummary(train) {
  return `${train.trainNumber}  ${train.departureStation} → ${train.arrivalStation}  ` +
    `${train.departureTime} → ${train.arrivalTime}`;
}
