package dev.yslee.catchtrain.webview

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * `window.open()` 으로 열리는 팝업 창을 **진짜 별도 창**으로 띄운다. (DESIGN.md §12, §22)
 *
 * ### 왜 필요한가
 *
 * 아래는 **SRT 에서 실측했던 사례**다. 코레일 달력이 같은 방식인지는 확인하지 않았지만,
 * `opener` 가 끊기면 팝업이 통째로 죽는다는 성질은 `window.open` 을 쓰는 페이지 공통이라
 * 근거로 그대로 남긴다. SRT 메인의 출발일 달력은 이렇게 동작했다.
 *
 * ```
 * main.do             : selectCalendarInfo() → Common.openWin(...) → window.open(달력URL, ...)
 * selectCalendarInfo.do: var target = parent.g_IFrame ? parent : opener;
 *                        function selectDateInfo(date) {
 *                            var o = target.$('search-form');   // ← opener 가 없으면 여기서 죽는다
 *                            o.dptDt.value = 'YYYY.MM.DD';
 *                            o.dptTm.value = '000000';
 *                            target.changeByOptionText(o.dptTm);
 *                            window.close();
 *                        }
 * ```
 *
 * 즉 팝업이 **부모 창의 폼을 직접 고쳐서** 날짜를 돌려준다.
 * `setSupportMultipleWindows(false)` 였을 때는 `window.open` 이 같은 WebView 의
 * 일반 이동으로 처리되어 `opener` 가 null 이 되고, `target.$(...)` 에서 TypeError 가
 * 난 채로 멈춘다. (그 줄이 try 블록 밖이라 `window.close()` 까지 못 간다)
 * 날짜를 눌러도 아무 반응이 없던 원인이다.
 *
 * ### 반드시 지켜야 하는 것
 *
 * `opener` 연결은 [WebChromeClient.onCreateWindow] 가 넘겨주는
 * [WebView.WebViewTransport] 에 자식 WebView 를 꽂아 줄 때만 유지된다.
 * `shouldOverrideUrlLoading` 으로 URL 만 가로채서 다른 WebView 에 `loadUrl` 하면
 * `opener` 는 여전히 null 이라 **똑같이 깨진다.** 그래서 여기서는
 * "처리 못 하겠으면 부모 WebView 에 그냥 띄운다" 같은 폴백을 두지 않는다.
 *
 * ### 창 스택
 *
 * 팝업이 또 팝업을 여는 경우(결제/본인확인 등)가 있어 스택으로 들고 있는다.
 * 화면에는 항상 맨 위 창만 보인다. [MAX_POPUPS] 를 넘으면 열지 않는다.
 */
