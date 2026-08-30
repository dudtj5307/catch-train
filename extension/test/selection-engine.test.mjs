// 선택 판정. (android: domain/SelectionEngineTest.kt)
//
// 케이스를 그대로 옮겼다. 두 클라이언트가 **같은 입력에 같은 답**을 내야 한다 (§34-2).

import test from 'node:test';
import assert from 'node:assert/strict';

import { SeatClass } from '../src/domain/seat-class.js';
import { SeatStatus } from '../src/domain/seat-status.js';
import { match } from '../src/domain/selection-engine.js';
import { describeMatch, matchKeyOf } from '../src/domain/match.js';
import { keyOf, trainSummary } from '../src/domain/train.js';
import {
  EMPTY_SELECTION,
  normalizeSelection,
  selectionContainsTrain,
  selectionIsEmpty,
  selectionOf,
  selectionRetainOnly,
  selectionSize,
  selectionToggle,
  selectionTrainCount,
  selectionValidate,
} from '../src/domain/watch-selection.js';

const train = (number, hour, general = SeatStatus.SOLD_OUT, firstClass = SeatStatus.SOLD_OUT) => ({
  trainNumber: number,
  departureStation: '수서',
  arrivalStation: '부산',
  departureTime: `${String(hour).padStart(2, '0')}:30`,
  arrivalTime: `${String(hour + 2).padStart(2, '0')}:35`,
  generalSeat: general,
  firstClassSeat: firstClass,
});

const selection = (...seats) =>
  selectionOf(seats.map(([t, seatClass]) => ({ trainKey: keyOf(t), seatClass })));

test('선택한 칸이 예약가능이면 발견이다', () => {
  const target = train('SRT 339', 18, SeatStatus.AVAILABLE);
  const result = match([target], selection([target, SeatClass.GENERAL]));

  assert.ok(result.matched);
  assert.equal(result.matches.length, 1);
  assert.equal(result.matches[0].seatClass, SeatClass.GENERAL);
});

test('선택하지 않은 칸이 열려도 발견이 아니다', () => {
  // 특실이 열렸지만 사용자가 고른 것은 일반실이다.
  const target = train('SRT 339', 18, SeatStatus.SOLD_OUT, SeatStatus.AVAILABLE);
  const result = match([target], selection([target, SeatClass.GENERAL]));

  assert.ok(!result.matched);
  assert.equal(result.matches.length, 0);
});

test('선택하지 않은 열차는 아예 보지 않는다', () => {
  const picked = train('SRT 339', 18);
  const other = train('SRT 341', 19, SeatStatus.AVAILABLE);

  const result = match([picked, other], selection([picked, SeatClass.GENERAL]));
  assert.ok(!result.matched);
});

test('예약대기는 발견으로 보지 않는다', () => {
  const target = train('SRT 339', 18, SeatStatus.WAITING);
  const result = match([target], selection([target, SeatClass.GENERAL]));

  assert.ok(!result.matched);
  assert.equal(result.reason, '선택한 좌석이 아직 나오지 않음');
});

test('한 열차에서 특실과 일반실을 모두 고르면 두 건으로 집계한다', () => {
  const target = train('SRT 339', 18, SeatStatus.AVAILABLE, SeatStatus.AVAILABLE);
  const result = match(
    [target],
    selection([target, SeatClass.GENERAL], [target, SeatClass.FIRST_CLASS]),
  );

  assert.equal(result.matches.length, 2);
  // 같은 열차라면 일반실을 먼저 본다. (더 싼 쪽)
  assert.equal(result.matches[0].seatClass, SeatClass.GENERAL);
});

test('여러 열차가 동시에 열리면 먼저 출발하는 열차가 앞에 온다', () => {
  const late = train('SRT 341', 19, SeatStatus.AVAILABLE);
  const early = train('SRT 339', 18, SeatStatus.AVAILABLE);

  const result = match(
    [late, early],
    selection([late, SeatClass.GENERAL], [early, SeatClass.GENERAL]),
  );

  assert.equal(result.matches[0].train.trainNumber, 'SRT 339');
  assert.equal(result.train.trainNumber, 'SRT 339');
});

