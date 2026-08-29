package com.example.srtwatcher.webview

import org.json.JSONArray
import org.json.JSONObject

/**
 * WebView 안에서 실행할 DOM 분석 스크립트. (DESIGN.md §13)
 *
 * 설계 원칙
 *  - 서버 API 를 직접 호출하지 않고, **현재 화면에 렌더링된 DOM** 만 읽는다.
 *  - selector 는 [SrtSelectors] 에서 주입받는다. (§28)
 *  - selector 가 맞지 않아도 표 헤더 텍스트로 열 위치를 찾아내는 휴리스틱으로
 *    동작하도록 만들어, 사이트 구조가 조금 바뀌어도 버티게 한다.
 *  - 부작용이 전혀 없다. 클릭도 하지 않고 값을 바꾸지도 않는다. 읽기만 한다.
 *
 * 반환값은 객체이며, WebView 가 JSON 으로 직렬화해서 Kotlin 쪽에 넘긴다.
 * 형식:
 * ```json
 * {
 *   "status": "TRAIN_LIST|NO_TRAIN|LOGIN_REQUIRED|SESSION_EXPIRED|UNKNOWN_PAGE",
 *   "url": "...", "title": "...", "rowCount": 8, "searchDate": "2026-08-24",
 *   "trains": [{
 *      "trainNumber": "SRT 339", "trainType": "SRT",
 *      "departureStation": "수서", "arrivalStation": "부산",
 *      "departureTime": "18:30", "arrivalTime": "21:05",
 *      "generalSeatText": "예약하기", "generalSeatStatus": "AVAILABLE",
 *      "firstClassSeatText": "매진", "firstClassSeatStatus": "SOLD_OUT"
 *   }],
 *   "warnings": ["..."]
 * }
 * ```
 */
object SrtParserScript {

    /** DOM 을 읽어 열차 목록 JSON 을 돌려주는 스크립트. */
    fun build(): String {
        val config = buildString {
            append("{")
            append("tables:").append(jsArray(SrtSelectors.TRAIN_TABLE)).append(",")
            append("rows:").append(jsArray(SrtSelectors.TRAIN_ROW)).append(",")
            append("loginMarkers:").append(jsArray(SrtSelectors.LOGIN_MARKERS)).append(",")
            append("blockedMarkers:").append(jsArray(SrtSelectors.BLOCKED_MARKERS)).append(",")
            append("scheduleHints:").append(jsArray(SrtSelectors.SCHEDULE_URL_HINTS)).append(",")
            append("loginHints:").append(jsArray(SrtSelectors.LOGIN_URL_HINTS)).append(",")
            append("dateFields:").append(jsArray(SrtSelectors.SEARCH_DATE_FIELDS)).append(",")
            append("header:{")
            append("trainType:").append(jsArray(SrtSelectors.HeaderKeywords.TRAIN_TYPE)).append(",")
            append("departure:").append(jsArray(SrtSelectors.HeaderKeywords.DEPARTURE)).append(",")
            append("arrival:").append(jsArray(SrtSelectors.HeaderKeywords.ARRIVAL)).append(",")
            append("general:").append(jsArray(SrtSelectors.HeaderKeywords.GENERAL)).append(",")
            append("firstClass:").append(jsArray(SrtSelectors.HeaderKeywords.FIRST_CLASS)).append(",")
            append("waiting:").append(jsArray(SrtSelectors.HeaderKeywords.WAITING))
            append("}}")
        }
        return SCRIPT_TEMPLATE.withSignature().replace(CONFIG_PLACEHOLDER, config)
    }

    /**
     * 결과 페이지의 "조회하기" 버튼이 **화면에서 어디에 있는지** 알아내는 스크립트.
     * (§10 재조회 방식)
     *
     * 이 스크립트는 버튼을 누르지 않는다. 좌표만 알려준다.
     * 실제 클릭은 Kotlin 쪽에서 WebView 에 [android.view.MotionEvent] 를 내려보내는,
     * 즉 **사용자가 그 위치를 손가락으로 누르는 것과 같은** 방식으로 한다.
     * (SrtWebViewHost.tap)
     *
     * JS 에서 dispatchEvent / el.click() 으로 만든 클릭을 쓰지 않는 이유:
     *  - isTrusted=false 이고 브라우저 입력 파이프라인을 거치지 않아 구분된다.
     *  - a[href] 를 누르거나 form.submit() 을 부르면 결국 URL 로 직접 접근하는 요청이 되고,
     *    이 경로는 SRT 쪽에서 사실상 항상 차단된다.
     *
     * @param viewWidthPx WebView 위젯의 가로 크기(px). 좌표 환산에 쓴다.
     * @param viewHeightPx WebView 위젯의 세로 크기(px)
     *
     * 반환 형식:
     * ```json
     * {"found": true, "tappable": true, "x": 540.0, "y": 812.0, "at": "0.5,0.5",
     *  "label": "조회하기", "tag": "INPUT", "type": "submit", "candidates": 3}
     * {"found": true, "tappable": false, "reason": "COVERED|OFF_SCREEN|ZERO_SIZE", "covered": "div#header"}
     * {"found": false, "reason": "BUTTON_NOT_FOUND", "scanned": 12}
     * ```
     */
    fun buildLocateScript(viewWidthPx: Int, viewHeightPx: Int): String {
        val config = buildString {
            append("{")
            append("selectors:").append(jsArray(SrtSelectors.RESEARCH_BUTTON)).append(",")
            append("texts:").append(jsArray(SrtSelectors.RESEARCH_TEXTS)).append(",")
            append("exclude:").append(jsArray(SrtSelectors.RESEARCH_TEXT_EXCLUDE)).append(",")
            append("scopes:").append(jsArray(SrtSelectors.SEARCH_FORM_SCOPES))
            append("}")
        }
        return LOCATE_TEMPLATE.withSignature().withTapPoint()
            .replace(CONFIG_PLACEHOLDER, config)
            .replace(VIEW_PLACEHOLDER, "{w:$viewWidthPx,h:$viewHeightPx}")
    }

