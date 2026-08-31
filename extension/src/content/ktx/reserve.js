// ★ **감시가 페이지를 누르는 유일한 코드.** 1단계(좌석 칸) · 2단계([예매]). (§38-6)
//
// 코레일 예매는 두 번 누른다. 좌석 칸을 골라 `active` 를 붙이고(1단계), 화면 하단에
// 나타난 예매 바에서 [예매] 를 누른다(2단계). **두 단계는 성질이 다르다** —
// 1단계는 아직 아무것도 잡지 않은 되돌릴 수 있는 동작이고, 2단계는 좌석을 잡는다.
//
// ## 이 파일이 지키는 것
//
//  1. **누르고 나면 반드시 확인한다.** 확장의 클릭은 `isTrusted=false` 라 사이트가
//     무시할 수 있다 (PLAN.md §E-2-1). 그래서 "눌렀다" 를 "됐다" 로 읽지 않는다 —
//     `active` 가 그 칸에 붙었는지 눈으로 확인하고, 아니면 **사람에게 넘긴다.**
//  2. **그 자리에서 다시 누르지 않는다.** 실패했을 때 다른 방법으로 한 번 더 누르는 것은
//     대원칙 2 가 금지하는 재시도다. 폴백의 방향은 인계 하나뿐이다. (§E-2-3)
//  3. **[예매] 는 완전일치 허용목록으로만 고른다.** 같은 자리에 `예약대기신청` 이나
//     `입석+좌석 예매` 가 온다. 그건 사용자가 고른 것이 아니다. (§38-6-1, 대원칙 3)
//  4. **2단계를 누른 뒤에는 기다리지 않고 곧바로 돌려준다.** [예매] 가 화면을 넘기면
//     content script 가 그 자리에서 죽고 응답이 사라진다 — 눌렀는지 아닌지 모르는 채로
//     남는 것이 가장 나쁜 결과다. 결과 관찰은 새로고침을 견디는 service worker 가
//     [reserveOutcome] 을 되풀이해 읽어서 한다. (PLAN.md §E-3-2)
//
// **결제는 건드리지 않는다.** [예매] 를 누르면 이 도구의 역할은 끝난다. (대원칙 3)

import * as KSEL from './selectors.js';
import * as dom from './dom.js';
import * as tap from './tap.js';
import { rowIdentity } from './parse.js';
import { SeatStatus } from '../../domain/seat-status.js';
import { SeatClass, seatClassLabel } from '../../domain/seat-class.js';
import { trainKey, trainKeyMatches } from '../../domain/train-key.js';

/** 안내 문구를 로그에 남길 때의 상한. 한 줄이 로그를 통째로 밀어내면 안 된다. */
const MAX_NOTICE_CHARS = 200;

// ------------------------------------------------------------------ 1단계

/**
 * **1단계 — 좌석 칸을 고른다.** 되돌릴 수 있는 동작이다.
 *
 * 이미 골라져 있으면 **누르지 않는다.** 그 칸을 한 번 더 누르면 선택이 풀릴 수 있고,
 * 우리가 원하는 상태(골라진 상태)에 이미 있는데 건드릴 이유가 없다.
 *
 * @return `{ result, detail, ... }` — `result` 는 안드로이드 `ReserveResult` 와 같은 이름이다.
 *   `SELECTED` / `ROW_NOT_FOUND` / `CELL_NOT_FOUND` / `SEAT_NOT_SELECTED` / `FAILED`
 */
