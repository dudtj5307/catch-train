// 팝업 — [열차 선택] 목록과 감시 제어. (android: ui/WatchScreen.kt)
//
// 화면은 service worker 에만 말을 건다. selector 도 DOM 판독도 모른다 (대원칙 8).
// 사용자가 코레일에서 직접 조회한 결과를 그대로 펼쳐 주고, 체크한 칸을 돌려줄 뿐이다.
//
// 매진인 칸도 체크할 수 있다. **지금 매진인 좌석이 풀리기를 기다리는 것이 이 도구의
// 목적이다.** 반대로 아예 없는 칸(UNKNOWN)은 체크할 수 없다 — 영영 열리지 않는 칸을
// 기다리게 된다.
//
// 상태는 **팝업이 떠 있는 동안만 물어본다.** service worker 가 팝업으로 밀어 주는
// 방식이면 팝업이 닫혀 있을 때마다 "받는 곳이 없다" 오류가 난다. 팝업은 잠깐 떴다
// 사라지는 창이라 물어보는 쪽이 맞다.

import { SeatClass, seatClassLabel } from '../domain/seat-class.js';
import { SeatStatus, seatStatusLabel } from '../domain/seat-status.js';
import { PageStatus, pageStatusLabel } from '../domain/page-snapshot.js';
import { keyOf, seatStatusOf } from '../domain/train.js';
import { selectionContainsTrain, selectionSize } from '../domain/watch-selection.js';
import { formatEntry } from '../background/logger.js';
import {
  WatchState,
  watchStateIndicator,
  watchStateIsRunning,
  watchStateLabel,
} from '../background/watch-state.js';
import {
  DEFAULT_MAX_INTERVAL_MS,
  DEFAULT_MIN_INTERVAL_MS,
  clampRange,
  formatRange,
  requestsPerMinute,
} from '../background/scheduler.js';

/** 감시 중일 때 상태를 다시 물어보는 간격. 팝업이 떠 있는 동안만 돈다. */
const STATUS_POLL_MS = 500;

/** 비로그인 안내. 미리 잠글 때와 시작이 거절됐을 때가 같은 문구를 쓴다. */
const LOGGED_OUT_MESSAGE =
  '코레일에 로그인되어 있지 않습니다.\n' +
  '로그인한 뒤 이 팝업을 다시 열면 [감시 시작] 이 풀립니다.';

const els = {
  count: document.getElementById('count'),
  clear: document.getElementById('clear'),
  refresh: document.getElementById('refresh'),
  notice: document.getElementById('notice'),
  table: document.getElementById('table'),
  rows: document.getElementById('rows'),
  warnings: document.getElementById('warnings'),
  watchState: document.getElementById('watch-state'),
  watchNext: document.getElementById('watch-next'),
  watchToggle: document.getElementById('watch-toggle'),
  watchMessage: document.getElementById('watch-message'),
  login: document.getElementById('login'),
  logToggle: document.getElementById('log-toggle'),
  logClear: document.getElementById('log-clear'),
  log: document.getElementById('log'),
  probe: document.getElementById('probe'),
  probeResult: document.getElementById('probe-result'),
  intervalRange: document.getElementById('interval-range'),
  intervalRate: document.getElementById('interval-rate'),
  intervalMin: document.getElementById('interval-min'),
  intervalMax: document.getElementById('interval-max'),
  intervalMinOut: document.getElementById('interval-min-out'),
  intervalMaxOut: document.getElementById('interval-max-out'),
  intervalNote: document.getElementById('interval-note'),
  presets: Array.from(document.querySelectorAll('.preset')),
};

/**
 * 이 이상이면 경고색으로 바꾼다. **분당 요청 수 기준이다.**
 *
 * 근거는 하나뿐이다 — SRT 에서 IP 차단이 실제로 있었고 코레일도 같다고 본다.
 * 정확한 임계값을 아무도 모르므로 이 숫자는 **안전을 증명하지 않는다.** 사용자가
 * "지금 얼마나 두들기고 있는지" 를 모른 채 쓰지 않게 하는 것이 전부다. (대원칙 2)
 */
