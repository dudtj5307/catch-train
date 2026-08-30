// 사용자가 사이트에 넣어 둔 **조회 조건이 바뀌었는지**만 보는 서명. (PLAN.md §E-6-3 예외 17)
//
// **해시만 올려보낸다. 원문은 읽어서 버린다.**
// 조회 조건(구간·날짜·시각·인원)은 앱이 갖지 않는다 (대원칙 4). 두 곳에 조건이 있으면
// 어긋났을 때 원인을 찾을 수 없고, 자동 클릭이 붙으면 엉뚱한 칸을 잡는다.
// 여기서 필요한 것은 "그때와 같은 조회인가" 하나뿐이라 같은지 다른지만 알면 된다.
//
// 조건이 `localStorage` 에 있다는 것 자체가 실측이다 — URL 에는 아무것도 없다.
// 그래서 새로고침해도 사용자가 넣은 조건이 그대로 살아난다. (§38-9)
//
// 못 읽으면 `null` 이고, 그러면 감시 루프는 **비교하지 않는다.** 애매하면 막지 않는다.
// (대원칙 6)

import { QUERY_STORAGE_KEY } from './selectors.js';
import { hash } from './dom.js';

export function querySig() {
  let raw = null;
  try {
    raw = localStorage.getItem(QUERY_STORAGE_KEY);
  } catch {
    // 사이트가 저장소를 막아 둔 경우(시크릿 창 설정 등). 판정하지 않는다.
    raw = null;
  }
  if (!raw) return { sig: null, found: false };
  return { sig: `${raw.length}:${hash(raw)}`, found: true };
}
