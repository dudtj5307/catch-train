// 감시 루프. (android: watcher/WatchControllerTest.kt)
//
// 케이스를 그대로 옮겼다. 두 클라이언트가 **같은 상황에서 같은 판단**을 해야 한다 (§34-2).
// 확장에서 새로 생기는 경로(content script 없음 / 탭 닫힘 / 조회 조건 바뀜)에는
// ★ 를 붙였다 — 안드로이드에는 대응 케이스가 없다.
//
// 시간은 **가짜 시계**로 흘린다. 실제로 0.3초씩 자는 것을 기다릴 이유가 없고,
// 예산(§39)을 검사하려면 시간을 밀 수 있어야 한다. (안드로이드에서 `clock` 을 갈아
// 끼워야 했던 것과 같은 이유다 — §39-8)

import test from 'node:test';
import assert from 'node:assert/strict';

import { SeatClass } from '../src/domain/seat-class.js';
import { SeatStatus } from '../src/domain/seat-status.js';
import { snapshotFromRaw } from '../src/domain/page-snapshot.js';
import { keyOf } from '../src/domain/train.js';
import { selectionOf } from '../src/domain/watch-selection.js';
import { LogCode, WatchLogger } from '../src/background/logger.js';
import { WatchController } from '../src/background/watch-controller.js';
import { DEFAULT_WATCH_CONFIG } from '../src/background/watch-config.js';
import { ReserveResult, ReserveStage, WatchError, WatchState } from '../src/background/watch-state.js';

// ---------------------------------------------------------------- 바탕

/** 18:30 305 한 편성. 칸 순번은 `[0]=일반실, [1]=특실` 이다. (§38-3) */
function raw(generalStatus, firstClassStatus = 'SOLD_OUT', overrides = {}) {
  return {
    status: 'TRAIN_LIST',
    url: 'https://www.korail.com/ticket/search/list',
    title: 't',
    trains: [{
      trainNumber: '305',
      trainType: 'KTX-산천',
      departureStation: '동탄',
      arrivalStation: '김천구미',
      departureTime: '18:30',
      arrivalTime: '19:36',
      generalSeatStatus: generalStatus,
      firstClassSeatStatus: firstClassStatus,
      rowKey: '2:abc123',
      rowIndex: 0,
      generalCellIndex: 0,
      firstClassCellIndex: 1,
    }],
    ...overrides,
  };
}

const pageOnly = (status) => ({ status, url: 'https://www.korail.com/x', title: 't', trains: [] });

/** 18:30 305 의 일반실 한 칸만 감시한다. */
const SELECTION = selectionOf([{
  trainKey: keyOf({ trainNumber: '305', departureTime: '18:30' }),
  seatClass: SeatClass.GENERAL,
}]);

/** content script 가 아직 없다. **오류가 아니다.** (PLAN.md §E-6-1) */
const ABSENT = Symbol('NO_CONTENT_SCRIPT');
/** 판독 자체가 깨졌다. */
const BROKEN = Symbol('PARSE_FAILED');

class FakeHost {
  tabId = 1;
  requeryCount = 0;
  parseCount = 0;
  loginCount = 0;

  /** 갱신 결과. 기본은 "목록이 다시 그려졌다". */
  outcome = { kind: 'UPDATED', detail: 'sig 변경' };

  /** 로그인 확인 결과. 기본은 판정 못 함 — 그래도 막지 않는다 (대원칙 6). */
  loginResult = { state: 'UNKNOWN', detail: '표시 없음' };

  /** 조회 조건 서명. 바꾸면 사용자가 다시 조회한 것이다. */
  sig = 'q1';

  /** 판독 결과를 이 순서대로 돌려준다. 다 쓰면 [fallback] 을 계속 돌려준다. */
  reads = [];

  constructor(fallback, { onCycle = () => {} } = {}) {
    this.fallback = fallback;
    this.onCycle = onCycle;
  }

  async isAlive() {
    return true;
  }

  async requery({ onReload }) {
    this.requeryCount++;
    if (this.outcome.kind !== 'TAB_GONE') onReload(`chrome.tabs.reload #${this.tabId}`);
    this.onCycle(this);
    return this.outcome;
  }

