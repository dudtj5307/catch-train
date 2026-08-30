// content script 진입점. **DOM 을 읽어 주는 팔일 뿐이다.**
// 무엇을 언제 할지는 전부 service worker 가 정한다. (PLAN.md §E-1)
//
// **이 파일만 정적으로 주입된다.** 선언된 content script 는 ES 모듈이 아니라서
// import 를 쓸 수 없다. 그래서 여기서 한 번 `import()` 로 진짜 판독 모듈을 불러온다.
// (그 모듈들은 manifest 의 `web_accessible_resources` 에 있어야 한다)
//
// 리스너 등록은 **동기적으로** 한다. 모듈 로딩을 기다린 뒤에 등록하면 그 사이에 온
// 메시지를 통째로 놓치고, service worker 쪽에서는 "content script 가 없다" 로 보인다.
// 그것은 새로고침마다 정상적으로 겪는 상태라 오류로 세지 않지만(§E-6-1),
// 실제로는 여기 있었는데 못 받은 것이라면 감시가 헛돈다.
//
// **[SCROLL_TOP] 하나를 빼면 전부 읽기다.** 그마저도 스크롤 위치를 손대는 것이고,
// 사이트로 나가는 요청은 여기서 하나도 만들지 않는다.

const ready = (async () => {
  const load = (path) => import(chrome.runtime.getURL(path));
  const [parse, pageKind, login, query] = await Promise.all([
    load('src/content/ktx/parse.js'),
    load('src/content/ktx/page-kind.js'),
    load('src/content/ktx/login.js'),
    load('src/content/ktx/query.js'),
  ]);
  return {
    /** 조회 결과 화면 판독. 안드로이드의 `KtxParserScript.build()` 자리다 */
    PARSE: () => parse.parse(),
    /** 목록이 그려져 있는지만 보는 가벼운 판정 */
    PAGE_KIND: () => pageKind.pageKind(),
    /** 로그인 여부. 화면 종류와 다른 판정이다 (§38-7) */
    LOGIN: () => login.loginState(),
    /** 조회 조건의 **해시만**. 원문은 올려보내지 않는다 (§E-6-3 예외 17) */
    QUERY_SIG: () => query.querySig(),
    /** 새로고침이 화면을 맨 밑으로 튕기는 것을 막는 세 자리 중 둘 (§38-9) */
    SCROLL_TOP: () => scrollTop(),
  };
})();

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  const type = message && message.type;
  if (!type) return false;

  ready.then((handlers) => {
    const handler = handlers[type];
    if (!handler) {
      sendResponse({ ok: false, error: `알 수 없는 요청: ${type}` });
      return;
    }
    try {
      sendResponse({ ok: true, data: handler(message) });
    } catch (e) {
      // 판독이 깨져도 페이지에는 아무 일도 일어나지 않는다. 이유만 올려보낸다.
      sendResponse({ ok: false, error: (e && e.message) || 'DOM 판독 실패' });
    }
  }).catch((e) => {
    sendResponse({ ok: false, error: `판독 모듈을 불러오지 못했습니다: ${(e && e.message) || e}` });
  });

  return true; // 비동기 응답
});

/**
 * 스크롤 되살리기를 끄고 맨 위로. (§38-9)
 *
 * `content/early.js` 가 새 문서에서 같은 일을 하지만 그것만으로는 부족하다.
 * 새로고침 **직전**(나가는 이력 항목)과 **목록이 그려진 뒤**에도 한 번씩 걸어야 한다.
 * 한 자리라도 빼면 화면이 맨 밑으로 튄다.
 */
function scrollTop() {
  let restoration = '';
  try {
    if ('scrollRestoration' in history) {
      history.scrollRestoration = 'manual';
      restoration = 'manual';
    }
  } catch {
    restoration = '(설정 실패)';
  }
  const before = window.scrollY;
  try {
    window.scrollTo(0, 0);
  } catch {
    // 스크롤을 못 옮겨도 감시는 그대로 돈다.
  }
  return { restoration, before, after: window.scrollY };
}
