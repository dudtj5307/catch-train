package dev.yslee.catchtrain.webview

import org.json.JSONArray
import org.json.JSONObject

/**
 * WebView 안에서 실행할 DOM 분석/탐색 스크립트. (DESIGN.md §13, §38)
 *
 * 설계 원칙
 *  - 서버 API 를 직접 호출하지 않고, **현재 화면에 렌더링된 DOM** 만 읽는다.
 *  - selector 는 [KtxSelectors] 에서 주입받는다. (§28)
 *  - 좌표만 알려주고 **아무것도 클릭하지 않는다.** 클릭은 Kotlin 쪽 진짜 터치가 한다.
 *
 * ## SRT 파서와 무엇이 다른가
 *
 * SRT 판(`v0.1.1-srt` 태그의 `KtxParserScript`)은 `<table>` 을 전제로 했고, 절반이
 * "표 헤더 텍스트로 좌석 열 위치를 찾는" 휴리스틱이었다. 코레일 결과는 `<ul><li>` 이고
 * **헤더 행 자체가 없어서** 그 부분이 통째로 사라졌다. (§38-1)
 *
 * 대신 좌석 상태가 `div.price_box` 의 **class** 로 드러나므로 class 를 1순위로 읽는다.
 * 텍스트로 읽으면 `특실(매진임박) 37,200원` 을 매진으로 오판한다. (§38-2)
 * 판정 규칙은 [dev.yslee.catchtrain.parser.SeatParser] 와 같은 것을 쓴다 —
 * 문자열 상수는 [KtxSelectors.SeatCellClass] 한 곳에만 두고 양쪽이 그것을 참조한다.
 *
 * 예매가 2단계라는 점도 다르다. (§38-6) 그래서 탐색 스크립트가 셋이다.
 *  - [buildSelectScript]        1단계: 좌석 칸(`price_box > a`) 의 좌표
 *  - [buildSelectConfirmScript] 1단계 직후: 그 칸에 `active` 가 붙었는지 확인 (읽기만)
 *  - [buildReserveScript]       2단계: 하단 바의 `예매` 버튼 좌표 (검증을 통과할 때만)
 *
 * 반환값은 객체이며, WebView 가 JSON 으로 직렬화해서 Kotlin 쪽에 넘긴다.
 * [build] 의 형식:
 * ```json
 * {
 *   "status": "TRAIN_LIST|NO_TRAIN|LOGIN_REQUIRED|SESSION_EXPIRED|BLOCKED|UNKNOWN_PAGE",
 *   "url": "...", "title": "...", "rowCount": 10, "searchDate": "",
 *   "trains": [{
 *      "trainNumber": "305", "trainType": "KTX-산천",
 *      "departureStation": "동탄", "arrivalStation": "김천구미",
 *      "departureTime": "07:11", "arrivalTime": "08:17",
 *      "generalSeatText": "일반실25,700원", "generalSeatStatus": "AVAILABLE",
 *      "generalSeatClass": "price_box fl-l gen", "generalCellIndex": 0,
 *      "firstClassSeatText": "매진", "firstClassSeatStatus": "SOLD_OUT",
 *      "firstClassSeatClass": "price_box fl-l sold_out", "firstClassCellIndex": 1,
 *      "rowKey": "2:1a2b3c", "rowIndex": 0
 *   }],
 *   "warnings": ["..."]
 * }
 * ```
 */
object KtxParserScript {

    /** DOM 을 읽어 열차 목록 JSON 을 돌려준다. 부작용이 없다. */
    fun build(): String {
        val config = buildString {
            append("{")
            append("listMarkers:").append(jsArray(KtxSelectors.TRAIN_LIST_MARKERS)).append(",")
            append("headingPattern:").append(jsString(KtxSelectors.ROUTE_TIME_PATTERN)).append(",")
            append("dateFields:").append(jsArray(KtxSelectors.SEARCH_DATE_FIELDS)).append(",")
            append("loginMarkers:").append(jsArray(KtxSelectors.LOGIN_MARKERS)).append(",")
            append("blockedMarkers:").append(jsArray(KtxSelectors.BLOCKED_MARKERS))
            append("}")
        }
        return SCRIPT_TEMPLATE.withSignature().replace(CONFIG_PLACEHOLDER, config)
    }

    /**
     * **예매 1단계.** 그 열차의 그 좌석 칸(`price_box > a`)이 화면 어디에 있는지. (§38-6)
     *
     * 탐색 범위가 재조회와 결정적으로 다르다. 조회 버튼은 화면에 하나뿐이라 문서 전체에서
     * 찾아도 되지만, 좌석 칸은 편성마다 둘씩 있으므로 **그 편성의 그 칸**만 본다.
     * 범위를 좁히지 못하면 좌표를 돌려주지 않는다. 엉뚱한 열차를 잡느니 안 누르는 편이 낫다.
     *
     * 행을 다시 찾는 기준은 위치가 아니라 내용 요약값([ReserveTarget.rowKey])이고,
     * 그 위에 **열차 번호**를 한 번 더 확인한다. 코레일은 같은 시각에 다른 편성이
     * 있으므로 시각만으로는 모자란다. (§38-4)
     *
     * 매진(`sold_out`)·예약대기(`wait`) 칸은 **누르지 않는다.** 예약대기는 발견으로 보지
     * 않기로 했고(§18), 매진 칸을 눌렀을 때 무엇이 일어나는지는 아직 실측이 없다. (§38-8)
     *
     * 반환 형식:
     * ```json
     * {"found": true, "tappable": true, "x": 1.0, "y": 2.0, "cellIndex": 0, "seatStatus": "AVAILABLE"}
     * {"found": false, "reason": "ROW_NOT_FOUND|ROW_AMBIGUOUS|ROW_MISMATCH|CELL_NOT_FOUND|SEAT_NOT_AVAILABLE"}
     * ```
     */
    fun buildSelectScript(
        viewWidthPx: Int,
        viewHeightPx: Int,
        target: ReserveTarget,
    ): String = SELECT_TEMPLATE.withSignature().withTapPoint()
        .replace(CONFIG_PLACEHOLDER, targetConfig(target))
        .replace(VIEW_PLACEHOLDER, "{w:$viewWidthPx,h:$viewHeightPx}")

    /**
     * **1단계 직후 확인.** 누른 칸에 `active` 가 붙었는지 읽는다. (§38-6-1 의 확인 1)
     *
     * 아무것도 누르지 않고 읽기만 한다. 목록 전체에서 `active` 가 붙은 칸이
     * **정확히 하나**이고 그것이 우리가 누른 칸일 때만 성공으로 본다.
     * 두 개 이상이면 우리가 무엇을 골랐는지 알 수 없다는 뜻이라 2단계로 넘어가지 않는다.
     *
     * 반환: `{"selected": true, "activeCount": 1, "seatStatus": "AVAILABLE", "detail": "..."}`
     */
    fun buildSelectConfirmScript(target: ReserveTarget): String =
        SELECT_CONFIRM_TEMPLATE.withSignature().replace(CONFIG_PLACEHOLDER, targetConfig(target))

    /**
     * **예매 2단계.** 하단 예매 바의 버튼 좌표. (§38-6, §38-6-1)
     *
     * 누르기 전에 세 가지를 확인하고, **하나라도 어긋나면 좌표를 돌려주지 않는다.**
     *  1. 1단계로 누른 `price_box` 에 `active` 가 붙어 있는가
     *  2. `ul.reserv_first` 첫 항목의 문구가 고른 등급(`일반실`/`특실`)과 같은가
     *  3. 버튼 문구가 **완전일치로** [KtxSelectors.RESERVE_TEXTS_EXACT] 안에 있는가
     *
     * 3번이 핵심이다. 하단 바는 1단계에서 무엇을 골랐느냐에 따라 통째로 달라져서,
     * `예약대기신청` 이나 `입석+좌석 예매` 가 같은 자리에 온다. 허용목록에 없으면
     * `NOT_ALLOWED` 를 돌려주고 **2단계는 사람에게 넘긴다.** (대원칙 3)
     *
     * 반환 형식:
     * ```json
     * {"found": true, "tappable": true, "x": 1.0, "y": 2.0, "label": "예매"}
     * {"found": false, "reason": "NOT_SELECTED|ROW_NOT_FOUND|BAR_NOT_FOUND|LABEL_MISMATCH|
     *                             NOT_ALLOWED|BUTTON_NOT_FOUND|BUTTON_AMBIGUOUS", "buttons": ["..."]}
     * ```
     */
    fun buildReserveScript(
        viewWidthPx: Int,
        viewHeightPx: Int,
        target: ReserveTarget,
    ): String = RESERVE_TEMPLATE.withSignature().withTapPoint()
        .replace(CONFIG_PLACEHOLDER, targetConfig(target))
        .replace(VIEW_PLACEHOLDER, "{w:$viewWidthPx,h:$viewHeightPx}")

