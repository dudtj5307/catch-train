// service worker 진입점. **결정은 여기(와 `watch-controller.js`)에만 있다.**
// content script 는 DOM 을 읽어 주는 팔일 뿐이다. (PLAN.md §E-1)
//
// 이 파일이 하는 일은 넷이다.
//   1. 팝업 ↔ 감시 엔진 라우팅
//   2. 탭 수명 (닫히면 감시 중지 — §E-6-3 예외 16)
//   3. `chrome.alarms` **부활 그물** (주 타이머가 아니다 — §E-3-2)
//   4. 상태·로그를 `chrome.storage.session` 에 흘려 두기 (SW 가 죽어도 이어가려고)
//
// **M2 부터 사이트로 요청이 나간다.** 감시가 내보내는 것은 새로고침 하나뿐이고, 그
// 자리는 `page-host.js` 의 `requery` 한 곳이다. 대원칙 2 를 어기는 코드는 거기서만 쓸 수 있다.
//
// 감시 밖에 하나 더 있다 — [clickProbe](M-a 실측). **사용자가 팝업에서 두 번 눌러야만**
// 돌고, 감시 중에는 아예 거절한다. 확장이 페이지를 누르는 것은 지금 그 자리뿐이다.

import { snapshotFromRaw, DomParseError } from '../domain/page-snapshot.js';
import { selectionRetainOnly, selectionToggle, selectionIsEmpty } from '../domain/watch-selection.js';
import { isKorailUrl, LOGIN_URL } from '../content/ktx/selectors.js';
import { LogCode, WatchLogger } from './logger.js';
import { ChromeNotifier } from './notifier.js';
import { TabPageHost } from './page-host.js';
import { WatchController } from './watch-controller.js';
import { DEFAULT_WATCH_CONFIG, normalizeConfig } from './watch-config.js';
import { INITIAL_STATUS, WatchState } from './watch-state.js';
import {
  clearLog,
  clearSelection,
  clearSelectionForTab,
  clearWatch,
  loadLog,
  loadSelection,
  loadSettings,
  loadWatch,
  saveLog,
  saveSelection,
  saveSettings,
  saveWatch,
} from './store.js';

/** 부활 그물의 주기. **주 타이머가 아니다** — 최소 30초라 감시 주기가 될 수 없다. (§E-3-1) */
const REVIVE_ALARM = 'catch-train-revive';
const REVIVE_PERIOD_MINUTES = 0.5;

/** 상태·로그를 저장소에 쓰는 최소 간격. 대기 중에는 250ms 마다 상태가 바뀐다. */
const PERSIST_DEBOUNCE_MS = 1_000;

const logger = new WatchLogger({ onLog: () => schedulePersist() });

const notifier = new ChromeNotifier({
  onError: (message) => logger.log(LogCode.NOTIFICATION_SKIPPED, `알림 실패: ${message}`),
  onOpenTab: () => focusWatchedTab(),
});
notifier.bindClicks();

const controller = new WatchController({
  notifier,
  logger,
  onStatus: () => schedulePersist(),
  // 조회 조건이 바뀌면 체크해 둔 칸은 다른 화면의 것이다. 버린다. (§E-6-3 예외 17)
  onSelectionInvalid: () => { clearSelection().catch(() => {}); },
  // **[예매] 를 누르기 직전/직후.** 이 사이에 SW 가 죽으면 부활이 또 누르면 안 된다.
  onReserving: (mark) => markReserving(mark),
});

/** 팝업이 보내는 요청. content script 로 가는 이름(`PARSE` 등)과 섞이지 않게 나눠 둔다. */
const handlers = {
  READ_PAGE: readPage,
  TOGGLE_SEAT: toggleSeat,
  CLEAR_SELECTION: clearAll,
  START_WATCH: startWatch,
  STOP_WATCH: stopWatch,
  GET_STATUS: getStatus,
  OPEN_LOGIN: openLogin,
  CLEAR_LOG: clearLogEntries,
  CLICK_PROBE: clickProbe,
  GET_SETTINGS: getSettings,
  SET_INTERVAL: updateInterval,
};