const RATE_WARN_PER_MINUTE = 30;

/** [클릭 진단] 이 확인 대기로 머무는 시간. 지나면 스스로 원래대로 돌아간다. */
const PROBE_ARM_MS = 6_000;

let state = {
  snapshot: null,
  selection: { seats: [] },
  status: { state: WatchState.IDLE },
  watching: false,
  log: [],
  failure: null,
  /** 시작을 막은 이유. 감시 상태가 아니라 **이번 [감시 시작] 시도**의 결과다. */
  startBlocked: null,
  logOpen: false,
  /** 클릭 진단(M-a)의 마지막 결과. 팝업이 닫히면 사라진다 — 남는 것은 로그 한 줄이다. */
  probe: null,
  /** 진단이 **한 번 더 눌리기를 기다리는 중**인가. 실수로 눌러 페이지가 눌리면 안 된다. */
  probeArmed: false,
  probeRunning: false,
  /**
   * 감시 간격. **비어 있는 상태를 두지 않는다.**
   *
   * 예전에는 `null` 로 두고 service worker 의 답을 기다리는 동안 "간격 —" 을 보여
   * 줬는데, 팝업이 뜨자마자 보이는 줄이 그것이라 **설정이 없는 것처럼** 읽혔다.
   * 실제로는 언제나 값이 있다 — 기본값은 [DEFAULT_MIN_INTERVAL_MS]~[DEFAULT_MAX_INTERVAL_MS]
   * 이고, `readSettings` 가 저장값을 받아 오면 그때 덮인다.
   */
  interval: {
    minIntervalMs: DEFAULT_MIN_INTERVAL_MS,
    maxIntervalMs: DEFAULT_MAX_INTERVAL_MS,
  },
  /** 저장은 됐지만 **지금 돌고 있는 감시에는 반영되지 않는** 상태인가. */
  intervalPending: false,
};

let probeArmTimer = null;

els.refresh.addEventListener('click', read);
els.watchToggle.addEventListener('click', toggleWatch);
els.login.addEventListener('click', () => send({ type: 'OPEN_LOGIN' }));
els.logToggle.addEventListener('click', () => {
  state.logOpen = !state.logOpen;
  render();
});
els.logClear.addEventListener('click', async () => {
  await send({ type: 'CLEAR_LOG' });
  state.log = [];
  render();
});
els.probe.addEventListener('click', runProbe);
els.intervalMin.addEventListener('input', () => onIntervalInput('min'));
els.intervalMax.addEventListener('input', () => onIntervalInput('max'));
els.intervalMin.addEventListener('change', commitInterval);
els.intervalMax.addEventListener('change', commitInterval);
for (const preset of els.presets) {
  preset.addEventListener('click', () => {
    state.interval = clampedInterval(Number(preset.dataset.min), Number(preset.dataset.max));
    renderInterval();
    commitInterval();
  });
}
els.clear.addEventListener('click', async () => {
  const res = await send({ type: 'CLEAR_SELECTION' });
  if (res.ok) {
    state.selection = res.selection;
    render();
  }
});

// 기본값으로 먼저 그린다. 저장값이 오면 `readSettings` 가 같은 자리를 덮는다 —
// 그 사이에 빈칸이 보이지 않게 하려는 것이다.
renderInterval();
read();
readSettings();
pollStatus();
setInterval(pollStatus, STATUS_POLL_MS);

async function read() {
  els.refresh.disabled = true;
  els.refresh.textContent = '읽는 중…';
  try {
    const res = await send({ type: 'READ_PAGE' });
    if (res.ok) {
      state.snapshot = res.snapshot;
      state.selection = res.selection;
      state.login = res.login;
      state.failure = null;
    } else {
      state.snapshot = null;
      state.selection = { seats: [] };
      state.login = null;
      state.failure = res;
    }
    render();
  } finally {
    els.refresh.disabled = false;
    els.refresh.textContent = '갱신';
  }
}

