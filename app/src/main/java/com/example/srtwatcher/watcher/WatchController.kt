package com.example.srtwatcher.watcher

import com.example.srtwatcher.domain.MatchKey
import com.example.srtwatcher.domain.SeatMatch
import com.example.srtwatcher.domain.SeatStatus
import com.example.srtwatcher.domain.SelectionEngine
import com.example.srtwatcher.domain.Train
import com.example.srtwatcher.domain.WatchSelection
import com.example.srtwatcher.domain.toKey
import com.example.srtwatcher.notification.MatchNotifier
import com.example.srtwatcher.parser.DomParseException
import com.example.srtwatcher.parser.PageSnapshot
import com.example.srtwatcher.parser.PageStatus
import com.example.srtwatcher.parser.SrtPageParser
import com.example.srtwatcher.webview.LoginCheck
import com.example.srtwatcher.webview.PageHost
import com.example.srtwatcher.webview.PageOutcome
import com.example.srtwatcher.webview.ReserveOutcome
import com.example.srtwatcher.webview.ReserveTarget
import com.example.srtwatcher.webview.SrtLoginParser
import com.example.srtwatcher.webview.SrtLoginScript
import com.example.srtwatcher.webview.SrtParserScript
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 감시 엔진. (DESIGN.md §9, §10, §24, §34)
 *
 * 핵심 원칙
 *  - WebView 는 [PageHost] 뒤에 숨어 있고, 감시 로직은 전부 여기에 있다. (§34-1)
 *  - DOM 분석([SrtPageParser])과 선택 판정([SelectionEngine])은 서로 분리되어 있다. (§34-2)
 *  - 재조회 주기는 [ReloadScheduler] 로 Native 에서 관리한다. (§34-3)
 *  - 선택한 좌석이 열리면 알림을 보내고 감시를 멈춘다. (§34-4)
 *  - 자동 예약이 켜져 있으면 알림을 보낸 **뒤에** [예약하기] 버튼까지 누른다. (§19)
 *    누르는 것은 예약하기 하나뿐이고, 좌석 선택과 결제는 사용자가 직접 한다.
 *
 * 감시 대상은 **사용자가 [열차 선택] 목록에서 체크한 칸**([WatchSelection])이다.
 * 구간/날짜/시간 조건은 앱이 들고 있지 않다. 사용자가 사이트에서 직접 조회한
 * 결과를 그대로 쓰고, 앱은 그 표의 어느 칸을 볼지만 안다.
 *
 * 한 사이클:
 *   조회하기 버튼 탭 → 정착 대기 → DOM 분석 → 선택 판정 → (알림) → (예약하기 탭) → 대기 → 다시 탭
 *
 * 페이지 갱신은 언제나 화면에 보이는 "조회하기" 버튼을 **직접 누르는 것**이다.
 * reload 도, 조회 URL 직접 호출도 쓰지 않는다. (그 경로는 차단된다)
 *
 * 감시를 "시작"한 직후 첫 사이클은 재조회 없이 현재 화면을 바로 분석한다.
 * 사용자는 이미 조회 결과 화면에서 열차를 골랐으므로, 그 화면을 그대로 보는 것이
 * 자연스럽고 요청도 한 번 아낀다.
 */
