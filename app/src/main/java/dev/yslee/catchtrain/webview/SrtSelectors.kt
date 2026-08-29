package dev.yslee.catchtrain.webview

/**
 * DOM selector 와 사이트 관련 상수를 한곳에 모은다. (DESIGN.md §28)
 *
 * SRT 사이트의 HTML 구조가 바뀌면 **이 파일과 [SrtParserScript] 만** 수정한다.
 * UI / ViewModel / SelectionEngine 은 selector 를 전혀 모른다.
 *
 * 주의: 아래 selector 들은 "우선 시도" 후보 목록이다.
 * 실제 페이지에서 맞는 것이 없으면 [SrtParserScript] 의 휴리스틱
 * (표 헤더 텍스트로 열 위치를 찾고, 셀 텍스트로 좌석 상태를 판정)이 동작한다.
 * 그래서 selector 가 틀려도 앱이 곧바로 못 쓰게 되지는 않는다.
 */
object SrtSelectors {

    /** SRT 메인. 조회/로그인은 사용자가 WebView 안에서 직접 진행한다. */
    const val START_URL = "https://etk.srail.kr/main.do"

    /** 감시 대상으로 인정할 URL 조각 (조회 결과 페이지) */
    val SCHEDULE_URL_HINTS = listOf(
        "selectScheduleList",
        "/hpg/hra/01/",
    )

    val LOGIN_URL_HINTS = listOf(
        "selectLoginForm",
        "/cmc/01/",
    )

    /** 열차 목록 표 후보. 앞에서부터 시도한다. */
    val TRAIN_TABLE = listOf(
        "#result-form table",
        "table.tbl_wrap",
        ".tbl_wrap table",
        "table",
    )

    /** 표 안에서 한 편성을 나타내는 행 */
    val TRAIN_ROW = listOf(
        "tbody tr",
        "tr",
    )

    /**
     * 표 헤더 텍스트 → 열 의미 매핑에 사용할 키워드.
     * 실제 SRT 조회 결과 표의 헤더는 다음과 같은 형태다.
     *   구분 | 열차종류 | 출발 | 도착 | 특실 | 일반실 | 예약대기 | 운임
     *
     * 좌석 열 순서에 주의한다. **특실이 왼쪽, 일반실이 오른쪽**이다.
     * 헤더 매핑은 텍스트로 찾으므로 순서와 무관하지만,
     * 헤더를 못 찾았을 때의 위치 기반 근사 판정([SrtParserScript] guessSeatTexts)은
     * 이 순서를 그대로 전제한다.
     */
    object HeaderKeywords {
        val TRAIN_TYPE = listOf("열차종류", "열차", "구분")
        val DEPARTURE = listOf("출발")
        val ARRIVAL = listOf("도착")
        val GENERAL = listOf("일반실", "일반")
        val FIRST_CLASS = listOf("특실", "특별실")
        val WAITING = listOf("예약대기")
    }

    /**
     * "조회하기" 재조회 버튼 후보. (DESIGN.md §10 - 재조회 방식)
     * 결과 페이지에서 이 버튼을 누르면 같은 조건으로 다시 조회된다.
     *
     * 이 selector 들은 버튼을 **누르기 위한** 것이 아니라 버튼이 화면 어디에
     * 그려져 있는지 찾기 위한 것이다. 실제 클릭은 그 좌표에 진짜 터치를
     * 내려보내는 방식으로 한다. (SrtWebViewHost.tap)
     *
     * 이 목록은 "빠른 경로"일 뿐이다. 하나도 맞지 않으면
     * [RESEARCH_TEXTS] 기반 텍스트 휴리스틱이 버튼을 찾는다.
     */
    /*
     * 2026-08-10 실제 DOM 확인 결과 (selectScheduleList.do):
     *
     *   form#search-form (method=post, action=/hpg/hra/01/selectScheduleList.do)
     *     └ fieldset
     *         └ div#search_top_tag.tal_c.mgt30
     *             └ <input type="submit" value="조회하기"
     *                      class="btn_large wx200 btn_burgundy_dark2 val_m corner inquery_btn">
     *
     * - iframe / frameset 없음. 단일 document 다.
     * - 페이지에 input[type=submit] 은 이 버튼 하나뿐이다.
     * - 클릭하면 같은 URL 로 POST 되어 화면이 전환된다. (AJAX 아님)
     * - value 와 inquery_btn 은 의미 기반이라 비교적 안정적이다.
     *   btn_burgundy_dark2 / wx200 같은 표현용 클래스는 기대지 않는다.
     */
    val RESEARCH_BUTTON = listOf(
        "#search_top_tag input[type=submit][value='조회하기']",
        "#search-form input.inquery_btn",
        "input[type=submit][value='조회하기']",
        "input.inquery_btn[type=submit]",
        "input.inquery_btn",
        ".inquery_btn",
        "input[value='조회하기']",
        "#search_top_tag input[type=submit]",
        "#search-form input[type=submit]",
        "input[type=submit][value*='조회']",
        "a[onclick*='selectScheduleList']",
        "a[href*='selectScheduleList']",
        "a.btn_search",
        "#btnSearch",
    )