/**
 * 상태만 다시 그린다. **목록은 건드리지 않는다** — 0.5초마다 표를 통째로 다시 만들면
 * 사용자가 체크하려는 순간에 체크박스가 새로 생겨 클릭이 흘러내린다.
 */
async function pollStatus() {
  const res = await send({ type: 'GET_STATUS' });
  if (!res || !res.ok) return;
  state.status = res.status;
  state.watching = res.watching;
  state.log = res.log;
  renderWatch();
  renderLog();
}

async function toggleWatch() {
  els.watchToggle.disabled = true;
  try {
    if (state.watching) {
      const res = await send({ type: 'STOP_WATCH' });
      if (res.ok) {
        state.status = res.status;
        state.watching = false;
        state.startBlocked = null;
      }
      return;
    }
    const res = await send({ type: 'START_WATCH' });
    if (res.ok) {
      state.status = res.status;
      state.watching = true;
      state.startBlocked = null;
    } else {
      state.startBlocked = res;
    }
  } finally {
    els.watchToggle.disabled = false;
    // 감시를 시작/중지하면 "다음 감시부터" 안내가 유효하지 않다. 다시 물어본다.
    readSettings();
    render();
  }
}

async function toggle(train, seatClass) {
  const res = await send({ type: 'TOGGLE_SEAT', trainKey: keyOf(train), seatClass });
  if (res.ok) {
    state.selection = res.selection;
    render();
  }
}

function render() {
  const { snapshot, selection } = state;
  const trains = snapshot ? snapshot.trains : [];

  /*
    **비로그인이 확실하면 목록 자체를 내린다.**

    체크는 로그인보다 먼저 할 수 있는 일처럼 보이지만, 이 상태에서 할 수 있는 일은
    로그인 하나뿐이다 — [감시 시작] 은 이미 잠겨 있다 (`renderWatch`). 목록이 남아
    있으면 사용자는 칸을 체크하는 것으로 시작해 놓고 잠긴 버튼 앞에서 막힌다.
    실제로 그렇게 되풀이해 누른 적이 있어 로그인 버튼을 크게 만들었는데, 목록이
    옆에서 계속 "여기서부터 하라" 고 말하고 있으면 같은 일이 다시 난다.

    체크해 둔 것은 지우지 않는다 — 화면에서 감출 뿐이라 로그인하고 [갱신] 하면
    그대로 돌아온다. 판정이 `UNKNOWN` 이면 감추지 않는다 (대원칙 6).
  */
  const loggedOut = !state.watching && isLoggedOut(state);

  els.count.textContent = snapshot && !loggedOut
    ? `선택 ${selectionSize(selection)} / 조회 ${trains.length}`
    : '';
  els.clear.hidden = loggedOut || selectionSize(selection) === 0;

  renderWatch();

  const notice = noticeFor(state, loggedOut);
  els.notice.textContent = notice ?? '';
  els.notice.hidden = notice === null;

  els.table.hidden = loggedOut || trains.length === 0;
  els.rows.replaceChildren(
    ...(loggedOut ? [] : trains.map((train) => trainRow(train, selection))),
  );

  const warnings = !loggedOut && snapshot && snapshot.warnings.length > 0
    ? [...new Set(snapshot.warnings)].join('\n')
    : '';
  els.warnings.textContent = warnings;
  els.warnings.hidden = warnings === '';

  renderLog();
}