class WatchController(
    private val host: PageHost,
    private val parser: SrtPageParser,
    private val notifier: MatchNotifier,
    private val logger: WatchLogger,
    private val scope: CoroutineScope,
    private val engine: SelectionEngine = SelectionEngine(),
    private val scheduler: ReloadScheduler = ReloadScheduler(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val _status = MutableStateFlow(WatchStatus())
    val status: StateFlow<WatchStatus> = _status.asStateFlow()

    private val parserScript: String by lazy { SrtParserScript.build() }

    private val loginScript: String by lazy { SrtLoginScript.build() }

    /** 이미 알린 (열차, 좌석등급) 조합. (§20) */
    private val notifiedKeys = mutableSetOf<MatchKey>()

    /**
     * 이미 [예약하기] 를 눌러 본 조합.
     *
     * 알림과 달리 좌석이 매진되었다가 다시 나와도 지우지 않는다.
     * 같은 열차에 자동으로 두 번 예약을 거는 일이 없어야 하기 때문이다.
     */
    private val reserveAttemptedKeys = mutableSetOf<MatchKey>()

    /**
     * [예약하기] 를 눌렀다가 "잔여석없음"을 만난 횟수. (§19-2)
     * [WatchConfig.maxSoldOutRetries] 를 넘기면 그 칸은 더 누르지 않는다.
     */
    private val soldOutCounts = mutableMapOf<MatchKey, Int>()

    private var loopJob: Job? = null

    /**
     * 결제 재촉 알림을 되풀이하는 루프. (DESIGN.md §19-3)
     *
     * 감시 루프([loopJob])와 **일부러 따로 둔다**. [예약하기] 를 누른 뒤 감시 루프는
     * 끝나지만 재촉은 그때부터 시작되고, 사용자가 앱을 잠깐 벗어나 [pause] 되어도
     * 계속 울려야 하기 때문이다. (그 순간이야말로 알림이 필요한 때다)
     */
    private var reminderJob: Job? = null

    /**
     * 감시 중인 선택. 루프는 사이클마다 이 값을 다시 읽는다.
     * 덕분에 감시 중에 체크를 바꿔도 재시작 없이 다음 사이클부터 반영된다.
     */
    @Volatile
    private var activeSelection: WatchSelection = WatchSelection.empty()
    private var activeConfig: WatchConfig = WatchConfig()

    val isWatching: Boolean
        get() = loopJob?.isActive == true

    // ---------------------------------------------------------------- 제어

    fun start(selection: WatchSelection, config: WatchConfig) {
        cancelLoop()
        stopReserveReminder("감시 재시작")
        activeSelection = selection
        activeConfig = config
        notifiedKeys.clear()
        reserveAttemptedKeys.clear()
        soldOutCounts.clear()
        _status.value = WatchStatus(state = WatchState.LOADING)
        logger.log(
            LogCode.WATCH_START,
            "선택 ${selection.size}칸 (열차 ${selection.trainCount}편성) " +
                "간격=${ReloadScheduler.formatRange(config.minIntervalMs, config.maxIntervalMs)} 랜덤 " +
                (if (config.autoReserveEnabled) "자동예약=켬" else "자동예약=끔"),
        )
        launchLoop(skipFirstRefresh = true)
    }

    /**
     * 감시 중에 사용자가 체크를 바꾼 경우. 다음 사이클부터 새 선택으로 본다.
     *
     * 감시를 재시작하지 않는다. 재시작하면 조회 요청이 한 번 더 나가고
     * 이미 알린 이력도 사라지기 때문이다.
     */
    fun updateSelection(selection: WatchSelection) {
        activeSelection = selection
        // 더 이상 보지 않기로 한 칸의 이력은 지운다. 다시 체크했을 때 알림이 나오도록.
        notifiedKeys.retainAll { selection.contains(it.trainKey, it.seatClass) }
        reserveAttemptedKeys.retainAll { selection.contains(it.trainKey, it.seatClass) }
        soldOutCounts.keys.retainAll { selection.contains(it.trainKey, it.seatClass) }
    }

    /**
     * Activity ON_STOP 등에서 호출. (§24)
     *
     * 결제 재촉 알림([reminderJob])은 **멈추지 않는다.** 앱을 벗어나 있을 때야말로
     * 그 알림이 필요한 순간이다.
     */
    fun pause() {
        if (!isWatching) return
        cancelLoop()
        logger.log(LogCode.WATCH_PAUSED)
        updateStatus { copy(state = WatchState.PAUSED, nextCheckInMs = null) }
    }

    /** Activity ON_START 등에서 호출. 일시정지 상태였을 때만 재개한다. (§24) */
    fun resume() {
        if (isWatching) return
        if (_status.value.state != WatchState.PAUSED) return
        if (activeSelection.isEmpty) return
        logger.log(LogCode.WATCH_RESUMED)
        updateStatus { copy(state = WatchState.LOADING) }
        launchLoop(skipFirstRefresh = true)
    }

    /** [감시 종료]. 결제 재촉 알림도 여기서 함께 멈춘다. */
    fun stop() {
        cancelLoop()
        stopReserveReminder("감시 종료")
        logger.log(LogCode.WATCH_STOP)
        updateStatus { copy(state = WatchState.STOPPED, nextCheckInMs = null) }
    }

    /**
     * 결제 재촉 알림만 끈다. 감시 상태(RESERVED)는 그대로 둔다.
     *
     * 알림의 [알림 끄기] 나 예약 카드의 버튼에서 부른다. 사용자가 화면을 확인한
     * 뒤 조용히 결제만 하고 싶은 경우다.
     */
    fun silenceReserveReminder() {
        stopReserveReminder("사용자가 끔")
    }

    /** 좌석 발견 후에도 계속 감시하고 싶을 때. 이미 알린 좌석은 다시 알리지 않는다. */
    fun continueWatching() {
        if (activeSelection.isEmpty) return
        if (isWatching) return
        stopReserveReminder("계속 감시")
        updateStatus { copy(state = WatchState.LOADING, error = null) }
        logger.log(LogCode.WATCH_RESUMED, "발견 후 계속")
        launchLoop(skipFirstRefresh = false)
    }

    /** 오류 화면의 [다시 시도] */
    fun retry() {
        if (activeSelection.isEmpty) return
        start(activeSelection, activeConfig)
    }

    fun clearMatchHistory() {
        notifiedKeys.clear()
        reserveAttemptedKeys.clear()
        soldOutCounts.clear()
    }

    private fun cancelLoop() {
        loopJob?.cancel()
        loopJob = null
    }

    private fun launchLoop(skipFirstRefresh: Boolean) {
        loopJob = scope.launch { runLoop(skipFirstRefresh) }
    }

    // ---------------------------------------------------------- 결제 재촉 알림

    /**
     * [예약하기] 를 눌러 결제 화면으로 넘어간 순간부터, 사용자가 멈출 때까지
     * [WatchConfig.reserveReminderIntervalMs] 마다 소리와 진동을 다시 울린다.
     * (DESIGN.md §19-3)
     *
     * 첫 알림은 한 주기 뒤에 온다. 넘어간 그 순간에는 좌석 발견 알림이 방금
     * 울렸으므로 곧바로 또 울릴 이유가 없다.
     *
     * 사용자가 멈추는 경로는 [stopReserveReminder] 를 부르는 곳 넷이다.
     * 감시 종료 / 알림 끄기 / 계속 감시 / 감시 재시작.
     *
     * 여기에 더해 [WatchConfig.reserveReminderMaxDurationMs] 가 지나면 스스로 멈춘다.
     * SRT 가 그때 좌석을 도로 풀기 때문에, 그 뒤로는 재촉해 봐야 잡을 표가 없다.
     * 횟수 상한이 아니라 시간 상한인 이유는 좌석이 풀리는 기준이 시간이라서다.
     */
    private fun startReserveReminder(match: SeatMatch, config: WatchConfig) {
        reminderJob?.cancel()

        if (!config.notificationEnabled) {
            logger.log(LogCode.NOTIFICATION_SKIPPED, "알림 꺼짐 (결제 재촉)")
            reminderJob = null
            return
        }

        val intervalMs = config.reserveReminderIntervalMs
        val maxDurationMs = config.reserveReminderMaxDurationMs
        logger.log(
            LogCode.RESERVE_REMINDER_START,
            "${intervalMs / 1000}초마다 · 최대 ${maxDurationMs / 60_000}분",
        )
        updateStatus { copy(reserveAlerting = true) }

        reminderJob = scope.launch {
            var count = 0
            while (currentCoroutineContext().isActive) {
                delay(intervalMs)
                count++
                val elapsedMs = count * intervalMs
                if (elapsedMs >= maxDurationMs) {
                    // 좌석이 풀린 시각. 재촉을 이어 갈 이유가 없다.
                    stopReserveReminder("결제 제한 시간 ${maxDurationMs / 60_000}분 지남")
                    return@launch
                }
                notifier.notifyReserveReminder(
                    match = match,
                    repeatIndex = count,
                    elapsedMs = elapsedMs,
                )
                logger.log(LogCode.RESERVE_REMINDER_SENT, "${count}회")
            }
        }
    }

    /**
     * 이미 꺼져 있으면 아무 일도 하지 않는다. (로그가 지저분해지지 않도록)
     *
     * 재촉 루프 안에서도 부른다(제한 시간 만료). 자기 자신을 취소하는 셈이지만
     * 뒤따르는 문장이 전부 정지 함수가 아니라 그대로 끝까지 실행된다.
     */
    private fun stopReserveReminder(reason: String) {
        if (reminderJob == null) return
        reminderJob?.cancel()
        reminderJob = null
        notifier.cancelReserveReminder()
        logger.log(LogCode.RESERVE_REMINDER_STOP, reason)
        updateStatus { copy(reserveAlerting = false) }
    }

    // ---------------------------------------------------------------- 목록 읽기

    /**
     * 사이트에 로그인되어 있는지 확인한다. ([SrtLoginScript])
     *
     * 감시를 시작하기 전에 한 번 부른다. SRT 는 **비로그인 상태에서도 조회가 되고
     * 표에 [예약하기] 버튼까지 보이기 때문에**, 화면만 보고는 로그인 여부를 알 수 없다.
     * 그대로 감시를 시작하면 좌석이 열린 바로 그 순간에 로그인 화면으로 튕긴다.
     *
     * DOM 만 읽으므로 요청이 나가지 않는다. = 차단 위험이 없다.
     *
     * 판단하지 못하면 [LoginState.UNKNOWN] 이고, 그때는 막지 않는다.
     */
    suspend fun checkLogin(): LoginCheck {
        val result = SrtLoginParser.parse(host.evaluate(loginScript))
        logger.log(LogCode.LOGIN_STATE, "${result.state} ${result.detail}")
        return result
    }

    /**
     * **재조회 없이** 지금 화면에 그려져 있는 열차 목록만 다시 읽는다.
     *
     * 사용자가 사이트에서 조회를 마친 뒤 [열차 선택] 목록을 채우기 위한 통로다.
     * 조회하기 버튼을 누르지 않으므로 요청이 나가지 않는다. = 차단 위험이 없다.
     * 그래서 사용자가 몇 번을 눌러도 안전하다.
     *
     * 페이지가 바뀔 때마다 자동으로도 불린다. 그렇게 불린 경우([quiet])에는
     * 실패해도 화면에 안내를 띄우지 않는다. 로그인 화면이나 결제 화면처럼
     * 열차 목록이 없는 것이 당연한 곳에서 "조회 결과 화면에서 다시 시도하세요" 가
     * 계속 뜨면 그것대로 소음이다. 기록은 로그에 남으므로 잃는 것은 없다.
     *
     * @return 읽어낸 열차 목록. 조회 결과 화면이 아니거나 분석에 실패하면 null.
     */
    suspend fun scanTrains(quiet: Boolean = false): List<Train>? {
        val snapshot = try {
            parser.parse(host.evaluate(parserScript))
        } catch (e: DomParseException) {
            logger.log(LogCode.DOM_PARSE_ERROR, e.message)
            if (!quiet) {
                updateStatus { copy(message = "화면을 분석하지 못했습니다. 조회 결과 화면인지 확인하세요.") }
            }
            return null
        }

        logger.log(LogCode.PAGE_STATUS, snapshot.status.name)
        snapshot.warnings.take(MAX_LOGGED_WARNINGS).forEach { logger.log(LogCode.DOM_WARNING, it) }

        val readable = snapshot.status == PageStatus.TRAIN_LIST ||
            snapshot.status == PageStatus.NO_TRAIN
        if (!readable) {
            if (!quiet) updateStatus { copy(message = scanHint(snapshot.status)) }
            return null
        }

        logger.log(LogCode.TRAIN_COUNT, snapshot.trains.size.toString())
        updateStatus {
            copy(
                trains = snapshot.trains,
                searchDate = snapshot.searchDate,
                trainCount = snapshot.trains.size,
                // 몇 편성을 읽었는지는 목록 머리말의 "조회 N" 이 이미 말해 주므로
                // 카드로 또 알리지 않는다. 앞서 남은 안내가 있으면 지운다.
                message = null,
            )
        }
        return snapshot.trains
    }

    private fun scanHint(status: PageStatus): String = when (status) {
        PageStatus.LOGIN_REQUIRED -> WatchError.LOGIN_REQUIRED.guide
        PageStatus.SESSION_EXPIRED -> WatchError.SESSION_EXPIRED.guide
        PageStatus.BLOCKED -> WatchError.BLOCKED.guide
        else -> "열차 조회 결과 화면에서 다시 시도하세요."
    }

    // ---------------------------------------------------------------- 감시 루프

    private suspend fun runLoop(skipFirstRefresh: Boolean) {
        val config = activeConfig
        var skipRefresh = skipFirstRefresh
        var consecutiveErrors = 0
        var consecutiveUnknownPages = 0

        while (currentCoroutineContext().isActive) {

            // 사용자가 감시 도중에 체크를 바꿀 수 있으므로 사이클마다 다시 읽는다.
            val selection = activeSelection
            if (selection.isEmpty) {
                logger.log(LogCode.WATCH_STOP, "선택된 열차가 없습니다")
                updateStatus {
                    copy(
                        state = WatchState.STOPPED,
                        nextCheckInMs = null,
                        message = "선택된 열차가 없어 감시를 멈췄습니다.",
                    )
                }
                return
            }

            // 1) 페이지 준비
            if (skipRefresh) {
                skipRefresh = false
                logger.log(LogCode.PAGE_LOAD_START, "현재 화면 분석 (재조회 없음)")
            } else {
                updateStatus { copy(state = WatchState.LOADING) }
                logger.log(LogCode.PAGE_LOAD_START, "조회하기 버튼 탭")

                val outcome = host.requery(
                    timeoutMs = config.pageTimeoutMs,
                    settleTimeoutMs = config.researchSettleMs,
                    // 탭이 실제로 발생한 즉시 기록한다. 갱신이 안 될 때
                    // "누르지 못한 것"과 "눌렸는데 결과가 같은 것"을 구분하기 위한 로그다.
                    onClick = { detail -> logger.log(LogCode.RESEARCH_CLICKED, detail) },
                )

                when (outcome) {
                    is PageOutcome.Finished -> {
                        logger.log(LogCode.PAGE_LOAD_FINISHED, outcome.url)
                    }

                    is PageOutcome.Updated -> {
                        logger.log(LogCode.PAGE_UPDATED, outcome.detail)
                    }

                    is PageOutcome.Settled -> {
                        // 좌석 상태가 그대로여서 응답이 같을 수도 있으므로 일단 분석해 본다.
                        logger.log(LogCode.PAGE_SETTLE_TIMEOUT, outcome.detail)
                    }

                    is PageOutcome.ButtonNotFound -> {
                        // 재시도하지 않고 곧바로 멈춘다.
                        // 버튼이 없는 이유는 대개 차단/오류 화면이라, 다시 눌러봐야
                        // 상황이 나아지지 않고 요청만 늘어난다.
                        logger.log(LogCode.RESEARCH_BUTTON_NOT_FOUND, outcome.detail)
                        val error = WatchError.RESEARCH_BUTTON_NOT_FOUND
                        fail(error, error.guide)
                        return
                    }

                    is PageOutcome.NotTappable -> {
                        // 조회 요청이 나가지 않았으므로 차단 위험은 없다.
                        // 사용자가 화면을 정리하면 곧바로 회복되는 상황이라
                        // 곧장 멈추지 않고 연속 오류로만 센다.
                        consecutiveErrors++
                        logger.log(LogCode.RESEARCH_NOT_TAPPABLE, outcome.detail)
                        val error = WatchError.RESEARCH_BUTTON_NOT_TAPPABLE
                        if (consecutiveErrors >= config.maxConsecutiveErrors) {
                            fail(error, "${error.guide} (연속 ${consecutiveErrors}회)")
                            return
                        }
                        updateStatus { copy(error = error, message = error.guide) }
                        waitForNextCycle(config)
                        continue
                    }

                    is PageOutcome.Deferred -> {
                        // 우리가 스스로 누르지 않기로 한 경우다. 오류가 아니므로
                        // 연속 오류로 세지 않고 다음 차례를 그대로 기다린다.
                        logger.log(LogCode.RESEARCH_DEFERRED, outcome.detail)
                        updateStatus { copy(message = outcome.detail) }
                        waitForNextCycle(config)
                        continue
                    }

                    is PageOutcome.Failed -> {
                        consecutiveErrors++
                        logger.log(
                            LogCode.PAGE_LOAD_ERROR,
                            "${outcome.code} ${outcome.description} ($consecutiveErrors)",
                        )
                        val error = toWatchError(outcome.code)
                        if (consecutiveErrors >= config.maxConsecutiveErrors) {
                            fail(error, "${error.guide} (연속 ${consecutiveErrors}회)")
                            return
                        }
                        updateStatus { copy(error = error, message = error.guide) }
                        waitForNextCycle(config)
                        continue
                    }
                }

                if (config.settleDelayMs > 0) delay(config.settleDelayMs)
            }

            // 2) DOM 분석
            updateStatus { copy(state = WatchState.ANALYZING) }
            logger.log(LogCode.DOM_PARSE_START)

            val snapshot: PageSnapshot = try {
                parser.parse(host.evaluate(parserScript))
            } catch (e: DomParseException) {
                consecutiveErrors++
                logger.log(LogCode.DOM_PARSE_ERROR, e.message)
                if (consecutiveErrors >= config.maxConsecutiveErrors) {
                    fail(WatchError.DOM_PARSE_ERROR, WatchError.DOM_PARSE_ERROR.guide)
                    return
                }
                updateStatus {
                    copy(error = WatchError.DOM_PARSE_ERROR, message = e.message)
                }
                waitForNextCycle(config)
                continue
            }

            consecutiveErrors = 0
            logger.log(LogCode.PAGE_STATUS, snapshot.status.name)
            snapshot.warnings.take(MAX_LOGGED_WARNINGS).forEach { logger.log(LogCode.DOM_WARNING, it) }

            when (snapshot.status) {
                PageStatus.LOGIN_REQUIRED -> {
                    fail(WatchError.LOGIN_REQUIRED, WatchError.LOGIN_REQUIRED.guide)
                    return
                }

                PageStatus.SESSION_EXPIRED -> {
                    fail(WatchError.SESSION_EXPIRED, WatchError.SESSION_EXPIRED.guide)
                    return
                }

                // 차단된 뒤에 계속 조회하면 차단이 길어진다. 재시도 없이 멈춘다.
                PageStatus.BLOCKED -> {
                    logger.log(LogCode.BLOCKED_DETECTED, snapshot.url)
                    fail(WatchError.BLOCKED, WatchError.BLOCKED.guide)
                    return
                }

                PageStatus.UNKNOWN_PAGE -> {
                    consecutiveUnknownPages++
                    if (consecutiveUnknownPages >= config.maxUnknownPages) {
                        fail(WatchError.UNKNOWN_PAGE, WatchError.UNKNOWN_PAGE.guide)
                        return
                    }
                    updateStatus {
                        copy(
                            lastCheckedAt = clock(),
                            error = WatchError.UNKNOWN_PAGE,
                            message = WatchError.UNKNOWN_PAGE.guide,
                        )
                    }
                    waitForNextCycle(config)
                    continue
                }

                PageStatus.NO_TRAIN, PageStatus.TRAIN_LIST -> {
                    consecutiveUnknownPages = 0
                }
            }

            // 3) 선택 판정
            logger.log(LogCode.TRAIN_COUNT, snapshot.trains.size.toString())
            logger.log(LogCode.SELECTION_CHECK)
            val result = engine.match(snapshot.trains, selection)
            logger.log(LogCode.MATCH_COUNT, result.matches.size.toString())

            updateStatus {
                copy(
                    lastCheckedAt = clock(),
                    cycleCount = cycleCount + 1,
                    trainCount = snapshot.trains.size,
                    foundCount = result.matches.size,
                    matches = result.matches,
                    trains = snapshot.trains,
                    searchDate = snapshot.searchDate,
                    error = null,
                    message = result.reason,
                )
            }

            // 4) 알림 + 중복 방지
            if (result.matched) {
                val newMatches = result.matches.filter { it.toKey() !in notifiedKeys }
                notifyMatches(newMatches, config)
            }
            // 현재 화면에서 더 이상 열려 있지 않은 키는 잊는다. (§20)
            notifiedKeys.retainAll(result.matches.map { it.toKey() }.toSet())

            // 4-1) 자동 예약. 알림을 보낸 **뒤에** 시도한다.
            //      클릭이 실패해도 사용자는 이미 알림을 받은 상태여야 한다.
            if (result.matched) {
                when (tryReserve(result.matches, snapshot, config)) {
                    // 예약 화면으로 넘어갔다. 이 페이지에는 조회하기 버튼이 없으므로 감시를 끝낸다.
                    ReserveStep.RESERVED -> return

                    // "잔여석없음" 이었다. 목록 화면으로 되돌려 놨으므로 감시를 이어간다.
                    // 좌석이 실제로 열린 것이 아니므로 "발견"으로 치지 않는다.
                    ReserveStep.RETURNED -> {
                        waitForNextCycle(config)
                        continue
                    }

                    // 예약 실패 안내 화면에서 빠져나오지 못했다. 이 화면에는 조회하기
                    // 버튼이 없으므로 더 진행해도 요청만 헛돈다. 사용자가 화면을
                    // 직접 되돌리도록 안내하고 멈춘다.
                    ReserveStep.DISMISS_FAILED -> {
                        fail(
                            WatchError.UNKNOWN_PAGE,
                            "예약 안내 화면에서 조회 결과 화면으로 돌아가지 못했습니다. " +
                                "SRT 화면에서 직접 다시 조회한 뒤 감시를 시작하세요.",
                        )
                        return
                    }

                    ReserveStep.NONE -> Unit
                }
            }

            if (result.matched && config.stopOnMatch) {
                val first = result.matches.first()
                logger.log(LogCode.MATCH_DETAIL, first.describe())
                logger.log(LogCode.WATCH_PAUSED, "좌석 발견")
                updateStatus { copy(state = WatchState.MATCHED, nextCheckInMs = null) }
                return
            }

            // 5) 다음 사이클 예약
            waitForNextCycle(config)
        }
    }

    private fun notifyMatches(newMatches: List<SeatMatch>, config: WatchConfig) {
        if (newMatches.isEmpty()) {
            logger.log(LogCode.NOTIFICATION_SKIPPED, "이미 알린 좌석")
            return
        }
        newMatches.forEach { notifiedKeys += it.toKey() }

        if (!config.notificationEnabled) {
            logger.log(LogCode.NOTIFICATION_SKIPPED, "알림 꺼짐")
            return
        }

        val head = newMatches.first()
        notifier.notifyMatch(match = head, extraCount = newMatches.size - 1)
        logger.log(LogCode.NOTIFICATION_SENT, head.describe())
    }

    // ---------------------------------------------------------------- 자동 예약

    /**
     * 사용자가 고른 좌석의 [예약하기] 버튼을 누른다. (DESIGN.md §19)
     *
     * @return 이번 사이클을 어떻게 이어갈지. ([ReserveStep])
     *
     * 누르지 않는 경우:
     *  - 자동 예약 설정이 꺼져 있다.
     *  - 이미 그 좌석에 시도했다. 같은 열차를 두 번 잡으려 들지 않는다.
     *  - 화면에서 그 행이나 버튼을 확실히 특정하지 못했다.
     *
     * 실패해도 그 자리에서 재시도하지 않는다. 알림은 이미 나갔으므로 사용자가 직접
     * 예약하면 되고, 잘못 누르는 것보다 안 누르는 편이 안전하다.
     * 예외는 "잔여석없음"([ReserveOutcome.SoldOut]) 하나뿐이다. 이때는 화면을
     * 목록으로 되돌리고, 다음 사이클에 좌석이 다시 열려 보이면 한 번 더 눌러 본다.
     * (횟수는 [WatchConfig.maxSoldOutRetries] 로 제한한다)
     */
    private suspend fun tryReserve(
        matches: List<SeatMatch>,
        snapshot: PageSnapshot,
        config: WatchConfig,
    ): ReserveStep {
        if (!config.autoReserveEnabled) return ReserveStep.NONE

        // [SelectionEngine] 이 이미 AVAILABLE 만 남겼지만, 자동 클릭은 되돌릴 수 없는
        // 동작이라 여기서 한 번 더 확인한다.
        val candidate = matches.firstOrNull {
            it.toKey() !in reserveAttemptedKeys &&
                it.train.seatStatusOf(it.seatClass) == SeatStatus.AVAILABLE
        } ?: return ReserveStep.NONE

        reserveAttemptedKeys += candidate.toKey()

        val ref = snapshot.rowRefOf(candidate.train)
        if (ref == null || !ref.usable) {
            return recordReserveFailure(
                candidate,
                ReserveResult.ROW_NOT_FOUND,
                "화면에서 그 열차의 행 정보를 읽지 못했습니다",
            )
        }

        val target = ReserveTarget(
            rowKey = ref.rowKey,
            rowIndex = ref.rowIndex,
            cellIndex = ref.cellIndexOf(candidate.seatClass),
            trainNumber = candidate.train.trainNumber,
            departureTime = candidate.train.departureTime.toString(),
            seatLabel = candidate.seatClass.label,
        )

        logger.log(
            LogCode.RESERVE_START,
            "${candidate.describe()} row=${ref.rowIndex} cell=${target.cellIndex}",
        )
        updateStatus { copy(message = "${candidate.seatClass.label} [예약하기] 를 누르는 중…") }

        val outcome = host.clickReserve(
            target = target,
            timeoutMs = config.reserveTimeoutMs,
            settleTimeoutMs = config.reserveSettleMs,
            onClick = { detail -> logger.log(LogCode.RESERVE_CLICKED, detail) },
        )

        return when (outcome) {
            is ReserveOutcome.Clicked -> {
                logger.log(LogCode.RESERVE_SUCCEEDED, outcome.detail)
                updateStatus {
                    copy(
                        state = WatchState.RESERVED,
                        nextCheckInMs = null,
                        error = null,
                        reserve = ReserveAttempt(candidate, ReserveResult.CLICKED, outcome.detail),
                        message = "${candidate.train.summary()} ${candidate.seatClass.label} " +
                            "[예약하기] 를 눌렀습니다. 좌석 선택과 결제는 직접 진행하세요.",
                    )
                }
                // 여기서부터 감시는 끝이고, 남은 일은 사용자가 이 화면을 보게 만드는 것뿐이다.
                startReserveReminder(candidate, config)
                ReserveStep.RESERVED
            }

            is ReserveOutcome.SoldOut -> handleSoldOut(candidate, config, outcome.detail)

            is ReserveOutcome.NoChange ->
                recordReserveFailure(candidate, ReserveResult.NO_CHANGE, outcome.detail)

            is ReserveOutcome.RowNotFound ->
                recordReserveFailure(candidate, ReserveResult.ROW_NOT_FOUND, outcome.detail)

            is ReserveOutcome.ButtonNotFound ->
                recordReserveFailure(candidate, ReserveResult.BUTTON_NOT_FOUND, outcome.detail)

            is ReserveOutcome.NotTappable ->
                recordReserveFailure(candidate, ReserveResult.NOT_TAPPABLE, outcome.detail)

            is ReserveOutcome.Failed ->
                recordReserveFailure(candidate, ReserveResult.FAILED, outcome.detail)
        }
    }

    /**
     * "잔여석없음" 안내가 뜬 경우. (DESIGN.md §19-2)
     *
     * 눌렀을 때는 열려 있었지만 그사이 다른 사람이 먼저 잡은 것이다.
     * 오류가 아니므로 감시를 멈추지 않는다. 다만 그 화면에는 [조회하기] 버튼이 없어
     * 다음 사이클을 진행할 수 없으므로, **목록 화면까지 되돌린 뒤** 감시를 이어간다.
     *
     * 되돌리기는 **뒤로 가기만** 쓴다. 안내 화면의 [확인] 버튼을 누르면 조회 폼이
     * 새로 열려서 사용자가 넣어 둔 조회 조건이 초기화되기 때문이다. (§19-2)
     * 되돌리지 못하면 그 화면에서 억지로 무언가를 더 누르지 않고 감시를 멈춘다.
     */
    private suspend fun handleSoldOut(
        match: SeatMatch,
        config: WatchConfig,
        detail: String,
    ): ReserveStep {
        val key = match.toKey()
        val attempts = (soldOutCounts[key] ?: 0) + 1
        soldOutCounts[key] = attempts
        logger.log(LogCode.RESERVE_SOLD_OUT, "${match.describe()} ($attempts) $detail")

        // 취소표를 노릴 때 "남이 먼저 잡음"은 흔한 일이라, 한 번 겪었다고 그 칸을
        // 포기하지 않는다. 다음 사이클에 다시 열려 보이면 한 번 더 눌러 본다.
        // 다만 계속 실패하는 칸이라면 요청만 늘어나므로 횟수를 제한한다.
        val retryable = attempts < config.maxSoldOutRetries
        if (retryable) {
            reserveAttemptedKeys -= key
        } else {
            logger.log(LogCode.RESERVE_SKIPPED, "${match.describe()} 잔여석없음 ${attempts}회 - 더 누르지 않음")
        }

        updateStatus {
            copy(
                state = WatchState.WAITING,
                error = null,
                reserve = ReserveAttempt(match, ReserveResult.SOLD_OUT, detail),
                message = "${match.train.summary()} ${match.seatClass.label} — " +
                    "누르는 사이에 좌석이 나갔습니다. " +
                    if (retryable) "목록으로 돌아가 계속 감시합니다." else "이 칸은 더 누르지 않고 알림만 보냅니다.",
            )
        }

        // 안내 화면에서 뒤로 가기로 조회 결과 화면(조건 그대로)까지 되돌아간다.
        val outcome = host.dismissReserveResult(
            timeoutMs = config.reserveTimeoutMs,
            settleTimeoutMs = config.reserveSettleMs,
            onClick = { detail -> logger.log(LogCode.RESERVE_DISMISSED, detail) },
        )

        return when (outcome) {
            is PageOutcome.Finished, is PageOutcome.Updated -> {
                logger.log(LogCode.RESERVE_DISMISSED, "목록 화면 복귀: ${outcome.detail}")
                ReserveStep.RETURNED
            }

            else -> {
                logger.log(LogCode.RESERVE_DISMISS_FAILED, outcome.detail)
                ReserveStep.DISMISS_FAILED
            }
        }
    }

    /**
     * 예약 클릭 실패를 기록한다. 감시 상태는 오류로 바꾸지 않는다.
     * 좌석을 찾은 것은 사실이고 알림도 나갔으므로, "발견" 화면에 실패 사유만 덧붙인다.
     *
     * @return 항상 [ReserveStep.NONE]. [tryReserve] 의 반환값으로 그대로 쓰기 위한 것이다.
     */
    private fun recordReserveFailure(
        match: SeatMatch,
        result: ReserveResult,
        detail: String,
    ): ReserveStep {
        logger.log(LogCode.RESERVE_FAILED, "${result.name} $detail")
        updateStatus {
            copy(
                reserve = ReserveAttempt(match, result, detail),
                message = "${result.label} · 아래 SRT 화면에서 직접 예약하세요.",
            )
        }
        return ReserveStep.NONE
    }

    /**
     * 다음 사이클까지 대기한다. 대기 시간은 설정된 범위 안에서 매번 새로 뽑으므로
     * 요청 주기가 일정하게 반복되지 않는다.
     */
    private suspend fun waitForNextCycle(config: WatchConfig) {
        val interval = scheduler.nextInterval(config.minIntervalMs, config.maxIntervalMs)
        logger.log(LogCode.NEXT_RELOAD, "${interval}ms")
        updateStatus { copy(state = WatchState.WAITING) }
        scheduler.waitForNext(interval) { remaining ->
            updateStatus { copy(nextCheckInMs = remaining) }
        }
    }

    private fun fail(error: WatchError, message: String) {
        updateStatus {
            copy(
                state = WatchState.ERROR,
                error = error,
                message = message,
                nextCheckInMs = null,
            )
        }
    }

    private fun toWatchError(errorCode: Int): WatchError = when (errorCode) {
        // WebViewClient.ERROR_HOST_LOOKUP / ERROR_CONNECT / ERROR_TIMEOUT / ERROR_IO 등
        -2, -6, -7, -8 -> WatchError.NETWORK_ERROR
        else -> WatchError.PAGE_LOAD_ERROR
    }

    private inline fun updateStatus(transform: WatchStatus.() -> WatchStatus) {
        _status.update { it.transform() }
    }

    private companion object {
        const val MAX_LOGGED_WARNINGS = 3
    }

    /** 자동 예약을 시도한 뒤 감시 루프가 이어갈 방향. */
    private enum class ReserveStep {
        /** 누르지 않았거나 눌렀지만 화면이 그대로다. 평소대로 이어간다. */
        NONE,

        /** 예약 화면으로 넘어갔다. 감시를 끝낸다. */
        RESERVED,

        /** "잔여석없음" 이어서 목록 화면으로 되돌렸다. 다음 사이클부터 다시 감시한다. */
        RETURNED,

        /** "잔여석없음" 화면에서 되돌아가지 못했다. 감시를 멈춘다. */
        DISMISS_FAILED,
    }
}