    /**
     * selector 로 못 찾을 때 버튼 문구로 찾는다. 앞에 있을수록 우선순위가 높다.
     * 비교 전에 공백을 모두 제거하므로 "다시 조회"도 "다시조회"로 걸린다.
     */
    val RESEARCH_TEXTS = listOf("조회하기", "다시조회", "재조회", "조회")

    /**
     * 재조회 버튼으로 오인하면 안 되는 문구.
     * 열차 목록의 "예약하기"를 눌러버리는 사고를 막는다.
     */
    val RESEARCH_TEXT_EXCLUDE = listOf(
        "예약", "예매", "선택", "취소", "닫기", "로그인", "로그아웃", "결제", "확인",
    )

    /**
     * 재조회 버튼이 들어있을 가능성이 높은 영역.
     * 텍스트 휴리스틱에서 이 안에 있는 후보를 (앞에 있는 것부터) 우선한다.
     */
    val SEARCH_FORM_SCOPES = listOf(
        "#search_top_tag",
        "#search-form",
        "form",
    )

    /**
     * 열차 목록 표 안의 "예약하기" 버튼 후보. (DESIGN.md §19)
     *
     * 재조회 버튼과 마찬가지로, 이 selector 는 버튼을 **누르기 위한** 것이 아니라
     * 버튼이 화면 어디에 그려져 있는지 찾기 위한 것이다. 실제 클릭은 그 좌표에
     * 진짜 터치를 내려보낸다. (SrtWebViewHost.tap)
     *
     * 탐색 범위는 항상 **조건을 만족한 그 행의 그 좌석 칸 안**으로 제한된다.
     * 표 전체에서 찾지 않는다. 다른 열차를 잡아버리면 안 되기 때문이다.
     *
     * 2026-08-10 실제 DOM 기준, 좌석 칸의 예약 링크는 다음 형태다.
     *   <td><a href="javascript:void(0);" class="btn_burgundy_dark small">예약하기</a></td>
     */
    val RESERVE_BUTTON = listOf(
        "a.btn_burgundy_dark",
        "a[href*='javascript']",
        "a",
        "input[type=submit]",
        "input[type=button]",
        "button",
    )

    /**
     * 좌석 칸에서 "지금 바로 예약" 을 뜻하는 문구. 앞에 있을수록 우선순위가 높다.
     * 비교 전에 공백을 모두 제거한다.
     */
    val RESERVE_TEXTS = listOf("예약하기", "좌석선택", "예매하기", "선택하기")

    /**
     * 예약하기로 오인하면 안 되는 문구.
     *
     * "예약대기"는 즉시 예약이 아니라 대기 신청이므로 자동으로 누르지 않는다.
     * (사용자가 그 칸을 체크해 두었더라도, 열린 것으로 보지 않는다)
     */
    val RESERVE_TEXT_EXCLUDE = listOf(
        "예약대기", "대기", "매진", "조회", "취소", "닫기", "로그인", "로그아웃",
    )

    /**
     * [예약하기] 를 누른 뒤 뜨는 **예약 실패 안내 화면** 판별용 문구. (DESIGN.md §19-2)
     *
     * 좌석이 열린 것을 보고 눌렀더라도 그 사이에 다른 사람이 먼저 잡으면
     * 예약 화면 대신 "잔여석없음" 안내가 뜬다. 이 화면에는 조회하기 버튼이 없으므로
     * 그대로 두면 감시가 이어지지 못한다. 그래서 목록 화면으로 되돌린다.
     *
     * 비교 전에 공백을 모두 제거하므로 "잔여석 없음"도 "잔여석없음"으로 걸린다.
     *
     * 2026-08-23 실제 DOM 기준 (confirmReservationInfo.do):
     *   <div class="box2 val_m tal_c"> … <span class="mgl20">잔여석없음</span></div>
     *   <div class="tal_c"><a href="#none" onclick="… selectScheduleList.do …"
     *        class="btn_large btn_blue val_m"><span>확인</span></a></div>
     */
    val RESERVE_FAILED_MARKERS = listOf(
        "잔여석없음",
        "잔여석이없",
        "좌석이없습니다",
        "예약가능한좌석이없",
        "예약가능한열차가없",
    )

    /** 예약 결과 화면(성공/실패 모두)으로 볼 URL 조각. 실패 판정의 보조 근거다. */
    val RESERVE_RESULT_URL_HINTS = listOf(
        "confirmReservationInfo",
        "/hpg/hra/02/",
        "/hpg/hra/03/",
    )

    /*
     * 예약 실패 안내 화면의 [확인] 버튼 selector 는 **일부러 두지 않는다.** (§19-2)
     *
     *   <a href="#none" onclick="… /hpg/hra/01/selectScheduleList.do …"
     *      class="btn_large btn_blue val_m"><span>확인</span></a>
     *
     * 이 버튼은 조회 폼을 새로 여는 링크라, 사용자가 사이트에서 직접 넣어 둔
     * 조회 조건(구간/날짜/시간)이 **전부 초기화된다.** 조건이 사라진 채로 감시를
     * 이어가면 다음 [조회하기] 가 엉뚱한 조회 결과를 가져온다.
     * 그래서 되돌리기는 WebView 뒤로 가기만 쓴다.
     * ([SrtWebViewHost.dismissReserveResult])
     */

