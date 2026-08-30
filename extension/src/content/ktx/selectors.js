// ★ 코레일(KTX) DOM selector 단일 출처. (android: webview/KtxSelectors.kt 의 1:1 미러)
//
// **사이트가 바뀌면 여기부터 고친다.** 그리고 고쳤으면 안드로이드 쪽도 같이 본다 —
// 두 클라이언트는 같은 화면을 읽으므로 한쪽만 고치면 반드시 어긋난다.
// (승격 계획: ../../../shared/README.md, PLAN.md §E-8)
//
// **여기에 사이트 동작을 설명하지 않는다.** 값이 왜 그런지는 docs/DESIGN.md §38 에
// 한 벌만 있다. 이 파일에는 값과 §번호만 둔다 — 설명을 복사하면 한쪽이 반드시 뒤처진다.

/** 승차권 예매 시작점. 조회도 로그인도 사용자가 직접 한다. */
export const START_URL = 'https://www.korail.com/ticket/main';

/**
 * 로그인 화면.
 *
 * **확장은 여기로 스스로 이동하지 않는다.** 안드로이드는 메인에서 비로그인이면
 * 스스로 보냈지만(§27-2), 사용자의 탭을 확장이 조용히 갈아 끼우는 것은
 * 브라우저에서 명백히 적대적인 동작이다. 팝업의 버튼으로만 연다. (PLAN.md §E-6-5)
 */
export const LOGIN_URL = 'https://www.korail.com/ticket/login';

const MAIN_PATH = '/ticket/main';

/** 이 URL 이 코레일 메인 화면인가. 경로 끝으로만 본다. (§27-2) */
export function isMainPage(url) {
  if (!url) return false;
  const path = url.split('#')[0].split('?')[0].replace(/\/+$/, '').toLowerCase();
  return path.endsWith(MAIN_PATH) && path.includes('korail.com');
}

/** 감시 대상 사이트인가. host permission 과 같은 범위여야 한다. */
export function isKorailUrl(url) {
  return typeof url === 'string' && /^https:\/\/www\.korail\.com\//.test(url);
}

/**
 * 조회 결과 화면의 URL. **판정에 쓰지 않는다** — AJAX 라 URL 이 안 바뀐다. (§38-5)
 * 로그·표시용이다.
 */
export const SCHEDULE_URL = 'https://www.korail.com/ticket/search/list';

// --- 페이지 종류 판정 (URL 이 아니라 DOM 으로) --------------------------------

/** 조회 결과 화면 마커. 하나라도 있으면 결과 화면으로 본다. (0건이어도 남는다) */
export const TRAIN_LIST_MARKERS = [
  'div.tckWrap',
  'div.sub_content.tab-tck_view',
];

/** 목록 안의 한 편성. */
export const TRAIN_ROW = [
  'li.tckList',
  'ul > li.tckList',
];

// --- 한 편성 안에서 읽는 것들 --------------------------------------------

/** 열차 종류. class 목록이 고정이 아니라 사람이 읽는 문구를 읽는다. (§38-8) */
export const TRAIN_TYPE = [
  '.flag_wrap .blind',
  '.tit_box .blind',
];

/** 열차 번호. **식별 주키다.** (§38-4) */
export const TRAIN_NUMBER = [
  '.flag_wrap .num',
  '.tit_box .num',
];

/** 구간과 시각이 함께 들어 있는 제목. */
export const ROUTE_HEADING = [
  '.data_box h3',
  '.data_box h3.txt_bk',
  '.info_box h3',
];

/** `동탄 → 김천구미 (07:11 ~ 08:17)` 에서 역 이름과 시각을 뽑는다. */
export const ROUTE_TIME_PATTERN =
  '^\s*(.+?)\s*(?:→|->|~>)\s*(.+?)\s*\(\s*(\d{1,2}:\d{2})\s*~\s*(\d{1,2}:\d{2})\s*\)\s*$';

// --- 좌석 칸 ------------------------------------------------------------

/** 좌석 칸. 편성마다 정확히 2개이고 **순서가 등급이다.** (§38-3) */
export const SEAT_CELL = [
  'div.price_box',
  '.tck_inner > div.price_box',
];

/** [SEAT_CELL] 안에서 실제로 누를 대상. */
export const SEAT_CELL_ANCHOR = [
  'div.inner a',
  'a',
];