    /**
     * 2단계를 누른 **뒤에** 실행한다. 지금 화면이 예약 실패 안내인지 알려준다. (§19-2)
     *
     * 좌석이 열린 것을 보고 눌러도 그사이 다른 사람이 먼저 잡으면 실패 안내가 뜬다.
     * 화면은 정상적으로 바뀌므로 성공과 구분이 되지 않아 본문 문구를 한 번 더 확인한다.
     *
     * > **[KtxSelectors.RESERVE_FAILED_MARKERS] 는 아직 SRT 문구 그대로다.** (§38-8)
     * > 코레일에서 실제로 무엇이 뜨는지 확인되면 그 상수부터 고친다.
     *
     * 오탐을 줄이려고 문구만으로 단정하지 않는다. **열차 목록이 사라진 화면**일 때만
     * 실패로 본다. 되돌아간 뒤 정말 목록으로 돌아왔는지 확인하는 데도 같은 값을 쓴다.
     *
     * 반환: `{"failed": true, "marker": "잔여석없음", "rows": 0, "list": false, "head": "..."}`
     */
    fun buildReserveResultScript(): String {
        val config = buildString {
            append("{")
            append("markers:").append(jsArray(KtxSelectors.RESERVE_FAILED_MARKERS)).append(",")
            append("listMarkers:").append(jsArray(KtxSelectors.TRAIN_LIST_MARKERS))
            append("}")
        }
        return RESERVE_RESULT_TEMPLATE.withSignature().replace(CONFIG_PLACEHOLDER, config)
    }

    /**
     * 터치를 내려보낸 **직후에** 실행한다.
     * 진짜 클릭이 목표까지 도달했는지 알려준다. (로그/진단용)
     *
     * 반환: `{"known": true, "fired": true, "trusted": true, "onTarget": true, "tag": "a.inner"}`
     * 화면이 전환되어 document 가 새로 만들어졌으면 `{"known": false}`.
     */
    fun buildTapConfirmScript(): String = TAP_CONFIRM_TEMPLATE

    /**
     * 클릭 **직전에** 실행한다. 목록 영역에 MutationObserver 를 걸고 지금 서명을 기록한다.
     *
     * 코레일은 `<form>` 이 없는 AJAX 라 조회해도 화면 전환이 일어나지 않는다. (§38-5)
     * 재조회가 반영되었는지 아는 길은 **이 서명의 변화**뿐이다.
     *
     * 반환: `{"sig": "10:1a2b3c", "observing": true}`
     */
    fun buildObserverScript(): String = OBSERVER_TEMPLATE.withSignature()

    /**
     * 클릭 **이후에** 주기적으로 실행한다. DOM 이 갱신되었는지 알려준다.
     *
     * 반환: `{"sig": "10:9z8y7x", "mut": 3, "changed": true}`
     */
    fun buildProbeScript(): String = PROBE_TEMPLATE.withSignature()

    /**
     * 지금 화면에 **열차 목록이 그려져 있는지**만 본다. 읽기만 한다.
     *
     * 예약 실패 안내에서 되돌아간 뒤, 정말 목록 화면으로 돌아왔는지 확인하는 데 쓴다.
     * 코레일은 SPA 라 뒤로 가기 한 칸이 조회 결과 화면에 대응한다는 보장이 없다. (§38-8)
     *
     * 반환: `{"list": true, "rows": 10, "sig": "10:1a2b3c", "url": "..."}`
     */
    fun buildPageKindScript(): String = PAGE_KIND_TEMPLATE.withSignature()

    /**
     * **진단 전용.** 역 선택 창이 왜 안 뜨는지 한 번에 좁힌다. (§38-10)
     *
     * 감시 경로에서는 부르지 않는다. 아무것도 누르지 않고 읽기만 하며, `document` 에
     * **캡처 단계 click 리스너 하나**를 건다 (한 문서당 한 번). 사용자가 실제로 역 버튼을
     * 손으로 누른 뒤 이 스크립트를 다시 돌리면 그 탭이 어디까지 갔는지가 나온다.
     *
     * 사이트 쪽 코드는 이렇게 생겼다 (번들 실측):
     * ```js
     * onClick: function (e) { e.preventDefault(); props.stationDisabled || (setOpen(true), ...) }
     * ```
     * **핸들러가 돌았는가**는 `defaultPrevented` 로, **창이 만들어졌는가**는 모달 개수로
     * 갈린다. 그 둘을 나눠 보는 것이 이 스크립트의 전부다. 한쪽만 보면
     * "아무 반응 없음" 이 셋 중 무엇인지 알 수 없다.
     *
     * 반환:
     * ```json
     * {"rows":10,"modals":0,"topModal":"","armed":true,"view":[375,812,2.75],
     *  "buttons":[{"t":"출발역 선택","box":[26,180,138,37],"vis":true,
     *              "hit":"p.","covered":true}],
     *  "taps":[{"on":"a.btn_pop","station":true,"trusted":true,
     *           "prevented":true,"before":0,"after":0}]}
     * ```
     */
    fun buildStationProbeScript(): String =
        STATION_PROBE_TEMPLATE.withSignature().replace(CONFIG_PLACEHOLDER, stationProbeConfig())

    /**
     * **`100vh` 가 0 으로 잡힌 문서를 되살린다.** (§38-10)
     *
     * 코레일의 역/날짜/인원 선택 창은 `.layerPopup { height: 100vh }` 하나로 화면을
     * 채우고, 오버레이에 인라인 기하가 없어 **대체 경로가 없다**
     * ([KtxSelectors.VIEWPORT_HEIGHT_LAYER]). WebView 에서 `100vh` 가 0 이 되면
     * 창은 열려도 높이 0 이라 사람 눈에는 아무 반응이 없다.
     *
     * 실측(2026-08-29, 411×460): `window.innerHeight` 는 460 인데 `100vh` 만 0 이었다.
     * 두 값이 어긋나는 것이 조건이라, **어긋났을 때만** 픽셀 높이를 덮어쓴다.
     * 정상인 WebView 에서는 아무것도 하지 않는다 — 멀쩡한 페이지를 건드리지 않는다.
     *
     * 하는 일은 스타일 시트 한 장과 `resize` 리스너 하나가 전부다.
     * **사이트 코드를 부르지 않고 요청도 내지 않는다.** 화면 회전이나 앱 패널이
     * 여닫혀 WebView 높이가 바뀌면 리스너가 값을 다시 맞춘다.
     *
     * 문서마다 새로 걸어야 한다. 새로고침하면 `window` 째로 사라지므로
     * [KtxWebViewHost] 가 `onPageFinished` 마다 다시 부른다.
     *
     * 반환: `{"applied":true,"vh":0,"ih":460}` / `{"applied":false,"reason":"ok",…}`
     */
    fun buildViewportFixScript(): String =
        VIEWPORT_FIX_TEMPLATE.replace(CONFIG_PLACEHOLDER, viewportFixConfig())

    /**
     * **새로고침 뒤 목록을 맨 위에서 보게 한다.** (§38-9)
     *
     * 브라우저는 새로고침할 때 직전 스크롤 위치를 되살린다(`history.scrollRestoration`).
     * 코레일은 SPA 라 문서를 받은 시점에는 목록이 아직 없어 문서가 짧고, 그 짧은 문서에
     * 예전 오프셋을 되살리면 **문서 끝으로 잘려 붙는다.** 이어서 목록이 그려지며 높이가
     * 늘어나도 브라우저의 스크롤 앵커링이 그 자리를 붙들어, 사용자 눈에는 갱신할 때마다
     * 화면이 맨 밑으로 튀는 것으로 보인다.
     *
     * 그래서 되살리기를 **끄고**(`manual`) 맨 위로 올린다. 문서마다 새로 걸어야 하므로
     * 새로고침 직전(나가는 이력 항목)과 새 문서 양쪽에서 부른다.
     *
     * 읽기와 스크롤이 전부다. **사이트 코드를 부르지 않고 요청도 내지 않는다.**
     *
     * 반환: `{"before":1840,"after":0,"restore":"auto"}`
     */
    fun buildScrollTopScript(): String = SCROLL_TOP_TEMPLATE

    // ---------------------------------------------------------------- 조립 도구

    /** 뷰포트 보정 스크립트가 쓰는 값. */
    private fun viewportFixConfig(): String = buildString {
        append("{")
        append("layers:").append(jsArray(KtxSelectors.VIEWPORT_HEIGHT_LAYER)).append(",")
        append("varName:").append(jsString(VIEWPORT_FIX_VAR)).append(",")
        append("styleId:").append(jsString(VIEWPORT_FIX_STYLE_ID)).append(",")
        append("tolerance:").append(VIEWPORT_FIX_TOLERANCE_PX)
        append("}")
    }

    /** 진단 스크립트가 쓰는 값. selector 는 전부 [KtxSelectors] 에서 온다. */
    private fun stationProbeConfig(): String = buildString {
        append("{")
        append("buttons:").append(jsArray(KtxSelectors.STATION_POPUP_BUTTON)).append(",")
        append("modals:").append(jsArray(KtxSelectors.PAGE_MODAL)).append(",")
        append("maxTaps:").append(PROBE_MAX_TAPS).append(",")
        append("settleMs:").append(PROBE_SETTLE_MS).append(",")
        append("textChars:").append(PROBE_TEXT_CHARS).append(",")
        append("chainDepth:").append(PROBE_CHAIN_DEPTH).append(",")
        append("cssValueChars:").append(PROBE_CSS_VALUE_CHARS)
        append("}")
    }

