// 감시 루프 파라미터. (android: watcher/WatchConfig.kt)
//
// **값마다 근거를 붙여 둔다.** 이 파일에서 숫자를 고치는 것은 대개 대원칙 2 를
// 건드리는 일이라, 왜 그 값인지 모르는 채로 바꾸면 차단으로 돌아온다.
//
// 갱신 방식은 설정하지 않는다. 언제나 **새로고침**뿐이다. (§38-9, PLAN.md §E-4)

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

  /**
   * 조건을 만족하면 좌석 칸을 고르고 [예매] 까지 눌러 준다. (§19, §38-6)
   *
   * **M2 에서는 아무 효과가 없다.** 클릭 드라이버를 실측(M2.5)으로 정하기 전에는
   * 만들지 않기로 했기 때문이다 (PLAN.md §E-2-4, §E-9). 지금은 발견하면 알리고
   * 멈춘다. 값을 미리 둔 것은 M3 에서 이 자리에 붙는다는 표시다.
   */
  autoReserveEnabled: false,

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
