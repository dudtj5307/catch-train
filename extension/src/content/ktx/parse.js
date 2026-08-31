// 조회 결과 화면 판독. (android: webview/KtxParserScript.kt 의 `SCRIPT_TEMPLATE`)
//
// `li.tckList` 하나가 편성 하나다. 헤더 행이 없으므로 열 위치를 찾을 일도 없다 (§38-1).
// 등급은 class 로 정하고, 매진 칸처럼 등급 class 가 붙지 않는 칸은 **위치로 보정한다** —
// `[0]`=일반실 `[1]`=특실. **SRT 와 순서가 반대다.** (§38-3)
//
// **읽기만 한다.** 누르지도, 요청을 내지도 않는다. 돌려주는 값의 모양은 안드로이드가
// WebView 에서 받던 JSON 과 같다 — 판정은 `domain/page-snapshot.js` 가 한다.

import * as KSEL from './selectors.js';
import * as dom from './dom.js';

export function parse() {
  const warnings = [];

  const list = dom.rows();
  const trains = [];
  list.forEach((row, index) => {
    try {
      const parsed = parseRow(row, index, warnings);
      if (parsed) trains.push(parsed);
    } catch (e) {
      warnings.push(`편성 분석 실패: ${(e && e.message) || 'unknown'}`);
    }
  });

  let bodyText = '';
  try {
    bodyText = dom.norm(document.body ? document.body.innerText || '' : '').slice(0, 3000);
  } catch {
    bodyText = '';
  }
  const contains = (list2) => list2.some((m) => m && bodyText.includes(dom.norm(m)));

  let status;
  // 차단 판정을 가장 먼저 본다. 목록이 남아 있어도 차단되었으면 멈춰야 한다.
  if (contains(KSEL.BLOCKED_MARKERS)) {
    status = 'BLOCKED';
  } else if (trains.length > 0) {
    status = 'TRAIN_LIST';
  } else if (looksLikeSessionExpired(bodyText)) {
    status = 'SESSION_EXPIRED';
  } else if (dom.exists(KSEL.LOGIN_MARKERS)) {
    status = 'LOGIN_REQUIRED';
  } else if (dom.exists(KSEL.TRAIN_LIST_MARKERS)) {
    // 목록 컨테이너는 있는데 편성이 없다. 조회 결과가 0건인 화면이다. (§38-5)
    status = 'NO_TRAIN';
  } else {
    // 대기열도 아직 그리는 중인 화면도 전부 여기로 온다.
    // **알아보려 들지 않는다** — 감시 루프가 제자리에서 다시 읽는다. (§39)
    status = 'UNKNOWN_PAGE';
  }

  return {
    status,
    url: location.href,
    title: document.title || '',
    rowCount: list.length,
    searchDate: searchDateOf(),
    trains,
    warnings,
  };
}

function parseRow(row, index, warnings) {
  const heading = parseHeading(dom.firstText(row, KSEL.ROUTE_HEADING));
  if (!heading.depTime || !heading.arrTime) {
    warnings.push(`시각을 읽지 못한 편성 ${index + 1}`);
    return null;
  }

  const cells = dom.seatCells(row);

  // 등급 → 칸 위치. **1단계 클릭도 같은 함수를 쓴다** (`dom.seatCellIndexes`).
  // 두 벌로 두면 "분석할 때 본 칸" 과 "누르는 칸" 이 갈라진다. (§38-3)
  const { general: gi, firstClass: fi } = dom.seatCellIndexes(cells);

  const general = gi >= 0 ? cells[gi] : null;
  const first = fi >= 0 ? cells[fi] : null;

  return {
    trainNumber: dom.firstText(row, KSEL.TRAIN_NUMBER),
    trainType: dom.firstText(row, KSEL.TRAIN_TYPE),
    departureStation: heading.dep,
    arrivalStation: heading.arr,
    departureTime: heading.depTime,
    arrivalTime: heading.arrTime,
    generalSeatText: general ? dom.text(general) : '',
    generalSeatClass: general ? dom.classOf(general) : '',
    generalSeatStatus: general ? dom.seatStatusOfCell(general) : 'UNKNOWN',
    generalCellIndex: gi,
    firstClassSeatText: first ? dom.text(first) : '',
    firstClassSeatClass: first ? dom.classOf(first) : '',
    firstClassSeatStatus: first ? dom.seatStatusOfCell(first) : 'UNKNOWN',
    firstClassCellIndex: fi,
    // 1·2단계에서 "이 편성이 그때 그 편성인지" 확인하는 데 쓴다.
    rowKey: dom.rowKey(row),
    rowIndex: index,
  };
}