/**
 * 이 빌드가 어디까지 하는가. **버전 번호만으로는 부족하다** — 개발 중에는 버전을
 * 올리지 않은 채 코드만 바뀌는 일이 흔하고, 정작 알고 싶은 것은 "이 빌드가 예매를
 * 누르는 코드를 갖고 있는가" 이기 때문이다. 마일스톤이 올라가면 여기도 같이 올린다.
 */
const BUILD_MARK = 'M4 (좌석 선택 + [예매])';

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  const type = message && message.type;
  const handler = handlers[type];
  // **모르는 요청에 침묵하지 않는다.** `return false` 로 두면 응답 채널이 그냥 닫히고
  // 팝업의 `sendMessage` 가 `undefined` 로 풀린다. 팝업에서는 그것이 "눌러도 아무 반응
  // 없음" 으로만 보인다 — 잠긴 버튼에 이유를 붙이는 것과 같은 이유로 여기도 말을 해야 한다.
  //
  // 이 자리에 실제로 오는 것은 거의 하나뿐이다: **낡은 service worker 가 새 팝업의
  // 요청을 받은 경우.** 팝업 HTML/JS 는 열 때마다 디스크에서 새로 읽히지만 service
  // worker 와 content script 는 확장을 다시 불러와야 갱신되기 때문에, 개발 중에는
  // "팝업에는 새 버튼이 있는데 그 버튼이 부르는 핸들러는 없는" 상태가 흔하다.
  if (!handler) {
    sendResponse({ ok: false, reason: 'UNKNOWN_TYPE', error: `알 수 없는 요청: ${type}` });
    return false;
  }
  handler(message)
    .then(sendResponse)
    .catch((e) => sendResponse({ ok: false, error: (e && e.message) || String(e) }));
  return true; // 비동기 응답
});

// 감시 대상이 사라지면 감시도 선택도 의미가 없다. (§E-6-3 예외 16)
chrome.tabs.onRemoved.addListener((tabId) => {
  if (controller.isWatching && controller.tabId === tabId) {
    logger.log(LogCode.TAB_GONE, `탭 #${tabId} 닫힘`);
    controller.stop('감시하던 코레일 탭이 닫혔습니다.');
    notifier.notifyWatchStopped(
      '감시하던 탭이 닫혔습니다',
      '코레일 탭이 닫혀 감시를 멈췄습니다. 다시 조회한 뒤 감시를 시작하세요.',
    );
    persistNow();
  }
  clearSelectionForTab(tabId).catch(() => { /* 세션 저장소가 이미 비었을 수 있다 */ });
});

/**
 * **부활 그물.** (§E-3-2)
 *
 * 위험 구간은 새로고침 직후 content script 가 아직 없는 몇 초다. 그때 SW 가 죽으면
 * 감시가 조용히 사라진다 — 사용자는 계속 감시 중인 줄 안다.
 *
 * 깨어나서 "감시 중이라고 저장돼 있는데 루프가 없네" 면 이어간다.
 * **M2 에는 클릭이 없어서 이어가는 것이 안전하다.** M3·M4 에서 `RESERVING` 상태가
 * 생기면 그때는 이어가지 않고 곧바로 인계로 확정해야 한다 — 눌렀는지 모르는 채로
 * 또 누르는 것이 가장 나쁜 결과다.
 */
chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name !== REVIVE_ALARM) return;
  reviveIfNeeded().catch(() => {});
});

// 확장을 새로 로드/업데이트하면 감시는 사라진다. 조용히 사라지면 사용자는 감시
// 중인 줄 안다. (§E-6-3 예외 19)
chrome.runtime.onInstalled.addListener(() => { announceLostWatch().catch(() => {}); });
chrome.runtime.onStartup.addListener(() => { announceLostWatch().catch(() => {}); });

// ------------------------------------------------------------------ 판독 (M1)