    /**
     * 조건을 만족한 열차의 **[예약하기] 버튼이 화면 어디에 있는지** 알아내는 스크립트.
     *
     * [buildLocateScript] 와 마찬가지로 아무것도 누르지 않는다. 좌표만 알려준다.
     *
     * 재조회 버튼과 결정적으로 다른 점은 **탐색 범위**다.
     * 조회하기는 화면에 하나뿐이라 문서 전체에서 찾아도 되지만,
     * 예약하기는 열차마다 하나씩 있으므로 **그 열차의 그 좌석 칸 안에서만** 찾는다.
     * 범위를 좁히지 못하거나 후보가 여럿이면 좌표를 돌려주지 않는다.
     * 엉뚱한 열차를 예약하느니 아무것도 안 하는 편이 낫다.
     *
     * 반환 형식:
     * ```json
     * {"found": true, "tappable": true, "x": 540.0, "y": 812.0, "label": "예약하기", "scope": "cell"}
     * {"found": true, "tappable": false, "reason": "COVERED|OFF_SCREEN|ZERO_SIZE"}
     * {"found": false, "reason": "ROW_NOT_FOUND|ROW_AMBIGUOUS|ROW_MISMATCH|BUTTON_NOT_FOUND|BUTTON_AMBIGUOUS"}
     * ```
     */
    fun buildReserveScript(
        viewWidthPx: Int,
        viewHeightPx: Int,
        target: ReserveTarget,
    ): String {
        val config = buildString {
            append("{")
            append("rowKey:").append(jsString(target.rowKey)).append(",")
            append("rowIndex:").append(target.rowIndex).append(",")
            append("cellIndex:").append(target.cellIndex).append(",")
            append("trainNumber:").append(jsString(target.trainNumber)).append(",")
            append("departureTime:").append(jsString(target.departureTime)).append(",")
            append("selectors:").append(jsArray(SrtSelectors.RESERVE_BUTTON)).append(",")
            append("texts:").append(jsArray(SrtSelectors.RESERVE_TEXTS)).append(",")
            append("exclude:").append(jsArray(SrtSelectors.RESERVE_TEXT_EXCLUDE))
            append("}")
        }
        return RESERVE_TEMPLATE.withSignature().withTapPoint()
            .replace(CONFIG_PLACEHOLDER, config)
            .replace(VIEW_PLACEHOLDER, "{w:$viewWidthPx,h:$viewHeightPx}")
    }

    /**
     * [예약하기] 를 누른 **뒤에** 실행한다. 지금 화면이 예약 실패 안내인지 알려준다.
     * (DESIGN.md §19-2)
     *
     * 좌석이 열린 것을 보고 눌러도, 그사이 다른 사람이 먼저 잡으면 예약 화면 대신
     * "잔여석없음" 안내가 뜬다. 화면은 정상적으로 전환되므로 클릭 성공과 구분이 되지 않는다.
     * 그래서 전환된 화면의 본문 문구를 한 번 더 확인한다.
     *
     * 오탐을 줄이기 위해 문구만으로 단정하지 않는다. 예약 결과 URL 이거나
     * 열차 목록 표가 없는 화면일 때만 실패로 본다. 아무것도 누르지 않고 읽기만 한다.
     *
     * 되돌아간 뒤에 한 번 더 실행해서, 정말 그 화면을 벗어났는지 확인하는 데도 쓴다.
     *
     * 반환 형식:
     * ```json
     * {"failed": true, "marker": "잔여석없음", "reserveUrl": true, "rows": 0,
     *  "url": "...", "title": "...", "head": "..."}
     * ```
     */
    fun buildReserveResultScript(): String {
        val config = buildString {
            append("{")
            append("markers:").append(jsArray(SrtSelectors.RESERVE_FAILED_MARKERS)).append(",")
            append("urlHints:").append(jsArray(SrtSelectors.RESERVE_RESULT_URL_HINTS))
            append("}")
        }
        return RESERVE_RESULT_TEMPLATE.withSignature().replace(CONFIG_PLACEHOLDER, config)
    }

    /**
     * 터치를 내려보낸 **직후에** 실행한다.
     * 진짜 클릭이 버튼까지 도달했는지 알려준다. (로그/진단용)
     *
     * 반환: `{"known": true, "fired": true, "trusted": true, "onTarget": true, "tag": "input.inquery_btn"}`
     * 화면이 전환되어 document 가 새로 만들어졌으면 `{"known": false}`.
     */
    fun buildTapConfirmScript(): String = TAP_CONFIRM_TEMPLATE

    /**
     * 클릭 **직전에** 실행한다. 결과 영역에 MutationObserver 를 걸고
     * 현재 표 시그니처를 기록한다. 화면 전환이 없는(AJAX) 재조회를 감지하기 위한 준비다.
     *
     * 반환: `{"sig": "8:1a2b3c", "observing": true}`
     */
    fun buildObserverScript(): String = OBSERVER_TEMPLATE.withSignature()

    /**
     * 클릭 **이후에** 주기적으로 실행한다. DOM 이 갱신되었는지 알려준다.
     *
     * 반환: `{"sig": "8:9z8y7x", "mut": 3, "changed": true}`
     */
    fun buildProbeScript(): String = PROBE_TEMPLATE.withSignature()

    private fun jsString(value: String): String = JSONObject.quote(value)

    private fun jsArray(values: List<String>): String {
        val array = JSONArray()
        values.forEach { array.put(it) }
        return array.toString()
    }

    private fun String.withSignature(): String = replace(SIGNATURE_PLACEHOLDER, SIGNATURE_JS)

    private fun String.withTapPoint(): String = replace(TAPPOINT_PLACEHOLDER, TAPPOINT_JS)

    private const val CONFIG_PLACEHOLDER = "/*__CONFIG__*/"
    private const val SIGNATURE_PLACEHOLDER = "/*__SIGNATURE__*/"