    /** 1·2단계가 공유하는 대상 정보. 같은 값을 세 스크립트가 똑같이 읽어야 한다. */
    private fun targetConfig(target: ReserveTarget): String = buildString {
        append("{")
        append("rowKey:").append(jsString(target.rowKey)).append(",")
        append("rowIndex:").append(target.rowIndex).append(",")
        append("cellIndex:").append(target.cellIndex).append(",")
        append("trainNumber:").append(jsString(target.trainNumber)).append(",")
        append("departureTime:").append(jsString(target.departureTime)).append(",")
        append("seatLabel:").append(jsString(target.seatLabel)).append(",")
        append("anchors:").append(jsArray(KtxSelectors.SEAT_CELL_ANCHOR)).append(",")
        append("bar:").append(jsArray(KtxSelectors.RESERVE_BAR)).append(",")
        append("barLabel:").append(jsArray(KtxSelectors.RESERVE_BAR_SEAT_LABEL)).append(",")
        append("buttons:").append(jsArray(KtxSelectors.RESERVE_BUTTON)).append(",")
        append("texts:").append(jsArray(KtxSelectors.RESERVE_TEXTS_EXACT)).append(",")
        append("exclude:").append(jsArray(KtxSelectors.RESERVE_TEXT_EXCLUDE)).append(",")
        append("disabledClass:").append(jsString(KtxSelectors.RESERVE_BUTTON_DISABLED_CLASS))
        append("}")
    }

    private fun jsString(value: String): String = JSONObject.quote(value)

    private fun jsArray(values: List<String>): String {
        val array = JSONArray()
        values.forEach { array.put(it) }
        return array.toString()
    }

    private fun String.withSignature(): String = replace(SIGNATURE_PLACEHOLDER, signatureJs())

    private fun String.withTapPoint(): String = replace(TAPPOINT_PLACEHOLDER, TAPPOINT_JS)

    private const val CONFIG_PLACEHOLDER = "/*__CONFIG__*/"
    private const val SIGNATURE_PLACEHOLDER = "/*__SIGNATURE__*/"
    private const val VIEW_PLACEHOLDER = "/*__VIEW__*/"
    private const val TAPPOINT_PLACEHOLDER = "/*__TAPPOINT__*/"

    /** 진단용. 최근 탭 몇 개까지 들고 있을지. 로그 한 줄에 담기는 만큼만. */
    private const val PROBE_MAX_TAPS = 3

    /** 탭 뒤 모달이 그려지기를 기다리는 시간. react-modal 은 즉시 붙는다. */
    private const val PROBE_SETTLE_MS = 700

    /** 떠 있는 모달의 본문을 몇 자까지 남길지. 차단/안내 문구를 알아볼 정도면 된다. */
    private const val PROBE_TEXT_CHARS = 80

    /**
     * 모달의 조상을 몇 대까지 볼지. `ReactModalPortal` → `body` → `html` 이면 충분하다.
     * `position:fixed` 의 기준을 가로채는 것은 이 사슬 안에 있다.
     */
    private const val PROBE_CHAIN_DEPTH = 3

    /** `transform` 같은 값은 matrix 로 길게 나온다. 알아볼 만큼만 남긴다. */
    private const val PROBE_CSS_VALUE_CHARS = 40

    /** 보정된 뷰포트 높이를 담는 CSS 변수. 이름이 사이트 것과 겹치면 안 된다. */
    private const val VIEWPORT_FIX_VAR = "--catchtrain-vh"

    /** 보정용 `<style>` 의 id. 같은 문서에 두 번 넣지 않기 위한 표식이다. */
    private const val VIEWPORT_FIX_STYLE_ID = "catchtrain-vh-fix"

    /**
     * `100vh` 와 `innerHeight` 가 이만큼까지 어긋나는 것은 정상으로 본다.
     *
     * 스크롤바·반올림·브라우저 바 때문에 1~2px 차이는 흔하다. 실제 고장은 0 대 460
     * 처럼 자릿수가 다르므로, 넉넉히 잡아도 오검출되지 않는다.
     */
    private const val VIEWPORT_FIX_TOLERANCE_PX = 4

    /**
     * 모든 스크립트가 공유하는 바탕 블록. **selector 를 자기 안에 들고 있다.**
     *
     * 목록을 찾고, 편성 하나를 요약하고, 좌석 칸의 상태를 class 로 판정하는 일은
     * 파싱 스크립트도 1·2단계 탐색 스크립트도 **똑같은 규칙으로** 해야 한다.
     * 규칙이 갈라지면 "분석할 때 본 칸"과 "누르는 칸"이 달라진다.
     */
    private fun signatureJs(): String = buildString {
        append("var KSEL = {")
        append("scopes:").append(jsArray(KtxSelectors.SIGNATURE_SCOPES)).append(",")
        append("rows:").append(jsArray(KtxSelectors.TRAIN_ROW)).append(",")
        append("trainType:").append(jsArray(KtxSelectors.TRAIN_TYPE)).append(",")
        append("trainNumber:").append(jsArray(KtxSelectors.TRAIN_NUMBER)).append(",")
        append("heading:").append(jsArray(KtxSelectors.ROUTE_HEADING)).append(",")
        append("seatCell:").append(jsArray(KtxSelectors.SEAT_CELL)).append(",")
        append("generalIndex:").append(KtxSelectors.SEAT_CELL_INDEX_GENERAL).append(",")
        append("firstIndex:").append(KtxSelectors.SEAT_CELL_INDEX_FIRST_CLASS).append(",")
        append("cls:{")
        append("general:").append(jsString(KtxSelectors.SeatCellClass.GENERAL)).append(",")
        append("firstClass:").append(jsString(KtxSelectors.SeatCellClass.FIRST_CLASS)).append(",")
        append("soldOutSoon:").append(jsString(KtxSelectors.SeatCellClass.SOLD_OUT_SOON)).append(",")
        append("soldOut:").append(jsString(KtxSelectors.SeatCellClass.SOLD_OUT)).append(",")
        append("soldOutWait:").append(jsString(KtxSelectors.SeatCellClass.SOLD_OUT_WAIT)).append(",")
        append("wait:").append(jsString(KtxSelectors.SeatCellClass.WAIT)).append(",")
        append("active:").append(jsString(KtxSelectors.SeatCellClass.ACTIVE))
        append("}};\n")
        append(SIGNATURE_JS)
    }

