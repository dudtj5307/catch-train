package dev.yslee.catchtrain.webview

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 실제 WebView 를 [PageHost] 로 감싼다. (DESIGN.md §10, §14, §38)
 *
 * - WebViewClient 를 설치하여 onPageStarted / onPageFinished / onReceivedError 를 수집한다.
 * - 갱신은 항상 **화면에 보이는 [열차조회] 버튼을 직접 누르는 것**으로만 한다.
 *   [WebView.reload] 도, 조회 URL 직접 호출도 하지 않는다.
 * - 예매도 똑같은 방식이다. 다만 **두 번 눌러야 한다.** (§38-6)
 *   [selectSeat] 로 좌석 칸을 고르고, [confirmReserve] 로 하단 바의 [예매] 를 누른다.
 * - JavaScript 의 setInterval 은 사용하지 않는다. (§34-3)
 * - WebView API 는 모두 메인 스레드에서 호출한다.
 *
 * 클릭 방식이 핵심이다. 한 번의 누름은 이렇게 진행된다.
 *  1) [KtxParserScript] 의 탐색 스크립트로 **화면 좌표**만 알아낸다. (누르지 않는다)
 *  2) 그 좌표에 [MotionEvent] 를 내려보낸다. 사용자가 손가락으로 누른 것과 같은 입력이다.
 *  3) [KtxParserScript.buildTapConfirmScript] 로 클릭이 목표까지 갔는지 확인한다.
 *
 * JS 의 `el.click()` / `dispatchEvent` 를 쓰지 않는 이유는 isTrusted=false 인 합성
 * 이벤트이기 때문이고, `a[href]` 나 URL 직접 호출을 쓰지 않는 이유는 그 경로가
 * 사실상 항상 차단되기 때문이다.
 *
 * 클릭 후 "정착"은 두 경로로 감지한다.
 *  1) 화면이 전환되면 onPageFinished
 *  2) 화면 전환 없이 목록만 바뀌면(AJAX) MutationObserver / 목록 서명 변화
 *
 * **코레일 조회는 `<form>` 이 없는 AJAX 라 1) 이 오지 않는다.** (§38-5)
 * 정상 경로는 언제나 2) 이고, 그래서 서명을 뜨는 대상이 정확해야 한다.
 * ([KtxSelectors.SIGNATURE_SCOPES] — 머리말·광고까지 넣으면 좌석과 무관한 변화에 반응한다)
 */