/**
 * 지금 보고 있는 코레일 탭을 판독해서 팝업에 넘긴다.
 *
 * **탭을 새로 열지도, 주소를 갈아 끼우지도 않는다.** 사용자가 직접 조회해 둔 화면을
 * 그대로 읽는 것이 전부다 (대원칙 4).
 */
async function readPage() {
  const tab = await targetTab();
  if (!tab) return { ok: false, reason: 'NOT_KORAIL' };

  const host = new TabPageHost(tab.id);
  const response = await host.send({ type: 'PARSE' });
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
  if (controller.isWatching && controller.tabId === tab.id) controller.updateSelection(selection);

  const login = await host.login();
  return { ok: true, tabId: tab.id, snapshot, selection, login };
}

async function toggleSeat(message) {
  const tab = await targetTab();
  if (!tab) return { ok: false, reason: 'NOT_KORAIL' };

  const selection = selectionToggle(
    await loadSelection(tab.id),
    message.trainKey,
    message.seatClass,
  );
  await saveSelection(tab.id, selection);
  // 감시 중이면 재시작 없이 다음 사이클부터 반영된다. 재시작하면 요청이 한 번 더 나간다.
  if (controller.isWatching && controller.tabId === tab.id) controller.updateSelection(selection);
  return { ok: true, selection };
}

async function clearAll() {
  await clearSelection();
  if (controller.isWatching) controller.updateSelection({ seats: [] });
  return { ok: true, selection: { seats: [] } };
}

// ------------------------------------------------------------------ 감시 (M2)

/**
 * 감시를 시작한다. **여기서부터 사이트로 요청이 나간다.** (대원칙 2)
 *
 * 시작 전에 막는 것 셋:
 *  - 감시는 **전역으로 하나뿐**이다. 두 탭이면 요청이 두 배다. (§E-6-3 예외 23)
 *  - 체크한 칸이 하나도 없으면 볼 것이 없다.
 *  - **비로그인이 확실하면 시작하지 않는다.** 좌석이 열린 그 순간에 튕기는 것보다 낫다.
 *    (§27-1) 대신 팝업이 [로그인 화면 열기] 를 띄운다 — 확장이 스스로 주소를 갈아
 *    끼우지는 않는다. (§E-6-5)
 */
async function startWatch(message) {
  // ★ **로그가 스스로 어느 빌드인지 말하게 한다.** 맨 앞에 둔다 — 탭을 잘못 골랐거나
  // 로그인이 아니어서 되돌아가는 경로에서도 이 줄은 남아야 한다.
  logger.log(LogCode.BUILD, `v${chrome.runtime.getManifest().version} ${BUILD_MARK}`);
  const tab = await targetTab();
  if (!tab) return { ok: false, reason: 'NOT_KORAIL' };

  if (controller.isWatching && controller.tabId !== tab.id) {
    return { ok: false, reason: 'ANOTHER_TAB', tabId: controller.tabId };
  }

  const selection = await loadSelection(tab.id);
  if (selectionIsEmpty(selection)) return { ok: false, reason: 'NO_SELECTION' };

  const host = new TabPageHost(tab.id);
  const login = await host.login();
  logger.log(LogCode.LOGIN_STATE, `${login.state} ${login.detail || ''}`.trim());
  if (login.state === 'LOGGED_OUT') {
    return { ok: false, reason: 'LOGGED_OUT', detail: login.detail };
  }

  // 간격은 **저장된 설정**이 정한다. 팝업이 보낸 값이 있으면 그것이 우선이다.
  const settings = await loadSettings();
  const config = normalizeConfig({
    ...DEFAULT_WATCH_CONFIG,
    ...settings,
    ...(message.config ?? {}),
  });
  // 간격과 분당 요청 수는 `WATCH_START` 한 줄에 같이 남는다 (watch-controller.js).
  controller.start({ host, selection, config });

  await saveWatch({
    tabId: tab.id, running: true, config, status: controller.status, reserving: null,
  });
  await chrome.alarms.create(REVIVE_ALARM, { periodInMinutes: REVIVE_PERIOD_MINUTES });

  // 루프가 끝나면(발견·오류·중지) 그물을 걷는다. 감시하지 않는데 깨어날 이유가 없다.
  controller.finished().then(() => { afterLoopEnded().catch(() => {}); });

  return { ok: true, status: controller.status };
}

