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
 * 실제 WebView 를 [PageHost] 로 감싼다. (DESIGN.md §10, §14)
 *
 * - WebViewClient 를 설치하여 onPageStarted / onPageFinished / onReceivedError 를 수집한다.
 * - 갱신은 항상 **화면에 보이는 "조회하기" 버튼을 직접 누르는 것**으로만 한다.
 *   [WebView.reload] 도, 조회 URL 직접 호출도 하지 않는다.
 * - 조건을 만족했을 때 누르는 "예약하기"([clickReserve]) 도 똑같은 방식이다.
 *   다만 탐색 범위가 그 열차의 그 좌석 칸으로 제한된다. (§19-1)
 * - JavaScript 의 setInterval 은 사용하지 않는다. (§34-3)
 * - WebView API 는 모두 메인 스레드에서 호출한다.
 *
 * 클릭 방식이 핵심이다. 한 사이클은 이렇게 진행된다.
 *  1) [SrtParserScript.buildLocateScript] 로 버튼의 **화면 좌표**만 알아낸다. (누르지 않는다)
 *  2) 그 좌표에 [MotionEvent] 를 내려보낸다. 사용자가 손가락으로 누른 것과 같은 입력이다.
 *  3) [SrtParserScript.buildTapConfirmScript] 로 클릭이 버튼까지 갔는지 확인한다.
 *
 * JS 의 `el.click()` / `dispatchEvent` 를 쓰지 않는 이유는 isTrusted=false 인 합성
 * 이벤트이기 때문이고, `a[href]` 나 URL 직접 호출을 쓰지 않는 이유는 그 경로가
 * 사실상 항상 차단되기 때문이다.
 *
 * 클릭 후 "정착"은 두 경로로 감지한다.
 *  1) form submit 으로 화면이 전환되면 onPageFinished
 *  2) 화면 전환 없이 결과 표만 바뀌면(AJAX) MutationObserver / 표 시그니처 변화
 */
