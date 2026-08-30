// 지금 화면에 **열차 목록이 그려져 있는지**만 본다. 읽기만 한다.
// (android: webview/KtxParserScript.kt 의 `PAGE_KIND_TEMPLATE`)
//
// 판독([parse])보다 훨씬 가볍다. 갱신이 반영되었는지, 되돌아간 뒤 정말 목록으로
// 돌아왔는지 확인하는 데 쓴다 — 코레일은 SPA 라 뒤로 가기 한 칸이 조회 결과 화면에
// 대응한다는 보장이 없다. (§38-5, §38-8)

import * as dom from './dom.js';

export function pageKind() {
  const rows = dom.rows().length;
  return {
    list: rows > 0,
    rows,
    sig: dom.signature(),
    url: location.href,
    title: document.title || '',
  };
}