export async function selectSeat({
  trainNumber = '',
  departureTime = '',
  seatClass = SeatClass.GENERAL,
  settleMs = tap.SETTLE_MS,
} = {}) {
  const label = seatClassLabel(seatClass);
  const found = findRow(trainNumber, departureTime);
  if (!found) {
    return fail('ROW_NOT_FOUND', `화면에서 ${trainNumber || departureTime} 편성을 다시 찾지 못했습니다`);
  }

  const { row, rowIndex } = found;
  const cells = dom.seatCells(row);
  const indexes = dom.seatCellIndexes(cells);
  const cellIndex = seatClass === SeatClass.FIRST_CLASS ? indexes.firstClass : indexes.general;
  if (cellIndex < 0 || cellIndex >= cells.length) {
    return fail('CELL_NOT_FOUND', `${label} 칸의 위치를 읽지 못했습니다`);
  }

  const cell = cells[cellIndex];
  const status = dom.seatStatusOfCell(cell);
  // 판정할 때는 열려 있었지만 그새 바뀌었을 수 있다. 자동 클릭은 되돌리기 어려우므로
  // 여기서 한 번 더 본다. 그새 매진이면 **누르지 않는다.** (§E-6-2 의 3번)
  if (status !== SeatStatus.AVAILABLE) {
    return fail('CELL_NOT_FOUND', `${label} 칸이 그새 ${status} 로 바뀌었습니다`);
  }

  const before = readSelectionState(cell);

  // 이미 골라져 있고 그 칸 하나뿐이면 그대로 2단계로 간다.
  if (before.cellActive && before.activeCount === 1) {
    return {
      result: 'SELECTED',
      detail: `${label} 이미 골라져 있음`,
      clicked: false,
      rowIndex,
      cellIndex,
      before,
      after: before,
      bar: readBarState(),
    };
  }

  const anchor = dom.first(cell, KSEL.SEAT_CELL_ANCHOR) || cell;
  tap.scrollIntoViewSafe(anchor);
  const hit = tap.hitTest(anchor);
  const arm = tap.armConfirm(anchor);
  const { error: clickError } = tap.synthClick(anchor);

  await tap.wait(settleMs);
  arm.stop();

  const after = readSelectionState(cell);
  const bar = readBarState();
  const common = {
    clicked: true,
    rowIndex,
    cellIndex,
    anchor: tap.describe(anchor),
    usedCellItself: anchor === cell,
    hit,
    event: arm.state,
    before,
    after,
    bar,
  };

  if (clickError) return { ...common, result: 'FAILED', detail: `click() 예외: ${clickError}` };

  // **여기서부터가 "눌렀다" 와 "됐다" 를 가르는 자리다.**
  if (!arm.state.fired) {
    return {
      ...common,
      result: 'SEAT_NOT_SELECTED',
      detail: `클릭이 페이지까지 가지 않았습니다 (${tap.describe(anchor)} ${hit.why || 'hit ok'})`,
    };
  }
  if (!after.cellActive) {
    return {
      ...common,
      result: 'SEAT_NOT_SELECTED',
      detail: `눌렀지만 ${label} 칸에 선택 표시가 붙지 않았습니다 ` +
        `(trusted=${arm.state.trusted} active ${before.activeCount}→${after.activeCount})`,
    };
  }
  if (after.activeCount !== 1) {
    // 무엇을 고른 건지 확신할 수 없으면 2단계로 가지 않는다. (§E-6-3 B)
    return {
      ...common,
      result: 'SEAT_NOT_SELECTED',
      detail: `선택 표시가 ${after.activeCount} 칸에 붙었습니다`,
    };
  }

  return { ...common, result: 'SELECTED', detail: `${label} 선택됨 ${barText(bar)}` };
}

// ------------------------------------------------------------------ 2단계

/**
 * **2단계 — 하단 바의 [예매].** 되돌릴 수 없는 동작이다.
 *
 * 누르기 전에 확인하는 것 셋. 하나라도 어긋나면 **누르지 않고 인계한다.**
 *
 *  1. 1단계 선택이 아직 살아 있는가 (`active` 가 정확히 한 칸)
 *  2. 하단 바에 적힌 등급이 우리가 고른 등급인가 (§38-6-1)
 *  3. 버튼 문구가 허용목록과 **완전일치**하는가 (대원칙 3)
 *
 * 누른 뒤에는 **기다리지 않는다.** 결과 관찰은 [reserveOutcome] 을 되풀이해 읽는
 * service worker 의 몫이다 — 화면이 넘어가면 이 스크립트는 그 자리에서 죽는다.
 *
 * @return `CLICKED`(눌렀다, 결과는 아직 모른다) / `MISMATCH` / `NOT_ALLOWED` /
 *   `BUTTON_NOT_FOUND` / `SEAT_NOT_SELECTED` / `FAILED`
 */