function renderLog() {
  els.logToggle.textContent = state.logOpen ? '로그 접기' : '로그 보기';
  els.logClear.hidden = !state.logOpen;
  els.probe.hidden = !state.logOpen;
  els.log.hidden = !state.logOpen;
  renderProbe();
  if (!state.logOpen) return;
  // 최신이 아래. 열자마자 마지막 줄이 보여야 한다 — 대기 중인지 아닌지가 거기 있다. (§39-7)
  els.log.textContent = state.log.map(formatEntry).join('\n') || '아직 로그가 없습니다.';
  els.log.scrollTop = els.log.scrollHeight;
}

// ----------------------------------------------------------------- 감시 간격
//
// **화면에서 주인공은 "분당 약 N회" 다.** 간격만 보여 주면 "0.3초니까 괜찮겠지" 로
// 읽히는데, 한 사이클의 대부분은 새로고침 자체(약 1초)라 대기를 두 배로 늘려도
// 요청 수는 절반이 되지 않는다. 차단을 부르는 것은 간격이 아니라 분당 요청 수다.
// (대원칙 2 — SRT IP 차단은 실제로 있었다)

async function readSettings() {
  const res = await send({ type: 'GET_SETTINGS' });
  if (!res || !res.ok) return;
  state.interval = res.settings;
  state.intervalPending = false;
  renderInterval();
}

/** 슬라이더를 끄는 동안. 저장은 [commitInterval] 이 손을 뗄 때 한 번만 한다. */
function onIntervalInput(which) {
  const lo = Number(els.intervalMin.value);
  const hi = Number(els.intervalMax.value);
  // 최소가 최대를 넘어서면 **끌고 있는 쪽을 존중하고** 반대쪽을 민다.
  state.interval = which === 'min'
    ? clampedInterval(lo, Math.max(lo, hi))
    : clampedInterval(Math.min(lo, hi), hi);
  renderInterval();
}

/**
 * 손을 뗐을 때 한 번 저장한다. 끄는 내내 저장하면 `storage` 를 수십 번 두들긴다.
 *
 * **감시 중이면 다음 감시부터 적용된다.** 돌고 있는 루프에 밀어 넣지 않는 이유는
 * 이번 사이클의 대기가 이미 뽑혀 있어서, 갈아 끼우면 요청이 한 번 더 나가는
 * 재시작이 되기 때문이다 (대원칙 2).
 */
async function commitInterval() {
  if (!state.interval) return;
  const res = await send({ type: 'SET_INTERVAL', ...state.interval });
  if (!res || !res.ok) return;
  state.interval = res.settings;
  state.intervalPending = !res.appliesNow;
  renderInterval();
}

function clampedInterval(minMs, maxMs) {
  const [minIntervalMs, maxIntervalMs] = clampRange(minMs, maxMs);
  // **다른 설정을 떨어뜨리지 않는다.** 지금은 간격뿐이지만, 설정이 늘어났을 때
  // 여기서 통째로 갈아 끼우면 화면과 저장값이 어긋난다.
  return { ...(state.interval ?? {}), minIntervalMs, maxIntervalMs };
}

function renderInterval() {
  const iv = state.interval;
  if (!iv) return;

  const { minIntervalMs: lo, maxIntervalMs: hi } = iv;
  els.intervalMin.value = String(lo);
  els.intervalMax.value = String(hi);
  els.intervalMinOut.textContent = `${(lo / 1000).toFixed(1)}초`;
  els.intervalMaxOut.textContent = `${(hi / 1000).toFixed(1)}초`;
  // "감시 간격" 이라는 이름표는 HTML 에 붙박이로 있다. 여기는 값만 쓴다.
  els.intervalRange.textContent = formatRange(lo, hi);

  const rate = requestsPerMinute(lo, hi);
  els.intervalRate.textContent = `분당 약 ${rate}회`;
  els.intervalRate.classList.toggle('bad', rate >= RATE_WARN_PER_MINUTE);

  for (const preset of els.presets) {
    const presetLo = Number(preset.dataset.min);
    const presetHi = Number(preset.dataset.max);
    preset.classList.toggle('on', presetLo === lo && presetHi === hi);
    // **버튼마다 대가를 적어 둔다.** 고르고 나서가 아니라 고르기 전에 보여야 한다.
    const rateEl = preset.querySelector('.preset-rate');
    if (rateEl) rateEl.textContent = `분당 ${requestsPerMinute(presetLo, presetHi)}회`;
  }

  els.intervalNote.textContent = state.intervalPending
    ? '지금 돌고 있는 감시에는 반영되지 않습니다. 다음 [감시 시작] 부터 적용됩니다.'
    : '새로고침 한 번이 문서 + 번들 + 조회 API 전부입니다. ' +
      '대기를 늘려도 요청 수가 그만큼 줄지는 않습니다 — 오른쪽 숫자를 보세요.';
}

