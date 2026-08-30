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
     * 로그인 화면. **앱이 스스로 여는 유일한 다른 URL 이다.** (§27-2)
     *
     * 메인에 닿았는데 비로그인이 **확실할 때만** 여기로 보낸다. 로그인 자체는 사용자가
     * 한다 (대원칙 3). 조회 결과 화면에서는 절대 하지 않는다 — 사용자가 넣어 둔 조회
     * 조건이 통째로 날아간다 (대원칙 4·5).
     */
    const val LOGIN_URL = "https://www.korail.com/ticket/login"

    /** [START_URL] 의 경로. URL 판정은 쿼리·해시를 뗀 경로 끝으로만 한다. */
    private const val MAIN_PATH = "/ticket/main"

    /**
     * 이 URL 이 코레일 **메인(승차권 예매 시작)** 화면인가. (§27-2)
     *
     * 경로 끝으로만 본다. `/ticket/search/list` 나 `/ticket/main/무엇` 이 여기에 걸리면
     * 사용자가 넣어 둔 조회 조건이 있는 화면을 앱이 갈아치우게 된다.
     */
    fun isMainPage(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val path = url.substringBefore('#').substringBefore('?').trimEnd('/')
        return path.endsWith(MAIN_PATH, ignoreCase = true) &&
            path.contains("korail.com", ignoreCase = true)
    }

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

    /*
     * --- 재조회 -------------------------------------------------------------
     *
     * **여기에 재조회 버튼 selector 를 다시 넣지 말 것.** 갱신은 페이지 새로고침(F5)이고,
     * 누를 버튼이 없다. (DESIGN.md §10, §38-9)
     *
     * 실측(2026-08-29, 모바일 폭 375px):
     *   div.ticketSrchWrap > div.selectAreaWrap > div.left_wrap
     *     > div.inner.minner > div.btnWrap.btn_box   ← display:none
     *       > button.btn_bn-blue   "열차조회"        ← DOM 에는 있지만 rect 가 0×0
     *
     * 즉 [열차조회] 는 **모바일 레이아웃에서 CSS 로 숨겨져 있다.** 문서에는 존재하므로
     * "selector 로 찾아진다" 는 것이 곧 "누를 수 있다" 가 아니다. 같은 자리에서 실제로
     * 보이는 버튼은 `button.btn_lookup` "다음날 (…) 조회" 하나뿐인데, 그것을 누르면
     * 사용자가 보던 날짜가 아닌 다음날을 조회한다. **절대 후보로 삼지 말 것.**
     *
     * 조회 조건은 DOM 이 아니라 localStorage["LS_TICKET_GENERAL"] 에 들어 있어서
     * 새로고침해도 살아남는다. 그래서 새로고침이 같은 조건의 재조회가 된다.
     */

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
         * 이 안에서만 찾는다. 본문까지 뒤지면 오판한다 — 아래 주의 참고.
         *
         * **이 영역은 모바일 폭에서 `display:none` 이다.** (2026-08-29 실측, §38-7)
         * 그래서 여기서도 [KtxLoginScript] 에서도 **보이는지를 따지지 않는다.**
         * 앱의 WebView 는 폰 폭이라 눈에 보이는 로그인 표시가 화면에 하나도 없다.
         */
        val HEADER_SCOPES = listOf(
            "ul.h_top_right",
            "div.header_top",
            "div.content_inner",
        )

        /**
         * 모바일 [전체메뉴] 안쪽. 문구 판정([LOGOUT_TEXTS])의 보조 범위다.
         *
         * `div.bottom_menu_choose > button.logoutBtn` 의 **문구**가 상태를 따라 바뀐다.
         * (비로그인 `로그인` / 로그인 `로그아웃`, 2026-08-29 실측)
         * **클래스 이름(`logoutBtn`)은 고정이라 판정에 쓰면 안 된다** — 아래 주의 참고.
         * 이 영역도 메뉴를 열기 전에는 `display:none` 이다.
         */
        val MENU_SCOPES = listOf(
            "div.bottom_menu_choose",
            "div.m_catetop_wrap",
        )

        /**
         * 비로그인 상태에만 나타나는 링크. `<a class="btnGoLogin" href="/ticket/login">로그인</a>`
         *
         * 2026-08-29 `www.korail.com/ticket/search/list` 에서 다시 확인했다.
         * 문서에 딱 하나 있고 `ul.h_top_right` 안이다.
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

    // --- 진단 전용 (감시 경로에서는 쓰지 않는다) --------------------------------

    /**
     * 출발역/도착역 선택 버튼. **누르지 않는다.** 상태를 읽는 데만 쓴다. (§38-10)
     *
     * 조회 결과 화면과 메인 화면의 클래스가 다르다.
     *  - 결과 화면: `<a href="#none" class="btn_pop btn_end btn_pop-openStationPop" title="출발역 선택">`
     *    (출발역 쪽에도 `btn_end` 가 붙는다. 사이트 쪽 오타지만 그대로 읽어야 한다)
     *  - 메인 화면: `<a class="btn_pop btn_start">` / `<a class="btn_pop btn_end">`
     *
     * `btn-disabled` 가 붙은 것은 사이트가 일부러 막아 둔 것이라 제외한다.
     */
    val STATION_POPUP_BUTTON = listOf(
        "a.btn_pop-openStationPop",
        "a.btn_pop.btn_start:not(.btn-disabled)",
        "a.btn_pop.btn_end:not(.btn-disabled)",
    )

    /**
     * 페이지가 **스스로** 그리는 모달. `window.open` 팝업이 아니다. (§38-10)
     *
     * 역/지역 선택 창, 달력, 안내 메시지가 전부 이 포털로 들어온다.
     * 이 개수가 늘었는지로 "창이 만들어지긴 했는가" 를 판정한다.
     */
    val PAGE_MODAL = listOf(
        "div.ReactModalPortal div.ReactModal__Overlay",
    )

    // --- 뷰포트 단위 보정 (§38-10) ---------------------------------------------

    /**
     * **`height: 100vh` 하나로 화면을 채우는 레이어.** 여기가 납작해지면 창은 열려도
     * 안 보인다. (§38-10)
     *
     * 사이트 CSS (번들 인라인, 2026-08-29 실측):
     * ```css
     * .layerPopup { width:100%; height:100vh; position:fixed;
     *               background:transparent; z-index:1005; top:0; left:0 }
     * ```
     *
     * 오버레이에는 **인라인 기하가 하나도 없다.** 코레일이 react-modal 에
     * `overlayClassName` 을 넘기기 때문인데, 그러면 react-modal 은 기본 오버레이
     * 인라인 스타일(`position:fixed; top/left/right/bottom:0`)을 **아예 붙이지 않는다.**
     * 실제 덤프의 오버레이 `style` 은 `background-color` 한 줄뿐이다.
     * 즉 이 창의 크기는 **전적으로 위 CSS 의 `100vh`** 에 달려 있고, 대체 경로가 없다.
     *
     * 안쪽 `div.ReactModal__Content` 는 `position:static; overflow:auto` 라 크기를
     * 만들어 주지 못한다(실측 40px, Chrome 에서도 같다). 실제 패널은 오버레이 기준으로
     * 놓이고 오버레이가 `overflow:hidden` 이라, 오버레이 높이가 0 이면 통째로 잘린다.
     *
     * [dev.yslee.catchtrain.webview.KtxParserScript.buildViewportFixScript] 가
     * `100vh` 가 실제로 깨졌을 때만 이 선택자에 픽셀 높이를 덮어쓴다.
     */
    val VIEWPORT_HEIGHT_LAYER = listOf(
        ".layerPopup",
    )
}
