// DOM 한 번 판독의 결과와, content script 가 보내온 값 → 도메인 모델 변환.
// (android: parser/PageSnapshot.kt + parser/KtxParser.kt + parser/TrainParser.kt)
//
// 추출은 content script(`content/ktx/parse.js`)가, **판정은 여기서** 한다.
// 안드로이드에서 JS 가 뽑고 Kotlin 이 모델로 만들던 분업 그대로다.
//
// 안드로이드에 있던 "JSON 문자열이 한 번 더 감싸여 오는 경우" 처리는 없다.
// 그것은 `WebView.evaluateJavascript` 의 성질이었고, chrome 메시지는 객체로 온다.

import { SeatClass } from './seat-class.js';
import { seatStatusFromRaw } from './seat-status.js';
import { parseTime } from './train-key.js';

/** 지금 페이지가 어떤 종류인가. (DESIGN.md §27) */
export const PageStatus = Object.freeze({
  /** 열차 조회 결과 목록을 찾음 */
  TRAIN_LIST: 'TRAIN_LIST',
  /** 조회 결과 화면이지만 열차가 0건 */
  NO_TRAIN: 'NO_TRAIN',
  /** 로그인 화면 */
  LOGIN_REQUIRED: 'LOGIN_REQUIRED',
  /** 세션 만료 */
  SESSION_EXPIRED: 'SESSION_EXPIRED',
  /** 접속 차단 / 비정상 접근 안내. 즉시 중지한다 */
  BLOCKED: 'BLOCKED',
  /** 감시할 수 있는 화면이 아님 — **아직 아무것도 못 알아봤다** 는 뜻이기도 하다 */
  UNKNOWN_PAGE: 'UNKNOWN_PAGE',
});

/**
 * 이 판정을 그대로 믿고 다음 단계로 가도 되는가. (DESIGN.md §39)
 *
 * [UNKNOWN_PAGE] 만 false 다. 화면이 정말 엉뚱한 곳일 수도, 아직 그려지는 중일 수도,
 * 접속 대기열일 수도 있어서 감시 루프는 이 값이 나오면 제자리에서 다시 읽는다.
 * 나머지 다섯은 무언가를 확실히 알아본 결과라 더 기다려도 답이 바뀌지 않는다.
 */
export function isSettled(status) {
  return status !== PageStatus.UNKNOWN_PAGE;
}

export class DomParseError extends Error {}

/**
 * content script 가 보낸 판독 결과 → [PageSnapshot].
 *
 * 한 편성이 깨져도 전체가 실패하지 않게, 그 행만 버리고 경고를 남긴다.
 */
export function snapshotFromRaw(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
    throw new DomParseError('DOM 판독 결과가 비어 있습니다.');
  }

  const warnings = [];
  const rows = Array.isArray(raw.trains) ? raw.trains : [];
  const trains = [];
  // trains 와 rowRefs 는 항상 같은 순서·같은 길이를 유지해야 한다. 함께 채운다.
  const rowRefs = [];

  rows.forEach((row, i) => {
    const train = trainFromRow(row, warnings);
    if (!train) return;
    trains.push(train);
    rowRefs.push({
      rowKey: typeof row.rowKey === 'string' ? row.rowKey : '',
      rowIndex: Number.isInteger(row.rowIndex) ? row.rowIndex : i,
      generalCellIndex: Number.isInteger(row.generalCellIndex) ? row.generalCellIndex : -1,
      firstClassCellIndex: Number.isInteger(row.firstClassCellIndex) ? row.firstClassCellIndex : -1,
    });
  });

  if (Array.isArray(raw.warnings)) {
    for (const w of raw.warnings) if (typeof w === 'string' && w.trim() !== '') warnings.push(w);
  }

  const declared = typeof raw.status === 'string' ? raw.status : '';
  const status = declared in PageStatus ? declared : PageStatus.UNKNOWN_PAGE;

  // 목록 컨테이너는 찾았는데 유효한 행이 하나도 없으면 NO_TRAIN 으로 강등한다.
  const effective = status === PageStatus.TRAIN_LIST && trains.length === 0
    ? PageStatus.NO_TRAIN
    : status;

  return {
    status: effective,
    url: typeof raw.url === 'string' ? raw.url : '',
    title: typeof raw.title === 'string' ? raw.title : '',
    trains,
    rowCount: Number.isInteger(raw.rowCount) ? raw.rowCount : rows.length,
    /**
     * 조회 폼이 들고 있던 출발일. 읽어내지 못했으면 빈 문자열.
     * **표시용이다** — 감시 판정에는 쓰지 않는다 (대원칙 4).
     */
    searchDate: typeof raw.searchDate === 'string' ? raw.searchDate : '',
    warnings,
    rowRefs,
  };
}