    /**
     * 결과 표의 "지금 내용"을 짧은 문자열로 요약하는 공용 함수.
     *
     * 재조회가 실제로 반영되었는지 판단하는 데만 쓴다. 그래서 파싱 쪽의
     * 정교한 표 선택 로직을 다시 쓰지 않고, tbody 행이 가장 많은 표를
     * 결과 표로 보는 값싼 판정을 사용한다. 읽기만 하고 아무것도 바꾸지 않는다.
     */
    private val SIGNATURE_JS = """
      function srtwNorm(s) { return (s || '').replace(/\s+/g, ''); }

      // 열차 목록 표. "행에 시각이 2개 이상(출발/도착)" 인 행이 가장 많은 표를 고른다.
      // 단순히 행이 가장 많은 표를 고르면, 표 레이아웃으로 만들어진
      // 상단 검색 폼이 결과 표로 오인될 수 있다.
      function srtwResultTable() {
        var tables = document.querySelectorAll('table');
        var best = null;
        var bestScore = 0;
        for (var i = 0; i < tables.length; i++) {
          var rows = tables[i].querySelectorAll('tbody tr');
          var rich = 0;
          for (var r = 0; r < rows.length; r++) {
            var text = rows[r].innerText || rows[r].textContent || '';
            var times = text.match(/[0-2]?\d:[0-5]\d/g);
            if (times && times.length >= 2) rich++;
          }
          if (rich > bestScore) { bestScore = rich; best = tables[i]; }
        }
        return best;
      }

      /**
       * 한 행(tr)의 "지금 내용"을 짧은 문자열로 요약한다.
       *
       * 분석할 때 읽은 행과 예약하기를 누를 때의 행이 정말 같은 행인지
       * 확인하는 데 쓴다. 표가 바뀌었는데 위치(index)만 믿고 누르면
       * 엉뚱한 열차를 예약하게 되므로, 위치가 아니라 이 값을 기준으로 삼는다.
       */
      function srtwRowKey(row) {
        var tds = row.querySelectorAll('td');
        var line = '';
        for (var c = 0; c < tds.length; c++) {
          line += srtwNorm(tds[c].innerText || tds[c].textContent) + ',';
        }
        var h = 5381;
        for (var k = 0; k < line.length; k++) {
          h = ((h * 33) ^ line.charCodeAt(k)) & 0x7fffffff;
        }
        return tds.length + ':' + h.toString(36);
      }

      function srtwSignature() {
        var table = srtwResultTable();
        if (!table) return 'no-table';
        var rows = table.querySelectorAll('tbody tr');
        var buf = [];
        for (var r = 0; r < rows.length; r++) {
          var tds = rows[r].querySelectorAll('td');
          var line = '';
          for (var c = 0; c < tds.length; c++) {
            line += srtwNorm(tds[c].innerText || tds[c].textContent) + ',';
          }
          buf.push(line);
        }
        var joined = buf.join('#');
        var h = 5381;
        for (var k = 0; k < joined.length; k++) {
          h = ((h * 33) ^ joined.charCodeAt(k)) & 0x7fffffff;
        }
        return rows.length + ':' + h.toString(36);
      }
    """.trimIndent()

    private const val VIEW_PLACEHOLDER = "/*__VIEW__*/"

    private const val TAPPOINT_PLACEHOLDER = "/*__TAPPOINT__*/"

