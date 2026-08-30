// 감시 주기. (android: watcher/ReloadSchedulerTest.kt)
//
// 지켜야 하는 것은 셋이다 — **범위 안에서 무작위 / 뒤집힌 입력에도 안전 / 즉시 중지.**
// (대원칙 7, PLAN.md §E-3-3)

import test from 'node:test';
import assert from 'node:assert/strict';

import {
  AbortError,
  MAX_INTERVAL_MS,
  ReloadScheduler,
  clampRange,
  formatRange,
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
