package com.example.srtwatcher.ui

import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.srtwatcher.ui.components.Hairline
import com.example.srtwatcher.ui.components.PanelCard
import com.example.srtwatcher.ui.components.StatusDot
import com.example.srtwatcher.ui.theme.BrandTitle
import com.example.srtwatcher.ui.theme.MonoUrl
import com.example.srtwatcher.viewmodel.WatchViewModel
import com.example.srtwatcher.watcher.WatchState
import com.example.srtwatcher.webview.SrtPopupHost
import kotlinx.coroutines.delay

/**
 * 앱 메인 화면. (DESIGN.md §21, §22)
 *
 * 구조(위 → 아래):
 *   헤더           ← 한 줄로 압축. [● 상태 · 간격] + [펼치기] + [설정]
 *   (펼침) 상세    ← 주소 / 조건 요약 / 감시 상태
 *   SRT WebView   ← 감시 중 주기적으로 [조회하기] 가 눌린다
 *   하단 컨트롤    ← 제스처 바에 가리지 않도록 아래 여백 확보
 *
 * 설정 화면은 오버레이 대신 화면 교체로 띄운다. WebView 는 Activity 가 소유하므로
 * 화면이 바뀌어도 로그인 세션과 페이지가 유지된다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: WatchViewModel,
    webView: WebView,
    isPageLoading: Boolean,
    pageUrl: String?,
    onReloadStartPage: () -> Unit,
    popup: SrtPopupHost.PopupWindow?,
    onClosePopup: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()

    var showSettings by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(true) }
    var showLogs by remember { mutableStateOf(false) }
    // 열차 선택이 이 앱의 출발점이라 처음부터 펼쳐 둔다.
    var showTrains by remember { mutableStateOf(true) }
    val snackbarHostState = remember { SnackbarHostState() }

    // 페이지가 바뀔 때마다 목록을 자동으로 다시 읽는다. (DESIGN.md §19)
    // 조회 요청을 보내는 것이 아니라 이미 그려진 화면을 읽기만 하므로 차단 위험이 없다.
    // 표가 그려질 틈을 주려고 조금 기다린다. 그 사이 또 화면이 바뀌면 이 대기는
    // 취소되고 새 주소로 다시 걸린다.
    LaunchedEffect(pageUrl, isPageLoading) {
        if (isPageLoading || pageUrl == null) return@LaunchedEffect
        delay(AUTO_SCAN_DELAY_MS)
        viewModel.refreshTrainList(quiet = true)
    }

    LaunchedEffect(toast) {
        val message = toast
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeToast()
        }
    }

    if (showSettings) {
        BackHandler { showSettings = false }
        SettingsScreen(
            settings = settings,
            viewModel = viewModel,
            onGoHome = {
                onReloadStartPage()
                showSettings = false
            },
            onClose = { showSettings = false },
        )
        return
    }

    BackHandler(enabled = webView.canGoBack()) { webView.goBack() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppHeader(
                state = status.state,
                intervalLabel = settings.intervalLabel,
                pageUrl = pageUrl,
                expanded = expanded,
                onToggleExpand = { expanded = !expanded },
                onOpenSettings = { showSettings = true },
            )
        },
        bottomBar = {
            ControlBar(
                state = status.state,
                onStart = viewModel::startWatching,
                onStop = viewModel::stopWatching,
                onRetry = viewModel::retry,
                onOpenLogs = {
                    showLogs = !showLogs
                    if (showLogs) showTrains = false
                },
                onOpenTrains = {
                    showTrains = !showTrains
                    if (showTrains) {
                        showLogs = false
                        // 창을 여는 것 자체가 "지금 뭐가 있는지 보자" 는 뜻이다.
                        viewModel.refreshTrainList(quiet = true)
                    }
                },
                logsVisible = showLogs,
                trainsVisible = showTrains,
                hasSelection = !selection.isEmpty,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 1) 페이지 로딩 표시
                if (isPageLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }

                // 2) 상세 패널. 헤더의 펼치기 토글로 여닫는다.
                if (expanded) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SelectionSummaryCard(
                            selection = selection,
                            searchSummary = searchSummaryOf(status.trains, status.searchDate),
                            onEdit = {
                                showTrains = true
                                showLogs = false
                                viewModel.refreshTrainList(quiet = true)
                            },
                        )
                        StatusCard(status = status)
                    }
                }

                // 3) 좌석 발견 / 예약하기 누름 카드는 접힌 상태에서도 항상 보여준다.
                //    이 앱이 내놓는 결과물이라 패널에 가려지면 안 된다.
                val reserve = status.reserve
                if (status.state == WatchState.MATCHED && status.matches.isNotEmpty()) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        MatchedCard(
                            matches = status.matches,
                            reserve = reserve,
                            onContinue = viewModel::continueWatching,
                            onStop = viewModel::stopWatching,
                        )
                    }
                } else if (status.state == WatchState.RESERVED && reserve != null) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        ReservedCard(
                            reserve = reserve,
                            alerting = status.reserveAlerting,
                            onSilence = viewModel::silenceReserveAlert,
                            onStop = viewModel::stopWatching,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Hairline()

                // 4) WebView. SRT 페이지는 흰 배경 그대로 둔다.
                AndroidView(
                    factory = {
                        // 이전 화면에 붙어 있던 경우 부모에서 떼어낸다.
                        (webView.parent as? ViewGroup)?.removeView(webView)
                        webView
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }

            // 열차 선택 / 로그 창. 흐름 배치로 두면 SRT 화면을 아래로 밀어내므로,
            // 화면 아래(= 조작 버튼 바로 위)에 붙여 띄우고 WebView 위에 얹는다.
            // 손이 닿는 곳에서 열리고, 뒤쪽 화면 위치가 흔들리지 않는다.
            if (showTrains || showLogs) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        // 카드 여백에 떨어진 터치가 뒤쪽 WebView 로 새지 않게 막는다.
                        .pointerInput(Unit) { detectTapGestures { } },
                ) {
                    if (showTrains) {
                        TrainSelectPanel(
                            trains = status.trains,
                            selection = selection,
                            scanning = scanning,
                            onRefresh = { viewModel.refreshTrainList() },
                            onToggle = viewModel::toggleSeat,
                            onClearSelection = viewModel::clearSelection,
                        )
                    }
                    if (showLogs) {
                        LogPanel(logs = logs, onClear = viewModel::clearLogs)
                    }
                }
            }
        }
    }

    // 팝업(달력 등)은 Scaffold 위에 얹는다. 같은 부모의 뒤쪽 형제라 위에 그려진다.
    if (popup != null) {
        BackHandler { onClosePopup() }
        PopupOverlay(popup = popup, onClose = onClosePopup)
    }
}

/**
 * `window.open()` 으로 열린 창을 화면 가운데 띄운다. (DESIGN.md §12, §22)
 *
 * WebView 자체는 [SrtPopupHost] 가 만들어 `opener` 연결까지 끝낸 것을 그대로 붙인다.
 * 여기서 새로 만들거나 URL 만 다시 로드하면 `opener` 가 끊겨 날짜 선택이 동작하지 않는다.
 *
 * 바깥 스크림을 누르면 닫힌다. 뒤로가기는 [MainScreen] 이 받는다.
 */