    /**
     * 좌석 상태 판정은 [dev.yslee.catchtrain.parser.SeatParser.fromClassNames] 와 같은 규칙이다.
     * **부분일치를 쓰지 않고 class 토큰 완전일치로만 본다** — `sold_out_soon` 안에
     * `sold_out` 이, `sold_out_wait` 안에 `wait` 가 들어 있어서 부분일치는 언젠가 어긋난다.
     */
    private val SIGNATURE_JS = """
      function ktxNorm(s) { return (s || '').replace(/\s+/g, ''); }

      function ktxText(el) {
        if (!el) return '';
        return ktxNorm(el.innerText || el.textContent || '');
      }

      /** selector 후보를 앞에서부터 시도해 처음 걸리는 요소. */
      function ktxFirst(root, selectors) {
        if (!root) return null;
        for (var i = 0; i < selectors.length; i++) {
          var el;
          try { el = root.querySelector(selectors[i]); } catch (e) { continue; }
          if (el) return el;
        }
        return null;
      }

      function ktxFirstText(root, selectors) { return ktxText(ktxFirst(root, selectors)); }

      /** 목록이 그려진 영역. 못 찾으면 document 전체를 본다. */
      function ktxScope() {
        for (var i = 0; i < KSEL.scopes.length; i++) {
          var el;
          try { el = document.querySelector(KSEL.scopes[i]); } catch (e) { continue; }
          if (el) return el;
        }
        return document.body || document;
      }

      /** 편성 목록(li.tckList). 없으면 빈 배열. */
      function ktxRows() {
        var roots = [ktxScope(), document];
        for (var r = 0; r < roots.length; r++) {
          if (!roots[r]) continue;
          for (var i = 0; i < KSEL.rows.length; i++) {
            var found;
            try { found = roots[r].querySelectorAll(KSEL.rows[i]); } catch (e) { continue; }
            if (found && found.length > 0) {
              var out = [];
              for (var j = 0; j < found.length; j++) out.push(found[j]);
              return out;
            }
          }
        }
        return [];
      }

      /** 한 편성의 좌석 칸. 실측에서는 언제나 2개고 [0]=일반실, [1]=특실 이다. (§38-3) */
      function ktxSeatCells(row) {
        for (var i = 0; i < KSEL.seatCell.length; i++) {
          var found;
          try { found = row.querySelectorAll(KSEL.seatCell[i]); } catch (e) { continue; }
          if (found && found.length > 0) {
            var out = [];
            for (var j = 0; j < found.length; j++) out.push(found[j]);
            return out;
          }
        }
        return [];
      }

      function ktxClassOf(el) {
        if (!el) return '';
        var cls = el.className;
        if (typeof cls !== 'string') {
          cls = (el.getAttribute && el.getAttribute('class')) || '';
        }
        return cls;
      }

      function ktxTokens(el) {
        var raw = ktxClassOf(el).split(/\s+/);
        var out = [];
        for (var i = 0; i < raw.length; i++) if (raw[i]) out.push(raw[i]);
        return out;
      }

      function ktxHas(tokens, name) {
        for (var i = 0; i < tokens.length; i++) if (tokens[i] === name) return true;
        return false;
      }

      /**
       * 좌석 칸의 상태. **class 토큰 완전일치**로만 본다. (§38-2)
       * 문구로 읽으면 `특실(매진임박)` 을 매진으로 오판한다.
       */
      function ktxSeatStatus(tokens) {
        if (ktxHas(tokens, KSEL.cls.soldOutSoon)) return 'AVAILABLE';
        if (ktxHas(tokens, KSEL.cls.soldOut)) return 'SOLD_OUT';
        if (ktxHas(tokens, KSEL.cls.soldOutWait)) return 'SOLD_OUT';
        if (ktxHas(tokens, KSEL.cls.wait)) return 'WAITING';
        if (ktxHas(tokens, KSEL.cls.general)) return 'AVAILABLE';
        if (ktxHas(tokens, KSEL.cls.firstClass)) return 'AVAILABLE';
        return 'UNKNOWN';
      }

      /** 1단계로 이 칸을 골라 둔 상태인가. 상태가 아니라 선택 표시다. (§38-6) */
      function ktxSelectedCell(cell) { return ktxHas(ktxTokens(cell), KSEL.cls.active); }

      function ktxHash(text) {
        var h = 5381;
        for (var i = 0; i < text.length; i++) {
          h = ((h * 33) ^ text.charCodeAt(i)) & 0x7fffffff;
        }
        return h.toString(36);
      }

      /**
       * 편성 하나의 "지금 내용"을 짧은 문자열로 요약한다.
       *
       * 분석할 때 읽은 편성과 누를 때의 편성이 정말 같은지 확인하는 데 쓴다.
       * 위치(index)만 믿으면 목록이 갱신된 순간 엉뚱한 열차를 잡는다.
       *
       * **`active` 는 요약에서 뺀다.** 1단계로 칸을 고르면 그 class 가 붙는데,
       * 그것 때문에 요약값이 바뀌면 2단계에서 같은 편성을 못 찾는다.
       */
      function ktxRowKey(row) {
        var parts = [];
        parts.push(ktxFirstText(row, KSEL.trainNumber));
        parts.push(ktxFirstText(row, KSEL.trainType));
        parts.push(ktxFirstText(row, KSEL.heading));
        var cells = ktxSeatCells(row);
        for (var i = 0; i < cells.length; i++) {
          var tokens = ktxTokens(cells[i]);
          var kept = [];
          for (var t = 0; t < tokens.length; t++) {
            if (tokens[t] !== KSEL.cls.active) kept.push(tokens[t]);
          }
          parts.push(kept.join('.') + '=' + ktxText(cells[i]).slice(0, 24));
        }
        return cells.length + ':' + ktxHash(parts.join('|'));
      }

      /** 목록 전체의 요약. 재조회가 실제로 반영되었는지 판단하는 데만 쓴다. */
      function ktxSignature() {
        var rows = ktxRows();
        if (rows.length === 0) return 'no-list';
        var buf = [];
        for (var i = 0; i < rows.length; i++) buf.push(ktxRowKey(rows[i]));
        return rows.length + ':' + ktxHash(buf.join('#'));
      }
    """.trimIndent()

    /**
     * "이 요소를 누르려면 화면의 어느 픽셀을 눌러야 하는가" 를 계산하는 공용 블록.
     *
     * 재조회·1단계·2단계가 똑같은 규칙으로 좌표를 잡아야 하므로 한곳에 모았다.
     * 아무것도 클릭하지 않고 좌표만 계산한다.
     * 이 블록을 쓰는 스크립트는 `VIEW`({w,h}) 와 `ktxNorm` 이 이미 정의되어 있어야 한다.
     */
    private val TAPPOINT_JS = """
      function ktxLabelOf(el) {
        var v = '';
        if (el.tagName === 'INPUT') {
          v = el.value || el.getAttribute('value') || el.getAttribute('alt') || '';
        }
        if (!v) v = el.innerText || el.textContent || '';
        if (!v) v = el.getAttribute('title') || el.getAttribute('aria-label') || '';
        return ktxNorm(v);
      }

      function ktxVisible(el) {
        if (!el || el.disabled) return false;
        var rect = el.getBoundingClientRect();
        if (rect.width <= 0 || rect.height <= 0) return false;
        var style = window.getComputedStyle ? window.getComputedStyle(el) : null;
        if (style && (style.visibility === 'hidden' || style.display === 'none')) return false;
        return true;
      }

      function ktxDescribeEl(el) {
        if (!el) return 'none';
        var s = el.tagName ? el.tagName.toLowerCase() : '?';
        if (el.id) s += '#' + el.id;
        var cls = el.className;
        if (cls && typeof cls === 'string') s += '.' + cls.split(/\s+/).slice(0, 2).join('.');
        return s;
      }

      function ktxRectText(r) {
        return Math.round(r.left) + ',' + Math.round(r.top) +
               ' ' + Math.round(r.width) + 'x' + Math.round(r.height);
      }

      /**
       * CSS 좌표 → WebView 위젯 픽셀 좌표 환산 기준.
       *
       * getBoundingClientRect 는 레이아웃 뷰포트(CSS px) 기준이고 터치는 위젯 픽셀로
       * 보내야 한다. 지금 화면에 실제로 보이는 visual viewport 가 위젯 전체(VIEW)에
       * 대응하므로 그 비율로 환산하고, 핀치 줌/가로 스크롤을 위해 offset 도 뺀다.
       */
      function ktxViewport() {
        var vv = window.visualViewport;
        var w = vv ? vv.width : (window.innerWidth || document.documentElement.clientWidth);
        var h = vv ? vv.height : (window.innerHeight || document.documentElement.clientHeight);
        return {
          w: w,
          h: h,
          offX: vv ? vv.offsetLeft : 0,
          offY: vv ? vv.offsetTop : 0,
          rx: w > 0 ? VIEW.w / w : 0,
          ry: h > 0 ? VIEW.h / h : 0
        };
      }

      // 그 지점을 누르면 정말 이 요소가 눌리는지 확인한다.
      // null 이면 화면 밖이다. (elementFromPoint 는 뷰포트 밖에서 null 을 돌려준다)
      function ktxHitAt(el, cx, cy) {
        var hit = null;
        try { hit = document.elementFromPoint(cx, cy); } catch (e) { return null; }
        if (!hit) return null;
        return { ok: hit === el || el.contains(hit), hit: hit };
      }

      /**
       * 누를 지점을 고른다. 가운데부터 시도하고, 가려져 있으면 요소 안쪽의 다른 지점을
       * 차례로 시도한다. 사람도 버튼 정중앙만 정확히 누르지는 않는다.
       */
      function ktxTapPoint(el) {
        try { el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' }); }
        catch (e) { try { el.scrollIntoView(); } catch (e2) { /* 무시 */ } }

        var rect = el.getBoundingClientRect();
        if (rect.width <= 0 || rect.height <= 0) {
          return { reason: 'ZERO_SIZE', rect: ktxRectText(rect) };
        }

        var vp = ktxViewport();
        if (vp.rx <= 0 || vp.ry <= 0) {
          return { reason: 'NO_VIEWPORT', rect: ktxRectText(rect) };
        }

        var fracs = [
          [0.50, 0.50], [0.50, 0.35], [0.50, 0.65],
          [0.35, 0.50], [0.65, 0.50], [0.20, 0.50], [0.80, 0.50]
        ];
        var covered = null;
        var outside = 0;

        for (var i = 0; i < fracs.length; i++) {
          var cx = rect.left + rect.width * fracs[i][0];
          var cy = rect.top + rect.height * fracs[i][1];
          var dx = (cx - vp.offX) * vp.rx;
          var dy = (cy - vp.offY) * vp.ry;
          if (dx < 1 || dy < 1 || dx > VIEW.w - 1 || dy > VIEW.h - 1) { outside++; continue; }

          var r = ktxHitAt(el, cx, cy);
          if (r === null) { outside++; continue; }
          if (r.ok) {
            return { x: dx, y: dy, at: fracs[i][0] + ',' + fracs[i][1], rect: ktxRectText(rect) };
          }
          if (covered === null) covered = ktxDescribeEl(r.hit);
        }

        return {
          reason: covered !== null ? 'COVERED' : 'OFF_SCREEN',
          covered: covered || '',
          outside: outside,
          rect: ktxRectText(rect),
          viewport: Math.round(vp.w) + 'x' + Math.round(vp.h) +
                    '@' + Math.round(vp.offX) + ',' + Math.round(vp.offY) +
                    ' view=' + VIEW.w + 'x' + VIEW.h
        };
      }

      /**
       * 터치가 실제로 목표 요소까지 도달했는지 확인할 준비.
       * 캡처 단계에서 click 을 한 번만 받아 기록하고 스스로 떨어진다.
       * 페이지 동작에는 영향을 주지 않는다. (읽기만 하고 막지 않는다)
       */
      function ktxArmConfirm(el) {
        var state = { fired: 0, trusted: 0, onTarget: 0, tag: '' };
        window.__ktxTap = state;
        function onClick(e) {
          state.fired = 1;
          state.trusted = e.isTrusted ? 1 : 0;
          state.onTarget = (e.target === el || el.contains(e.target)) ? 1 : 0;
          state.tag = ktxDescribeEl(e.target);
          try { document.removeEventListener('click', onClick, true); } catch (e2) { /* 무시 */ }
        }
        try { document.addEventListener('click', onClick, true); } catch (e) { /* 무시 */ }
      }
    """.trimIndent()

