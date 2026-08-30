// 감시 상태 보관. (PLAN.md §E-3-2)
//
// **`chrome.storage.session` 에만 둔다.** 디스크에 남지 않고 브라우저를 닫으면 사라진다 —
// "이 선택은 저장하지 않는다"(대원칙 4)와 어긋나지 않으면서, service worker 가 죽었다
// 살아나도 이어갈 수 있다.
//
// MV3 의 service worker 는 30초 놀면 죽는다. 감시 중에는 사실상 놀지 않지만
// (사이클마다, 대기 중에는 500ms 마다 content script 와 메시지가 오간다),
// **죽는 것을 전제로 만든다.** 여기 있는 값이 그 전제의 전부다.
//
// 상태는 service worker 만 갖는다. content script 가 읽을 일이 없으므로
// `setAccessLevel` 도 필요 없다. (§E-8)

import { EMPTY_SELECTION, normalizeSelection } from '../domain/watch-selection.js';
import { normalizeConfig } from './watch-config.js';

const SELECTION_KEY = 'selection';
const WATCH_KEY = 'watch';
const LOG_KEY = 'log';

/**
 * 선택은 **탭 하나에 대해서만** 들고 있다.
 *
 * 체크한 칸은 "그 탭의 그 조회 결과 화면" 에만 의미가 있고(대원칙 4),
 * 감시도 전역으로 하나만 돈다(§E-6-3 예외 23). 다른 탭을 보면 이전 선택은 버린다.
 */
export async function loadSelection(tabId) {
  const stored = await chrome.storage.session.get(SELECTION_KEY);
  const record = stored[SELECTION_KEY];
  if (!record || record.tabId !== tabId) return EMPTY_SELECTION;
  return normalizeSelection(record);
}

export async function saveSelection(tabId, selection) {
  await chrome.storage.session.set({
    [SELECTION_KEY]: { tabId, seats: selection.seats },
  });
}

export async function clearSelection() {
  await chrome.storage.session.remove(SELECTION_KEY);
}

/** 그 탭의 선택만 지운다. 다른 탭 것이면 그대로 둔다. */
export async function clearSelectionForTab(tabId) {
  const stored = await chrome.storage.session.get(SELECTION_KEY);
  const record = stored[SELECTION_KEY];
  if (record && record.tabId === tabId) await clearSelection();
}

/**
 * 지금 감시 중인 것. **service worker 가 죽었다 살아났을 때 이어가기 위한 값이다.**
 *
 * `running` 이 true 인데 메모리에 루프가 없으면 SW 가 죽었던 것이다.
 * `alarms` 그물이 깨워서 이 값을 보고 이어간다. (§E-3-2)
 */
export async function loadWatch() {
  const stored = await chrome.storage.session.get(WATCH_KEY);
  const record = stored[WATCH_KEY];
  if (!record || typeof record.tabId !== 'number') return null;
  return {
    tabId: record.tabId,
    running: record.running === true,
    config: normalizeConfig(record.config),
    status: record.status ?? null,
  };
}

export async function saveWatch(record) {
  await chrome.storage.session.set({ [WATCH_KEY]: record });
}

export async function clearWatch() {
  await chrome.storage.session.remove(WATCH_KEY);
}

/**
 * 로그. 팝업이 [로그] 창에서 읽고, SW 가 죽었다 살아나도 앞부분이 남아 있게 한다.
 *
 * **한 줄마다 쓰지 않는다.** 사이클마다 열 줄 남짓이 나오고 간격이 0.1초일 수 있어,
 * 줄마다 저장하면 storage 를 초당 수십 번 두들긴다. 부르는 쪽(`index.js`)이 모아서 쓴다.
 */
export async function loadLog() {
  const stored = await chrome.storage.session.get(LOG_KEY);
  const entries = stored[LOG_KEY];
  return Array.isArray(entries) ? entries : [];
}

export async function saveLog(entries) {
  await chrome.storage.session.set({ [LOG_KEY]: entries });
}

export async function clearLog() {
  await chrome.storage.session.remove(LOG_KEY);
}
