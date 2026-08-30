package dev.yslee.catchtrain.ui

import android.os.Build
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.yslee.catchtrain.ui.components.Hairline
import dev.yslee.catchtrain.ui.components.PanelCard
import dev.yslee.catchtrain.ui.components.StatusDot
import dev.yslee.catchtrain.ui.theme.BrandTitle
import dev.yslee.catchtrain.ui.theme.MonoUrl
import dev.yslee.catchtrain.viewmodel.WatchViewModel
import dev.yslee.catchtrain.watcher.WatchState
import dev.yslee.catchtrain.webview.KtxPopupHost
import kotlinx.coroutines.delay

/**
 * 앱 메인 화면. (DESIGN.md §21, §22)
 *
 * 구조(위 → 아래):
 *   헤더           ← 한 줄로 압축. [● 상태 · 간격] + [펼치기] + [설정]
 *   (펼침) 상세    ← 주소 / 조건 요약 / 감시 상태
 *   코레일 WebView ← 감시 중 주기적으로 [열차조회] 가 눌린다
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
    popup: KtxPopupHost.PopupWindow?,
    onClosePopup: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()

    var showSettings by remember { mutableStateOf(false) }
    // 상세도 접어 둔다. 시작 직후에는 보여 줄 것이 주소뿐이라, 좁은 폰에서
    // 코레일 화면만 밀어 낸다. 필요하면 헤더의 [펼치기] 로 연다.
    var expanded by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    // 시작할 때는 접어 둔다. 조회 전에는 목록이 비어 있어서 코레일 화면만 가린다.
    var showTrains by remember { mutableStateOf(false) }
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
                // 화면을 먼저 닫는다. 설정 화면이 떠 있는 동안 WebView 는 화면에서
                // 떼어져 있고, 크기 0 인 WebView 로 문서를 받으면 `100vh` 가 0 으로
                // 굳어 코레일의 역/날짜 선택 레이어가 납작해진다. (§38-10)
                // 로드 쪽에도 대기가 있지만(KtxWebViewHost.loadStartUrl), 굳이
                // 기다리게 만들 이유가 없다.
                showSettings = false
                onReloadStartPage()
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
                // 이력은 상태가 아니라 WebView 가 들고 있다. 주소가 바뀔 때 같이
                // 다시 읽히도록 pageUrl 과 한 식에 묶어 둔다.
                canGoBack = pageUrl != null && webView.canGoBack(),
                expanded = expanded,
                onToggleExpand = { expanded = !expanded },
                onOpenSettings = { showSettings = true },
                onGoBack = { if (webView.canGoBack()) webView.goBack() },
                // 갱신은 새로고침 하나뿐이다. 감시 루프가 쓰는 것과 같은 방식이다. (대원칙 1)
                onReloadPage = { webView.reload() },
                onNavigate = { input -> webView.loadUrl(normalizeUrl(input)) },
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
                        )
                        StatusCard(status = status)
                    }
                }

                // 3) 좌석 발견 / 예매 카드는 접힌 상태에서도 항상 보여준다.
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
                } else if (
                    // 2단계까지 눌렀거나(RESERVED), 좌석만 골라 두고 사람에게 넘겼거나(SEAT_SELECTED).
                    // 둘 다 "지금 코레일 화면을 봐야 하는" 상태라, 앱은 그 화면을 가리지 않고
                    // 재촉 알림을 끄는 버튼만 얹는다. (§38-6-1, §19-3)
                    (status.state == WatchState.RESERVED ||
                        status.state == WatchState.SEAT_SELECTED) && status.reserveAlerting
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        ReserveAlertCard(onSilence = viewModel::silenceReserveAlert)
                    }
                }

                Spacer(Modifier.height(8.dp))
                Hairline()

                // 4) WebView. 코레일 페이지는 흰 배경 그대로 둔다.
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

            // 열차 선택 / 로그 창. 흐름 배치로 두면 코레일 화면을 아래로 밀어내므로,
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
                        LogPanel(
                            logs = logs,
                            onClear = viewModel::clearLogs,
                            onProbeStation = viewModel::probeStationPopup,
                            onCopied = { count ->
                                // Android 13+ 는 시스템이 복사 확인을 직접 띄운다.
                                // 여기서 또 띄우면 같은 말이 두 번 나온다.
                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                    viewModel.notify("로그 ${count}줄을 복사했습니다.")
                                }
                            },
                        )
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
 * WebView 자체는 [KtxPopupHost] 가 만들어 `opener` 연결까지 끝낸 것을 그대로 붙인다.
 * 여기서 새로 만들거나 URL 만 다시 로드하면 `opener` 가 끊겨 날짜 선택이 동작하지 않는다.
 *
 * 바깥 스크림을 누르면 닫힌다. 뒤로가기는 [MainScreen] 이 받는다.
 */