// ------------------------------------------------------------- 클릭 진단 (M-a)
//
// **이 팝업에서 페이지를 실제로 누르는 유일한 자리다.** (PLAN.md §E-2-4)
// 답해야 하는 것은 하나 — 합성 클릭(`el.click()`)이 코레일에 통하는가. 그 답이
// `ClickDriver` 기본값을 정하고, 정해지기 전에는 M3 을 시작하지 않는다.
//
// 그래서 **두 번 눌러야** 돈다. 진단은 좌석 칸 하나를 실제로 골라 두고 끝나므로
// 실수로 한 번 눌린 것과 구분되어야 한다.

async function runProbe() {
  if (!state.probeArmed) {
    state.probeArmed = true;
    clearTimeout(probeArmTimer);
    probeArmTimer = setTimeout(() => {
      state.probeArmed = false;
      renderProbe();
    }, PROBE_ARM_MS);
    renderProbe();
    return;
  }

  state.probeArmed = false;
  state.probeRunning = true;
  clearTimeout(probeArmTimer);
  renderProbe();
  try {
    // **응답이 없는 것도 결과다.** 낡은 service worker 는 모르는 요청에 아무 말도 하지
    // 않고 채널을 닫아 버리고, 그러면 여기 `undefined` 가 온다. 그대로 두면 아래
    // [renderProbe] 가 빈 글을 그려서 **눌러도 아무 일이 없는 것처럼** 보인다 —
    // 실제로 그 침묵 때문에 "클릭 진단이 반응이 없다" 로 한참을 헤맸다.
    state.probe = (await send({ type: 'CLICK_PROBE' })) ?? { ok: false, reason: 'NO_RESPONSE' };
  } catch (e) {
    // "Could not establish connection" — service worker 가 아예 없다. 원인은 같다.
    state.probe = { ok: false, reason: 'NO_RESPONSE', error: (e && e.message) || String(e) };
  } finally {
    state.probeRunning = false;
    renderProbe();
  }
}

/** 0.5초마다 다시 불린다 (`pollStatus`). 상태는 전부 [state] 에 있어야 한다. */
function renderProbe() {
  els.probe.classList.toggle('arm', state.probeArmed);
  els.probe.disabled = state.probeRunning === true;
  els.probe.textContent = state.probeRunning
    ? '진단 중…'
    : (state.probeArmed ? '한 번 더 → 실제로 누름' : '클릭 진단');

  const text = state.probe ? probeText(state.probe) : '';
  els.probeResult.textContent = text;
  els.probeResult.hidden = !state.logOpen || text === '';
}

