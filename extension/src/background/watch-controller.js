// 감시 엔진. (android: watcher/WatchController.kt — 같은 순서, 같은 이름)
//
// 핵심 원칙 (대원칙 8)
//  - 탭은 [PageHost] 뒤에 숨어 있다. 이 파일은 `chrome.*` 를 하나도 부르지 않는다.
//    그래서 브라우저 없이 통째로 테스트할 수 있다. (§34-1)
//  - DOM 판독(`content/ktx/parse.js`)과 선택 판정(`domain/selection-engine.js`)은
//    서로 모른다. (§34-2)
//  - 감시 주기는 **페이지 밖**(service worker)에서 관리한다. (대원칙 7, §E-3-3)
//  - 선택한 좌석이 열리면 알리고 감시를 멈춘다. (§34-4)
//
// 한 사이클 (PLAN.md §E-5):
//   새로고침 → 화면 확정까지 판독 → 로그인·조회조건 확인 → 판정 → 알림 → 예매 → 대기
//
// ## 누르는 것 (M3)
//
// 좌석을 발견하면 **좌석 칸(1단계) → 하단 바 [예매](2단계)** 까지 누르고 멈춘다.
// 결제는 사람이 한다 (대원칙 3). 자동 클릭을 켜 두어도 다음 셋은 지켜진다.
//
//  - **누른 뒤에는 반드시 확인한다.** 확장의 클릭은 `isTrusted=false` 라 사이트가
//    무시할 수 있다 (PLAN.md §E-2-1). `active` 가 붙지 않았으면 2단계로 가지 않는다.
//  - **실패한 자리에서 다시 누르지 않는다.** 다른 방법으로 한 번 더 누르는 것은
//    대원칙 2 가 금지하는 재시도다. 폴백의 방향은 **인계** 하나뿐이다. (§E-2-3)
//  - **[예매] 는 완전일치 허용목록으로만.** `예약대기신청` 은 사용자가 고른 것이
//    아니다. 허용목록에 없으면 좌석만 골라 둔 채 멈춘다. (§38-6-1)

import { DomParseError, PageStatus, isSettled } from '../domain/page-snapshot.js';
import { describeMatch, matchKeyOf } from '../domain/match.js';
import { match as matchSelection } from '../domain/selection-engine.js';
import { seatClassLabel } from '../domain/seat-class.js';
import { trainSummary } from '../domain/train.js';
import {
  selectionIsEmpty,
  selectionSize,
  selectionTrainCount,
} from '../domain/watch-selection.js';
import { LogCode } from './logger.js';
import { AbortError, ReloadScheduler, formatRange, formatRate, sleep } from './scheduler.js';
import { DEFAULT_WATCH_CONFIG } from './watch-config.js';
import {
  INITIAL_STATUS,
  ReserveResult,
  ReserveStage,
  WatchError,
  WatchState,
  reserveResultLabel,
  watchErrorGuide,
} from './watch-state.js';

/**
 * 한 사이클에서 예매를 시도한 뒤 감시 루프가 무엇을 할지.
 *
 *  - `NONE`     : 누르지 않았다. 예전(M2)처럼 발견 처리로 흘러간다
 *  - `CONTINUE` : 눌렀거나 건너뛰었고, **감시는 이어간다** (잔여석없음 등)
 *  - `STOPPED`  : 루프가 여기서 끝났다. 부르는 쪽은 곧바로 return 한다
 */
const ReserveStep = Object.freeze({
  NONE: 'NONE',
  CONTINUE: 'CONTINUE',
  STOPPED: 'STOPPED',
});

/** 화면을 기다리는 동안 진행 로그를 남기는 주기(판독 횟수). 500ms 간격이니 약 5초에 한 줄. */
const WAIT_TICK_READS = 10;

const MAX_LOGGED_WARNINGS = 3;

/** 감시 도중 로그인이 풀렸을 때의 안내. 화면과 알림에 같은 문구를 쓴다. */
const LOGGED_OUT_GUIDE =
  '코레일 로그인이 풀려 감시를 멈췄습니다. 코레일 탭에서 다시 로그인한 뒤 감시를 시작하세요.';

/** 기다리는 중임을 알리는 문구의 머리. 빠져나갈 때 이 문구만 골라 지운다. */
const WAITING_MESSAGE_PREFIX = '화면을 기다리는 중';

export class WatchController {
  #notifier;
  #logger;
  #scheduler;
  #clock;
  #sleep;
  #onStatus;
  #onSelectionInvalid;
  #onReserving;

  #status = { ...INITIAL_STATUS };
  #host = null;
  #config = { ...DEFAULT_WATCH_CONFIG };
  #selection = { seats: [] };
  #abort = null;
  #loop = null;

  /** 이미 알린 (열차, 좌석등급) 조합. 문자열 키다. (§20) */
  #notifiedKeys = new Set();

  /**
   * **이미 눌러 본 칸.** 같은 칸을 두 번 잡으려 들지 않는다.
   * 예외는 "잔여석없음" 하나뿐이고, 그때는 여기서 빼서 다음 사이클에 다시 눌러 본다.
   */
  #reserveAttempts = new Set();

  /** 칸마다 "잔여석없음" 을 몇 번 겪었나. [maxSoldOutRetries] 를 넘으면 더 안 누른다. */
  #soldOutCounts = new Map();

  /**
   * **이번 감시에서 조회 결과 화면에 한 번이라도 닿아 봤는가.** (§39)
   * [#readSettledSnapshot] 이 얼마나 오래 기다릴지를 이 값으로 가른다.
   */
  #hasSeenList = false;

  /**
   * 감시를 시작할 때의 조회 조건 서명. **해시뿐이고 원문은 갖지 않는다.**
   * 조회 조건은 앱이 갖지 않는다 (대원칙 4) — 같은지 다른지만 본다. (§E-6-3 예외 17)
   */
  #querySig = null;

