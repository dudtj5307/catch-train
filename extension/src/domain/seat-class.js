// 좌석 등급. (android: domain/SeatClass.kt, DESIGN.md §18)
//
// MVP 에서는 일반실/특실까지만 다룬다. 자유석·입석이 별도 칸으로 나오는지는
// 아직 실측이 없다 (§38-8) — 확정되기 전에는 늘리지 않는다.

export const SeatClass = Object.freeze({
  GENERAL: 'GENERAL',
  FIRST_CLASS: 'FIRST_CLASS',
});

/**
 * 선언 순서가 곧 우선순위다. 같은 열차에서 두 칸이 동시에 열리면
 * 일반실(= 싼 쪽)을 먼저 본다. (SelectionEngine 의 정렬 기준)
 *
 * 화면에 늘어놓는 순서이기도 하다 — 사이트와 같게 **일반실이 왼쪽**. (§38-3)
 */
export const SEAT_CLASSES = Object.freeze([SeatClass.GENERAL, SeatClass.FIRST_CLASS]);

export function seatClassOrdinal(seatClass) {
  const index = SEAT_CLASSES.indexOf(seatClass);
  return index >= 0 ? index : SEAT_CLASSES.length;
}

export function seatClassLabel(seatClass) {
  switch (seatClass) {
    case SeatClass.GENERAL:
      return '일반실';
    case SeatClass.FIRST_CLASS:
      return '특실';
    default:
      return '?';
  }
}
