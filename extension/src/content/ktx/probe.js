// ★ 클릭 실측(M-a) 전용. **감시 경로에서는 한 줄도 쓰이지 않는다.** (PLAN.md §E-2-4)
//
// 답해야 하는 질문은 하나다 — **합성 클릭(`el.click()`)이 이 사이트에 통하는가.**
// content script 의 이벤트는 `isTrusted = false` 라 안드로이드가 `MotionEvent` 로
// 넘었던 벽이 확장에는 그대로 남아 있고, 넘을 수단은 `chrome.debugger` 하나뿐인데
// 그것은 상시 배너 + 매크로 감지(§38-10) 위험을 함께 가져온다. 그래서
// **드라이버 기본값을 추측으로 정하지 않고 여기서 한 번 재고 시작한다.** (§E-2-2)
//
// 이 파일은 이 저장소에서 **페이지를 실제로 누르는 유일한 코드**다. 그래서 제약이 셋:
//
//  1. **사용자가 팝업에서 두 번 눌러야만** 돈다. 감시 루프는 이것을 부르지 않는다.
//  2. **한 번에 한 칸.** 실패해도 다른 방법으로 다시 누르지 않는다 — 그것이 대원칙 2 가
//     금지하는 재시도이고, 첫 클릭이 서버에 닿았는지 우리는 모른다. (§E-2-3)
//  3. **예매하지 않는다.** 누르는 것은 좌석 칸(1단계)뿐이고, 하단 바의 [예매](2단계)는
//     건드리지 않는다. 되돌릴 수 있는 자리까지만 간다. (대원칙 3)
//
// 요청이 나갔는지도 여기서 같이 답한다. `PerformanceObserver` 로 진단하는 동안 실제로
// 나간 요청을 세면 **devtools 를 열지 않고** 알 수 있다 — §38-10 의 개발자도구 감지를
// 건드리지 않는 것이 중요하다.
//
// **URL 은 쿼리스트링을 잘라서 남긴다.** 조회 조건은 앱이 갖지 않는다 (대원칙 4).

import * as KSEL from './selectors.js';
import * as dom from './dom.js';
import * as tap from './tap.js';
import { SeatStatus } from '../../domain/seat-status.js';
import { SeatClass, seatClassLabel } from '../../domain/seat-class.js';

/** 결과에 담을 요청 개수 상한. 진단 한 줄이 로그를 통째로 밀어내면 안 된다. */
const MAX_REQUESTS = 12;

/**
 * M-a 를 한 번 수행한다.
 *
 * 순서: 대상 고르기 → 전 상태 읽기 → 훅 걸기 → `click()` → 기다림 → 후 상태 읽기.
 * 어느 단계든 대상을 확신할 수 없으면 **누르지 않고** 이유만 돌려준다. (§E-6 원칙 2)
 */
export async function clickProbe({ settleMs = tap.SETTLE_MS } = {}) {
  const target = pickTarget();
  if (!target.ok) return { ...target, ran: false };

  const { row, cell, seatClass, anchor } = target;
  const before = readState(cell);

  // **먼저 화면 안으로 올린다.** 아래 [hitTest] 는 뷰포트 좌표로 재기 때문에, 목록을
  // 스크롤해 둔 상태에서 그냥 재면 멀쩡한 칸도 `OFF_SCREEN` 으로 읽힌다.
  // (진단이 스크롤을 건드리는 것은 여기 한 번뿐이고, 감시 경로와 무관하다)
  tap.scrollIntoViewSafe(anchor);

  // **진짜 포인터라면 이 요소에 닿는가.** 합성 클릭 자체는 좌표를 쓰지 않으므로
  // 아래 판정과는 별개의 질문이고, 답이 필요한 것은 **디버거 드라이버(M-b) 쪽**이다 —
  // `Input.dispatchMouseEvent` 는 좌표로 누른다. 그래서 이 값은 판정에 섞지 않는다.
  // (§38-9 의 [열차조회] 함정: selector 로 찾아진다 ≠ 누를 수 있다)
  const hit = tap.hitTest(anchor);

  const requests = watchRequests();
  const arm = tap.armConfirm(anchor);

  const { error: clickError } = tap.synthClick(anchor);

  await tap.wait(settleMs);

  arm.stop();
  const after = readState(cell);

  return {
    ran: true,
    ok: true,
    settleMs,
    target: {
      trainNumber: dom.firstText(row, KSEL.TRAIN_NUMBER) || '(번호 미상)',
      heading: dom.firstText(row, KSEL.ROUTE_HEADING) || '',
      seatClass,
      seatClassLabel: seatClassLabel(seatClass),
      anchor: tap.describe(anchor),
      usedCellItself: anchor === cell,
    },
    hit,
    clickError,
    event: arm.state,
    before,
    after,
    requests: requests.stop(),
    verdict: verdictOf({ arm: arm.state, before, after, clickError }),
  };
}

