// 감시 엔진이 바라보는 페이지. (android: webview/PageHost.kt + KtxWebViewHost.kt)
//
// **chrome API 가 여기서 끊긴다.** `watch-controller.js` 는 이 인터페이스만 알기 때문에
// 브라우저 없이 테스트할 수 있다. (§34-1)
//
// 갱신은 **`chrome.tabs.reload` 하나뿐이다.** PC 폭에서는 [열차조회] 버튼이 실제로
// 보이지만 누르지 않는다 — 그것을 누르면 사용자가 조회한 조건이 아니라 **화면 폼의
// 지금 값**으로 조회가 나가고, 사용자가 폼만 만지작거려 뒀다면 조회하지 않은 조건으로
// 요청이 나간다. 조회 조건은 앱이 갖지 않는다 (대원칙 4). (PLAN.md §E-4, §38-9)
//
// 새로고침이 조건을 날리지 않는 이유는 조건이 DOM 이 아니라
// `localStorage["LS_TICKET_GENERAL"]` 에 있기 때문이다. 다시 불러오면 SPA 가 그 값으로
// 같은 조회를 스스로 되풀이한다.
//
// 새로고침 한 번은 **문서 + 번들 + 조회 API** 전체다. 간격을 좁히지 말 것. (대원칙 2)
//
// **누르는 것도 여기를 지난다.** [selectSeat](1단계) · [confirmReserve](2단계) ·
// [watchReserveOutcome](2단계 뒤 관찰) · [goBack](되돌리기). 감시 루프는 이 넷만 알고,
// 클릭이 실제로 어떻게 나가는지(합성이냐 디버거냐)는 content script 안쪽 사정이다.
// 드라이버를 갈아 끼울 자리도 여기다. (PLAN.md §E-2-2)

import { DomParseError, snapshotFromRaw } from '../domain/page-snapshot.js';
import { AbortError, sleep } from './scheduler.js';

/** 갱신 결과. (android: PageOutcome) `kind` 로만 갈린다. */
function outcome(kind, detail, extra = {}) {
  return { kind, detail, ...extra };
}

export class TabPageHost {
  /** @param tabId 감시 대상 탭. 감시는 전역으로 하나뿐이다. (§E-6-3 예외 23) */
  constructor(tabId) {
    this.tabId = tabId;
  }

  /** 탭이 아직 있는가. 없으면 감시 대상이 사라진 것이다. (§E-6-3 예외 16) */
  async isAlive() {
    try {
      const tab = await chrome.tabs.get(this.tabId);
      return !!tab;
    } catch {
      return false;
    }
  }

  /**
   * content script 에 물어본다.
   *
   * **"연결할 수 없음" 은 오류가 아니다.** 문서를 다시 그리는 중이거나 아직 주입되기
   * 전이면 늘 이렇게 되고, 새로고침마다 몇 초씩 정상적으로 겪는 상태다.
   * 감시 루프는 이 값을 **기다림**으로 다룬다 — 연속 실패로 세지 않는다. (§E-6-1)
   */
  async send(message) {
    try {
      const data = await chrome.tabs.sendMessage(this.tabId, message);
      if (!data) return { ok: false, reason: 'NO_CONTENT_SCRIPT' };
      return data;
    } catch (e) {
      return { ok: false, reason: 'NO_CONTENT_SCRIPT', error: (e && e.message) || String(e) };
    }
  }

  /** 조회 결과 화면 판독. 판정은 `domain/page-snapshot.js` 가 한다. */
  async parse() {
    const res = await this.send({ type: 'PARSE' });
    if (!res.ok) return res;
    try {
      return { ok: true, snapshot: snapshotFromRaw(res.data) };
    } catch (e) {
      if (e instanceof DomParseError) return { ok: false, reason: 'PARSE_FAILED', error: e.message };
      throw e;
    }
  }

  /** 목록이 그려져 있는지만 보는 가벼운 판정. 갱신이 반영됐는지 확인하는 데 쓴다. */
  async pageKind() {
    const res = await this.send({ type: 'PAGE_KIND' });
    return res.ok ? { ok: true, ...res.data } : res;
  }

  /** 로그인 여부. 못 읽으면 `UNKNOWN` 이고, 그때는 막지 않는다. (대원칙 6, §38-7) */
  async login() {
    const res = await this.send({ type: 'LOGIN' });
    if (!res.ok) return { state: 'UNKNOWN', detail: res.error || '읽지 못함' };
    return { state: res.data.state, detail: res.data.detail };
  }

  /** 조회 조건의 **해시**. 원문은 받지도 쓰지도 않는다. (§E-6-3 예외 17) */
  async querySig() {
    const res = await this.send({ type: 'QUERY_SIG' });
    return res.ok ? { ok: true, sig: res.data.sig } : { ok: false };
  }

  // ------------------------------------------------------------ 예매 (M3)

