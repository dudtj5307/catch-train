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

/** 감시 중일 때 상태를 다시 물어보는 간격. 팝업이 떠 있는 동안만 돈다. */
const STATUS_POLL_MS = 500;

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
};

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
};

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
els.clear.addEventListener('click', async () => {
  const res = await send({ type: 'CLEAR_SELECTION' });
  if (res.ok) {
    state.selection = res.selection;
    render();
  }
});

read();
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

  els.count.textContent = snapshot ? `선택 ${selectionSize(selection)} / 조회 ${trains.length}` : '';
  els.clear.hidden = selectionSize(selection) === 0;

  renderWatch();

  const notice = noticeFor(state);
  els.notice.textContent = notice ?? '';
  els.notice.hidden = notice === null;

  els.table.hidden = trains.length === 0;
  els.rows.replaceChildren(...trains.map((train) => trainRow(train, selection)));

  const warnings = snapshot && snapshot.warnings.length > 0
    ? [...new Set(snapshot.warnings)].join('\n')
    : '';
  els.warnings.textContent = warnings;
  els.warnings.hidden = warnings === '';

  renderLog();
}

function renderLog() {
  els.logToggle.textContent = state.logOpen ? '로그 접기' : '로그 보기';
  els.logClear.hidden = !state.logOpen;
  els.log.hidden = !state.logOpen;
  if (!state.logOpen) return;
  // 최신이 아래. 열자마자 마지막 줄이 보여야 한다 — 대기 중인지 아닌지가 거기 있다. (§39-7)
  els.log.textContent = state.log.map(formatEntry).join('\n') || '아직 로그가 없습니다.';
  els.log.scrollTop = els.log.scrollHeight;
}

function renderWatch() {
  const status = state.status ?? { state: WatchState.IDLE };
  els.watchState.textContent =
    `${watchStateIndicator(status.state)} ${watchStateLabel(status.state)}`;

  const running = state.watching && watchStateIsRunning(status.state);
  els.watchToggle.textContent = state.watching ? '감시 중지' : '감시 시작';
  els.watchToggle.classList.toggle('stop', state.watching);

  els.watchNext.textContent = running && typeof status.nextCheckInMs === 'number'
    ? `다음 확인 ${(status.nextCheckInMs / 1000).toFixed(1)}초`
    : (status.cycleCount ? `${status.cycleCount}회 확인` : '');

  const message = watchMessage(state);
  els.watchMessage.textContent = message ?? '';
  els.watchMessage.hidden = message === null;
  els.watchMessage.classList.toggle('bad', status.state === WatchState.ERROR);
  els.watchMessage.classList.toggle('good', status.state === WatchState.MATCHED);

  // 로그인 화면은 **사용자가 눌렀을 때만** 연다. 확장이 조용히 주소를 갈아 끼우지
  // 않는다 — 사용자의 탭은 사용자의 것이다. (PLAN.md §E-6-5)
  els.login.hidden = !(
    (state.startBlocked && state.startBlocked.reason === 'LOGGED_OUT') ||
    (state.login && state.login.state === 'LOGGED_OUT')
  );
}

/** 감시 칸에 띄울 한 줄. 시작을 막은 이유가 있으면 그것이 먼저다. */
function watchMessage({ startBlocked, status }) {
  if (startBlocked) {
    switch (startBlocked.reason) {
      case 'NO_SELECTION':
        return '감시할 칸을 하나 이상 체크하세요.';
      case 'LOGGED_OUT':
        return '코레일에 로그인되어 있지 않습니다.\n' +
          '좌석이 열린 순간 로그인 화면으로 튕기지 않도록, 먼저 로그인해 주세요.';
      case 'ANOTHER_TAB':
        return '다른 코레일 탭에서 이미 감시 중입니다. 감시는 한 번에 하나만 돕니다.';
      case 'NOT_KORAIL':
        return '코레일 조회 결과 화면에서 시작하세요.';
      default:
        return `감시를 시작하지 못했습니다. ${startBlocked.error ?? startBlocked.reason ?? ''}`;
    }
  }
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

/** 목록이 안 나오는 이유를 사람 말로. 원인을 모르면 모른다고 한다 (대원칙 6). */
function noticeFor({ snapshot, failure }) {
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
