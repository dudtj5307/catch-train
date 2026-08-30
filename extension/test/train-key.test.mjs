// 열차 식별 규칙. (android: domain/TrainKeyTest.kt, DESIGN.md §38-4)
//
// 케이스를 그대로 옮겼다. **같은 입력에 같은 답이 나와야 한다** (§34-2).
// 실측 근거는 코레일 동탄→김천구미 조회 결과다 —
// 07:11 에 305 와 381 이, 18:55 에 353 과 397 이 함께 있었다.

import test from 'node:test';
import assert from 'node:assert/strict';

import {
  parseTime,
  trainKey,
  trainKeyAmbiguous,
  trainKeyLabel,
  trainKeyMatches,
} from '../src/domain/train-key.js';
import { SeatClass } from '../src/domain/seat-class.js';
import {
  EMPTY_SELECTION,
  selectionContains,
  selectionToggle,
} from '../src/domain/watch-selection.js';

const key = (number, hour, minute = 0) =>
  trainKey(number, `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`);

test('번호가 같으면 같은 열차다', () => {
  assert.ok(trainKeyMatches(key('305', 7, 11), key('305', 7, 11)));
});

test('출발 시각이 같아도 번호가 다르면 다른 열차다', () => {
  // 이것이 SRT 규칙에서 실제로 깨지던 경우다.
  assert.ok(!trainKeyMatches(key('305', 7, 11), key('381', 7, 11)));
  assert.ok(!trainKeyMatches(key('381', 7, 11), key('305', 7, 11)));
});

test('18시 55분의 353 과 397 도 서로 다른 열차다', () => {
  assert.ok(!trainKeyMatches(key('353', 18, 55), key('397', 18, 55)));
});

test('번호가 같으면 시각을 다시 읽은 값이 달라도 같은 열차로 본다', () => {
  assert.ok(trainKeyMatches(key('305', 7, 11), key('305', 7, 12)));
});

test('한쪽 번호를 읽지 못하면 출발 시각으로 대체 판정한다', () => {
  const known = key('305', 7, 11);
  const unread = key('', 7, 11);

  assert.ok(trainKeyMatches(known, unread));
  assert.ok(trainKeyMatches(unread, known));
  assert.ok(!trainKeyMatches(known, key('', 8, 11)));
});

test('번호를 읽지 못한 키는 모호한 키로 표시된다', () => {
  assert.ok(trainKeyAmbiguous(key('', 7, 11)));
  assert.ok(!trainKeyAmbiguous(key('305', 7, 11)));
});

test('표시 문자열', () => {
  assert.equal(trainKeyLabel(key('305', 7, 11)), '07:11 305');
  assert.equal(trainKeyLabel(key('', 7, 11)), '07:11');
});

test('선택은 번호로 유지된다', () => {
  // 재조회로 목록 순서가 바뀌어도 체크해 둔 편성은 그대로 남아야 한다.
  const selection = selectionToggle(EMPTY_SELECTION, key('381', 7, 11), SeatClass.GENERAL);

  assert.ok(selectionContains(selection, key('381', 7, 11), SeatClass.GENERAL));
  // 같은 시각의 다른 편성이 열려도 내 선택이 아니다.
  assert.ok(!selectionContains(selection, key('305', 7, 11), SeatClass.GENERAL));
});

test('시각 표기는 0 을 채워 정규화한다', () => {
  // 문자열 비교만으로 정렬되려면 자릿수가 같아야 한다.
  assert.equal(parseTime('7:11'), '07:11');
  assert.equal(parseTime('출발 18:32'), '18:32');
  assert.equal(parseTime('18:32 도착'), '18:32');
  assert.equal(parseTime(''), null);
  assert.equal(parseTime('25:00'), null);
  assert.equal(parseTime('없음'), null);
});