    /**
     * 예매 1단계 — 좌석 칸 탐색 스크립트.
     *
     * 순서
     *  1) 분석할 때 읽었던 그 편성을 다시 찾는다. 기준은 위치가 아니라 내용 요약([ktxRowKey]).
     *  2) 그 편성이 정말 그 열차인지 **열차 번호**와 출발 시각으로 한 번 더 확인한다. (§38-4)
     *  3) 지정된 좌석 칸이 지금도 예약 가능한지 class 로 확인한다. (§38-2)
     *  4) 그 칸의 `a` 를 누를 수 있는 화면 좌표를 돌려준다.
     *
     * 어느 단계든 확실하지 않으면 좌표 대신 실패 이유를 돌려준다.
     */
    private val SELECT_TEMPLATE = """
    (function () {
      var CFG = /*__CONFIG__*/;
      var VIEW = /*__VIEW__*/;
      /*__SIGNATURE__*/
      /*__TAPPOINT__*/

      var rows = ktxRows();
      var hits = [];
      for (var i = 0; i < rows.length; i++) {
        if (ktxRowKey(rows[i]) === CFG.rowKey) hits.push(rows[i]);
      }

      if (hits.length === 0) {
        return {
          found: false,
          reason: 'ROW_NOT_FOUND',
          detail: '편성 ' + rows.length + '개 중 일치 없음 key=' + CFG.rowKey,
          url: location.href
        };
      }

      // 같은 내용의 편성이 둘 이상이면(숨은 중복 목록 등) 보이는 것만 남긴다.
      if (hits.length > 1) {
        var shown = [];
        for (var h = 0; h < hits.length; h++) {
          if (ktxVisible(hits[h])) shown.push(hits[h]);
        }
        if (shown.length !== 1) {
          return {
            found: false,
            reason: 'ROW_AMBIGUOUS',
            detail: '같은 내용의 편성 ' + hits.length + '개 (보이는 것 ' + shown.length + '개)'
          };
        }
        hits = shown;
      }

      var row = hits[0];
      var number = ktxFirstText(row, KSEL.trainNumber);
      var rowText = ktxText(row);

      // 요약 해시가 우연히 겹쳤을 가능성까지 막는다. 주키는 열차 번호다. (§38-4)
      if (CFG.trainNumber && number && number !== ktxNorm(CFG.trainNumber)) {
        return {
          found: false,
          reason: 'ROW_MISMATCH',
          detail: '열차번호 ' + CFG.trainNumber + ' 가 아니라 ' + number
        };
      }
      if (CFG.departureTime && rowText.indexOf(ktxNorm(CFG.departureTime)) < 0) {
        return {
          found: false,
          reason: 'ROW_MISMATCH',
          detail: CFG.departureTime + ' 없음 / row=' + rowText.slice(0, 60)
        };
      }

      var cells = ktxSeatCells(row);
      if (CFG.cellIndex < 0 || CFG.cellIndex >= cells.length) {
        return {
          found: false,
          reason: 'CELL_NOT_FOUND',
          detail: '좌석 칸 ' + cells.length + '개 중 ' + CFG.cellIndex + '번을 찾지 못함'
        };
      }

      var cell = cells[CFG.cellIndex];
      var status = ktxSeatStatus(ktxTokens(cell));
      if (status !== 'AVAILABLE') {
        // 매진·예약대기 칸은 누르지 않는다. 예약대기는 발견으로 보지 않고(§18),
        // 매진 칸을 눌렀을 때의 반응은 아직 실측이 없다. (§38-8)
        return {
          found: false,
          reason: 'SEAT_NOT_AVAILABLE',
          seatStatus: status,
          detail: CFG.seatLabel + ' 상태=' + status + ' class=' + ktxClassOf(cell)
        };
      }

      var anchor = ktxFirst(cell, CFG.anchors) || cell;
      var point = ktxTapPoint(anchor);
      var out = {
        found: true,
        label: ktxText(cell).slice(0, 24),
        tag: anchor.tagName,
        cellIndex: CFG.cellIndex,
        rowIndex: CFG.rowIndex,
        cells: cells.length,
        seatStatus: status,
        train: CFG.trainNumber,
        departureTime: CFG.departureTime,
        rect: point.rect || ''
      };

      if (point.reason) {
        out.tappable = false;
        out.reason = point.reason;
        out.covered = point.covered || '';
        out.viewport = point.viewport || '';
        return out;
      }

      ktxArmConfirm(anchor);
      out.tappable = true;
      out.x = point.x;
      out.y = point.y;
      out.at = point.at;
      return out;
    })();
    """.trimIndent()

    /**
     * 1단계 직후 확인 스크립트. 읽기만 한다.
     *
     * 목록 전체에서 `active` 가 붙은 칸을 센다. 우리가 누른 칸 하나만 붙어 있어야
     * "내가 고른 것이 무엇인지" 를 확신할 수 있다. 사용자가 화면에서 다른 칸을
     * 만져 두었거나 사이트가 여러 칸을 동시에 표시하면 2단계로 넘어가지 않는다.
     */
    private val SELECT_CONFIRM_TEMPLATE = """
    (function () {
      var CFG = /*__CONFIG__*/;
      /*__SIGNATURE__*/

      var rows = ktxRows();
      var activeCount = 0;
      var activeLabels = [];
      for (var r = 0; r < rows.length; r++) {
        var cs = ktxSeatCells(rows[r]);
        for (var c = 0; c < cs.length; c++) {
          if (ktxSelectedCell(cs[c])) {
            activeCount++;
            if (activeLabels.length < 3) {
              activeLabels.push(ktxFirstText(rows[r], KSEL.trainNumber) + '#' + c);
            }
          }
        }
      }

      var row = null;
      for (var i = 0; i < rows.length; i++) {
        if (ktxRowKey(rows[i]) === CFG.rowKey) { row = rows[i]; break; }
      }
      if (row === null) {
        return {
          selected: false,
          reason: 'ROW_NOT_FOUND',
          activeCount: activeCount,
          detail: '편성 ' + rows.length + '개 중 일치 없음'
        };
      }

      var cells = ktxSeatCells(row);
      var cell = (CFG.cellIndex >= 0 && CFG.cellIndex < cells.length) ? cells[CFG.cellIndex] : null;
      if (cell === null) {
        return {
          selected: false,
          reason: 'CELL_NOT_FOUND',
          activeCount: activeCount,
          detail: '좌석 칸 ' + cells.length + '개'
        };
      }

      var mine = ktxSelectedCell(cell);
      return {
        selected: mine && activeCount === 1,
        mine: mine,
        activeCount: activeCount,
        seatStatus: ktxSeatStatus(ktxTokens(cell)),
        detail: CFG.seatLabel + ' class=' + ktxClassOf(cell) +
                ' active=' + activeCount + (activeLabels.length ? ' [' + activeLabels.join(',') + ']' : '')
      };
    })();
    """.trimIndent()

