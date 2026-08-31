// 감시 상태. (android: watcher/WatchState.kt, DESIGN.md §8)
//
// **팝업은 이 파일의 값만 보고 화면을 그린다.** (§21) selector 도 chrome API 도 모른다.
//
// 안드로이드에 있는데 여기 없는 것:
//  - `PAUSED` : Activity lifecycle 이 없다. 팝업을 닫는 것은 일시정지가 아니고,
//    탭이 배경으로 가는 것도 아니다. 감시는 탭이 살아 있는 동안 계속 돈다.

export const WatchState = Object.freeze({
  IDLE: 'IDLE',
  LOADING: 'LOADING',
  ANALYZING: 'ANALYZING',
  WAITING: 'WAITING',
  MATCHED: 'MATCHED',

  /** 좌석 칸을 고르고 [예매] 까지 눌렀다. **결제는 사용자가 한다.** (§38-6) */
  RESERVED: 'RESERVED',

  /**
   * **1단계까지만 눌렀다.** 좌석 칸은 골라 뒀고 [예매] 는 사람이 눌러야 한다. (§38-6-1)
   *
   * 실패가 아니라 **일부러 멈춘 것**이다. 2단계 버튼이 허용목록에 없거나, 누르기 전
   * 확인이 어긋났거나, 누른 결과를 확신할 수 없을 때 여기로 온다. (대원칙 3)
   *
   * 감시는 여기서 끝난다 — 다음 사이클의 새로고침이 골라 둔 선택을 지우기 때문이다.
   */
  SEAT_SELECTED: 'SEAT_SELECTED',

  ERROR: 'ERROR',
  STOPPED: 'STOPPED',
});

export function watchStateIsRunning(state) {
  return state === WatchState.LOADING ||
    state === WatchState.ANALYZING ||
    state === WatchState.WAITING;
}

export function watchStateLabel(state) {
  switch (state) {
    case WatchState.LOADING: return '페이지 로딩 중';
    case WatchState.ANALYZING: return '페이지 분석 중';
    case WatchState.WAITING: return '다음 확인 대기';
    case WatchState.MATCHED: return '좌석 발견';
    case WatchState.RESERVED: return '예매 누름';
    case WatchState.SEAT_SELECTED: return '좌석 골라 둠';
    case WatchState.ERROR: return '오류';
    case WatchState.STOPPED: return '중지됨';
    default: return '대기';
  }
}

/** UI 표시용 신호등. (§8) */
export function watchStateIndicator(state) {
  if (watchStateIsRunning(state)) return '🟢';
  switch (state) {
    case WatchState.MATCHED: return '🎯';
    case WatchState.RESERVED: return '🎫';
    case WatchState.SEAT_SELECTED: return '🎟';
    case WatchState.ERROR: return '🔴';
    default: return '⚪';
  }
}

/** 에러 구분. (§27) */
export const WatchError = Object.freeze({
  NETWORK_ERROR: 'NETWORK_ERROR',
  PAGE_LOAD_ERROR: 'PAGE_LOAD_ERROR',
  DOM_PARSE_ERROR: 'DOM_PARSE_ERROR',
  LOGIN_REQUIRED: 'LOGIN_REQUIRED',
  SESSION_EXPIRED: 'SESSION_EXPIRED',
  UNKNOWN_PAGE: 'UNKNOWN_PAGE',

  /** 새로고침했지만 감시할 수 있는 화면에 닿지 못했다. (§38-9) */
  REFRESH_FAILED: 'REFRESH_FAILED',

  /** 접속이 차단되었다. 재시도하지 않고 즉시 멈춘다. */
  BLOCKED: 'BLOCKED',

  /** ★ 확장 고유 — 감시하던 탭이 사라졌다. (PLAN.md §E-6-3 예외 16) */
  TAB_GONE: 'TAB_GONE',

  /** ★ 확장 고유 — 사용자가 조회 조건을 바꿔 다시 조회했다. (예외 17) */
  QUERY_CHANGED: 'QUERY_CHANGED',
});

export function watchErrorTitle(error) {
  switch (error) {
    case WatchError.NETWORK_ERROR: return '네트워크 오류';
    case WatchError.PAGE_LOAD_ERROR: return '페이지 오류';
    case WatchError.DOM_PARSE_ERROR: return '분석 오류';
    case WatchError.LOGIN_REQUIRED: return '로그인 필요';
    case WatchError.SESSION_EXPIRED: return '세션 만료';
    case WatchError.UNKNOWN_PAGE: return '감시할 수 없는 페이지';
    case WatchError.REFRESH_FAILED: return '새로고침 실패';
    case WatchError.BLOCKED: return '접속 차단';
    case WatchError.TAB_GONE: return '감시하던 탭이 없음';
    case WatchError.QUERY_CHANGED: return '조회 조건이 바뀜';
    default: return '오류';
  }
}

