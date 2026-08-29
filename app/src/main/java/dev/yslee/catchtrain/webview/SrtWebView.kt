package dev.yslee.catchtrain.webview

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * SRT 페이지를 표시할 WebView 생성. (DESIGN.md §12)
 *
 * User-Agent 는 기본 Android WebView 값을 그대로 사용한다.
 * 로그인/세션은 사용자가 WebView 안에서 직접 처리하며, 앱은 쿠키를 보관만 한다.
 *
 * [create] 는 메인 WebView 와 팝업 창(자식 WebView) 양쪽에서 쓴다.
 * 팝업도 같은 설정이어야 하는 이유는 [SrtPopupHost] 주석에 적어 두었다.
 */
object SrtWebViewFactory {

    fun create(context: Context): WebView = WebView(context).also { configure(it) }

    /**
     * 메인/팝업 공통 설정.
     *
     * [WebSettings.setSupportMultipleWindows] 가 true 인 것이 중요하다.
     * false 면 `window.open()` 이 **같은 WebView 의 일반 이동**으로 처리되어
     * `window.opener` 가 null 이 되고, 팝업이 부모 창을 조작하는 페이지가
     * 통째로 동작하지 않는다. (SRT 운행일자 선택 달력이 정확히 이 경우다)
     *
     * true 로 두면 모든 팝업이 [SrtPopupHost] 의 `onCreateWindow` 로 들어온다.
     * 거기서 처리하지 않으면 `window.open()` 은 조용히 실패한다.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.isVerticalScrollBarEnabled = true
        webView.isHorizontalScrollBarEnabled = false
    }

    /** 쿠키를 디스크에 반영한다. Activity 가 백그라운드로 갈 때 호출한다. */
    fun flushCookies() {
        CookieManager.getInstance().flush()
    }
}