async function stopWatch() {
  controller.stop('사용자가 감시를 멈췄습니다.');
  notifier.cancelAll();
  await afterLoopEnded();
  return { ok: true, status: controller.status };
}

async function getStatus() {
  const status = controller.isWatching || controller.status.state !== WatchState.IDLE
    ? controller.status
    : (await loadWatch())?.status ?? { ...INITIAL_STATUS };
  return {
    ok: true,
    status,
    watching: controller.isWatching,
    log: logger.entries(),
  };
}

/**
 * 로그인 화면을 **새 탭으로** 연다. (§E-6-5)
 *
 * 사용자의 탭은 사용자의 것이다. 보고 있던 탭의 주소를 갈아 끼우면 직접 넣어 둔
 * 조회 조건이 통째로 날아간다 (대원칙 4·5). 세션은 탭 사이에 공유되므로 새 탭에서
 * 로그인하면 원래 탭도 로그인 상태가 된다.
 */
async function openLogin() {
  const tab = await chrome.tabs.create({ url: LOGIN_URL, active: true });
  return { ok: true, tabId: tab.id };
}

// ------------------------------------------------------------------ 설정

/**
 * 감시 간격. 팝업이 열릴 때마다 물어본다.
 *
 * 감시 중이면 **지금 돌고 있는 값**을 돌려준다. 저장된 값과 다를 수 있는데
 * (시작한 뒤에 슬라이더를 움직였다면), 화면에는 실제로 나가고 있는 것이 보여야 한다.
 */
async function getSettings() {
  const stored = await loadSettings();
  const running = controller.isWatching ? controller.config : null;
  return {
    ok: true,
    settings: running
      ? { minIntervalMs: running.minIntervalMs, maxIntervalMs: running.maxIntervalMs }
      : stored,
    stored,
    watching: controller.isWatching,
  };
}

/**
 * 간격을 바꾼다. **다음 감시부터 적용된다.**
 *
 * 돌고 있는 루프에 밀어 넣지 않는 이유는, 지금 사이클의 대기 시간은 이미 뽑혀 있고
 * 중간에 갈아 끼우면 **요청이 한 번 더 나가는 재시작**이 되기 때문이다 (대원칙 2).
 * 감시 중이면 팝업이 "다음 감시부터" 라고 말해 준다.
 */
async function updateInterval(message) {
  const settings = await saveSettings({
    minIntervalMs: message.minIntervalMs,
    maxIntervalMs: message.maxIntervalMs,
  });
  return { ok: true, settings, appliesNow: !controller.isWatching };
}

async function clearLogEntries() {
  logger.clear();
  await clearLog();
  return { ok: true };
}

// ------------------------------------------------------------------ 실측 (M2.5)

/**
 * ★ **클릭 실측(M-a) 한 번.** (PLAN.md §E-2-4)
 *
 * 이 저장소에서 **확장이 페이지를 누르는 유일한 경로**다. 감시 루프는 부르지 않고
 * 사용자가 팝업에서 두 번 눌러야만 여기 온다.
 *
 * 답을 얻으려는 질문은 하나뿐이다 — 합성 클릭이 이 사이트에 통하는가. 그 답이
 * `ClickDriver` 기본값을 정하고, 그때 비로소 M3 을 시작할 수 있다. 추측으로 정하면
 * 첫 실사용에서 좌석 하나를 날린다.
 *
 * **감시 중에는 하지 않는다.** 감시가 새로고침을 하는 사이에 좌석을 골라 두면
 * 그다음 판정이 진단 때문에 흔들린다.
 */
