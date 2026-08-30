// Catch Train — content script
//
// 아직 골격만 있다. 여기에 들어갈 것은 안드로이드의 `webview/KtxParserScript` 에
// 해당하는 DOM 판독이다. selector 는 이 파일에 직접 쓰지 말고
// ../../shared/ 의 단일 출처에서 가져온다 (shared/README.md 참조).
//
// **이 스크립트는 페이지를 건드리지 않는다.** 요청을 보내지도, 무엇을 누르지도 않는다.
// 감시가 붙기 전까지는 그대로 둘 것 — 실제 사이트에 대고 반복 실험하지 않는다 (대원칙 2).

console.log('[CatchTrain] content script ready:', location.pathname);
