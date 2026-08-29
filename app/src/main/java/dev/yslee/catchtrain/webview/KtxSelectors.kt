package dev.yslee.catchtrain.webview

/**
 * 코레일(KTX) DOM selector 와 사이트 관련 상수를 한곳에 모은다. (DESIGN.md §28, §38)
 *
 * 사이트 구조가 바뀌면 **이 파일과 파서 스크립트만** 수정한다.
 * UI / ViewModel / SelectionEngine 은 selector 를 전혀 모른다.
 *
 * 여기 값은 전부 2026-08-29 실측이다 (동탄 → 김천구미, KTX-산천 10편성).
 * 두 번 받았고, 우연히 한 번은 로그인·예약대기 선택 상태, 한 번은 비로그인·일반실 선택
 * 상태여서 상태별 차이까지 확인됐다. 근거는 `docs/DESIGN.md §38`.
 *
 * **SRT 와 달리 "표 헤더로 열을 찾는" 휴리스틱이 없다.** 코레일 결과는 `<table>` 이 아니라
 * `<ul><li>` 이고 헤더 행 자체가 없다. 대신 좌석 상태가 class 로 드러나므로
 * class 를 1순위로 읽는다. ([dev.yslee.catchtrain.parser.SeatParser.fromClassNames])
 */
object KtxSelectors {

    /** 코레일 승차권 예매 시작점. 조회/로그인은 사용자가 WebView 안에서 직접 진행한다. */
    const val START_URL = "https://www.korail.com/ticket/main"

    /**
     * 조회 결과 화면의 URL.
     *
     * **판정에 쓰지 않는다.** 코레일은 `<form>` 이 없는 AJAX 라 조회해도 URL 이 바뀌지
     * 않고, 반대로 이 URL 인 채로 결과가 없을 수도 있다 (§38-5).
     * 페이지 종류는 [TRAIN_LIST_MARKERS] 로만 판정한다. 이 값은 로그·표시용이다.
     */
    const val SCHEDULE_URL = "https://www.korail.com/ticket/search/list"

    // --- 페이지 종류 판정 (URL 이 아니라 DOM 으로) --------------------------------

    /**
     * 조회 결과 화면임을 뜻하는 마커. 하나라도 있으면 결과 화면으로 본다.
     *
     * `div.tckWrap` 이 목록 컨테이너고 그 안의 `li.tckList` 가 편성 하나다.
     * 결과가 0건이어도 `tckWrap` 은 남을 수 있으므로,
     * 편성 수는 [TRAIN_ROW] 로 따로 센다. (0건이면 `NO_TRAIN`)
     */
    val TRAIN_LIST_MARKERS = listOf(
        "div.tckWrap",
        "div.sub_content.tab-tck_view",
    )

    /**
     * 목록 안에서 한 편성을 나타내는 항목.
     *
     * 목록 컨테이너를 따로 두지 않는다. 편성을 찾는 범위는 [SIGNATURE_SCOPES] 이고,
     * 거기서 못 찾으면 문서 전체에서 찾는다. 컨테이너 selector 를 하나 더 두면
     * 그것이 어긋났을 때 편성이 통째로 안 보인다.
     */
    val TRAIN_ROW = listOf(
        "li.tckList",
        "ul > li.tckList",
    )

    // --- 한 편성 안에서 읽는 것들 --------------------------------------------

    /**
     * 열차 종류. `<span class="train_sancheon_ticket"><span class="blind">KTX-산천</span></span>`
     *
     * 바깥 `span` 의 class 가 종류를 뜻하지만(`train_sancheon_ticket`) 실측에 한 종류만
     * 나와서 **class 목록을 고정하지 않는다.** 사람이 읽는 문구인 `.blind` 를 읽는다.
     * ITX·무궁화가 섞이면 어떻게 되는지는 아직 모른다. (§38-8)
     */
    val TRAIN_TYPE = listOf(
        ".flag_wrap .blind",
        ".tit_box .blind",
    )

    /** 열차 번호. `<span class="num">305</span>` — **식별 주키다.** (§38-4) */
    val TRAIN_NUMBER = listOf(
        ".flag_wrap .num",
        ".tit_box .num",
    )

    /**
     * 구간과 시각이 함께 들어 있는 제목.
     * `<h3 class="txt_bk"><span>동탄</span> → <span>김천구미</span> <span>(07:11 ~ 08:17)</span></h3>`
     *
     * `span` 세 개로 나뉘지만 순서에 기대지 않고 전체 텍스트를 [ROUTE_TIME_PATTERN] 으로 읽는다.
     */
    val ROUTE_HEADING = listOf(
        ".data_box h3",
        ".data_box h3.txt_bk",
        ".info_box h3",
    )

