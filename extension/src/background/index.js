// service worker 진입점. 팝업과 content script 사이의 라우팅.
// (PLAN.md §E-1 / §E-3 — 감시 루프가 들어올 자리이기도 하다)
//
// **지금은 M1 이라 판독까지만 한다.** 새로고침도, 클릭도, 알림도 아직 없다.
// 사이트로 나가는 요청이 하나도 없는 상태다 — 그래서 대원칙 2 의 상한도 아직 필요 없다.
// 감시 루프(`watch-controller.js`)는 M2 에서 이 파일 옆에 붙는다.

import { snapshotFromRaw, DomParseError } from '../domain/page-snapshot.js';
import { selectionRetainOnly, selectionToggle } from '../domain/watch-selection.js';
import { isKorailUrl } from '../content/ktx/selectors.js';
import {
  clearSelection,
  clearSelectionForTab,
  loadSelection,
  saveSelection,
} from './store.js';

/** 팝업이 보내는 요청. content script 로 가는 이름(`PARSE` 등)과 섞이지 않게 나눠 둔다. */
const handlers = {
  READ_PAGE: readPage,
  TOGGLE_SEAT: toggleSeat,
  CLEAR_SELECTION: clearAll,
};

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  const handler = handlers[message && message.type];
  if (!handler) return false;
  handler(message)
    .then(sendResponse)
    .catch((e) => sendResponse({ ok: false, error: (e && e.message) || String(e) }));
  return true; // 비동기 응답
});

// 감시 대상이 사라지면 선택도 의미가 없다. (§E-6-3 예외 16)
chrome.tabs.onRemoved.addListener((tabId) => {
  clearSelectionForTab(tabId).catch(() => { /* 세션 저장소가 이미 비었을 수 있다 */ });
});

/**
 * 지금 보고 있는 코레일 탭을 판독해서 팝업에 넘긴다.
 *
 * **탭을 새로 열지도, 주소를 갈아 끼우지도 않는다.** 사용자가 직접 조회해 둔 화면을
 * 그대로 읽는 것이 전부다 (대원칙 4).
 */
async function readPage() {
  const tab = await activeTab();
  if (!tab || !isKorailUrl(tab.url)) {
    return { ok: false, reason: 'NOT_KORAIL' };
  }

  const response = await sendToContent(tab.id, { type: 'PARSE' });
  if (!response.ok) {
    return {
      ok: false,
      reason: response.reason ?? 'READ_FAILED',
      error: response.error,
      tabId: tab.id,
    };
  }

  let snapshot;
  try {
    snapshot = snapshotFromRaw(response.data);
  } catch (e) {
    if (e instanceof DomParseError) return { ok: false, reason: 'PARSE_FAILED', error: e.message };
    throw e;
  }

  // 화면에 없는 열차의 선택은 지운다. 눈에 보이지 않는 조건이 남으면
  // 감시가 영영 걸리지 않는다.
  const stored = await loadSelection(tab.id);
  const selection = selectionRetainOnly(stored, snapshot.trains);
  if (selection !== stored) await saveSelection(tab.id, selection);

  return { ok: true, tabId: tab.id, snapshot, selection };
}

async function toggleSeat(message) {
  const tab = await activeTab();
  if (!tab || !isKorailUrl(tab.url)) return { ok: false, reason: 'NOT_KORAIL' };

  const selection = selectionToggle(
    await loadSelection(tab.id),
    message.trainKey,
    message.seatClass,
  );
  await saveSelection(tab.id, selection);
  return { ok: true, selection };
}

async function clearAll() {
  await clearSelection();
  return { ok: true, selection: { seats: [] } };
}

/**
 * 팝업이 떠 있는 창의 활성 탭.
 *
 * `tabs` 권한을 넣지 않았으므로 `url` 은 **host permission 이 있는 탭에만** 채워진다.
 * 코레일이 아닌 탭이면 값이 비고, 그대로 `NOT_KORAIL` 이 된다. 그것으로 충분하다 —
 * 진단 문구 한 줄을 위해 모든 탭의 주소를 읽을 권한을 넓히지 않는다. (§E-6-1)
 */
async function activeTab() {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  return tab ?? null;
}

/**
 * content script 에 물어본다.
 *
 * **"연결할 수 없음" 은 오류가 아니다.** 문서를 다시 그리는 중이거나 아직 주입되기
 * 전이면 늘 이렇게 되고, 새로고침마다 몇 초씩 정상적으로 겪는 상태다.
 * M2 에서 이 값은 "기다린다" 로 이어진다 — 연속 실패로 세지 않는다. (§E-6-1)
 */
async function sendToContent(tabId, message) {
  try {
    const data = await chrome.tabs.sendMessage(tabId, message);
    if (!data) return { ok: false, reason: 'NO_CONTENT_SCRIPT' };
    return data;
  } catch (e) {
    return { ok: false, reason: 'NO_CONTENT_SCRIPT', error: (e && e.message) || String(e) };
  }
}