    /**
     * 예매 2단계 — 하단 바 버튼 탐색 스크립트.
     *
     * 검증 세 가지를 모두 통과했을 때만 좌표를 돌려준다. (§38-6-1)
     * 아무것도 클릭하지 않는다.
     */
    private val RESERVE_TEMPLATE = """
    (function () {
      var CFG = /*__CONFIG__*/;
      var VIEW = /*__VIEW__*/;
      /*__SIGNATURE__*/
      /*__TAPPOINT__*/

      // ------------------------------------------------ 확인 1) 그 칸이 골라져 있는가

      var rows = ktxRows();
      var row = null;
      for (var i = 0; i < rows.length; i++) {
        if (ktxRowKey(rows[i]) === CFG.rowKey) { row = rows[i]; break; }
      }
      if (row === null) {
        return {
          found: false,
          reason: 'ROW_NOT_FOUND',
          detail: '편성 ' + rows.length + '개 중 일치 없음 key=' + CFG.rowKey
        };
      }

      var cells = ktxSeatCells(row);
      var cell = (CFG.cellIndex >= 0 && CFG.cellIndex < cells.length) ? cells[CFG.cellIndex] : null;
      if (cell === null || !ktxSelectedCell(cell)) {
        return {
          found: false,
          reason: 'NOT_SELECTED',
          detail: CFG.seatLabel + ' 칸에 선택 표시가 없음 class=' +
                  (cell ? ktxClassOf(cell) : '칸없음')
        };
      }

      // ------------------------------------------------ 확인 2) 하단 바의 등급이 같은가

      var bar = null;
      for (var b = 0; b < CFG.bar.length && bar === null; b++) {
        var found;
        try { found = document.querySelectorAll(CFG.bar[b]); } catch (e) { continue; }
        for (var k = 0; k < found.length; k++) {
          if (ktxVisible(found[k])) { bar = found[k]; break; }
        }
      }
      if (bar === null) {
        return { found: false, reason: 'BAR_NOT_FOUND', detail: '하단 예매 바가 보이지 않음' };
      }

      var barLabel = ktxFirstText(bar, CFG.barLabel);
      if (barLabel !== ktxNorm(CFG.seatLabel)) {
        return {
          found: false,
          reason: 'LABEL_MISMATCH',
          detail: '고른 등급은 ' + CFG.seatLabel + ' 인데 하단 바는 ' + (barLabel || '(빈칸)')
        };
      }

      // ------------------------------------------------ 확인 3) 누를 수 있는 문구인가

      function excluded(label) {
        for (var i = 0; i < CFG.exclude.length; i++) {
          if (label.indexOf(ktxNorm(CFG.exclude[i])) >= 0) return true;
        }
        return false;
      }

      function allowed(label) {
        for (var i = 0; i < CFG.texts.length; i++) {
          if (label === ktxNorm(CFG.texts[i])) return true;
        }
        return false;
      }

      function disabled(el) {
        if (el.disabled) return true;
        if (el.getAttribute && el.getAttribute('disabled') !== null) return true;
        return ktxHas(ktxTokens(el), CFG.disabledClass);
      }

      var buttons = [];
      var seen = [];
      for (var s = 0; s < CFG.buttons.length; s++) {
        var list;
        try { list = bar.querySelectorAll(CFG.buttons[s]); } catch (e) { continue; }
        for (var j = 0; j < list.length; j++) {
          if (seen.indexOf(list[j]) < 0) { seen.push(list[j]); buttons.push(list[j]); }
        }
      }

      var labels = [];
      var candidates = [];
      for (var n = 0; n < buttons.length; n++) {
        var el = buttons[n];
        var label = ktxLabelOf(el);
        var note = label || '(문구없음)';
        if (disabled(el)) note += '(비활성)';
        if (labels.length < 4) labels.push(note);
        if (disabled(el)) continue;
        if (excluded(label)) continue;
        if (!allowed(label)) continue;
        candidates.push({ el: el, label: label });
      }

      if (buttons.length === 0) {
        return { found: false, reason: 'BUTTON_NOT_FOUND', detail: '하단 바에 버튼이 없음' };
      }
      if (candidates.length === 0) {
        // `예약대기신청` / `입석+좌석 예매` 가 여기로 온다. 누르지 않고 사람에게 넘긴다.
        return {
          found: false,
          reason: 'NOT_ALLOWED',
          buttons: labels,
          detail: '누를 수 있는 문구가 없음 (' + labels.join(', ') + ')'
        };
      }
      if (candidates.length > 1) {
        return {
          found: false,
          reason: 'BUTTON_AMBIGUOUS',
          buttons: labels,
          detail: '같은 문구의 버튼이 ' + candidates.length + '개'
        };
      }

      // ------------------------------------------------------------------ 좌표

      var target = candidates[0];
      var point = ktxTapPoint(target.el);
      var out = {
        found: true,
        label: target.label,
        tag: target.el.tagName,
        seatLabel: CFG.seatLabel,
        barLabel: barLabel,
        buttons: labels,
        train: CFG.trainNumber,
        departureTime: CFG.departureTime,
        rect: point.rect || ''
      };

      if (point.reason) {
        out.tappable = false;
        out.reason = point.reason;
        out.covered = point.covered || '';
        out.viewport = point.viewport || '';
        return out;
      }

      ktxArmConfirm(target.el);
      out.tappable = true;
      out.x = point.x;
      out.y = point.y;
      out.at = point.at;
      return out;
    })();
    """.trimIndent()

    /**
     * 예약 결과 판별 스크립트. 2단계를 누른 뒤에 실행한다.
     *
     * 본문에 실패 문구가 보이고 **열차 목록이 사라졌을 때만** 실패로 본다.
     * 문구는 `innerText` 로만 읽으므로 `<script>` 안의 안내 문자열에는 걸리지 않는다.
     */
    private val RESERVE_RESULT_TEMPLATE = """
    (function () {
      var CFG = /*__CONFIG__*/;
      /*__SIGNATURE__*/

      var body = '';
      try { body = ktxNorm(document.body ? (document.body.innerText || '') : ''); } catch (e) { body = ''; }

      var marker = '';
      for (var i = 0; i < CFG.markers.length; i++) {
        if (body.indexOf(ktxNorm(CFG.markers[i])) >= 0) { marker = CFG.markers[i]; break; }
      }

      var rows = ktxRows().length;
      var hasMarker = false;
      for (var j = 0; j < CFG.listMarkers.length; j++) {
        try {
          if (document.querySelector(CFG.listMarkers[j])) { hasMarker = true; break; }
        } catch (e) { /* 잘못된 selector 무시 */ }
      }

      return {
        failed: marker !== '' && rows === 0,
        marker: marker,
        rows: rows,
        list: rows > 0 && hasMarker,
        url: location.href,
        title: document.title || '',
        head: body.slice(0, 120)
      };
    })();
    """.trimIndent()

    /** 터치 직후 확인 스크립트. 진짜 클릭이 목표까지 갔는지 알려준다. */
    private val TAP_CONFIRM_TEMPLATE = """
    (function () {
      var s = window.__ktxTap;
      if (!s) return { known: false };
      return {
        known: true,
        fired: s.fired === 1,
        trusted: s.trusted === 1,
        onTarget: s.onTarget === 1,
        tag: s.tag || ''
      };
    })();
    """.trimIndent()

    /**
     * 클릭 직전 준비 스크립트. 목록 영역에 MutationObserver 를 설치하고 카운터를 0 으로 되돌린다.
     * 화면이 전환되면 window 가 새로 만들어지므로 매 사이클 다시 설치한다.
     */
    private val OBSERVER_TEMPLATE = """
    (function () {
      /*__SIGNATURE__*/

      var state = window.__ktxWatch;
      if (!state || state.doc !== document) {
        state = { doc: document, mut: 0, obs: null, sig: '' };
        window.__ktxWatch = state;
      }
      if (state.obs) {
        try { state.obs.disconnect(); } catch (e) { /* 무시 */ }
        state.obs = null;
      }

      var root = ktxScope();
      if (root && window.MutationObserver) {
        try {
          state.obs = new MutationObserver(function () { state.mut++; });
          state.obs.observe(root, { childList: true, subtree: true, characterData: true });
        } catch (e) { state.obs = null; }
      }

      state.mut = 0;
      state.sig = ktxSignature();
      return { sig: state.sig, observing: state.obs !== null };
    })();
    """.trimIndent()

    /** 클릭 이후 폴링 스크립트. DOM 이 갱신되었는지 알려준다. */
    private val PROBE_TEMPLATE = """
    (function () {
      /*__SIGNATURE__*/

      var state = window.__ktxWatch;
      var sig = ktxSignature();
      if (!state || state.doc !== document) {
        // 화면이 전환되어 새 document 가 되었다.
        return { sig: sig, mut: -1, changed: true };
      }
      return { sig: sig, mut: state.mut, changed: sig !== state.sig };
    })();
    """.trimIndent()

    /** 지금 화면에 열차 목록이 있는지. 되돌리기가 성공했는지 확인하는 데 쓴다. */
    private val PAGE_KIND_TEMPLATE = """
    (function () {
      /*__SIGNATURE__*/

      var rows = ktxRows().length;
      return {
        list: rows > 0,
        rows: rows,
        sig: ktxSignature(),
        url: location.href,
        title: document.title || ''
      };
    })();
    """.trimIndent()