async function clickProbe(message) {
  if (controller.isWatching) return { ok: false, reason: 'WATCHING' };

  const tab = await targetTab();
  if (!tab) return { ok: false, reason: 'NOT_KORAIL' };

  const res = await new TabPageHost(tab.id).send({ type: 'CLICK_PROBE', ...message });
  if (!res.ok) {
    logger.log(LogCode.CLICK_PROBE, `실패: ${res.error ?? res.reason ?? '알 수 없음'}`);
    return { ok: false, reason: res.reason ?? 'PROBE_FAILED', error: res.error };
  }

  logger.log(LogCode.CLICK_PROBE, probeSummary(res.data));
  await persistNow();
  return { ok: true, probe: res.data };
}

/** 로그 한 줄. 자세한 것은 팝업이 펼쳐 보여 준다. */
function probeSummary(probe) {
  if (!probe.ran) return `누르지 않음 (${probe.reason})`;
  const req = probe.requests && probe.requests.supported
    ? `요청 ${probe.requests.entries.length}건`
    : '요청 측정 불가';
  return `${probe.verdict} trusted=${probe.event.trusted} ` +
    `active ${probe.before.activeCount}→${probe.after.activeCount} ${req}`;
}

/** 루프가 끝난 뒤 정리. 감시하지 않는 동안 그물을 켜 두지 않는다. */
async function afterLoopEnded() {
  if (controller.isWatching) return;
  await chrome.alarms.clear(REVIVE_ALARM);
  const watch = await loadWatch();
  if (watch) await saveWatch({ ...watch, running: false, status: controller.status });
  await persistNow();
}

/** SW 가 죽었다 살아났다면 이어간다. 살아 있으면 아무 일도 하지 않는다. (§E-3-2) */
async function reviveIfNeeded() {
  if (controller.isWatching) return;
  const watch = await loadWatch();
  if (!watch || !watch.running) {
    await chrome.alarms.clear(REVIVE_ALARM);
    return;
  }

  const host = new TabPageHost(watch.tabId);
  if (!(await host.isAlive())) {
    logger.log(LogCode.TAB_GONE, `부활: 탭 #${watch.tabId} 없음`);
    await saveWatch({ ...watch, running: false });
    await chrome.alarms.clear(REVIVE_ALARM);
    return;
  }

  const selection = await loadSelection(watch.tabId);
  if (selectionIsEmpty(selection)) {
    await saveWatch({ ...watch, running: false });
    await chrome.alarms.clear(REVIVE_ALARM);
    return;
  }

  // ★ **되돌릴 수 없는 클릭 도중에 죽었다.** (§E-3-2 3번)
  //
  // [예매] 가 실제로 나갔는지 우리는 모른다. 감시를 이어가면 다음 사이클에 같은 칸을
  // 한 번 더 누를 수 있고, 그것이 이 확장이 저지를 수 있는 가장 나쁜 일이다.
  // 그래서 **이어가지 않는다.** 좌석은 골라져 있을 테니 사람에게 넘긴다.
  if (watch.reserving) {
    const { trainNumber, departureTime, seatClass } = watch.reserving;
    logger.log(
      LogCode.RESERVE_HANDOVER,
      `부활: [예매] 를 누르는 중에 끊겼습니다 (${trainNumber} ${departureTime} ${seatClass})`,
    );
    await saveWatch({ ...watch, running: false, reserving: null });
    await chrome.alarms.clear(REVIVE_ALARM);
    notifier.notifyReserve(
      '예매 중에 확장이 잠시 멈췄습니다',
      '코레일 화면을 직접 확인해 주세요. 눌렸는지 알 수 없어 다시 누르지 않습니다.',
    );
    await persistNow();
    return;
  }

  logger.log(LogCode.WATCH_RESUMED, 'service worker 가 다시 깨어남');
  controller.start({ host, selection, config: watch.config });
  controller.finished().then(() => { afterLoopEnded().catch(() => {}); });
}

