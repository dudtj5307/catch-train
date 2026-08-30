// 좌석 상태 판정. (android: parser/SeatParserTest.kt, DESIGN.md §38-2)
//
// 아래 class 조합은 전부 실측에서 나온 것이다. (동탄→김천구미, 10편성)

import test from 'node:test';
import assert from 'node:assert/strict';

import { SeatClass } from '../src/domain/seat-class.js';
import {
  SeatStatus,
  isSelectedCellClassNames,
  seatClassFromClassNames,
  seatStatusFromClassNames,
  seatStatusFromRaw,
  seatStatusFromText,
} from '../src/domain/seat-status.js';

test('예약 가능 표기', () => {
  assert.equal(seatStatusFromText('예약하기'), SeatStatus.AVAILABLE);
  assert.equal(seatStatusFromText(' 좌석선택 '), SeatStatus.AVAILABLE);
  assert.equal(seatStatusFromText('예약 가능'), SeatStatus.AVAILABLE);
});

test('매진 표기', () => {
  assert.equal(seatStatusFromText('매진'), SeatStatus.SOLD_OUT);
  assert.equal(seatStatusFromText('좌석없음'), SeatStatus.SOLD_OUT);
  assert.equal(seatStatusFromText('잔여석없음'), SeatStatus.SOLD_OUT);
});

test('예약대기와 입석은 WAITING', () => {
  assert.equal(seatStatusFromText('예약대기'), SeatStatus.WAITING);
  assert.equal(seatStatusFromText('입석+좌석'), SeatStatus.WAITING);
});

test('빈 값과 대시는 UNKNOWN', () => {
  assert.equal(seatStatusFromText(null), SeatStatus.UNKNOWN);
  assert.equal(seatStatusFromText(''), SeatStatus.UNKNOWN);
  assert.equal(seatStatusFromText('-'), SeatStatus.UNKNOWN);
  assert.equal(seatStatusFromText('---'), SeatStatus.UNKNOWN);
});

test('모르는 문자열은 UNKNOWN', () => {
  assert.equal(seatStatusFromText('38,000원'), SeatStatus.UNKNOWN);
});

test('판독 결과를 우선하고 UNKNOWN 이면 텍스트로 보정한다', () => {
  assert.equal(seatStatusFromRaw('SOLD_OUT', '예약하기'), SeatStatus.SOLD_OUT);
  assert.equal(seatStatusFromRaw('UNKNOWN', '예약하기'), SeatStatus.AVAILABLE);
  assert.equal(seatStatusFromRaw(null, '예약하기'), SeatStatus.AVAILABLE);
  assert.equal(seatStatusFromRaw('이상한값', '38,000원'), SeatStatus.UNKNOWN);
});

// --- 코레일 class 기반 판정 (§38-2) ----------------------------------------

test('매진임박은 살 수 있는 칸이다', () => {
  // 실측 10편성 중 3편성이 이 상태였다. 텍스트로 읽으면 전부 놓친다.
  assert.equal(seatStatusFromClassNames('price_box fl-l spe sold_out_soon'), SeatStatus.AVAILABLE);
  assert.equal(seatStatusFromText('특실(매진임박) 37,200원 5%적립'), SeatStatus.AVAILABLE);
});

test('예약 가능한 칸', () => {
  assert.equal(seatStatusFromClassNames('price_box fl-l gen'), SeatStatus.AVAILABLE);
  assert.equal(seatStatusFromClassNames('price_box fl-l spe'), SeatStatus.AVAILABLE);
});

test('매진인 칸', () => {
  assert.equal(seatStatusFromClassNames('price_box fl-l sold_out'), SeatStatus.SOLD_OUT);
  assert.equal(seatStatusFromClassNames('price_box fl-l sold_out_wait'), SeatStatus.SOLD_OUT);
});

test('예약대기는 WAITING 이고 sold_out_wait 와 섞이지 않는다', () => {
  assert.equal(seatStatusFromClassNames('price_box fl-l wait'), SeatStatus.WAITING);
  // sold_out_wait 안에 wait 가 들어 있다. 부분일치로 읽으면 여기서 어긋난다.
  assert.equal(seatStatusFromClassNames('price_box fl-l sold_out_wait'), SeatStatus.SOLD_OUT);
});

test('선택 표시는 상태 판정을 바꾸지 않는다', () => {
  assert.equal(seatStatusFromClassNames('price_box fl-l active gen'), SeatStatus.AVAILABLE);
  assert.equal(seatStatusFromClassNames('price_box fl-l active wait'), SeatStatus.WAITING);

  assert.ok(isSelectedCellClassNames('price_box fl-l active gen'));
  assert.ok(!isSelectedCellClassNames('price_box fl-l gen'));
});

test('등급은 class 에서 읽고 매진 칸은 읽지 못한다', () => {
  assert.equal(seatClassFromClassNames('price_box fl-l gen'), SeatClass.GENERAL);
  assert.equal(seatClassFromClassNames('price_box fl-l spe sold_out_soon'), SeatClass.FIRST_CLASS);

  // 매진 칸에는 등급 class 가 붙지 않는다. 위치로 보정해야 한다. (§38-3)
  assert.equal(seatClassFromClassNames('price_box fl-l sold_out'), null);
  assert.equal(seatClassFromClassNames('price_box fl-l wait'), null);
});

test('모르는 class 는 UNKNOWN', () => {
  assert.equal(seatStatusFromClassNames(null), SeatStatus.UNKNOWN);
  assert.equal(seatStatusFromClassNames(''), SeatStatus.UNKNOWN);
  assert.equal(seatStatusFromClassNames('price_box fl-l'), SeatStatus.UNKNOWN);
});

test('판독이 상태를 모르면 텍스트보다 class 를 먼저 믿는다', () => {
  // 문구에는 "매진" 이 들어 있지만 class 는 살 수 있는 칸이라고 말한다. class 가 맞다.
  assert.equal(
    seatStatusFromRaw('UNKNOWN', '특실(매진임박) 37,200원', 'price_box fl-l spe sold_out_soon'),
    SeatStatus.AVAILABLE,
  );
});