  /**
   * **1단계 — 좌석 칸 고르기.** 되돌릴 수 있는 동작이다. (§38-6)
   *
   * content script 가 눌러 보고 `active` 가 붙었는지 확인한 결과를 그대로 돌려준다.
   * 말을 걸지 못하면 `FAILED` 다 — 여기서 다시 걸지 않는다. 누르는 경로에서 재시도는
   * "첫 클릭이 닿았는지 모르는 채로 또 누르는 것" 이다. (대원칙 2)
   */
  async selectSeat({ trainNumber, departureTime, seatClass, settleMs }) {
    const res = await this.send({
      type: 'SELECT_SEAT', trainNumber, departureTime, seatClass, settleMs,
    });
    if (!res.ok) {
      return { result: 'FAILED', detail: res.error || res.reason || '좌석 칸을 누르지 못했습니다' };
    }
    return res.data;
  }

  /**
   * **2단계 — 하단 바의 [예매].** 되돌릴 수 없다.
   *
   * content script 는 누르고 **기다리지 않고** 곧바로 답한다. 화면이 넘어가면 그
   * 스크립트가 죽어 응답이 사라지기 때문이다. 결과는 [watchReserveOutcome] 이 본다.
   */
  async confirmReserve({ seatClass }) {
    const res = await this.send({ type: 'CONFIRM_RESERVE', seatClass });
    if (!res.ok) {
      return { result: 'FAILED', detail: res.error || res.reason || '[예매] 를 누르지 못했습니다' };
    }
    return res.data;
  }

  /**
   * **[예매] 를 누른 뒤 화면을 관찰한다.** 요청은 나가지 않는다 — 읽기뿐이다.
   *
   * 예산이 조회보다 넉넉한 이유가 있다. **대기열은 [예매] 를 누른 직후에 가장 잘 붙고**,
   * 전환이 시작되었다는 것은 요청이 이미 나갔다는 뜻이다. 6초에서 포기하면 몇 시간
   * 기다린 좌석을 대기창 앞에서 버린다. (§39-6)
   *
   * `NO_CONTENT_SCRIPT` 는 여기서 **화면이 넘어가는 중**이라는 신호다. 문서가 바뀌지
   * 않으면 content script 는 죽지 않기 때문이다. 그래서 오류로 세지 않고, 끝내 돌아오지
   * 않으면 "넘어갔다" 로 읽는다. (§E-6-1)
   *
   * @return `{ kind, detail, page }` — `kind` 는 판정이 아니라 **관찰 결과**다.
   *   `SOLD_OUT` / `BLOCKED` / `NOTICE`(모르는 안내가 떴다) / `CLICKED` / `NO_CHANGE`
   */
  async watchReserveOutcome({ baseline, budgetMs, pollMs = 500, signal }) {
    const startedAt = Date.now();
    let last = null;
    let gone = false;

    for (;;) {
      const res = await this.send({ type: 'RESERVE_OUTCOME' });
      if (res.ok) {
        const page = res.data;
        last = page;
        // 차단이 먼저다. 차단된 화면에서는 더 아무것도 하지 않는다.
        if (page.blocked) return { kind: 'BLOCKED', detail: page.notice, page };
        if (page.failed) return { kind: 'SOLD_OUT', detail: page.notice, page };
        if (page.url !== baseline.url || (baseline.rows > 0 && page.rows === 0)) {
          return { kind: 'CLICKED', detail: `화면 전환 ${baseline.url} → ${page.url}`, page };
        }
        // **모르는 안내가 떴다.** 성공인지 실패인지 우리는 모른다 — 코레일의 실제
        // 실패 문구가 아직 실측 전이기 때문이다 (§38-8). 추측하지 않고 사람에게 넘기고,
        // 문구를 로그에 남겨 다음 번에는 알아볼 수 있게 한다.
        if (page.modal && !baseline.modal) {
          return { kind: 'NOTICE', detail: page.notice, page };
        }
      } else if (res.reason === 'NO_CONTENT_SCRIPT') {
        gone = true;
      }

      if (Date.now() - startedAt >= budgetMs) {
        if (gone) {
          return { kind: 'CLICKED', detail: '화면이 넘어가 페이지를 읽을 수 없습니다', page: last };
        }
        return { kind: 'NO_CHANGE', detail: '눌렀지만 화면이 바뀌지 않았습니다', page: last };
      }

      // 중지는 즉시 먹혀야 한다 (대원칙 7-c). [AbortError] 는 감시 루프까지 그대로 올린다.
      await sleep(pollMs, signal);
    }
  }

  /**
   * **뒤로 가기.** (android: `WebView.goBack()`)
   *
   * 되돌리기는 이것뿐이다. 안내 화면의 [확인] 을 누르면 조회 폼이 새로 열려서
   * 사용자가 넣어 둔 조회 조건이 통째로 초기화된다. 되돌리기 한 번이 감시 전체를
   * 망친다. (대원칙 5)
   *
   * SPA 라 한 칸 뒤가 목록이라는 보장이 없어, **목록이 실제로 보이는지 확인한다.**
   */
  async goBack({ settleTimeoutMs, pollMs = 300, signal }) {
    if (!(await this.isAlive())) return outcome('TAB_GONE', '탭이 사라졌습니다');
    try {
      await chrome.tabs.goBack(this.tabId);
    } catch (e) {
      return outcome('FAILED', `뒤로 가기 실패: ${(e && e.message) || e}`);
    }
    const settled = await this.#waitForList(settleTimeoutMs, pollMs, signal);
    if (settled.list) {
      await this.send({ type: 'SCROLL_TOP' });
      return outcome('UPDATED', `목록 ${settled.rows}편성 sig=${settled.sig}`);
    }
    return outcome('SETTLED', '뒤로 갔지만 목록 화면이 아닙니다');
  }