/** 좌석 칸 순서 → 등급. **SRT 와 반대다.** (§38-3) */
export const SEAT_CELL_INDEX_GENERAL = 0;
export const SEAT_CELL_INDEX_FIRST_CLASS = 1;

/**
 * 좌석 칸 class 토큰. (§38-2)
 *
 * 판정 규칙 자체는 `domain/seat-status.js` 에 있다 — domain 은 사이트를 몰라야 하므로
 * 토큰 사본을 따로 들고 있고, 두 벌이 어긋나지 않는지는 테스트가 지킨다.
 */
export const SeatCellClass = Object.freeze({
  /** 일반실 (예약 가능) */
  general: 'gen',
  /** 특실 (예약 가능) */
  firstClass: 'spe',
  /** **매진임박 — 아직 살 수 있다.** 문구에 "매진" 이 들어가는 함정 */
  soldOutSoon: 'sold_out_soon',
  /** 매진 */
  soldOut: 'sold_out',
  /** 매진 (같은 편성에 예약대기가 있는 경우) */
  soldOutWait: 'sold_out_wait',
  /** 예약대기 — 발견으로 보지 않는다 (§18) */
  wait: 'wait',
  /** 1단계에서 고른 칸. 상태가 아니라 선택 표시다 */
  active: 'active',
});

/*
 * --- 재조회 -------------------------------------------------------------
 *
 * **여기에 조회 버튼 selector 를 넣지 말 것.** 갱신은 새로고침이다. (§38-9, PLAN.md §E-4)
 * PC 폭에서는 [열차조회] 가 실제로 보이지만, 그것을 누르면 사용자가 조회한 조건이 아니라
 * **화면 폼의 지금 값**으로 조회가 나간다. 조회 조건은 앱이 갖지 않는다 (대원칙 4).
 */

/**
 * 조회 조건의 **출발일**. 화면 표시용이다.
 * **아직 실측이 없어 비어 있다** (§38-8). 추측으로 채우면 엉뚱한 날짜를 띄운다.
 */
export const SEARCH_DATE_FIELDS = [];

/**
 * 사용자가 넣은 조회 조건이 통째로 들어 있는 `localStorage` 키. (§38-9)
 *
 * URL 에는 조회 조건이 하나도 없다. 새로고침해도 조건이 살아남는 이유가 이것이고,
 * SPA 가 이 값으로 같은 조회를 스스로 되풀이한다.
 *
 * **확장은 이 값을 읽어서 해시만 만들고 버린다.** 조회 조건은 앱이 갖지 않는다
 * (대원칙 4) — 필요한 것은 "감시를 시작할 때와 같은 조회인가" 뿐이다.
 * (PLAN.md §E-6-3 예외 17, `content/ktx/query.js`)
 */
export const QUERY_STORAGE_KEY = 'LS_TICKET_GENERAL';

// --- 예매 1단계 / 2단계 (§38-6) -------------------------------------------

/** 예매 2단계 바. 1단계로 좌석 칸을 누르면 화면 최하단에 나타난다. */
export const RESERVE_BAR = [
  'div.ticket_reserv_wrap',
  'div.ticket_reserv_inner',
];

/** 2단계 바에 표시되는 **1단계에서 고른 등급**. 첫 `li` 만 본다. (§38-6-1) */
export const RESERVE_BAR_SEAT_LABEL = [
  'ul.reserv_first > li:first-child',
  'ul.reserv_first li',
];

/** 2단계 버튼 후보. 표현용 클래스는 상태마다 달라서 `reservbtn` 만 쓴다. */
export const RESERVE_BUTTON = [
  'div.reservbtnWrap button.reservbtn',
  'div.ticket_reserv button.reservbtn',
];

/** **누를 수 있는 2단계 버튼 문구.** 완전일치. (§38-6-1, 대원칙 3) */
export const RESERVE_TEXTS_EXACT = ['예매'];

/** 2단계에서 **절대 누르면 안 되는** 문구. 완전일치의 2차 방어다. */
export const RESERVE_TEXT_EXCLUDE = [
  '예약대기신청', '예약대기', '입석+좌석예매', '입석', '취소', '닫기',
];

/** 눌러선 안 되는 비활성 버튼 표시. */
export const RESERVE_BUTTON_DISABLED_CLASS = 'btn-disabled';

