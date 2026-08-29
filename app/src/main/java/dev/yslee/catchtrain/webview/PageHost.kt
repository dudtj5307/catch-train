package dev.yslee.catchtrain.webview

/**
 * 페이지 갱신 결과. (DESIGN.md §10)
 *
 * 갱신은 **결과 화면의 [열차조회] 버튼을 사용자가 누르는 것과 같은 방식으로**만 한다.
 * 즉 버튼이 화면에 그려진 위치에 진짜 터치([android.view.MotionEvent])를 내려보낸다.
 *
 * 쓰지 않는 방법과 그 이유:
 *  - `WebView.reload()` : 조회 결과는 같은 결과를 보장하지 않고, 사람이 조작할 때는
 *    나오지 않는 요청 패턴이라 자동화로 판단되기 쉽다.
 *  - `loadUrl` 로 조회 URL 직접 호출 : 이 경로는 사실상 항상 차단된다.
 *  - JS `el.click()` / `dispatchEvent` : isTrusted=false 인 합성 이벤트라 구분된다.
 *
 * 그래서 버튼을 못 찾거나([ButtonNotFound]) 화면에서 누를 수 없으면([NotTappable])
 * 다른 방법으로 대체하지 않고 그대로 실패를 올린다.
 */
sealed interface PageOutcome {

    /** 로그에 남길 상세 정보. */
    val detail: String

    /**
     * 클릭이 화면 전환으로 이어져 onPageFinished 를 받았다.
     *
     * 코레일 조회는 AJAX 라 여기로 오지 않는다. (§38-5) 예매 이후 화면에서만 볼 수 있다.
     */
    data class Finished(val url: String?) : PageOutcome {
        override val detail: String get() = url ?: ""
    }

    /** 화면 전환 없이 결과 영역 DOM 이 갱신되었다. (AJAX 재조회 — 코레일의 정상 경로) */
    data class Updated(override val detail: String) : PageOutcome

    /**
     * 클릭은 했지만 정해진 시간 안에 화면 전환도 DOM 변경도 관찰되지 않았다.
     * 좌석 상태가 실제로 그대로여서 응답이 같은 경우도 있으므로,
     * 곧바로 실패로 보지 않고 DOM 분석을 한 번 시도한다.
     */
    data class Settled(override val detail: String) : PageOutcome

    /**
     * [열차조회] 버튼을 찾지 못했다.
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
     * [열차조회] 가 그대로 눌린다. 사용자가 조건을 고르는 중에 조회가 나가면
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
 * 누를 좌석 칸을 가리키는 좌표. (DESIGN.md §19, §38-6)
 *
 * "목록의 몇 번째" 같은 위치만으로는 부족하다. 분석한 순간과 누르는 순간 사이에
 * 목록이 갱신되었다면 엉뚱한 열차를 예매하게 되기 때문이다. 그래서 위치([rowIndex])는
 * 참고용 힌트일 뿐이고, 실제 판단 기준은 그 편성의 내용을 요약한 [rowKey] 다.
 * [rowKey] 가 일치하는 편성이 정확히 하나가 아니면 아무것도 누르지 않는다.
 *
 * 1단계와 2단계가 **같은 값을 공유한다.** 2단계에서도 이 값으로 편성을 다시 찾아
 * "내가 고른 그 칸에 선택 표시가 붙었는지" 확인한다. (§38-6-1)
 *
 * @param rowKey 분석 시점에 읽은 편성 내용의 요약값 (KtxParserScript 의 `ktxRowKey`)
 * @param rowIndex 목록 안에서의 위치. 진단 로그용이다.
 * @param cellIndex 좌석 등급에 해당하는 `price_box` 의 순번. `[0]=일반실, [1]=특실` (§38-3)
 *                  -1 이면 칸을 특정하지 못했다는 뜻이고, 이 경우 아무것도 누르지 않는다.
 * @param trainNumber 확인용 열차 번호. **식별 주키다.** (§38-4)
 * @param departureTime 확인용 출발 시각 ("18:30")
 * @param seatLabel 좌석 등급 이름 ("일반실"). 2단계에서 하단 바의 문구와 대조한다.
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
 * **예매 1단계** — 좌석 칸을 고른 결과. (DESIGN.md §38-6)
 *
 * 코레일은 좌석 칸(`price_box`)을 눌러 `active` 를 붙인 뒤, 화면 하단에 나타나는
 * 예매 바에서 한 번 더 눌러야 한다. 이 결과는 그 **첫 번째** 누름에 대한 것이다.
 *
 * [Selected] 가 아니면 2단계로 넘어가지 않는다. 무엇을 골랐는지 확신하지 못한 채
 * 예매 버튼을 누르면 원하지 않은 좌석을 잡게 된다.
 */
sealed interface SeatSelectOutcome {

    /** 로그에 남길 상세 정보. */
    val detail: String

    /** 눌렀고 그 칸에 선택 표시(`active`)가 붙은 것을 확인했다. */
    data class Selected(override val detail: String) : SeatSelectOutcome

    /**
     * 눌렀지만 선택 표시가 붙지 않았다. (또는 여러 칸에 붙어 있어 구분할 수 없다)
     *
     * **요청이 나갔을 수 있으므로 그 자리에서 다시 누르지 않는다.** (대원칙 2)
     */
    data class NotSelected(override val detail: String) : SeatSelectOutcome

    /** 그 편성을 화면에서 다시 찾지 못했다. (목록이 갱신되었을 수 있다) */
    data class RowNotFound(override val detail: String) : SeatSelectOutcome

    /**
     * 편성은 찾았지만 그 좌석 칸을 누를 수 없었다.
     * 칸을 특정하지 못했거나, 이미 매진/예약대기로 바뀌어 있는 경우다.
     */
    data class CellNotFound(override val detail: String) : SeatSelectOutcome