    /**
     * 역 선택 버튼 진단. 읽기 + 캡처 리스너 하나. 아무것도 누르지 않는다. (§38-10)
     *
     * 리스너는 **캡처 단계**여야 한다. React 핸들러보다 먼저 들어와야
     * "탭이 그 요소까지 갔는가" 와 "핸들러가 돌았는가" 를 구분할 수 있다.
     * `defaultPrevented` 는 dispatch 가 끝나야 확정되므로 setTimeout 으로 미뤄 읽는다.
     */
    private val STATION_PROBE_TEMPLATE = """
    (function () {
      var CFG = /*__CONFIG__*/;
      /*__SIGNATURE__*/

      var BTN = CFG.buttons.join(',');
      var MOD = CFG.modals.join(',');

      function nameOf(el) {
        if (!el) return 'none';
        var cls = '';
        if (el.className && typeof el.className === 'string') {
          cls = el.className.trim().split(/\s+/).slice(0, 2).join('.');
        }
        return (el.tagName || '?').toLowerCase() + (cls ? '.' + cls : '');
      }

      function modalNodes() {
        try { return document.querySelectorAll(MOD); } catch (e) { return []; }
      }

      function boxOf(el) {
        var r = el.getBoundingClientRect();
        return [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)];
      }

      /** 보이지 않게 만들 수 있는 속성만 추린다. 기본값인 것은 적지 않는다. */
      function cssOf(el) {
        var c = window.getComputedStyle(el);
        var out = c.position + ' z=' + c.zIndex + ' op=' + c.opacity;
        if (c.visibility !== 'visible') out += ' vis=' + c.visibility;
        if (c.display !== 'block') out += ' disp=' + c.display;
        if (c.overflow !== 'visible') out += ' of=' + c.overflow;
        if (c.transform && c.transform !== 'none') {
          out += ' tf=' + c.transform.slice(0, CFG.cssValueChars);
        }
        if (c.filter && c.filter !== 'none') out += ' flt=' + c.filter.slice(0, CFG.cssValueChars);
        if (c.clipPath && c.clipPath !== 'none') {
          out += ' clip=' + c.clipPath.slice(0, CFG.cssValueChars);
        }
        return out;
      }

      function onStation(el) {
        try { return !!(el && el.closest && el.closest(BTN)); } catch (e) { return false; }
      }

      // 한 문서에 한 번만 건다. 새로고침으로 document 가 바뀌면 다시 건다.
      var rec = window.__ktxStationProbe;
      if (!rec || rec.doc !== document) {
        rec = { doc: document, taps: [], armed: false };
        window.__ktxStationProbe = rec;
      }
      if (!rec.armed) {
        rec.armed = true;
        document.addEventListener('click', function (e) {
          var entry = {
            on: nameOf(e.target),
            station: onStation(e.target),
            trusted: e.isTrusted === true,
            prevented: null,
            before: modalNodes().length,
            after: -1
          };
          rec.taps.push(entry);
          while (rec.taps.length > CFG.maxTaps) rec.taps.shift();
          setTimeout(function () {
            entry.after = modalNodes().length;
            entry.prevented = e.defaultPrevented === true;
          }, CFG.settleMs);
        }, true);
      }

      var buttons = [];
      var found;
      try { found = document.querySelectorAll(BTN); } catch (e) { found = []; }
      for (var i = 0; i < found.length; i++) {
        var a = found[i];
        var r = a.getBoundingClientRect();
        var cs = window.getComputedStyle(a);
        var hit = null;
        if (r.width > 0 && r.height > 0) {
          hit = document.elementFromPoint(
            Math.round(r.left + r.width / 2),
            Math.round(r.top + r.height / 2)
          );
        }
        buttons.push({
          t: a.getAttribute('title') || '',
          box: [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)],
          vis: cs.display !== 'none' && cs.visibility !== 'hidden' && cs.pointerEvents !== 'none',
          hit: nameOf(hit),
          covered: hit !== null && hit !== a && !a.contains(hit)
        });
      }

      var open = modalNodes();
      var top = '';
      if (open.length > 0) {
        top = (open[open.length - 1].innerText || '')
          .replace(/\s+/g, ' ').trim().slice(0, CFG.textChars);
      }

      // 창이 만들어졌는데도 안 보이는 경우를 위해, 맨 위 모달의 기하와 조상 사슬을 뜬다.
      // 조상까지 보는 이유는 `position:fixed` 때문이다 — 조상에 transform/filter 가 있으면
      // 그 조상이 기준이 되어 화면 밖으로 나갈 수 있다.
      var modal = null;
      if (open.length > 0) {
        var ov = open[open.length - 1];
        var b = boxOf(ov);
        var mhit = null;
        if (b[2] > 0 && b[3] > 0) {
          mhit = document.elementFromPoint(
            Math.max(0, Math.min(window.innerWidth - 1, b[0] + Math.round(b[2] / 2))),
            Math.max(0, Math.min(window.innerHeight - 1, b[1] + Math.round(b[3] / 2)))
          );
        }
        var chain = [];
        for (var p = ov.parentElement; p && chain.length < CFG.chainDepth; p = p.parentElement) {
          chain.push(nameOf(p) + '[' + cssOf(p) + ']');
        }
        var body = ov.firstElementChild;
        modal = {
          cls: (ov.className || '').trim(),
          box: b,
          css: cssOf(ov),
          hit: nameOf(mhit),
          inside: mhit !== null && (mhit === ov || ov.contains(mhit)),
          contentBox: body ? boxOf(body) : null,
          contentCss: body ? cssOf(body) : '',
          chain: chain.join(' < '),
          scroll: [Math.round(window.scrollX), Math.round(window.scrollY)]
        };
      }

      // `100vh` 가 실제로 몇 px 인지 재 본다. 이것이 0 이면 원인은 여기서 끝난다 —
      // 코레일 레이어는 `.layerPopup { height: 100vh }` 라, vh 가 0 이면 창은 열려도
      // 높이 0 이라 사람 눈에는 아무 반응이 없다. (§38-10)
      // innerHeight 는 멀쩡한데 vh 만 0 인 경우가 있어서 **따로** 재야 한다.
      var vh100 = -1;
      try {
        var ruler = document.createElement('div');
        ruler.style.cssText = 'position:fixed;top:0;left:0;width:1px;height:100vh;' +
          'visibility:hidden;pointer-events:none';
        document.body.appendChild(ruler);
        vh100 = Math.round(ruler.getBoundingClientRect().height);
        ruler.parentNode.removeChild(ruler);
      } catch (e) {
        vh100 = -1;
      }

      return {
        rows: ktxRows().length,
        modals: open.length,
        topModal: top,
        modal: modal,
        armed: rec.armed,
        view: [window.innerWidth, window.innerHeight, window.devicePixelRatio || 1],
        vh100: vh100,
        clientH: document.documentElement.clientHeight,
        buttons: buttons,
        taps: rec.taps
      };
    })();
    """.trimIndent()

    /**
     * `100vh` 보정. 어긋났을 때만 손대고, 멀쩡하면 아무것도 하지 않는다. (§38-10)
     *
     * `getComputedStyle` 로는 `100vh` 가 몇 px 인지 알 수 없다 — 규칙이 어느 요소에
     * 걸렸는지 모르기 때문이다. 그래서 자를 하나 만들어 **직접 잰다.**
     * `innerHeight` 와 비교하는 이유는 실측에서 그 둘이 갈렸기 때문이다(460 대 0).
     */
    private val VIEWPORT_FIX_TEMPLATE = """
    (function () {
      var CFG = /*__CONFIG__*/;

      // `100vh` 가 실제로 몇 px 인지. 잰 즉시 걷어낸다.
      function measureVh() {
        var host = document.body || document.documentElement;
        if (!host) return -1;
        var ruler = document.createElement('div');
        ruler.style.cssText = 'position:fixed;top:0;left:0;width:1px;height:100vh;' +
          'visibility:hidden;pointer-events:none';
        host.appendChild(ruler);
        var h = Math.round(ruler.getBoundingClientRect().height);
        ruler.parentNode.removeChild(ruler);
        return h;
      }

      var ih = Math.round(window.innerHeight || 0);
      var vh;
      try { vh = measureVh(); } catch (e) { vh = -1; }

      // 아직 크기가 없는 문서. 여기서 값을 박아 두면 오히려 잘못 굳는다.
      if (ih <= 0) return { applied: false, reason: 'noHeight', vh: vh, ih: ih };
      if (vh >= 0 && Math.abs(vh - ih) <= CFG.tolerance) {
        return { applied: false, reason: 'ok', vh: vh, ih: ih };
      }

      var root = document.documentElement;
      function apply() {
        var h = Math.round(window.innerHeight || 0);
        if (h > 0) root.style.setProperty(CFG.varName, h + 'px');
      }

      if (!document.getElementById(CFG.styleId)) {
        var style = document.createElement('style');
        style.id = CFG.styleId;
        // 폴백을 `100vh` 로 둔다. 변수가 어떤 이유로 비어도 지금보다 나빠지지 않는다.
        style.textContent = CFG.layers.join(',') +
          '{height:var(' + CFG.varName + ',100vh)!important}';
        (document.head || root).appendChild(style);
      }

      // 앱 패널이 여닫히거나 화면이 돌면 WebView 높이가 바뀐다. 그때 다시 맞춘다.
      if (!window.__catchtrainVhBound) {
        window.__catchtrainVhBound = true;
        window.addEventListener('resize', apply, true);
        window.addEventListener('orientationchange', apply, true);
      }
      apply();
      return { applied: true, vh: vh, ih: ih };
    })();
    """.trimIndent()

