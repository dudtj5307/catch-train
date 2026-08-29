package com.example.srtwatcher.webview

/**
 * 페이지 갱신 결과. (DESIGN.md §10)
 *
 * 갱신은 **결과 페이지의 "조회하기" 버튼을 사용자가 누르는 것과 같은 방식으로**만 한다.
 * 즉 버튼이 화면에 그려진 위치에 진짜 터치([android.view.MotionEvent])를 내려보낸다.
 *
 * 쓰지 않는 방법과 그 이유:
 *  - `WebView.reload()` : 조회 결과는 POST 결과 화면이라 같은 결과를 보장하지 않고,
 *    사람이 조작할 때는 나오지 않는 요청 패턴이라 자동화로 판단되기 쉽다.
 *  - `loadUrl` 로 조회 URL 직접 호출 : 이 경로는 사실상 항상 차단된다.
 *  - JS `el.click()` / `dispatchEvent` : isTrusted=false 인 합성 이벤트라 구분된다.
 *
 * 그래서 버튼을 못 찾거나([ButtonNotFound]) 화면에서 누를 수 없으면([NotTappable])
 * 다른 방법으로 대체하지 않고 그대로 실패를 올린다.
 */
sealed interface PageOutcome {

    /** 로그에 남길 상세 정보. */
    val detail: String

    /** 클릭이 화면 전환(form submit)으로 이어져 onPageFinished 를 받았다. */
    data class Finished(val url: String?) : PageOutcome {
        override val detail: String get() = url ?: ""
    }

    /** 화면 전환 없이 결과 영역 DOM 이 갱신되었다. (AJAX 재조회) */
    data class Updated(override val detail: String) : PageOutcome

    /**
     * 클릭은 했지만 정해진 시간 안에 화면 전환도 DOM 변경도 관찰되지 않았다.
     * 좌석 상태가 실제로 그대로여서 응답이 같은 경우도 있으므로,
     * 곧바로 실패로 보지 않고 DOM 분석을 한 번 시도한다.
     */
    data class Settled(override val detail: String) : PageOutcome

    /**
     * "조회하기" 버튼을 찾지 못했다.
     * 조회 결과 화면이 아니거나 사이트 마크업이 바뀐 경우다.
     */
    data class ButtonNotFound(override val detail: String) : PageOutcome

    /**
     * 버튼은 찾았지만 **화면에서 누를 수 없었다.**
     * 버튼이 WebView 영역 밖으로 밀려났거나, 고정 배너/레이어가 덮고 있거나,
     * WebView 자체가 화면에 없는 경우다.
     *
     * 조회 요청이 나가지 않은 상태이므로 차단 위험은 없다.
     * 사용자가 화면을 조금만 정리하면 해결되는 경우가 많아 재시도할 수 있다.
     */
    data class NotTappable(override val detail: String) : PageOutcome

    /**
     * 누를 수 있었지만 **일부러 누르지 않았다.**
     *
     * 지금은 팝업 창(달력 등)이 열려 있는 경우다. 조회는 WebView 위젯 좌표에
     * MotionEvent 를 직접 내려보내는 방식이라 팝업이 화면을 덮고 있어도 뒤쪽
     * [조회하기] 가 그대로 눌린다. 사용자가 조건을 고르는 중에 조회가 나가면
     * 요청만 늘고 팝업이 잡고 있던 폼이 사라질 수 있어 이번 차례를 건너뛴다.
     *
     * 오류가 아니다. 감시를 멈추지도, 연속 오류로 세지도 않는다.
     */
    data class Deferred(override val detail: String) : PageOutcome

    /** 네트워크/페이지 오류 */
    data class Failed(val code: Int, val description: String) : PageOutcome {
        override val detail: String get() = "$code $description"
    }
}

/**
 * 누를 [예약하기] 버튼을 가리키는 좌표. (DESIGN.md §19)
 *
 * "표의 몇 번째 행" 같은 위치만으로는 부족하다. 분석한 순간과 누르는 순간 사이에
 * 표가 바뀌었다면 엉뚱한 열차를 예약하게 되기 때문이다. 그래서 위치([rowIndex])는
 * 참고용 힌트일 뿐이고, 실제 판단 기준은 그 행의 내용을 요약한 [rowKey] 다.
 * [rowKey] 가 일치하는 행이 정확히 하나가 아니면 아무것도 누르지 않는다.
 *
 * @param rowKey 분석 시점에 읽은 행 내용의 요약값 (SrtParserScript.srtwRowKey)
 * @param rowIndex 표 안에서의 행 위치. 진단 로그용이다.
 * @param cellIndex 좌석 등급에 해당하는 칸의 위치. -1 이면 칸을 특정하지 못했다는 뜻이고,
 *                  이 경우 행 전체에서 찾되 후보가 둘 이상이면 누르지 않는다.
 * @param trainNumber 확인용 열차 번호
 * @param departureTime 확인용 출발 시각 ("18:30")
 * @param seatLabel 로그에 남길 좌석 등급 이름 ("일반실")
 */
data class ReserveTarget(
    val rowKey: String,
    val rowIndex: Int,
    val cellIndex: Int,
    val trainNumber: String,
    val departureTime: String,
    val seatLabel: String,
)

/**
 * [예약하기] 클릭 결과.
 *
 * 재조회와 마찬가지로 화면에 그려진 버튼을 진짜 터치로 누른다.
 * 버튼을 확실하게 특정하지 못하면 **다른 방법으로 대체하지 않고 그대로 실패를 올린다.**
 * 잘못된 좌석을 잡는 것보다 안 누르는 편이 낫다.
 */