// ------------------------------------------------------------------ 대상 고르기

/**
 * 누를 칸 하나. **`AVAILABLE` 인 칸만** 고른다.
 *
 * 매진 칸을 눌러 봐야 아무 일도 일어나지 않는 것이 정상이라, "통하지 않았다" 와
 * "통했는데 원래 아무 일도 없는 칸이었다" 를 구분할 수 없다. (§38-8)
 *
 * 사용자가 체크해 둔 칸을 쓰지 않는 이유도 같다 — 그 칸은 매진인 것이 정상이고
 * (지금 매진인 좌석이 풀리기를 기다리는 것이 이 도구의 목적이다), 그러면 실측이
 * 통째로 무의미해진다. 대신 **어느 칸을 눌렀는지 결과에 적어 준다.**
 */
function pickTarget() {
  const rows = dom.rows();
  if (rows.length === 0) {
    return { ok: false, reason: 'NO_LIST', detail: '조회 결과 목록이 화면에 없습니다.' };
  }

  for (const row of rows) {
    const cells = dom.seatCells(row);
    for (let i = 0; i < cells.length; i++) {
      const cell = cells[i];
      if (dom.seatStatusOfCell(cell) !== SeatStatus.AVAILABLE) continue;
      // 이미 골라 둔 칸은 건너뛴다. 눌러 봐야 `active` 가 원래 붙어 있어서
      // **붙은 것인지 남아 있던 것인지** 갈리지 않는다. (§38-6)
      if (dom.isSelectedCell(cell)) continue;
      return {
        ok: true,
        row,
        cell,
        // 등급은 위치가 정한다. `[0]`=일반실 `[1]`=특실. **SRT 와 반대다.** (§38-3)
        seatClass: i === KSEL.SEAT_CELL_INDEX_FIRST_CLASS
          ? SeatClass.FIRST_CLASS
          : SeatClass.GENERAL,
        anchor: dom.first(cell, KSEL.SEAT_CELL_ANCHOR) || cell,
      };
    }
  }

  return {
    ok: false,
    reason: 'NO_AVAILABLE_CELL',
    detail: '예약 가능하고 아직 고르지 않은 칸이 없습니다. 좌석이 있는 조회 결과에서 해 주세요.',
  };
}

// ------------------------------------------------------------------ 전/후 읽기

/** 누르기 전후로 똑같이 읽는 것들. 두 벌을 대조해야 "무엇이 바뀌었나" 가 나온다. */
function readState(cell) {
  const bar = dom.reserveBar();
  const barRect = tap.rectOf(bar);
  return {
    cellClass: dom.classOf(cell).trim(),
    /** 이 칸이 1단계로 골라졌는가. **M-a 의 정답지가 이 값이다.** (§38-6-1) */
    cellActive: dom.isSelectedCell(cell),
    /** 목록 전체에서 `active` 가 붙은 칸 수. 엉뚱한 칸이 골라졌는지 본다 */
    activeCount: dom.activeCellCount(),
    /** 2단계 바. DOM 에 있는지와 실제로 보이는지는 다른 질문이다 (§38-9 의 0×0 함정) */
    bar: bar !== null,
    barVisible: barRect !== null && barRect.height > 0,
    barLabel: dom.reserveBarLabel(bar),
    barButtons: buttonTexts(bar),
  };
}

