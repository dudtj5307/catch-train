// Catch Train — service worker (MV3)
//
// 아직 골격만 있다. 여기에 들어갈 것은 안드로이드의 `watcher/WatchController` 에
// 해당하는 감시 루프(주기 관리·연속 실패 상한·알림)다.
//
// 규칙은 ../../CLAUDE.md 의 대원칙을 그대로 따른다. 특히:
//   대원칙 2 — 실패 경로에 자동 재시도를 넣지 않는다 (IP 차단은 실제로 일어났다)
//   대원칙 7 — 간격은 [min, max] 범위에서 사이클마다 무작위로 뽑는다
//
// `chrome.alarms` 는 최소 주기가 30초이고 값을 무작위로 흔들기 어렵다.
// 주기 관리를 어디서 할지는 아직 정하지 않았다 → extension/README.md "정해야 할 것"

chrome.runtime.onInstalled.addListener(() => {
  console.log('[CatchTrain] installed');
});