    /**
     * 스크롤 되살리기를 끄고 맨 위로. 읽기 + 스크롤뿐이다. (§38-9)
     *
     * `scrollingElement` 와 `body` 를 함께 건드리는 이유는 문서 모드에 따라 실제로
     * 스크롤되는 요소가 갈리기 때문이다. 이미 맨 위면 아무 일도 일어나지 않는다.
     */
    private val SCROLL_TOP_TEMPLATE = """
    (function () {
      function top() {
        try {
          return Math.round(
            window.scrollY || (document.scrollingElement || document.documentElement).scrollTop || 0
          );
        } catch (e) { return -1; }
      }

      var out = { before: top(), after: -1, restore: '' };

      // 다음 새로고침이 이 위치를 되살리지 않게 한다. 이력 항목에 붙는 값이라
      // 나가는 문서에서 걸어 두어야 효과가 있다.
      try {
        if ('scrollRestoration' in history) {
          out.restore = history.scrollRestoration;
          history.scrollRestoration = 'manual';
        }
      } catch (e) { /* 무시 */ }

      try { window.scrollTo(0, 0); } catch (e) { /* 무시 */ }
      try {
        var se = document.scrollingElement || document.documentElement;
        if (se) se.scrollTop = 0;
      } catch (e) { /* 무시 */ }
      try { if (document.body) document.body.scrollTop = 0; } catch (e) { /* 무시 */ }

      out.after = top();
      return out;
    })();
    """.trimIndent()

    /**
     * 열차 목록 분석 스크립트.
     *
     * `li.tckList` 하나가 편성 하나다. 헤더 행이 없으므로 열 위치를 찾을 일도 없다.
     * 등급은 class 로 정하고, 매진 칸처럼 등급 class 가 붙지 않는 칸은
     * **위치로 보정한다** — `[0]=일반실, [1]=특실`. SRT 와 순서가 반대다. (§38-3)
     */
    private val SCRIPT_TEMPLATE = """
    (function () {
      var CFG = /*__CONFIG__*/;
      /*__SIGNATURE__*/

      var warnings = [];

      function allTimes(text) {
        var re = /([0-2]?\d):([0-5]\d)/g;
        var out = [];
        var m;
        while ((m = re.exec(text || '')) !== null) out.push(m[0]);
        return out;
      }

      /**
       * `동탄→김천구미(07:11~08:17)` 에서 역과 시각을 뽑는다.
       *
       * 정규식이 맞지 않으면 시각 두 개와 화살표 분해로 근사한다. 시각을 못 읽으면
       * 그 편성은 버린다 — 시각 없이는 사용자에게 보여줄 수도, 확인할 수도 없다.
       */
      function parseHeading(text) {
        var out = { dep: '', arr: '', depTime: '', arrTime: '' };
        var m;
        try { m = new RegExp(CFG.headingPattern).exec(text); } catch (e) { m = null; }
        if (m) {
          out.dep = m[1];
          out.arr = m[2];
          out.depTime = m[3];
          out.arrTime = m[4];
          return out;
        }
        var times = allTimes(text);
        out.depTime = times[0] || '';
        out.arrTime = times[1] || '';
        var head = (text || '').split('(')[0];
        var parts = head.split(/→|->|~>/);
        if (parts.length >= 2) {
          out.dep = parts[0];
          out.arr = parts[1];
        }
        return out;
      }

      function parseRow(row, index) {
        var heading = parseHeading(ktxFirstText(row, KSEL.heading));
        if (!heading.depTime || !heading.arrTime) {
          warnings.push('시각을 읽지 못한 편성 ' + (index + 1));
          return null;
        }

        var cells = ktxSeatCells(row);

        // 등급은 class 가 1순위다. 매진 칸에는 등급 class 가 붙지 않으므로,
        // 남은 자리는 위치로 채운다. (§38-3)
        var gi = -1;
        var fi = -1;
        for (var i = 0; i < cells.length; i++) {
          var tokens = ktxTokens(cells[i]);
          if (gi < 0 && ktxHas(tokens, KSEL.cls.general)) { gi = i; continue; }
          if (fi < 0 && ktxHas(tokens, KSEL.cls.firstClass)) { fi = i; }
        }
        if (gi < 0 && KSEL.generalIndex < cells.length && KSEL.generalIndex !== fi) {
          gi = KSEL.generalIndex;
        }
        if (fi < 0 && KSEL.firstIndex < cells.length && KSEL.firstIndex !== gi) {
          fi = KSEL.firstIndex;
        }

        var general = gi >= 0 ? cells[gi] : null;
        var first = fi >= 0 ? cells[fi] : null;

        return {
          trainNumber: ktxFirstText(row, KSEL.trainNumber),
          trainType: ktxFirstText(row, KSEL.trainType),
          departureStation: heading.dep,
          arrivalStation: heading.arr,
          departureTime: heading.depTime,
          arrivalTime: heading.arrTime,
          generalSeatText: general ? ktxText(general) : '',
          generalSeatClass: general ? ktxClassOf(general) : '',
          generalSeatStatus: general ? ktxSeatStatus(ktxTokens(general)) : 'UNKNOWN',
          generalCellIndex: gi,
          firstClassSeatText: first ? ktxText(first) : '',
          firstClassSeatClass: first ? ktxClassOf(first) : '',
          firstClassSeatStatus: first ? ktxSeatStatus(ktxTokens(first)) : 'UNKNOWN',
          firstClassCellIndex: fi,
          // 1·2단계에서 "이 편성이 그때 그 편성인지" 확인하는 데 쓴다.
          rowKey: ktxRowKey(row),
          rowIndex: index
        };
      }

      /**
       * 조회 조건의 출발일. 화면에 "무엇을 감시 중인지" 보여주기 위해서만 쓴다.
       *
       * 코레일 조회 폼의 날짜 입력이 어떤 이름/구조인지는 **아직 실측이 없다.** (§38-8)
       * [KtxSelectors.SEARCH_DATE_FIELDS] 가 비어 있으면 이 함수는 빈 문자열을 돌려주고,
       * UI 는 날짜 없이 구간만 보여준다. 표시용이라 비어도 감시에는 아무 영향이 없다.
       */
      function normDate(raw) {
        var d = (raw || '').replace(/[^0-9]/g, '');
        if (d.length < 8) return '';
        d = d.slice(0, 8);
        var y = parseInt(d.slice(0, 4), 10);
        var mo = parseInt(d.slice(4, 6), 10);
        var da = parseInt(d.slice(6, 8), 10);
        if (y < 2000 || y > 2100 || mo < 1 || mo > 12 || da < 1 || da > 31) return '';
        return d.slice(0, 4) + '-' + d.slice(4, 6) + '-' + d.slice(6, 8);
      }

      function searchDateOf() {
        for (var i = 0; i < CFG.dateFields.length; i++) {
          var els = [];
          try { els = document.querySelectorAll(CFG.dateFields[i]); } catch (e) { continue; }
          for (var j = 0; j < els.length; j++) {
            var got = normDate(els[j].value || els[j].getAttribute('value') ||
                               (els[j].innerText || els[j].textContent));
            if (got) return got;
          }
        }
        return '';
      }

      function hasListMarker() {
        for (var i = 0; i < CFG.listMarkers.length; i++) {
          try {
            if (document.querySelector(CFG.listMarkers[i])) return true;
          } catch (e) { /* 잘못된 selector 무시 */ }
        }
        return false;
      }

      var bodyText = '';
      try {
        bodyText = ktxNorm(document.body ? (document.body.innerText || '') : '').slice(0, 3000);
      } catch (e) { bodyText = ''; }

      function containsAny(list) {
        for (var i = 0; i < list.length; i++) {
          if (list[i] && bodyText.indexOf(ktxNorm(list[i])) >= 0) return true;
        }
        return false;
      }

      /** 지금 화면이 로그인 화면인가. (로그인 **여부** 는 KtxLoginScript 가 따로 본다) */
      function looksLikeLogin() {
        for (var i = 0; i < CFG.loginMarkers.length; i++) {
          try {
            if (document.querySelector(CFG.loginMarkers[i])) return true;
          } catch (e) { /* 잘못된 selector 무시 */ }
        }
        return false;
      }

      function looksLikeSessionExpired() {
        return /세션[^가-힣]{0,4}(만료|종료)/.test(bodyText) ||
               /다시로그인/.test(bodyText) ||
               /로그인후이용/.test(bodyText);
      }

      var rows = ktxRows();
      var trains = [];
      for (var r = 0; r < rows.length; r++) {
        var parsed = null;
        try {
          parsed = parseRow(rows[r], r);
        } catch (e) {
          warnings.push('편성 분석 실패: ' + (e && e.message ? e.message : 'unknown'));
        }
        if (parsed) trains.push(parsed);
      }

      var searchDate = '';
      try { searchDate = searchDateOf(); } catch (e) { searchDate = ''; }

      var status;
      // 차단 판정을 가장 먼저 본다. 목록이 남아 있어도 차단되었으면 멈춰야 한다.
      if (containsAny(CFG.blockedMarkers)) {
        status = 'BLOCKED';
      } else if (trains.length > 0) {
        status = 'TRAIN_LIST';
      } else if (looksLikeSessionExpired()) {
        status = 'SESSION_EXPIRED';
      } else if (looksLikeLogin()) {
        status = 'LOGIN_REQUIRED';
      } else if (hasListMarker()) {
        // 목록 컨테이너는 있는데 편성이 없다. 조회 결과가 0건인 화면이다. (§38-5)
        status = 'NO_TRAIN';
      } else {
        status = 'UNKNOWN_PAGE';
      }

      return {
        status: status,
        url: location.href,
        title: document.title || '',
        rowCount: rows.length,
        searchDate: searchDate,
        trains: trains,
        warnings: warnings
      };
    })();
    """.trimIndent()
}