// --- 예약 실패 / 차단 ------------------------------------------------------

/**
 * 2단계 뒤에 뜨는 **예약 실패 안내** 문구. (§19-2)
 * **아직 SRT 값 그대로다** (§38-8) — 코레일 실측이 되면 여기부터 고친다.
 */
export const RESERVE_FAILED_MARKERS = [
  '잔여석없음',
  '잔여석이없',
  '좌석이없습니다',
  '예약가능한좌석이없',
  '매진되었습니다',
  '선택하신좌석',
];

/** 접속 차단 / 비정상 접근 안내. 보이면 **즉시** 중지한다. */
export const BLOCKED_MARKERS = [
  '비정상적인 접근',
  '비정상적인 방법',
  '접근이 차단',
  '접속이 차단',
  '이용이 제한',
  '차단되었습니다',
  '일시적으로 차단',
  '과도한 조회',
  '과도한 요청',
  '자동입력 방지',
  '매크로',
];

/** 지금 보고 있는 화면이 로그인 화면인가. (로그인 **여부** 는 아래 것이 본다) */
export const LOGIN_MARKERS = [
  'input[type=password]',
];

/**
 * **로그인 여부** 판별. [LOGIN_MARKERS] 와 목적이 다르다. (§38-7, §27-1)
 *
 * 비로그인에서도 조회가 되고 좌석 선택까지 되므로 화면 종류로는 알 수 없다.
 * **보이는지를 따지지 않고 DOM 에 있는지만** 본다 — 폰 폭에서 머리말이 통째로
 * `display:none` 이라, 가시성으로 거르면 언제나 UNKNOWN 이 된다.
 * PC 폭에서는 실제로 보이지만 판정 규칙을 폭으로 나누면 두 벌이 되고 반드시 어긋난다.
 */
export const LoginIndicator = Object.freeze({
  HEADER_SCOPES: ['ul.h_top_right', 'div.header_top', 'div.content_inner'],
  MENU_SCOPES: ['div.bottom_menu_choose', 'div.m_catetop_wrap'],
  /** 비로그인 상태에만 나타나는 링크 */
  LOGIN_LINK: ['a.btnGoLogin', "a[href='/ticket/login']"],
  /** 로그인 상태에만 나타나는 링크 */
  LOGOUT_LINK: ['a.btnGoLogout'],
  /** 링크를 못 찾았을 때의 문구 비교. **완전일치**로만 본다 */
  LOGOUT_TEXTS: ['로그아웃', 'logout', 'signout'],
  LOGIN_TEXTS: ['로그인', 'login', 'signin'],
  /*
   * 쓰면 안 되는 것 (§38-7):
   *  - `button.logoutBtn` : 클래스 이름이 고정이고 문구만 바뀐다 → 항상 로그인으로 읽는다
   *  - `li.loginY`        : 두 상태 모두 DOM 에 있다
   *  - 본문에서 "로그아웃" 찾기 : 로그인 화면 안내문에 그 단어가 있다
   */
});

/** 재조회 결과가 실제로 바뀌었는지 보는 **DOM 서명**의 대상. (§38-5) */
export const SIGNATURE_SCOPES = [
  'div.tckWrap',
  'div.tabPage.active',
  'div.sub_content.tab-tck_view',
];

// --- 진단 전용 (감시 경로에서는 쓰지 않는다) --------------------------------

/** 출발역/도착역 선택 버튼. **누르지 않는다.** 상태를 읽는 데만 쓴다. (§38-10) */
export const STATION_POPUP_BUTTON = [
  'a.btn_pop-openStationPop',
  'a.btn_pop.btn_start:not(.btn-disabled)',
  'a.btn_pop.btn_end:not(.btn-disabled)',
];

/** 페이지가 스스로 그리는 모달. `window.open` 팝업이 아니다. (§38-10) */
export const PAGE_MODAL = [
  'div.ReactModalPortal div.ReactModal__Overlay',
];

/*
 * `VIEWPORT_HEIGHT_LAYER` 는 옮기지 않았다. `100vh` 가 0 으로 굳는 것은
 * **WebView 가 높이를 얻기 전에 문서를 받는** 안드로이드 고유의 사고다 (§38-10).
 * 브라우저 탭에는 그런 순간이 없다. 확장에서 실제로 관찰되면 그때 옮긴다.
 */