/**
 * 2단계 바에 실제로 놓인 버튼 문구. **누르지 않는다 — 읽기만 한다.**
 *
 * `예약대기신청` 이나 `입석+좌석 예매` 가 같은 자리에 온다는 것이 §38-6-1 의 실측인데,
 * 코레일에서 그 자리에 정확히 무엇이 오는지는 아직 못 봤다 (§38-8). 여기서 같이 재 둔다.
 */
function buttonTexts(bar) {
  return dom.reserveBarButtons(bar)
    .filter((b) => b.label)
    .map((b) => (b.disabled ? `${b.label}(비활성)` : b.label));
}

// ------------------------------------------------------------------ 훅

/**
 * 진단하는 동안 실제로 나간 요청. **devtools 없이** 답하려고 이렇게 한다 (§38-10).
 *
 * `buffered:false` 라 이 창 안에 새로 생긴 것만 본다. **쿼리스트링은 자른다** —
 * 조회 조건이 거기 실려 있을 수 있고, 조회 조건은 앱이 갖지 않는다 (대원칙 4).
 */
function watchRequests() {
  const seen = [];
  let observer = null;

  const take = (entries) => {
    for (const entry of entries) {
      if (seen.length >= MAX_REQUESTS) return;
      seen.push({ url: stripQuery(entry.name), how: entry.initiatorType || '?' });
    }
  };

  try {
    observer = new PerformanceObserver((list) => take(list.getEntries()));
    observer.observe({ type: 'resource', buffered: false });
  } catch {
    observer = null;
  }

  return {
    stop() {
      if (!observer) return { supported: false, entries: [] };
      try {
        take(observer.takeRecords()); // disconnect 는 대기 중인 것을 버린다
        observer.disconnect();
      } catch {
        // 이미 끊겼다. 모은 것만 돌려준다.
      }
      return { supported: true, entries: seen };
    },
  };
}

// ------------------------------------------------------------------ 판정

/**
 * 다섯 중 무엇이었나. **화면에서는 전부 똑같아 보인다** — 역 진단과 같은 구조다 (§38-10).
 *
 *  - `CLICK_THREW`   : `click()` 이 예외를 냈다. 사이트가 아니라 우리 쪽 문제다
 *  - `NOT_DELIVERED` : 이벤트가 페이지의 리스너까지 가지도 않았다. selector 문제다
 *  - `SYNTHETIC_OK`  : 합성 클릭으로 그 칸이 골라졌다. **디버거가 필요 없다** (가장 좋은 결말)
 *  - `WRONG_CELL`    : 무언가 골라지긴 했는데 우리가 노린 칸이 아니다
 *  - `NO_EFFECT`     : 닿았지만 아무 일도 없었다. 사이트가 `isTrusted` 를 보는 쪽에 가깝다
 *
 * **`hit` 은 여기 들어오지 않는다.** 합성 클릭은 좌표를 쓰지 않으므로 `hit` 이 나빠도
 * 이벤트는 멀쩡히 전달된다. 섞으면 통한 클릭을 "닿지 않았다" 로 읽는다 —
 * 실제로 그렇게 잘못 읽는 것을 확인하고 뺐다. `hit` 은 M-b(디버거) 판단에만 쓴다.
 */
function verdictOf({ arm, before, after, clickError }) {
  if (clickError) return 'CLICK_THREW';
  if (!arm.fired) return 'NOT_DELIVERED';
  if (after.cellActive && !before.cellActive) return 'SYNTHETIC_OK';
  if (after.activeCount !== before.activeCount) return 'WRONG_CELL';
  return 'NO_EFFECT';
}

// ------------------------------------------------------------------ 잡동사니

/** 쿼리스트링과 해시를 버린다. 조회 조건이 로그에 남으면 안 된다. (대원칙 4) */
function stripQuery(url) {
  const cut = String(url).split(/[?#]/)[0];
  return cut.length > 120 ? `${cut.slice(0, 120)}…` : cut;
}