export function watchErrorGuide(error) {
  switch (error) {
    case WatchError.NETWORK_ERROR:
      return '네트워크 상태를 확인한 뒤 다시 시도하세요.';
    case WatchError.PAGE_LOAD_ERROR:
      return '코레일 페이지를 불러오지 못했습니다.';
    case WatchError.DOM_PARSE_ERROR:
      return '페이지 구조를 읽지 못했습니다. 조회 결과 화면인지 확인하세요.';
    case WatchError.LOGIN_REQUIRED:
      return '코레일 탭에서 직접 로그인한 뒤 다시 시작하세요.';
    case WatchError.SESSION_EXPIRED:
      return '세션이 만료되었습니다. 다시 로그인하세요.';
    case WatchError.UNKNOWN_PAGE:
      return '열차 조회 결과 화면에서 감시를 시작하세요.';
    case WatchError.REFRESH_FAILED:
      return '새로고침했지만 열차 목록 화면으로 돌아오지 못했습니다. ' +
        '차단 안내나 오류 화면이 떠 있는지 먼저 확인하고, 조회 결과 화면에서 다시 시작하세요.';
    case WatchError.BLOCKED:
      return '접속이 차단된 것으로 보입니다. 감시를 멈췄습니다. ' +
        '한동안 기다린 뒤 재조회 간격을 최대한 늘려서 다시 시작하세요.';
    case WatchError.TAB_GONE:
      return '감시하던 코레일 탭이 닫혔습니다. 다시 조회한 뒤 감시를 시작하세요.';
    case WatchError.QUERY_CHANGED:
      return '코레일 화면에서 조회 조건이 바뀌었습니다. ' +
        '체크해 둔 칸은 그 조회 결과에만 의미가 있어 비웠습니다. 다시 골라 주세요.';
    default:
      return '';
  }
}

/**
 * 자동 예매가 **어느 단계**에서 끝났는지. (§38-6)
 *
 * 두 단계는 성질이 다르다. 1단계는 아직 아무것도 잡지 않은 되돌릴 수 있는 동작이고,
 * 2단계는 좌석을 잡는다. 그래서 실패를 볼 때 **어느 단계에서 멈췄는지**를 함께 본다 —
 * `CONFIRM` 단계의 실패는 "1단계까지는 되어 있다" 는 뜻이기도 하다.
 */
export const ReserveStage = Object.freeze({
  SELECT: 'SELECT',
  CONFIRM: 'CONFIRM',
});

export function reserveStageLabel(stage) {
  return stage === ReserveStage.SELECT ? '좌석 선택' : '예매';
}

/**
 * 자동 예매 클릭의 결과. (android: `ReserveResult`, 이름을 같게 둔다)
 *
 * 성공([CLICKED])이란 **"[예매] 를 눌렀고 화면이 반응했다"** 는 뜻일 뿐이고,
 * 예약이 확정되었다는 뜻이 아니다. 좌석 선택과 결제는 사용자가 직접 한다. (대원칙 3)
 */
export const ReserveResult = Object.freeze({
  CLICKED: 'CLICKED',
  NO_CHANGE: 'NO_CHANGE',
  SOLD_OUT: 'SOLD_OUT',
  ROW_NOT_FOUND: 'ROW_NOT_FOUND',
  CELL_NOT_FOUND: 'CELL_NOT_FOUND',
  SEAT_NOT_SELECTED: 'SEAT_NOT_SELECTED',
  NOT_ALLOWED: 'NOT_ALLOWED',
  MISMATCH: 'MISMATCH',
  BUTTON_NOT_FOUND: 'BUTTON_NOT_FOUND',

  /** 눌렀는데 **모르는 안내가 떴다.** 성공인지 실패인지 확신할 수 없다 (§38-8) */
  UNKNOWN_NOTICE: 'UNKNOWN_NOTICE',

  FAILED: 'FAILED',
  SKIPPED: 'SKIPPED',
});

export function reserveResultLabel(result) {
  switch (result) {
    case ReserveResult.CLICKED: return '[예매] 누름';
    case ReserveResult.NO_CHANGE: return '눌렀지만 화면이 그대로';
    case ReserveResult.SOLD_OUT: return '잔여석 없음';
    case ReserveResult.ROW_NOT_FOUND: return '편성을 다시 찾지 못함';
    case ReserveResult.CELL_NOT_FOUND: return '좌석 칸을 특정 못 함';
    case ReserveResult.SEAT_NOT_SELECTED: return '좌석이 골라지지 않음';
    case ReserveResult.NOT_ALLOWED: return '누를 수 있는 [예매] 가 아님';
    case ReserveResult.MISMATCH: return '고른 등급과 하단 바가 다름';
    case ReserveResult.BUTTON_NOT_FOUND: return '예매 바를 찾지 못함';
    case ReserveResult.UNKNOWN_NOTICE: return '모르는 안내가 떴음';
    case ReserveResult.FAILED: return '페이지 오류';
    case ReserveResult.SKIPPED: return '누르지 않음';
    default: return '';
  }
}

/** 팝업이 받는 상태 스냅샷의 초기값. **평범한 객체다** — 메시지로 그대로 건너간다. */
export const INITIAL_STATUS = Object.freeze({
  state: WatchState.IDLE,
  tabId: null,
  lastCheckedAt: null,
  nextCheckInMs: null,
  cycleCount: 0,
  trainCount: 0,
  foundCount: 0,
  matches: [],
  searchDate: '',
  error: null,
  message: null,

  /** 마지막 자동 예매 시도. `{ match, stage, result, detail }` 또는 null. */
  reserve: null,
});