/** 진단 결과를 사람이 읽는 글로. **판단까지 여기서 해 준다** — 숫자만 보면 못 읽는다. */
function probeText(res) {
  if (!res.ok) {
    switch (res.reason) {
      case 'WATCHING':
        return '감시 중에는 진단하지 않습니다. 감시를 멈추고 다시 해 주세요.';
      case 'NOT_KORAIL':
        return '코레일 조회 결과 화면에서 해 주세요.';
      // 낡은 빌드가 돌고 있다는 뜻이다. 팝업만 새 코드라 버튼은 보이는데 그 버튼이
      // 부르는 핸들러가 없는 상태 — 개발 중에 가장 자주 겪는 "아무 반응 없음" 이다.
      case 'NO_RESPONSE':
      case 'UNKNOWN_TYPE':
        return '확장이 이 요청을 모릅니다 — 낡은 코드가 돌고 있습니다.\n' +
          '  1) chrome://extensions 에서 이 확장을 새로고침(↻)\n' +
          '  2) 코레일 탭도 F5\n' +
          '팝업은 열 때마다 새로 읽히지만 service worker 와 content script 는 그렇지 않습니다.';
      default:
        return `진단하지 못했습니다. ${res.error ?? res.reason ?? ''}`;
    }
  }

  const p = res.probe;
  if (!p.ran) return `누르지 않았습니다 — ${p.detail ?? p.reason}`;

  const req = p.requests.supported
    ? (p.requests.entries.length === 0
      ? '요청 0건 (화면 안에서 끝났다)'
      : `요청 ${p.requests.entries.length}건\n` +
        p.requests.entries.map((r) => `      ${r.how} ${r.url}`).join('\n'))
    : '요청을 재지 못함 (PerformanceObserver 없음)';

  // 빈 줄을 넣지 않는다 — 아래 `filter` 가 걸러낸다. 그 `filter` 는 예외 줄이 없을 때
  // 빈 줄이 남지 않게 하려고 있는 것이다.
  return [
    `[M-a] ${p.verdict}  ${verdictText(p.verdict)}`,
    `  대상  ${p.target.trainNumber} ${p.target.seatClassLabel}  ${p.target.anchor}` +
      (p.target.usedCellItself ? ' (칸 자체를 누름 — a 를 못 찾았다)' : ''),
    `  닿음  ${p.hit.ok ? 'ok' : `${p.hit.why} ${p.hit.covered}`} rect=${p.hit.rect}`,
    `  이벤트 fired=${p.event.fired} trusted=${p.event.trusted} ` +
      `onTarget=${p.event.onTarget} prevented=${p.event.prevented} → ${p.event.tag}`,
    `  active ${p.before.activeCount} → ${p.after.activeCount}` +
      `   이 칸 ${p.before.cellActive} → ${p.after.cellActive}`,
    `  하단바 ${barText(p.before)} → ${barText(p.after)}`,
    `  ${req}`,
    p.clickError ? `  click() 예외: ${p.clickError}` : '',
    '  ※ 좌석 칸 하나가 골라진 채로 끝납니다. 되돌리려면 페이지를 새로고침하세요.',
  ].filter((line) => line !== '').join('\n');
}

/** 결과 한 줄이 무엇을 뜻하는지. 다음에 무엇을 할지까지 적는다. */
function verdictText(verdict) {
  switch (verdict) {
    case 'SYNTHETIC_OK':
      return '합성 클릭이 통했다. 디버거 없이 M3 을 붙일 수 있다.';
    case 'NO_EFFECT':
      return '닿았지만 아무 일도 없었다. 다음은 M-b(디버거 attach 만).';
    case 'NOT_DELIVERED':
      return '클릭이 페이지까지 가지 않았다. selector·가림 문제다.';
    case 'WRONG_CELL':
      return '엉뚱한 칸이 골라졌다. 대상 탐색부터 다시 봐야 한다.';
    case 'CLICK_THREW':
      return 'click() 이 예외를 냈다. 사이트가 아니라 확장 쪽 문제다.';
    default:
      return '';
  }
}

function barText(side) {
  if (!side.bar) return '없음';
  const buttons = side.barButtons.length > 0 ? ` [${side.barButtons.join(' / ')}]` : '';
  return `${side.barVisible ? '보임' : 'DOM만'} "${side.barLabel}"${buttons}`;
}