/**
 * 편성 하나의 **식별자**(`{ trainNumber, departureTime }`).
 *
 * 목록이 갱신된 뒤에도 "그때 그 편성" 을 다시 찾는 데 쓴다 (`reserve.js`).
 * 판독([parseRow])과 **같은 규칙으로 읽어야** 한다 — 다르게 읽으면 자동 클릭이
 * 엉뚱한 편성을 잡는다. 주키는 열차 번호다. (§38-4)
 */
export function rowIdentity(row) {
  const heading = parseHeading(dom.firstText(row, KSEL.ROUTE_HEADING));
  return {
    trainNumber: dom.firstText(row, KSEL.TRAIN_NUMBER),
    departureTime: heading.depTime,
  };
}

function allTimes(value) {
  return (value || '').match(/([0-2]?\d):([0-5]\d)/g) || [];
}

/**
 * `동탄→김천구미(07:11~08:17)` 에서 역과 시각을 뽑는다.
 *
 * 정규식이 맞지 않으면 시각 두 개와 화살표 분해로 근사한다. 시각을 못 읽으면
 * 그 편성은 버린다 — 시각 없이는 사용자에게 보여줄 수도, 확인할 수도 없다.
 */
function parseHeading(value) {
  const out = { dep: '', arr: '', depTime: '', arrTime: '' };
  let m = null;
  try {
    m = new RegExp(KSEL.ROUTE_TIME_PATTERN).exec(value);
  } catch {
    m = null;
  }
  if (m) {
    out.dep = m[1];
    out.arr = m[2];
    out.depTime = m[3];
    out.arrTime = m[4];
    return out;
  }
  const times = allTimes(value);
  out.depTime = times[0] || '';
  out.arrTime = times[1] || '';
  const parts = (value || '').split('(')[0].split(/→|->|~>/);
  if (parts.length >= 2) {
    out.dep = parts[0];
    out.arr = parts[1];
  }
  return out;
}

function looksLikeSessionExpired(bodyText) {
  return /세션[^가-힣]{0,4}(만료|종료)/.test(bodyText) ||
    /다시로그인/.test(bodyText) ||
    /로그인후이용/.test(bodyText);
}

/**
 * 조회 조건의 출발일. 화면에 "무엇을 보고 있는지" 되비쳐 주기 위해서만 쓴다.
 *
 * [KSEL.SEARCH_DATE_FIELDS] 가 비어 있으면(= 아직 실측 전, §38-8) 빈 문자열을
 * 돌려주고 화면은 날짜 없이 구간만 보여준다. 감시 판정에는 쓰지 않는다.
 */
function searchDateOf() {
  for (const selector of KSEL.SEARCH_DATE_FIELDS) {
    let els = [];
    try {
      els = document.querySelectorAll(selector);
    } catch {
      continue;
    }
    for (const el of els) {
      const got = normDate(el.value || el.getAttribute('value') || el.innerText || el.textContent);
      if (got) return got;
    }
  }
  return '';
}

function normDate(raw) {
  const digits = (raw || '').replace(/[^0-9]/g, '').slice(0, 8);
  if (digits.length < 8) return '';
  const y = Number(digits.slice(0, 4));
  const mo = Number(digits.slice(4, 6));
  const da = Number(digits.slice(6, 8));
  if (y < 2000 || y > 2100 || mo < 1 || mo > 12 || da < 1 || da > 31) return '';
  return `${digits.slice(0, 4)}-${digits.slice(4, 6)}-${digits.slice(6, 8)}`;
}
