// 판정 결과 모델. (android: domain/MatchResult.kt + domain/MatchKey.kt)

import { seatClassLabel } from './seat-class.js';
import { seatStatusLabel } from './seat-status.js';
import { keyOf, seatStatusOf } from './train.js';
import { trainKeyLabel } from './train-key.js';

/** 조건을 만족한 (열차, 좌석등급) 한 건. `{ train, seatClass }` */
export function describeMatch(match) {
  const status = seatStatusOf(match.train, match.seatClass);
  return `${match.train.trainNumber} ${match.train.departureTime} ` +
    `${seatClassLabel(match.seatClass)} ${seatStatusLabel(status)}`;
}

/**
 * 중복 알림 방지 키. (DESIGN.md §20)
 *
 * 같은 열차 + 같은 좌석등급은 한 번만 알린다. 매진으로 바뀌면 키를 지워서,
 * 다시 열리면 새 알림이 나가게 한다.
 *
 * 안드로이드는 `data class MatchKey` 를 Set 에 넣지만 여기서는 **문자열**이다.
 * Set<객체> 는 값 비교가 되지 않고, 감시 상태는 storage 를 건너가야 한다.
 */
export function matchKeyOf(match) {
  return `${trainKeyLabel(keyOf(match.train))}|${match.seatClass}`;
}

export function noMatch(reason) {
  return { matched: false, train: null, reason, matches: [] };
}