  async parse() {
    this.parseCount++;
    const next = this.reads.length > 0 ? this.reads.shift() : this.fallback;
    if (next === ABSENT) return { ok: false, reason: 'NO_CONTENT_SCRIPT', error: '연결할 수 없음' };
    if (next === BROKEN) return { ok: false, reason: 'PARSE_FAILED', error: '판독 실패' };
    return { ok: true, snapshot: snapshotFromRaw(next) };
  }

  async login() {
    this.loginCount++;
    return this.loginResult;
  }

  async querySig() {
    return { ok: true, sig: this.sig };
  }

  // --- 예매 계열. **기본은 "다 잘 된다"** -------------------------------
  //
  // 좌석이 열리면 누르는 것은 설정이 아니라 이 도구가 하는 일이다 (`watch-config.js`).
  // 끄는 스위치가 없으므로 **가짜 host 도 누를 줄 알아야 한다** — 예전에는 대부분의
  // 테스트가 자동 예매를 꺼서 이 자리를 비워 두었다. 다르게 굴러야 하는 테스트는
  // [withReserve] 로 갈아 끼운다.

  selectCalls = [];
  confirmCalls = [];
  backCalls = 0;

  async selectSeat(arg) {
    this.selectCalls.push(arg);
    return { result: 'SELECTED', detail: '일반실 선택됨', clicked: true };
  }

  async confirmReserve(arg) {
    this.confirmCalls.push(arg);
    return { result: 'CLICKED', detail: '[예매] 눌렀습니다', clicked: true, button: '예매', baseline: {} };
  }

  async watchReserveOutcome() {
    return { kind: 'CLICKED', detail: '화면 전환', page: null };
  }

  async goBack() {
    this.backCalls++;
    return { kind: 'UPDATED', detail: '목록 1편성' };
  }
}

class RecordingNotifier {
  sent = [];
  stopped = [];
  reserved = [];

  notifyMatch(match, extraCount) {
    this.sent.push({ match, extraCount });
  }

  notifyWatchStopped(title, body) {
    this.stopped.push([title, body]);
  }

  /** 예매의 결말 알림. ([예매] 누름 / 인계 / 클릭이 안 먹음) */
  notifyReserve(title, body) {
    this.reserved.push([title, body]);
  }

  cancelAll() {}
}

/**
 * 가짜 시계. [sleep] 이 곧바로 돌아오면서 시간만 민다.
 * 예산(§39)이 실제로 만료되는 것을 검사할 수 있어야 하므로 시계와 잠을 함께 준다.
 */
function fakeTime() {
  let now = 0;
  return {
    now: () => now,
    sleep: async (ms) => {
      now += Math.max(0, ms);
    },
  };
}

/** 대기 시간을 기록하는 스케줄러. 간격이 매번 새로 뽑히는지도 여기서 본다. */
function fakeScheduler(time) {
  const intervals = [];
  return {
    intervals,
    nextInterval: (min, max) => {
      const value = Math.round((min + max) / 2);
      intervals.push(value);
      return value;
    },
    wait: async (ms, { onRemaining = () => {} } = {}) => {
      await time.sleep(ms);
      onRemaining(0);
    },
  };
}

function setup(host, config = {}) {
  const time = fakeTime();
  const notifier = new RecordingNotifier();
  const logger = new WatchLogger({ clock: time.now });
  const scheduler = fakeScheduler(time);
  let selectionCleared = 0;
  const reserveMarks = [];
  const controller = new WatchController({
    notifier,
    logger,
    scheduler,
    clock: time.now,
    sleepFn: time.sleep,
    onSelectionInvalid: () => { selectionCleared++; },
    // **[예매] 를 누르기 직전/직후의 표시.** 실제로는 storage 에 쓴다 (§E-3-2 3번).
    onReserving: (mark) => { reserveMarks.push(mark); },
  });
  const run = (selection = SELECTION) => {
    controller.start({
      host,
      selection,
      config: { ...DEFAULT_WATCH_CONFIG, ...config },
    });
    return controller.finished();
  };
  return {
    controller, notifier, logger, scheduler, time, run,
    cleared: () => selectionCleared,
    reserveMarks,
  };
}

// ---------------------------------------------------------------- 발견과 알림

