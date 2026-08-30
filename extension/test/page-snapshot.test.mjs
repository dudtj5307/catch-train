// 판독 결과 → 도메인 모델. (android: parser/KtxParserTest.kt)
//
// 안드로이드의 "JSON 문자열이 한 번 더 감싸여 오는 경우" 케이스는 옮기지 않았다.
// 그것은 `WebView.evaluateJavascript` 의 성질이고, chrome 메시지는 객체로 온다.

import test from 'node:test';
import assert from 'node:assert/strict';

import { SeatClass } from '../src/domain/seat-class.js';
import { SeatStatus } from '../src/domain/seat-status.js';
import { trainKeyAmbiguous, trainKeyMatches } from '../src/domain/train-key.js';
import { keyOf } from '../src/domain/train.js';
import {
  DomParseError,
  PageStatus,
  cellIndexOf,
  isSettled,
  rowRefOf,
  rowRefUsable,
  snapshotFromRaw,
} from '../src/domain/page-snapshot.js';

/** 실측 형태 그대로. `[0]`=일반실 `[1]`=특실 이고 매진 칸에는 등급 class 가 없다. (§38-3) */
const sample = {
  status: 'TRAIN_LIST',
  url: 'https://www.korail.com/ticket/search/list',
  title: '승차권 예매',
  rowCount: 2,
  trains: [
    {
      trainNumber: '305',
      trainType: 'KTX-산천',
      departureStation: '동탄',
      arrivalStation: '김천구미',
      departureTime: '07:11',
      arrivalTime: '08:17',
      generalSeatText: '일반실25,700원',
      generalSeatStatus: 'AVAILABLE',
      generalSeatClass: 'price_box fl-l gen',
      generalCellIndex: 0,
      firstClassSeatText: '매진',
      firstClassSeatStatus: 'SOLD_OUT',
      firstClassSeatClass: 'price_box fl-l sold_out',
      firstClassCellIndex: 1,
      rowKey: '2:abc123',
      rowIndex: 0,
    },
    {
      trainNumber: '381',
      trainType: 'KTX-산천',
      departureStation: '동탄',
      arrivalStation: '김천구미',
      departureTime: '07:11',
      arrivalTime: '08:29',
      generalSeatText: '매진',
      generalSeatStatus: 'SOLD_OUT',
      generalSeatClass: 'price_box fl-l sold_out',
      generalCellIndex: 0,
      firstClassSeatText: '특실(매진임박)37,200원',
      firstClassSeatStatus: 'AVAILABLE',
      firstClassSeatClass: 'price_box fl-l spe sold_out_soon',
      firstClassCellIndex: 1,
      rowKey: '2:def456',
      rowIndex: 1,
    },
  ],
  warnings: [],
};

test('판독 결과를 도메인 모델로 옮긴다', () => {
  const snapshot = snapshotFromRaw(sample);

  assert.equal(snapshot.status, PageStatus.TRAIN_LIST);
  assert.equal(snapshot.trains.length, 2);

  const first = snapshot.trains[0];
  assert.equal(first.trainNumber, '305');
  assert.equal(first.departureTime, '07:11');
  assert.equal(first.arrivalTime, '08:17');
  assert.equal(first.generalSeat, SeatStatus.AVAILABLE);
  assert.equal(first.firstClassSeat, SeatStatus.SOLD_OUT);

  // 매진임박은 **살 수 있는 칸**이다. 문구에 "매진" 이 들어가는 함정. (§38-2)
  const second = snapshot.trains[1];
  assert.equal(second.trainNumber, '381');
  assert.equal(second.generalSeat, SeatStatus.SOLD_OUT);
  assert.equal(second.firstClassSeat, SeatStatus.AVAILABLE);
});

test('출발 시각이 겹쳐도 열차 번호로 구분된다', () => {
  const trains = snapshotFromRaw(sample).trains;

  assert.equal(trains[0].departureTime, trains[1].departureTime);
  assert.ok(!trainKeyMatches(keyOf(trains[0]), keyOf(trains[1])), '같은 열차로 읽히면 안 된다');
});

test('좌석 칸 순번을 행 참조에 담는다', () => {
  const snapshot = snapshotFromRaw(sample);
  const ref = rowRefOf(snapshot, snapshot.trains[0]);

  assert.equal(ref.rowKey, '2:abc123');
  assert.equal(cellIndexOf(ref, SeatClass.GENERAL), 0);
  assert.equal(cellIndexOf(ref, SeatClass.FIRST_CLASS), 1);
  assert.ok(rowRefUsable(ref));
});

