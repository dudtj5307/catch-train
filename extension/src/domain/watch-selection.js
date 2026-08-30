// 감시 대상 — 사용자가 [열차 선택] 목록에서 체크한 칸들.
// (android: domain/WatchSelection.kt, 대원칙 4)
//
// 조회 조건(구간·날짜·시간)은 사이트가 이미 알고 있다. 앱이 들고 있는 것은
// **"화면에서 체크한 그 칸"** 뿐이다. 한 칸 = 사이트 표의 `price_box` 하나.
//
// 안드로이드는 `Set<SeatSelection>` 이지만 여기서는 **배열**이다.
// Set 은 chrome.storage(JSON) 를 건너가지 못한다. 중복은 [selectionToggle] 이 막는다.

import { SEAT_CLASSES } from './seat-class.js';
import { trainKeyMatches } from './train-key.js';
import { keyOf } from './train.js';

/** `{ seats: [{ trainKey, seatClass }] }` */
export const EMPTY_SELECTION = Object.freeze({ seats: Object.freeze([]) });

export function selectionOf(seats) {
  return { seats: seats.slice() };
}

export function selectionIsEmpty(selection) {
  return selectionSize(selection) === 0;
}

export function selectionSize(selection) {
  return selection && Array.isArray(selection.seats) ? selection.seats.length : 0;
}

/** 선택된 열차 수 (좌석 등급 중복 제거) */
export function selectionTrainCount(selection) {
  const keys = [];
  for (const seat of selection.seats) {
    if (!keys.some((k) => k.trainNumber === seat.trainKey.trainNumber &&
      k.departureTime === seat.trainKey.departureTime)) {
      keys.push(seat.trainKey);
    }
  }
  return keys.length;
}

export function selectionContains(selection, key, seatClass) {
  return selection.seats.some(
    (seat) => seat.seatClass === seatClass && trainKeyMatches(seat.trainKey, key),
  );
}

export function selectionContainsTrain(selection, train, seatClass) {
  return selectionContains(selection, keyOf(train), seatClass);
}

/** 체크를 켜고 끈다. 이미 있으면 지우고, 없으면 넣는다. */
export function selectionToggle(selection, key, seatClass) {
  const kept = selection.seats.filter(
    (seat) => !(seat.seatClass === seatClass && trainKeyMatches(seat.trainKey, key)),
  );
  if (kept.length !== selection.seats.length) return { seats: kept };
  return { seats: [...selection.seats, { trainKey: { ...key }, seatClass }] };
}

/**
 * 지금 화면에 없는 열차의 선택을 지운다.
 *
 * 사용자가 사이트에서 날짜나 구간을 바꿔 다시 조회하면 이전 선택은 의미가 없다.
 * 그대로 두면 눈에 보이지 않는 조건이 남아 감시가 영영 걸리지 않는다.
 */
export function selectionRetainOnly(selection, trains) {
  if (!trains || trains.length === 0) return selection;
  const kept = selection.seats.filter(
    (seat) => trains.some((train) => trainKeyMatches(seat.trainKey, keyOf(train))),
  );
  return kept.length === selection.seats.length ? selection : { seats: kept };
}

export function selectionValidate(selection) {
  return selectionIsEmpty(selection) ? '감시할 열차를 하나 이상 선택하세요.' : null;
}

/**
 * 저장소에서 읽은 값을 신뢰하지 않고 형태를 맞춘다.
 *
 * `chrome.storage.session` 의 값은 확장을 새로 로드하기 전의 것일 수 있다.
 * 모양이 어긋난 항목 하나 때문에 판정이 통째로 죽는 것보다 그 항목만 버리는 편이 낫다.
 */
export function normalizeSelection(raw) {
  const seats = raw && Array.isArray(raw.seats) ? raw.seats : [];
  const cleaned = [];
  for (const seat of seats) {
    if (!seat || !seat.trainKey || !SEAT_CLASSES.includes(seat.seatClass)) continue;
    const { trainNumber, departureTime } = seat.trainKey;
    if (typeof departureTime !== 'string' || departureTime === '') continue;
    cleaned.push({
      trainKey: { trainNumber: typeof trainNumber === 'string' ? trainNumber : '', departureTime },
      seatClass: seat.seatClass,
    });
  }
  return { seats: cleaned };
}