class KtxPopupHost(
    private val parent: WebView,
    private val createWebView: (Context) -> WebView = { KtxWebViewFactory.create(it) },
) {

    /** 화면에 띄울 팝업 창 하나. */
    data class PopupWindow(
        val webView: WebView,
        val title: String,
        val url: String?,
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _popups = MutableStateFlow<List<PopupWindow>>(emptyList())

    /** 열려 있는 팝업 스택. 화면에는 마지막 항목만 보여 준다. */
    val popups: StateFlow<List<PopupWindow>> = _popups.asStateFlow()

    /**
     * 팝업이 하나라도 열려 있는지.
     *
     * 감시 루프가 이 값을 본다. 자동 조회는 WebView 위젯 좌표에 MotionEvent 를
     * 직접 내려보내므로, 팝업 오버레이가 덮여 있어도 뒤쪽 [조회하기] 가 그대로
     * 눌린다. 사용자가 팝업을 보고 있는 동안 조회가 나가면 안 되므로 여기서 막는다.
     * ([KtxWebViewHost] 의 `isPopupOpen`)
     */
    val isOpen: Boolean get() = _popups.value.isNotEmpty()

    init {
        parent.webChromeClient = chromeClient()
    }

    /** 사용자가 [닫기]/뒤로가기로 맨 위 창을 닫는다. 닫을 창이 없으면 false. */
    fun closeTop(): Boolean {
        val top = _popups.value.lastOrNull() ?: return false
        remove(top.webView)
        return true
    }

    /** Activity 가 백그라운드로 갈 때. */
    fun onPause() {
        _popups.value.forEach {
            it.webView.onPause()
            it.webView.pauseTimers()
        }
    }

    /** Activity 가 돌아올 때. */
    fun onResume() {
        _popups.value.forEach {
            it.webView.onResume()
            it.webView.resumeTimers()
        }
    }

    /** Activity 종료. 열려 있던 창을 모두 정리한다. */
    fun destroy() {
        val open = _popups.value
        _popups.value = emptyList()
        open.forEach { detachAndDestroy(it.webView) }
    }

    // ---------------------------------------------------------------- 창 생성

    /**
     * 부모/자식 모두에 같은 ChromeClient 를 건다.
     * 자식이 또 창을 열 수 있고, 자식의 `window.close()` 도 받아야 한다.
     */
    private fun chromeClient(): WebChromeClient = object : WebChromeClient() {

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message,
        ): Boolean {
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            if (_popups.value.size >= MAX_POPUPS) return false

            val child = createWebView(view.context).apply {
                webViewClient = childWebViewClient()
                webChromeClient = chromeClient()
            }

            // 창 목록에 먼저 올려 두면 Compose 가 다음 프레임에 화면에 붙인다.
            // sendToTarget 은 메시지 큐를 거치므로 붙기 전에 로딩이 시작돼도 문제없다.
            _popups.value = _popups.value + PopupWindow(child, DEFAULT_TITLE, null)

            transport.webView = child
            resultMsg.sendToTarget()
            return true
        }

        /** 팝업 스스로 `window.close()` 를 부른 경우. 달력의 날짜 선택/[닫기] 가 여기로 온다. */
        override fun onCloseWindow(window: WebView) {
            remove(window)
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            if (title.isNullOrBlank()) return
            update(view) { it.copy(title = title) }
        }
    }

    private fun childWebViewClient(): WebViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            update(view) { it.copy(url = url) }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            update(view) { it.copy(url = url, title = view.title ?: it.title) }
        }
    }

    // ---------------------------------------------------------------- 목록 관리

    private fun update(webView: WebView, transform: (PopupWindow) -> PopupWindow) {
        val current = _popups.value
        val index = current.indexOfFirst { it.webView === webView }
        if (index < 0) return
        _popups.value = current.toMutableList().also { it[index] = transform(it[index]) }
    }

    /**
     * 창 하나를 닫는다.
     *
     * 파괴는 다음 메시지 루프로 미룬다. 이 함수는 팝업이 스스로 부른 `window.close()`
     * (= 그 WebView 자신의 콜백) 안에서 호출되는데, 콜백이 아직 스택에 남아 있는 상태로
     * `destroy()` 를 부르면 안 되기 때문이다. 목록에서는 즉시 빠지므로 화면과
     * [isOpen] 은 바로 반영된다.
     */
    private fun remove(webView: WebView) {
        val current = _popups.value
        val target = current.firstOrNull { it.webView === webView } ?: return
        _popups.value = current.filterNot { it.webView === webView }
        mainHandler.post { detachAndDestroy(target.webView) }
    }

    /**
     * 화면에서 떼어낸 뒤 파괴한다. 순서가 바뀌면 안 된다.
     *
     * Compose 가 오버레이를 지우면서 이미 떼어냈을 수도 있어 부모가 없어도 그냥 넘어간다.
     */
    private fun detachAndDestroy(webView: WebView) {
        webView.stopLoading()
        webView.webChromeClient = null
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    private companion object {
        /** 팝업이 팝업을 여는 경우까지만 본다. 그 이상은 정상적인 흐름이 아니다. */
        const val MAX_POPUPS = 3

        const val DEFAULT_TITLE = "코레일"
    }
}
