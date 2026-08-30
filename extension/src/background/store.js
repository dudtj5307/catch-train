// 감시 상태 보관. (PLAN.md §E-3-2)
//
// **`chrome.storage.session` 에만 둔다.** 디스크에 남지 않고 브라우저를 닫으면 사라진다 —
// "이 선택은 저장하지 않는다"(대원칙 4)와 어긋나지 않으면서, service worker 가 죽었다
// 살아나도 이어갈 수 있다.
//
// 상태는 service worker 만 갖는다. content script 가 읽을 일이 없으므로
// `setAccessLevel` 도 필요 없다. (§E-8)

import { EMPTY_SELECTION, normalizeSelection } from '../domain/watch-selection.js';

const SELECTION_KEY = 'selection';

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