    /**
     * `동탄 → 김천구미 (07:11 ~ 08:17)` 에서 역 이름과 시각을 뽑는다.
     *
     * 화살표는 `→`(U+2192) 였지만 `->` 나 `~` 표기로 바뀔 여지를 남긴다.
     * 시각은 괄호 안에 `HH:MM ~ HH:MM` 형태로 붙는다.
     */
    const val ROUTE_TIME_PATTERN =
        """^\s*(.+?)\s*(?:→|->|~>)\s*(.+?)\s*\(\s*(\d{1,2}:\d{2})\s*~\s*(\d{1,2}:\d{2})\s*\)\s*$"""

    // --- 좌석 칸 ------------------------------------------------------------

    /**
     * 좌석 칸. 편성마다 **정확히 2개**이고 **순서가 등급이다.**
     *
     * `[0]` = 일반실, `[1]` = 특실. **SRT 와 반대다** (SRT 는 특실이 왼쪽). §38-3
     *
     * 매진 칸에는 등급 class 가 붙지 않아 위치 말고는 등급을 알 방법이 없다.
     * class 가 있으면 class 를 우선하고, 없을 때만 위치로 보정한다.
     */
    val SEAT_CELL = listOf(
        "div.price_box",
        ".tck_inner > div.price_box",
    )

    /** [SEAT_CELL] 안에서 실제로 누를 대상. `<a href="#none" title="">` */
    val SEAT_CELL_ANCHOR = listOf(
        "div.inner a",
        "a",
    )

    /** 좌석 칸 순서 → 등급. 위치 보정에 쓴다. (§38-3) */
    const val SEAT_CELL_INDEX_GENERAL = 0
    const val SEAT_CELL_INDEX_FIRST_CLASS = 1

    /**
     * 좌석 칸 class 토큰.
     *
     * 판정 규칙 자체는 [dev.yslee.catchtrain.parser.SeatParser] 에 있다.
     * 여기 상수는 JS 쪽에 같은 값을 넘기기 위해 둔다 — 두 곳에 문자열을 적어 두면 어긋난다.
     */
    object SeatCellClass {
        /** 일반실 (예약 가능) */
        const val GENERAL = "gen"

        /** 특실 (예약 가능) */
        const val FIRST_CLASS = "spe"

        /** **매진임박 — 아직 살 수 있다.** 문구에 "매진" 이 들어가는 함정. (§38-2) */
        const val SOLD_OUT_SOON = "sold_out_soon"

        /** 매진 */
        const val SOLD_OUT = "sold_out"

        /** 매진 (같은 편성에 예약대기가 있는 경우) */
        const val SOLD_OUT_WAIT = "sold_out_wait"

        /** 예약대기 — 발견으로 보지 않는다 (§18) */
        const val WAIT = "wait"

        /** 1단계에서 사용자가(또는 앱이) 고른 칸. 상태가 아니라 선택 표시다. */
        const val ACTIVE = "active"
    }

    // --- 재조회 -------------------------------------------------------------

    /**
     * 재조회 버튼. (DESIGN.md §10)
     *
     * 이 selector 는 버튼을 **누르기 위한** 것이 아니라 버튼이 화면 어디에 그려져 있는지
     * 찾기 위한 것이다. 실제 클릭은 그 좌표에 진짜 터치를 내려보낸다.
     *
     * 실측 위치:
     *   div.ticketSrchWrap > div.selectAreaWrap > div.left_wrap
     *     > div.inner.minner > div.btnWrap.btn_box > button.btn_bn-blue   "열차조회"
     *
     * `btn_bn-blue` 는 표현용 클래스라 기대지 않는다. 문구로 찾는 편이 안전하다.
     */
    val RESEARCH_BUTTON = listOf(
        "div.ticketSrchWrap div.btnWrap button",
        "div.selectAreaWrap div.btn_box button",
        "div.ticketSrchWrap button.btn_bn-blue",
    )

    /**
     * 재조회 버튼 문구. **부분일치를 쓰지 않고 완전일치로 비교한다.** (§38-5)
     *
     * 바로 옆에 `다음날 (26년09월02일) 조회`(`button.btn_lookup`)가 있다.
     * "조회" 부분일치로 찾으면 **사용자가 보던 날짜가 아닌 다음날을 조회해 버린다.**
     * 비교 전에 공백을 모두 제거한다.
     */
    val RESEARCH_TEXTS_EXACT = listOf("열차조회")

    /** 재조회 버튼으로 오인하면 안 되는 문구. 완전일치가 뚫렸을 때의 2차 방어다. */
    val RESEARCH_TEXT_EXCLUDE = listOf(
        "다음날", "이전날", "예매", "예약", "선택", "취소", "닫기", "로그인", "로그아웃", "결제",
    )

