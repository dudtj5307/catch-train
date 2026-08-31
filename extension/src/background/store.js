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
//
// **예외가 하나 있다 — 설정은 `storage.local` 이다.** 감시 간격은 감시 상태가 아니라
// 사용자가 고른 **설정**이라, 브라우저를 닫았다 열면 되돌아가면 안 된다. 대원칙 4 가
// 저장을 금지한 것은 **조회 조건과 체크한 칸**이지 설정이 아니다 (안드로이드도 설정에
// 저장한다). 여기 들어가는 것은 숫자 둘뿐이고, 조회 조건은 여전히 아무 데도 저장하지
// 않는다.

import { EMPTY_SELECTION, normalizeSelection } from '../domain/watch-selection.js';
import { normalizeConfig } from './watch-config.js';
import { clampRange, DEFAULT_MAX_INTERVAL_MS, DEFAULT_MIN_INTERVAL_MS } from './scheduler.js';

const SELECTION_KEY = 'selection';
const WATCH_KEY = 'watch';
const LOG_KEY = 'log';
const SETTINGS_KEY = 'settings';

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
    /**
     * ★ **[예매] 를 누르는 중이었나.** (§E-3-2 3번)
     *
     * 이 값이 남아 있다는 것은 되돌릴 수 없는 클릭 도중에 service worker 가 죽었다는
     * 뜻이다. **눌렀는지 아닌지 우리는 모른다.** 부활은 감시를 이어가지 않고
     * 그 자리에서 인계로 확정한다 — 모르는 채로 한 번 더 누르는 것이 가장 나쁘다.
     */
    reserving: record.reserving ?? null,
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

/**
 * 사용자가 고른 설정. **`storage.local` 이라 브라우저를 닫아도 남는다.**
 *
 * 저장에 실패하거나 값이 이상하면 기본값으로 떨어진다 — 설정을 못 읽었다고 감시가
 * 시작되지 않는 편이 더 나쁘다 (대원칙 6).
 *
 * **예매를 누를지 말지는 여기 없다.** 그것은 설정이 아니라 이 도구가 하는 일이다
 * (`watch-config.js` 머리말).
 */
export async function loadSettings() {
  let record = null;
  try {
    const stored = await chrome.storage.local.get(SETTINGS_KEY);
    record = stored[SETTINGS_KEY];
  } catch {
    // 저장소를 못 읽었다. 기본값으로 간다.
  }
  const [minIntervalMs, maxIntervalMs] = clampRange(
    record && Number.isFinite(record.minIntervalMs) ? record.minIntervalMs : DEFAULT_MIN_INTERVAL_MS,
    record && Number.isFinite(record.maxIntervalMs) ? record.maxIntervalMs : DEFAULT_MAX_INTERVAL_MS,
  );
  return { minIntervalMs, maxIntervalMs };
}

/** 넘기지 않은 항목은 **저장된 값 그대로 둔다.** 하나를 만졌다고 나머지가 되돌아가면 안 된다. */
export async function saveSettings(patch) {
  const current = await loadSettings();
  const next = { ...current, ...patch };
  const [lo, hi] = clampRange(next.minIntervalMs, next.maxIntervalMs);
  const settings = { minIntervalMs: lo, maxIntervalMs: hi };
  await chrome.storage.local.set({ [SETTINGS_KEY]: settings });
  return settings;
}