  /**
   * **새로고침하고 목록이 다시 그려질 때까지 기다린다.**
   *
   * `tabs.onUpdated` 의 `complete` 는 안드로이드의 `onPageFinished` 에 대응하는데,
   * **거기서 바로 분석하면 안 된다.** 코레일은 React SPA 라 문서를 받은 뒤 번들이 돌고
   * 조회 API 를 쳐야 `li.tckList` 가 생긴다. 그래서 목록이 보일 때까지 한 번 더 기다린다.
   * 여기서 기다리는 동안 하는 일은 DOM 읽기뿐이라 **요청이 나가지 않는다.** (§38-9)
   */
  async requery({ timeoutMs, settleTimeoutMs, pollMs = 300, signal, onReload = () => {} }) {
    if (!(await this.isAlive())) return outcome('TAB_GONE', '탭이 사라졌습니다');

    // 새로고침은 스크롤을 되살려서 화면을 맨 밑으로 튕긴다. 되살릴 때의 문서에는
    // 아직 목록이 없어 짧고, 예전 오프셋이 문서 끝에 잘려 붙는다. 그래서
    // **세 자리**에서 막는다 — 여기(나가는 이력 항목) / `content/early.js`(새 문서) /
    // 목록이 그려진 뒤. 한 자리라도 빼면 다시 튄다. (§38-9)
    await this.send({ type: 'SCROLL_TOP' });

    // 리스너를 **reload 보다 먼저** 건다. 뒤에 걸면 빠른 문서의 complete 를 놓친다.
    const completed = this.#waitForComplete(timeoutMs, signal);

    try {
      await chrome.tabs.reload(this.tabId);
    } catch (e) {
      completed.cancel();
      const message = (e && e.message) || String(e);
      if (/No tab with id/i.test(message)) return outcome('TAB_GONE', message);
      return outcome('FAILED', `새로고침 실패: ${message}`, { network: true });
    }
    onReload(`chrome.tabs.reload #${this.tabId}`);

    const loaded = await completed.promise;

    // 로딩 완료 신호를 못 받았어도 곧바로 실패로 보지 않는다. 목록이 그려져 있을 수도
    // 있으므로 아래에서 한 번 확인한다. (대원칙 6)
    const settled = await this.#waitForList(settleTimeoutMs, pollMs, signal);

    if (settled.list) {
      // 목록이 그려진 뒤 한 번 더. 짧던 문서에서 올린 것만으로는 부족하다. (§38-9)
      await this.send({ type: 'SCROLL_TOP' });
      return outcome('UPDATED', `목록 ${settled.rows}편성 sig=${settled.sig}`);
    }
    return outcome(
      'SETTLED',
      loaded
        ? `목록이 다시 그려지지 않음 (${Math.round(settleTimeoutMs / 1000)}초)`
        : `문서 로딩 완료 신호를 받지 못함 (${Math.round(timeoutMs / 1000)}초)`,
    );
  }

  /**
   * 새로고침한 문서가 `complete` 가 될 때까지. 시간이 지나면 false 로 돌아온다.
   *
   * 취소(감시 종료)도 false 다 — 부르는 쪽이 곧바로 중단 검사를 하므로 여기서
   * 예외를 던져 봐야 같은 자리에서 다시 던지게 될 뿐이다.
   */
  #waitForComplete(timeoutMs, signal) {
    let done;
    let timer;
    let onAbort;
    const listener = (tabId, info) => {
      if (tabId === this.tabId && info.status === 'complete') done(true);
    };
    const promise = new Promise((resolve) => {
      done = (value) => {
        chrome.tabs.onUpdated.removeListener(listener);
        clearTimeout(timer);
        if (signal && onAbort) signal.removeEventListener('abort', onAbort);
        resolve(value);
      };
      chrome.tabs.onUpdated.addListener(listener);
      timer = setTimeout(() => done(false), timeoutMs);
      if (signal) {
        onAbort = () => done(false);
        signal.addEventListener('abort', onAbort, { once: true });
      }
    });
    return { promise, cancel: () => done(false) };
  }

  /** 목록이 그려질 때까지 [pollMs] 마다 다시 본다. **읽기라 요청이 아니다.** */
  async #waitForList(budgetMs, pollMs, signal) {
    const startedAt = Date.now();
    for (;;) {
      const kind = await this.pageKind();
      if (kind.ok && kind.list) return { list: true, rows: kind.rows, sig: kind.sig };
      if (Date.now() - startedAt >= budgetMs) return { list: false };
      try {
        await sleep(pollMs, signal);
      } catch (e) {
        if (e instanceof AbortError) return { list: false };
        throw e;
      }
    }
  }
}