function renderWatch() {
  const status = state.status ?? { state: WatchState.IDLE };
  els.watchState.textContent =
    `${watchStateIndicator(status.state)} ${watchStateLabel(status.state)}`;

  const running = state.watching && watchStateIsRunning(status.state);
  els.watchToggle.textContent = state.watching ? '감시 중지' : '감시 시작';
  els.watchToggle.classList.toggle('stop', state.watching);

  // **비로그인이 확실하면 [감시 시작] 을 미리 잠그고 로그인으로 보낸다.**
  // 예전에는 누른 뒤에야 막았는데, 거절이 작은 글씨로만 떠서 사용자가 같은 버튼을
  // 되풀이해 눌렀다 (실사용). 이 상태에서 누를 수 있는 것은 하나여야 한다.
  //
  // 판정이 `UNKNOWN` 이면 잠그지 않는다 — 마커 하나를 놓쳤을 뿐인데 감시가 영영
  // 시작되지 않는 편이 더 나쁘다. (대원칙 6)
  //
  // service worker 쪽 확인(`startWatch`)은 그대로 둔다. 팝업이 아는 로그인 상태는
  // **마지막으로 읽은 시점의 것**이라 낡을 수 있다.
  const loggedOut = !state.watching && isLoggedOut(state);
  els.watchToggle.disabled = loggedOut;
  els.watchToggle.title = loggedOut ? '코레일에 로그인한 뒤 시작할 수 있습니다.' : '';

  els.watchNext.textContent = running && typeof status.nextCheckInMs === 'number'
    ? `다음 확인 ${(status.nextCheckInMs / 1000).toFixed(1)}초`
    : (status.cycleCount ? `${status.cycleCount}회 확인` : '');

  const message = watchMessage(state);
  els.watchMessage.textContent = message ?? '';
  els.watchMessage.hidden = message === null;
  els.watchMessage.classList.toggle('bad', status.state === WatchState.ERROR || loggedOut);
  els.watchMessage.classList.toggle(
    'good',
    status.state === WatchState.MATCHED ||
      status.state === WatchState.RESERVED ||
      status.state === WatchState.SEAT_SELECTED,
  );

  // 로그인 화면은 **사용자가 눌렀을 때만** 연다. 확장이 조용히 주소를 갈아 끼우지
  // 않는다 — 사용자의 탭은 사용자의 것이다. (PLAN.md §E-6-5)
  els.login.hidden = !loggedOut;
}

/**
 * 비로그인이 **확실한가.** `UNKNOWN` 은 여기 들어오지 않는다 (대원칙 6).
 *
 * 눌러서 거절당한 결과(`startBlocked`)가 마지막 판독보다 최신이므로 그쪽을 먼저 본다.
 */
function isLoggedOut({ startBlocked, login }) {
  if (startBlocked && startBlocked.reason === 'LOGGED_OUT') return true;
  return Boolean(login && login.state === 'LOGGED_OUT');
}

/** 감시 칸에 띄울 한 줄. 시작을 막은 이유가 있으면 그것이 먼저다. */
function watchMessage(state) {
  const { startBlocked, status } = state;
  if (startBlocked) {
    switch (startBlocked.reason) {
      case 'NO_SELECTION':
        return '감시할 칸을 하나 이상 체크하세요.';
      case 'LOGGED_OUT':
        return LOGGED_OUT_MESSAGE;
      case 'ANOTHER_TAB':
        return '다른 코레일 탭에서 이미 감시 중입니다. 감시는 한 번에 하나만 돕니다.';
      case 'NOT_KORAIL':
        return '코레일 조회 결과 화면에서 시작하세요.';
      default:
        return `감시를 시작하지 못했습니다. ${startBlocked.error ?? startBlocked.reason ?? ''}`;
    }
  }
  // 아직 누르지 않았어도 왜 잠겨 있는지 알려 준다. 잠긴 버튼만 있고 이유가 없으면
  // 그것이 사용자가 말한 "아무 반응 없음" 이다.
  if (!state.watching && isLoggedOut(state)) return LOGGED_OUT_MESSAGE;
  return status && status.message ? status.message : null;
}

