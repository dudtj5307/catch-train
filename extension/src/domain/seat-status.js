// 좌석 상태와 그 판정 규칙.
// (android: domain/SeatStatus.kt + parser/SeatParser.kt, DESIGN.md §38-2)
//
// 안드로이드에서 두 파일로 나뉘어 있던 것을 하나로 합쳤다. 상태 enum 과 그것을
// 만들어내는 규칙은 언제나 같이 고쳐야 하는 것이라, 파일을 나누면 한쪽만 고치게 된다.
//
// **판정 경로는 class 가 1순위, 텍스트가 대체다.** 문구로 읽으면
// `특실(매진임박) 37,200원` 을 매진으로 오판한다 — 살 수 있는 칸인데도. (§38-2)

import { SeatClass } from './seat-class.js';

export const SeatStatus = Object.freeze({
  /** 예약 가능 (지금 바로 고를 수 있는 칸) */
  AVAILABLE: 'AVAILABLE',
  /** 예약대기 / 입석 — 즉시 예약이 아니다 */
  WAITING: 'WAITING',
  /** 매진 */
  SOLD_OUT: 'SOLD_OUT',
  /** 그 칸이 없거나 상태를 판별할 수 없음 */
  UNKNOWN: 'UNKNOWN',
});

export function seatStatusLabel(status) {
  switch (status) {
    case SeatStatus.AVAILABLE:
      return '예약가능';
    case SeatStatus.WAITING:
      return '예약대기';
    case SeatStatus.SOLD_OUT:
      return '매진';
    default:
      return '-';
  }
}

// --- class 기반 판정 (코레일) ----------------------------------------------
//
// 값의 출처는 content/ktx/selectors.js 의 `SeatCellClass` 이고, 사실 자체는
// docs/DESIGN.md §38-2 에 있다. 여기 상수는 **domain 이 사이트를 모른 채로**
// 같은 규칙을 갖기 위한 사본이다 (안드로이드 SeatParser 도 같은 구조다).
// 두 벌이 어긋나지 않는지는 test/selectors-mirror.test.mjs 가 지킨다.

/** 매진임박. 문구에 "매진" 이 들어가지만 **아직 살 수 있다.** */
const SOLD_OUT_SOON = 'sold_out_soon';

/** 매진 (`sold_out_wait` 는 같은 편성에 예약대기가 있는 경우) */
const SOLD_OUT_CLASSES = ['sold_out', 'sold_out_wait'];

/** 예약대기 */
const WAIT = 'wait';

/** 등급이 드러나는 class. **매진 칸에는 붙지 않는다.** (§38-3) */
const GENERAL = 'gen';
const FIRST_CLASS = 'spe';

/** 1단계에서 고른 칸. 상태가 아니라 선택 표시다. (§38-6) */
const ACTIVE = 'active';

/** 테스트가 selectors.js 와 대조하는 값. 코드에서는 위 상수를 그대로 쓴다. */
export const SEAT_CLASS_TOKENS = Object.freeze({
  general: GENERAL,
  firstClass: FIRST_CLASS,
  soldOutSoon: SOLD_OUT_SOON,
  soldOut: SOLD_OUT_CLASSES[0],
  soldOutWait: SOLD_OUT_CLASSES[1],
  wait: WAIT,
  active: ACTIVE,
});

function tokensOf(classNames) {
  if (typeof classNames !== 'string') return [];
  return classNames.trim().split(/\s+/).filter((t) => t.length > 0);
}

/**
 * `div.price_box` 의 class 문자열로 좌석 상태를 판정한다.
 *
 * **부분일치를 쓰지 않고 토큰 완전일치로만 본다.** `sold_out_soon` 안에 `sold_out` 이,
 * `sold_out_wait` 안에 `wait` 가 들어 있어 부분일치는 순서를 아무리 맞춰도 언젠가 어긋난다.
 */
export function seatStatusFromClassNames(classNames) {
  const tokens = tokensOf(classNames);
  if (tokens.length === 0) return SeatStatus.UNKNOWN;

  if (tokens.includes(SOLD_OUT_SOON)) return SeatStatus.AVAILABLE;
  if (tokens.some((t) => SOLD_OUT_CLASSES.includes(t))) return SeatStatus.SOLD_OUT;
  if (tokens.includes(WAIT)) return SeatStatus.WAITING;
  if (tokens.includes(GENERAL) || tokens.includes(FIRST_CLASS)) return SeatStatus.AVAILABLE;

  return SeatStatus.UNKNOWN;
}

/**
 * class 에서 좌석 등급을 읽는다. 읽어낼 수 없으면 null.
 *
 * 매진 칸에는 등급 class 가 없으므로 null 이 정상이며, 호출부가 **위치로 보정**해야 한다.
 * (§38-3, `[0]`=일반실 `[1]`=특실)
 */
export function seatClassFromClassNames(classNames) {
  const tokens = tokensOf(classNames);
  if (tokens.includes(GENERAL)) return SeatClass.GENERAL;
  if (tokens.includes(FIRST_CLASS)) return SeatClass.FIRST_CLASS;
  return null;
}

/** 1단계로 이 칸을 눌러 고른 상태인가. (§38-6-1 의 확인 1) */
export function isSelectedCellClassNames(classNames) {
  return tokensOf(classNames).includes(ACTIVE);
}

// --- 텍스트 기반 판정 (대체 경로) --------------------------------------------

/** "매진" 을 포함하지만 매진이 아닌 문구. 반드시 [SOLD_OUT_TOKENS] 보다 먼저 본다. */
const AVAILABLE_DESPITE_SOLD_OUT_TOKENS = ['매진임박'];

const SOLD_OUT_TOKENS = ['매진', '좌석없음', '잔여석없음', '없음'];
const WAITING_TOKENS = ['예약대기', '대기', '입석'];
const AVAILABLE_TOKENS = ['예약하기', '좌석선택', '예매하기', '선택하기', '가능', '있음'];

/** 좌석 칸의 텍스트로 판정한다. class 를 읽지 못했을 때만 쓴다. */
export function seatStatusFromText(rawText) {
  const text = (rawText ?? '').replace(/\s+/g, '').trim();
  if (text.length === 0 || /^-+$/.test(text)) return SeatStatus.UNKNOWN;

  // 판정 순서가 중요하다. "매진임박" 은 "매진" 을 포함한다. (§38-2)
  if (AVAILABLE_DESPITE_SOLD_OUT_TOKENS.some((t) => text.includes(t))) return SeatStatus.AVAILABLE;
  if (SOLD_OUT_TOKENS.some((t) => text.includes(t))) return SeatStatus.SOLD_OUT;
  if (WAITING_TOKENS.some((t) => text.includes(t))) return SeatStatus.WAITING;
  if (AVAILABLE_TOKENS.some((t) => text.includes(t))) return SeatStatus.AVAILABLE;

  return SeatStatus.UNKNOWN;
}

/**
 * content script 가 이미 판정해 보낸 값을 믿고, 알 수 없으면 class → 텍스트 순으로 다시 본다.
 * (android: SeatParser.fromJs)
 */
export function seatStatusFromRaw(rawStatus, rawText, classNames) {
  if (rawStatus && rawStatus !== SeatStatus.UNKNOWN && rawStatus in SeatStatus) {
    return rawStatus;
  }
  const byClass = seatStatusFromClassNames(classNames);
  return byClass !== SeatStatus.UNKNOWN ? byClass : seatStatusFromText(rawText);
}