@Composable
private fun PopupOverlay(
    popup: SrtPopupHost.PopupWindow,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = SCRIM_ALPHA))
            // clickable 대신 pointerInput 을 쓴다. 물결 효과나 포커스를 만들지 않고
            // 뒤쪽 WebView 로 터치가 새는 것만 막으면 된다.
            .pointerInput(Unit) { detectTapGestures { onClose() } }
            .systemBarsPadding()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        PanelCard(
            modifier = Modifier.fillMaxHeight(POPUP_HEIGHT_RATIO),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = popup.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = popup.url?.take(MAX_URL_CHARS) ?: "",
                        style = MonoUrl,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(
                    onClick = onClose,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Text("닫기")
                }
            }
            Hairline()
            AndroidView(
                factory = {
                    (popup.webView.parent as? ViewGroup)?.removeView(popup.webView)
                    popup.webView
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }
}

/**
 * 화면 맨 위 헤더. (DESIGN.md §22)
 *
 * 접힘 : ● 다음 확인 대기 · 1.0~3.0초            [v] [설정]
 * 펼침 : 위 한 줄 + SRT WATCHER / 현재 주소
 *
 * 예전에 패널 아래에 따로 있던 [패널 접기/펼치기] 버튼을 여기로 흡수했다.
 * 여닫는 컨트롤이 한 곳에만 있어야 헤더 높이가 예측 가능하다.
 *
 * [SRT 홈] 버튼이 있던 자리는 [설정] 이 가져갔다. 시작 페이지로 돌아가는 기능은
 * 설정 화면의 "페이지" 섹션으로 옮겼다.
 */