export function confirmReserve({ seatClass = SeatClass.GENERAL } = {}) {
  const label = seatClassLabel(seatClass);
  const bar = dom.reserveBar();
  const state = readBarState(bar);

  if (!bar) return fail('BUTTON_NOT_FOUND', '하단 예매 바가 화면에 없습니다', { bar: state });

  // 1) 1단계 선택이 아직 살아 있는가.
  const activeCount = dom.activeCellCount();
  if (activeCount !== 1) {
    return fail('SEAT_NOT_SELECTED', `선택 표시가 ${activeCount} 칸입니다`, { bar: state });
  }

  // 2) 바에 적힌 등급. **읽히지 않으면 막지 않는다** (대원칙 6) — 다르게 읽혔을 때만 막는다.
  if (state.label && !state.label.includes(dom.norm(label))) {
    return fail('MISMATCH', `하단 바가 "${state.label}" 인데 고른 것은 ${label} 입니다`, { bar: state });
  }

  // 3) 버튼. **완전일치 허용목록.** 부분일치로 고르면 `입석+좌석 예매` 가 걸린다.
  const buttons = dom.reserveBarButtons(bar);
  if (buttons.length === 0) {
    return fail('BUTTON_NOT_FOUND', '예매 바에 버튼이 없습니다', { bar: state });
  }
  const allowed = buttons.find((b) => isAllowedButton(b));
  if (!allowed) {
    return fail(
      'NOT_ALLOWED',
      `누를 수 있는 [예매] 버튼이 없습니다 (있는 것: ${state.buttons.join(' / ') || '없음'})`,
      { bar: state },
    );
  }

  const hit = tap.hitTest(allowed.el);
  const arm = tap.armConfirm(allowed.el);
  const { error: clickError } = tap.synthClick(allowed.el);
  // `fired` 는 클릭 중에 동기로 채워진다. 여기서 곧바로 훅을 떼도 안전하다.
  arm.stop();

  const common = {
    clicked: true,
    button: allowed.label,
    anchor: tap.describe(allowed.el),
    hit,
    event: arm.state,
    bar: state,
    // 관찰의 기준선. service worker 가 이 값과 대조해 화면이 바뀌었는지 본다.
    baseline: pageBaseline(),
  };

  if (clickError) return { ...common, result: 'FAILED', detail: `click() 예외: ${clickError}` };
  if (!arm.state.fired) {
    return {
      ...common,
      result: 'NOT_ALLOWED',
      detail: `[${allowed.label}] 클릭이 페이지까지 가지 않았습니다`,
    };
  }
  return { ...common, result: 'CLICKED', detail: `[${allowed.label}] 눌렀습니다` };
}

/** 허용목록 **완전일치** + 금지목록 + 비활성 검사. 셋 다 통과해야 누른다. (§38-6-1) */
function isAllowedButton(button) {
  if (button.disabled) return false;
  const label = dom.norm(button.label);
  if (!label) return false;
  if (KSEL.RESERVE_TEXT_EXCLUDE.some((t) => label === dom.norm(t))) return false;
  return KSEL.RESERVE_TEXTS_EXACT.some((t) => label === dom.norm(t));
}

// ------------------------------------------------------------------ 2단계 이후 관찰

/**
 * **[예매] 를 누른 뒤 화면이 어떻게 되었나.** service worker 가 되풀이해 읽는다.
 *
 * 판정하지 않고 **사실만** 돌려준다. 성공/실패를 가르는 규칙은 감시 루프에 있고,
 * 그래야 규칙을 고칠 때 페이지 안에 들어가지 않아도 된다.
 *
 * `notice` 는 **안내 문구만** 담는다 (모달이 있으면 모달, 없고 목록도 없으면 본문 머리).
 * 본문을 통째로 올려보내지 않는 이유는 조회 조건이 거기 섞여 있기 때문이다 (대원칙 4).
 * 코레일의 실제 "잔여석없음" 문구는 아직 실측 전이라(§38-8), **이 값이 그 실측이다.**
 */
