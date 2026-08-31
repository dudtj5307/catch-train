// 판독 스크립트들이 공유하는 바탕 도구.
// (android: webview/KtxParserScript.kt 의 `SIGNATURE_JS` 블록)
//
// 목록을 찾고, 편성 하나를 요약하고, 좌석 칸을 집는 일은 **판독도 1·2단계 탐색도
// 똑같은 규칙으로** 해야 한다. 규칙이 갈라지면 "분석할 때 본 칸" 과 "누르는 칸" 이 달라진다.
//
// **이 모듈은 페이지를 건드리지 않는다.** 읽기만 하고, 아무것도 누르지 않는다.

import * as KSEL from './selectors.js';
import {
  seatStatusFromClassNames,
  seatClassFromClassNames,
  isSelectedCellClassNames,
} from '../../domain/seat-status.js';
import { SeatClass } from '../../domain/seat-class.js';

export function norm(s) {
  return (s || '').replace(/\s+/g, '');
}

export function text(el) {
  if (!el) return '';
  return norm(el.innerText || el.textContent || '');
}

/** selector 후보를 앞에서부터 시도해 처음 걸리는 요소. */
export function first(root, selectors) {
  if (!root) return null;
  for (const selector of selectors) {
    let el;
    try {
      el = root.querySelector(selector);
    } catch {
      continue;
    }
    if (el) return el;
  }
  return null;
}

export function firstText(root, selectors) {
  return text(first(root, selectors));
}

export function exists(selectors) {
  return first(document, selectors) !== null;
}

/** 목록이 그려진 영역. 못 찾으면 document 전체를 본다. */
export function scope() {
  return first(document, KSEL.SIGNATURE_SCOPES) || document.body || document;
}

/** 편성 목록(`li.tckList`). 없으면 빈 배열. */
export function rows() {
  for (const root of [scope(), document]) {
    if (!root) continue;
    for (const selector of KSEL.TRAIN_ROW) {
      let found;
      try {
        found = root.querySelectorAll(selector);
      } catch {
        continue;
      }
      if (found && found.length > 0) return Array.from(found);
    }
  }
  return [];
}

/** 한 편성의 좌석 칸. 실측에서는 언제나 2개고 `[0]`=일반실 `[1]`=특실 이다. (§38-3) */
export function seatCells(row) {
  for (const selector of KSEL.SEAT_CELL) {
    let found;
    try {
      found = row.querySelectorAll(selector);
    } catch {
      continue;
    }
    if (found && found.length > 0) return Array.from(found);
  }
  return [];
}

/**
 * 한 편성에서 **등급 → 좌석 칸 위치**. `{ general, firstClass }`, 없으면 -1.
 *
 * 등급은 class 가 1순위이고, 매진 칸에는 등급 class 가 붙지 않으므로 남은 자리를
 * 위치로 채운다 — `[0]`=일반실 `[1]`=특실. **SRT 와 반대다.** (§38-3)
 *
 * **판독(`parse.js`)과 1단계 클릭(`reserve.js`)이 같은 함수를 쓴다.** 두 벌로 두면
 * "분석할 때 본 칸" 과 "누르는 칸" 이 갈라지고, 그 순간 엉뚱한 칸을 누른다.
 */
export function seatCellIndexes(cells) {
  let general = -1;
  let firstClass = -1;
  cells.forEach((cell, i) => {
    const seatClass = seatClassFromClassNames(classOf(cell));
    if (general < 0 && seatClass === SeatClass.GENERAL) general = i;
    else if (firstClass < 0 && seatClass === SeatClass.FIRST_CLASS) firstClass = i;
  });
  if (general < 0 && KSEL.SEAT_CELL_INDEX_GENERAL < cells.length &&
    KSEL.SEAT_CELL_INDEX_GENERAL !== firstClass) {
    general = KSEL.SEAT_CELL_INDEX_GENERAL;
  }
  if (firstClass < 0 && KSEL.SEAT_CELL_INDEX_FIRST_CLASS < cells.length &&
    KSEL.SEAT_CELL_INDEX_FIRST_CLASS !== general) {
    firstClass = KSEL.SEAT_CELL_INDEX_FIRST_CLASS;
  }
  return { general, firstClass };
}

/**
 * 목록 전체에서 1단계로 골라진 칸 수.
 *
 * 하나여야 한다. **여러 칸이면 무엇을 고른 건지 확신할 수 없어 2단계로 가지 않는다.**
 * (§E-6-3 B: `SEAT_NOT_SELECTED`)
 */