test('열차 번호를 못 읽어도 출발 시각이 같으면 같은 열차로 본다', () => {
  const picked = train('SRT 339', 18, SeatStatus.AVAILABLE);
  // 다음 조회에서는 파서가 열차 번호를 읽지 못했다.
  const reparsed = { ...picked, trainNumber: '' };

  const result = match([reparsed], selection([picked, SeatClass.GENERAL]));
  assert.ok(result.matched);
});

test('선택이 비어 있으면 사유를 남긴다', () => {
  const target = train('SRT 339', 18, SeatStatus.AVAILABLE);
  const result = match([target], EMPTY_SELECTION);

  assert.ok(!result.matched);
  assert.equal(result.reason, '선택된 열차 없음');
});

test('선택한 열차가 이번 조회 결과에 없으면 사유를 남긴다', () => {
  const picked = train('SRT 339', 18, SeatStatus.AVAILABLE);
  const different = train('SRT 501', 7, SeatStatus.AVAILABLE);

  const result = match([different], selection([picked, SeatClass.GENERAL]));

  assert.ok(!result.matched);
  assert.equal(result.reason, '선택한 열차가 이번 조회 결과에 없음');
});

test('화면에 없는 열차의 선택은 정리한다', () => {
  const picked = train('SRT 339', 18);
  const other = train('SRT 341', 19);
  const picked2 = selection([picked, SeatClass.GENERAL], [other, SeatClass.FIRST_CLASS]);

  const kept = selectionRetainOnly(picked2, [other]);

  assert.equal(selectionSize(kept), 1);
  assert.ok(selectionContainsTrain(kept, other, SeatClass.FIRST_CLASS));
});

test('토글은 같은 칸을 다시 누르면 해제한다', () => {
  const target = train('SRT 339', 18);
  const on = selectionToggle(EMPTY_SELECTION, keyOf(target), SeatClass.FIRST_CLASS);
  assert.ok(selectionContainsTrain(on, target, SeatClass.FIRST_CLASS));
  assert.ok(!selectionContainsTrain(on, target, SeatClass.GENERAL));

  const off = selectionToggle(on, keyOf(target), SeatClass.FIRST_CLASS);
  assert.ok(selectionIsEmpty(off));
});

test('저장소에서 온 값은 모양이 어긋난 항목만 버린다', () => {
  // chrome.storage 의 값은 확장을 새로 로드하기 전의 것일 수 있다.
  const target = train('305', 7);
  const restored = selectionOf([
    { trainKey: keyOf(target), seatClass: SeatClass.GENERAL },
    { trainKey: keyOf(target), seatClass: 'WHAT_IS_THIS' },
    { seatClass: SeatClass.GENERAL },
    null,
  ]);

  const cleaned = normalizeSelection(restored);
  assert.equal(selectionSize(cleaned), 1);

  const result = match([{ ...target, generalSeat: SeatStatus.AVAILABLE }], cleaned);
  assert.ok(result.matched);
});

test('알림과 로그가 쓰는 표시값', () => {
  // 안드로이드의 `SeatMatch.describe()` / `MatchKey` / `Train.summary()` 자리다.
  const target = train('305', 7, SeatStatus.AVAILABLE);
  target.departureStation = '동탄';
  target.arrivalStation = '김천구미';
  const result = match([target], selection([target, SeatClass.GENERAL]));

  assert.equal(describeMatch(result.matches[0]), '305 07:30 일반실 예약가능');
  // 중복 알림 방지 키. 같은 열차·같은 등급은 한 번만 알린다 (§20)
  assert.equal(matchKeyOf(result.matches[0]), '07:30 305|GENERAL');
  assert.equal(trainSummary(target), '305  동탄 → 김천구미  07:30 → 09:35');
});

test('선택 요약과 검증', () => {
  const a = train('305', 7);
  const b = train('381', 7);
  const picked = selection([a, SeatClass.GENERAL], [a, SeatClass.FIRST_CLASS], [b, SeatClass.GENERAL]);

  assert.equal(selectionSize(picked), 3);
  assert.equal(selectionTrainCount(picked), 2);
  assert.equal(selectionValidate(picked), null);
  assert.equal(selectionValidate(EMPTY_SELECTION), '감시할 열차를 하나 이상 선택하세요.');
});
