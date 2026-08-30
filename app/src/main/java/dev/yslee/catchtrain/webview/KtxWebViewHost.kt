package dev.yslee.catchtrain.webview

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 실제 WebView 를 [PageHost] 로 감싼다. (DESIGN.md §10, §14, §38)
 *
 * - WebViewClient 를 설치하여 onPageStarted / onPageFinished / onReceivedError 를 수집한다.
 * - 갱신([requery])은 **페이지 새로고침**([WebView.reload], = F5) 하나뿐이다. (§38-9)
 *   조회 URL 직접 호출은 여전히 하지 않는다.
 * - **예매는 다르다.** 좌석 칸과 [예매] 버튼은 화면에 실제로 있으므로 예전 그대로
 *   [MotionEvent] 로 직접 누른다. 그리고 **두 번 눌러야 한다.** (§38-6)
 *   [selectSeat] 로 좌석 칸을 고르고, [confirmReserve] 로 하단 바의 [예매] 를 누른다.
 * - JavaScript 의 setInterval 은 사용하지 않는다. (§34-3)
 * - WebView API 는 모두 메인 스레드에서 호출한다.
 *
 * ## 갱신이 왜 새로고침인가
 *
 * 원래는 결과 화면의 [열차조회] 버튼을 진짜 터치로 눌렀다. **모바일 폭에서는 그 버튼이
 * 없다.** `div.btnWrap.btn_box` 가 `display:none` 이라 rect 가 0×0 이고, 그 자리에 보이는
 * 것은 절대 눌러선 안 되는 `다음날 (…) 조회` 뿐이다. (§38-9)
 *
 * 조회 조건은 `localStorage["LS_TICKET_GENERAL"]` 에 있어서 새로고침해도 살아남는다.
 * 다시 불러오면 SPA 가 그 값으로 같은 조회를 스스로 되풀이한다.
 *
 * ## 예매 클릭 방식 (그대로다)
 *
 *  1) [KtxParserScript] 의 탐색 스크립트로 **화면 좌표**만 알아낸다. (누르지 않는다)
 *  2) 그 좌표에 [MotionEvent] 를 내려보낸다. 사용자가 손가락으로 누른 것과 같은 입력이다.
 *  3) [KtxParserScript.buildTapConfirmScript] 로 클릭이 목표까지 갔는지 확인한다.
 *
 * JS 의 `el.click()` / `dispatchEvent` 를 쓰지 않는 이유는 isTrusted=false 인 합성
 * 이벤트이기 때문이고, `a[href]` 나 URL 직접 호출을 쓰지 않는 이유는 그 경로가
 * 사실상 항상 차단되기 때문이다.
 *
 * ## 정착 감지
 *
 * 갱신([awaitReloaded])은 화면 전환이 확실하므로 `onPageFinished` 를 먼저 기다리고,
 * 그다음 **목록이 다시 그려졌는지**를 폴링한다. 문서 로딩과 목록 렌더링은 별개다.
 *
 * 예매 2단계와 되돌리기([awaitSettled])는 화면 전환이 있을 수도 없을 수도 있어서
 * onPageFinished 와 MutationObserver / 목록 서명 변화를 함께 본다.
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
    /**
     * 문서마다 한 번, `100vh` 보정 결과를 밖으로 알린다. (§38-10)
     *
     * 로그에 남겨야 하는 이유는 이 보정이 **조용히** 동작하기 때문이다. 창이 다시
     * 뜨는지만 봐서는 보정이 걸렸는지, 애초에 멀쩡했는지 구분할 수 없다.
     */
    private val onViewportFix: (String) -> Unit = {},
    /**
     * 메인 화면에서 로그인 여부를 확인한 결과를 밖으로 알린다. (§27-2)
     *
     * 이 확인은 사용자가 아무것도 누르지 않은 사이에 조용히 일어나고, 결과에 따라
     * **화면이 바뀐다.** 왜 로그인 화면으로 갔는지(또는 왜 안 갔는지) 가 남는 곳은
     * 이 로그뿐이다.
     */
    private val onLoginRedirect: (String) -> Unit = {},
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

    /**
     * 메인 화면 로그인 확인([guardMainPageLogin])이 도는 자리. (§27-2)
     *
     * 감시 루프(ViewModel 의 scope)와 섞지 않는다. 이 확인은 감시와 무관하게
     * **메인 문서를 받을 때마다** 일어나고, 감시가 꺼져 있는 동안에도 돌아야 한다.
     */
    private val loginGuardScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    /** 직전 문서의 확인. 새 문서가 시작되면 버린다 — 그 판정은 이미 남의 페이지 것이다. */
    private var loginGuardJob: Job? = null

    /** 마지막으로 로그인 화면으로 보낸 시각. 되튐(main↔login)을 끊는 데만 쓴다. */
    private var lastLoginRedirectAt = 0L

    init {
        webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                _isLoading.value = true
                _pageUrl.value = url
                navigationStarted = true
                // 앞 문서의 로그인 확인은 여기서 끝난다. 늦게 온 판정으로 새 문서를
                // 끌고 가면, 사용자가 이미 다른 화면에 있는데 로그인으로 튕긴다.
                loginGuardJob?.cancel()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                _isLoading.value = false
                _pageUrl.value = url
                // 문서가 바뀔 때마다 다시 걸어야 한다. 새로고침하면 window 째로 사라진다.
                repairViewportUnits()
                resetScrollTop()
                outcomes.trySend(PageOutcome.Finished(url))
                // 메인에 닿았을 때만 로그인 여부를 본다. 다른 화면은 건드리지 않는다. (§27-2)
                if (KtxSelectors.isMainPage(url)) {
                    loginGuardJob = loginGuardScope.launch { guardMainPageLogin() }
                }
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

    /**
     * 시작 페이지를 연다. **WebView 가 높이를 얻은 뒤에** 연다. (DESIGN.md §38-10)
     *
     * 크기가 0 인 WebView 로 문서를 받으면 뷰포트 단위(`vh`)가 0 으로 잡히고,
     * 코레일의 역/날짜 선택 레이어는 `height:100vh` 라 통째로 납작해진다 —
     * 열려 있는데 높이 0 이라 사람 눈에는 "아무 반응 없음" 이다.
     *
     * 기다리는 곳이 여기인 이유는 **부르는 곳이 하나가 아니기 때문**이다.
     * 첫 실행(아직 `setContent` 전이라 붙지 않았다)과 설정 화면의 [시작 페이지로]
     * (그 화면이 떠 있는 동안 WebView 는 화면에서 떼어져 있다) 가 똑같이 위험하다.
     * Activity 쪽에 두면 한쪽만 막힌다.
     */
    override suspend fun loadStartUrl() {
        withContext(Dispatchers.Main) {
            awaitViewportSize()
            drainOutcomes()
            webView.loadUrl(startUrl)
        }
    }

    /**
     * WebView 가 화면에 붙어 높이를 가질 때까지 기다린다. 메인 스레드에서 부른다.
     *
     * 배치는 여러 번 일어날 수 있으므로 높이가 0 인 동안에는 듣기만 한다.
     * 끝내 크기가 생기지 않아도 [SIZE_WAIT_TIMEOUT_MS] 뒤에는 그냥 연다 —
     * 페이지가 아예 안 뜨는 것보다는 낫다. (대원칙 6)
     */
    private suspend fun awaitViewportSize() {
        if (isSized()) return
        withTimeoutOrNull(SIZE_WAIT_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(
                        view: View,
                        left: Int,
                        top: Int,
                        right: Int,
                        bottom: Int,
                        oldLeft: Int,
                        oldTop: Int,
                        oldRight: Int,
                        oldBottom: Int,
                    ) {
                        if (!isSized()) return
                        view.removeOnLayoutChangeListener(this)
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
                webView.addOnLayoutChangeListener(listener)
                continuation.invokeOnCancellation {
                    // 취소는 아무 스레드에서나 온다. 제거는 메인으로 돌린다.
                    webView.post { webView.removeOnLayoutChangeListener(listener) }
                }
            }
        }
    }

    /** 문서를 받아도 되는 크기인가. 떼어진 WebView 는 높이가 남아 있어도 안 된다. */
    private fun isSized(): Boolean = webView.isAttachedToWindow && webView.height > 0

    /**
     * 메인 화면에 닿았는데 **비로그인이면 로그인 화면으로 보낸다.** (DESIGN.md §27-2)
     *
     * 코레일은 로그인을 예매를 누른 **뒤에야** 요구한다(§27-1). 그래서 비로그인인 줄
     * 모른 채 조회부터 하다가, 좌석이 열린 그 순간에 로그인 화면으로 튕기는 일이 생긴다.
     * 그 자리에서 잃는 것이 가장 크므로 **아직 아무것도 안 한 메인에서** 미리 보낸다.
     *
     * 지키는 선:
     *  - **메인에서만.** 조회 결과 화면에서 URL 을 갈아타면 사용자가 넣어 둔 조회 조건이
     *    통째로 날아간다 (대원칙 4·5). [KtxSelectors.isMainPage] 가 참일 때만 불리고,
     *    그때도 목록이 그려져 있으면 그만둔다.
     *  - **확실할 때만.** [LoginState.LOGGED_OUT] 하나에만 반응한다. `UNKNOWN` 은 사이트
     *    개편으로 마커를 놓친 것일 수 있어 그대로 둔다 (대원칙 6).
     *  - **요청은 늘지 않는다.** 판정은 DOM 만 읽는다. 실제로 여는 것은 사용자가 눌러도
     *    갈 수 있는 로그인 화면 한 번뿐이다 (대원칙 2 와 무관한 경로다).
     *
     * 되풀이해 읽는 이유는 코레일이 React SPA 이기 때문이다. `onPageFinished` 는 문서를
     * 받은 시점이라 머리말이 아직 없을 수 있고(§38-9 와 같은 함정), 그때 한 번만 보면
     * 언제나 `UNKNOWN` 이 되어 이 확인이 통째로 죽는다.
     */
    private suspend fun guardMainPageLogin() {
        val script = KtxLoginScript.build()
        var check = LoginCheck(LoginState.UNKNOWN, "확인 전")

        var attempt = 0
        while (attempt < LOGIN_GUARD_MAX_TRIES) {
            if (attempt > 0) delay(LOGIN_GUARD_INTERVAL_MS)
            check = KtxLoginParser.parse(evaluate(script))
            if (check.state != LoginState.UNKNOWN) break
            attempt++
        }

        if (check.state != LoginState.LOGGED_OUT) {
            onLoginRedirect("메인 ${check.state} — 그대로 둔다 (${check.detail})")
            return
        }

        // 되튐 방지. 로그인 직후 사이트가 메인으로 돌려보냈는데 머리말이 아직 비로그인인
        // 채라면 main→login→main 을 무한히 오갈 수 있다.
        val now = System.currentTimeMillis()
        if (now - lastLoginRedirectAt < LOGIN_REDIRECT_COOLDOWN_MS) {
            onLoginRedirect("메인 비로그인이지만 방금 보냈다 — 건너뜀")
            return
        }

        // 목록이 떠 있으면 여기는 사용자가 조회해 둔 화면이다. 손대지 않는다.
        val kind = evaluateJson(KtxParserScript.buildPageKindScript())
        if (kind?.optBoolean("list") == true) {
            onLoginRedirect("메인인데 목록이 떠 있다 — 그대로 둔다")
            return
        }

        lastLoginRedirectAt = now
        onLoginRedirect("메인 비로그인 → 로그인 화면 (${check.detail})")
        withContext(Dispatchers.Main) {
            drainOutcomes()
            webView.loadUrl(KtxSelectors.LOGIN_URL)
        }
    }

    /**
     * 이 호스트가 벌여 둔 일을 정리한다. WebView 를 destroy 하기 **전에** 부른다.
     *
     * [loginGuardScope] 는 Activity 의 lifecycle 을 따르지 않으므로, 여기서 끊지 않으면
     * 이미 파괴된 WebView 에 스크립트를 던지게 된다.
     */
    fun dispose() {
        loginGuardScope.cancel()
    }

    /**
     * 이 문서의 `100vh` 가 깨졌으면 되살린다. (§38-10)
     *
     * 스크립트가 스스로 판단한다 — `100vh` 와 `innerHeight` 가 어긋날 때만 손댄다.
     * 멀쩡한 문서에서는 아무것도 하지 않으므로 조건 없이 매번 불러도 된다.
     */
    private fun repairViewportUnits() {
        webView.evaluateJavascript(KtxParserScript.buildViewportFixScript()) { raw ->
            val result = runCatching { JSONObject(raw.orEmpty()) }.getOrNull() ?: return@evaluateJavascript
            onViewportFix(describeViewportFix(result))
        }
    }

    /**
     * 목록을 맨 위에서 보게 한다. **메인 스레드에서 부른다.** (§38-9)
     *
     * 새로고침은 브라우저가 직전 스크롤 위치를 되살리는데, SPA 라 그 시점의 문서는
     * 아직 목록이 없어 짧다. 짧은 문서에 예전 오프셋을 되살리면 문서 끝에 붙고,
     * 이어서 목록이 그려져도 그 자리에 남아 **갱신할 때마다 화면이 맨 밑으로 튄다.**
     *
     * JS 쪽에서 되살리기를 끄고 올리며([KtxParserScript.buildScrollTopScript]),
     * 뷰의 스크롤도 함께 0 으로 둔다 — WebView 가 자체적으로 되살려 둔 오프셋이
     * 남아 있을 수 있다. 요청은 나가지 않는다.
     */
    private fun resetScrollTop() {
        webView.scrollTo(0, 0)
        webView.evaluateJavascript(KtxParserScript.buildScrollTopScript(), null)
    }

    /**
     * 페이지를 통째로 다시 불러 결과를 갱신한다. **브라우저의 F5 와 같다.** (§10, §38-9)
     *
     * 예전에는 결과 화면의 [열차조회] 버튼 좌표에 진짜 터치를 내려보냈다.
     * **모바일 폭에서는 그 버튼이 존재하지 않는다.** `div.btnWrap.btn_box` 가
     * `display:none` 이라 버튼의 rect 가 0×0 이고, 옆에 보이는 것은 눌러선 안 되는
     * `다음날 (…) 조회` 뿐이다. 누를 수 있는 버튼이 없으니 누르는 방식은 성립하지 않는다.
     *
     * 새로고침이 조회 조건을 날리지 않는 이유는 조건이 DOM 이 아니라
     * `localStorage["LS_TICKET_GENERAL"]` 에 들어 있기 때문이다. 다시 불러오면
     * SPA 가 그 값으로 같은 조회를 스스로 되풀이한다. (§38-9)
     *
     * 대신 값이 하나 비싸졌다. 한 사이클이 **문서 + 번들 + 조회 API** 전체가 되어
     * AJAX 재조회보다 요청이 훨씬 크다. 간격을 좁히지 말 것. (대원칙 2)
     */
    override suspend fun requery(
        timeoutMs: Long,
        settleTimeoutMs: Long,
        onClick: (String) -> Unit,
    ): PageOutcome {
        // 팝업이 열려 있으면 이번 차례는 아무것도 하지 않는다. 요청도 나가지 않는다.
        // 새로고침은 사용자가 팝업에서 고르던 것까지 통째로 날린다.
        if (isPopupOpen()) return PageOutcome.Deferred("팝업 창이 열려 있음")

        // 사람이 볼 수 있을 때만 갱신한다. 화면에 없는 WebView 를 새로고침하는 것은
        // 사람의 조작에서 나올 수 없는 요청이다. (대원칙 1 의 따름정리)
        val surface = readSurface()
        if (!surface.usable) {
            return PageOutcome.NotTappable("WebView 가 화면에 없음 (${surface.describe()})")
        }

        drainOutcomes()
        navigationStarted = false

        // 새로고침 전 목록 상태. 갱신되었는지 비교하는 기준이자 로그 재료다.
        val before = evaluateJson(KtxParserScript.buildPageKindScript())
        val beforeSig = before?.optString("sig").orEmpty()

        // 나가는 이력 항목에 걸어 둔다. 새로고침이 시작된 뒤에는 늦다. (§38-9)
        evaluate(KtxParserScript.buildScrollTopScript())

        withContext(Dispatchers.Main) { webView.reload() }
        onClick(describeReload(before, surface))

        return awaitReloaded(timeoutMs, settleTimeoutMs, beforeSig)
    }

    /**
     * 새로고침이 **목록이 다시 그려진 상태**까지 갔는지 기다린다.
     *
     * 두 단계로 나뉜다. 한 단계로 합칠 수 없다.
     *  1. `onPageFinished` — 문서를 받은 시점. 여기서는 아직 목록이 없다.
     *  2. 목록 렌더링 — 코레일은 React SPA 라 문서를 받은 **뒤에** 번들이 돌고
     *     조회 API 를 쳐서 `li.tckList` 를 그린다. 1 에서 바로 분석하면
     *     좌석이 있어도 `NO_TRAIN` 으로 읽는다.
     *
     * 2 를 못 보고 시간이 다 되어도 실패로 단정하지 않는다. 결과가 0건인 조회일 수도
     * 있어서, 분석은 한 번 해 보게 [PageOutcome.Settled] 로 넘긴다. (대원칙 6)
     */
    /**
     * `onPageFinished` 하나만 기다린다.
     *
     * [PageOutcome.Finished] / [PageOutcome.Failed] 를 그대로 돌려주고,
     * 시간이 다 되면 [PageOutcome.Settled] 를 돌려준다.
     */
    private suspend fun awaitPageFinished(timeoutMs: Long): PageOutcome {
        val startedAt = System.currentTimeMillis()
        while (true) {
            when (val event = outcomes.tryReceive().getOrNull()) {
                is PageOutcome.Failed -> return event
                is PageOutcome.Finished -> return event
                else -> Unit
            }
            if (System.currentTimeMillis() - startedAt >= timeoutMs) {
                return PageOutcome.Settled("새로고침 후 로딩이 끝나지 않음")
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun awaitReloaded(
        timeoutMs: Long,
        settleTimeoutMs: Long,
        beforeSig: String,
    ): PageOutcome {
        // 1) 문서 로딩이 끝나기를 기다린다. reload 는 반드시 화면 전환을 만든다.
        val loaded = awaitPageFinished(timeoutMs)
        if (loaded !is PageOutcome.Finished) return loaded
        val loadedUrl = loaded.detail

        // 2) SPA 가 목록을 다시 그리기를 기다린다. 읽기만 하므로 요청은 나가지 않는다.
        val renderStartedAt = System.currentTimeMillis()
        var last = "목록 확인 결과 없음"
        while (true) {
            (outcomes.tryReceive().getOrNull() as? PageOutcome.Failed)?.let { return it }

            val kind = evaluateJson(KtxParserScript.buildPageKindScript())
            if (kind != null) {
                val rows = kind.optInt("rows")
                val sig = kind.optString("sig")
                last = "list=${kind.optBoolean("list")} rows=$rows sig=$beforeSig→$sig"
                if (kind.optBoolean("list") && rows > 0) {
                    // 렌더링이 끝나기 전에 읽지 않도록 잠깐 기다린다.
                    delay(SETTLE_GRACE_MS)
                    // 목록이 그려지며 문서 높이가 늘어난 뒤 한 번 더. `onPageFinished`
                    // 시점에는 문서가 짧아 그때 올려 둔 것만으로는 부족하다. (§38-9)
                    withContext(Dispatchers.Main) { resetScrollTop() }
                    return PageOutcome.Updated("새로고침 $last")
                }
            }

            if (System.currentTimeMillis() - renderStartedAt >= settleTimeoutMs) {
                return PageOutcome.Settled("$last / url=${loadedUrl.takeLast(60)}")
            }
            delay(POLL_INTERVAL_MS)
        }
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

    /**
     * 역 선택 창 진단. (§38-10)
     *
     * 세 가지가 전부 "아무 반응 없음" 으로 보이는데, 구분하지 못하면 고칠 수 없다.
     *  - 탭 기록 자체가 없다 → 손가락이 그 요소에 닿지 않았다
     *  - `역버튼` 인데 `핸들러=안돎` → 이벤트가 React 까지 가지 못했다
     *  - `핸들러=돎` 인데 `모달=0→0` → 사이트가 일부러 막았다 (`stationDisabled`)
     *  - `모달=0→1` → 창은 만들어졌다. 남은 것은 보이느냐의 문제다
     */
    override suspend fun probeStationPopup(): String {
        val probe = evaluateJson(KtxParserScript.buildStationProbeScript())
            ?: return "스크립트가 돌지 않음 (페이지가 아직 안 떴을 수 있음)"
        return describeStationProbe(probe)
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
     * `100vh` 보정 결과를 로그 한 줄로. (§38-10)
     *
     * `보정함 100vh=0 → 460` 이면 이 WebView 의 뷰포트 단위가 깨져 있었다는 뜻이고,
     * `정상 100vh=460` 이면 손댈 것이 없었다는 뜻이다. 역 선택 창이 여전히 안 뜬다면
     * 원인이 다른 곳이라는 것을 이 줄로 가른다.
     */
    private fun describeViewportFix(result: JSONObject): String {
        val vh = result.optInt("vh", -1)
        val ih = result.optInt("ih", -1)
        return if (result.optBoolean("applied")) {
            "보정함 100vh=$vh → ${ih}px"
        } else {
            "손대지 않음(${result.optString("reason")}) 100vh=$vh innerHeight=$ih"
        }
    }

    /** [probeStationPopup] 의 결과를 로그 한 줄로 만든다. 사람이 읽을 것이다. */
    private fun describeStationProbe(probe: JSONObject): String = buildString {
        append("rows=").append(probe.optInt("rows"))
        append(" 모달=").append(probe.optInt("modals"))
        probe.optString("topModal").takeIf { it.isNotBlank() }?.let {
            append(" 맨위=\"").append(it).append("\"")
        }
        probe.optJSONArray("view")?.let {
            append(" 뷰=").append(it.optInt(0)).append("x").append(it.optInt(1))
        }
        // 코레일 레이어는 `height: 100vh` 다. innerHeight 와 어긋나면 그것이 원인이다.
        append(" 100vh=").append(probe.optInt("vh100", -1))
        append(" cH=").append(probe.optInt("clientH", -1))

        val buttons = probe.optJSONArray("buttons")
        if (buttons == null || buttons.length() == 0) {
            append(" | 역 버튼 없음")
        } else {
            for (i in 0 until buttons.length()) {
                val button = buttons.optJSONObject(i) ?: continue
                append(" | ").append(button.optString("t").ifBlank { "역버튼$i" })
                append(" ").append(button.optJSONArray("box"))
                if (!button.optBoolean("vis")) append(" 숨김")
                if (button.optBoolean("covered")) {
                    append(" 가려짐(").append(button.optString("hit")).append(")")
                }
            }
        }

        probe.optJSONObject("modal")?.let { append(describeOpenModal(it)) }

        val taps = probe.optJSONArray("taps")
        if (taps == null || taps.length() == 0) {
            append(" | 탭 기록 없음 — 역 버튼을 눌러 본 뒤 다시 진단")
            return@buildString
        }
        for (i in 0 until taps.length()) {
            val tap = taps.optJSONObject(i) ?: continue
            append(" | 탭 ").append(tap.optString("on"))
            append(if (tap.optBoolean("station")) " 역버튼" else " 딴곳")
            if (!tap.optBoolean("trusted")) append(" 합성")
            append(" 핸들러=").append(handlerVerdict(tap))
            append(" 모달=").append(tap.optInt("before")).append("→").append(tap.optInt("after"))
        }
    }

    /**
     * 떠 있는데 안 보이는 모달을 설명한다. (§38-10, 4번 갈래)
     *
     * 여기까지 왔다면 탭도 핸들러도 정상이다. 남은 것은 **왜 안 그려지는가** 뿐이라,
     * 보이지 않게 만들 수 있는 것만 본다: 상자 / 스타일 / 실제로 맨 위에 있는지
     * (`덮임`) / 조상 사슬. 조상을 보는 이유는 `position:fixed` 의 기준이
     * `transform` 을 가진 조상에게 가로채이면 화면 밖으로 나가기 때문이다.
     */
    private fun describeOpenModal(modal: JSONObject): String = buildString {
        append(" | 모달 ").append(modal.optJSONArray("box"))
        append(" ").append(modal.optString("css"))
        modal.optJSONArray("contentBox")?.let { append(" 내용").append(it) }
        modal.optString("contentCss").takeIf { it.isNotBlank() }?.let { append(" ").append(it) }
        if (!modal.optBoolean("inside")) {
            append(" 덮임(").append(modal.optString("hit")).append(")")
        }
        modal.optJSONArray("scroll")?.let {
            if (it.optInt(0) != 0 || it.optInt(1) != 0) append(" 스크롤").append(it)
        }
        modal.optString("chain").takeIf { it.isNotBlank() }?.let { append(" 조상 ").append(it) }
    }

    /**
     * 그 탭에서 사이트의 `onClick` 이 돌았는지.
     *
     * 코레일 핸들러는 첫 줄이 `e.preventDefault()` 라 **돌기만 하면 반드시 참**이 된다.
     * 아직 판정 전(`null`)인 것은 진단을 너무 빨리 두 번 누른 경우다.
     */
    private fun handlerVerdict(tap: JSONObject): String = when {
        tap.isNull("prevented") -> "판정전"
        tap.optBoolean("prevented") -> "돎"
        else -> "안돎"
    }

    /** 무엇을 새로고침했는지. 갱신이 안 될 때 원인을 좁히는 데 쓴다. */
    private fun describeReload(before: JSONObject?, surface: Surface): String = buildString {
        append("새로고침(F5)")
        if (before != null) {
            append(" 이전 rows=").append(before.optInt("rows"))
            append(" sig=").append(before.optString("sig"))
            append(" url=").append(before.optString("url").takeLast(60))
        } else {
            append(" 이전 상태를 읽지 못함")
        }
        append(" / webview=").append(surface.describe())
    }

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

        /**
         * WebView 가 배치되기를 기다리는 상한. (§38-10)
         * 화면 전환 한 프레임이면 충분한데, 넉넉히 두어도 첫 로딩만 늦어진다.
         */
        const val SIZE_WAIT_TIMEOUT_MS = 3_000L

        /**
         * 메인 화면 로그인 판정을 다시 읽어 보는 횟수와 간격. (§27-2)
         *
         * SPA 가 머리말을 그릴 때까지만 기다리면 된다. 요청이 나가지 않는 읽기라
         * 늘려도 사이트에는 부담이 없지만, 판정이 늦으면 사용자가 이미 메인에서
         * 무언가를 하고 있는데 화면이 바뀐다. 2초 안에 끝낸다.
         */
        const val LOGIN_GUARD_MAX_TRIES = 10
        const val LOGIN_GUARD_INTERVAL_MS = 200L

        /**
         * 로그인 화면으로 보낸 뒤 다시 보내기까지의 최소 간격. (§27-2)
         *
         * 판정이 어긋나 main↔login 을 무한히 오가는 것만 끊는 값이다. 사용자가 로그인하지
         * 않고 뒤로 돌아온 경우에는 한 번 그냥 통과한다 — 가두는 것이 목적이 아니다.
         */
        const val LOGIN_REDIRECT_COOLDOWN_MS = 5_000L
    }
}
