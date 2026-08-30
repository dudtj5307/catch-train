// 알림. (android: notification/MatchNotifier.kt + NotificationHelper.kt)
//
// 안드로이드는 소리·진동·전체화면 인텐트까지 쓰지만 확장은 `chrome.notifications` 뿐이다.
// **소리는 아직 없다.** MV3 service worker 에는 DOM 이 없어 오디오를 낼 수 없고,
// `chrome.offscreen` 문서가 필요하다 — 그것은 결제 재촉 알림(§19-3)과 함께 M4 에서 붙는다.
// 그 전에 권한만 미리 얻어 두지 않는다 (PLAN.md §E-8: 그 단계에서 실제로 쓰는 것만).
//
// **알림이 안 되는 것이 감시를 멈출 이유는 아니다.** (§E-6-3 예외 24)
// 사용자가 알림을 껐거나 방해 금지 모드일 수 있고, 그래도 감시는 계속되어야 한다.
// 그래서 여기서 나는 오류는 전부 삼키고 로그로만 남긴다.

import { describeMatch } from '../domain/match.js';
import { trainSummary } from '../domain/train.js';
import { seatClassLabel } from '../domain/seat-class.js';

const ICON_URL = 'icons/icon128.png';

/** 좌석 발견 알림. 새 알림이 예전 것을 덮도록 id 를 고정한다. */
const MATCH_ID = 'catch-train-match';

/** 감시가 스스로 멈췄다는 알림. 발견 알림과 섞이면 안 되므로 id 를 나눈다. */
const STOPPED_ID = 'catch-train-stopped';

export class ChromeNotifier {
  /**
   * @param onError 알림에 실패했을 때. 로그로만 남기고 감시는 이어간다.
   * @param onOpenTab 알림을 눌렀을 때 어느 탭을 앞으로 낼지. (없으면 아무 일도 안 한다)
   */
  constructor({ onError = () => {}, onOpenTab = null } = {}) {
    this.onError = onError;
    this.onOpenTab = onOpenTab;
  }

  /**
   * 조건을 만족한 좌석을 알린다. (§20)
   *
   * 같은 사이클에 여러 칸이 열려도 알림은 **하나**다. 여러 개를 쌓으면 사용자가
   * 하나씩 지워야 하고, 정작 봐야 할 화면은 어차피 하나뿐이다.
   */
  notifyMatch(match, extraCount = 0) {
    const extra = extraCount > 0 ? ` 외 ${extraCount}건` : '';
    this.#create(MATCH_ID, {
      title: `좌석이 열렸습니다${extra}`,
      message: `${trainSummary(match.train)}\n${seatClassLabel(match.seatClass)} — 지금 예매하세요.`,
      contextMessage: describeMatch(match),
      // 놓치면 안 되는 알림이다. 사용자가 직접 닫을 때까지 남긴다.
      requireInteraction: true,
      priority: 2,
    });
  }

  /** 감시가 스스로 멈췄다. (차단 / 로그인 풀림 / 탭 닫힘 / 조회 조건 바뀜) */
  notifyWatchStopped(title, body) {
    this.#create(STOPPED_ID, {
      title,
      message: body,
      requireInteraction: true,
      priority: 2,
    });
  }

  cancelAll() {
    for (const id of [MATCH_ID, STOPPED_ID]) {
      try {
        chrome.notifications.clear(id);
      } catch {
        // 이미 없는 알림이다. 지울 것이 없는 것은 실패가 아니다.
      }
    }
  }

  /** 알림 클릭 → 감시하던 탭을 앞으로. 확장이 주소를 갈아 끼우지는 않는다. (§E-6-5) */
  bindClicks() {
    chrome.notifications.onClicked.addListener((id) => {
      if (id !== MATCH_ID && id !== STOPPED_ID) return;
      try {
        chrome.notifications.clear(id);
      } catch { /* 이미 닫혔다 */ }
      if (this.onOpenTab) this.onOpenTab();
    });
  }

  #create(id, options) {
    try {
      chrome.notifications.create(id, {
        type: 'basic',
        iconUrl: chrome.runtime.getURL(ICON_URL),
        silent: false,
        ...options,
      }, () => {
        // 권한이 없거나 방해 금지 모드면 여기로 온다. 읽지 않으면 콘솔에 경고가 남는다.
        const error = chrome.runtime.lastError;
        if (error) this.onError(error.message || String(error));
      });
    } catch (e) {
      this.onError((e && e.message) || String(e));
    }
  }
}