test('열차 목록이 비면 NO_TRAIN 으로 강등한다', () => {
  const snapshot = snapshotFromRaw({ status: 'TRAIN_LIST', trains: [], url: '', title: '', rowCount: 0 });
  assert.equal(snapshot.status, PageStatus.NO_TRAIN);
});

test('알 수 없는 status 는 UNKNOWN_PAGE 로 처리한다', () => {
  assert.equal(snapshotFromRaw({ status: 'WHAT_IS_THIS', trains: [] }).status, PageStatus.UNKNOWN_PAGE);
});

test('UNKNOWN_PAGE 만 확정되지 않은 상태다', () => {
  // 이 구분이 대기열 처리의 전부다. (§39)
  assert.ok(!isSettled(PageStatus.UNKNOWN_PAGE));
  for (const status of [
    PageStatus.TRAIN_LIST, PageStatus.NO_TRAIN, PageStatus.LOGIN_REQUIRED,
    PageStatus.SESSION_EXPIRED, PageStatus.BLOCKED,
  ]) {
    assert.ok(isSettled(status), status);
  }
});

test('시간이 없는 행은 건너뛰고 경고를 남긴다', () => {
  const snapshot = snapshotFromRaw({
    status: 'TRAIN_LIST',
    trains: [
      { trainNumber: '301', departureTime: '', arrivalTime: '', generalSeatStatus: 'AVAILABLE' },
      { trainNumber: '305', departureTime: '08:00', arrivalTime: '10:00', generalSeatStatus: 'AVAILABLE' },
    ],
  });

  assert.equal(snapshot.trains.length, 1);
  assert.equal(snapshot.trains[0].trainNumber, '305');
  assert.ok(snapshot.warnings.length > 0);
});

test('열차 번호를 읽지 못하면 종류로 메우지 않는다', () => {
  // 종류로 메우면 모든 편성이 같은 값을 갖게 되어 서로 다른 열차가 한 열차로 합쳐진다. (§38-4)
  const snapshot = snapshotFromRaw({
    status: 'TRAIN_LIST',
    trains: [
      { trainType: 'KTX-산천', departureTime: '08:00', arrivalTime: '10:00' },
      { trainType: 'KTX-산천', departureTime: '09:00', arrivalTime: '11:00' },
    ],
  });
  const trains = snapshot.trains;

  assert.equal(trains.length, 2);
  assert.equal(trains[0].trainNumber, '');
  assert.ok(trainKeyAmbiguous(keyOf(trains[0])), '번호가 없으면 모호한 키다');
  assert.ok(!trainKeyMatches(keyOf(trains[0]), keyOf(trains[1])), '시각이 다르면 다른 열차여야 한다');
  assert.ok(snapshot.warnings.length > 0);
});

test('로그인 페이지 상태를 그대로 전달한다', () => {
  assert.equal(snapshotFromRaw({ status: 'LOGIN_REQUIRED', trains: [] }).status, PageStatus.LOGIN_REQUIRED);
});

test('빈 결과는 예외로 처리한다', () => {
  assert.throws(() => snapshotFromRaw(null), DomParseError);
  assert.throws(() => snapshotFromRaw(undefined), DomParseError);
  assert.throws(() => snapshotFromRaw('<html>'), DomParseError);
  assert.throws(() => snapshotFromRaw([]), DomParseError);
});

test('판독이 상태를 모르면 class 로 재판정한다', () => {
  // 텍스트를 먼저 보면 `특실(매진임박)` 을 매진으로 오판한다. (§38-2)
  const train = snapshotFromRaw({
    status: 'TRAIN_LIST',
    trains: [{
      trainNumber: '305', departureTime: '08:00', arrivalTime: '10:00',
      generalSeatText: '매진', generalSeatStatus: 'UNKNOWN',
      generalSeatClass: 'price_box fl-l sold_out',
      firstClassSeatText: '특실(매진임박)37,200원', firstClassSeatStatus: 'UNKNOWN',
      firstClassSeatClass: 'price_box fl-l spe sold_out_soon',
    }],
  }).trains[0];

  assert.equal(train.generalSeat, SeatStatus.SOLD_OUT);
  assert.equal(train.firstClassSeat, SeatStatus.AVAILABLE);
});

test('class 가 없으면 텍스트로 재판정한다', () => {
  const train = snapshotFromRaw({
    status: 'TRAIN_LIST',
    trains: [{
      trainNumber: '305', departureTime: '08:00', arrivalTime: '10:00',
      generalSeatText: '매진임박', generalSeatStatus: 'UNKNOWN',
      firstClassSeatText: '매진', firstClassSeatStatus: 'UNKNOWN',
    }],
  }).trains[0];

  assert.equal(train.generalSeat, SeatStatus.AVAILABLE);
  assert.equal(train.firstClassSeat, SeatStatus.SOLD_OUT);
});