    /**
     * "이 요소를 누르려면 화면의 어느 픽셀을 눌러야 하는가" 를 계산하는 공용 함수들.
     *
     * 재조회([LOCATE_TEMPLATE])와 예약하기([RESERVE_TEMPLATE])가 똑같은 규칙으로
     * 좌표를 잡아야 하므로 한곳에 모았다. 아무것도 클릭하지 않고 좌표만 계산한다.
     *
     * 이 블록을 쓰는 스크립트는 `VIEW` ({w,h}) 와 `srtwNorm` 이 이미 정의되어 있어야 한다.
     */
    private val TAPPOINT_JS = """
      function srtwLabelOf(el) {
        var v = '';
        if (el.tagName === 'INPUT') {
          v = el.value || el.getAttribute('value') || el.getAttribute('alt') || '';
        }
        if (!v) v = el.innerText || el.textContent || '';
        if (!v) v = el.getAttribute('title') || el.getAttribute('aria-label') || '';
        return srtwNorm(v);
      }

      function srtwVisible(el) {
        if (!el || el.disabled) return false;
        var rect = el.getBoundingClientRect();
        if (rect.width <= 0 || rect.height <= 0) return false;
        var style = window.getComputedStyle ? window.getComputedStyle(el) : null;
        if (style && (style.visibility === 'hidden' || style.display === 'none')) return false;
        return true;
      }

      function srtwDescribeEl(el) {
        if (!el) return 'none';
        var s = el.tagName ? el.tagName.toLowerCase() : '?';
        if (el.id) s += '#' + el.id;
        var cls = el.className;
        if (cls && typeof cls === 'string') s += '.' + cls.split(/\s+/).slice(0, 2).join('.');
        return s;
      }

      function srtwRectText(r) {
        return Math.round(r.left) + ',' + Math.round(r.top) +
               ' ' + Math.round(r.width) + 'x' + Math.round(r.height);
      }

      /**
       * CSS 좌표 → WebView 위젯 픽셀 좌표 환산 기준.
       *
       * getBoundingClientRect 는 레이아웃 뷰포트(CSS px) 기준이고
       * 터치는 위젯 픽셀로 보내야 한다. 지금 화면에 실제로 보이는 영역인
       * visual viewport 가 위젯 전체(VIEW)에 대응하므로 그 비율로 환산한다.
       * 핀치 줌이나 가로 스크롤 상태에서도 맞도록 offset 을 함께 뺀다.
       */
      function srtwViewport() {
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
      function srtwHitAt(el, cx, cy) {
        var hit = null;
        try { hit = document.elementFromPoint(cx, cy); } catch (e) { return null; }
        if (!hit) return null;
        return { ok: hit === el || el.contains(hit), hit: hit };
      }

      /**
       * 누를 지점을 고른다. 가운데부터 시도하고, 가려져 있으면
       * 요소 안쪽의 다른 지점을 차례로 시도한다.
       * 사람도 버튼 정중앙만 정확히 누르지는 않는다.
       */
      function srtwTapPoint(el) {
        try { el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' }); }
        catch (e) { try { el.scrollIntoView(); } catch (e2) { /* 무시 */ } }

        var rect = el.getBoundingClientRect();
        if (rect.width <= 0 || rect.height <= 0) {
          return { reason: 'ZERO_SIZE', rect: srtwRectText(rect) };
        }

        var vp = srtwViewport();
        if (vp.rx <= 0 || vp.ry <= 0) {
          return { reason: 'NO_VIEWPORT', rect: srtwRectText(rect) };
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

          var r = srtwHitAt(el, cx, cy);
          if (r === null) { outside++; continue; }
          if (r.ok) {
            return {
              x: dx,
              y: dy,
              at: fracs[i][0] + ',' + fracs[i][1],
              rect: srtwRectText(rect)
            };
          }
          if (covered === null) covered = srtwDescribeEl(r.hit);
        }

        return {
          reason: covered !== null ? 'COVERED' : 'OFF_SCREEN',
          covered: covered || '',
          outside: outside,
          rect: srtwRectText(rect),
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
      function srtwArmConfirm(el) {
        var state = { fired: 0, trusted: 0, onTarget: 0, tag: '' };
        window.__srtTap = state;
        function onClick(e) {
          state.fired = 1;
          state.trusted = e.isTrusted ? 1 : 0;
          state.onTarget = (e.target === el || el.contains(e.target)) ? 1 : 0;
          state.tag = srtwDescribeEl(e.target);
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
     * 버튼을 못 찾으면 found=false, 찾았지만 누를 수 없으면 tappable=false 를 돌려준다.
     * Kotlin 쪽은 reload 나 URL 이동으로 대체하지 않는다. (그 경로는 차단된다)
     */
    private val LOCATE_TEMPLATE = """
    (function () {
      var CFG = /*__CONFIG__*/;
      var VIEW = /*__VIEW__*/;
      /*__SIGNATURE__*/
      /*__TAPPOINT__*/

      var scanned = 0;

      // 열차 목록 표 안의 버튼(예약하기 등)일 가능성.
      // 하드 제외가 아니라 감점 요소로만 쓴다. 표 판정이 틀렸을 때
      // 정작 진짜 조회하기 버튼을 버리는 일이 없어야 한다.
      function inResultTable(el) {
        var table = srtwResultTable();
        return !!(table && table.contains && table.contains(el));
      }

      function excluded(label) {
        for (var i = 0; i < CFG.exclude.length; i++) {
          if (label.indexOf(CFG.exclude[i]) >= 0) return true;
        }
        return false;
      }

      function textRank(label) {
        for (var i = 0; i < CFG.texts.length; i++) {
          if (label.indexOf(CFG.texts[i]) >= 0) return i;
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
       * 보이지 않는 요소도 후보로 남기되 크게 감점한다.
       * 진짜 터치는 화면에 보이는 것만 누를 수 있으므로 안 보이는 후보가
       * 최종 선택되면 결국 tappable=false 로 끝나지만, srtwVisible() 판정이
       * 틀렸을 때 진짜 버튼을 통째로 버리지 않기 위해 후보로는 남겨둔다.
       */
      var candidates = [];
      var seen = [];

      function addCandidate(el, how, by) {
        if (!el || el.nodeType !== 1) return;
        if (seen.indexOf(el) >= 0) return;
        seen.push(el);
        scanned++;

        var label = srtwLabelOf(el);
        if (excluded(label)) return;

        var rank = textRank(label);
        if (how === 'text' && rank < 0) return;
        // selector 로 잡힌 후보는 문구가 없어도(이미지 버튼 등) 인정한다.
        if (rank < 0) rank = CFG.texts.length;

        candidates.push({
          el: el,
          how: how,
          by: by,
          label: label,
          rank: rank,
          scope: scopeRank(el),
          hidden: srtwVisible(el) ? 0 : 1,
          inTable: inResultTable(el) ? 1 : 0
        });
      }

      function collect() {
        for (var i = 0; i < CFG.selectors.length; i++) {
          var list;
          try { list = document.querySelectorAll(CFG.selectors[i]); } catch (e) { continue; }
          for (var j = 0; j < list.length; j++) addCandidate(list[j], 'selector', CFG.selectors[i]);
        }
        var nodes = document.querySelectorAll(
          'a, button, input[type=submit], input[type=button], input[type=image]');
        for (var k = 0; k < nodes.length; k++) addCandidate(nodes[k], 'text', 'text');
      }

      // 앞의 조건이 우선. 보이는 것 > 결과 표 밖 > 검색 영역 > 문구 > selector 경로
      function better(a, b) {
        if (a.hidden !== b.hidden) return a.hidden < b.hidden;
        if (a.inTable !== b.inTable) return a.inTable < b.inTable;
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
       * 로그만 보고 selector 를 고칠 수 있어야 한다.
       */
      function diagnose() {
        var nodes = document.querySelectorAll('a, button, input, [role=button]');
        var out = [];
        for (var i = 0; i < nodes.length && out.length < 5; i++) {
          var label = srtwLabelOf(nodes[i]);
          if (label.indexOf('조회') < 0) continue;
          var note = nodes[i].tagName.toLowerCase();
          var type = nodes[i].getAttribute ? nodes[i].getAttribute('type') : null;
          if (type) note += ':' + type;
          note += '[' + label.slice(0, 12) + ']';
          if (!srtwVisible(nodes[i])) note += ' hidden';
          if (excluded(label)) note += ' excluded';
          if (inResultTable(nodes[i])) note += ' inTable';
          var cls = nodes[i].className;
          if (cls && typeof cls === 'string') note += ' .' + cls.split(/\s+/).join('.');
          out.push(note);
        }
        return out;
      }

      collect();
      var found = pick();
      if (found === null) {
        // 버튼이 없는 이유는 대개 "selector 가 틀렸다"가 아니라
        // "지금 보고 있는 페이지가 조회 결과 페이지가 아니다" 다.
        // (차단 안내 페이지 / WebView 오류 페이지 등)
        // 그래서 어떤 문서를 보고 있었는지 함께 남긴다.
        var body = '';
        try { body = srtwNorm(document.body ? (document.body.innerText || '') : ''); } catch (e) { body = ''; }
        return {
          found: false,
          reason: 'BUTTON_NOT_FOUND',
          scanned: scanned,
          near: diagnose(),
          url: location.href,
          title: document.title || '',
          counts: 'a=' + document.querySelectorAll('a').length +
                  ' button=' + document.querySelectorAll('button').length +
                  ' input=' + document.querySelectorAll('input').length +
                  ' table=' + document.querySelectorAll('table').length +
                  ' iframe=' + document.querySelectorAll('iframe, frame').length,
          bodyHead: body.slice(0, 120)
        };
      }

      var point = srtwTapPoint(found.el);
      var out = {
        found: true,
        how: found.how,
        by: found.by,
        label: found.label,
        tag: found.el.tagName,
        type: found.el.getAttribute ? (found.el.getAttribute('type') || '') : '',
        hidden: found.hidden === 1,
        inTable: found.inTable === 1,
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

      srtwArmConfirm(found.el);
      out.tappable = true;
      out.x = point.x;
      out.y = point.y;
      out.at = point.at;
      return out;
    })();
    """.trimIndent()

    /**
     * 예약하기 버튼 탐색 스크립트.
     *
     * 순서
     *  1) 분석할 때 읽었던 그 행을 다시 찾는다. 기준은 위치가 아니라 행 내용([srtwRowKey])이다.
     *  2) 그 행이 정말 그 열차인지 출발 시각으로 한 번 더 확인한다.
     *  3) 지정된 좌석 칸 **안에서만** [예약하기] 문구를 가진 요소를 찾는다.
     *  4) 그 요소를 누를 수 있는 화면 좌표를 돌려준다.
     *
     * 어느 단계든 확실하지 않으면 좌표 대신 실패 이유를 돌려준다.
     */
    private val RESERVE_TEMPLATE = """
    (function () {
      var CFG = /*__CONFIG__*/;
      var VIEW = /*__VIEW__*/;
      /*__SIGNATURE__*/
      /*__TAPPOINT__*/

      // ---------------------------------------------------------------- 1) 행 찾기

      function dataRows() {
        var all = document.querySelectorAll('table tr');
        var out = [];
        for (var i = 0; i < all.length; i++) {
          if (all[i].querySelectorAll('td').length > 0) out.push(all[i]);
        }
        return out;
      }

      var rows = dataRows();
      var hits = [];
      for (var i = 0; i < rows.length; i++) {
        if (srtwRowKey(rows[i]) === CFG.rowKey) hits.push(rows[i]);
      }

      if (hits.length === 0) {
        return {
          found: false,
          reason: 'ROW_NOT_FOUND',
          detail: '행 ' + rows.length + '개 중 일치 없음 key=' + CFG.rowKey,
          url: location.href
        };
      }

      // 같은 내용의 행이 둘 이상이면(레이아웃용 중복 표 등) 보이는 것만 남긴다.
      if (hits.length > 1) {
        var shown = [];
        for (var h = 0; h < hits.length; h++) {
          if (srtwVisible(hits[h])) shown.push(hits[h]);
        }
        if (shown.length !== 1) {
          return {
            found: false,
            reason: 'ROW_AMBIGUOUS',
            detail: '같은 내용의 행 ' + hits.length + '개 (보이는 것 ' + shown.length + '개)'
          };
        }
        hits = shown;
      }

      var row = hits[0];
      var rowText = srtwNorm(row.innerText || row.textContent || '');

      // 2) 해시가 우연히 겹쳤을 가능성까지 막는다. 출발 시각이 그 행에 있어야 한다.
      if (CFG.departureTime && rowText.indexOf(srtwNorm(CFG.departureTime)) < 0) {
        return {
          found: false,
          reason: 'ROW_MISMATCH',
          detail: CFG.departureTime + ' 없음 / row=' + rowText.slice(0, 60)
        };
      }

      // ---------------------------------------------------------------- 3) 버튼 찾기

      var tds = row.querySelectorAll('td');
      var cell = (CFG.cellIndex >= 0 && CFG.cellIndex < tds.length) ? tds[CFG.cellIndex] : null;
      var scope = cell || row;

      function excluded(label) {
        for (var i = 0; i < CFG.exclude.length; i++) {
          if (label.indexOf(CFG.exclude[i]) >= 0) return true;
        }
        return false;
      }

      function textRank(label) {
        for (var i = 0; i < CFG.texts.length; i++) {
          if (label.indexOf(CFG.texts[i]) >= 0) return i;
        }
        return -1;
      }

      var candidates = [];
      var seen = [];

      /**
       * 재조회 버튼과 달리, selector 로 잡혔다는 이유만으로는 후보에 넣지 않는다.
       * 반드시 "예약하기" 계열 문구가 있어야 한다.
       * 좌석 칸 안의 아무 링크나 누르면 안 되기 때문이다.
       */
      function addCandidate(el, how, by) {
        if (!el || el.nodeType !== 1) return;
        if (seen.indexOf(el) >= 0) return;
        seen.push(el);

        var label = srtwLabelOf(el);
        if (excluded(label)) return;
        var rank = textRank(label);
        if (rank < 0) return;

        candidates.push({
          el: el,
          how: how,
          by: by,
          label: label,
          rank: rank,
          hidden: srtwVisible(el) ? 0 : 1
        });
      }

      for (var si = 0; si < CFG.selectors.length; si++) {
        var list;
        try { list = scope.querySelectorAll(CFG.selectors[si]); } catch (e) { continue; }
        for (var sj = 0; sj < list.length; sj++) addCandidate(list[sj], 'selector', CFG.selectors[si]);
      }
      var nodes = scope.querySelectorAll(
        'a, button, input[type=submit], input[type=button], input[type=image], [role=button]');
      for (var nk = 0; nk < nodes.length; nk++) addCandidate(nodes[nk], 'text', 'text');

      function cellSummary() {
        return (cell ? 'cell#' + CFG.cellIndex + '=' + srtwNorm(cell.innerText || '').slice(0, 20)
                     : 'cell=none') + ' tds=' + tds.length;
      }

      if (candidates.length === 0) {
        return {
          found: false,
          reason: 'BUTTON_NOT_FOUND',
          detail: cellSummary() + ' / row=' + rowText.slice(0, 60)
        };
      }

      // 좌석 칸을 특정하지 못한 채 행 전체에서 여러 개가 나왔다면 고르지 않는다.
      // 일반실/특실 버튼을 헷갈리면 원하지 않은 좌석을 잡게 된다.
      if (cell === null && candidates.length > 1) {
        var labels = [];
        for (var li = 0; li < candidates.length && li < 4; li++) labels.push(candidates[li].label);
        return {
          found: false,
          reason: 'BUTTON_AMBIGUOUS',
          detail: '좌석 칸을 특정하지 못했고 후보가 ' + candidates.length +
                  '개 (' + labels.join(', ') + ')'
        };
      }

      function better(a, b) {
        if (a.hidden !== b.hidden) return a.hidden < b.hidden;
        if (a.rank !== b.rank) return a.rank < b.rank;
        return a.how === 'selector' && b.how !== 'selector';
      }

      var found = candidates[0];
      for (var ci = 1; ci < candidates.length; ci++) {
        if (better(candidates[ci], found)) found = candidates[ci];
      }

      // ---------------------------------------------------------------- 4) 좌표

      var point = srtwTapPoint(found.el);
      var out = {
        found: true,
        label: found.label,
        tag: found.el.tagName,
        how: found.how,
        by: found.by,
        scope: cell ? 'cell' : 'row',
        cellIndex: CFG.cellIndex,
        rowIndex: CFG.rowIndex,
        candidates: candidates.length,
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

      srtwArmConfirm(found.el);
      out.tappable = true;
      out.x = point.x;
      out.y = point.y;
      out.at = point.at;
      return out;
    })();
    """.trimIndent()

    /**
     * 예약 결과 판별 스크립트. [예약하기] 를 누른 뒤에 실행한다.
     *
     * 본문에 실패 문구가 보이고, 그 화면이 예약 결과 URL 이거나 열차 목록 표가 없는
     * 화면일 때만 실패로 본다. 문구는 `innerText` 로만 읽으므로 `<script>` 안의
     * 안내 문자열(예: alert 메시지)에는 걸리지 않는다.
     */
    private val RESERVE_RESULT_TEMPLATE = """
    (function () {
      var CFG = /*__CONFIG__*/;
      /*__SIGNATURE__*/

      var body = '';
      try { body = srtwNorm(document.body ? (document.body.innerText || '') : ''); } catch (e) { body = ''; }

      var marker = '';
      for (var i = 0; i < CFG.markers.length; i++) {
        if (body.indexOf(srtwNorm(CFG.markers[i])) >= 0) { marker = CFG.markers[i]; break; }
      }

      var url = location.href || '';
      var reserveUrl = false;
      for (var j = 0; j < CFG.urlHints.length; j++) {
        if (url.indexOf(CFG.urlHints[j]) >= 0) { reserveUrl = true; break; }
      }

      // 열차 목록 표가 그대로 있으면 아직 목록 화면이다. (실패 화면이 아니다)
      var table = srtwResultTable();
      var rows = table ? table.querySelectorAll('tbody tr').length : 0;

      return {
        failed: marker !== '' && (reserveUrl || rows === 0),
        marker: marker,
        reserveUrl: reserveUrl,
        rows: rows,
        url: url,
        title: document.title || '',
        head: body.slice(0, 120)
      };
    })();
    """.trimIndent()

    /** 터치 직후 확인 스크립트. 진짜 클릭이 버튼까지 갔는지 알려준다. */
    private val TAP_CONFIRM_TEMPLATE = """
    (function () {
      var s = window.__srtTap;
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
     * 클릭 직전 준비 스크립트. 결과 영역에 MutationObserver 를 설치하고 카운터를 0 으로 되돌린다.
     * 화면이 전환되면 window 가 새로 만들어지므로 매 사이클 다시 설치한다.
     */
    private val OBSERVER_TEMPLATE = """
    (function () {
      /*__SIGNATURE__*/

      var state = window.__srtWatch;
      if (!state || state.doc !== document) {
        state = { doc: document, mut: 0, obs: null, sig: '' };
        window.__srtWatch = state;
      }
      if (state.obs) {
        try { state.obs.disconnect(); } catch (e) { /* 무시 */ }
        state.obs = null;
      }

      var table = srtwResultTable();
      var root = table ? (table.parentNode || table) : document.body;
      if (root && window.MutationObserver) {
        try {
          state.obs = new MutationObserver(function () { state.mut++; });
          state.obs.observe(root, { childList: true, subtree: true, characterData: true });
        } catch (e) { state.obs = null; }
      }

      state.mut = 0;
      state.sig = srtwSignature();
      return { sig: state.sig, observing: state.obs !== null };
    })();
    """.trimIndent()

    /** 클릭 이후 폴링 스크립트. DOM 이 갱신되었는지 알려준다. */
    private val PROBE_TEMPLATE = """
    (function () {
      /*__SIGNATURE__*/

      var state = window.__srtWatch;
      var sig = srtwSignature();
      if (!state || state.doc !== document) {
        // 화면이 전환되어 새 document 가 되었다.
        return { sig: sig, mut: -1, changed: true };
      }
      return { sig: sig, mut: state.mut, changed: sig !== state.sig };
    })();
    """.trimIndent()

    private val SCRIPT_TEMPLATE = """
    (function () {
      var CFG = /*__CONFIG__*/;
      /*__SIGNATURE__*/

      var warnings = [];

      function norm(s) {
        return (s || '').replace(/\s+/g, ' ').trim();
      }
      function textOf(el) {
        if (!el) return '';
        return norm(el.innerText || el.textContent || '');
      }
      function containsAny(text, list) {
        for (var i = 0; i < list.length; i++) {
          if (list[i] && text.indexOf(list[i]) >= 0) return true;
        }
        return false;
      }
      function seatStatus(text) {
        var t = (text || '').replace(/\s+/g, '');
        if (!t || /^-+$/.test(t)) return 'UNKNOWN';
        if (t.indexOf('매진') >= 0) return 'SOLD_OUT';
        if (t.indexOf('좌석없음') >= 0 || t.indexOf('잔여석없음') >= 0) return 'SOLD_OUT';
        if (t.indexOf('예약대기') >= 0 || t.indexOf('입석') >= 0) return 'WAITING';
        if (t.indexOf('예약하기') >= 0 || t.indexOf('좌석선택') >= 0) return 'AVAILABLE';
        if (t.indexOf('예매하기') >= 0 || t.indexOf('선택하기') >= 0) return 'AVAILABLE';
        if (t.indexOf('없음') >= 0) return 'SOLD_OUT';
        if (t.indexOf('있음') >= 0 || t.indexOf('가능') >= 0) return 'AVAILABLE';
        return 'UNKNOWN';
      }
      function firstTime(text) {
        var m = /([0-2]?\d):([0-5]\d)/.exec(text || '');
        return m ? m[0] : '';
      }
      function allTimes(text) {
        var re = /([0-2]?\d):([0-5]\d)/g;
        var out = [];
        var m;
        while ((m = re.exec(text || '')) !== null) out.push(m[0]);
        return out;
      }
      function stationOf(text) {
        return norm((text || '')
          .replace(/([0-2]?\d):([0-5]\d)/g, ' ')
          .replace(/[()\[\]]/g, ' ')
          .replace(/\d+/g, ' ')
          .replace(/(출발|도착|소요|시간|분)/g, ' '));
      }

      function headerCells(table) {
        var ths = table.querySelectorAll('thead th');
        if (ths.length === 0) ths = table.querySelectorAll('thead td');
        if (ths.length === 0) {
          var firstRow = table.querySelector('tr');
          if (firstRow) ths = firstRow.querySelectorAll('th');
        }
        return ths;
      }

      // 표 헤더 텍스트로 열 인덱스를 찾는다. colspan 을 고려한다.
      function buildColumnMap(table) {
        var map = {
          trainType: -1, departure: -1, arrival: -1,
          general: -1, firstClass: -1, waiting: -1, matched: 0
        };
        var ths = headerCells(table);
        var index = 0;
        for (var i = 0; i < ths.length; i++) {
          var t = textOf(ths[i]);
          var spanAttr = ths[i].getAttribute ? ths[i].getAttribute('colspan') : null;
          var span = parseInt(spanAttr || '1', 10);
          if (!span || span < 1) span = 1;

          if (map.waiting < 0 && containsAny(t, CFG.header.waiting)) {
            map.waiting = index; map.matched++;
          } else if (map.firstClass < 0 && containsAny(t, CFG.header.firstClass)) {
            map.firstClass = index; map.matched++;
          } else if (map.general < 0 && containsAny(t, CFG.header.general)) {
            map.general = index; map.matched++;
          } else if (map.arrival < 0 && containsAny(t, CFG.header.arrival)) {
            map.arrival = index; map.matched++;
          } else if (map.departure < 0 && containsAny(t, CFG.header.departure)) {
            map.departure = index; map.matched++;
          } else if (map.trainType < 0 && containsAny(t, CFG.header.trainType)) {
            map.trainType = index; map.matched++;
          }
          index += span;
        }
        return map;
      }

      function candidateTables() {
        var seen = [];
        var out = [];
        for (var i = 0; i < CFG.tables.length; i++) {
          var found;
          try { found = document.querySelectorAll(CFG.tables[i]); } catch (e) { continue; }
          for (var j = 0; j < found.length; j++) {
            var t = found[j];
            if (t.tagName !== 'TABLE') t = t.closest ? t.closest('table') : t;
            if (!t || t.tagName !== 'TABLE') continue;
            if (seen.indexOf(t) >= 0) continue;
            seen.push(t);
            out.push(t);
          }
        }
        return out;
      }

      function rowsOf(table) {
        for (var i = 0; i < CFG.rows.length; i++) {
          var found;
          try { found = table.querySelectorAll(CFG.rows[i]); } catch (e) { continue; }
          if (found.length > 0) return found;
        }
        return [];
      }

      function timeRichRowCount(table) {
        var rows = rowsOf(table);
        var count = 0;
        for (var i = 0; i < rows.length; i++) {
          if (allTimes(textOf(rows[i])).length >= 2) count++;
        }
        return count;
      }

      // 좌석 열을 가진 표를 우선 선택하고, 없으면 시각이 2개 이상인 행이 가장 많은 표를 쓴다.
      function pickTable() {
        var tables = candidateTables();
        var best = null;
        var bestMap = null;
        for (var i = 0; i < tables.length; i++) {
          var map = buildColumnMap(tables[i]);
          if (map.general >= 0 || map.firstClass >= 0) {
            return { table: tables[i], map: map, heuristic: false };
          }
          var score = timeRichRowCount(tables[i]);
          if (score > 0 && (best === null || score > best.score)) {
            best = { table: tables[i], score: score };
            bestMap = map;
          }
        }
        if (best !== null) {
          warnings.push('표 헤더에서 좌석 열을 찾지 못해 행 텍스트 휴리스틱으로 분석했습니다.');
          return { table: best.table, map: bestMap, heuristic: true };
        }
        return null;
      }

      function inRange(index, length) {
        return index >= 0 && index < length;
      }

      function cellTextAt(cells, index) {
        if (!inRange(index, cells.length)) return '';
        return cells[index];
      }

      // 헤더에서 좌석 열을 못 찾은 경우의 근사 판정.
      // 1) '특실' / '일반' 문구가 들어있는 칸을 우선 사용한다.
      // 2) 없으면 좌석 상태로 읽히는 칸을 왼쪽부터 모아 [특실, 일반실] 순으로 본다.
      //    (SRT 조회 결과 표는 왼쪽이 특실, 그 오른쪽이 일반실이다)
      function guessSeatTexts(cellText) {
        var general = '';
        var first = '';
        var generalIndex = -1;
        var firstIndex = -1;
        for (var i = 0; i < cellText.length; i++) {
          var t = cellText[i];
          if (!t) continue;
          if (!first && t.indexOf('특실') >= 0) { first = t; firstIndex = i; }
          if (!general && t.indexOf('일반') >= 0) { general = t; generalIndex = i; }
        }
        if (general && first) {
          return {
            general: general, firstClass: first,
            generalIndex: generalIndex, firstClassIndex: firstIndex
          };
        }

        // 아직 모르는 쪽은 좌석 상태로 읽히는 칸으로 채운다.
        // 한쪽을 이미 찾았다면 '특실이 왼쪽' 이라는 순서를 지키는 칸만 고른다.
        var seatLike = [];
        for (var k = 0; k < cellText.length; k++) {
          if (k === generalIndex || k === firstIndex) continue;
          if (seatStatus(cellText[k]) !== 'UNKNOWN') seatLike.push(k);
        }
        if (!first && !general) {
          if (seatLike.length > 0) {
            firstIndex = seatLike[0];
            first = cellText[firstIndex];
          }
          if (seatLike.length > 1) {
            generalIndex = seatLike[1];
            general = cellText[generalIndex];
          }
        } else if (!general) {
          // 특실만 알면 그 오른쪽에서 일반실을 찾는다.
          for (var g = 0; g < seatLike.length; g++) {
            if (seatLike[g] > firstIndex) {
              generalIndex = seatLike[g];
              general = cellText[generalIndex];
              break;
            }
          }
        } else if (!first) {
          // 일반실만 알면 그 왼쪽에서 특실을 찾는다.
          for (var f = seatLike.length - 1; f >= 0; f--) {
            if (seatLike[f] < generalIndex) {
              firstIndex = seatLike[f];
              first = cellText[firstIndex];
              break;
            }
          }
        }
        return {
          general: general, firstClass: first,
          generalIndex: generalIndex, firstClassIndex: firstIndex
        };
      }

      function parseRow(row, map, heuristic) {
        var cellNodes = row.querySelectorAll('td');
        if (cellNodes.length < 2) return null;

        var cellText = [];
        for (var i = 0; i < cellNodes.length; i++) cellText.push(textOf(cellNodes[i]));
        var rowText = cellText.join(' | ');
        if (rowText.length === 0) return null;

        var depCell = cellTextAt(cellText, map.departure);
        var arrCell = cellTextAt(cellText, map.arrival);
        var depTime = firstTime(depCell);
        var arrTime = firstTime(arrCell);

        // 출발/도착이 한 칸에 합쳐진 표(colspan)라면 같은 시각을 두 번 읽게 되므로
        // 도착 시각은 행 전체 텍스트에서 다시 찾는다.
        if (map.arrival >= 0 && map.arrival === map.departure) {
          arrTime = '';
          arrCell = '';
        }

        if (!depTime || !arrTime) {
          var times = allTimes(rowText);
          if (times.length >= 2) {
            if (!depTime) depTime = times[0];
            if (!arrTime) arrTime = times[1];
          }
        }
        if (!depTime || !arrTime) return null;

        var typeCell = cellTextAt(cellText, map.trainType);
        var numberSource = typeCell || rowText;
        var m = /SRT\s*#?\s*(\d{1,4})/.exec(numberSource);
        if (!m) m = /SRT\s*#?\s*(\d{1,4})/.exec(rowText);
        var trainNumber = m ? ('SRT ' + m[1]) : '';
        if (!trainNumber) {
          var m2 = /(\d{2,4})\s*호/.exec(rowText);
          if (m2) trainNumber = m2[1];
        }

        // 좌석 텍스트뿐 아니라 **그 텍스트가 몇 번째 칸에서 나왔는지**도 기록한다.
        // 나중에 [예약하기] 를 누를 때, 그 칸 안에서만 버튼을 찾기 위해서다.
        // (표 전체에서 찾으면 다른 열차의 버튼을 누를 위험이 있다)
        var generalIndex = inRange(map.general, cellText.length) ? map.general : -1;
        var firstIndex = inRange(map.firstClass, cellText.length) ? map.firstClass : -1;
        var generalText = cellTextAt(cellText, map.general);
        var firstText = cellTextAt(cellText, map.firstClass);
        if (heuristic || (!generalText && !firstText)) {
          var guessed = guessSeatTexts(cellText);
          if (!generalText) {
            generalText = guessed.general;
            generalIndex = guessed.generalIndex;
          }
          if (!firstText) {
            firstText = guessed.firstClass;
            firstIndex = guessed.firstClassIndex;
          }
        }

        var generalStatus = seatStatus(generalText);
        var firstStatus = seatStatus(firstText);

        // 예약대기 열이 따로 있고 신청이 가능한 상태라면, 매진인 일반실을 WAITING 으로 본다.
        // (SRT 의 예약대기는 일반실 기준으로 표시되는 경우가 많다 - 근사 판정)
        var waitingText = cellTextAt(cellText, map.waiting);
        if (waitingText && generalStatus === 'SOLD_OUT') {
          var w = waitingText.replace(/\s+/g, '');
          if (w.indexOf('신청') >= 0 || w.indexOf('예약대기') >= 0) generalStatus = 'WAITING';
        }

        return {
          trainNumber: trainNumber,
          trainType: typeCell,
          departureStation: stationOf(depCell),
          arrivalStation: stationOf(arrCell),
          departureTime: depTime,
          arrivalTime: arrTime,
          generalSeatText: generalText,
          generalSeatStatus: generalStatus,
          firstClassSeatText: firstText,
          firstClassSeatStatus: firstStatus,
          waitingText: waitingText,
          // 예약하기를 누를 때 "이 행이 그때 그 행인지" 확인하는 데 쓴다.
          rowKey: srtwRowKey(row),
          generalCellIndex: generalIndex,
          firstClassCellIndex: firstIndex
        };
      }

      // 조회 폼이 들고 있는 출발일. 화면에 "무엇을 감시 중인지" 보여주기 위해서만 쓴다.
      // 읽기만 하고 값을 고치지 않는다. 못 찾으면 빈 문자열을 돌려준다.
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
          var els;
          try {
            els = document.getElementsByName(CFG.dateFields[i]);
          } catch (e) { continue; }
          for (var j = 0; j < els.length; j++) {
            var got = normDate(els[j].value);
            if (got) return got;
          }
        }
        return '';
      }

      function urlHasAny(hints) {
        var href = location.href;
        for (var i = 0; i < hints.length; i++) {
          if (href.indexOf(hints[i]) >= 0) return true;
        }
        return false;
      }

      function looksLikeLogin() {
        for (var i = 0; i < CFG.loginMarkers.length; i++) {
          try {
            if (document.querySelector(CFG.loginMarkers[i])) return true;
          } catch (e) { /* 잘못된 selector 무시 */ }
        }
        return urlHasAny(CFG.loginHints);
      }

      var bodyText = '';
      try { bodyText = textOf(document.body).slice(0, 3000); } catch (e) { bodyText = ''; }

      function looksLikeSessionExpired() {
        return /세션[^가-힣]{0,4}(만료|종료)/.test(bodyText) ||
               /다시\s?로그인/.test(bodyText) ||
               /로그인\s?후\s?이용/.test(bodyText);
      }

      // 차단 / 비정상 접근 안내 페이지. 감시를 즉시 멈추기 위한 판정이다.
      function looksBlocked() {
        return containsAny(bodyText, CFG.blockedMarkers);
      }

      var picked = pickTable();
      var trains = [];
      var rowCount = 0;

      if (picked !== null) {
        var rows = rowsOf(picked.table);
        rowCount = rows.length;
        for (var r = 0; r < rows.length; r++) {
          var parsed = null;
          try {
            parsed = parseRow(rows[r], picked.map, picked.heuristic);
          } catch (e) {
            warnings.push('행 분석 실패: ' + (e && e.message ? e.message : 'unknown'));
          }
          if (parsed) {
            parsed.rowIndex = r;
            trains.push(parsed);
          }
        }
      }

      var searchDate = '';
      try { searchDate = searchDateOf(); } catch (e) { searchDate = ''; }

      var status;
      // 차단 판정을 가장 먼저 본다. 목록이 남아 있어도 차단되었으면 멈춰야 한다.
      if (looksBlocked()) {
        status = 'BLOCKED';
      } else if (trains.length > 0) {
        status = 'TRAIN_LIST';
      } else if (looksLikeSessionExpired()) {
        status = 'SESSION_EXPIRED';
      } else if (looksLikeLogin()) {
        status = 'LOGIN_REQUIRED';
      } else if (urlHasAny(CFG.scheduleHints)) {
        status = picked === null ? 'UNKNOWN_PAGE' : 'NO_TRAIN';
      } else {
        status = 'UNKNOWN_PAGE';
      }

      return {
        status: status,
        url: location.href,
        title: document.title || '',
        rowCount: rowCount,
        searchDate: searchDate,
        trains: trains,
        warnings: warnings
      };
    })();
    """.trimIndent()
}