sealed interface ReserveOutcome {

    /** 로그에 남길 상세 정보. */
    val detail: String

    /** 버튼을 눌렀고 화면이 넘어갔다. (좌석 선택 / 결제 화면) */
    data class Clicked(override val detail: String) : ReserveOutcome

    /**
     * 눌렀지만 예약 화면 대신 **"잔여석없음" 안내 화면**이 떴다. (DESIGN.md §19-2)
     *
     * 화면에 좌석이 열려 있는 것을 보고 눌러도, 그사이 다른 사람이 먼저 잡으면
     * 이 화면이 뜬다. 드문 일이 아니라 취소표를 노릴 때는 오히려 흔하다.
     *
     * 오류가 아니다. 이 화면에는 [조회하기] 버튼이 없어 감시를 이어갈 수 없으므로,
     * [PageHost.dismissReserveResult] 로 목록 화면까지 되돌린 뒤 감시를 계속한다.
     */
    data class SoldOut(override val detail: String) : ReserveOutcome

    /**
     * 눌렀지만 정해진 시간 안에 화면 전환도 DOM 변경도 관찰되지 않았다.
     * 누르는 사이에 좌석이 나갔거나 사이트가 응답하지 않는 경우다.
     */
    data class NoChange(override val detail: String) : ReserveOutcome

    /** 그 열차의 행을 화면에서 다시 찾지 못했다. (표가 갱신되었을 수 있다) */
    data class RowNotFound(override val detail: String) : ReserveOutcome

    /** 행은 찾았지만 그 좌석 칸에 [예약하기] 버튼이 없었다. */
    data class ButtonNotFound(override val detail: String) : ReserveOutcome

    /** 버튼은 찾았지만 화면에서 누를 수 없었다. (가려짐 / 화면 밖 / WebView 안 보임) */
    data class NotTappable(override val detail: String) : ReserveOutcome

    /** 네트워크/페이지 오류 */
    data class Failed(val code: Int, val description: String) : ReserveOutcome {
        override val detail: String get() = "$code $description"
    }
}

/**
 * 감시 엔진이 바라보는 페이지 인터페이스. (DESIGN.md §34-1)
 *
 * WatchController 는 WebView 를 직접 알지 못하고 이 인터페이스만 사용한다.
 * 덕분에 감시 로직을 WebView 없이 테스트할 수 있다.
 */
interface PageHost {

    val currentUrl: String?

    /** 시작 URL 로 이동 */
    suspend fun loadStartUrl()

    /**
     * "조회하기" 버튼을 **직접 눌러** 같은 조건으로 재조회하고, 페이지가 정착할 때까지 기다린다.
     *
     * @param timeoutMs 화면 전환이 시작된 경우 onPageFinished 를 기다리는 최대 시간
     * @param settleTimeoutMs 화면 전환 없이(AJAX) DOM 변경을 기다리는 최대 시간
     * @param onClick 터치가 실제로 발생한 직후 호출된다. 어디를 어떻게 눌렀는지 담긴다.
     */
    suspend fun requery(
        timeoutMs: Long,
        settleTimeoutMs: Long,
        onClick: (String) -> Unit = {},
    ): PageOutcome

    /**
     * 조건을 만족한 그 열차의 **[예약하기] 버튼을 직접 눌러** 예약 화면으로 넘어간다.
     *
     * 재조회와 완전히 같은 방식이다. 버튼의 화면 좌표를 찾아 그 자리에 진짜 터치를
     * 내려보낸다. JS 클릭이나 URL 직접 호출은 쓰지 않는다.
     *
     * 누르는 대상은 [target] 이 가리키는 **하나의 버튼**뿐이다.
     * 그 버튼을 확실히 특정하지 못하면 아무것도 누르지 않고 실패를 돌려준다.
     *
     * @param timeoutMs 클릭 후 화면 전환을 기다리는 최대 시간
     * @param settleTimeoutMs 화면 전환 없이 DOM 변경을 기다리는 최대 시간
     * @param onClick 터치가 실제로 발생한 직후 호출된다.
     */
    suspend fun clickReserve(
        target: ReserveTarget,
        timeoutMs: Long,
        settleTimeoutMs: Long,
        onClick: (String) -> Unit = {},
    ): ReserveOutcome

    /**
     * 예약 실패 안내 화면([ReserveOutcome.SoldOut])에서 **열차 목록 화면으로 되돌아간다.**
     * (DESIGN.md §19-2)
     *
     * 되돌리는 방법은 **뒤로 가기 하나뿐**이다. 화면의 [확인] 버튼은 누르지 않는다.
     * 그 버튼은 조회 폼을 새로 여는 링크라 사용자가 넣어 둔 조회 조건이 초기화되고,
     * 조건이 사라진 채로 감시를 이어가면 엉뚱한 조회 결과를 보게 된다.
     * 뒤로 가기는 조회 결과 화면(그 조건 그대로)으로 돌아간다.
     *
     * 실패해도 그 화면에서 다른 것을 더 누르지 않는다.
     *
     * @param timeoutMs 화면 전환을 기다리는 최대 시간
     * @param settleTimeoutMs 화면 전환 없이 DOM 변경을 기다리는 최대 시간
     * @param onClick 되돌리기 동작이 실제로 일어난 직후 호출된다.
     */
    suspend fun dismissReserveResult(
        timeoutMs: Long,
        settleTimeoutMs: Long,
        onClick: (String) -> Unit = {},
    ): PageOutcome

    /** 스크립트를 실행하고 결과 문자열을 돌려준다. */
    suspend fun evaluate(script: String): String?
}
