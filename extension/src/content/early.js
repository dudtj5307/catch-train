// 새 문서를 받자마자 도는 것. **여기서 하는 일은 하나뿐이다.**
// (android: KtxWebViewHost 의 onPageFinished 스크롤 복구 차단, §38-9)
//
// 새로고침은 스크롤을 되살려서 화면을 맨 밑으로 튕긴다. 되살릴 때의 문서에는 아직
// 목록이 없어 짧고, 예전 오프셋이 문서 끝에 잘려 붙기 때문이다.
//
// 그래서 **세 자리**에서 막는다 —
//   1. 새로고침 직전 (나가는 이력 항목)   ← background/page-host.js 의 SCROLL_TOP
//   2. 새 문서를 받은 직후               ← **이 파일**
//   3. 목록이 그려진 뒤                  ← background/page-host.js 의 SCROLL_TOP
// 한 자리라도 빼면 다시 튄다.
//
// `document_start` 라 아직 `document.body` 도 없다. import 도 쓸 수 없다 (선언된
// content script 는 ES 모듈이 아니다). 이 파일이 짧아야 하는 이유다.

try {
  if ('scrollRestoration' in history) history.scrollRestoration = 'manual';
} catch {
  // 되살리기를 끄지 못해도 페이지는 멀쩡히 돈다. 화면이 튈 뿐이다.
}
