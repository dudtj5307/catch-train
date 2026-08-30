// 감시 로그. (android: watcher/WatchLog.kt, DESIGN.md §29)
//
// **코드 이름을 안드로이드와 같게 둔다.** 한쪽에서 본 로그를 다른 쪽 코드에서
// 바로 찾을 수 있어야 하고, 문서(§39-7 등)가 이 이름으로 "무엇이 보이면 정상인가" 를
// 설명한다. 이름을 바꾸면 그 문서가 통째로 거짓말이 된다.
//
// **chrome API 를 모른다.** 저장은 부르는 쪽(`index.js`)이 [onLog] 로 받아서 한다 —
// 로그 한 줄마다 storage 를 두들기면 사이클당 수십 번 쓰게 된다.

/** 로그 코드. 안드로이드 `LogCode` 중 **확장이 실제로 남기는 것만** 옮겼다. */
export const LogCode = Object.freeze({
  WATCH_START: 'WATCH_START',
  WATCH_STOP: 'WATCH_STOP',
  WATCH_PAUSED: 'WATCH_PAUSED',
  WATCH_RESUMED: 'WATCH_RESUMED',

  PAGE_LOAD_START: 'PAGE_LOAD_START',
  PAGE_LOAD_FINISHED: 'PAGE_LOAD_FINISHED',
  PAGE_SETTLE_TIMEOUT: 'PAGE_SETTLE_TIMEOUT',
  PAGE_LOAD_ERROR: 'PAGE_LOAD_ERROR',

  /** 페이지를 새로고침했다. **이 줄이 나가면 요청이 나간 것이다.** (§38-9) */
  RESEARCH_TRIGGERED: 'RESEARCH_TRIGGERED',

  /** 갱신이 목표 화면에 닿지 못했다. */
  RESEARCH_FAILED: 'RESEARCH_FAILED',

  /** 새로고침 뒤 목록이 다시 그려졌다. */
  PAGE_UPDATED: 'PAGE_UPDATED',

  BLOCKED_DETECTED: 'BLOCKED_DETECTED',

  DOM_PARSE_START: 'DOM_PARSE_START',
  DOM_PARSE_ERROR: 'DOM_PARSE_ERROR',
  DOM_WARNING: 'DOM_WARNING',

  /**
   * 화면이 아직 열차 목록이 아니라 **제자리에서 기다리기 시작했다.** (§39)
   *
   * 이 줄이 보이면 그 사이 [RESEARCH_TRIGGERED] 가 **한 줄도 없어야 한다** —
   * 대기 중에 새로고침하면 대기 순번이 날아간다. (§39-7)
   */
  PAGE_WAIT_START: 'PAGE_WAIT_START',
  PAGE_WAIT_TICK: 'PAGE_WAIT_TICK',
  PAGE_WAIT_DONE: 'PAGE_WAIT_DONE',
  PAGE_WAIT_TIMEOUT: 'PAGE_WAIT_TIMEOUT',

  TRAIN_COUNT: 'TRAIN_COUNT',
  SELECTION_CHECK: 'SELECTION_CHECK',
  MATCH_COUNT: 'MATCH_COUNT',
  MATCH_DETAIL: 'MATCH_DETAIL',
  NOTIFICATION_SENT: 'NOTIFICATION_SENT',
  NOTIFICATION_SKIPPED: 'NOTIFICATION_SKIPPED',
  NEXT_RELOAD: 'NEXT_RELOAD',
  PAGE_STATUS: 'PAGE_STATUS',
  LOGIN_STATE: 'LOGIN_STATE',

  /**
   * ★ 확장 고유 — content script 에 말을 걸지 못했다. (PLAN.md §E-6-1)
   *
   * **오류가 아니다.** 새로고침 직후에는 항상 몇 초 동안 이 상태이고,
   * 이것을 연속 오류로 세면 정상 동작만으로 감시가 죽는다.
   */
  CONTENT_ABSENT: 'CONTENT_ABSENT',

  /** ★ 확장 고유 — 감시하던 탭이 사라졌다. (§E-6-3 예외 16) */
  TAB_GONE: 'TAB_GONE',

  /**
   * ★ 확장 고유 — 사용자가 사이트에서 조회 조건을 바꿔 다시 조회했다. (§E-6-3 예외 17)
   * detail 에 남는 것은 **해시뿐이다.** 조회 조건 자체는 읽지도 쓰지도 않는다.
   */
  QUERY_CHANGED: 'QUERY_CHANGED',
});

/** 최근 [capacity] 건만 유지하는 로그 버퍼. */
export class WatchLogger {
  #entries = [];
  #capacity;
  #clock;

  /** @param onLog 한 줄이 쌓일 때마다 불린다. 저장·중계는 부르는 쪽의 몫이다. */
  constructor({ capacity = 300, clock = () => Date.now(), onLog = null } = {}) {
    this.#capacity = capacity;
    this.#clock = clock;
    this.onLog = onLog;
  }

  log(code, detail = '') {
    const entry = { at: this.#clock(), code, detail: detail == null ? '' : String(detail) };
    this.#entries.push(entry);
    if (this.#entries.length > this.#capacity) {
      this.#entries.splice(0, this.#entries.length - this.#capacity);
    }
    if (this.onLog) {
      try {
        this.onLog(entry, this.#entries);
      } catch {
        // 로그를 남기다 감시가 죽으면 안 된다. 중계 실패는 삼킨다.
      }
    }
    return entry;
  }

  /**
   * 저장해 둔 줄을 그대로 되살린다. (service worker 가 죽었다 깨어난 뒤)
   *
   * [log] 로 다시 넣지 않는 이유는 **시각 때문이다.** 그러면 예전 줄이 전부 "지금"
   * 으로 찍혀서, 대기가 얼마나 이어졌는지(§39-7)를 로그에서 읽을 수 없게 된다.
   */
  restore(entries) {
    for (const entry of entries) {
      if (!entry || typeof entry.code !== 'string') continue;
      this.#entries.push({
        at: Number.isFinite(entry.at) ? entry.at : this.#clock(),
        code: entry.code,
        detail: typeof entry.detail === 'string' ? entry.detail : '',
      });
    }
    if (this.#entries.length > this.#capacity) {
      this.#entries.splice(0, this.#entries.length - this.#capacity);
    }
  }

  /** 최신이 뒤. 얕은 복사라 부르는 쪽이 들고 있어도 버퍼가 흔들리지 않는다. */
  entries() {
    return this.#entries.slice();
  }

  /** 그 코드가 한 번이라도 남았는가. (테스트와 진단용) */
  has(code) {
    return this.#entries.some((e) => e.code === code);
  }

  count(code) {
    return this.#entries.filter((e) => e.code === code).length;
  }

  clear() {
    this.#entries = [];
  }

  dump() {
    return this.#entries.map(formatEntry).join('\n');
  }
}

/** "[14:03:11] PAGE_WAIT_START=UNKNOWN_PAGE - 최대 180초 기다림" */
export function formatEntry(entry) {
  const stamp = timeText(entry.at);
  return entry.detail ? `[${stamp}] ${entry.code}=${entry.detail}` : `[${stamp}] ${entry.code}`;
}

function timeText(at) {
  const d = new Date(at);
  const pad = (n) => String(n).padStart(2, '0');
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}
