// 지금 사이트에 **로그인되어 있는지**만 본다. (android: webview/KtxLoginScript.kt)
//
// ## 왜 따로 필요한가 (§27-1, §38-7)
//
// 코레일은 **비로그인 상태에서도 열차 조회가 되고 좌석 선택까지 된다.** 로그인을
// 요구하는 시점은 예매를 누른 **뒤**다. 그래서 `parse.js` 의 `status` 만으로는 부족하다 —
// 그쪽의 `LOGIN_REQUIRED` 는 "지금 화면이 로그인 화면인가" 라서, 비로그인 사용자가
// 조회 결과 화면에 있으면 멀쩡히 `TRAIN_LIST` 가 나온다. 그대로 감시하면:
//
//   좌석이 열림 → 알림 → 예매 클릭 → 로그인 화면으로 튕김 → 좌석은 남이 가져감
//
// 즉 **취소표가 나오는 그 순간에** 실패한다. 몇 시간 기다린 보람이 통째로 날아간다.
//
// ## 보이는지는 따지지 않는다
//
// **DOM 에 있는가만** 본다. 폰 폭에서 머리말이 통째로 `display:none` 이라, 가시성으로
// 거르면 언제나 `UNKNOWN` 이 되어 확인이 통째로 죽는다. PC 폭에서는 실제로 보이지만
// 판정 규칙을 폭에 따라 나누면 두 벌이 되고 반드시 어긋난다. (§38-7)
//
// ## 쓰면 안 되는 것
//
//  - `button.logoutBtn` : 클래스 이름이 고정이고 문구만 바뀐다 → 항상 로그인으로 읽는다
//  - 본문에서 "로그아웃" 찾기 : 로그인 화면 안내문에 그 단어가 있다
//
// 판정하지 못하면 `UNKNOWN` 이고, 그때는 **막지 않는다.** (대원칙 6)
//
// **읽기만 한다.** 요청이 나가지 않는다.

import { LoginIndicator } from './selectors.js';

export function loginState() {
  const head = collect(LoginIndicator.HEADER_SCOPES);
  const scopeName = head ? head.name : 'body';
  const scopes = head ? head.nodes : [document.body].filter(Boolean);

  // 문구 판정에만 함께 보는 모바일 전체메뉴 영역.
  const menu = collect(LoginIndicator.MENU_SCOPES);
  const textScopes = menu ? scopes.concat(menu.nodes) : scopes;

  let how = 'link';
  let scanned = 0;
  let logout = findLink(scopes, LoginIndicator.LOGOUT_LINK);
  let login = findLink(scopes, LoginIndicator.LOGIN_LINK);
  if (!logout && !login) {
    // 2순위: 문구 **완전일치**. 부분일치를 쓰면 "간편로그인 설정" 같은 메뉴에 걸린다.
    how = 'text';
    const byLogout = findByText(textScopes, LoginIndicator.LOGOUT_TEXTS);
    const byLogin = findByText(textScopes, LoginIndicator.LOGIN_TEXTS);
    logout = byLogout.el;
    login = byLogin.el;
    scanned = byLogout.scanned + byLogin.scanned;
  }

  let state;
  let detail;
  if (logout && !login) {
    state = 'LOGGED_IN';
    detail = `로그아웃 ${describe(logout)}`;
  } else if (login && !logout) {
    state = 'LOGGED_OUT';
    detail = `로그인 ${describe(login)}`;
  } else if (login && logout) {
    // 둘 다 있으면 우리가 읽는 방식이 이 화면에 맞지 않는다는 뜻이다. 막지 않는다.
    state = 'UNKNOWN';
    detail = '로그인/로그아웃 둘 다 있음';
  } else {
    state = 'UNKNOWN';
    detail = '표시 없음';
  }

  const suffix = how === 'text'
    ? `${menu ? `+${menu.name}` : ''} 요소${scanned}개`
    : '';
  return {
    state,
    detail: `${detail} / ${how} scope=${scopeName}${suffix}`,
    url: location.href,
  };
}

/** selector 목록 중 **처음으로 걸리는** 것의 요소들. 보이는지는 보지 않는다. */
function collect(selectors) {
  for (const selector of selectors) {
    let nodes;
    try {
      nodes = document.querySelectorAll(selector);
    } catch {
      continue;
    }
    if (nodes.length > 0) return { name: selector, nodes: Array.from(nodes) };
  }
  return null;
}

/** 1순위: 상태와 1:1 인 링크. (§38-7) */
function findLink(scopes, selectors) {
  for (const scope of scopes) {
    for (const selector of selectors) {
      let nodes;
      try {
        nodes = scope.querySelectorAll(selector);
      } catch {
        continue;
      }
      if (nodes.length > 0) return nodes[0];
    }
  }
  return null;
}

/**
 * 2순위: 문구 완전일치. 링크가 둘 다 빗나갔을 때만 쓴다.
 * 머리말과 모바일 전체메뉴를 함께 본다 — 전체메뉴 버튼은 **문구가** 상태를 따라 바뀐다.
 */
function findByText(scopes, texts) {
  let scanned = 0;
  for (const scope of scopes) {
    let nodes;
    try {
      nodes = scope.querySelectorAll('a, button, input[type=submit], input[type=button]');
    } catch {
      continue;
    }
    for (const node of nodes) {
      scanned++;
      if (textHits(node, texts)) return { el: node, scanned };
    }
  }
  return { el: null, scanned };
}

function squash(text) {
  return (text || '').replace(/\s+/g, '').toLowerCase();
}

function textOf(el) {
  let text = '';
  try {
    text = el.innerText || el.textContent || '';
  } catch {
    text = '';
  }
  if (!text && el.value) text = el.value;
  return squash(text);
}

function textHits(el, texts) {
  const value = textOf(el);
  if (!value) return false;
  return texts.some((t) => value === squash(t));
}

/**
 * 화면에 실제로 그려져 있는가. **판정에는 쓰지 않는다** — 로그에 남길 detail 을
 * 꾸미는 데만 쓴다. 왜 안 쓰는지는 파일 머리말에 있다. (§38-7)
 */
function isShown(el) {
  if (!el) return false;
  try {
    if (el.getClientRects().length === 0 && !el.offsetParent) return false;
    const style = window.getComputedStyle(el);
    if (style && (style.visibility === 'hidden' || style.display === 'none')) return false;
  } catch {
    return true; // 계산 실패는 보이는 것으로 본다
  }
  return true;
}

function describe(el) {
  const text = textOf(el);
  let href = '';
  try {
    href = (el.getAttribute('href') || '').slice(-40);
  } catch {
    href = '';
  }
  return `${text || '(문구없음)'}${href ? ` href=${href}` : ''}${isShown(el) ? '' : ' (숨김)'}`;
}