test('선택한 좌석이 열리면 알림을 보내고 감시를 멈춘다', async () => {
  const host = new FakeHost(raw('AVAILABLE'));
  const t = setup(host);
  await t.run();

  assert.equal(t.notifier.sent.length, 1);
  assert.equal(t.notifier.sent[0].match.seatClass, SeatClass.GENERAL);
  // 알림이 먼저 나가고, 이어서 누른다. 끄는 스위치가 없으므로 여기서 멈추지 않는다.
  assert.equal(t.controller.status.state, WatchState.RESERVED);
  assert.equal(t.controller.isWatching, false);
});

test('첫 사이클은 새로고침하지 않는다 — 사용자가 방금 조회한 화면이다', async () => {
  const host = new FakeHost(raw('AVAILABLE'));
  const t = setup(host);
  await t.run();

  assert.equal(host.requeryCount, 0);
  assert.equal(t.logger.count(LogCode.RESEARCH_TRIGGERED), 0);
});

test('선택하지 않은 좌석이 열려도 반응하지 않는다', async () => {
  // 특실이 열렸지만 사용자가 고른 것은 일반실이다.
  const host = new FakeHost(raw('SOLD_OUT', 'AVAILABLE'), {
    onCycle: (h) => { if (h.requeryCount >= 2) t.controller.stop('테스트 종료'); },
  });
  const t = setup(host);
  await t.run();

  assert.equal(t.notifier.sent.length, 0);
});

test('예약대기는 발견으로 보지 않는다', async () => {
  const host = new FakeHost(raw('WAITING'), {
    onCycle: (h) => { if (h.requeryCount >= 2) t.controller.stop('테스트 종료'); },
  });
  const t = setup(host);
  await t.run();

  assert.equal(t.notifier.sent.length, 0);
});

test('갱신은 새로고침이고, 나간 사실이 로그에 남는다', async () => {
  const host = new FakeHost(raw('SOLD_OUT'), {
    onCycle: (h) => { if (h.requeryCount >= 3) t.controller.stop('테스트 종료'); },
  });
  const t = setup(host);
  await t.run();

  assert.equal(host.requeryCount, 3);
  // 요청이 나간 횟수 = 이 로그의 줄 수. 이 등식이 깨지면 §39-7 의 진단이 거짓말이 된다.
  assert.equal(t.logger.count(LogCode.RESEARCH_TRIGGERED), 3);
});

test('같은 좌석은 다시 알리지 않고, 매진되었다 다시 열리면 새로 알린다', async () => {
  // 알림 중복 방지(§20)는 **여러 사이클을 도는 동안**의 규칙이라, 예매가 성공해
  // 첫 사이클에서 끝나 버리면 볼 수가 없다. 그래서 1단계가 편성을 못 찾는 host 를
  // 쓴다 — 아무것도 누르지 않았으므로 감시가 그대로 이어지는 경로다.
  const host = withReserve(new FakeHost(raw('AVAILABLE'), {
    onCycle: (h) => {
      // 열림 → 열림(같은 칸) → 매진 → 열림
      if (h.requeryCount === 2) h.fallback = raw('SOLD_OUT');
      if (h.requeryCount === 3) h.fallback = raw('AVAILABLE');
      if (h.requeryCount >= 4) t.controller.stop('테스트 종료');
    },
  }), {
    select: { result: 'ROW_NOT_FOUND', detail: '편성을 찾지 못했습니다', clicked: false },
  });
  const t = setup(host, { stopOnMatch: false });
  await t.run();

  // 첫 발견 + 매진 뒤 다시 열린 발견 = 2회. 이어진 같은 칸은 세지 않는다. (§20)
  assert.equal(t.notifier.sent.length, 2);
});

test('선택이 비어 있으면 감시를 멈춘다', async () => {
  const host = new FakeHost(raw('AVAILABLE'));
  const t = setup(host);
  await t.run(selectionOf([]));

  assert.equal(t.controller.status.state, WatchState.STOPPED);
  assert.equal(host.parseCount, 0);
});

// ---------------------------------------------------------------- 화면 확정 (§39)