function trainRow(train, selection) {
  const tr = document.createElement('tr');

  const info = document.createElement('td');
  info.className = 'train';
  const time = document.createElement('div');
  time.className = 'time';
  time.textContent = `${train.departureTime} → ${train.arrivalTime}`;
  const number = document.createElement('div');
  number.className = 'number';
  number.textContent = [
    train.trainNumber || '열차번호 미상',
    train.departureStation && `${train.departureStation} → ${train.arrivalStation}`,
  ].filter(Boolean).join('  ');
  info.append(time, number);

  tr.append(
    info,
    // 사이트와 같은 순서: 일반실이 왼쪽, 특실이 오른쪽. **SRT 와 반대다.** (§38-3)
    seatCell(train, SeatClass.GENERAL, selection),
    seatCell(train, SeatClass.FIRST_CLASS, selection),
  );
  return tr;
}

function seatCell(train, seatClass, selection) {
  const td = document.createElement('td');
  const label = document.createElement('label');
  label.className = 'seat';

  const status = seatStatusOf(train, seatClass);
  const box = document.createElement('input');
  box.type = 'checkbox';
  box.checked = selectionContainsTrain(selection, train, seatClass);
  box.disabled = status === SeatStatus.UNKNOWN;
  box.title = `${train.departureTime} ${train.trainNumber} ${seatClassLabel(seatClass)}`;
  box.addEventListener('change', () => toggle(train, seatClass));

  const text = document.createElement('span');
  text.className = status === SeatStatus.AVAILABLE ? 'status available' : 'status';
  text.textContent = seatStatusLabel(status);

  label.append(box, text);
  td.append(label);
  return td;
}

/**
 * 목록이 안 나오는 이유를 사람 말로. 원인을 모르면 모른다고 한다 (대원칙 6).
 *
 * [loggedOut] 이면 아무 말도 하지 않는다. 로그인이 필요하다는 것은 감시 칸의
 * 안내([LOGGED_OUT_MESSAGE])와 [코레일 로그인 화면 열기] 버튼이 이미 말하고 있다 —
 * 같은 말이 두 번 있으면 둘 다 안 읽힌다.
 */
function noticeFor({ snapshot, failure }, loggedOut = false) {
  if (loggedOut) return null;
  if (failure) {
    switch (failure.reason) {
      case 'NOT_KORAIL':
        return '코레일 예매 화면에서 열어 주세요.\n' +
          '이 창은 지금 보고 있는 탭의 조회 결과를 읽습니다.';
      case 'NO_CONTENT_SCRIPT':
        return '아직 화면을 읽을 수 없습니다.\n' +
          '페이지가 다 그려진 뒤 [갱신] 을 눌러 보세요. (확장을 새로 설치했다면 탭 새로고침이 필요합니다)';
      default:
        return `화면을 읽지 못했습니다.\n${failure.error ?? failure.reason ?? ''}`;
    }
  }
  if (!snapshot) return '읽어온 화면이 없습니다.';

  switch (snapshot.status) {
    case PageStatus.TRAIN_LIST:
      return snapshot.searchDate ? `${snapshot.searchDate} 조회 결과` : null;
    case PageStatus.NO_TRAIN:
      return '조회된 열차가 없습니다. 코레일 화면에서 조건을 바꿔 다시 조회해 주세요.';
    case PageStatus.BLOCKED:
      return '접속이 차단된 화면으로 보입니다. 잠시 뒤에 다시 시도하세요.';
    default:
      return `${pageStatusLabel(snapshot.status)}.\n` +
        '코레일 화면에서 원하는 조건으로 조회한 뒤 [갱신] 을 누르세요.';
  }
}

function send(message) {
  return chrome.runtime.sendMessage(message);
}
