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
//   새로고침 → 화면 확정까지 판독 → 로그인·조회조건 확인 → 판정 → 알림 → 대기
//
// **M2 는 알림까지다.** 좌석을 발견하면 알리고 멈춘다. 누르는 것(1·2단계)은
// 클릭 드라이버를 실측으로 정한 뒤(M2.5) M3·M4 에서 이 자리에 붙는다.
// 추측으로 기본값을 정하지 않기 위해 일부러 비워 둔 자리다. (PLAN.md §E-2-4)

import { DomParseError, PageStatus, isSettled } from '../domain/page-snapshot.js';
import { describeMatch, matchKeyOf } from '../domain/match.js';
import { match as matchSelection } from '../domain/selection-engine.js';
import {
  selectionIsEmpty,
  selectionSize,
  selectionTrainCount,
} from '../domain/watch-selection.js';
import { LogCode } from './logger.js';
import { AbortError, ReloadScheduler, formatRange, sleep } from './scheduler.js';
import { DEFAULT_WATCH_CONFIG } from './watch-config.js';
import {
  INITIAL_STATUS,
  WatchError,
  WatchState,
  watchErrorGuide,
} from './watch-state.js';

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

  #status = { ...INITIAL_STATUS };
  #host = null;
  #config = { ...DEFAULT_WATCH_CONFIG };
  #selection = { seats: [] };
  #abort = null;
  #loop = null;

  /** 이미 알린 (열차, 좌석등급) 조합. 문자열 키다. (§20) */
  #notifiedKeys = new Set();

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
  }) {
    this.#notifier = notifier;
    this.#logger = logger;
    this.#scheduler = scheduler;
    this.#clock = clock;
    this.#sleep = sleepFn;
    this.#onStatus = onStatus;
    this.#onSelectionInvalid = onSelectionInvalid;
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
    this.#hasSeenList = false;
    this.#querySig = null;
    this.#status = { ...INITIAL_STATUS, state: WatchState.LOADING, tabId: host.tabId };
    this.#emit();

    this.#logger.log(
      LogCode.WATCH_START,
      `선택 ${selectionSize(selection)}칸 (열차 ${selectionTrainCount(selection)}편성) ` +
        `간격=${formatRange(config.minIntervalMs, config.maxIntervalMs)} 랜덤` +
        // M2 에는 클릭이 없다. 켜 두었더라도 그렇게 보이면 안 된다.
        (config.autoReserveEnabled ? ' 자동예약=아직 없음(M3)' : ''),
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

      // 4-1) 자동 예매(1·2단계)가 붙을 자리다. M3·M4 — 지금은 아무것도 누르지 않는다.

      if (result.matched && config.stopOnMatch) {
        this.#logger.log(LogCode.MATCH_DETAIL, describeMatch(result.matches[0]));
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