test('화면이 확정되기 전에는 새로고침하지 않고 제자리에서 다시 읽는다', async () => {
  const host = new FakeHost(raw('AVAILABLE'));
  host.reads = [
    pageOnly('UNKNOWN_PAGE'),
    pageOnly('UNKNOWN_PAGE'),
    pageOnly('UNKNOWN_PAGE'),
    raw('AVAILABLE'),
  ];
  const t = setup(host);
  await t.run();

  assert.ok(t.logger.has(LogCode.PAGE_WAIT_START));
  assert.ok(t.logger.has(LogCode.PAGE_WAIT_DONE));
  // **기다리는 동안 요청이 한 번도 나가지 않아야 한다.** 나가면 대기 순번이 날아간다.
  assert.equal(t.logger.count(LogCode.RESEARCH_TRIGGERED), 0);
  assert.equal(host.parseCount, 4);
  assert.equal(t.controller.status.state, WatchState.RESERVED);
});

test('★ content script 가 없는 동안은 기다린다 — 오류로 세지 않는다', async () => {
  const host = new FakeHost(raw('AVAILABLE'));
  host.reads = [ABSENT, ABSENT, ABSENT, raw('AVAILABLE')];
  const t = setup(host);
  await t.run();

  assert.ok(t.logger.has(LogCode.CONTENT_ABSENT));
  // 새로고침 직후마다 정상적으로 겪는 상태다. 오류로 세면 감시가 3사이클에 죽는다.
  assert.equal(t.logger.count(LogCode.DOM_PARSE_ERROR), 0);
  assert.equal(t.controller.status.state, WatchState.RESERVED);
});

test('목록을 본 적이 없으면 짧게 끊고 안내한다', async () => {
  const host = new FakeHost(pageOnly('UNKNOWN_PAGE'));
  const t = setup(host);
  await t.run();

  assert.ok(t.logger.has(LogCode.PAGE_WAIT_TIMEOUT));
  assert.equal(t.controller.status.error, WatchError.UNKNOWN_PAGE);
  // 예산은 10초 두 번(maxUnknownPages=2). 3분짜리를 붙들었으면 여기서 걸린다.
  assert.ok(t.time.now() < 60_000, `너무 오래 기다렸다: ${t.time.now()}ms`);
  // 사이클을 두 번 돌았으니 새로고침은 한 번 나갔다 (첫 사이클은 건너뛴다).
  assert.equal(host.requeryCount, 1);
});

test('목록을 본 뒤에는 짧은 예산을 넘겨도 계속 기다린다', async () => {
  const host = new FakeHost(raw('AVAILABLE'));
  // 1) 첫 사이클에 목록(매진)을 본다  2) 그 뒤로 30번(=15초) 대기 화면  3) 좌석이 열린다
  host.reads = [raw('SOLD_OUT'), ...Array(30).fill(pageOnly('UNKNOWN_PAGE')), raw('AVAILABLE')];
  const t = setup(host);
  await t.run();

  assert.equal(t.logger.count(LogCode.PAGE_WAIT_TIMEOUT), 0, '10초 예산으로 끊겼다');
  assert.ok(t.time.now() > 10_000, '10초를 넘겨서 기다린 적이 없다');
  assert.equal(t.controller.status.state, WatchState.RESERVED);
});

test('차단 화면은 기다리지 않고 즉시 멈춘다', async () => {
  const host = new FakeHost(pageOnly('BLOCKED'));
  const t = setup(host);
  await t.run();

  assert.equal(host.parseCount, 1, '차단 화면을 붙들고 기다렸다');
  assert.equal(t.logger.count(LogCode.PAGE_WAIT_START), 0);
  assert.equal(t.controller.status.error, WatchError.BLOCKED);
  assert.equal(t.notifier.stopped.length, 1);
});

test('결과 0건은 기다리지 않고 다음 사이클로 간다', async () => {
  const host = new FakeHost(pageOnly('NO_TRAIN'), {
    onCycle: (h) => { if (h.requeryCount >= 2) t.controller.stop('테스트 종료'); },
  });
  const t = setup(host);
  await t.run();

  assert.equal(t.logger.count(LogCode.PAGE_WAIT_START), 0);
  assert.equal(host.requeryCount, 2);
});

test('로그인 화면이면 오류 상태로 중지한다', async () => {
  const host = new FakeHost(pageOnly('LOGIN_REQUIRED'));
  const t = setup(host);
  await t.run();

  assert.equal(t.controller.status.error, WatchError.LOGIN_REQUIRED);
  assert.equal(host.requeryCount, 0);
});

