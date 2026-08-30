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
import { WatchError, WatchState } from '../src/background/watch-state.js';

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
}

class RecordingNotifier {
  sent = [];
  stopped = [];

  notifyMatch(match, extraCount) {
    this.sent.push({ match, extraCount });
  }

  notifyWatchStopped(title, body) {
    this.stopped.push([title, body]);
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
  const controller = new WatchController({
    notifier,
    logger,
    scheduler,
    clock: time.now,
    sleepFn: time.sleep,
    onSelectionInvalid: () => { selectionCleared++; },
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
  };
}

// ---------------------------------------------------------------- 발견과 알림

test('선택한 좌석이 열리면 알림을 보내고 감시를 멈춘다', async () => {
  const host = new FakeHost(raw('AVAILABLE'));
  const t = setup(host);
  await t.run();

  assert.equal(t.notifier.sent.length, 1);
  assert.equal(t.notifier.sent[0].match.seatClass, SeatClass.GENERAL);
  assert.equal(t.controller.status.state, WatchState.MATCHED);
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
  const host = new FakeHost(raw('AVAILABLE'), {
    onCycle: (h) => {
      // 열림 → 열림(같은 칸) → 매진 → 열림
      if (h.requeryCount === 2) h.fallback = raw('SOLD_OUT');
      if (h.requeryCount === 3) h.fallback = raw('AVAILABLE');
      if (h.requeryCount >= 4) t.controller.stop('테스트 종료');
    },
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
  assert.equal(t.controller.status.state, WatchState.MATCHED);
});

test('★ content script 가 없는 동안은 기다린다 — 오류로 세지 않는다', async () => {
  const host = new FakeHost(raw('AVAILABLE'));
  host.reads = [ABSENT, ABSENT, ABSENT, raw('AVAILABLE')];
  const t = setup(host);
  await t.run();

  assert.ok(t.logger.has(LogCode.CONTENT_ABSENT));
  // 새로고침 직후마다 정상적으로 겪는 상태다. 오류로 세면 감시가 3사이클에 죽는다.
  assert.equal(t.logger.count(LogCode.DOM_PARSE_ERROR), 0);
  assert.equal(t.controller.status.state, WatchState.MATCHED);
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
  assert.equal(t.controller.status.state, WatchState.MATCHED);
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
  assert.equal(t.controller.status.state, WatchState.MATCHED);
  assert.ok(t.logger.has(LogCode.NOTIFICATION_SKIPPED));
});

test('M2 는 아무것도 누르지 않는다 — 발견해도 host 에 클릭 요청이 없다', async () => {
  const host = new FakeHost(raw('AVAILABLE'));
  // 클릭 계열 메서드가 하나라도 불리면 실패한다. 실측 전에 만들지 않기로 한 자리다.
  for (const name of ['selectSeat', 'confirmReserve', 'dismissReserveResult']) {
    host[name] = () => assert.fail(`${name} 을 불렀다 — M2 에는 클릭이 없다`);
  }
  const t = setup(host);
  await t.run();

  assert.equal(t.controller.status.state, WatchState.MATCHED);
  assert.equal(t.controller.status.matches[0].train.trainNumber, '305');
  assert.equal(
    t.controller.status.matches[0].train.generalSeat,
    SeatStatus.AVAILABLE,
  );
});
