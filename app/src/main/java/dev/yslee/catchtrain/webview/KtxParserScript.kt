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
     * 결과 화면의 [열차조회] 버튼이 **화면 어디에 있는지** 알아낸다. (§10, §38-5)
     *
     * 이 스크립트는 버튼을 누르지 않는다. 좌표만 알려준다.
     * 실제 클릭은 Kotlin 쪽에서 [android.view.MotionEvent] 를 내려보내는,
     * 즉 **사용자가 그 위치를 손가락으로 누르는 것과 같은** 방식으로 한다.
     *
     * **문구는 완전일치로만 본다.** 바로 옆에 `다음날 (26년09월02일) 조회` 버튼이 있어서
     * "조회" 부분일치로 고르면 사용자가 보던 날짜가 아닌 **다음날을 조회해 버린다.** (§38-5)
     * 그래서 selector 로 잡혔더라도 문구가 완전일치하지 않으면 후보로 올리지 않는다.
     * 찾지 못하면 다른 방법으로 대체하지 않고 그대로 실패를 올린다.
     *
     * 반환 형식:
     * ```json
     * {"found": true, "tappable": true, "x": 540.0, "y": 812.0, "at": "0.5,0.5", "label": "열차조회"}
     * {"found": true, "tappable": false, "reason": "COVERED|OFF_SCREEN|ZERO_SIZE"}
     * {"found": false, "reason": "BUTTON_NOT_FOUND", "scanned": 12, "near": ["..."]}
     * ```
     */
    fun buildLocateScript(viewWidthPx: Int, viewHeightPx: Int): String {
        val config = buildString {
            append("{")
            append("selectors:").append(jsArray(KtxSelectors.RESEARCH_BUTTON)).append(",")
            append("texts:").append(jsArray(KtxSelectors.RESEARCH_TEXTS_EXACT)).append(",")
            append("exclude:").append(jsArray(KtxSelectors.RESEARCH_TEXT_EXCLUDE)).append(",")
            append("scopes:").append(jsArray(KtxSelectors.SEARCH_FORM_SCOPES))
            append("}")
        }
        return LOCATE_TEMPLATE.withSignature().withTapPoint()
            .replace(CONFIG_PLACEHOLDER, config)
            .replace(VIEW_PLACEHOLDER, "{w:$viewWidthPx,h:$viewHeightPx}")
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

    // ---------------------------------------------------------------- 조립 도구

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
     * 재조회 버튼 탐색 스크립트.
     *
     * 후보를 점수로 고른 뒤, **그 버튼을 실제로 누를 수 있는 화면 좌표**를 돌려준다.
     * 아무것도 클릭하지 않는다. (클릭은 Kotlin 쪽 진짜 터치가 담당한다)
     *
     * 좌표는 두 단계로 검증한다.
     *  1) 화면(visual viewport) 안에 들어와 있는가 — 밖이면 손가락도 닿지 않는다.
     *  2) document.elementFromPoint 로 그 지점의 최상위 요소가 정말 이 버튼인가 —
     *     고정 헤더나 배너가 덮고 있으면 사람이 눌러도 그 요소가 눌린다.
     *
     * SRT 판과 한 가지가 다르다. **문구가 완전일치하지 않으면 후보로 올리지 않는다.**
     * SRT 에서는 selector 로 잡힌 것을 문구 없이도 인정했지만(이미지 버튼 대비),
     * 코레일은 같은 selector 에 `다음날 (…) 조회` 버튼이 함께 걸리므로 그 관용이
     * 곧바로 **엉뚱한 날짜 조회**가 된다. (§38-5)
     */
    private val LOCATE_TEMPLATE = """
    (function () {
      var CFG = /*__CONFIG__*/;
      var VIEW = /*__VIEW__*/;
      /*__SIGNATURE__*/
      /*__TAPPOINT__*/

      var scanned = 0;

      // 편성 안의 버튼(좌석 칸 등)일 가능성. 하드 제외가 아니라 감점 요소로만 쓴다.
      function inTrainList(el) {
        var rows = ktxRows();
        for (var i = 0; i < rows.length; i++) {
          if (rows[i].contains && rows[i].contains(el)) return true;
        }
        return false;
      }

      function excluded(label) {
        for (var i = 0; i < CFG.exclude.length; i++) {
          if (label.indexOf(ktxNorm(CFG.exclude[i])) >= 0) return true;
        }
        return false;
      }

      /** **완전일치.** 부분일치를 쓰면 옆의 `다음날 … 조회` 를 누른다. (§38-5) */
      function textRank(label) {
        for (var i = 0; i < CFG.texts.length; i++) {
          if (label === ktxNorm(CFG.texts[i])) return i;
        }
        return -1;
      }

      function scopeRank(el) {
        for (var i = 0; i < CFG.scopes.length; i++) {
          var scope;
          try { scope = document.querySelector(CFG.scopes[i]); } catch (e) { continue; }
          if (scope && scope.contains(el)) return i;
        }
        return CFG.scopes.length;
      }

      /**
       * 후보를 모두 모아 점수로 고른다.
       *
       * 보이지 않는 요소도 후보로 남기되 크게 감점한다. 진짜 터치는 화면에 보이는 것만
       * 누를 수 있으므로 안 보이는 후보가 뽑히면 결국 tappable=false 로 끝나지만,
       * ktxVisible() 판정이 틀렸을 때 진짜 버튼을 통째로 버리지 않기 위해서다.
       */
      var candidates = [];
      var seen = [];

      function addCandidate(el, how, by) {
        if (!el || el.nodeType !== 1) return;
        if (seen.indexOf(el) >= 0) return;
        seen.push(el);
        scanned++;

        var label = ktxLabelOf(el);
        if (excluded(label)) return;
        var rank = textRank(label);
        if (rank < 0) return;

        candidates.push({
          el: el,
          how: how,
          by: by,
          label: label,
          rank: rank,
          scope: scopeRank(el),
          hidden: ktxVisible(el) ? 0 : 1,
          inList: inTrainList(el) ? 1 : 0
        });
      }

      function collect() {
        for (var i = 0; i < CFG.selectors.length; i++) {
          var list;
          try { list = document.querySelectorAll(CFG.selectors[i]); } catch (e) { continue; }
          for (var j = 0; j < list.length; j++) addCandidate(list[j], 'selector', CFG.selectors[i]);
        }
        var nodes = document.querySelectorAll(
          'a, button, input[type=submit], input[type=button], input[type=image], [role=button]');
        for (var k = 0; k < nodes.length; k++) addCandidate(nodes[k], 'text', 'text');
      }

      // 앞의 조건이 우선. 보이는 것 > 목록 밖 > 조회 영역 > 문구 > selector 경로
      function better(a, b) {
        if (a.hidden !== b.hidden) return a.hidden < b.hidden;
        if (a.inList !== b.inList) return a.inList < b.inList;
        if (a.scope !== b.scope) return a.scope < b.scope;
        if (a.rank !== b.rank) return a.rank < b.rank;
        return a.how === 'selector' && b.how !== 'selector';
      }

      function pick() {
        var best = null;
        for (var i = 0; i < candidates.length; i++) {
          if (best === null || better(candidates[i], best)) best = candidates[i];
        }
        return best;
      }

      /**
       * 실패했을 때 "무엇이 있었고 왜 못 눌렀는지"를 남긴다.
       * 로그만 보고 [KtxSelectors] 를 고칠 수 있어야 한다.
       */
      function diagnose() {
        var nodes = document.querySelectorAll('a, button, input, [role=button]');
        var out = [];
        for (var i = 0; i < nodes.length && out.length < 5; i++) {
          var label = ktxLabelOf(nodes[i]);
          if (label.indexOf('조회') < 0) continue;
          var note = nodes[i].tagName.toLowerCase();
          var type = nodes[i].getAttribute ? nodes[i].getAttribute('type') : null;
          if (type) note += ':' + type;
          note += '[' + label.slice(0, 12) + ']';
          if (!ktxVisible(nodes[i])) note += ' hidden';
          if (excluded(label)) note += ' excluded';
          if (inTrainList(nodes[i])) note += ' inList';
          var cls = ktxClassOf(nodes[i]);
          if (cls) note += ' .' + cls.split(/\s+/).join('.');
          out.push(note);
        }
        return out;
      }

      collect();
      var found = pick();
      if (found === null) {
        // 버튼이 없는 이유는 대개 "selector 가 틀렸다"가 아니라
        // "지금 보고 있는 화면이 조회 결과가 아니다" 다. (차단 안내 / 오류 화면 등)
        // 그래서 어떤 문서를 보고 있었는지 함께 남긴다.
        var body = '';
        try { body = ktxNorm(document.body ? (document.body.innerText || '') : ''); } catch (e) { body = ''; }
        return {
          found: false,
          reason: 'BUTTON_NOT_FOUND',
          scanned: scanned,
          near: diagnose(),
          url: location.href,
          title: document.title || '',
          counts: 'a=' + document.querySelectorAll('a').length +
                  ' button=' + document.querySelectorAll('button').length +
                  ' li=' + ktxRows().length +
                  ' iframe=' + document.querySelectorAll('iframe, frame').length,
          bodyHead: body.slice(0, 120)
        };
      }

      var point = ktxTapPoint(found.el);
      var out = {
        found: true,
        how: found.how,
        by: found.by,
        label: found.label,
        tag: found.el.tagName,
        type: found.el.getAttribute ? (found.el.getAttribute('type') || '') : '',
        hidden: found.hidden === 1,
        inList: found.inList === 1,
        candidates: candidates.length,
        scanned: scanned,
        rect: point.rect || ''
      };

      if (point.reason) {
        out.tappable = false;
        out.reason = point.reason;
        out.covered = point.covered || '';
        out.viewport = point.viewport || '';
        out.url = location.href;
        return out;
      }

      ktxArmConfirm(found.el);
      out.tappable = true;
      out.x = point.x;
      out.y = point.y;
      out.at = point.at;
      return out;
    })();
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