    /**
     * 조회 조건이 들어 있는 영역. 재조회 버튼 후보의 **가점**에만 쓴다.
     *
     * 이 안에 있는 버튼을 먼저 본다는 뜻이고, 밖에 있다고 버리지는 않는다.
     * 사이트가 바뀌어 이 영역을 못 찾아도 문구 완전일치가 여전히 버튼을 골라낸다.
     */
    val SEARCH_FORM_SCOPES = listOf(
        "div.ticketSrchWrap",
        "div.selectAreaWrap",
    )

    /**
     * 조회 조건의 **출발일**을 읽을 자리. 화면에 되비쳐 주기 위한 표시용이다.
     *
     * **아직 실측이 없어 비어 있다.** (§38-8) 코레일은 `<form>` 이 없어서 SRT 처럼
     * `getElementsByName` 으로 잡을 입력이 없고, 조회 폼의 날짜 입력이 어떤 구조인지
     * 확인하지 못했다. 추측으로 채우면 엉뚱한 날짜를 화면에 띄우게 되므로 비워 둔다.
     *
     * 비어 있으면 파서가 빈 문자열을 돌려주고, UI 는 날짜 없이 구간만 보여준다.
     * 감시 판정에는 쓰이지 않으므로 비어도 동작에는 아무 영향이 없다.
     * 실측되면 CSS selector 를 여기 넣기만 하면 된다. (`value` → 텍스트 순으로 읽는다)
     */
    val SEARCH_DATE_FIELDS = emptyList<String>()

    // --- 예매 1단계 / 2단계 (§38-6) -------------------------------------------

    /**
     * 예매 2단계 바. 1단계로 좌석 칸을 누르면 화면 최하단에 나타난다.
     *
     * 두 덤프 모두 이미 선택된 상태였다. 아무것도 안 고른 상태에서 이 요소가
     * DOM 에 아예 없는지, 숨겨져만 있는지는 아직 모른다. (§38-8)
     */
    val RESERVE_BAR = listOf(
        "div.ticket_reserv_wrap",
        "div.ticket_reserv_inner",
    )

    /**
     * 2단계 바에 표시되는 **1단계에서 고른 등급**. `<ul class="reserv_first"><li>일반실</li></ul>`
     *
     * 2단계를 누르기 전에 이 문구가 고른 등급과 같은지 확인한다. (§38-6-1)
     * 첫 `li` 만 본다 — `gen` 을 고르면 뒤에 `자유석1량>` 안내 버튼이 하나 더 붙는다.
     */
    val RESERVE_BAR_SEAT_LABEL = listOf(
        "ul.reserv_first > li:first-child",
        "ul.reserv_first li",
    )

    /**
     * 2단계 버튼 후보. 표현용 클래스(`btn_by-blue02` / `btn_bn-blue02`)는 상태마다
     * 달라서 공통인 `reservbtn` 만 쓴다.
     */
    val RESERVE_BUTTON = listOf(
        "div.reservbtnWrap button.reservbtn",
        "div.ticket_reserv button.reservbtn",
    )

    /**
     * **누를 수 있는 2단계 버튼 문구.** 완전일치. 공백 제거 후 비교한다. (§38-6-1)
     *
     * 허용목록에 없으면 누르지 않고 알림까지만 한다. 대원칙 3 을 지키는 쪽이다.
     */
    val RESERVE_TEXTS_EXACT = listOf("예매")

    /**
     * 2단계에서 **절대 누르면 안 되는** 버튼 문구.
     *
     * - `예약대기신청` : 예약대기는 발견으로 보지 않는다(§18). 누르면 원치 않는 대기가 걸린다.
     * - `입석+좌석 예매` : 사용자가 체크한 것은 좌석이다. 입석을 대신 잡아 주지 않는다.
     *
     * [RESERVE_TEXTS_EXACT] 가 완전일치라 이미 걸리지 않지만, 문구가 늘어났을 때를 위한
     * 2차 방어로 남긴다.
     */
    val RESERVE_TEXT_EXCLUDE = listOf(
        "예약대기신청", "예약대기", "입석+좌석예매", "입석", "취소", "닫기",
    )

    /** 눌러선 안 되는 비활성 버튼 표시. `disabled` 속성과 함께 붙어 있었다. */
    const val RESERVE_BUTTON_DISABLED_CLASS = "btn-disabled"

    // --- 예약 실패 / 차단 ------------------------------------------------------