test('판독이 계속 깨지면 연속 오류로 세고 중지한다', async () => {
  const host = new FakeHost(BROKEN);
  const t = setup(host);
  await t.run();

  assert.equal(t.controller.status.error, WatchError.DOM_PARSE_ERROR);
  assert.equal(t.logger.count(LogCode.DOM_PARSE_ERROR), DEFAULT_WATCH_CONFIG.maxConsecutiveErrors);
});

test('갱신이 실패하면 연속 오류로 세고 중지한다', async () => {
  const host = new FakeHost(raw('SOLD_OUT'));
  host.outcome = { kind: 'FAILED', detail: '새로고침 실패', network: true };
  const t = setup(host);
  await t.run();

  assert.equal(t.controller.status.state, WatchState.ERROR);
  assert.equal(t.controller.status.error, WatchError.NETWORK_ERROR);
  assert.equal(host.requeryCount, DEFAULT_WATCH_CONFIG.maxConsecutiveErrors);
});

// ---------------------------------------------------------------- 로그인 (§27-1)

test('조회 결과 화면이어도 로그인 여부는 사이클마다 따로 확인한다', async () => {
  const host = new FakeHost(raw('SOLD_OUT'), {
    onCycle: (h) => { if (h.requeryCount >= 2) t.controller.stop('테스트 종료'); },
  });
  const t = setup(host);
  await t.run();

  // 끝까지 돈 사이클은 둘(첫 사이클 + 갱신 1번) → 확인도 두 번.
  // DOM 읽기 한 번이라 요청은 늘지 않는다. (§27-1)
  assert.equal(host.loginCount, 2);
});

test('감시 도중 로그인이 풀리면 멈추고 알린다', async () => {
  const host = new FakeHost(raw('SOLD_OUT'));
  host.loginResult = { state: 'LOGGED_OUT', detail: '로그인 href=/ticket/login' };
  const t = setup(host);
  await t.run();

  assert.equal(t.controller.status.error, WatchError.SESSION_EXPIRED);
  assert.equal(t.notifier.stopped.length, 1);
  assert.ok(t.logger.has(LogCode.LOGIN_STATE));
});

test('로그인 여부를 판정하지 못하면 감시를 이어간다', async () => {
  const host = new FakeHost(raw('SOLD_OUT'), {
    onCycle: (h) => { if (h.requeryCount >= 2) t.controller.stop('테스트 종료'); },
  });
  host.loginResult = { state: 'UNKNOWN', detail: '표시 없음' };
  const t = setup(host);
  await t.run();

  // 사이트 개편으로 마커를 놓쳤을 뿐인데 감시가 영영 시작되지 않는 편이 더 나쁘다. (대원칙 6)
  assert.notEqual(t.controller.status.error, WatchError.SESSION_EXPIRED);
  assert.equal(host.requeryCount, 2);
});

// ---------------------------------------------------------------- ★ 확장 고유

test('★ 조회 조건이 바뀌면 선택을 버리고 멈춘다', async () => {
  const host = new FakeHost(raw('SOLD_OUT'), {
    // 사용자가 사이트에서 날짜를 바꿔 다시 조회했다.
    onCycle: (h) => { h.sig = 'q2'; },
  });
  const t = setup(host);
  await t.run();

  assert.equal(t.controller.status.error, WatchError.QUERY_CHANGED);
  assert.equal(t.cleared(), 1, '체크해 둔 칸을 버리지 않았다');
  assert.ok(t.logger.has(LogCode.QUERY_CHANGED));
});

test('★ 조회 조건을 읽지 못하면 판정하지 않는다', async () => {
  const host = new FakeHost(raw('SOLD_OUT'), {
    onCycle: (h) => { if (h.requeryCount >= 2) t.controller.stop('테스트 종료'); },
  });
  host.querySig = async () => ({ ok: false });
  const t = setup(host);
  await t.run();

  assert.notEqual(t.controller.status.error, WatchError.QUERY_CHANGED);
  assert.equal(t.cleared(), 0);
});