    /**
     * 접속 차단 / 비정상 접근 안내 페이지 판별용 문구.
     *
     * 차단된 뒤에도 계속 조회 버튼을 누르면 차단이 길어진다.
     * 이 문구가 보이면 감시를 **즉시** 중지한다. (재시도하지 않는다)
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

    /**
     * 조회 조건 중 **출발일**이 들어 있는 폼 필드 이름. (화면 표시용)
     *
     * 앱은 조회 조건을 갖지 않지만(대원칙 4), 지금 무엇을 감시하고 있는지는
     * 사용자에게 보여 줘야 한다. 구간은 결과 표의 행에서 읽히는데 날짜는 표에 없다.
     *
     * 조회 폼(`form#search-form`)이 그 값을 들고 있다. [SrtPopupHost] 주석에 있듯
     * 달력 팝업이 `o.dptDt.value = 'YYYY.MM.DD'` 로 채워 넣는 바로 그 필드이고,
     * 결과 화면의 [조회하기] 가 같은 조건으로 다시 POST 하는 근거이기도 하다.
     *
     * 읽기만 한다. 값을 고치면 사용자가 넣어 둔 조회 조건이 바뀐다.
     */
    val SEARCH_DATE_FIELDS = listOf(
        "dptDt",
        "dptDt1",
    )

    /** 로그인 화면 판별용 */
    val LOGIN_MARKERS = listOf(
        "input[type=password]",
        "#srchDvNm01",
    )

    /**
     * **로그인 여부** 판별용. (감시 시작 전 확인 — [SrtLoginScript])
     *
     * [LOGIN_MARKERS] 와 목적이 다르다. 저쪽은 "지금 보고 있는 화면이 로그인 화면인가"를
     * 묻고, 이쪽은 "이 사용자가 사이트에 로그인되어 있는가"를 묻는다.
     * 조회 결과 화면은 **비로그인 상태에서도 정상적으로 보이기 때문에**([SrtLoginScript] 주석)
     * 화면 종류만으로는 로그인 여부를 알 수 없다.
     *
     * 2026-08-23 실제 DOM 확인 결과 (etk.srail.kr):
     *
     * 조회/예약 쪽 페이지(구 레이아웃)의 머리말에 로그인 상태 영역이 있다.
     *   div.header.header-e > div.global.clear > div.login_wrap.val_m.fl_r
     *     ├ (비로그인) <a href="/cmc/01/selectLoginForm.do?...">로그인</a>
     *     │            <a href="/cmc/02/selectJoinInfo.do?...">회원가입</a>
     *     └ (로그인)   같은 자리에 로그아웃 링크가 들어간다.
     *
     * 메인 화면(신 레이아웃, KRDS)은 구조가 다르다.
     *   div.header-container … <a class="btn-navi login" href="/cmc/01/selectLoginForm.do?…">로그인</a>
     */
    object LoginIndicator {

        /**
         * 로그인 상태 표시가 들어 있는 머리말 영역. 앞에서부터 시도한다.
         *
         * 이 안에서만 찾는 이유는 본문에 섞인 안내 문구를 상태로 오인하지 않기 위해서다.
         * 예를 들어 **로그인 화면 본문에는 "로그인 후 1시간 동안 입력이 없을 경우
         * 자동으로 로그아웃됩니다." 라는 문장이 있다.** 문서 전체에서 "로그아웃"을
         * 찾으면 비로그인 상태를 로그인으로 잘못 읽는다. (2026-08-23 실측)
         */
        val HEADER_SCOPES = listOf(
            ".login_wrap",
            ".global",
            ".header",
            ".header-utility",
            ".header-container",
            "#header",
        )

        /**
         * 로그아웃 링크의 href / onclick 에 들어가는 조각.
         * 문구보다 이쪽이 먼저다. 다국어 화면에서도 그대로 통한다.
         */
        val LOGOUT_HREF_HINTS = listOf(
            "logout",
            "Logout",
            "LOGOUT",
        )

        /**
         * 로그아웃 링크/버튼의 **정확한** 문구. 비교 전에 공백을 모두 제거하고,
         * 대소문자를 무시하며, **부분 일치를 쓰지 않는다.**
         * ("자동으로 로그아웃됩니다" 같은 안내 문장에 걸리면 안 된다)
         */
        val LOGOUT_TEXTS = listOf(
            "로그아웃",
            "logout",
            "signout",
        )

        /** 로그인 링크의 href 에 들어가는 조각. */
        val LOGIN_HREF_HINTS = listOf(
            "selectLoginForm",
            "/cmc/01/login",
        )

        /** 로그인 링크/버튼의 정확한 문구. [LOGOUT_TEXTS] 와 같은 규칙으로 비교한다. */
        val LOGIN_TEXTS = listOf(
            "로그인",
            "login",
            "signin",
        )
    }
}