/**
 * [예매] 클릭 표시를 **곧바로** 저장한다. 디바운스를 태우지 않는다 —
 * 이 값이 늦게 쓰이면 있으나 마나다. (§E-3-2 3번)
 */
async function markReserving(mark) {
  const watch = await loadWatch();
  if (!watch) return;
  await saveWatch({
    ...watch,
    running: controller.isWatching,
    status: controller.status,
    reserving: mark,
  });
}

/**
 * 확장이 새로 로드되어 감시가 사라졌다는 것을 알린다. (§E-6-3 예외 19)
 *
 * `storage.session` 은 브라우저를 닫을 때 비므로, 여기서 `running` 이 남아 있다는 것은
 * 브라우저는 그대로인데 확장만 다시 뜬 경우다.
 */
async function announceLostWatch() {
  const watch = await loadWatch();
  if (!watch || !watch.running) return;
  await saveWatch({ ...watch, running: false });
  await chrome.alarms.clear(REVIVE_ALARM);
  notifier.notifyWatchStopped(
    '확장이 다시 시작되어 감시를 멈췄습니다',
    '코레일 탭에서 다시 [감시 시작] 을 눌러 주세요.',
  );
}

async function focusWatchedTab() {
  const tabId = controller.tabId ?? (await loadWatch())?.tabId;
  if (typeof tabId !== 'number') return;
  try {
    const tab = await chrome.tabs.update(tabId, { active: true });
    if (tab && typeof tab.windowId === 'number') {
      await chrome.windows.update(tab.windowId, { focused: true });
    }
  } catch {
    // 탭이 이미 없다. 알림을 눌렀을 뿐이니 조용히 넘어간다.
  }
}

// ------------------------------------------------------------------ 공통

/**
 * 감시 중이면 **그 탭**, 아니면 지금 보고 있는 탭.
 *
 * 감시 중에 팝업을 열면 다른 탭을 보고 있을 수 있는데, 그때 팝업이 엉뚱한 탭을
 * 읽으면 "감시 중인데 목록이 비어 있다" 로 보인다.
 *
 * `tabs` 권한을 넣지 않았으므로 `url` 은 **host permission 이 있는 탭에만** 채워진다.
 * 코레일이 아닌 탭이면 값이 비고, 그대로 `NOT_KORAIL` 이 된다. 그것으로 충분하다 —
 * 진단 문구 한 줄을 위해 모든 탭의 주소를 읽을 권한을 넓히지 않는다. (§E-6-1)
 */
async function targetTab() {
  if (controller.isWatching && controller.tabId !== null) {
    try {
      return await chrome.tabs.get(controller.tabId);
    } catch {
      // 감시하던 탭이 방금 사라졌다. 아래에서 활성 탭으로 떨어진다.
    }
  }
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  return tab && isKorailUrl(tab.url) ? tab : null;
}

// ------------------------------------------------------------------ 저장 (SW 가 죽어도)

let persistTimer = null;

/** 상태·로그를 모아서 쓴다. 줄마다 쓰면 초당 수십 번 storage 를 두들긴다. */
function schedulePersist() {
  if (persistTimer !== null) return;
  persistTimer = setTimeout(() => {
    persistTimer = null;
    persistNow().catch(() => {});
  }, PERSIST_DEBOUNCE_MS);
}

async function persistNow() {
  if (persistTimer !== null) {
    clearTimeout(persistTimer);
    persistTimer = null;
  }
  await saveLog(logger.entries());
  const watch = await loadWatch();
  if (watch) {
    await saveWatch({
      ...watch,
      running: controller.isWatching,
      status: controller.status,
    });
  }
}

/** 깨어날 때 예전 로그를 되살린다. 대기 중이었다면 그 흔적이 남아 있어야 한다. (§39-7) */
loadLog().then((entries) => logger.restore(entries)).catch(() => {});

// 감시 중이라고 저장돼 있으면 곧바로 이어간다. 그물(30초)을 기다릴 이유가 없다.
reviveIfNeeded().catch(() => {});