export function activeCellCount() {
  let n = 0;
  for (const row of rows()) {
    for (const cell of seatCells(row)) {
      if (isSelectedCell(cell)) n++;
    }
  }
  return n;
}

export function classOf(el) {
  if (!el) return '';
  const cls = el.className;
  if (typeof cls === 'string') return cls;
  return (el.getAttribute && el.getAttribute('class')) || '';
}

export function tokens(el) {
  return classOf(el).split(/\s+/).filter((t) => t.length > 0);
}

/**
 * 좌석 칸의 상태. **판정 규칙은 `domain/seat-status.js` 한 곳에 있다.**
 *
 * 안드로이드는 JS(주입 문자열)와 Kotlin 에 같은 규칙을 두 벌 두어야 했다.
 * 확장은 양쪽이 다 JS 라 그럴 이유가 없다 — 규칙은 한 벌이고 여기서 부른다.
 */
export function seatStatusOfCell(cell) {
  return seatStatusFromClassNames(classOf(cell));
}

/** 1단계로 이 칸을 골라 둔 상태인가. 상태가 아니라 선택 표시다. (§38-6) */
export function isSelectedCell(cell) {
  return isSelectedCellClassNames(classOf(cell));
}

// --- 예매 2단계 바 (§38-6) ------------------------------------------------
//
// **읽기만 한다.** 누르는 것은 `reserve.js` 이고, 그쪽도 여기서 찾은 것을 누른다 —
// 진단(`probe.js`)이 "이 버튼이 있다" 고 말한 것과 감시가 누르는 것이 같아야 한다.

/** 1단계로 좌석 칸을 고르면 화면 최하단에 나타나는 바. 없으면 null. */
export function reserveBar() {
  return first(document, KSEL.RESERVE_BAR);
}

/** 바에 표시되는 **1단계에서 고른 등급** 문구. (§38-6-1) */
export function reserveBarLabel(bar) {
  return bar ? firstText(bar, KSEL.RESERVE_BAR_SEAT_LABEL) : '';
}

/**
 * 바 안의 버튼들. `{ el, label, disabled }`.
 *
 * 표현용 class 는 상태마다 바뀌므로 `reservbtn` 만 쓴다. **문구로 고르는 것은
 * 부르는 쪽의 몫이다** — 여기서는 무엇이 놓여 있는지만 알려준다.
 */
export function reserveBarButtons(bar) {
  if (!bar) return [];
  for (const selector of KSEL.RESERVE_BUTTON) {
    let found;
    try {
      found = bar.querySelectorAll(selector);
    } catch {
      continue;
    }
    if (!found || found.length === 0) continue;
    const out = [];
    for (const el of found) {
      out.push({
        el,
        label: text(el),
        disabled: el.disabled === true ||
          tokens(el).includes(KSEL.RESERVE_BUTTON_DISABLED_CLASS),
      });
    }
    if (out.length > 0) return out;
  }
  return [];
}

export function hash(value) {
  let h = 5381;
  for (let i = 0; i < value.length; i++) {
    h = ((h * 33) ^ value.charCodeAt(i)) & 0x7fffffff;
  }
  return h.toString(36);
}

/**
 * 편성 하나의 "지금 내용" 을 짧은 문자열로 요약한다.
 *
 * 판독할 때 읽은 편성과 누를 때의 편성이 정말 같은지 확인하는 데 쓴다.
 * 위치(index)만 믿으면 목록이 갱신된 순간 엉뚱한 열차를 잡는다.
 *
 * **`active` 는 요약에서 뺀다.** 1단계로 칸을 고르면 그 class 가 붙는데,
 * 그것 때문에 요약값이 바뀌면 2단계에서 같은 편성을 못 찾는다.
 */
export function rowKey(row) {
  const parts = [
    firstText(row, KSEL.TRAIN_NUMBER),
    firstText(row, KSEL.TRAIN_TYPE),
    firstText(row, KSEL.ROUTE_HEADING),
  ];
  const cells = seatCells(row);
  for (const cell of cells) {
    const kept = tokens(cell).filter((t) => t !== KSEL.SeatCellClass.active);
    parts.push(`${kept.join('.')}=${text(cell).slice(0, 24)}`);
  }
  return `${cells.length}:${hash(parts.join('|'))}`;
}

/** 목록 전체의 요약. 갱신이 실제로 반영되었는지 판단하는 데만 쓴다. (§38-5) */
export function signature() {
  const list = rows();
  if (list.length === 0) return 'no-list';
  return `${list.length}:${hash(list.map(rowKey).join('#'))}`;
}