class SrtWebViewHost(
    private val webView: WebView,
    private val startUrl: String = SrtSelectors.START_URL,
    /**
     * 팝업 창이 열려 있는지. ([SrtPopupHost.isOpen])
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

        // 1) 클릭 전 상태 기록 (MutationObserver 설치 + 표 시그니처)
        val baseline = evaluateJson(SrtParserScript.buildObserverScript())
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
            SrtParserScript.buildLocateScript(surface.width, surface.height),
        ) ?: return PageOutcome.ButtonNotFound("스크립트 실행 실패")

        if (!located.optBoolean("found")) return buttonNotFound(located)
        if (!located.optBoolean("tappable")) return notTappable(located, surface)

        val x = located.optDouble("x", -1.0)
        val y = located.optDouble("y", -1.0)
        if (x < 0 || y < 0) {
            return PageOutcome.NotTappable("좌표를 읽지 못함 (${located.optString("rect")})")
        }

        // 4) 그 자리를 진짜로 누른다.
        val tap = tap(x.toFloat(), y.toFloat())
        val confirm = evaluateJson(SrtParserScript.buildTapConfirmScript())
        onClick(describeTap(located, tap, confirm, observing))

        if (!tap.delivered) {
            // 터치가 WebView 에 전달조차 되지 않았다. 조회 요청은 나가지 않았다.
            return PageOutcome.NotTappable("터치가 전달되지 않음 (${tap.describe()})")
        }

        // 5) 화면 전환 또는 DOM 갱신을 기다린다.
        return awaitSettled(timeoutMs, settleTimeoutMs, baselineSig)
    }

    /**
     * 조건을 만족한 열차의 [예약하기] 버튼을 누른다. (DESIGN.md §19)
     *
     * 재조회와 완전히 같은 절차다. 좌표를 찾고 → 그 자리를 진짜로 누르고 → 정착을 기다린다.
     * 다른 점은 탐색 범위가 **그 행의 그 좌석 칸**으로 제한된다는 것뿐이다.
     * 버튼을 하나로 특정하지 못하면 누르지 않는다.
     */
    override suspend fun clickReserve(
        target: ReserveTarget,
        timeoutMs: Long,
        settleTimeoutMs: Long,
        onClick: (String) -> Unit,
    ): ReserveOutcome {
        drainOutcomes()
        navigationStarted = false

        val baseline = evaluateJson(SrtParserScript.buildObserverScript())
        val baselineSig = baseline?.optString("sig").orEmpty()

        val surface = readSurface()
        if (!surface.usable) {
            return ReserveOutcome.NotTappable("WebView 를 누를 수 없음 (${surface.describe()})")
        }

        val located = evaluateJson(
            SrtParserScript.buildReserveScript(surface.width, surface.height, target),
        ) ?: return ReserveOutcome.ButtonNotFound("스크립트 실행 실패")

        if (!located.optBoolean("found")) return reserveNotFound(target, located)
        if (!located.optBoolean("tappable")) return reserveNotTappable(located, surface)

        val x = located.optDouble("x", -1.0)
        val y = located.optDouble("y", -1.0)
        if (x < 0 || y < 0) {
            return ReserveOutcome.NotTappable("좌표를 읽지 못함 (${located.optString("rect")})")
        }

        val tap = tap(x.toFloat(), y.toFloat())
        val confirm = evaluateJson(SrtParserScript.buildTapConfirmScript())
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
     * **뒤로 가기만 쓴다.** 화면의 [확인] 버튼은 누르지 않는다.
     * 그 버튼은 `selectScheduleList.do` 를 새로 여는 링크라, 사용자가 사이트에서
     * 직접 넣어 둔 조회 조건(구간/날짜/시간)이 초기화된 빈 조회 폼으로 간다.
     * 조건이 사라진 채로 감시를 이어가면 엉뚱한 조회 결과를 보게 된다.
     *
     * 뒤로 가기는 조회 결과 화면(그 조건 그대로)으로 되돌아간다.
     * 사람이 뒤로 가기를 누른 것과 같은 경로이고, 조회 URL 을 직접 부르지도 않는다.
     *
     * 되돌아간 화면이 또 예약 실패 안내면(리다이렉트로 이력이 두 칸인 경우)
     * [MAX_BACK_STEPS] 까지 한 번 더 물러난다. 그 이상은 하지 않는다.
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

            val baseline = evaluateJson(SrtParserScript.buildObserverScript())
            val baselineSig = baseline?.optString("sig").orEmpty()

            val wentBack = withContext(Dispatchers.Main) {
                if (webView.canGoBack()) {
                    webView.goBack()
                    true
                } else {
                    false
                }
            }
            if (!wentBack) return last

            onClick(if (step == 0) "뒤로 가기" else "뒤로 가기 (${step + 1}번째)")
            last = awaitSettled(timeoutMs, settleTimeoutMs, baselineSig)

            // 아직도 예약 실패 안내면 한 칸 더 물러난다. 아니면 여기서 끝이다.
            if (readReserveFailure() == null) return last
        }

        return last
    }

    /**
     * 지금 화면이 예약 실패 안내인지 확인한다.
     * 실패 화면이면 [ReserveOutcome.SoldOut], 아니면 null.
     */
    private suspend fun readReserveFailure(): ReserveOutcome.SoldOut? {
        val result = evaluateJson(SrtParserScript.buildReserveResultScript()) ?: return null
        if (!result.optBoolean("failed")) return null
        return ReserveOutcome.SoldOut(
            buildString {
                append(result.optString("marker"))
                append(" / url=").append(result.optString("url").takeLast(60))
                if (!result.optBoolean("hasDismiss")) append(" / 확인 버튼 없음")
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
                // form submit 으로 전환 중이다. 페이지 이벤트만 기다린다.
                if (waited >= timeoutMs) return PageOutcome.Settled("화면 전환 후 응답 없음")
            } else {
                if (waited >= settleTimeoutMs) {
                    return PageOutcome.Settled("DOM 변경 없음 sig=$baselineSig")
                }
                val probe = evaluateJson(SrtParserScript.buildProbeScript())
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
     * 로그만 보고 [SrtSelectors] 를 고칠 수 있어야 한다.
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
    private fun notTappable(located: JSONObject, surface: Surface): PageOutcome.NotTappable {
        val reason = when (located.optString("reason")) {
            "COVERED" -> "다른 요소가 덮고 있음"
            "OFF_SCREEN" -> "버튼이 화면 밖"
            "ZERO_SIZE" -> "버튼 크기가 0"
            "NO_VIEWPORT" -> "뷰포트 크기를 읽지 못함"
            else -> located.optString("reason")
        }
        return PageOutcome.NotTappable(
            buildString {
                append(reason)
                val label = located.optString("label")
                if (label.isNotBlank()) append(" [").append(label).append("]")
                append(" <").append(located.optString("tag").lowercase()).append(">")
                val covered = located.optString("covered")
                if (covered.isNotBlank()) append(" ← ").append(covered)
                val rect = located.optString("rect")
                if (rect.isNotBlank()) append(" / rect=").append(rect)
                val viewport = located.optString("viewport")
                if (viewport.isNotBlank()) append(" / ").append(viewport)
                append(" / webview=").append(surface.describe())
            },
        )
    }

    /** 예약할 행/버튼을 특정하지 못했다. 무엇을 확인했는지 남긴다. */
    private fun reserveNotFound(
        target: ReserveTarget,
        located: JSONObject,
    ): ReserveOutcome {
        val head = "${target.trainNumber} ${target.departureTime} ${target.seatLabel}"
        val reason = located.optString("reason")
        val detail = buildString {
            append(head).append(" / ")
            append(
                when (reason) {
                    "ROW_NOT_FOUND" -> "해당 열차 행 없음"
                    "ROW_AMBIGUOUS" -> "같은 내용의 행이 여러 개"
                    "ROW_MISMATCH" -> "행 내용이 달라짐"
                    "BUTTON_NOT_FOUND" -> "좌석 칸에 예약하기 버튼 없음"
                    "BUTTON_AMBIGUOUS" -> "예약하기 후보가 여러 개"
                    else -> reason
                },
            )
            val extra = located.optString("detail")
            if (extra.isNotBlank()) append(" (").append(extra).append(")")
        }
        return when (reason) {
            "ROW_NOT_FOUND", "ROW_AMBIGUOUS", "ROW_MISMATCH" -> ReserveOutcome.RowNotFound(detail)
            else -> ReserveOutcome.ButtonNotFound(detail)
        }
    }

    /** 예약하기 버튼은 찾았지만 손가락이 닿지 않는 상태. */
    private fun reserveNotTappable(located: JSONObject, surface: Surface): ReserveOutcome.NotTappable {
        val reason = when (located.optString("reason")) {
            "COVERED" -> "다른 요소가 덮고 있음"
            "OFF_SCREEN" -> "버튼이 화면 밖"
            "ZERO_SIZE" -> "버튼 크기가 0"
            "NO_VIEWPORT" -> "뷰포트 크기를 읽지 못함"
            else -> located.optString("reason")
        }
        return ReserveOutcome.NotTappable(
            buildString {
                append(reason)
                val label = located.optString("label")
                if (label.isNotBlank()) append(" [").append(label).append("]")
                val covered = located.optString("covered")
                if (covered.isNotBlank()) append(" ← ").append(covered)
                val rect = located.optString("rect")
                if (rect.isNotBlank()) append(" / rect=").append(rect)
                append(" / webview=").append(surface.describe())
            },
        )
    }

    /** 어떤 열차의 어떤 좌석을 눌렀는지. 예약은 되돌리기 어려우므로 자세히 남긴다. */
    private fun describeReserveTap(
        target: ReserveTarget,
        located: JSONObject,
        tap: TapResult,
        confirm: JSONObject?,
    ): String = buildString {
        append(target.trainNumber).append(" ").append(target.departureTime)
        append(" ").append(target.seatLabel)
        append(" 탭 (").append(tap.x.roundToInt()).append(",").append(tap.y.roundToInt())
        append(") ").append(tap.holdMs).append("ms")
        append(" [").append(located.optString("label")).append("]")
        append(" <").append(located.optString("tag").lowercase()).append(">")
        append(" ").append(located.optString("scope"))
        append("#").append(located.optInt("cellIndex"))
        append(" 후보=").append(located.optInt("candidates"))

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
        if (located.optBoolean("inTable")) append(" inTable")
        append(" 후보=").append(located.optInt("candidates"))

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

        if (!tap.down || !tap.up) append(" / ").append(tap.describe())
        if (!observing) append(" (observer 없음)")
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