test('★ 탭이 사라지면 즉시 멈추고 알린다', async () => {
  const host = new FakeHost(raw('SOLD_OUT'));
  host.outcome = { kind: 'TAB_GONE', detail: '탭이 사라졌습니다' };
  const t = setup(host);
  await t.run();

  assert.equal(t.controller.status.error, WatchError.TAB_GONE);
  assert.equal(host.requeryCount, 1, '탭이 없는데 새로고침을 되풀이했다');
  assert.equal(t.notifier.stopped.length, 1);
});

test('★ 중지는 즉시 먹힌다 — 다음 사이클을 기다리지 않는다', async () => {
  const host = new FakeHost(raw('SOLD_OUT'), {
    onCycle: (h) => { if (h.requeryCount >= 1) t.controller.stop('사용자가 멈춤'); },
  });
  const t = setup(host);
  await t.run();

  assert.equal(t.controller.isWatching, false);
  assert.equal(t.controller.status.state, WatchState.STOPPED);
  assert.equal(host.requeryCount, 1);
});

test('감시 중에 선택을 바꾸면 다음 사이클부터 반영된다', async () => {
  const host = new FakeHost(raw('SOLD_OUT', 'AVAILABLE'), {
    onCycle: (h) => {
      if (h.requeryCount === 1) {
        // 사용자가 특실로 갈아탔다. 재시작 없이 다음 사이클부터 봐야 한다.
        t.controller.updateSelection(selectionOf([{
          trainKey: keyOf({ trainNumber: '305', departureTime: '18:30' }),
          seatClass: SeatClass.FIRST_CLASS,
        }]));
      }
    },
  });
  const t = setup(host);
  await t.run();

  assert.equal(t.notifier.sent.length, 1);
  assert.equal(t.notifier.sent[0].match.seatClass, SeatClass.FIRST_CLASS);
  // 선택은 **사이클 머리에서** 읽는다. 갱신 도중에 바꾸면 그 사이클이 아니라
  // 다음 사이클부터 반영된다 — 그래서 새로고침이 두 번 나갔다. (안드로이드와 같다)
  assert.equal(host.requeryCount, 2);
});

test('알림이 꺼져 있으면 알리지 않지만 발견은 그대로다', async () => {
  const host = new FakeHost(raw('AVAILABLE'));
  const t = setup(host, { notificationEnabled: false });
  await t.run();

  assert.equal(t.notifier.sent.length, 0);
  assert.equal(t.controller.status.matches[0].train.trainNumber, '305');
  assert.equal(t.controller.status.matches[0].train.generalSeat, SeatStatus.AVAILABLE);
  assert.ok(t.logger.has(LogCode.NOTIFICATION_SKIPPED));
});

// ---------------------------------------------------------------- 자동 예매 (M3)
//
// **누르는 경로다.** 여기서 지키는 것은 하나로 요약된다 —
// *누른 뒤에 확인하고, 확인이 어긋나면 다른 방법으로 또 누르지 않고 사람에게 넘긴다.*
// (PLAN.md §E-2-3, 대원칙 2·3)

/** [FakeHost] 의 예매 계열 메서드를 갈아 끼운다. 넘기지 않은 것은 "잘 된다" 그대로다. */
function withReserve(host, {
  select = { result: 'SELECTED', detail: '일반실 선택됨', clicked: true },
  confirm = { result: 'CLICKED', detail: '[예매] 눌렀습니다', clicked: true, button: '예매', baseline: {} },
  observe = { kind: 'CLICKED', detail: '화면 전환', page: null },
  back = { kind: 'UPDATED', detail: '목록 1편성' },
} = {}) {
  host.selectCalls = [];
  host.confirmCalls = [];
  host.backCalls = 0;
  host.selectSeat = async (arg) => {
    host.selectCalls.push(arg);
    return typeof select === 'function' ? select(host) : select;
  };
  host.confirmReserve = async (arg) => {
    host.confirmCalls.push(arg);
    return typeof confirm === 'function' ? confirm(host) : confirm;
  };
  host.watchReserveOutcome = async () => (typeof observe === 'function' ? observe(host) : observe);
  host.goBack = async () => {
    host.backCalls++;
    return typeof back === 'function' ? back(host) : back;
  };
  return host;
}

