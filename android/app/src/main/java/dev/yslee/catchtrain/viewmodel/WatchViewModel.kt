package dev.yslee.catchtrain.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.yslee.catchtrain.domain.SeatClass
import dev.yslee.catchtrain.domain.TrainKey
import dev.yslee.catchtrain.domain.WatchSelection
import dev.yslee.catchtrain.notification.NotificationHelper
import dev.yslee.catchtrain.parser.KtxParser
import dev.yslee.catchtrain.storage.AppSettings
import dev.yslee.catchtrain.storage.SettingsRepository
import dev.yslee.catchtrain.watcher.ReloadScheduler
import dev.yslee.catchtrain.watcher.WatchController
import dev.yslee.catchtrain.watcher.LogCode
import dev.yslee.catchtrain.watcher.WatchLogEntry
import dev.yslee.catchtrain.watcher.WatchLogger
import dev.yslee.catchtrain.watcher.WatchStatus
import dev.yslee.catchtrain.webview.PageHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 화면 상태를 모아 UI 에 노출한다. (DESIGN.md §5, §6)
 *
 * UI 는 WebView / DOM / selector 를 전혀 모르고, 이 ViewModel 의 StateFlow 만 본다.
 *
 * 감시 대상([selection])은 저장하지 않고 메모리에만 둔다. 사용자가 사이트에서
 * 조회한 그 결과 화면에 대해서만 의미가 있는 값이라, 앱을 다시 켰을 때 되살리면
 * 화면에 없는 열차를 감시하게 된다.
 */
class WatchViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository(application)
    private val notifier = NotificationHelper(application)

    val logger = WatchLogger()

    private var controller: WatchController? = null

    /** 진단용으로만 직접 쓴다. 감시는 전부 [controller] 를 거친다. (§38-10) */
    private var host: PageHost? = null

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _status = MutableStateFlow(WatchStatus())
    val status: StateFlow<WatchStatus> = _status.asStateFlow()

    /** 사용자가 [열차 선택] 목록에서 체크한 좌석들. */
    private val _selection = MutableStateFlow(WatchSelection.empty())
    val selection: StateFlow<WatchSelection> = _selection.asStateFlow()

    /**
     * 로그인 확인을 마치고 감시를 시작하는 중인지. [감시 시작] 연타를 막는다.
     * 확인은 DOM 만 읽어 금방 끝나므로 화면에 따로 표시하지 않는다.
     */
    private var startPending = false

    /** 화면 목록을 읽어오는 중인지. 버튼 중복 클릭을 막는다. */
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    val logs: StateFlow<List<WatchLogEntry>> get() = logger.entries

    init {
        viewModelScope.launch {
            repository.settings.collect { _settings.value = it }
        }
    }

    /** WebView 가 준비되면 Activity 가 한 번 호출한다. */
    fun attachHost(host: PageHost) {
        if (controller != null) return
        this.host = host
        val created = WatchController(
            host = host,
            parser = KtxParser(),
            notifier = notifier,
            logger = logger,
            scope = viewModelScope,
        )
        controller = created
        viewModelScope.launch {
            created.status.collect { _status.value = it }
        }
    }

    // ------------------------------------------------------------ 열차 선택

    /**
     * 지금 화면에 떠 있는 조회 결과를 읽어 [열차 선택] 목록을 채운다.
     *
     * 조회 요청을 보내지 않고 이미 그려진 DOM 만 읽으므로 차단 위험이 없다.
     * 그래서 사용자가 누를 때뿐 아니라 페이지가 바뀔 때마다 자동으로 불러도 된다.
     *
     * @param quiet 사용자가 직접 누른 것이 아니라 자동으로 불린 경우.
     *   실패해도 토스트나 안내를 띄우지 않는다. 사용자가 시킨 적 없는 일이
     *   실패했다고 말을 거는 셈이라, 화면이 시끄러워지기만 한다.
     */
    fun refreshTrainList(quiet: Boolean = false) {
        val target = controller
        if (target == null) {
            if (!quiet) _toast.value = "WebView 준비 중입니다. 잠시 후 다시 시도하세요."
            return
        }
        if (_scanning.value) return
        // 감시 중에는 루프가 매 사이클 목록을 새로 읽는다. 자동 갱신이 끼어들 이유가 없다.
        if (quiet && _status.value.state.isRunning) return
        _scanning.value = true
        viewModelScope.launch {
            try {
                val trains = target.scanTrains(quiet = quiet)
                if (trains == null) {
                    if (!quiet) _toast.value = "조회 결과 화면이 아닙니다. 사이트에서 먼저 조회하세요."
                    return@launch
                }
                if (trains.isEmpty()) {
                    if (!quiet) _toast.value = "조회된 열차가 없습니다."
                    return@launch
                }
                // 다시 조회한 결과에 없는 열차의 체크는 지운다.
                // (사용자가 사이트에서 날짜나 구간을 바꿨을 수 있다)
                setSelection(_selection.value.retainOnly(trains))
            } finally {
                _scanning.value = false
            }
        }
    }

    fun toggleSeat(trainKey: TrainKey, seatClass: SeatClass) {
        setSelection(_selection.value.toggle(trainKey, seatClass))
    }

    fun clearSelection() {
        setSelection(WatchSelection.empty())
    }

    /**
     * 선택을 갈아끼운다. 감시 중이라면 재시작 없이 다음 사이클부터 반영한다.
     * (재시작하면 조회 요청이 한 번 더 나가고 알림 이력도 사라진다)
     */
    private fun setSelection(next: WatchSelection) {
        if (next == _selection.value) return
        _selection.value = next
        controller?.updateSelection(next)
    }

    // ------------------------------------------------------------ 감시 제어

    /**
     * 감시를 시작한다. 시작 전에 **로그인 여부를 먼저 확인한다.**
     *
     * 코레일은 비로그인 상태에서도 조회가 되고 좌석 선택까지 된다. (§38-7)
     * 그래서 로그인하지 않은 채로도 감시가 멀쩡히 돌아가는 것처럼 보이다가,
     * 정작 좌석이 열려 예매를 누르는 순간 로그인 화면으로 튕긴다.
     * 몇 시간을 기다린 그 한 번을 놓치는 자리라, 시작 자체를 막는다.
     *
     * 로그인 여부를 **판단하지 못한 경우에는 막지 않는다.**
     * (사이트 개편으로 마커를 놓친 것뿐인데 앱이 영영 시작되지 않으면 더 나쁘다)
     */
    fun startWatching() {
        val current = _selection.value
        val invalid = current.validate()
        if (invalid != null) {
            _toast.value = invalid
            return
        }
        val target = controller
        if (target == null) {
            _toast.value = "WebView 준비 중입니다. 잠시 후 다시 시도하세요."
            return
        }
        if (startPending) return
        startPending = true
        viewModelScope.launch {
            try {
                if (target.checkLogin().blocksWatch) {
                    _toast.value = LOGIN_REQUIRED_MESSAGE
                    return@launch
                }
                notifier.cancelAll()
                target.start(current, _settings.value.toWatchConfig())
            } finally {
                startPending = false
            }
        }
    }

    fun stopWatching() {
        controller?.stop()
    }

    /**
     * 결제 재촉 알림만 끈다. (DESIGN.md §19-3)
     *
     * 예약 카드의 [알림 끄기] 버튼과, 알림 자체의 [알림 끄기] 동작이 여기로 온다.
     * 화면은 예약 상태 그대로 남겨 둔다. 사용자는 결제를 이어서 해야 한다.
     */
    fun silenceReserveAlert() {
        controller?.silenceReserveReminder()
    }

    fun continueWatching() {
        controller?.continueWatching()
    }

    fun retry() {
        controller?.retry()
    }

    /** Activity ON_STOP (§24) */
    fun onHostPaused() {
        controller?.pause()
    }

    /** Activity ON_START (§24) */
    fun onHostResumed() {
        controller?.resume()
    }

    val isWatching: Boolean
        get() = _status.value.state.isRunning

    fun canStart(): Boolean = !_status.value.state.isRunning

    fun consumeToast() {
        _toast.value = null
    }

    /** 화면에서 벌어진 일(로그 복사 등)을 짧게 알린다. */
    fun notify(message: String) {
        _toast.value = message
    }

    fun clearLogs() {
        logger.clear()
    }

    /**
     * 역 선택 창이 안 뜨는 원인을 로그에 한 줄 남긴다. (DESIGN.md §38-10)
     *
     * 이 창은 `window.open` 팝업이 아니라 **페이지가 스스로 그리는** react-modal 이라,
     * 안 뜰 때 앱이 대신 띄워 줄 방법이 없다. 남는 일은 어디서 끊겼는지 보는 것뿐이다.
     * 읽기만 하므로 조회 요청이 나가지 않는다 — 감시 중에 눌러도 된다.
     *
     * 쓰는 순서: **진단 → 역 버튼을 손으로 눌러 봄 → 진단**.
     * 첫 번째가 탭 기록을 받을 준비를 하고, 두 번째가 그 탭이 어디까지 갔는지 알려준다.
     */
    fun probeStationPopup() {
        val target = host
        if (target == null) {
            _toast.value = "WebView 준비 중입니다. 잠시 후 다시 시도하세요."
            return
        }
        viewModelScope.launch {
            logger.log(LogCode.STATION_PROBE, target.probeStationPopup())
        }
    }

    fun dumpLogs(): String = logger.dump()

    /**
     * ViewModel 이 사라지면 재촉 알림 루프도 함께 죽는다(viewModelScope 취소).
     * 그런데 이미 올라간 재촉 알림은 ongoing 이라 저절로 사라지지 않으므로,
     * 다시는 갱신되지 않을 알림이 상태바에 눌어붙지 않게 여기서 걷어낸다.
     */
    override fun onCleared() {
        notifier.cancelAll()
        super.onCleared()
    }

    // ------------------------------------------------------------ 앱 설정

    /** 재조회 간격 범위. 실제 간격은 매 사이클 이 범위 안에서 무작위로 정해진다. */
    fun setReloadIntervalRange(minMs: Long, maxMs: Long) {
        val (min, max) = ReloadScheduler.clampRange(minMs, maxMs)
        _settings.value = _settings.value.copy(minIntervalMs = min, maxIntervalMs = max)
        viewModelScope.launch { repository.updateReloadIntervalRange(min, max) }
    }

    fun setNotificationEnabled(enabled: Boolean) {
        _settings.value = _settings.value.copy(notificationEnabled = enabled)
        viewModelScope.launch { repository.updateNotificationEnabled(enabled) }
    }

    /**
     * 선택한 좌석이 열리면 [예약하기] 버튼까지 눌러 줄지.
     * 누르는 것은 예약하기 하나뿐이고, 좌석 선택/결제는 사용자가 직접 한다.
     */
    fun setAutoReserveEnabled(enabled: Boolean) {
        _settings.value = _settings.value.copy(autoReserveEnabled = enabled)
        viewModelScope.launch { repository.updateAutoReserveEnabled(enabled) }
    }

    fun setStopOnMatch(enabled: Boolean) {
        _settings.value = _settings.value.copy(stopOnMatch = enabled)
        viewModelScope.launch { repository.updateStopOnMatch(enabled) }
    }

    companion object {
        const val LOGIN_REQUIRED_MESSAGE =
            "먼저 코레일에 로그인하세요. 로그인하지 않으면 좌석이 열려도 예약 화면으로 넘어가지 못합니다."
    }
}