    /** 칸은 찾았지만 화면에서 누를 수 없었다. (가려짐 / 화면 밖 / WebView 안 보임) */
    data class NotTappable(override val detail: String) : SeatSelectOutcome

    /** 네트워크/페이지 오류 */
    data class Failed(val code: Int, val description: String) : SeatSelectOutcome {
        override val detail: String get() = "$code $description"
    }
}

/**
 * **예매 2단계** — 하단 바의 [예매] 를 누른 결과. (DESIGN.md §38-6-1)
 *
 * 재조회와 마찬가지로 화면에 그려진 버튼을 진짜 터치로 누른다.
 * 버튼을 확실하게 특정하지 못하면 **다른 방법으로 대체하지 않고 그대로 실패를 올린다.**
 * 잘못된 좌석을 잡는 것보다 안 누르는 편이 낫다.
 *
 * [NotAllowed] 와 [Mismatch] 는 오류가 아니라 **일부러 멈춘 것**이다.
 * 1단계까지는 되어 있으므로 사용자가 화면에서 직접 이어서 누르면 된다. (대원칙 3)
 */
sealed interface ReserveOutcome {

    /** 로그에 남길 상세 정보. */
    val detail: String

    /** 버튼을 눌렀고 화면이 반응했다. (좌석 선택 / 결제 화면) */
    data class Clicked(override val detail: String) : ReserveOutcome

    /**
     * 눌렀지만 예약 화면 대신 **"잔여석없음" 안내**가 떴다. (DESIGN.md §19-2)
     *
     * 화면에 좌석이 열려 있는 것을 보고 눌러도, 그사이 다른 사람이 먼저 잡으면
     * 이 화면이 뜬다. 드문 일이 아니라 취소표를 노릴 때는 오히려 흔하다.
     *
     * 오류가 아니다. 이 화면에는 [열차조회] 버튼이 없어 감시를 이어갈 수 없으므로,
     * [PageHost.dismissReserveResult] 로 목록 화면까지 되돌린 뒤 감시를 계속한다.
     */
    data class SoldOut(override val detail: String) : ReserveOutcome

    /**
     * 눌렀지만 정해진 시간 안에 화면 전환도 DOM 변경도 관찰되지 않았다.
     * 누르는 사이에 좌석이 나갔거나 사이트가 응답하지 않는 경우다.
     */
    data class NoChange(override val detail: String) : ReserveOutcome

    /**
     * 버튼은 있지만 **누를 수 있는 문구가 아니었다.** (§38-6-1)
     *
     * `예약대기신청` 과 `입석+좌석 예매` 가 여기로 온다. 예약대기는 발견으로 보지
     * 않기로 했고(§18), 사용자가 체크한 것은 좌석이지 입석이 아니다.
     * 실패가 아니라 **사람에게 넘긴 것**이다.
     */
    data class NotAllowed(override val detail: String) : ReserveOutcome

    /**
     * 누르기 전 확인이 어긋났다. (§38-6-1 의 확인 1·2)
     *
     * 고른 칸에 선택 표시가 없거나, 하단 바에 뜬 등급이 고른 등급과 다르다.
     * 이 상태로 누르면 원하지 않은 좌석을 잡는다.
     */
    data class Mismatch(override val detail: String) : ReserveOutcome

    /** 하단 예매 바나 그 안의 버튼을 찾지 못했다. */
    data class ButtonNotFound(override val detail: String) : ReserveOutcome

    /** 버튼은 찾았지만 화면에서 누를 수 없었다. (가려짐 / 화면 밖) */
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
     * [열차조회] 버튼을 **직접 눌러** 같은 조건으로 재조회하고, 페이지가 정착할 때까지 기다린다.
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
     * **예매 1단계.** 조건을 만족한 그 열차의 그 좌석 칸을 눌러 고른다. (§38-6)
     *
     * 재조회와 완전히 같은 방식이다. 칸의 화면 좌표를 찾아 그 자리에 진짜 터치를
     * 내려보낸다. JS 클릭이나 URL 직접 호출은 쓰지 않는다.
     *
     * 누르는 대상은 [target] 이 가리키는 **하나의 칸**뿐이다.
     * 그 칸을 확실히 특정하지 못하면 아무것도 누르지 않고 실패를 돌려준다.
     * 누른 뒤에는 그 칸에 선택 표시가 붙었는지 **읽어서 확인**한다.
     *
     * 이 단계는 화면 전환을 만들지 않으므로 확인은 [settleTimeoutMs] 안에서 끝난다.
     * [timeoutMs] 는 2단계와 계약을 맞추기 위해 받아 두는 값이다.
     */
    suspend fun selectSeat(
        target: ReserveTarget,
        timeoutMs: Long,
        settleTimeoutMs: Long,
        onClick: (String) -> Unit = {},
    ): SeatSelectOutcome

    /**
     * **예매 2단계.** 화면 하단 예매 바의 [예매] 를 누른다. (§38-6-1)
     *
     * [selectSeat] 가 [SeatSelectOutcome.Selected] 를 돌려준 다음에만 부른다.
     * 누르기 전에 선택 표시·등급 문구·버튼 문구를 다시 확인하고,
     * 하나라도 어긋나면 누르지 않는다. 허용목록에 없는 버튼도 누르지 않는다.
     */
    suspend fun confirmReserve(
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
     *
     * **코레일은 SPA 라 뒤로 가기 한 칸이 조회 결과 화면에 대응한다는 보장이 없다.**
     * (§38-8 미확인) 그래서 돌아간 뒤 목록이 실제로 보이는지 확인하고,
     * 보이지 않으면 성공으로 치지 않는다. 그 화면에서 다른 것을 더 누르지도 않는다.
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
