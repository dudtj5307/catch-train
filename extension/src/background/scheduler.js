// 감시 주기. (android: watcher/ReloadScheduler.kt, 대원칙 7)
//
// 대원칙 7 의 알맹이는 셋이다 —
// **(a) 타이머가 감시 대상 페이지 안에 있으면 안 된다, (b) 간격은 사이클마다 무작위,
// (c) 중지가 즉시 먹혀야 한다.**
//
// 확장에서는 (a) 를 "content script 에 두지 않는다" 로 읽는다. service worker 의
// `await sleep()` 은 페이지 밖이고, [AbortSignal] 로 즉시 끊긴다. 셋 다 지켜진다.
// (PLAN.md §E-3-3)
//
// `setInterval` 을 쓰지 않는 것은 그대로다. 간격이 정확히 일정하면 자동화로 판단된다.

/** 취소로 끝났음을 알리는 신호. 오류가 아니다 — 감시 루프는 이것을 조용히 삼킨다. */
export class AbortError extends Error {
  constructor(message = '중지됨') {
    super(message);
    this.name = 'AbortError';
  }
}

/**
 * [ms] 만큼 기다린다. [signal] 이 끊기면 곧바로 [AbortError] 로 깨어난다.
 *
 * `chrome.alarms` 를 쓰지 않는 이유는 최소 30초이기 때문이다. 기본 간격이
 * 0.1~0.3초인 제품에서 주 타이머가 될 수 없다. (alarms 는 부활 그물로만 쓴다 — §E-3-2)
 */
export function sleep(ms, signal) {
  return new Promise((resolve, reject) => {
    if (signal && signal.aborted) {
      reject(new AbortError());
      return;
    }
    const timer = setTimeout(() => {
      if (signal) signal.removeEventListener('abort', onAbort);
      resolve();
    }, Math.max(0, ms));
    function onAbort() {
      clearTimeout(timer);
      reject(new AbortError());
    }
    if (signal) signal.addEventListener('abort', onAbort, { once: true });
  });
}

/** 조정 가능한 간격의 하한. 0 이면 대기 없이 곧바로 다음 사이클로 간다. */
export const MIN_INTERVAL_MS = 0;

/** 상한. 슬라이더와 직접 입력 모두 이 값까지만 받는다. (§11) */
export const MAX_INTERVAL_MS = 3_000;

/**
 * 처음 켰을 때의 간격. 취소표는 나오자마자 사라지므로 짧게 잡는다.
 * 짧은 간격을 오래 유지하면 차단 위험이 커진다 (대원칙 2).
 */
export const DEFAULT_MIN_INTERVAL_MS = 100;
export const DEFAULT_MAX_INTERVAL_MS = 300;

/** 슬라이더가 스냅되는 단위(0.1초). */
export const INTERVAL_STEP_MS = 100;

export function clampInterval(ms) {
  const value = Number.isFinite(ms) ? ms : DEFAULT_MIN_INTERVAL_MS;
  return Math.min(MAX_INTERVAL_MS, Math.max(MIN_INTERVAL_MS, Math.round(value)));
}

/** 최소값이 최대값보다 큰 상태를 허용하지 않는다. */
export function clampRange(minMs, maxMs) {
  const lo = clampInterval(Math.min(minMs, maxMs));
  const hi = clampInterval(Math.max(minMs, maxMs));
  return [lo, hi];
}

/** "0.1~0.3초" */
export function formatRange(minMs, maxMs) {
  const [lo, hi] = clampRange(minMs, maxMs);
  const s = (ms) => (ms / 1000).toFixed(1);
  return lo === hi ? `${s(lo)}초` : `${s(lo)}~${s(hi)}초`;
}

/**
 * 사이클마다 대기 시간을 새로 뽑고, 남은 시간을 흘려 주며 기다린다.
 *
 * 시계와 타이머를 통째로 갈아 끼울 수 있게 두었다. 테스트에서 실제로 0.3초씩
 * 자는 것을 기다릴 이유가 없고, 예산(§39)을 검사하려면 시간을 밀 수 있어야 한다.
 */
export class ReloadScheduler {
  #tickMs;
  #random;
  #sleep;

  constructor({ tickMs = 250, random = Math.random, sleepFn = sleep } = {}) {
    this.#tickMs = tickMs;
    this.#random = random;
    this.#sleep = sleepFn;
  }

  /** [minMs]~[maxMs] (양끝 포함) 에서 이번 사이클의 대기 시간을 뽑는다. */
  nextInterval(minMs, maxMs) {
    const [lo, hi] = clampRange(minMs, maxMs);
    if (hi <= lo) return lo;
    return lo + Math.floor(this.#random() * (hi - lo + 1));
  }

  /**
   * [intervalMs] 만큼 기다리며 남은 시간을 [onRemaining] 으로 알린다.
   * 팝업이 "다음 확인 약 N초 후" 를 보여줄 수 있게 하려는 것이다.
   */
  async wait(intervalMs, { onRemaining = () => {}, signal = undefined } = {}) {
    let remaining = clampInterval(intervalMs);
    onRemaining(remaining);
    while (remaining > 0) {
      const step = remaining < this.#tickMs ? remaining : this.#tickMs;
      await this.#sleep(step, signal);
      remaining -= step;
      onRemaining(remaining < 0 ? 0 : remaining);
    }
  }
}
