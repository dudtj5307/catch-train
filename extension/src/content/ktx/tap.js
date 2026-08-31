// 페이지를 **실제로 누르는** 일의 바탕. (android: `KtxWebViewHost` 의 MotionEvent 자리)
//
// 이 저장소에서 페이지를 누르는 코드는 둘뿐이다 — 진단(`probe.js`)과 감시(`reserve.js`).
// **둘은 같은 방법으로 눌러야 한다.** 진단이 "통한다" 고 말한 방법과 감시가 실제로 쓰는
// 방법이 다르면 진단은 아무것도 보장하지 않는다. 그래서 누르는 동작·확인 훅·좌표 검사를
// 여기 한 벌만 둔다.
//
// ## 확장에는 MotionEvent 가 없다
//
// 합성 클릭(`el.click()`)은 `isTrusted = false` 다. 안드로이드가 진짜 터치로 넘었던 벽이
// 확장에는 그대로 남아 있고, 넘을 수단은 `chrome.debugger` 하나뿐인데 상시 배너와
// 매크로 감지(-8002, §38-10) 위험을 함께 가져온다. (PLAN.md §E-2-1)
//
// 그래서 이 확장은 **합성으로 누르고, 통했는지 눈으로 확인한다.**
// 안 통했으면 **그 자리에서 다른 방법으로 또 누르지 않는다** — 폴백의 방향은
// "다른 방법으로 한 번 더" 가 아니라 "사람에게 넘김" 하나뿐이다. (§E-2-3, 대원칙 2)
//
// **이 파일은 아무것도 판정하지 않는다.** 눌렀다/닿았다는 사실만 돌려주고,
// 그것이 성공인지는 부르는 쪽이 정한다.

import * as dom from './dom.js';

/**
 * 누른 뒤 화면이 반응할 때까지 주는 기본 시간.
 *
 * React 는 이벤트를 받아도 그 자리에서 다시 그리지 않고, 하단 바는 나타나면서
 * 애니메이션이 붙는다. 너무 짧으면 **통했는데 안 통한 것으로** 읽힌다.
 */
export const SETTLE_MS = 900;

/**
 * 합성 클릭 한 번. **한 번뿐이다** — 실패해도 여기서 다시 누르지 않는다.
 *
 * `scrollIntoView` 를 먼저 하는 것은 좌표 검사([hitTest]) 때문이지 클릭 때문이 아니다.
 * 합성 클릭 자체는 좌표를 쓰지 않아 화면 밖 요소도 눌린다.
 *
 * @return `{ error }` — 빈 문자열이면 예외 없이 눌렀다는 뜻이다. (통했다는 뜻은 아니다)
 */
export function synthClick(el) {
  try {
    el.click();
    return { error: '' };
  } catch (e) {
    return { error: (e && e.message) || String(e) };
  }
}

/** 화면 안으로 올린다. 못 올려도 계속한다 — [hitTest] 가 `OFF_SCREEN` 으로 남을 뿐이다. */
export function scrollIntoViewSafe(el) {
  try {
    el.scrollIntoView({ block: 'center' });
  } catch {
    // 오래된 렌더러이거나 요소가 방금 떨어졌다. 클릭은 그대로 해 본다.
  }
}

/**
 * 클릭이 페이지까지 닿았는지 확인할 준비. (android: `KtxParserScript.ktxArmConfirm`)
 *
 * 캡처 단계에서 **읽기만** 하고 막지 않는다. 페이지 동작은 그대로 흘러간다.
 * `trusted` 가 `false` 로 나오는 것은 **정상이다** — 그 벽이 있다는 것은 이미 알고 있고,
 * 알고 싶은 것은 "사이트가 그것을 보느냐" 이다.
 */
export function armConfirm(el) {
  const state = { fired: false, trusted: false, onTarget: false, prevented: false, tag: '' };
  const onClick = (e) => {
    state.fired = true;
    state.trusted = e.isTrusted === true;
    state.onTarget = e.target === el || el.contains(e.target);
    state.tag = describe(e.target);
    // `defaultPrevented` 는 페이지 핸들러가 돈 **뒤에** 읽어야 의미가 있다.
    // 캡처 단계에서 그 자리에 읽으면 언제나 false 다.
    setTimeout(() => { state.prevented = e.defaultPrevented === true; }, 0);
  };
  let attached = false;
  try {
    document.addEventListener('click', onClick, true);
    attached = true;
  } catch {
    // 훅을 못 걸어도 클릭 자체는 해 본다. 결과에 `fired=false` 로 남는다.
  }
  return {
    state,
    stop() {
      if (!attached) return;
      try {
        document.removeEventListener('click', onClick, true);
      } catch {
        // 이미 떨어졌다.
      }
    },
  };
}

/**
 * 가운데 점이 정말 이 요소로 떨어지는가. (android: `ktxHitAt`)
 *
 * **합성 클릭의 성패 판정에 섞지 말 것.** 합성 클릭은 좌표를 쓰지 않으므로 `hit` 이
 * 나빠도 이벤트는 멀쩡히 전달된다. 섞으면 통한 클릭을 "닿지 않았다" 로 읽는다.
 * 이 값이 판정에 필요한 것은 좌표로 누르는 디버거 드라이버(M-b) 쪽이다.
 */
export function hitTest(el) {
  const rect = rectOf(el);
  if (!rect || rect.width < 1 || rect.height < 1) {
    return { ok: false, why: 'ZERO_RECT', rect: rectText(rect), covered: '' };
  }
  const x = rect.left + rect.width / 2;
  const y = rect.top + rect.height / 2;
  if (x < 0 || y < 0 || x > window.innerWidth || y > window.innerHeight) {
    return { ok: false, why: 'OFF_SCREEN', rect: rectText(rect), covered: '' };
  }
  let at = null;
  try {
    at = document.elementFromPoint(x, y);
  } catch {
    return { ok: false, why: 'HIT_FAILED', rect: rectText(rect), covered: '' };
  }
  if (at && (at === el || el.contains(at) || at.contains(el))) {
    return { ok: true, why: '', rect: rectText(rect), covered: '' };
  }
  return { ok: false, why: 'COVERED', rect: rectText(rect), covered: describe(at) };
}

export function rectOf(el) {
  if (!el || !el.getBoundingClientRect) return null;
  try {
    return el.getBoundingClientRect();
  } catch {
    return null;
  }
}

export function rectText(rect) {
  if (!rect) return '';
  const r = (n) => Math.round(n);
  return `[${r(rect.left)},${r(rect.top)},${r(rect.width)},${r(rect.height)}]`;
}

/** 로그에 남길 요소 이름. `div.price_box.gen` 정도. */
export function describe(el) {
  if (!el || !el.tagName) return '(없음)';
  const cls = dom.classOf(el).trim().split(/\s+/).filter(Boolean).slice(0, 3).join('.');
  const tag = el.tagName.toLowerCase();
  return cls ? `${tag}.${cls}` : tag;
}

export function wait(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