  constructor({
    notifier,
    logger,
    scheduler = new ReloadScheduler(),
    clock = () => Date.now(),
    sleepFn = sleep,
    onStatus = () => {},
    onSelectionInvalid = () => {},
    onReserving = async () => {},
  }) {
    this.#notifier = notifier;
    this.#logger = logger;
    this.#scheduler = scheduler;
    this.#clock = clock;
    this.#sleep = sleepFn;
    this.#onStatus = onStatus;
    this.#onSelectionInvalid = onSelectionInvalid;
    this.#onReserving = onReserving;
  }

  get status() {
    return this.#status;
  }

  get isWatching() {
    return this.#abort !== null && !this.#abort.signal.aborted;
  }

  get tabId() {
    return this.#host ? this.#host.tabId : null;
  }

  /**
   * 지금 루프가 쓰고 있는 파라미터. **읽기 전용으로만 쓴다.**
   *
   * 팝업이 "저장된 간격" 과 "실제로 돌고 있는 간격" 을 구분해 보여주려고 있다.
   * 여기 값을 바꿔서 루프에 밀어 넣지 말 것 — 이번 사이클의 대기 시간은 이미 뽑혀
   * 있고, 중간에 갈아 끼우면 요청이 한 번 더 나가는 재시작이 된다 (대원칙 2).
   */
  get config() {
    return { ...this.#config };
  }

  /** 루프가 끝날 때까지. **테스트용이다** — 실제 동작은 아무도 기다리지 않는다. */
  finished() {
    return this.#loop ?? Promise.resolve();
  }

  // ---------------------------------------------------------------- 제어

  /**
   * 감시를 시작한다. 첫 사이클은 **새로고침하지 않는다.**
   *
   * 사용자는 방금 자기 손으로 조회한 화면을 보고 있고, 새로고침은 그 화면을 통째로
   * 다시 만든다. 요청도 한 번 아낀다. (§E-5)
   */
  start({ host, selection, config = DEFAULT_WATCH_CONFIG }) {
    this.#cancelLoop();
    this.#host = host;
    this.#selection = selection;
    this.#config = config;
    this.#notifiedKeys.clear();
    this.#reserveAttempts.clear();
    this.#soldOutCounts.clear();
    this.#hasSeenList = false;
    this.#querySig = null;
    this.#status = { ...INITIAL_STATUS, state: WatchState.LOADING, tabId: host.tabId };
    this.#emit();

    this.#logger.log(
      LogCode.WATCH_START,
      `선택 ${selectionSize(selection)}칸 (열차 ${selectionTrainCount(selection)}편성) ` +
        // **간격이 아니라 분당 요청 수가 차단을 부른다.** 간격만 적어 두면
        // "0.3초니까 괜찮겠지" 로 읽힌다 — 사이클의 대부분은 새로고침 자체다. (대원칙 2)
        `간격=${formatRange(config.minIntervalMs, config.maxIntervalMs)} 랜덤 ` +
        `(${formatRate(config.minIntervalMs, config.maxIntervalMs)})`,
    );

    this.#abort = new AbortController();
    this.#loop = this.#runLoop(true).catch((e) => {
      if (e instanceof AbortError) return;
      // 루프에서 새는 예외는 감시가 조용히 죽는 자리다. 반드시 남긴다.
      this.#logger.log(LogCode.WATCH_STOP, `예상치 못한 오류: ${(e && e.message) || e}`);
      this.#finish({ state: WatchState.ERROR, message: (e && e.message) || String(e) });
    });
    return this.#loop;
  }

  /** [감시 종료]. 중지는 즉시 먹혀야 한다 (대원칙 7-c). */
  stop(reason = '') {
    const wasWatching = this.isWatching;
    this.#cancelLoop();
    if (wasWatching) this.#logger.log(LogCode.WATCH_STOP, reason);
    this.#update({
      state: WatchState.STOPPED,
      nextCheckInMs: null,
      message: reason || null,
    });
  }

  /**
   * 감시 중에 사용자가 체크를 바꾼 경우. 다음 사이클부터 새 선택으로 본다.
   * 재시작하지 않는다 — 재시작하면 요청이 한 번 더 나가고 알림 이력도 사라진다.
   */
  updateSelection(selection) {
    this.#selection = selection;
    // 더 이상 보지 않기로 한 칸의 이력은 지운다. 다시 체크했을 때 알림이 나오도록.
    const kept = new Set(
      selection.seats.map((seat) => matchKeyOf({
        train: { trainNumber: seat.trainKey.trainNumber, departureTime: seat.trainKey.departureTime },
        seatClass: seat.seatClass,
      })),
    );
    for (const key of [...this.#notifiedKeys]) if (!kept.has(key)) this.#notifiedKeys.delete(key);
    // 더 이상 보지 않기로 한 칸의 시도 이력도 같이 지운다. 다시 체크하면 새로 눌러 본다.
    for (const key of [...this.#reserveAttempts]) if (!kept.has(key)) this.#reserveAttempts.delete(key);
    for (const key of [...this.#soldOutCounts.keys()]) if (!kept.has(key)) this.#soldOutCounts.delete(key);
  }

  #cancelLoop() {
    if (this.#abort) this.#abort.abort();
    this.#abort = null;
  }

  #emit() {
    this.#onStatus(this.#status);
  }

  #update(patch) {
    this.#status = { ...this.#status, ...patch };
    this.#emit();
  }

  get #signal() {
    return this.#abort ? this.#abort.signal : undefined;
  }

  #throwIfAborted() {
    if (!this.#abort || this.#abort.signal.aborted) throw new AbortError();
  }

  // ---------------------------------------------------------------- 감시 루프

  async #runLoop(skipFirstRefresh) {
    const config = this.#config;
    let skipRefresh = skipFirstRefresh;
    let consecutiveErrors = 0;
    let consecutiveUnknownPages = 0;

    // 시작 시점의 조회 조건 서명. 못 읽으면 null 이고, 그러면 비교하지 않는다.
    this.#querySig = await this.#readQuerySig();

    for (;;) {
      this.#throwIfAborted();

      // 사용자가 감시 도중에 체크를 바꿀 수 있으므로 사이클마다 다시 읽는다.
      const selection = this.#selection;
      if (selectionIsEmpty(selection)) {
        this.#logger.log(LogCode.WATCH_STOP, '선택된 열차가 없습니다');
        this.#finish({
          state: WatchState.STOPPED,
          message: '선택된 열차가 없어 감시를 멈췄습니다.',
        });
        return;
      }

      // 1) 페이지 갱신 -------------------------------------------------
      if (skipRefresh) {
        skipRefresh = false;
        this.#logger.log(LogCode.PAGE_LOAD_START, '현재 화면 분석 (새로고침 없음)');
      } else {
        this.#update({ state: WatchState.LOADING });
        this.#logger.log(LogCode.PAGE_LOAD_START, '새로고침(F5)');

        const outcome = await this.#host.requery({
          timeoutMs: config.pageTimeoutMs,
          settleTimeoutMs: config.researchSettleMs,
          pollMs: config.researchPollMs,
          signal: this.#signal,
          // 새로고침이 **실제로 나간 즉시** 기록한다. "갱신하지 않은 것" 과
          // "갱신했는데 결과가 같은 것" 을 로그로 가르기 위한 줄이다. (§39-7)
          onReload: (detail) => this.#logger.log(LogCode.RESEARCH_TRIGGERED, detail),
        });
        this.#throwIfAborted();

        if (outcome.kind === 'UPDATED') {
          this.#logger.log(LogCode.PAGE_UPDATED, outcome.detail);
        } else if (outcome.kind === 'SETTLED') {
          // 조회 결과가 실제로 0건일 수도 있으므로 일단 분석해 본다. (대원칙 6)
          this.#logger.log(LogCode.PAGE_SETTLE_TIMEOUT, outcome.detail);
        } else if (outcome.kind === 'TAB_GONE') {
          // 감시 대상이 없어졌다. 확정이므로 재시도하지 않는다. (§E-6-3 예외 16)
          this.#logger.log(LogCode.TAB_GONE, outcome.detail);
          this.#notifyStopped('감시하던 탭이 닫혔습니다', watchErrorGuide(WatchError.TAB_GONE));
          this.#fail(WatchError.TAB_GONE, watchErrorGuide(WatchError.TAB_GONE));
          return;
        } else {
          consecutiveErrors++;
          this.#logger.log(LogCode.PAGE_LOAD_ERROR, `${outcome.detail} (${consecutiveErrors})`);
          const error = outcome.network ? WatchError.NETWORK_ERROR : WatchError.PAGE_LOAD_ERROR;
          if (consecutiveErrors >= config.maxConsecutiveErrors) {
            this.#fail(error, `${watchErrorGuide(error)} (연속 ${consecutiveErrors}회)`);
            return;
          }
          this.#update({ error, message: watchErrorGuide(error) });
          await this.#waitForNextCycle(config);
          continue;
        }

        if (config.settleDelayMs > 0) await this.#sleep(config.settleDelayMs, this.#signal);
      }

      // 2) 판독. 화면이 확정될 때까지 제자리에서 다시 읽는다. (§39) -------
      this.#update({ state: WatchState.ANALYZING });
      this.#logger.log(LogCode.DOM_PARSE_START);

      let snapshot;
      try {
        snapshot = await this.#readSettledSnapshot(config);
      } catch (e) {
        if (!(e instanceof DomParseError)) throw e;
        consecutiveErrors++;
        this.#logger.log(LogCode.DOM_PARSE_ERROR, e.message);
        if (consecutiveErrors >= config.maxConsecutiveErrors) {
          this.#fail(WatchError.DOM_PARSE_ERROR, watchErrorGuide(WatchError.DOM_PARSE_ERROR));
          return;
        }
        this.#update({ error: WatchError.DOM_PARSE_ERROR, message: e.message });
        await this.#waitForNextCycle(config);
        continue;
      }

      consecutiveErrors = 0;
      this.#logger.log(LogCode.PAGE_STATUS, snapshot.status);
      snapshot.warnings.slice(0, MAX_LOGGED_WARNINGS)
        .forEach((w) => this.#logger.log(LogCode.DOM_WARNING, w));

      if (snapshot.status === PageStatus.LOGIN_REQUIRED) {
        this.#fail(WatchError.LOGIN_REQUIRED, watchErrorGuide(WatchError.LOGIN_REQUIRED));
        return;
      }
      if (snapshot.status === PageStatus.SESSION_EXPIRED) {
        this.#fail(WatchError.SESSION_EXPIRED, watchErrorGuide(WatchError.SESSION_EXPIRED));
        return;
      }
      if (snapshot.status === PageStatus.BLOCKED) {
        // 차단된 뒤에 계속 조회하면 차단이 길어진다. 재시도 없이 멈춘다. (대원칙 2)
        this.#logger.log(LogCode.BLOCKED_DETECTED, snapshot.url);
        this.#notifyStopped('접속이 차단되어 감시를 멈췄습니다', watchErrorGuide(WatchError.BLOCKED));
        this.#fail(WatchError.BLOCKED, watchErrorGuide(WatchError.BLOCKED));
        return;
      }
      if (snapshot.status === PageStatus.UNKNOWN_PAGE) {
        // 여기 닿았다는 것은 [#readSettledSnapshot] 이 예산을 다 쓰도록 화면이
        // 목록이 되지 않았다는 뜻이다. **그제서야** 한 번 센다. (§39-5)
        consecutiveUnknownPages++;
        if (consecutiveUnknownPages >= config.maxUnknownPages) {
          this.#fail(WatchError.UNKNOWN_PAGE, watchErrorGuide(WatchError.UNKNOWN_PAGE));
          return;
        }
        this.#update({
          lastCheckedAt: this.#clock(),
          error: WatchError.UNKNOWN_PAGE,
          message: watchErrorGuide(WatchError.UNKNOWN_PAGE),
        });
        await this.#waitForNextCycle(config);
        continue;
      }

      // NO_TRAIN / TRAIN_LIST — 조회 결과 화면에 닿았다.
      consecutiveUnknownPages = 0;
      this.#hasSeenList = true;

      // 2-1) 로그인이 아직 살아 있는가. (§27-1) DOM 읽기라 요청이 나가지 않는다.
      if (!(await this.#ensureStillLoggedIn(config))) return;

      // 2-2) 사용자가 조회 조건을 바꾸지 않았는가. (§E-6-3 예외 17)
      if (!(await this.#ensureSameQuery())) return;

      // 3) 선택 판정 ----------------------------------------------------
      this.#logger.log(LogCode.TRAIN_COUNT, String(snapshot.trains.length));
      this.#logger.log(LogCode.SELECTION_CHECK);
      const result = matchSelection(snapshot.trains, selection);
      this.#logger.log(LogCode.MATCH_COUNT, String(result.matches.length));

      this.#update({
        lastCheckedAt: this.#clock(),
        cycleCount: this.#status.cycleCount + 1,
        trainCount: snapshot.trains.length,
        foundCount: result.matches.length,
        matches: result.matches,
        searchDate: snapshot.searchDate,
        error: null,
        message: result.reason,
      });

      // 4) 알림 + 중복 방지 (§20) ----------------------------------------
      if (result.matched) {
        this.#notifyMatches(
          result.matches.filter((m) => !this.#notifiedKeys.has(matchKeyOf(m))),
          config,
        );
      }
      // 지금 화면에서 더 이상 열려 있지 않은 키는 잊는다. 다시 열리면 새로 알린다.
      const open = new Set(result.matches.map(matchKeyOf));
      for (const key of [...this.#notifiedKeys]) if (!open.has(key)) this.#notifiedKeys.delete(key);

      // 4-1) 자동 예매 (M3). **감시에서 페이지를 누르는 유일한 자리다.**
      if (result.matched) this.#logger.log(LogCode.MATCH_DETAIL, describeMatch(result.matches[0]));

      const step = await this.#tryReserve(result.matches, config);
      this.#throwIfAborted();
      if (step === ReserveStep.STOPPED) return;
      if (step === ReserveStep.CONTINUE) {
        // 눌렀지만 감시를 이어간다 (잔여석없음 등). 발견으로 치지 않는다. (§19-2)
        await this.#waitForNextCycle(config);
        continue;
      }

      if (result.matched && config.stopOnMatch) {
        this.#logger.log(LogCode.WATCH_STOP, '좌석 발견');
        this.#finish({
          state: WatchState.MATCHED,
          message: `${describeMatch(result.matches[0])} — 코레일 화면에서 예매하세요.`,
        });
        return;
      }

      // 5) 다음 사이클까지 대기 ------------------------------------------
      await this.#waitForNextCycle(config);
    }
  }

  // ---------------------------------------------------------------- 자동 예매 (M3)

  /**
   * ★ **좌석 칸(1단계) → 하단 바 [예매](2단계).** 감시가 페이지를 누르는 유일한 자리다.
   *
   * **끄는 스위치는 없다.** 좌석이 열리면 누르는 것이 이 도구가 하는 일이다
   * (`watch-config.js` 머리말). 대신 누르지 않는 경우가 아래처럼 좁게 정해져 있다.
   *
   * 누르지 않는 경우:
   *  - 이미 그 칸에 시도했다. **같은 칸을 두 번 잡으려 들지 않는다**
   *  - "잔여석없음" 을 [maxSoldOutRetries] 회 겪었다
   *  - 화면에서 그 편성이나 칸을 확실히 특정하지 못했다 (그새 매진 포함)
   *  - 2단계 버튼이 [예매] 가 아니다 (`예약대기신청` / `입석+좌석 예매`)
   *
   * **실패해도 그 자리에서 다시 누르지 않는다.** 알림은 이미 나갔으므로 사용자가 직접
   * 예매하면 되고, 잘못 누르는 것보다 안 누르는 편이 안전하다. (대원칙 2, 3)
   * 예외는 "잔여석없음" 하나뿐 — 그때는 목록으로 되돌리고 다음 사이클에 다시 본다.
   */
  async #tryReserve(matches, config) {
    if (matches.length === 0) return ReserveStep.NONE;

    const candidate = matches.find((m) => {
      const key = matchKeyOf(m);
      return !this.#reserveAttempts.has(key) &&
        (this.#soldOutCounts.get(key) ?? 0) < config.maxSoldOutRetries;
    });
    if (!candidate) {
      this.#logger.log(LogCode.RESERVE_SKIPPED, '이미 눌러 본 칸뿐입니다');
      return ReserveStep.CONTINUE;
    }

    this.#reserveAttempts.add(matchKeyOf(candidate));
    const label = seatClassLabel(candidate.seatClass);
    this.#logger.log(LogCode.RESERVE_START, describeMatch(candidate));

    // --- 1단계 : 좌석 칸 고르기 (되돌릴 수 있다) --------------------------
    this.#update({ state: WatchState.MATCHED, message: `${label} 좌석을 고르는 중…` });

    const selected = await this.#host.selectSeat({
      trainNumber: candidate.train.trainNumber,
      departureTime: candidate.train.departureTime,
      seatClass: candidate.seatClass,
      settleMs: config.reserveSettleMs,
    });
    this.#throwIfAborted();
    if (selected.clicked) {
      this.#logger.log(
        LogCode.RESERVE_CLICKED,
        `1단계 ${selected.anchor || ''} ${eventText(selected.event)}`.trim(),
      );
    }
    if (selected.result !== 'SELECTED') {
      return this.#reserveFailed(candidate, ReserveStage.SELECT, selected.result, selected.detail);
    }
    this.#logger.log(LogCode.RESERVE_SEAT_SELECTED, selected.detail);

    // --- 2단계 : 하단 바의 [예매] (되돌릴 수 없다) ------------------------
    this.#update({ message: '[예매] 를 누르는 중…' });

    // ★ **누르기 직전에 표시를 남긴다.** service worker 는 언제든 죽을 수 있고,
    // 부활한 뒤 "눌렀는지 아닌지 모르는 채로 또 누르는 것" 이 가장 나쁜 결과다.
    // 이 표시가 남아 있으면 부활은 감시를 이어가지 않고 인계로 확정한다. (§E-3-2 3번)
    await this.#onReserving({
      trainNumber: candidate.train.trainNumber,
      departureTime: candidate.train.departureTime,
      seatClass: candidate.seatClass,
      at: this.#clock(),
    });

    try {
      const confirmed = await this.#host.confirmReserve({ seatClass: candidate.seatClass });
      this.#throwIfAborted();
      if (confirmed.clicked) {
        this.#logger.log(
          LogCode.RESERVE_CLICKED,
          `2단계 [${confirmed.button}] ${eventText(confirmed.event)}`,
        );
      }
      if (confirmed.result !== 'CLICKED') {
        // 여기까지 왔다는 것은 **좌석은 골라져 있다**는 뜻이다. 사람이 이어서 누르면 된다.
        return this.#handOver(candidate, confirmed.result, confirmed.detail);
      }

      // --- 관찰 : 눌렀는데 화면이 어떻게 되었나 ---------------------------
      //
      // 여기만 예산이 넉넉하다. **대기열은 [예매] 를 누른 직후에 가장 잘 붙는다** (§39-6).
      // 관찰은 DOM 읽기라 요청이 나가지 않는다.
      this.#update({ message: '예매 결과를 확인하는 중…' });

      const observed = await this.#host.watchReserveOutcome({
        baseline: confirmed.baseline,
        budgetMs: config.confirmSettleMs,
        pollMs: config.confirmPollMs,
        signal: this.#signal,
      });
      this.#throwIfAborted();

      // **실측 자리다.** 코레일의 실제 안내 문구를 우리는 아직 모른다 (§38-8).
      // 판정과 무관하게 보이는 대로 남긴다 — 이 줄이 다음 selector 수정의 근거다.
      if (observed.page && observed.page.notice) {
        this.#logger.log(LogCode.RESERVE_NOTICE, observed.page.notice);
      }

      switch (observed.kind) {
        case 'CLICKED':
          return this.#reserved(candidate, observed.detail);

        case 'SOLD_OUT':
          return this.#soldOut(candidate, config, observed.detail);

        case 'BLOCKED':
          // 차단된 화면에서는 되돌리기도 하지 않는다. 그 자리에서 멈춘다. (대원칙 2)
          this.#logger.log(LogCode.BLOCKED_DETECTED, observed.detail);
          this.#notifyStopped('접속이 차단되어 감시를 멈췄습니다', watchErrorGuide(WatchError.BLOCKED));
          this.#fail(WatchError.BLOCKED, watchErrorGuide(WatchError.BLOCKED));
          return ReserveStep.STOPPED;

        case 'NOTICE':
          // 모르는 안내가 떴다. 성공인지 실패인지 **추측하지 않는다.** 사람에게 넘긴다.
          return this.#handOver(candidate, ReserveResult.UNKNOWN_NOTICE, observed.detail);

        default:
          return this.#handOver(candidate, ReserveResult.NO_CHANGE, observed.detail);
      }
    } finally {
      // 어떻게 빠져나가든 표시를 지운다. 남겨 두면 다음 부활이 헛되이 인계로 끝난다.
      await this.#onReserving(null);
    }
  }

  /** 2단계까지 눌렀고 화면이 넘어갔다. **여기서부터는 사용자의 몫이다.** (대원칙 3) */
  #reserved(match, detail) {
    this.#logger.log(LogCode.RESERVE_SUCCEEDED, detail);
    this.#logger.log(LogCode.WATCH_STOP, '예매 누름');
    this.#notifyReserve(
      '[예매] 를 눌렀습니다',
      `${trainSummary(match.train)}\n${seatClassLabel(match.seatClass)} — ` +
        '좌석 선택과 결제를 지금 진행하세요.',
    );
    this.#finish({
      state: WatchState.RESERVED,
      reserve: {
        match,
        stage: ReserveStage.CONFIRM,
        result: ReserveResult.CLICKED,
        detail,
      },
      message: `${trainSummary(match.train)} ${seatClassLabel(match.seatClass)} ` +
        '[예매] 를 눌렀습니다. 좌석 선택과 결제는 직접 진행하세요.',
    });
    return ReserveStep.STOPPED;
  }

  /**
   * **1단계는 되었는데 2단계를 누르지 않은(또는 확신할 수 없는) 경우.** (§38-6-1)
   *
   * 오류가 아니다. 좌석 칸은 화면에서 골라져 있고 하단 바도 떠 있으므로 사용자가
   * [예매] 만 누르면 된다. 그래서 **감시를 여기서 끝낸다** — 다음 사이클의 새로고침이
   * 골라 둔 선택을 지우기 때문이다.
   */
  #handOver(match, result, detail) {
    this.#logger.log(LogCode.RESERVE_HANDOVER, `${result} ${detail}`.trim());
    this.#logger.log(LogCode.WATCH_STOP, '좌석만 골라 두고 인계');
    this.#notifyReserve(
      '좌석을 골라 뒀습니다 — [예매] 를 눌러 주세요',
      `${trainSummary(match.train)}\n${seatClassLabel(match.seatClass)} — ` +
        `${reserveResultLabel(result)}`,
    );
    this.#finish({
      state: WatchState.SEAT_SELECTED,
      reserve: { match, stage: ReserveStage.CONFIRM, result, detail },
      message: `${trainSummary(match.train)} ${seatClassLabel(match.seatClass)} 좌석을 골라 뒀습니다. ` +
        `화면 아래 [예매] 를 직접 눌러 주세요. (${reserveResultLabel(result)})`,
    });
    return ReserveStep.STOPPED;
  }

  /**
   * **1단계에서 끝난 경우.** 두 갈래이고 처리가 다르다.
   *
   *  - 편성/칸을 못 찾았다 → 화면이 그새 바뀐 것이다. **아무것도 누르지 않았다.**
   *    감시를 이어간다. 다음 사이클에 다시 열려 보이면 그때 누른다.
   *  - 눌렀는데 안 골라졌다 → **합성 클릭이 이 사이트에 통하지 않는다**는 뜻이다
   *    (PLAN.md §E-2-1). 계속 돌면 사이클마다 헛클릭이 나가므로 멈추고 사람을 부른다.
   */
  #reserveFailed(match, stage, result, detail) {
    this.#logger.log(LogCode.RESERVE_FAILED, `${stage}/${result} ${detail}`);

    if (result === ReserveResult.ROW_NOT_FOUND || result === ReserveResult.CELL_NOT_FOUND) {
      // **아무것도 누르지 않았으니 시도 이력에서 뺀다.** 다음 사이클에 다시 보이면
      // 그때 누른다. 대원칙 2 가 세는 것은 나간 요청이지 이런 헛걸음이 아니다.
      this.#reserveAttempts.delete(matchKeyOf(match));
      this.#update({
        reserve: { match, stage, result, detail },
        message: `${detail} — 감시를 계속합니다.`,
      });
      return ReserveStep.CONTINUE;
    }

    const guide = '좌석은 열렸는데 확장이 누른 클릭을 사이트가 받지 않았습니다. ' +
      '코레일 화면에서 직접 예매하세요.';
    this.#logger.log(LogCode.WATCH_STOP, '클릭이 통하지 않음');
    this.#notifyReserve('좌석이 열렸습니다 — 직접 예매하세요', `${trainSummary(match.train)}\n${guide}`);
    this.#finish({
      state: WatchState.MATCHED,
      reserve: { match, stage, result, detail },
      message: `${describeMatch(match)} — ${guide} (${reserveResultLabel(result)})`,
    });
    return ReserveStep.STOPPED;
  }

  /**
   * **"잔여석없음".** 눌렀을 때는 열려 있었지만 그사이 남이 먼저 잡은 것이다. (§19-2)
   *
   * 오류가 아니므로 감시를 멈추지 않는다. 다만 그 화면에서는 다음 사이클을 진행할 수
   * 없으므로 **목록 화면까지 되돌린 뒤** 이어간다.
   *
   * 되돌리기는 **뒤로 가기만** 쓴다. 안내 화면의 [확인] 을 누르면 조회 폼이 새로 열려
   * 사용자가 넣어 둔 조회 조건이 초기화된다. 되돌리기 한 번이 감시 전체를 망친다.
   * (대원칙 5) 되돌리지 못하면 그 화면에서 더 누르지 않고 멈춘다.
   */
  async #soldOut(match, config, detail) {
    const key = matchKeyOf(match);
    const attempts = (this.#soldOutCounts.get(key) ?? 0) + 1;
    this.#soldOutCounts.set(key, attempts);
    this.#logger.log(LogCode.RESERVE_SOLD_OUT, `${describeMatch(match)} (${attempts}) ${detail}`);

    const retryable = attempts < config.maxSoldOutRetries;
    if (retryable) {
      this.#reserveAttempts.delete(key);
    } else {
      this.#logger.log(
        LogCode.RESERVE_SKIPPED,
        `${describeMatch(match)} 잔여석없음 ${attempts}회 - 더 누르지 않음`,
      );
    }

    this.#update({
      state: WatchState.WAITING,
      error: null,
      reserve: { match, stage: ReserveStage.CONFIRM, result: ReserveResult.SOLD_OUT, detail },
      message: `${trainSummary(match.train)} ${seatClassLabel(match.seatClass)} — ` +
        '누르는 사이에 좌석이 나갔습니다. ' +
        (retryable ? '목록으로 돌아가 계속 감시합니다.' : '이 칸은 더 누르지 않습니다.'),
    });

    const back = await this.#host.goBack({
      settleTimeoutMs: config.researchSettleMs,
      pollMs: config.researchPollMs,
      signal: this.#signal,
    });
    this.#throwIfAborted();

    if (back.kind === 'UPDATED') {
      this.#logger.log(LogCode.RESERVE_DISMISSED, back.detail);
      return ReserveStep.CONTINUE;
    }

    this.#logger.log(LogCode.RESERVE_DISMISS_FAILED, back.detail);
    this.#notifyStopped(
      '예매 화면에서 목록으로 돌아가지 못했습니다',
      '코레일 화면을 확인한 뒤 다시 조회하고 감시를 시작하세요.',
    );
    this.#fail(WatchError.REFRESH_FAILED, watchErrorGuide(WatchError.REFRESH_FAILED));
    return ReserveStep.STOPPED;
  }

  /** 예매 결과 알림. 발견 알림 자리를 덮는다 — 사용자가 봐야 하는 화면은 하나뿐이다. */
  #notifyReserve(title, body) {
    try {
      this.#notifier.notifyReserve(title, body);
    } catch (e) {
      this.#logger.log(LogCode.NOTIFICATION_SKIPPED, `알림 실패: ${(e && e.message) || e}`);
    }
  }

  /**
   * **화면이 확정될 때까지 제자리에서 다시 읽는다.** (DESIGN.md §39)
   *
   * ## 왜 한 번으로 부족한가
   *
   * 코레일에는 NetFunnel 접속 대기열이 있다. 대기 화면은 `UNKNOWN_PAGE` 로 읽히는데,
   * 예전 구조라면 그 한 번의 판독으로 사이클을 끝내고 다음 사이클로 넘어간다.
   * **다음 사이클의 첫 동작이 새로고침이고, 대기 중에 새로고침하면 대기 순번이
   * 날아가서 대기가 영영 끝나지 않는다.**
   *
   * 그래서 목록이 나타날 때까지 다음 사이클로 넘어가지 않는다. 여기서 하는 일은
   * DOM 읽기뿐이라 **요청이 한 번도 나가지 않는다.** 대원칙 2 가 세는 것은
   * 요청 횟수지 판독 횟수가 아니다. (§39-5)
   *
   * ## ★ 확장에서 새로 생기는 경로 (PLAN.md §E-6-1)
   *
   * content script 에 말을 걸지 못하는 상태(`NO_CONTENT_SCRIPT`)가 **여기서
   * 기다림으로 취급된다.** 새로고침 직후에는 항상 몇 초 동안 그렇고, 그것을
   * 연속 오류로 세면 정상 동작만으로 감시가 죽는다. 확장에서 가장 저지르기 쉬운 실수다.
   *
   * ## 정상일 때는 아무것도 달라지지 않는다
   *
   * 첫 판독이 확정([isSettled])이면 그 자리에서 돌려준다. 차단·로그인·세션만료도
   * 확정이라 **첫 판독에서 즉시** 빠져나간다 — 차단된 화면을 3분씩 붙들면 안 된다.
   * 기다림이 붙는 것은 `UNKNOWN_PAGE` 하나뿐이다.
   *
   * @return 확정된 스냅샷. 예산을 다 쓰면 마지막으로 읽은 `UNKNOWN_PAGE` 스냅샷.
   * @throws DomParseError 예산 내내 판독 자체가 실패한 경우.
   */
  async #readSettledSnapshot(config) {
    const budgetMs = this.#hasSeenList ? config.pageWaitMs : config.pageWaitFirstMs;
    const startedAt = this.#clock();
    let reads = 0;
    let waitLogged = false;
    let absentLogged = false;
    let last = null;
    let failure = null;

    try {
      for (;;) {
        this.#throwIfAborted();
        const read = await this.#host.parse();
        reads++;

        let reason;
        if (read.ok) {
          last = read.snapshot;
          failure = null;
          if (isSettled(read.snapshot.status)) {
            if (waitLogged) {
              this.#logger.log(
                LogCode.PAGE_WAIT_DONE,
                `${read.snapshot.status} ${this.#elapsedText(startedAt)} ${reads}회 판독`,
              );
            }
            return read.snapshot;
          }
          reason = PageStatus.UNKNOWN_PAGE;
        } else if (read.reason === 'NO_CONTENT_SCRIPT') {
          // 오류가 아니다. 문서를 다시 그리는 중이거나, 대기열이 다른 오리진으로
          // 튕겼거나, 사용자가 잠깐 딴 데를 보고 있는 것이다. 전부 기다림이다.
          reason = LogCode.CONTENT_ABSENT;
          if (!absentLogged) {
            absentLogged = true;
            this.#logger.log(LogCode.CONTENT_ABSENT, read.error || '연결할 수 없음 (오류 아님)');
          }
        } else {
          failure = new DomParseError(read.error || '화면을 읽지 못했습니다.');
          reason = LogCode.DOM_PARSE_ERROR;
        }

        if (this.#clock() - startedAt >= budgetMs) {
          if (waitLogged) {
            this.#logger.log(
              LogCode.PAGE_WAIT_TIMEOUT,
              `${this.#elapsedText(startedAt)} ${reads}회 판독 - 목록이 나타나지 않음`,
            );
          }
          if (last) return last;
          throw failure ?? new DomParseError('화면을 읽지 못했습니다.');
        }

        if (!waitLogged) {
          waitLogged = true;
          this.#logger.log(
            LogCode.PAGE_WAIT_START,
            `${reason} - 최대 ${Math.round(budgetMs / 1000)}초 기다림 (새로고침하지 않음)`,
          );
        } else if (reads % WAIT_TICK_READS === 0) {
          this.#logger.log(LogCode.PAGE_WAIT_TICK, `${this.#elapsedText(startedAt)} ${reads}회`);
        }

        // 팝업이 멈춘 것처럼 보이면 사용자는 죽은 줄 안다. 경과 시간을 흘려 준다.
        this.#update({ message: `${WAITING_MESSAGE_PREFIX}입니다… ${this.#elapsedText(startedAt)}` });

        await this.#sleep(config.pageWaitPollMs, this.#signal);
      }
    } finally {
      // 어떻게 빠져나가든 "기다리는 중" 문구를 남겨 두지 않는다. 중지해서 화면이
      // "중지됨" 이 됐는데 옆에 그 문구가 붙어 있으면 헷갈린다.
      const message = this.#status.message;
      if (waitLogged && typeof message === 'string' && message.startsWith(WAITING_MESSAGE_PREFIX)) {
        this.#update({ message: null });
      }
    }
  }

  #elapsedText(startedAt) {
    return `${Math.floor((this.#clock() - startedAt) / 1000)}초`;
  }

  /**
   * 감시 도중에 로그인이 풀렸는지 본다. **사이클마다** 부른다. (§27-1)
   *
   * 코레일은 **비로그인 상태에서도 조회가 되고 좌석 선택까지 된다.** 세션이 풀린 채로
   * 감시를 이어가면 좌석이 열려 [예매] 를 누르는 **바로 그 순간** 로그인 화면으로 튕긴다.
   * 몇 시간 기다린 그 한 번을 잃느니 그 자리에서 멈추고 사람을 부르는 편이 낫다.
   *
   * DOM 읽기 한 번이라 요청이 나가지 않는다. 판정하지 못하면(UNKNOWN) 막지 않는다. (대원칙 6)
   *
   * @return 계속 감시해도 되면 true. false 면 이미 멈춤 처리까지 끝난 상태다.
   */
  async #ensureStillLoggedIn(config) {
    const check = await this.#host.login();
    this.#logger.log(LogCode.LOGIN_STATE, `${check.state} ${check.detail || ''}`.trim());
    if (check.state !== 'LOGGED_OUT') return true;

    this.#logger.log(LogCode.WATCH_STOP, '감시 중 로그인이 풀림');
    if (config.notificationEnabled) {
      this.#notifyStopped('로그인이 풀려 감시를 멈췄습니다', LOGGED_OUT_GUIDE);
    } else {
      this.#logger.log(LogCode.NOTIFICATION_SKIPPED, '알림 꺼짐 (로그인 풀림)');
    }
    this.#fail(WatchError.SESSION_EXPIRED, LOGGED_OUT_GUIDE);
    return false;
  }

  /**
   * 사용자가 사이트에서 **조회 조건을 바꿔 다시 조회했는지** 본다. (§E-6-3 예외 17)
   *
   * 체크해 둔 칸은 "그 조회 결과 화면" 에만 의미가 있다 (대원칙 4). 조건이 바뀌면
   * 그 선택은 다른 화면의 것이므로 **버리고 멈춘다.** 그대로 두면 눈에 보이지 않는
   * 조건으로 감시가 계속 돌고, 자동 클릭이 붙는 M3 부터는 엉뚱한 칸을 잡는다.
   *
   * 비교하는 것은 **해시뿐이다.** 조회 조건 자체는 읽지도 갖지도 않는다.
   */
  async #ensureSameQuery() {
    const sig = await this.#readQuerySig();
    if (!sig || !this.#querySig || sig === this.#querySig) {
      // 못 읽었으면 판정하지 않는다. 애매하면 막지 않는다 (대원칙 6).
      if (sig && !this.#querySig) this.#querySig = sig;
      return true;
    }

    this.#logger.log(LogCode.QUERY_CHANGED, `${this.#querySig} → ${sig}`);
    this.#selection = { seats: [] };
    this.#onSelectionInvalid();
    this.#notifyStopped(
      '조회 조건이 바뀌어 감시를 멈췄습니다',
      watchErrorGuide(WatchError.QUERY_CHANGED),
    );
    this.#fail(WatchError.QUERY_CHANGED, watchErrorGuide(WatchError.QUERY_CHANGED));
    return false;
  }

  async #readQuerySig() {
    const res = await this.#host.querySig();
    return res && res.ok && res.sig ? res.sig : null;
  }

  #notifyMatches(newMatches, config) {
    if (newMatches.length === 0) {
      this.#logger.log(LogCode.NOTIFICATION_SKIPPED, '이미 알린 좌석');
      return;
    }
    newMatches.forEach((m) => this.#notifiedKeys.add(matchKeyOf(m)));

    if (!config.notificationEnabled) {
      this.#logger.log(LogCode.NOTIFICATION_SKIPPED, '알림 꺼짐');
      return;
    }
    const head = newMatches[0];
    this.#notifier.notifyMatch(head, newMatches.length - 1);
    this.#logger.log(LogCode.NOTIFICATION_SENT, describeMatch(head));
  }

  /** 감시가 스스로 멈췄다는 알림. 알림이 안 되는 것이 감시를 멈출 이유는 아니다. (예외 24) */
  #notifyStopped(title, body) {
    try {
      this.#notifier.notifyWatchStopped(title, body);
    } catch (e) {
      this.#logger.log(LogCode.NOTIFICATION_SKIPPED, `알림 실패: ${(e && e.message) || e}`);
    }
  }

  async #waitForNextCycle(config) {
    const interval = this.#scheduler.nextInterval(config.minIntervalMs, config.maxIntervalMs);
    this.#logger.log(LogCode.NEXT_RELOAD, `${interval}ms`);
    this.#update({ state: WatchState.WAITING });
    await this.#scheduler.wait(interval, {
      signal: this.#signal,
      onRemaining: (remaining) => this.#update({ nextCheckInMs: remaining }),
    });
  }

  /** 루프를 끝낸다. 이 뒤로는 `isWatching` 이 false 다. */
  #finish(patch) {
    this.#cancelLoop();
    this.#update({ nextCheckInMs: null, ...patch });
  }

  #fail(error, message) {
    this.#finish({ state: WatchState.ERROR, error, message });
  }
}

/** 클릭 훅이 본 것 한 줄. `trusted=false` 는 **정상이다** — 확장의 클릭은 원래 그렇다. */
function eventText(event) {
  if (!event) return '';
  return `fired=${event.fired} trusted=${event.trusted} onTarget=${event.onTarget}`;
}