@Composable
private fun AppHeader(
    state: WatchState,
    intervalLabel: String,
    pageUrl: String?,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .statusBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(state = state)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = state.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    text = "  ·  $intervalLabel",
                    style = MonoUrl,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onToggleExpand,
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Icon(
                        imageVector = if (expanded) {
                            Icons.Filled.KeyboardArrowUp
                        } else {
                            Icons.Filled.KeyboardArrowDown
                        },
                        contentDescription = if (expanded) "상세 접기" else "상세 펼치기",
                    )
                }
                IconButton(
                    onClick = onOpenSettings,
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "설정",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            if (expanded) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "SRT WATCHER",
                        style = BrandTitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = pageUrl?.take(MAX_URL_CHARS) ?: "페이지 없음",
                        style = MonoUrl,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.shapes.extraSmall,
                            )
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                }
            }

            Hairline()
        }
    }
}

@Composable
private fun ControlBar(
    state: WatchState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenTrains: () -> Unit,
    logsVisible: Boolean,
    trainsVisible: Boolean,
    hasSelection: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Hairline()
        Column(
            modifier = Modifier
                // 제스처 내비게이션 바(홈 바)에 버튼이 가리지 않도록 시스템 여백을 확보한다.
                .navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = BOTTOM_SAFE_PADDING),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 창이 열렸는지가 아니라 "고른 칸이 있는지" 를 테두리로 알린다.
                // 감시를 시작하기 전에 확인해야 하는 건 그쪽이다.
                GhostButton(
                    text = if (trainsVisible) "열차 숨기기" else "열차 선택",
                    onClick = onOpenTrains,
                    active = hasSelection,
                    modifier = Modifier.weight(1f),
                )
                GhostButton(
                    text = if (logsVisible) "로그 숨기기" else "로그 보기",
                    onClick = onOpenLogs,
                    active = logsVisible,
                    modifier = Modifier.weight(1f),
                )
            }

            when (state) {
                WatchState.ERROR -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(
                        text = "다시 시도",
                        onClick = onRetry,
                        modifier = Modifier.weight(1f),
                    )
                    GhostButton(
                        text = "중지",
                        onClick = onStop,
                        active = false,
                        modifier = Modifier
                            .weight(1f)
                            .height(PRIMARY_BUTTON_HEIGHT),
                    )
                }

                WatchState.LOADING, WatchState.ANALYZING, WatchState.WAITING -> PrimaryButton(
                    text = "감시 중지",
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )

                else -> PrimaryButton(
                    text = "감시 시작",
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 채워진 주 버튼. 기본은 강조색 위에 검정 글씨. */
@Composable
private fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(PRIMARY_BUTTON_HEIGHT),
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
    ) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

/** 배경 없이 테두리만 있는 보조 버튼. 켜져 있으면 테두리와 글씨가 강조색이 된다. */
@Composable
private fun GhostButton(
    text: String,
    onClick: () -> Unit,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, if (active) accent else MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

private const val MAX_URL_CHARS = 60

/**
 * 페이지가 정착한 뒤 자동으로 목록을 읽기까지 기다리는 시간.
 * onPageFinished 직후에는 표가 아직 그려지지 않은 경우가 있다.
 */
private const val AUTO_SCAN_DELAY_MS = 400L

/** 팝업 뒤를 덮는 스크림의 검정 농도. */
private const val SCRIM_ALPHA = 0.55f

/** 팝업 창이 차지하는 화면 세로 비율. */
private const val POPUP_HEIGHT_RATIO = 0.8f

private val PRIMARY_BUTTON_HEIGHT = 52.dp

/**
 * 시스템 내비게이션 인셋과 별개로 항상 확보하는 아래쪽 여백.
 * 제스처 힌트 바가 버튼 위로 겹쳐 터치를 먹는 문제를 막는다.
 */
private val BOTTOM_SAFE_PADDING = 20.dp