test('★ 좌석이 열리면 1단계 → 2단계까지 누르고 RESERVED 로 끝난다', async () => {
  const host = withReserve(new FakeHost(raw('AVAILABLE')));
  const t = setup(host);
  await t.run();

  assert.equal(host.selectCalls.length, 1);
  assert.equal(host.selectCalls[0].trainNumber, '305');
  assert.equal(host.selectCalls[0].seatClass, SeatClass.GENERAL);
  assert.equal(host.confirmCalls.length, 1);

  assert.equal(t.controller.status.state, WatchState.RESERVED);
  assert.equal(t.controller.status.reserve.result, ReserveResult.CLICKED);
  assert.equal(t.controller.isWatching, false);
  assert.ok(t.logger.has(LogCode.RESERVE_START));
  assert.ok(t.logger.has(LogCode.RESERVE_SEAT_SELECTED));
  assert.ok(t.logger.has(LogCode.RESERVE_SUCCEEDED));
  assert.equal(t.notifier.reserved.length, 1);
});

test('★ 1단계가 먹지 않으면 2단계로 가지 않고 멈춘다 — 합성 클릭이 통하지 않는 사이트', async () => {
  const host = withReserve(new FakeHost(raw('AVAILABLE')), {
    select: { result: 'SEAT_NOT_SELECTED', detail: '선택 표시가 붙지 않았습니다', clicked: true },
  });
  const t = setup(host);
  await t.run();

  // **여기가 핵심이다.** 눌렀는데 안 먹었으면 2단계는 절대 부르지 않는다.
  assert.equal(host.confirmCalls.length, 0);
  assert.equal(t.controller.status.state, WatchState.MATCHED);
  assert.equal(t.controller.status.reserve.result, ReserveResult.SEAT_NOT_SELECTED);
  assert.equal(t.controller.status.reserve.stage, ReserveStage.SELECT);
  assert.ok(t.logger.has(LogCode.RESERVE_FAILED));
  assert.equal(t.controller.isWatching, false);
});

test('★ 편성을 다시 못 찾으면 누르지 않고 감시를 이어간다 — 화면이 그새 바뀐 것이다', async () => {
  const host = withReserve(new FakeHost(raw('AVAILABLE'), {
    onCycle: (h) => { if (h.requeryCount >= 3) t.controller.stop('테스트 종료'); },
  }), {
    select: { result: 'ROW_NOT_FOUND', detail: '편성을 찾지 못했습니다', clicked: false },
  });
  const t = setup(host);
  await t.run();

  // 스스로 멈추지 않았고, 2단계로는 한 번도 가지 않았다.
  assert.equal(host.requeryCount, 3);
  assert.equal(host.confirmCalls.length, 0);
  // **아무것도 안 눌렀으니 다음 사이클에 다시 시도한다.** 한 번 헛걸음했다고 포기하지 않는다.
  assert.ok(host.selectCalls.length >= 3, `다시 시도하지 않았다 (${host.selectCalls.length}회)`);
  assert.ok(t.logger.has(LogCode.RESERVE_FAILED));
});

test('★ 2단계 버튼이 [예매] 가 아니면 좌석만 골라 두고 인계한다', async () => {
  const host = withReserve(new FakeHost(raw('AVAILABLE')), {
    confirm: {
      result: 'NOT_ALLOWED',
      detail: '누를 수 있는 [예매] 버튼이 없습니다 (있는 것: 예약대기신청)',
      clicked: false,
    },
  });
  const t = setup(host);
  await t.run();

  assert.equal(t.controller.status.state, WatchState.SEAT_SELECTED);
  assert.equal(t.controller.status.reserve.result, ReserveResult.NOT_ALLOWED);
  assert.ok(t.logger.has(LogCode.RESERVE_HANDOVER));
  assert.equal(t.notifier.reserved.length, 1);
});

test('★ 모르는 안내가 뜨면 성공으로 치지 않고 인계한다', async () => {
  const host = withReserve(new FakeHost(raw('AVAILABLE')), {
    observe: { kind: 'NOTICE', detail: '알 수 없는 안내', page: { notice: '처음 보는 문구입니다' } },
  });
  const t = setup(host);
  await t.run();

  assert.equal(t.controller.status.state, WatchState.SEAT_SELECTED);
  assert.equal(t.controller.status.reserve.result, ReserveResult.UNKNOWN_NOTICE);
  // 실제 문구를 남겨야 `RESERVE_FAILED_MARKERS` 를 코레일 값으로 고칠 수 있다. (§38-8)
  assert.ok(t.logger.has(LogCode.RESERVE_NOTICE));
});