    /**
     * 2단계를 누른 뒤 뜨는 **예약 실패 안내** 판별용 문구. (DESIGN.md §19-2)
     *
     * 좌석이 열린 것을 보고 눌러도 그 사이 다른 사람이 먼저 잡으면 실패한다.
     * SRT 문구를 그대로 옮겨 둔 것이고 **코레일 실측이 아직 없다.**
     * 실제 문구를 확인하면 여기부터 고친다.
     */
    val RESERVE_FAILED_MARKERS = listOf(
        "잔여석없음",
        "잔여석이없",
        "좌석이없습니다",
        "예약가능한좌석이없",
        "매진되었습니다",
        "선택하신좌석",
    )

    /**
     * 접속 차단 / 비정상 접근 안내 판별용 문구.
     *
     * 차단된 뒤에도 계속 조회하면 차단이 길어진다. 이 문구가 보이면 **즉시** 중지한다.
     * SRT 에서 쓰던 목록을 그대로 가져왔다. 코레일 실측은 없다 — 일부러 확인하러 갈 수 없다.
     */
    val BLOCKED_MARKERS = listOf(
        "비정상적인 접근",
        "비정상적인 방법",
        "접근이 차단",
        "접속이 차단",
        "이용이 제한",
        "차단되었습니다",
        "일시적으로 차단",
        "과도한 조회",
        "과도한 요청",
        "자동입력 방지",
        "매크로",
    )

    /** 로그인 화면 판별용 (지금 보고 있는 화면이 로그인 화면인가) */
    val LOGIN_MARKERS = listOf(
        "input[type=password]",
    )

    /**
     * **로그인 여부** 판별용. [LOGIN_MARKERS] 와 목적이 다르다. (§38-7, §27-1)
     *
     * 코레일도 SRT 처럼 **비로그인 상태에서 조회가 되고 좌석 선택까지 된다.**
     * (2차 덤프가 비로그인 상태였는데 목록도 1단계 선택도 정상이었다)
     * 그래서 화면 종류만으로는 로그인 여부를 알 수 없고 머리말을 따로 본다.
     */
    object LoginIndicator {

        /**
         * 로그인 상태 표시가 들어 있는 머리말 영역.
         *
         * 이 안에서만 찾는다. 본문이나 모바일 메뉴까지 뒤지면 오판한다 — 아래 주의 참고.
         */
        val HEADER_SCOPES = listOf(
            "ul.h_top_right",
            "div.header_top",
            "div.content_inner",
        )

        /**
         * 비로그인 상태에만 나타나는 링크. `<a class="btnGoLogin" href="/ticket/login">로그인</a>`
         */
        val LOGIN_LINK = listOf(
            "a.btnGoLogin",
            "a[href='/ticket/login']",
        )

        /**
         * 로그인 상태에만 나타나는 링크. `<a class="btnGoLogout" href="#none">로그아웃</a>`
         */
        val LOGOUT_LINK = listOf(
            "a.btnGoLogout",
        )

        /*
         * 쓰면 안 되는 것들 (2026-08-29 실측으로 확인된 함정, §38-7):
         *
         *  - `button.logoutBtn` : 클래스 이름이 고정이고 **문구만 바뀐다.**
         *    비로그인 상태에서 이 버튼의 텍스트는 "로그인" 이었다.
         *    클래스 이름으로 판정하면 항상 로그인 상태로 읽는다.
         *  - `li.loginY` (장바구니 / 마이페이지) : 두 상태 모두 DOM 에 있다.
         *    표시 여부는 CSS 로 갈리므로 존재 여부로 판정할 수 없다.
         *  - 본문 전체 텍스트에서 "로그아웃" 찾기 : SRT 에서 이미 오판했던 방법이다.
         */

        /**
         * 링크를 못 찾았을 때 쓰는 문구 비교. **완전일치**로만 본다.
         * 공백을 제거하고 대소문자를 무시한다.
         */
        val LOGOUT_TEXTS = listOf("로그아웃", "logout", "signout")

        /** [LOGOUT_TEXTS] 와 같은 규칙. */
        val LOGIN_TEXTS = listOf("로그인", "login", "signin")
    }

    /**
     * 재조회 결과가 실제로 바뀌었는지 보는 **DOM 서명**의 대상. (§38-5)
     *
     * 코레일은 AJAX 라 화면 전환이 없고 `onPageFinished` 도 오지 않는다.
     * `KtxWebViewHost.awaitSettled` 는 이 영역의 서명이 바뀌는 것으로 갱신을 감지한다.
     * 머리말·광고까지 넣으면 좌석과 무관한 변화에 반응하므로 목록만 본다.
     */
    val SIGNATURE_SCOPES = listOf(
        "div.tckWrap",
        "div.tabPage.active",
        "div.sub_content.tab-tck_view",
    )
}
