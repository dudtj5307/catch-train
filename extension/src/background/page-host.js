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