export function reserveOutcome() {
  const base = pageBaseline();
  const notice = noticeText();
  const haystack = dom.norm(`${notice} ${bodyHead()}`);
  return {
    ...base,
    notice: notice.slice(0, MAX_NOTICE_CHARS),
    failed: KSEL.RESERVE_FAILED_MARKERS.some((m) => haystack.includes(dom.norm(m))),
    blocked: KSEL.BLOCKED_MARKERS.some((m) => haystack.includes(dom.norm(m))),
  };
}

/** 화면이 바뀌었는지 대조할 최소한의 값. 조회 조건이 섞이지 않는 것들만. */
function pageBaseline() {
  return {
    url: stripQuery(location.href),
    rows: dom.rows().length,
    bar: dom.reserveBar() !== null,
    modal: dom.first(document, KSEL.PAGE_MODAL) !== null,
    activeCount: dom.activeCellCount(),
  };
}

/** 화면을 덮은 안내. 모달이 1순위이고, 없으면 목록이 사라진 화면의 머리글이다. */
function noticeText() {
  const modal = dom.first(document, KSEL.PAGE_MODAL);
  if (modal) return trimText(modal.innerText || modal.textContent || '');
  if (dom.rows().length > 0) return '';
  return bodyHead();
}

function bodyHead() {
  try {
    return trimText(document.body ? document.body.innerText || '' : '');
  } catch {
    return '';
  }
}

function trimText(raw) {
  return String(raw).replace(/\s+/g, ' ').trim().slice(0, MAX_NOTICE_CHARS);
}

// ------------------------------------------------------------------ 잡동사니

/**
 * 감시가 판독한 그 편성을 화면에서 다시 찾는다.
 *
 * **위치(index)로 찾지 않는다.** 목록은 새로고침마다 다시 그려지고 순서가 바뀔 수 있다.
 * 주키는 열차 번호이고, 번호를 못 읽었을 때만 시각으로 떨어진다 — 판정과 **같은 규칙**을
 * 쓰려고 `domain/train-key.js` 를 그대로 부른다. (§38-4)
 */
function findRow(trainNumber, departureTime) {
  const want = trainKey(trainNumber, departureTime);
  const list = dom.rows();
  for (let i = 0; i < list.length; i++) {
    const row = list[i];
    const identity = rowIdentity(row);
    if (trainKeyMatches(want, trainKey(identity.trainNumber, identity.departureTime))) {
      return { row, rowIndex: i };
    }
  }
  return null;
}

function readSelectionState(cell) {
  return {
    cellClass: dom.classOf(cell).trim(),
    cellActive: dom.isSelectedCell(cell),
    activeCount: dom.activeCellCount(),
  };
}

function readBarState(bar = dom.reserveBar()) {
  const rect = tap.rectOf(bar);
  return {
    present: bar !== null,
    visible: rect !== null && rect.height > 0,
    label: dom.reserveBarLabel(bar),
    buttons: dom.reserveBarButtons(bar)
      .filter((b) => b.label)
      .map((b) => (b.disabled ? `${b.label}(비활성)` : b.label)),
  };
}

function barText(bar) {
  if (!bar.present) return '하단바 없음';
  const buttons = bar.buttons.length > 0 ? ` [${bar.buttons.join(' / ')}]` : '';
  return `하단바 ${bar.visible ? '보임' : 'DOM만'} "${bar.label}"${buttons}`;
}

function fail(result, detail, extra = {}) {
  return { result, detail, clicked: false, ...extra };
}

/** 쿼리스트링과 해시를 버린다. 조회 조건이 로그에 남으면 안 된다. (대원칙 4) */
function stripQuery(url) {
  const cut = String(url).split(/[?#]/)[0];
  return cut.length > 120 ? `${cut.slice(0, 120)}…` : cut;
}
