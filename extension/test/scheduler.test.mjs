// 감시 주기. (android: watcher/ReloadSchedulerTest.kt)
//
// 지켜야 하는 것은 셋이다 — **범위 안에서 무작위 / 뒤집힌 입력에도 안전 / 즉시 중지.**
// (대원칙 7, PLAN.md §E-3-3)

import test from 'node:test';
import assert from 'node:assert/strict';

import {
  AbortError,
  DEFAULT_MAX_INTERVAL_MS,
  DEFAULT_MIN_INTERVAL_MS,
  MAX_INTERVAL_MS,
  ReloadScheduler,
  TYPICAL_CYCLE_OVERHEAD_MS,
  clampRange,
  formatRange,
  formatRate,
  requestsPerMinute,
  sleep,
} from '../src/background/scheduler.js';

test('간격은 범위 안에서 뽑힌다', () => {
  const scheduler = new ReloadScheduler();
  for (let i = 0; i < 200; i++) {
    const value = scheduler.nextInterval(100, 300);
    assert.ok(value >= 100 && value <= 300, `범위를 벗어났다: ${value}`);
  }
});

test('간격은 사이클마다 새로 뽑는다 — 같은 값만 나오면 자동화로 보인다', () => {
  const scheduler = new ReloadScheduler();
  const values = new Set();
  for (let i = 0; i < 200; i++) values.add(scheduler.nextInterval(100, 300));
  assert.ok(values.size > 1, '늘 같은 값이 나왔다');
});

test('최소와 최대가 뒤집혀 들어와도 안전하다', () => {
  const scheduler = new ReloadScheduler();
  const value = scheduler.nextInterval(500, 200);
  assert.ok(value >= 200 && value <= 500);
  assert.deepEqual(clampRange(500, 200), [200, 500]);
});

test('상한을 넘겨 들어온 값은 잘린다', () => {
  assert.deepEqual(clampRange(0, MAX_INTERVAL_MS + 5_000), [0, MAX_INTERVAL_MS]);
});

test('범위 문구는 사람이 읽는 초 단위다', () => {
  assert.equal(formatRange(100, 300), '0.1~0.3초');
  assert.equal(formatRange(2_000, 2_000), '2.0초');
});

// --- 분당 요청 수 ---------------------------------------------------------
//
// 실사용 로그에서 간격이 0.1~0.3초인 채로 돌고 있었다. 갱신이 새로고침(F5)이 된 뒤로는
// 사이클의 대부분이 새로고침 자체라, **간격만 봐서는 얼마나 두들기는지 알 수 없다.**
// 그래서 팝업이 분당 요청 수를 보여 준다. 그 계산을 여기서 지킨다. (대원칙 2)

test('분당 요청 수는 새로고침 비용을 포함한다 — 대기 시간만으로 세지 않는다', () => {
  // 대기가 0 이어도 새로고침(1초)이 있으므로 분당 60회를 넘을 수 없다.
  assert.equal(requestsPerMinute(0, 0), 60);
  // 대기 0.2초(평균)를 더하면 1.2초 사이클 → 50회.
  assert.equal(requestsPerMinute(200, 200), 50);
});

test('대기를 두 배로 늘려도 요청 수는 절반이 되지 않는다', () => {
  const before = requestsPerMinute(300, 300);
  const after = requestsPerMinute(600, 600);
  assert.ok(after > before / 2, `절반 이하로 떨어졌다: ${before} → ${after}`);
});

test('기본값은 SRT 시절(0.1~0.3초)이 아니다', () => {
  assert.ok(
    DEFAULT_MIN_INTERVAL_MS >= 300,
    `기본 하한이 ${DEFAULT_MIN_INTERVAL_MS}ms 다 — 새로고침 갱신에는 너무 짧다 (§38-9)`,
  );
  assert.ok(DEFAULT_MAX_INTERVAL_MS >= DEFAULT_MIN_INTERVAL_MS);
});

test('상한까지 물러서면 요청 수가 실제로 크게 준다 — 슬라이더가 쓸모 있어야 한다', () => {
  const fastest = requestsPerMinute(0, 0);
  const slowest = requestsPerMinute(MAX_INTERVAL_MS, MAX_INTERVAL_MS);
  assert.ok(slowest <= fastest / 3, `상한이 너무 낮다: ${fastest} → ${slowest}`);
});

test('요청 수 문구', () => {
  assert.equal(formatRate(0, 0), '분당 약 60회');
  assert.equal(TYPICAL_CYCLE_OVERHEAD_MS, 1_000);
});

test('남은 시간을 흘려 준다 — 팝업이 "다음 확인 N초" 를 그린다', async () => {
  const seen = [];
  const scheduler = new ReloadScheduler({ tickMs: 100, sleepFn: async () => {} });
  await scheduler.wait(300, { onRemaining: (ms) => seen.push(ms) });

  assert.deepEqual(seen, [300, 200, 100, 0]);
});

test('중지는 즉시 먹힌다 — 남은 시간을 다 기다리지 않는다', async () => {
  const controller = new AbortController();
  const started = Date.now();
  const waiting = sleep(5_000, controller.signal);
  controller.abort();

  await assert.rejects(waiting, (e) => e instanceof AbortError);
  assert.ok(Date.now() - started < 1_000, '중지가 늦었다');
});

test('이미 중지된 뒤에 기다리려 하면 곧바로 깨어난다', async () => {
  const controller = new AbortController();
  controller.abort();
  await assert.rejects(sleep(5_000, controller.signal), (e) => e instanceof AbortError);
});
