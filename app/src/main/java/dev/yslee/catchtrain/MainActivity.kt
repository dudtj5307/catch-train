package dev.yslee.catchtrain

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.yslee.catchtrain.notification.NotificationHelper
import dev.yslee.catchtrain.ui.MainScreen
import dev.yslee.catchtrain.ui.theme.CatchTrainTheme
import dev.yslee.catchtrain.viewmodel.WatchViewModel
import dev.yslee.catchtrain.webview.KtxPopupHost
import dev.yslee.catchtrain.webview.KtxWebViewFactory
import dev.yslee.catchtrain.webview.KtxWebViewHost
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 단일 Activity. WebView 인스턴스를 소유한다.
 *
 * WebView 를 Activity 가 들고 있으므로 화면 전환(설정 화면 등)에도
 * 로그인 세션과 현재 페이지가 유지된다. (DESIGN.md §12, §24)
 */
class MainActivity : ComponentActivity() {

    private val viewModel: WatchViewModel by viewModels()

    private lateinit var webView: WebView
    private lateinit var host: KtxWebViewHost
    private lateinit var popupHost: KtxPopupHost

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 결과는 무시 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 앱은 항상 다크다. 시스템 바를 투명하게 두고 아이콘을 밝게 고정한다.
        // 헤더/하단바가 이미 statusBarsPadding, navigationBarsPadding 으로
        // 인셋을 피하고 있어서 edge-to-edge 로 켜도 레이아웃은 그대로다.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )

        webView = KtxWebViewFactory.create(this)
        // 팝업(달력 등)이 떠 있는 동안에는 자동 조회를 하지 않는다. (KtxPopupHost 주석 참고)
        popupHost = KtxPopupHost(webView)
        host = KtxWebViewHost(webView, isPopupOpen = { popupHost.isOpen })
        viewModel.attachHost(host)

        requestNotificationPermissionIfNeeded()
        keepScreenOnWhileWatching()
        handleNotificationIntent(intent)

        if (savedInstanceState == null) {
            lifecycleScope.launch { host.loadStartUrl() }
        }

        setContent {
            CatchTrainTheme {
                val isLoading by host.isLoading.collectAsStateWithLifecycle()
                val pageUrl by host.pageUrl.collectAsStateWithLifecycle()
                val popups by popupHost.popups.collectAsStateWithLifecycle()

                MainScreen(
                    viewModel = viewModel,
                    webView = webView,
                    isPageLoading = isLoading,
                    pageUrl = pageUrl,
                    onReloadStartPage = {
                        lifecycleScope.launch { host.loadStartUrl() }
                    },
                    // 창이 겹쳐 열렸다면 맨 위 하나만 보여 준다.
                    popup = popups.lastOrNull(),
                    onClosePopup = { popupHost.closeTop() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    /**
     * 알림을 눌러 들어온 경우. (§19, §19-3)
     *
     * 그냥 눌렀다면 화면 복귀 자체가 목적이므로 좌석 발견 알림만 정리한다.
     * [알림 끄기] 를 눌렀다면 결제 재촉 알림도 멈춘다. 이때 감시 상태는 건드리지
     * 않는다 — 사용자는 알림만 조용히 시키고 결제를 이어서 해야 한다.
     *
     * 앱이 죽어 있다가 알림으로 살아난 경우에는 onCreate 의 intent 로 들어오므로
     * 양쪽에서 모두 부른다.
     */
    private fun handleNotificationIntent(intent: Intent) {
        if (!intent.getBooleanExtra(NotificationHelper.EXTRA_FROM_NOTIFICATION, false)) return
        if (intent.getBooleanExtra(NotificationHelper.EXTRA_STOP_ALERT, false)) {
            viewModel.silenceReserveAlert()
        }
        NotificationHelper(this).cancelAll()
        // 같은 intent 가 재사용되어 두 번 처리되지 않도록 표시를 지운다.
        intent.removeExtra(NotificationHelper.EXTRA_FROM_NOTIFICATION)
        intent.removeExtra(NotificationHelper.EXTRA_STOP_ALERT)
    }

    /** foreground 로 돌아오면 감시를 재개한다. (§24) */
    override fun onStart() {
        super.onStart()
        webView.onResume()
        webView.resumeTimers()
        popupHost.onResume()
        viewModel.onHostResumed()
    }

    /** background 로 가면 감시를 일시정지한다. MVP 는 백그라운드 감시를 하지 않는다. (§25) */
    override fun onStop() {
        viewModel.onHostPaused()
        popupHost.onPause()
        webView.onPause()
        webView.pauseTimers()
        KtxWebViewFactory.flushCookies()
        super.onStop()
    }

    override fun onDestroy() {
        // 팝업이 부모보다 먼저 정리되어야 한다.
        popupHost.destroy()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    /**
     * 감시 중에는 화면이 꺼지지 않게 한다.
     *
     * 이 앱은 화면에 그려진 [조회하기] 버튼을 실제 좌표로 눌러 갱신하므로,
     * 화면이 꺼지면 Activity 가 ON_STOP 이 되어 감시가 그대로 멈춘다. (§24, §26)
     * 화면 시간 초과로 감시가 끊기는 것을 막기 위한 것이다.
     *
     * 좌석을 발견한 뒤(MATCHED / RESERVED)에도 유지한다.
     * 예약 화면은 제한 시간이 있어서 그 순간 화면이 꺼지면 곤란하다.
     *
     * lifecycleScope 는 ON_DESTROY 에서 취소되고 이 플래그는 창이 보일 때만
     * 효력이 있으므로, 따로 해제하는 경로를 두지 않는다.
     */
    private fun keepScreenOnWhileWatching() {
        lifecycleScope.launch {
            viewModel.status
                .map { it.state.keepsScreenOn }
                .distinctUntilChanged()
                .collect { keepOn ->
                    if (keepOn) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