/**
 * 행 하나 → [Train]. 유효하지 않으면 null 을 돌려주고 이유를 [warnings] 에 남긴다.
 *
 * 열차 번호는 [TrainKey] 의 **주키**다 (§38-4). 읽지 못했으면 빈 문자열로 둔다 —
 * 열차 종류("KTX-산천")로 메우면 그럴듯해 보이지만 모든 편성이 같은 값을 갖게 되어
 * 서로 다른 열차가 한 열차로 합쳐진다.
 */
function trainFromRow(row, warnings) {
  if (!row || typeof row !== 'object') return null;

  const trainNumber = str(row.trainNumber).trim();
  if (trainNumber === '') {
    warnings.push('열차 번호를 읽지 못했습니다 (출발 시각으로 구분합니다)');
  }

  const departureTime = parseTime(row.departureTime);
  if (!departureTime) {
    warnings.push(`출발시간 파싱 실패: ${str(row.departureTime)}`);
    return null;
  }
  const arrivalTime = parseTime(row.arrivalTime);
  if (!arrivalTime) {
    warnings.push(`도착시간 파싱 실패: ${str(row.arrivalTime)}`);
    return null;
  }

  return {
    trainNumber,
    trainType: str(row.trainType).trim(),
    departureStation: str(row.departureStation).trim(),
    arrivalStation: str(row.arrivalStation).trim(),
    departureTime,
    arrivalTime,
    // class 문자열을 함께 넘겨받는다. content script 가 판정하지 못한 칸을
    // 여기서 같은 규칙으로 다시 볼 수 있어야 한다. 텍스트는 마지막 대체 경로다. (§38-2)
    generalSeat: seatStatusFromRaw(row.generalSeatStatus, row.generalSeatText, row.generalSeatClass),
    firstClassSeat: seatStatusFromRaw(
      row.firstClassSeatStatus, row.firstClassSeatText, row.firstClassSeatClass,
    ),
  };
}

function str(value) {
  return typeof value === 'string' ? value : '';
}

/** [train] 이 화면의 어느 행에서 나왔는지. 알 수 없으면 null. (예매할 때만 쓴다) */
export function rowRefOf(snapshot, train) {
  const index = snapshot.trains.indexOf(train);
  return index >= 0 ? snapshot.rowRefs[index] ?? null : null;
}

/** 좌석 등급에 해당하는 칸의 위치. 특정하지 못했으면 -1. (§38-3) */
export function cellIndexOf(rowRef, seatClass) {
  if (!rowRef) return -1;
  return seatClass === SeatClass.GENERAL ? rowRef.generalCellIndex : rowRef.firstClassCellIndex;
}

export function rowRefUsable(rowRef) {
  return !!rowRef && typeof rowRef.rowKey === 'string' && rowRef.rowKey.trim() !== '';
}

/** 화면에 보일 한 줄 요약. 상태별로 무엇을 해야 하는지까지 알려준다. */
export function pageStatusLabel(status) {
  switch (status) {
    case PageStatus.TRAIN_LIST:
      return '조회 결과';
    case PageStatus.NO_TRAIN:
      return '조회 결과 0건';
    case PageStatus.LOGIN_REQUIRED:
      return '로그인 화면';
    case PageStatus.SESSION_EXPIRED:
      return '세션 만료';
    case PageStatus.BLOCKED:
      return '접속 차단 안내';
    default:
      return '조회 결과 화면이 아님';
  }
}