class KtxWebViewHost(
    private val webView: WebView,
    private val startUrl: String = KtxSelectors.START_URL,
    /**
     * 팝업 창이 열려 있는지. ([KtxPopupHost.isOpen])
     *
     * 탭은 뷰 계층을 거치지 않고 이 WebView 에 직접 들어가므로, 팝업이 화면을
     * 덮고 있어도 버튼은 눌린다. 사용자가 팝업을 보고 있는 동안에는 누르지 않는다.
     */
    private val isPopupOpen: () -> Boolean = { false },
) : PageHost {

    /**
     * 페이지 정착 이벤트 큐. CONFLATED 이므로 요청 직전에 비워두면
     * "비우기 → 트리거 → 수신" 사이에 도착한 이벤트도 놓치지 않는다.
     */
    private val outcomes = Channel<PageOutcome>(Channel.CONFLATED)

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _pageUrl = MutableStateFlow<String?>(null)
    val pageUrl: StateFlow<String?> = _pageUrl.asStateFlow()

    /**
     * 이번 사이클에서 화면 전환이 시작되었는지. onPageStarted(메인 스레드) 로 세워지고
     * 감시 루프에서 읽으므로 [Volatile] 로 둔다.
     */
    @Volatile
    private var navigationStarted = false

    override val currentUrl: String?
        get() = _pageUrl.value ?: webView.url

    init {
        webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                _isLoading.value = true
                _pageUrl.value = url
                navigationStarted = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                _isLoading.value = false
                _pageUrl.value = url
                outcomes.trySend(PageOutcome.Finished(url))
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                // 서브리소스(이미지/광고 등) 실패로 감시를 중단하지 않는다.
                if (request?.isForMainFrame != true) return
                _isLoading.value = false
                outcomes.trySend(
                    PageOutcome.Failed(
                        code = error?.errorCode ?: -1,
                        description = error?.description?.toString() ?: "페이지 오류",
                    ),
                )
            }
        }
    }

    override suspend fun loadStartUrl() {
        withContext(Dispatchers.Main) {
            drainOutcomes()
            webView.loadUrl(startUrl)
        }
    }

    override suspend fun requery(
        timeoutMs: Long,
        settleTimeoutMs: Long,
        onClick: (String) -> Unit,
    ): PageOutcome {
        // 팝업이 열려 있으면 이번 차례는 아무것도 하지 않는다. 요청도 나가지 않는다.
        if (isPopupOpen()) return PageOutcome.Deferred("팝업 창이 열려 있음")

        drainOutcomes()
        navigationStarted = false

        // 1) 클릭 전 상태 기록 (MutationObserver 설치 + 목록 서명)
        val baseline = evaluateJson(KtxParserScript.buildObserverScript())
        val baselineSig = baseline?.optString("sig").orEmpty()
        val observing = baseline?.optBoolean("observing") ?: false

        // 2) 누르려면 WebView 가 실제로 화면에 떠 있어야 한다.
        //    (사람이 못 누르는 상태면 이 앱도 누르지 않는다)
        val surface = readSurface()
        if (!surface.usable) {
            return PageOutcome.NotTappable("WebView 를 누를 수 없음 (${surface.describe()})")
        }

        // 3) 버튼의 화면 좌표를 찾는다. 이 단계는 아무것도 누르지 않는다.
        val located = evaluateJson(
            KtxParserScript.buildLocateScript(surface.width, surface.height),
        ) ?: return PageOutcome.ButtonNotFound("스크립트 실행 실패")

        if (!located.optBoolean("found")) return buttonNotFound(located)
        if (!located.optBoolean("tappable")) return notTappable(located, surface)

        val point = tapPointOf(located)
            ?: return PageOutcome.NotTappable("좌표를 읽지 못함 (${located.optString("rect")})")

        // 4) 그 자리를 진짜로 누른다.
        val tap = tap(point.first, point.second)
        val confirm = evaluateJson(KtxParserScript.buildTapConfirmScript())
        onClick(describeTap(located, tap, confirm, observing))

        if (!tap.delivered) {
            // 터치가 WebView 에 전달조차 되지 않았다. 조회 요청은 나가지 않았다.
            return PageOutcome.NotTappable("터치가 전달되지 않음 (${tap.describe()})")
        }

        // 5) 화면 전환 또는 DOM 갱신을 기다린다.
        return awaitSettled(timeoutMs, settleTimeoutMs, baselineSig)
    }

    /**
     * 예매 1단계 — 좌석 칸을 눌러 고른다. (DESIGN.md §38-6)
     *
     * 재조회와 같은 절차다. 좌표를 찾고 → 그 자리를 진짜로 누르고 → 정착을 기다린다.
     * 다른 점은 탐색 범위가 **그 편성의 그 칸**으로 제한된다는 것과,
     * 누른 뒤에 **정말 골라졌는지 읽어서 확인**한다는 것이다. (§38-6-1 의 확인 1)
     *
     * 확인이 필요한 이유는 이 누름이 화면 전환을 만들지 않기 때문이다. 눌렀는데
     * 아무 일도 일어나지 않은 경우와 제대로 골라진 경우가 겉으로는 구분되지 않는다.
     */
    override suspend fun selectSeat(
        target: ReserveTarget,
        timeoutMs: Long,
        settleTimeoutMs: Long,
        onClick: (String) -> Unit,
    ): SeatSelectOutcome {
        drainOutcomes()
        navigationStarted = false

        val surface = readSurface()
        if (!surface.usable) {
            return SeatSelectOutcome.NotTappable("WebView 를 누를 수 없음 (${surface.describe()})")
        }

        val located = evaluateJson(
            KtxParserScript.buildSelectScript(surface.width, surface.height, target),
        ) ?: return SeatSelectOutcome.CellNotFound("스크립트 실행 실패")

        if (!located.optBoolean("found")) return selectNotFound(target, located)
        if (!located.optBoolean("tappable")) {
            return SeatSelectOutcome.NotTappable(tapBlockedDetail(located, surface))
        }

        val point = tapPointOf(located)
            ?: return SeatSelectOutcome.NotTappable("좌표를 읽지 못함 (${located.optString("rect")})")

        val tap = tap(point.first, point.second)
        val confirm = evaluateJson(KtxParserScript.buildTapConfirmScript())
        onClick(describeSeatTap(target, located, tap, confirm))

        if (!tap.delivered) {
            return SeatSelectOutcome.NotTappable("터치가 전달되지 않음 (${tap.describe()})")
        }

        return awaitSeatSelected(target, settleTimeoutMs)
    }

    /**
     * 1단계를 누른 뒤 그 칸이 실제로 골라졌는지 **짧게 되풀이해 읽는다.**
     *
     * [awaitSettled] 를 쓰지 않는다. 좌석 칸을 고르면 그 칸에 class 하나(`active`)가
     * 붙고 화면 하단에 예매 바가 뜰 뿐이다. 목록 서명은 그대로고(선택 표시는 서명에서
     * 일부러 뺀다), MutationObserver 도 class 변경이나 목록 바깥의 변화는 세지 않는다.
     * 정착을 기다리면 **아무 일도 없는 채로 대기 시간을 통째로 버린다** — 가장 급한 순간에.
     *
     * 그래서 확인 스크립트를 직접 폴링한다. 읽기만 하므로 요청은 나가지 않는다.
     */
    private suspend fun awaitSeatSelected(
        target: ReserveTarget,
        settleTimeoutMs: Long,
    ): SeatSelectOutcome {
        val script = KtxParserScript.buildSelectConfirmScript(target)
        val startedAt = System.currentTimeMillis()
        var last = "선택 확인 결과 없음"

        while (true) {
            (outcomes.tryReceive().getOrNull() as? PageOutcome.Failed)?.let {
                return SeatSelectOutcome.Failed(it.code, it.description)
            }

            val checked = evaluateJson(script)
            if (checked != null) {
                last = checked.optString("detail")
                    .ifBlank { checked.optString("reason") }
                    .ifBlank { "선택 확인 결과 없음" }
                if (checked.optBoolean("selected")) return SeatSelectOutcome.Selected(last)
            }

            if (System.currentTimeMillis() - startedAt >= settleTimeoutMs) {
                return SeatSelectOutcome.NotSelected(last)
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    /**
     * 예매 2단계 — 하단 바의 [예매] 를 누른다. (DESIGN.md §38-6-1)
     *
     * 스크립트가 확인 세 가지를 모두 통과했을 때만 좌표를 돌려주므로, 여기서는
     * 좌표가 왔는지만 보면 된다. 확인에 걸린 경우는 이유별로 나눠서 올린다 —
     * **누르지 않은 것과 누르고 실패한 것은 다른 사건이다.**
     */
    override suspend fun confirmReserve(
        target: ReserveTarget,
        timeoutMs: Long,
        settleTimeoutMs: Long,
        onClick: (String) -> Unit,
    ): ReserveOutcome {
        drainOutcomes()
        navigationStarted = false

        val baseline = evaluateJson(KtxParserScript.buildObserverScript())
        val baselineSig = baseline?.optString("sig").orEmpty()

        val surface = readSurface()
        if (!surface.usable) {
            return ReserveOutcome.NotTappable("WebView 를 누를 수 없음 (${surface.describe()})")
        }

        val located = evaluateJson(
            KtxParserScript.buildReserveScript(surface.width, surface.height, target),
        ) ?: return ReserveOutcome.ButtonNotFound("스크립트 실행 실패")

        if (!located.optBoolean("found")) return reserveNotFound(target, located)
        if (!located.optBoolean("tappable")) {
            return ReserveOutcome.NotTappable(tapBlockedDetail(located, surface))
        }

        val point = tapPointOf(located)
            ?: return ReserveOutcome.NotTappable("좌표를 읽지 못함 (${located.optString("rect")})")

        val tap = tap(point.first, point.second)
        val confirm = evaluateJson(KtxParserScript.buildTapConfirmScript())
        onClick(describeReserveTap(target, located, tap, confirm))

        if (!tap.delivered) {
            return ReserveOutcome.NotTappable("터치가 전달되지 않음 (${tap.describe()})")
        }

        val outcome = awaitSettled(timeoutMs, settleTimeoutMs, baselineSig)

        // 화면이 넘어갔다고 예약 화면인 것은 아니다. "잔여석없음" 안내일 수 있다. (§19-2)
        if (outcome !is PageOutcome.Failed) {
            readReserveFailure()?.let { return it }
        }

        return when (outcome) {
            is PageOutcome.Finished -> ReserveOutcome.Clicked(outcome.url ?: "")
            is PageOutcome.Updated -> ReserveOutcome.Clicked(outcome.detail)
            is PageOutcome.Failed -> ReserveOutcome.Failed(outcome.code, outcome.description)
            // 눌렀는데 아무 변화가 없다. 그사이 좌석이 나갔을 수 있다.
            else -> ReserveOutcome.NoChange(outcome.detail)
        }
    }

    /**
     * 예약 실패 안내 화면에서 목록으로 되돌아간다. (DESIGN.md §19-2)
     *
     * **뒤로 가기만 쓴다.** 화면의 [확인] 버튼은 누르지 않는다. 그 버튼은 조회 폼을
     * 새로 여는 링크라, 사용자가 사이트에서 직접 넣어 둔 조회 조건(구간/날짜/시간)이
     * 초기화된 빈 조회 폼으로 간다. 조건이 사라진 채로 감시를 이어가면 엉뚱한 조회
     * 결과를 보게 된다.
     *
     * 코레일은 SPA 라 **뒤로 가기 한 칸이 조회 결과 화면이라는 보장이 없다.** (§38-8)
     * 그래서 물러난 뒤 [KtxParserScript.buildPageKindScript] 로 목록이 실제로 보이는지
     * 확인하고, 보이지 않으면 [MAX_BACK_STEPS] 까지만 한 번 더 물러난다.
     * 끝내 목록이 보이지 않으면 성공으로 치지 않는다 — 그 화면에는 [열차조회] 가 없어
     * 감시를 이어갈 수 없고, 억지로 무언가를 더 누르지도 않는다.
     */
    override suspend fun dismissReserveResult(
        timeoutMs: Long,
        settleTimeoutMs: Long,
        onClick: (String) -> Unit,
    ): PageOutcome {
        var last: PageOutcome = PageOutcome.ButtonNotFound("뒤로 갈 이력이 없음")

        repeat(MAX_BACK_STEPS) { step ->
            drainOutcomes()
            navigationStarted = false

            val baseline = evaluateJson(KtxParserScript.buildObserverScript())
            val baselineSig = baseline?.optString("sig").orEmpty()

            val wentBack = withContext(Dispatchers.Main) {
                if (webView.canGoBack()) {
                    webView.goBack()
                    true
                } else {
                    false
                }
            }
            if (!wentBack) return listNotBack(last, "뒤로 갈 이력이 없음")

            onClick(if (step == 0) "뒤로 가기" else "뒤로 가기 (${step + 1}번째)")
            last = awaitSettled(timeoutMs, settleTimeoutMs, baselineSig)

            // 목록이 다시 보이면 여기서 끝이다. 아니면 한 칸 더 물러난다.
            val kind = evaluateJson(KtxParserScript.buildPageKindScript())
            if (kind != null && kind.optBoolean("list")) return last
        }

        return listNotBack(last, "뒤로 가기 ${MAX_BACK_STEPS}번 뒤에도 열차 목록이 없음")
    }

    /** 되돌리기가 목록 화면에 닿지 못했다. 감시를 이어갈 수 없는 상태다. */
    private fun listNotBack(last: PageOutcome, reason: String): PageOutcome =
        PageOutcome.ButtonNotFound("$reason (마지막: ${last.detail.take(60)})")

    /**
     * 지금 화면이 예약 실패 안내인지 확인한다.
     * 실패 화면이면 [ReserveOutcome.SoldOut], 아니면 null.
     */
    private suspend fun readReserveFailure(): ReserveOutcome.SoldOut? {
        val result = evaluateJson(KtxParserScript.buildReserveResultScript()) ?: return null
        if (!result.optBoolean("failed")) return null
        return ReserveOutcome.SoldOut(
            buildString {
                append(result.optString("marker"))
                append(" / url=").append(result.optString("url").takeLast(60))
                val head = result.optString("head")
                if (head.isNotBlank()) append(" / body=").append(head.take(60))
            },
        )
    }

    // ---------------------------------------------------------------- 터치

    /**
     * WebView 위젯의 [x], [y] 지점을 실제로 누른다.
     *
     * 사람의 탭과 같은 입력이 되도록:
     *  - 터치스크린 소스의 손가락(TOOL_TYPE_FINGER) MotionEvent 를 보낸다.
     *  - 누르는 시간을 매번 조금씩 다르게 한다. 사람은 같은 시간만큼 누르지 않는다.
     *  - 뗄 때 좌표를 1px 안쪽에서 흔든다. 손가락은 완전히 고정되지 않는다.
     *    (touch slop 안이라 스크롤로 해석되지 않는다)
     */
    private suspend fun tap(x: Float, y: Float): TapResult {
        val holdMs = TAP_HOLD_MIN_MS + Random.nextLong(TAP_HOLD_SPREAD_MS)
        val upX = x + Random.nextInt(-TAP_JITTER_PX, TAP_JITTER_PX + 1)
        val upY = y + Random.nextInt(-TAP_JITTER_PX, TAP_JITTER_PX + 1)
        val downTime = SystemClock.uptimeMillis()

        val down = withContext(Dispatchers.Main) {
            sendTouch(downTime, downTime, MotionEvent.ACTION_DOWN, x, y)
        }

        try {
            delay(holdMs)
        } catch (cancelled: CancellationException) {
            // 감시가 취소되어도 버튼을 누른 채로 두지 않는다.
            withContext(NonCancellable + Dispatchers.Main) {
                sendTouch(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, x, y)
            }
            throw cancelled
        }

        val up = withContext(Dispatchers.Main) {
            val moveTime = SystemClock.uptimeMillis()
            sendTouch(downTime, moveTime, MotionEvent.ACTION_MOVE, upX, upY)
            sendTouch(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, upX, upY)
        }

        return TapResult(x = x, y = y, holdMs = holdMs, down = down, up = up)
    }

    /** MotionEvent 하나를 WebView 에 전달한다. 메인 스레드에서만 호출한다. */
    private fun sendTouch(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ): Boolean {
        val properties = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1f
                size = 1f
            },
        )
        val event = MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1,
            properties,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
        return try {
            webView.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    /** WebView 가 지금 눌릴 수 있는 상태인지. 메인 스레드에서 읽는다. */
    private suspend fun readSurface(): Surface = withContext(Dispatchers.Main) {
        Surface(
            width = webView.width,
            height = webView.height,
            attached = webView.isAttachedToWindow,
            shown = webView.isShown,
        )
    }

    /** 스크립트가 돌려준 좌표. 하나라도 음수면 쓰지 않는다. */
    private fun tapPointOf(located: JSONObject): Pair<Float, Float>? {
        val x = located.optDouble("x", -1.0)
        val y = located.optDouble("y", -1.0)
        if (x < 0 || y < 0) return null
        return x.toFloat() to y.toFloat()
    }

    // ---------------------------------------------------------------- 정착 대기

    /**
     * 화면 전환이 시작되었다면 [timeoutMs] 까지 onPageFinished 를 기다린다.
     * 전환이 없다면 [settleTimeoutMs] 까지 DOM 변경만 살핀다.
     * AJAX 재조회에서 응답 내용이 이전과 같으면 변화가 관찰되지 않으므로,
     * 이 경우 [PageOutcome.Settled] 로 돌려주고 분석은 계속 진행한다.
     */
    private suspend fun awaitSettled(
        timeoutMs: Long,
        settleTimeoutMs: Long,
        baselineSig: String,
    ): PageOutcome {
        // 스크립트 실행에 걸리는 시간도 포함되도록 실제 경과 시간으로 판단한다.
        val startedAt = System.currentTimeMillis()
        while (true) {
            outcomes.tryReceive().getOrNull()?.let { return it }
            val waited = System.currentTimeMillis() - startedAt

            if (navigationStarted) {
                // 화면 전환 중이다. 페이지 이벤트만 기다린다.
                if (waited >= timeoutMs) return PageOutcome.Settled("화면 전환 후 응답 없음")
            } else {
                if (waited >= settleTimeoutMs) {
                    return PageOutcome.Settled("DOM 변경 없음 sig=$baselineSig")
                }
                val probe = evaluateJson(KtxParserScript.buildProbeScript())
                if (probe != null && (probe.optBoolean("changed") || probe.optInt("mut") > 0)) {
                    // 렌더링이 끝나기 전에 읽지 않도록 잠깐 기다린다.
                    delay(SETTLE_GRACE_MS)
                    return PageOutcome.Updated(
                        "mut=${probe.optInt("mut")} sig=$baselineSig→${probe.optString("sig")}",
                    )
                }
            }

            delay(POLL_INTERVAL_MS)
        }
    }

    override suspend fun evaluate(script: String): String? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                webView.evaluateJavascript(script) { value ->
                    if (continuation.isActive) continuation.resume(value)
                }
            }
        }

    /** 스크립트가 돌려준 JSON 객체. 실행 실패(null/undefined)면 null. */
    private suspend fun evaluateJson(script: String): JSONObject? {
        val raw = evaluate(script) ?: return null
        if (raw == "null" || raw.isBlank()) return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private fun drainOutcomes() {
        while (outcomes.tryReceive().isSuccess) {
            // 이전 사이클에 남은 이벤트 제거
        }
    }

    // ---------------------------------------------------------------- 로그 문구

    /**
     * "조회" 가 들어간 요소를 찾아 왜 못 눌렀는지까지 남긴다.
     * 로그만 보고 [KtxSelectors] 를 고칠 수 있어야 한다.
     */
    private fun buttonNotFound(located: JSONObject): PageOutcome.ButtonNotFound {
        val near = located.optJSONArray("near")
            ?.let { array -> (0 until array.length()).map { array.optString(it) } }
            .orEmpty()
        return PageOutcome.ButtonNotFound(
            buildString {
                append("요소 ${located.optInt("scanned")}개 확인, 조회 버튼 없음")
                append(" / ").append(located.optString("counts"))
                append(" / url=").append(located.optString("url").takeLast(60))
                val title = located.optString("title")
                if (title.isNotBlank()) append(" / title=").append(title.take(40))
                if (near.isNotEmpty()) append(" / 근접: ").append(near.joinToString(" | "))
                val bodyHead = located.optString("bodyHead")
                if (bodyHead.isNotBlank()) append(" / body=").append(bodyHead)
            },
        )
    }

    /** 버튼은 찾았지만 손가락이 닿지 않는 상태. 무엇이 막고 있는지 남긴다. */
    private fun notTappable(located: JSONObject, surface: Surface): PageOutcome.NotTappable =
        PageOutcome.NotTappable(tapBlockedDetail(located, surface))

    /** 좌표를 못 잡은 이유를 사람이 읽을 문장으로. 세 경로가 같은 형식을 쓴다. */
    private fun tapBlockedDetail(located: JSONObject, surface: Surface): String {
        val reason = when (located.optString("reason")) {
            "COVERED" -> "다른 요소가 덮고 있음"
            "OFF_SCREEN" -> "화면 밖"
            "ZERO_SIZE" -> "크기가 0"
            "NO_VIEWPORT" -> "뷰포트 크기를 읽지 못함"
            else -> located.optString("reason")
        }
        return buildString {
            append(reason)
            val label = located.optString("label")
            if (label.isNotBlank()) append(" [").append(label).append("]")
            val tag = located.optString("tag")
            if (tag.isNotBlank()) append(" <").append(tag.lowercase()).append(">")
            val covered = located.optString("covered")
            if (covered.isNotBlank()) append(" ← ").append(covered)
            val rect = located.optString("rect")
            if (rect.isNotBlank()) append(" / rect=").append(rect)
            val viewport = located.optString("viewport")
            if (viewport.isNotBlank()) append(" / ").append(viewport)
            append(" / webview=").append(surface.describe())
        }
    }

    /** 1단계에서 좌석 칸을 특정하지 못했다. 무엇을 확인했는지 남긴다. */
    private fun selectNotFound(target: ReserveTarget, located: JSONObject): SeatSelectOutcome {
        val head = "${target.trainNumber} ${target.departureTime} ${target.seatLabel}"
        val reason = located.optString("reason")
        val detail = buildString {
            append(head).append(" / ")
            append(
                when (reason) {
                    "ROW_NOT_FOUND" -> "해당 열차를 목록에서 찾지 못함"
                    "ROW_AMBIGUOUS" -> "같은 내용의 편성이 여러 개"
                    "ROW_MISMATCH" -> "편성 내용이 달라짐"
                    "CELL_NOT_FOUND" -> "좌석 칸을 특정하지 못함"
                    "SEAT_NOT_AVAILABLE" -> "그사이 좌석이 닫힘"
                    else -> reason
                },
            )
            val extra = located.optString("detail")
            if (extra.isNotBlank()) append(" (").append(extra).append(")")
        }
        return when (reason) {
            "ROW_NOT_FOUND", "ROW_AMBIGUOUS", "ROW_MISMATCH" -> SeatSelectOutcome.RowNotFound(detail)
            else -> SeatSelectOutcome.CellNotFound(detail)
        }
    }

    /** 2단계에서 버튼을 누르지 않은 이유. 확인 실패와 허용목록 밖을 나눈다. (§38-6-1) */
    private fun reserveNotFound(target: ReserveTarget, located: JSONObject): ReserveOutcome {
        val head = "${target.trainNumber} ${target.departureTime} ${target.seatLabel}"
        val reason = located.optString("reason")
        val buttons = located.optJSONArray("buttons")
            ?.let { array -> (0 until array.length()).map { array.optString(it) } }
            .orEmpty()
        val detail = buildString {
            append(head).append(" / ")
            append(
                when (reason) {
                    "ROW_NOT_FOUND" -> "고른 편성을 다시 찾지 못함"
                    "NOT_SELECTED" -> "고른 칸에 선택 표시가 없음"
                    "BAR_NOT_FOUND" -> "하단 예매 바가 보이지 않음"
                    "LABEL_MISMATCH" -> "하단 바의 좌석 등급이 다름"
                    "NOT_ALLOWED" -> "누를 수 있는 문구가 아님"
                    "BUTTON_NOT_FOUND" -> "예매 버튼 없음"
                    "BUTTON_AMBIGUOUS" -> "예매 버튼 후보가 여러 개"
                    else -> reason
                },
            )
            val extra = located.optString("detail")
            if (extra.isNotBlank()) append(" (").append(extra).append(")")
            if (buttons.isNotEmpty()) append(" 버튼=").append(buttons.joinToString("/"))
        }
        return when (reason) {
            "NOT_ALLOWED" -> ReserveOutcome.NotAllowed(detail)
            "ROW_NOT_FOUND", "NOT_SELECTED", "LABEL_MISMATCH" -> ReserveOutcome.Mismatch(detail)
            else -> ReserveOutcome.ButtonNotFound(detail)
        }
    }

    /** 어떤 열차의 어떤 칸을 골랐는지. */
    private fun describeSeatTap(
        target: ReserveTarget,
        located: JSONObject,
        tap: TapResult,
        confirm: JSONObject?,
    ): String = buildString {
        append("1단계 ").append(target.trainNumber).append(" ").append(target.departureTime)
        append(" ").append(target.seatLabel)
        append(" 탭 (").append(tap.x.roundToInt()).append(",").append(tap.y.roundToInt())
        append(") ").append(tap.holdMs).append("ms")
        append(" 칸#").append(located.optInt("cellIndex"))
        append("/").append(located.optInt("cells"))
        append(" [").append(located.optString("label")).append("]")
        appendClickConfirm(confirm)
        if (!tap.down || !tap.up) append(" / ").append(tap.describe())
    }

    /** 어떤 버튼을 눌러 예매를 걸었는지. 되돌리기 어려우므로 자세히 남긴다. */
    private fun describeReserveTap(
        target: ReserveTarget,
        located: JSONObject,
        tap: TapResult,
        confirm: JSONObject?,
    ): String = buildString {
        append("2단계 ").append(target.trainNumber).append(" ").append(target.departureTime)
        append(" ").append(target.seatLabel)
        append(" 탭 (").append(tap.x.roundToInt()).append(",").append(tap.y.roundToInt())
        append(") ").append(tap.holdMs).append("ms")
        append(" [").append(located.optString("label")).append("]")
        append(" 하단바=").append(located.optString("barLabel"))
        appendClickConfirm(confirm)
        if (!tap.down || !tap.up) append(" / ").append(tap.describe())
    }

    /** 어디를 어떻게 눌렀는지. 갱신이 안 될 때 원인을 좁히는 데 쓴다. */
    private fun describeTap(
        located: JSONObject,
        tap: TapResult,
        confirm: JSONObject?,
        observing: Boolean,
    ): String = buildString {
        append("탭 (").append(tap.x.roundToInt()).append(",").append(tap.y.roundToInt())
        append(") ").append(tap.holdMs).append("ms")
        append(" ").append(located.optString("how")).append(" ").append(located.optString("by"))
        val label = located.optString("label")
        if (label.isNotBlank()) append(" [").append(label).append("]")
        append(" <").append(located.optString("tag").lowercase())
        val type = located.optString("type")
        if (type.isNotBlank()) append(":").append(type)
        append(">")
        if (located.optBoolean("inList")) append(" inList")
        append(" 후보=").append(located.optInt("candidates"))
        appendClickConfirm(confirm)
        if (!tap.down || !tap.up) append(" / ").append(tap.describe())
        if (!observing) append(" (observer 없음)")
    }

    /** 진짜 클릭이 목표까지 갔는지. 세 경로가 같은 형식으로 남긴다. */
    private fun StringBuilder.appendClickConfirm(confirm: JSONObject?) {
        when {
            confirm == null || !confirm.optBoolean("known") -> append(" / 확인 불가")
            !confirm.optBoolean("fired") -> append(" / click 미발생")
            else -> {
                append(" / click")
                append(if (confirm.optBoolean("trusted")) " trusted" else " untrusted")
                if (!confirm.optBoolean("onTarget")) {
                    append(" 다른요소=").append(confirm.optString("tag"))
                }
            }
        }
    }

    /** WebView 위젯의 지금 상태. */
    private data class Surface(
        val width: Int,
        val height: Int,
        val attached: Boolean,
        val shown: Boolean,
    ) {
        val usable: Boolean
            get() = attached && shown && width >= MIN_SURFACE_PX && height >= MIN_SURFACE_PX

        fun describe(): String = buildString {
            append(width).append("x").append(height)
            if (!attached) append(" detached")
            if (!shown) append(" hidden")
        }
    }

    /** 한 번의 탭 결과. down/up 은 WebView 가 이벤트를 소비했는지다. */
    private data class TapResult(
        val x: Float,
        val y: Float,
        val holdMs: Long,
        val down: Boolean,
        val up: Boolean,
    ) {
        /** 터치가 WebView 에 들어갔는지. DOWN 이 무시되면 아무 일도 일어나지 않는다. */
        val delivered: Boolean get() = down

        fun describe(): String = "down=$down up=$up"
    }

    private companion object {
        const val POLL_INTERVAL_MS = 200L
        const val SETTLE_GRACE_MS = 300L

        /** 탭을 유지하는 시간. 사람의 탭은 대략 60~160ms 다. */
        const val TAP_HOLD_MIN_MS = 60L
        const val TAP_HOLD_SPREAD_MS = 100L

        /** 뗄 때 흔드는 폭(px). touch slop 보다 훨씬 작아 스크롤로 해석되지 않는다. */
        const val TAP_JITTER_PX = 1

        /** 이보다 작으면 화면에 제대로 그려진 것으로 보지 않는다. */
        const val MIN_SURFACE_PX = 32

        /**
         * 예약 실패 안내에서 물러날 최대 이력 칸 수. (§19-2)
         * 리다이렉트로 안내 화면이 두 칸 쌓이는 경우까지만 감당한다.
         * 더 물러나면 사용자가 보던 화면에서 너무 멀어진다.
         */
        const val MAX_BACK_STEPS = 2
    }
}