@Composable
private fun PopupOverlay(
    popup: KtxPopupHost.PopupWindow,
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
 * 접힘 : CATCH TRAIN  ● 다음 확인 대기 · 1.0~3.0초     [v] [설정]
 * 펼침 : 위 한 줄 + [←] 주소 입력칸 [⟳]
 *
 * 예전에 패널 아래에 따로 있던 [패널 접기/펼치기] 버튼을 여기로 흡수했다.
 * 여닫는 컨트롤이 한 곳에만 있어야 헤더 높이가 예측 가능하다.
 *
 * [코레일 홈] 버튼이 있던 자리는 [설정] 이 가져갔다. 시작 페이지로 돌아가는 기능은
 * 설정 화면의 "페이지" 섹션으로 옮겼다.
 *
 * 로고는 펼침 줄에 있었는데, 접었을 때 앱 이름이 화면 어디에도 없었다.
 * 항상 보이는 첫 줄 맨 왼쪽으로 옮기고, 그 자리는 주소 줄이 통째로 쓴다.
 *
 * **[onGoBack] · [onNavigate] 는 조회 조건을 날린다.** 되돌리기든 주소 이동이든
 * 조회 결과 화면을 떠나면 사용자가 사이트에 직접 넣어 둔 구간·날짜가 사라진다
 * (대원칙 4·5). 그래도 두는 이유는 **사용자가 누른 것**이기 때문이다 —
 * 앱이 스스로 부르는 자리가 아니다.
 */
@Composable
private fun AppHeader(
    state: WatchState,
    intervalLabel: String,
    pageUrl: String?,
    canGoBack: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onOpenSettings: () -> Unit,
    onGoBack: () -> Unit,
    onReloadPage: () -> Unit,
    onNavigate: (String) -> Unit,
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
                    .padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "CATCH TRAIN",
                    style = BrandTitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.width(12.dp))
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
                        .padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onGoBack,
                        enabled = canGoBack,
                        modifier = Modifier.size(NAV_BUTTON_SIZE),
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "뒤로 가기",
                            modifier = Modifier.size(NAV_ICON_SIZE),
                        )
                    }
                    UrlField(
                        pageUrl = pageUrl,
                        onNavigate = onNavigate,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onReloadPage,
                        modifier = Modifier.size(NAV_BUTTON_SIZE),
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "새로고침",
                            modifier = Modifier.size(NAV_ICON_SIZE),
                        )
                    }
                }
            }

            Hairline()
        }
    }
}

/**
 * 주소 입력칸. 보여 주기만 하던 pill 을 그대로 입력칸으로 바꾼 것이다.
 *
 * 화면 주소를 따라가되 **편집 중에는 따라가지 않는다.** 감시 중에는 새로고침마다
 * 주소가 다시 흘러들어오는데, 그때 입력 중인 글자를 덮어쓰면 고칠 수가 없다.
 * 초점을 잃으면 고치던 것을 버리고 실제 주소로 돌아온다 — 고친 주소가 남아 있으면
 * 지금 보고 있는 화면과 다른 주소가 표시된다.
 *
 * 이동은 키보드의 [이동](`ImeAction.Go`) 으로만 일어난다. 글자를 고치는 것만으로는
 * 아무 요청도 나가지 않는다.
 */
@Composable
private fun UrlField(
    pageUrl: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    var editing by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf(TextFieldValue(pageUrl.orEmpty())) }

    LaunchedEffect(pageUrl, editing) {
        if (!editing) value = TextFieldValue(pageUrl.orEmpty())
    }

    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    BasicTextField(
        value = value,
        onValueChange = { value = it },
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.shapes.extraSmall,
            )
            .padding(horizontal = 8.dp, vertical = 7.dp)
            .onFocusChanged { focus ->
                editing = focus.isFocused
                // 들어오자마자 전체 선택. 주소는 길고, 대개 통째로 갈아 끼운다.
                if (focus.isFocused) {
                    value = value.copy(selection = TextRange(0, value.text.length))
                }
            },
        textStyle = MonoUrl.copy(color = MaterialTheme.colorScheme.onSurface),
        singleLine = true,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(
            onGo = {
                val target = value.text.trim()
                if (target.isNotEmpty()) onNavigate(target)
                focusManager.clearFocus()
            },
        ),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.text.isEmpty()) {
                    Text(text = "페이지 없음", style = MonoUrl, color = muted)
                }
                inner()
            }
        },
    )
}

/** 사용자가 친 주소. 스킴이 없으면 https 로 연다. */
private fun normalizeUrl(input: String): String {
    val trimmed = input.trim()
    val hasScheme = trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
    return if (hasScheme) trimmed else "https://$trimmed"
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
                // 오류가 나면 감시 루프는 이미 멈춰 있다. [중지] 는 누를 것이 없어서 뺐다.
                WatchState.ERROR -> PrimaryButton(
                    text = "다시 시도",
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                )

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

/** 주소 줄의 [←] / [⟳]. 기본 IconButton(48dp)은 주소칸을 너무 밀어낸다. */
private val NAV_BUTTON_SIZE = 36.dp
private val NAV_ICON_SIZE = 18.dp

/**
 * 시스템 내비게이션 인셋과 별개로 항상 확보하는 아래쪽 여백.
 * 제스처 힌트 바가 버튼 위로 겹쳐 터치를 먹는 문제를 막는다.
 */
private val BOTTOM_SAFE_PADDING = 20.dp
