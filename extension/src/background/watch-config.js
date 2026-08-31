// 감시 루프 파라미터. (android: watcher/WatchConfig.kt)
//
// **값마다 근거를 붙여 둔다.** 이 파일에서 숫자를 고치는 것은 대개 대원칙 2 를
// 건드리는 일이라, 왜 그 값인지 모르는 채로 바꾸면 차단으로 돌아온다.
//
// 갱신 방식은 설정하지 않는다. 언제나 **새로고침**뿐이다. (§38-9, PLAN.md §E-4)
//
// **예매를 누르는 것도 설정하지 않는다.** 좌석이 열리면 그 칸을 고르고 [예매] 까지
// 누르는 것이 이 도구의 목적이고, 그 앞에서 멈출 이유가 없다. 끄는 스위치를 두지 않는다.
// 위험해 보이지 않는 이유는 셋이다 — [예매] 를 넘어가지 않고(대원칙 3), 누른 뒤에 반드시
// 확인하며, 확인이 어긋나면 **좌석만 골라 둔 채 사람에게 넘긴다** (§38-6-1). 그래서
// "모르는 사이에 엉뚱한 것이 눌리는" 경로가 없다.

import {
  DEFAULT_MAX_INTERVAL_MS,
  DEFAULT_MIN_INTERVAL_MS,
  clampRange,
} from './scheduler.js';

export const DEFAULT_WATCH_CONFIG = Object.freeze({
  /** 재조회 간격 하한. 실제 대기는 [minIntervalMs]~[maxIntervalMs] 에서 매번 무작위다. */
  minIntervalMs: DEFAULT_MIN_INTERVAL_MS,
  maxIntervalMs: DEFAULT_MAX_INTERVAL_MS,

  notificationEnabled: true,

  /** 조건 만족 시 감시를 멈춘다. (§34-4) */
  stopOnMatch: true,

  /** 새로고침한 문서의 로딩 완료를 기다리는 최대 시간. (`tabs.onUpdated` complete) */
  pageTimeoutMs: 20_000,

  /**
   * 문서 로딩이 끝난 **뒤** SPA 가 열차 목록을 다시 그리기를 기다리는 최대 시간.
   *
   * 코레일은 React SPA 라 문서를 받은 뒤 번들이 돌고 조회 API 를 쳐야 `li.tckList` 가
   * 생긴다. 짧게 잡으면 좌석이 있는데도 매번 `NO_TRAIN` 으로 읽는다. (§38-9)
   *
   * 여기서 기다리는 동안 **요청은 나가지 않는다.** DOM 을 읽기만 한다.
   */
  researchSettleMs: 12_000,

  /** 목록이 그려졌는지 다시 보는 간격. 읽기라 요청이 아니다. */
  researchPollMs: 300,

  /** 페이지 정착 직후 렌더링 안정화를 위한 짧은 대기. */
  settleDelayMs: 300,

  /**
   * 화면이 아직 `UNKNOWN_PAGE` 일 때 **제자리에서 다시 읽어 보는 간격.** (§39)
   * 읽기만 하므로 요청이 나가지 않는다. 간격과 무관하고 차단 위험과도 무관하다.
   */
  pageWaitPollMs: 500,

  /**
   * **접속 대기열에 걸린 화면을 기다려 주는 최대 시간.** (§39)
   *
   * 대기 중에 새로고침하면 **대기 순번이 날아가서 대기가 영영 끝나지 않는다.**
   * 그래서 목록이 나타날 때까지 다음 사이클로 넘어가지 않고 제자리에서 기다린다.
   * 이 예산을 다 쓰면 그때 비로소 [maxUnknownPages] 를 한 번 소모한다.
   */
  pageWaitMs: 3 * 60_000,

  /**
   * **이번 감시에서 목록을 아직 한 번도 못 본** 상태에서의 예산. (§39-4)
   *
   * 목록을 본 적 있으면 지금의 `UNKNOWN` 은 전이 중일 가능성이 높아 길게 기다릴
   * 값어치가 있다. 한 번도 못 봤다면 화면 자체가 엉뚱한 곳일 가능성이 높고,
   * 그때 3분을 붙들면 사용자 눈에는 멈춘 것으로 보인다. **짧은 쪽을 지울 것.**
   */
  pageWaitFirstMs: 10_000,

  /**
   * 1단계를 누른 뒤 화면이 반응할 때까지 주는 시간. (§38-6)
   *
   * React 는 이벤트를 받아도 그 자리에서 다시 그리지 않고, 하단 바는 나타나면서
   * 애니메이션이 붙는다. 짧게 잡으면 **통했는데 안 통한 것으로** 읽고 인계해 버린다.
   * 화면 안에서 끝나는 동작이라 요청은 나가지 않는다.
   */
  reserveSettleMs: 900,

  /**
   * **2단계를 누른 뒤** 화면을 관찰하는 예산. (§39-6)
   *
   * 여기만 예산이 다르다. **대기열은 [예매] 를 누른 직후에 가장 잘 붙고**, 전환이
   * 시작되었다는 것은 요청이 이미 나갔다는 뜻이다. 6초에서 포기하면 몇 시간 기다린
   * 좌석을 대기창 앞에서 버린다. 관찰은 읽기라 요청이 나가지 않는다.
   */
  confirmSettleMs: 90_000,

  /** 2단계 뒤 화면을 다시 보는 간격. 읽기라 요청이 아니다. */
  confirmPollMs: 500,

  /**
   * "잔여석없음" 을 겪은 칸을 다시 눌러 보는 횟수 상한. (§19-2)
   *
   * 취소표를 노릴 때 "남이 먼저 잡음" 은 흔한 일이라 한 번 겪었다고 그 칸을 포기하지
   * 않는다. 다만 계속 실패하는 칸이면 요청만 늘어난다. **늘리지 말 것** (대원칙 2).
   */
  maxSoldOutRetries: 3,

  /** 연속 오류 허용 횟수. 넘으면 감시를 중지한다. */
  maxConsecutiveErrors: 3,

  /**
   * 감시 가능한 페이지가 아닐 때 허용할 **사이클** 수.
   *
   * **세는 단위는 요청이지 판독이 아니다.** [pageWaitMs] 동안 몇 번을 다시 읽든
   * 하나도 반영되지 않는다. 하나 늘어나려면 새로고침이 실제로 한 번 더 나가야
   * 한다. (§39-5)
   */
  maxUnknownPages: 2,
});

/** 저장소에서 읽은 값을 신뢰하지 않고 형태를 맞춘다. 모르는 키는 버린다. */
export function normalizeConfig(raw) {
  const config = { ...DEFAULT_WATCH_CONFIG };
  if (raw && typeof raw === 'object') {
    for (const key of Object.keys(DEFAULT_WATCH_CONFIG)) {
      const value = raw[key];
      if (typeof value === typeof DEFAULT_WATCH_CONFIG[key]) config[key] = value;
    }
  }
  const [minIntervalMs, maxIntervalMs] = clampRange(config.minIntervalMs, config.maxIntervalMs);
  config.minIntervalMs = minIntervalMs;
  config.maxIntervalMs = maxIntervalMs;
  return config;
}