test('★ 잔여석없음이면 뒤로 가서 감시를 이어간다 — 발견으로 치지 않는다', async () => {
  const host = withReserve(new FakeHost(raw('AVAILABLE'), {
    onCycle: (h) => { if (h.requeryCount >= 3) t.controller.stop('테스트 종료'); },
  }), {
    observe: { kind: 'SOLD_OUT', detail: '잔여석없음', page: { notice: '잔여석이 없습니다' } },
  });
  const t = setup(host, { maxSoldOutRetries: 10 });
  await t.run();

  // 되돌린 뒤 다음 사이클에 **다시 눌러 본다.** 취소표에서는 흔한 일이다. (§19-2)
  assert.ok(host.backCalls >= 2, '되돌리지 않았다');
  assert.ok(host.confirmCalls.length >= 2, '다시 눌러 보지 않았다');
  assert.ok(t.logger.count(LogCode.RESERVE_SOLD_OUT) >= 2);
  assert.ok(t.logger.has(LogCode.RESERVE_DISMISSED));
  assert.ok(t.logger.has(LogCode.RESERVE_NOTICE));
  // 발견으로 치고 멈춰 버리면 취소표 감시가 성립하지 않는다.
  assert.equal(t.controller.status.state, WatchState.STOPPED);
});

test('★ 잔여석없음이 상한을 넘으면 그 칸은 더 누르지 않는다', async () => {
  const host = withReserve(new FakeHost(raw('AVAILABLE'), {
    onCycle: (h) => { if (h.requeryCount >= 6) t.controller.stop('테스트 종료'); },
  }), {
    observe: { kind: 'SOLD_OUT', detail: '잔여석없음', page: null },
  });
  const t = setup(host, { maxSoldOutRetries: 2 });
  await t.run();

  // 계속 실패하는 칸이면 요청만 늘어난다. 상한을 넘겨서는 안 된다. (대원칙 2)
  assert.equal(host.confirmCalls.length, 2, `상한을 넘겨 눌렀다 (${host.confirmCalls.length}회)`);
  assert.ok(t.logger.has(LogCode.RESERVE_SKIPPED));
  // 그래도 감시는 이어간다 — 다른 칸이 열릴 수 있다.
  assert.equal(host.requeryCount, 6);
});

test('★ 되돌아가지 못하면 그 화면에서 더 누르지 않고 멈춘다', async () => {
  const host = withReserve(new FakeHost(raw('AVAILABLE')), {
    observe: { kind: 'SOLD_OUT', detail: '잔여석없음', page: null },
    back: { kind: 'SETTLED', detail: '뒤로 갔지만 목록 화면이 아닙니다' },
  });
  const t = setup(host);
  await t.run();

  assert.equal(t.controller.status.state, WatchState.ERROR);
  assert.ok(t.logger.has(LogCode.RESERVE_DISMISS_FAILED));
  assert.equal(t.controller.isWatching, false);
});

test('★ [예매] 를 누르기 직전에 표시를 남기고, 끝나면 지운다', async () => {
  const host = withReserve(new FakeHost(raw('AVAILABLE')));
  const t = setup(host);
  await t.run();

  // 표시가 남은 채로 service worker 가 죽으면 부활이 **또 누르지 않는다.** (§E-3-2 3번)
  assert.equal(t.reserveMarks.length, 2);
  assert.equal(t.reserveMarks[0].trainNumber, '305');
  assert.equal(t.reserveMarks[0].seatClass, SeatClass.GENERAL);
  assert.equal(t.reserveMarks[1], null);
});

test('★ 1단계 전에는 표시를 남기지 않는다 — 좌석 고르기는 되돌릴 수 있다', async () => {
  const host = withReserve(new FakeHost(raw('AVAILABLE')), {
    select: { result: 'SEAT_NOT_SELECTED', detail: '안 붙음', clicked: true },
  });
  const t = setup(host);
  await t.run();

  assert.equal(t.reserveMarks.length, 0);
});
