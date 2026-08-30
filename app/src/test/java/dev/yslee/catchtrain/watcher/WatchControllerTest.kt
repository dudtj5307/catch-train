package dev.yslee.catchtrain.watcher

import dev.yslee.catchtrain.domain.SeatClass
import dev.yslee.catchtrain.domain.SeatMatch
import dev.yslee.catchtrain.domain.SeatSelection
import dev.yslee.catchtrain.domain.TrainKey
import dev.yslee.catchtrain.domain.WatchSelection
import dev.yslee.catchtrain.notification.MatchNotifier
import dev.yslee.catchtrain.parser.KtxParser
import dev.yslee.catchtrain.webview.PageHost
import dev.yslee.catchtrain.webview.PageOutcome
import dev.yslee.catchtrain.webview.ReserveOutcome
import dev.yslee.catchtrain.webview.ReserveTarget
import dev.yslee.catchtrain.webview.SeatSelectOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

@OptIn(ExperimentalCoroutinesApi::class)
class WatchControllerTest {

    /**
     * 예매가 2단계라서 가짜 호스트도 두 단계를 따로 흉내낸다. (DESIGN.md §38-6)
     *
     * [selectOutcome] 이 [SeatSelectOutcome.Selected] 일 때만 2단계로 넘어간다.
     * 실제 [PageHost] 계약과 같아야 테스트가 의미를 갖는다.
     */
    private class FakePageHost(
        var json: String,
        var outcome: PageOutcome = PageOutcome.Updated("sig 변경"),
        var selectOutcome: SeatSelectOutcome = SeatSelectOutcome.Selected("active 확인"),
        var reserveOutcome: ReserveOutcome = ReserveOutcome.Clicked("예약 화면"),
    ) : PageHost {
        var requeryCount = 0
        var evaluateCount = 0

        /** 로그인 확인 스크립트가 돌려줄 결과. */
        var loginJson: String = """{"state":"UNKNOWN","detail":"표시 없음"}"""
        var dismissCount = 0
        var dismissOutcome: PageOutcome = PageOutcome.Updated("목록 복귀")
        val clickDetails = mutableListOf<String>()

        /** 1단계로 고른 좌석 칸. */
        val selectTargets = mutableListOf<ReserveTarget>()

        /** 2단계로 누른 [예매]. 1단계가 실패하면 비어 있어야 한다. */
        val reserveTargets = mutableListOf<ReserveTarget>()

        override val currentUrl: String = "https://www.korail.com/ticket/search/list"

        override suspend fun loadStartUrl() = Unit

        override suspend fun requery(
            timeoutMs: Long,
            settleTimeoutMs: Long,
            onClick: (String) -> Unit,
        ): PageOutcome {
            requeryCount++
            if (outcome !is PageOutcome.ButtonNotFound) {
                val detail = "selector div.ticketSrchWrap button [열차조회]"
                clickDetails += detail
                onClick(detail)
            }
            return outcome
        }

        override suspend fun selectSeat(
            target: ReserveTarget,
            timeoutMs: Long,
            settleTimeoutMs: Long,
            onClick: (String) -> Unit,
        ): SeatSelectOutcome {
            selectTargets += target
            onClick("1단계 ${target.trainNumber} ${target.seatLabel} 탭")
            return selectOutcome
        }

        override suspend fun confirmReserve(
            target: ReserveTarget,
            timeoutMs: Long,
            settleTimeoutMs: Long,
            onClick: (String) -> Unit,
        ): ReserveOutcome {
            reserveTargets += target
            onClick("2단계 ${target.trainNumber} [예매] 탭")
            return reserveOutcome
        }

        override suspend fun dismissReserveResult(
            timeoutMs: Long,
            settleTimeoutMs: Long,
            onClick: (String) -> Unit,
        ): PageOutcome {
            dismissCount++
            onClick("뒤로 가기")
            return dismissOutcome
        }

        override suspend fun evaluate(script: String): String {
            // 로그인 확인은 DOM 분석과 다른 스크립트다. 결과도 따로 돌려준다.
            // (evaluateCount 는 감시 루프가 DOM 을 몇 번 읽었는지를 세는 값이라 건드리지 않는다)
            if (script.contains("LOGGED_OUT")) return loginJson
            evaluateCount++
            return json
        }
    }

    private class RecordingNotifier : MatchNotifier {
        val sent = mutableListOf<SeatMatch>()
        var cancelCount = 0

        /** 결제 재촉 알림. (몇 번째, 경과 시간) */
        val reminders = mutableListOf<Pair<Int, Long>>()
        var reminderCancelCount = 0

        override fun notifyMatch(match: SeatMatch, extraCount: Int) {
            sent += match
        }

        override fun notifyReserveReminder(match: SeatMatch, repeatIndex: Int, elapsedMs: Long) {
            reminders += repeatIndex to elapsedMs
        }

        /** 감시가 스스로 멈췄다는 알림. (제목, 본문) */
        val stopped = mutableListOf<Pair<String, String>>()

        override fun notifyWatchStopped(title: String, body: String) {
            stopped += title to body
        }

        override fun cancelReserveReminder() {
            reminderCancelCount++
        }

        override fun cancelAll() {
            cancelCount++
        }
    }

    private val trainKey = TrainKey("305", LocalTime.of(18, 30))

    /** 18:30 305 의 일반실 한 칸만 감시한다. */
    private val selection = WatchSelection(setOf(SeatSelection(trainKey, SeatClass.GENERAL)))

    /** 칸 순번은 `[0]=일반실, [1]=특실` 이다. (§38-3) */
    private fun json(
        generalStatus: String,
        firstClassStatus: String = "SOLD_OUT",
    ) = """
        {
          "status": "TRAIN_LIST",
          "url": "https://www.korail.com/ticket/search/list",
          "title": "t",
          "trains": [
            {
              "trainNumber": "305",
              "trainType": "KTX-산천",
              "departureStation": "동탄",
              "arrivalStation": "김천구미",
              "departureTime": "18:30",
              "arrivalTime": "19:36",
              "generalSeatStatus": "$generalStatus",
              "firstClassSeatStatus": "$firstClassStatus",
              "rowKey": "2:abc123",
              "rowIndex": 0,
              "generalCellIndex": 0,
              "firstClassCellIndex": 1
            }
          ]
        }
    """.trimIndent()

    /**
     * @param clock 시간 예산을 재는 시계. 기본값은 실제 벽시계라 [advanceTimeBy] 로
     *   흘려보낸 **가상 시간이 반영되지 않는다.** [WatchConfig.pageWaitMs] 처럼
     *   경과 시간으로 끝나는 동작을 검사하려면 `{ testScheduler.currentTime }` 을 준다.
     *   (DESIGN.md §39)
     */
    private fun controllerFor(
        host: FakePageHost,
        notifier: MatchNotifier = RecordingNotifier(),
        logger: WatchLogger = WatchLogger(),
        scope: CoroutineScope,
        clock: () -> Long = System::currentTimeMillis,
    ) = WatchController(
        host = host,
        parser = KtxParser(),
        notifier = notifier,
        logger = logger,
        scope = scope,
        clock = clock,
    )

    @Test
    fun `선택한 좌석이 열리면 알림을 보내고 감시를 멈춘다`() = runTest {
        val host = FakePageHost(json("AVAILABLE"))
        val notifier = RecordingNotifier()
        val controller = controllerFor(host, notifier, scope = backgroundScope)

        controller.start(selection, WatchConfig(stopOnMatch = true, autoReserveEnabled = false))
        runCurrent()

        assertEquals(WatchState.MATCHED, controller.status.value.state)
        assertEquals(1, notifier.sent.size)
        assertEquals("305", notifier.sent.single().train.trainNumber)
        // 첫 사이클은 재조회 없이 현재 화면을 분석한다.
        assertEquals(0, host.requeryCount)
        assertEquals(1, host.evaluateCount)
    }

    @Test
    fun `선택하지 않은 좌석이 열려도 반응하지 않는다`() = runTest {
        // 특실만 열렸다. 사용자가 고른 것은 일반실이다.
        val host = FakePageHost(json(generalStatus = "SOLD_OUT", firstClassStatus = "AVAILABLE"))
        val notifier = RecordingNotifier()
        val controller = controllerFor(host, notifier, scope = backgroundScope)

        controller.start(
            selection,
            WatchConfig(minIntervalMs = 2_000L, maxIntervalMs = 2_000L, autoReserveEnabled = true),
        )
        advanceTimeBy(5_000L)
        runCurrent()

        assertTrue(controller.status.value.state.isRunning)
        assertEquals(0, notifier.sent.size)
        assertTrue(host.reserveTargets.isEmpty())

        controller.stop()
    }

    @Test
    fun `특실을 고르면 특실 칸을 누른다`() = runTest {
        val host = FakePageHost(json(generalStatus = "AVAILABLE", firstClassStatus = "AVAILABLE"))
        val controller = controllerFor(host, scope = backgroundScope)

        controller.start(
            WatchSelection(setOf(SeatSelection(trainKey, SeatClass.FIRST_CLASS))),
            WatchConfig(autoReserveEnabled = true),
        )
        runCurrent()

        val target = host.selectTargets.single()
        assertEquals("특실", target.seatLabel)
        // 코레일 목록에서 특실은 일반실보다 **오른쪽** 칸이다. SRT 와 반대다. (§38-3)
        assertEquals(1, target.cellIndex)
    }

    @Test
    fun `선택이 비어 있으면 감시를 멈춘다`() = runTest {
        val host = FakePageHost(json("AVAILABLE"))
        val controller = controllerFor(host, scope = backgroundScope)

        controller.start(WatchSelection.empty(), WatchConfig())
        runCurrent()

        assertEquals(WatchState.STOPPED, controller.status.value.state)
        assertEquals(0, host.evaluateCount)
    }

    @Test
    fun `감시 중에 선택을 바꾸면 다음 사이클부터 반영된다`() = runTest {
        val host = FakePageHost(json(generalStatus = "SOLD_OUT", firstClassStatus = "AVAILABLE"))
        val notifier = RecordingNotifier()
        val controller = controllerFor(host, notifier, scope = backgroundScope)

        controller.start(
            selection,
            WatchConfig(
                minIntervalMs = 2_000L,
                maxIntervalMs = 2_000L,
                stopOnMatch = false,
                autoReserveEnabled = false,
            ),
        )
        runCurrent()
        assertEquals(0, notifier.sent.size)

        // 감시를 재시작하지 않고 체크만 특실로 옮긴다.
        controller.updateSelection(
            WatchSelection(setOf(SeatSelection(trainKey, SeatClass.FIRST_CLASS))),
        )
        advanceTimeBy(3_000L)
        runCurrent()

        assertEquals(1, notifier.sent.size)
        assertEquals(SeatClass.FIRST_CLASS, notifier.sent.single().seatClass)

        controller.stop()
    }

    @Test
    fun `선택한 좌석이 매진이면 간격마다 재조회한다`() = runTest {
        val host = FakePageHost(json("SOLD_OUT"))
        val notifier = RecordingNotifier()
        val controller = controllerFor(host, notifier, scope = backgroundScope)

        controller.start(selection, WatchConfig(minIntervalMs = 2_000L, maxIntervalMs = 2_000L))
        advanceTimeBy(7_000L)
        runCurrent()

        assertTrue("주기적으로 재조회해야 한다", host.requeryCount >= 3)
        assertTrue(controller.status.value.state.isRunning)
        assertEquals(0, notifier.sent.size)

        controller.stop()
        runCurrent()
        assertEquals(WatchState.STOPPED, controller.status.value.state)
    }

    @Test
    fun `재조회는 조회하기 클릭으로만 하고 클릭 사실을 로그에 남긴다`() = runTest {
        val host = FakePageHost(json("SOLD_OUT"))
        val logger = WatchLogger()
        val controller = controllerFor(host, logger = logger, scope = backgroundScope)

        controller.start(selection, WatchConfig(minIntervalMs = 2_000L, maxIntervalMs = 2_000L))
        advanceTimeBy(5_000L)
        runCurrent()

        assertTrue(host.requeryCount >= 2)
        assertEquals("재조회마다 클릭이 발생해야 한다", host.requeryCount, host.clickDetails.size)

        val clicked = logger.entries.value.filter { it.code == LogCode.RESEARCH_TRIGGERED }
        assertEquals(host.requeryCount, clicked.size)
        assertTrue(clicked.first().detail!!.contains("열차조회"))

        controller.stop()
    }

    @Test
    fun `조회 버튼을 못 찾으면 재시도 없이 즉시 중지한다`() = runTest {
        val host = FakePageHost(
            json = json("AVAILABLE"),
            outcome = PageOutcome.ButtonNotFound("후보 12개 확인, 조회 버튼 없음"),
        )
        val logger = WatchLogger()
        val controller = controllerFor(host, logger = logger, scope = backgroundScope)

        // 첫 사이클은 재조회를 건너뛰므로, 계속 감시 설정으로 두 번째 사이클부터 실패시킨다.
        controller.start(
            selection,
            WatchConfig(
                stopOnMatch = false,
                autoReserveEnabled = false,
                minIntervalMs = 2_000L,
                maxIntervalMs = 2_000L,
            ),
        )
        advanceTimeBy(20_000L)
        runCurrent()

        assertEquals(WatchState.ERROR, controller.status.value.state)
        assertEquals(WatchError.REFRESH_FAILED, controller.status.value.error)
        assertTrue(logger.entries.value.any { it.code == LogCode.RESEARCH_FAILED })
        // 클릭이 안 됐으므로 클릭 로그는 없어야 한다.
        assertTrue(logger.entries.value.none { it.code == LogCode.RESEARCH_TRIGGERED })
        // 재시도하지 않는다. 실패 뒤에 요청이 더 나가면 차단만 길어진다.
        assertEquals("한 번만 시도해야 한다", 1, host.requeryCount)
    }

    @Test
    fun `차단 안내 페이지를 만나면 즉시 중지한다`() = runTest {
        val host = FakePageHost(
            """{"status":"BLOCKED","url":"https://www.korail.com/ticket/search/list","trains":[]}""",
        )
        val logger = WatchLogger()
        val controller = controllerFor(host, logger = logger, scope = backgroundScope)

        controller.start(
            selection,
            WatchConfig(stopOnMatch = false, minIntervalMs = 2_000L, maxIntervalMs = 2_000L),
        )
        advanceTimeBy(20_000L)
        runCurrent()

        assertEquals(WatchState.ERROR, controller.status.value.state)
        assertEquals(WatchError.BLOCKED, controller.status.value.error)
        assertTrue(logger.entries.value.any { it.code == LogCode.BLOCKED_DETECTED })
        // 첫 사이클에서 바로 멈추므로 조회 요청은 한 번도 나가지 않는다.
        assertEquals(0, host.requeryCount)
    }

    @Test
    fun `화면 전환 없이 DOM 만 갱신된 경우에도 분석을 계속한다`() = runTest {
        val host = FakePageHost(
            json = json("SOLD_OUT"),
            outcome = PageOutcome.Updated("mut=3 sig=8:abc→8:def"),
        )
        val logger = WatchLogger()
        val controller = controllerFor(host, logger = logger, scope = backgroundScope)

        controller.start(selection, WatchConfig(minIntervalMs = 2_000L, maxIntervalMs = 2_000L))
        advanceTimeBy(5_000L)
        runCurrent()

        assertTrue(controller.status.value.state.isRunning)
        assertTrue(logger.entries.value.any { it.code == LogCode.PAGE_UPDATED })

        controller.stop()
    }

    @Test
    fun `같은 좌석은 다시 알리지 않는다`() = runTest {
        val host = FakePageHost(json("AVAILABLE"))
        val notifier = RecordingNotifier()
        val controller = controllerFor(host, notifier, scope = backgroundScope)

        // 발견 후에도 계속 감시하는 설정
        controller.start(
            selection,
            WatchConfig(
                minIntervalMs = 2_000L,
                maxIntervalMs = 2_000L,
                stopOnMatch = false,
                autoReserveEnabled = false,
            ),
        )
        advanceTimeBy(7_000L)
        runCurrent()

        assertEquals("중복 알림이 없어야 한다", 1, notifier.sent.size)
        assertTrue(host.requeryCount >= 2)

        controller.stop()
    }

    @Test
    fun `매진으로 바뀌었다가 다시 예약 가능해지면 새로 알린다`() = runTest {
        val host = FakePageHost(json("AVAILABLE"))
        val notifier = RecordingNotifier()
        val controller = controllerFor(host, notifier, scope = backgroundScope)

        controller.start(
            selection,
            WatchConfig(
                minIntervalMs = 2_000L,
                maxIntervalMs = 2_000L,
                stopOnMatch = false,
                autoReserveEnabled = false,
            ),
        )
        runCurrent()
        assertEquals(1, notifier.sent.size)

        host.json = json("SOLD_OUT")
        advanceTimeBy(3_000L)
        runCurrent()

        host.json = json("AVAILABLE")
        advanceTimeBy(3_000L)
        runCurrent()

        assertEquals(2, notifier.sent.size)
        controller.stop()
    }

    /**
     * 코레일은 비로그인 상태에서도 조회 결과가 그대로 보인다. (§38-7)
     * 그래서 화면이 TRAIN_LIST 여도 로그인 여부는 따로 확인해야 한다.
     */
    @Test
    fun `조회 결과 화면이어도 로그인 여부는 따로 확인한다`() = runTest {
        val host = FakePageHost(json("SOLD_OUT"))
        host.loginJson = """{"state":"LOGGED_OUT","detail":"로그인 href=/cmc/01/selectLoginForm.do"}"""
        val logger = WatchLogger()
        val controller = controllerFor(host, logger = logger, scope = backgroundScope)

        val result = controller.checkLogin()

        assertTrue(result.blocksWatch)
        // 확인은 DOM 만 읽는다. 조회 요청은 나가지 않는다.
        assertEquals(0, host.requeryCount)
        assertTrue(logger.entries.value.any { it.code == LogCode.LOGIN_STATE })
    }

    @Test
    fun `로그인 상태면 감시를 막지 않는다`() = runTest {
        val host = FakePageHost(json("SOLD_OUT"))
        host.loginJson = """{"state":"LOGGED_IN","detail":"로그아웃 href=/cmc/01/logout.do"}"""
        val controller = controllerFor(host, scope = backgroundScope)

        assertFalse(controller.checkLogin().blocksWatch)
    }

    /**
     * 코레일 세션은 시간이 지나면 서버에서 풀린다. 그대로 감시를 이어가면 좌석이 열려
     * [예매] 를 누르는 그 순간에 튕긴다. 그래서 사이클마다 확인하고 즉시 멈춘다. (§27-1)
     */
    @Test
    fun `감시 도중 로그인이 풀리면 멈추고 알린다`() = runTest {
        val host = FakePageHost(json("SOLD_OUT"))
        val notifier = RecordingNotifier()
        val controller = controllerFor(host, notifier, scope = backgroundScope)

        controller.start(
            selection,
            WatchConfig(minIntervalMs = 2_000L, maxIntervalMs = 2_000L),
        )
        runCurrent()
        // 첫 사이클은 로그인 상태를 판정하지 못했다(UNKNOWN). 멈추지 않는다.
        assertTrue(controller.status.value.state.isRunning)

        host.loginJson = """{"state":"LOGGED_OUT","detail":"로그인 href=/ticket/login"}"""
        advanceTimeBy(3_000L)
        runCurrent()

        assertEquals(WatchState.ERROR, controller.status.value.state)
        assertEquals(WatchError.SESSION_EXPIRED, controller.status.value.error)
        assertTrue(notifier.stopped.single().first.contains("로그인"))
        // 좌석 발견 알림과는 다른 알림이다.
        assertTrue(notifier.sent.isEmpty())
    }

    /** 판정하지 못한 것은 로그아웃이 아니다. 마커 하나를 놓쳤다고 감시를 끊지 않는다. (대원칙 6) */
    @Test
    fun `로그인 여부를 판정하지 못하면 감시를 이어간다`() = runTest {
        val host = FakePageHost(json("SOLD_OUT"))
        val notifier = RecordingNotifier()
        val controller = controllerFor(host, notifier, scope = backgroundScope)

        controller.start(
            selection,
            WatchConfig(minIntervalMs = 2_000L, maxIntervalMs = 2_000L),
        )
        advanceTimeBy(5_000L)
        runCurrent()

        assertTrue(controller.status.value.state.isRunning)
        assertTrue(notifier.stopped.isEmpty())

        controller.stop()
    }

    // ------------------------------------------------- 접속 대기열 (DESIGN.md §39)

    /** 대기 화면·차단 화면처럼 열차가 없는 화면. */
    private fun pageJson(status: String) =
        """{"status":"$status","url":"https://www.korail.com/x","title":"t","trains":[]}"""

    /**
     * 대기열에 걸린 화면(`UNKNOWN_PAGE`)에서 **새로고침이 나가면 안 된다.** (§39)
     *
     * 이것이 사용자가 겪은 문제의 핵심이다. 대기 중에 새로고침하면 대기 순번이
     * 날아가서 대기가 영영 끝나지 않는다.
     */
    @Test
    fun `화면이 확정되기 전에는 새로고침하지 않고 제자리에서 다시 읽는다`() = runTest {
        val host = FakePageHost(pageJson("UNKNOWN_PAGE"))
        val controller = controllerFor(host, scope = backgroundScope, clock = { testScheduler.currentTime })

        controller.start(
            selection,
            WatchConfig(
                minIntervalMs = 0L,
                maxIntervalMs = 0L,
                pageWaitPollMs = 500L,
                pageWaitFirstMs = 60_000L,
            ),
        )
        advanceTimeBy(10_000L)
        runCurrent()

        // 첫 사이클은 새로고침 없이 현재 화면을 본다. 기다리는 동안에도 늘지 않아야 한다.
        assertEquals(0, host.requeryCount)
        // 대신 DOM 은 여러 번 다시 읽었다. (요청이 아니라 읽기다)
        assertTrue("판독이 반복되어야 한다 (${host.evaluateCount}회)", host.evaluateCount > 5)
        assertTrue(controller.status.value.state.isRunning)

        controller.stop()
    }

    /** 대기가 풀리면 다음 사이클을 기다리지 않고 **그 자리에서** 이어서 분석한다. (§39) */
    @Test
    fun `기다리는 동안 목록이 나타나면 그대로 이어서 판정한다`() = runTest {
        val host = FakePageHost(pageJson("UNKNOWN_PAGE"))
        val notifier = RecordingNotifier()
        val controller = controllerFor(host, notifier, scope = backgroundScope, clock = { testScheduler.currentTime })

        controller.start(
            selection,
            WatchConfig(
                autoReserveEnabled = false,
                pageWaitPollMs = 500L,
                pageWaitFirstMs = 60_000L,
            ),
        )
        advanceTimeBy(5_000L)
        runCurrent()
        assertTrue(controller.status.value.state.isRunning)

        // 대기가 풀려 목록이 그려졌다.
        host.json = json("AVAILABLE")
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(WatchState.MATCHED, controller.status.value.state)
        assertEquals(1, notifier.sent.size)
        // 목록을 다시 보기까지 새로고침은 한 번도 나가지 않았다.
        assertEquals(0, host.requeryCount)
    }

    /**
     * 목록을 본 적이 있으면 [WatchConfig.pageWaitMs] 로 길게, 아직 못 봤으면
     * [WatchConfig.pageWaitFirstMs] 로 짧게 기다린다. (§39)
     *
     * 짧은 쪽이 있어야 조회 결과 화면이 아닌 곳에서 감시를 시작한 사용자를
     * 3분씩 세워 두지 않는다.
     */
    @Test
    fun `목록을 본 적이 없으면 짧게 끊고 안내한다`() = runTest {
        val host = FakePageHost(pageJson("UNKNOWN_PAGE"))
        val controller = controllerFor(host, scope = backgroundScope, clock = { testScheduler.currentTime })

        controller.start(
            selection,
            WatchConfig(
                minIntervalMs = 0L,
                maxIntervalMs = 0L,
                pageWaitPollMs = 500L,
                pageWaitFirstMs = 5_000L,
                pageWaitMs = 3 * 60_000L,
                maxUnknownPages = 1,
            ),
        )
        advanceTimeBy(2_000L)
        runCurrent()
        assertTrue("예산 안에서는 기다린다", controller.status.value.state.isRunning)

        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(WatchState.ERROR, controller.status.value.state)
        assertEquals(WatchError.UNKNOWN_PAGE, controller.status.value.error)
    }

    /** 목록을 한 번 본 뒤의 `UNKNOWN` 은 전이 중일 가능성이 높다. 길게 기다린다. (§39) */
    @Test
    fun `목록을 본 뒤에는 짧은 예산을 넘겨도 계속 기다린다`() = runTest {
        val host = FakePageHost(json("SOLD_OUT"))
        val controller = controllerFor(host, scope = backgroundScope, clock = { testScheduler.currentTime })

        controller.start(
            selection,
            WatchConfig(
                minIntervalMs = 1_000L,
                maxIntervalMs = 1_000L,
                pageWaitPollMs = 500L,
                pageWaitFirstMs = 5_000L,
                pageWaitMs = 3 * 60_000L,
                maxUnknownPages = 1,
            ),
        )
        runCurrent()
        // 첫 사이클에서 목록을 봤다.
        assertEquals(1, controller.status.value.trainCount)

        // 두 번째 사이클에서 대기열에 걸렸다.
        host.json = pageJson("UNKNOWN_PAGE")
        advanceTimeBy(1_500L)
        runCurrent()
        val refreshesWhenWaitBegan = host.requeryCount

        // 짧은 예산(5초)을 한참 넘겨도 멈추지 않고, 그동안 새로고침도 나가지 않는다.
        advanceTimeBy(30_000L)
        runCurrent()

        assertTrue(controller.status.value.state.isRunning)
        assertEquals(refreshesWhenWaitBegan, host.requeryCount)

        controller.stop()
    }

    /**
     * 차단·로그인·세션만료는 [dev.yslee.catchtrain.parser.PageStatus.isSettled] 라
     * **첫 판독에서 즉시** 빠져나간다. 기다림이 이 셋을 늦추면 안 된다. (§39)
     */
    @Test
    fun `차단 화면은 기다리지 않고 즉시 멈춘다`() = runTest {
        val host = FakePageHost(pageJson("BLOCKED"))
        val controller = controllerFor(host, scope = backgroundScope, clock = { testScheduler.currentTime })

        controller.start(
            selection,
            WatchConfig(pageWaitFirstMs = 3 * 60_000L, pageWaitMs = 3 * 60_000L),
        )
        // 시간을 전혀 흘려보내지 않았는데도 이미 멈춰 있어야 한다.
        runCurrent()

        assertEquals(WatchState.ERROR, controller.status.value.state)
        assertEquals(WatchError.BLOCKED, controller.status.value.error)
        assertEquals(1, host.evaluateCount)
    }

    /** 열차가 0건인 조회는 확정된 답이다. 기다리지 않고 그대로 다음 사이클로 간다. (§39) */
    @Test
    fun `결과 0건은 기다리지 않는다`() = runTest {
        val host = FakePageHost(pageJson("NO_TRAIN"))
        val controller = controllerFor(host, scope = backgroundScope, clock = { testScheduler.currentTime })

        controller.start(
            selection,
            WatchConfig(
                minIntervalMs = 1_000L,
                maxIntervalMs = 1_000L,
                pageWaitFirstMs = 3 * 60_000L,
            ),
        )
        runCurrent()
        assertEquals(1, host.evaluateCount)

        // 기다리지 않았으므로 통상 간격 뒤에 곧바로 다음 새로고침이 나간다.
        advanceTimeBy(1_500L)
        runCurrent()
        assertEquals(1, host.requeryCount)

        controller.stop()
    }

    @Test
    fun `로그인 화면이면 오류 상태로 중지한다`() = runTest {
        val host = FakePageHost("""{"status":"LOGIN_REQUIRED","trains":[]}""")
        val controller = controllerFor(host, scope = backgroundScope)

        controller.start(selection, WatchConfig())
        runCurrent()

        assertEquals(WatchState.ERROR, controller.status.value.state)
        assertEquals(WatchError.LOGIN_REQUIRED, controller.status.value.error)
    }

    @Test
    fun `페이지 오류가 연속되면 감시를 중지한다`() = runTest {
        val host = FakePageHost(
            json = json("AVAILABLE"),
            outcome = PageOutcome.Failed(-2, "net::ERR_NAME_NOT_RESOLVED"),
        )
        val controller = controllerFor(host, scope = backgroundScope)

        // 첫 사이클은 재조회를 건너뛰므로, 계속 감시 설정으로 두 번째 사이클부터 실패시킨다.
        controller.start(
            selection,
            WatchConfig(
                stopOnMatch = false,
                autoReserveEnabled = false,
                maxConsecutiveErrors = 2,
                minIntervalMs = 2_000L,
                maxIntervalMs = 2_000L,
            ),
        )
        advanceTimeBy(20_000L)
        runCurrent()

        assertEquals(WatchState.ERROR, controller.status.value.state)
        assertEquals(WatchError.NETWORK_ERROR, controller.status.value.error)
    }

    // ------------------------------------------------------------ 목록 읽기

    @Test
    fun `scanTrains 는 재조회 없이 현재 화면만 읽는다`() = runTest {
        val host = FakePageHost(json("SOLD_OUT"))
        val controller = controllerFor(host, scope = backgroundScope)

        val trains = controller.scanTrains()

        assertEquals(1, trains?.size)
        assertEquals("305", trains?.single()?.trainNumber)
        assertEquals("조회 요청이 나가면 안 된다", 0, host.requeryCount)
        assertEquals(1, controller.status.value.trains.size)
    }

    @Test
    fun `조회 결과 화면이 아니면 scanTrains 는 null 을 돌려준다`() = runTest {
        val host = FakePageHost("""{"status":"UNKNOWN_PAGE","trains":[]}""")
        val controller = controllerFor(host, scope = backgroundScope)

        assertEquals(null, controller.scanTrains())
        assertEquals(0, host.requeryCount)
    }

    // ------------------------------------------------------------ 자동 예약 (§19)

    @Test
    fun `선택한 좌석이 열리면 두 단계를 모두 누르고 감시를 끝낸다`() = runTest {
        val host = FakePageHost(json("AVAILABLE"))
        val notifier = RecordingNotifier()
        val controller = controllerFor(host, notifier, scope = backgroundScope)

        controller.start(selection, WatchConfig(autoReserveEnabled = true))
        runCurrent()

        assertEquals(WatchState.RESERVED, controller.status.value.state)
        // 알림이 먼저다. 클릭이 실패하더라도 사용자는 알림을 받아야 한다.
        assertEquals(1, notifier.sent.size)

        val target = host.selectTargets.single()
        assertEquals("305", target.trainNumber)
        assertEquals("18:30", target.departureTime)
        // 일반실을 골랐으므로 왼쪽(0번) 칸을 눌러야 한다. (§38-3)
        assertEquals(0, target.cellIndex)
        assertEquals("2:abc123", target.rowKey)

        // 2단계는 1단계와 **같은 대상**을 그대로 넘겨받아야 한다.
        assertEquals(target, host.reserveTargets.single())

        val attempt = controller.status.value.reserve!!
        assertEquals(ReserveResult.CLICKED, attempt.result)
        assertEquals(ReserveStage.CONFIRM, attempt.stage)
    }

    @Test
    fun `예약대기는 발견으로 보지 않는다`() = runTest {
        val host = FakePageHost(json("WAITING"))
        val notifier = RecordingNotifier()
        val controller = controllerFor(host, notifier, scope = backgroundScope)

        controller.start(
            selection,
            WatchConfig(
                autoReserveEnabled = true,
                minIntervalMs = 2_000L,
                maxIntervalMs = 2_000L,
            ),
        )
        runCurrent()

        // 예약대기는 즉시 예약이 아니라 대기 신청이다. 알리지도, 누르지도 않는다.
        assertTrue(controller.status.value.state.isRunning)
        assertEquals(0, notifier.sent.size)
        assertTrue("좌석을 고르면 안 된다", host.selectTargets.isEmpty())
        assertTrue("예매를 누르면 안 된다", host.reserveTargets.isEmpty())

        controller.stop()
    }

    /** 1단계에서 멈춘 경우. 아직 아무것도 잡지 않았으므로 "발견" 화면 그대로 둔다. */
    @Test
    fun `좌석 칸을 고르지 못하면 알림은 남고 발견 상태를 유지한다`() = runTest {
        val host = FakePageHost(
            json = json("AVAILABLE"),
            selectOutcome = SeatSelectOutcome.CellNotFound("그사이 좌석이 닫힘"),
        )
        val notifier = RecordingNotifier()
        val logger = WatchLogger()
        val controller = controllerFor(host, notifier, logger, scope = backgroundScope)

        controller.start(selection, WatchConfig(autoReserveEnabled = true, stopOnMatch = true))
        runCurrent()

        assertEquals(WatchState.MATCHED, controller.status.value.state)
        assertEquals(1, notifier.sent.size)
        val attempt = controller.status.value.reserve!!
        assertEquals(ReserveResult.CELL_NOT_FOUND, attempt.result)
        assertEquals(ReserveStage.SELECT, attempt.stage)
        assertFalse("좌석은 골라지지 않았다", attempt.seatSelected)
        // 1단계가 실패했으면 2단계는 시도조차 하지 않는다.
        assertTrue(host.reserveTargets.isEmpty())
        // 감시 자체가 실패한 것은 아니므로 오류 상태로 만들지 않는다.
        assertEquals(null, controller.status.value.error)
        assertTrue(logger.entries.value.any { it.code == LogCode.RESERVE_FAILED })
    }

    /**
     * 2단계 버튼이 `예약대기신청` / `입석+좌석 예매` 인 경우. (§38-6-1)
     * 누르지 않고 사람에게 넘기되, 좌석은 골라 둔 채로 감시를 끝낸다.
     */
    @Test
    fun `예매 버튼이 아니면 좌석만 골라 두고 사람에게 넘긴다`() = runTest {
        val host = FakePageHost(
            json = json("AVAILABLE"),
            reserveOutcome = ReserveOutcome.NotAllowed("누를 수 있는 문구가 없음 (예약대기신청)"),
        )
        val notifier = RecordingNotifier()
        val logger = WatchLogger()
        val controller = controllerFor(
            host,
            notifier,
            logger,
            scope = backgroundScope,
        )

        controller.start(
            selection,
            WatchConfig(
                autoReserveEnabled = true,
                stopOnMatch = false,
                minIntervalMs = 2_000L,
                maxIntervalMs = 2_000L,
            ),
        )
        runCurrent()

        assertEquals(WatchState.SEAT_SELECTED, controller.status.value.state)
        val attempt = controller.status.value.reserve!!
        assertEquals(ReserveResult.NOT_ALLOWED, attempt.result)
        assertEquals(ReserveStage.CONFIRM, attempt.stage)
        assertTrue("좌석은 골라져 있다", attempt.seatSelected)
        assertEquals(null, controller.status.value.error)
        assertTrue(logger.entries.value.any { it.code == LogCode.RESERVE_HANDOVER })
        // 지금 화면을 봐야 하는 상태다. 재촉 알림이 울려야 한다. (§19-3)
        assertTrue(controller.status.value.reserveAlerting)

        // 여기서 재조회를 하면 골라 둔 선택이 지워진다. 감시는 멈춰 있어야 한다.
        val requeriesSoFar = host.requeryCount
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(requeriesSoFar, host.requeryCount)

        controller.stop()
    }

    @Test
    fun `같은 좌석에 예약을 두 번 시도하지 않는다`() = runTest {
        val host = FakePageHost(
            json = json("AVAILABLE"),
            // 1단계에서 막히면 감시는 계속된다. 그래도 같은 칸을 다시 누르지 않아야 한다.
            selectOutcome = SeatSelectOutcome.NotSelected("active 가 붙지 않음"),
        )
        val controller = controllerFor(host, scope = backgroundScope)

        controller.start(
            selection,
            WatchConfig(
                autoReserveEnabled = true,
                stopOnMatch = false,
                minIntervalMs = 2_000L,
                maxIntervalMs = 2_000L,
            ),
        )
        advanceTimeBy(9_000L)
        runCurrent()

        assertTrue("여러 사이클을 돌아야 한다", host.requeryCount >= 2)
        assertEquals("예약 시도는 한 번뿐이어야 한다", 1, host.selectTargets.size)

        controller.stop()
    }

    // ------------------------------------------------------------ 잔여석없음 (§19-2)

    @Test
    fun `잔여석없음 화면이 뜨면 목록으로 되돌리고 감시를 이어간다`() = runTest {
        val host = FakePageHost(
            json = json("AVAILABLE"),
            reserveOutcome = ReserveOutcome.SoldOut("잔여석없음 / url=confirmReservationInfo.do"),
        )
        val notifier = RecordingNotifier()
        val logger = WatchLogger()
        val controller = controllerFor(host, notifier, logger, scope = backgroundScope)

        controller.start(
            selection,
            WatchConfig(
                autoReserveEnabled = true,
                // 좌석을 찾으면 멈추는 설정이라도, 잔여석없음은 "발견"이 아니므로 멈추지 않는다.
                stopOnMatch = true,
                minIntervalMs = 2_000L,
                maxIntervalMs = 2_000L,
            ),
        )
        runCurrent()

        assertEquals("되돌리기는 한 번만", 1, host.dismissCount)
        assertTrue("감시가 이어져야 한다", controller.status.value.state.isRunning)
        assertEquals(ReserveResult.SOLD_OUT, controller.status.value.reserve!!.result)
        // 클릭 실패가 아니라 남이 먼저 잡은 것이다. 오류 상태로 만들지 않는다.
        assertEquals(null, controller.status.value.error)
        assertTrue(logger.entries.value.any { it.code == LogCode.RESERVE_SOLD_OUT })
        assertTrue(logger.entries.value.any { it.code == LogCode.RESERVE_DISMISSED })

        controller.stop()
    }

    @Test
    fun `잔여석없음이 반복되면 정해진 횟수까지만 다시 누른다`() = runTest {
        val host = FakePageHost(
            json = json("AVAILABLE"),
            reserveOutcome = ReserveOutcome.SoldOut("잔여석없음"),
        )
        val controller = controllerFor(host, scope = backgroundScope)

        controller.start(
            selection,
            WatchConfig(
                autoReserveEnabled = true,
                stopOnMatch = false,
                minIntervalMs = 2_000L,
                maxIntervalMs = 2_000L,
                maxSoldOutRetries = 2,
            ),
        )
        advanceTimeBy(15_000L)
        runCurrent()

        assertTrue("여러 사이클을 돌아야 한다", host.requeryCount >= 3)
        assertEquals("제한 횟수만큼만 눌러야 한다", 2, host.reserveTargets.size)
        assertEquals("1단계도 그만큼만", 2, host.selectTargets.size)

        controller.stop()
    }

    @Test
    fun `되돌아가지 못하면 감시를 멈춘다`() = runTest {
        val host = FakePageHost(
            json = json("AVAILABLE"),
            reserveOutcome = ReserveOutcome.SoldOut("잔여석없음"),
        ).apply {
            dismissOutcome = PageOutcome.ButtonNotFound("뒤로 갈 이력이 없음")
        }
        val logger = WatchLogger()
        val controller = controllerFor(host, logger = logger, scope = backgroundScope)

        controller.start(selection, WatchConfig(autoReserveEnabled = true))
        runCurrent()

        assertTrue(logger.entries.value.any { it.code == LogCode.RESERVE_DISMISS_FAILED })
        // 조회하기 버튼이 없는 화면이라 더 진행해도 헛돈다. 안내하고 멈춘다.
        assertEquals(WatchState.ERROR, controller.status.value.state)
    }

    @Test
    fun `자동 예약이 꺼져 있으면 알림만 보낸다`() = runTest {
        val host = FakePageHost(json("AVAILABLE"))
        val notifier = RecordingNotifier()
        val controller = controllerFor(host, notifier, scope = backgroundScope)

        controller.start(selection, WatchConfig(autoReserveEnabled = false))
        runCurrent()

        assertEquals(WatchState.MATCHED, controller.status.value.state)
        assertEquals(1, notifier.sent.size)
        assertTrue(host.reserveTargets.isEmpty())
        assertEquals(null, controller.status.value.reserve)
    }

    // ---------------------------------------------------------- 결제 재촉 알림

    @Test
    fun `예약하기를 누르면 10초마다 결제를 재촉한다`() = runTest {
        val host = FakePageHost(json("AVAILABLE"))
        val notifier = RecordingNotifier()
        val controller = controllerFor(host, notifier, scope = backgroundScope)

        controller.start(selection, WatchConfig(autoReserveEnabled = true))
        runCurrent()
        assertEquals(WatchState.RESERVED, controller.status.value.state)
        assertTrue(controller.status.value.reserveAlerting)

        // 넘어간 직후에는 좌석 발견 알림이 방금 울렸으므로 곧바로 또 울리지 않는다.
        assertTrue(notifier.reminders.isEmpty())

        advanceTimeBy(10_001L)
        runCurrent()
        assertEquals(listOf(1 to 10_000L), notifier.reminders)

        advanceTimeBy(20_000L)
        runCurrent()
        assertEquals(
            listOf(1 to 10_000L, 2 to 20_000L, 3 to 30_000L),
            notifier.reminders,
        )
    }

    @Test
    fun `재촉 알림은 10분이 지나면 스스로 멈춘다`() = runTest {
        val host = FakePageHost(json("AVAILABLE"))
        val notifier = RecordingNotifier()
        val controller = controllerFor(host, notifier, scope = backgroundScope)

        controller.start(selection, WatchConfig(autoReserveEnabled = true))
        runCurrent()

        // 9분 50초짜리가 마지막. 10분에 걸리는 회차는 보내지 않고 멈춘다.
        advanceTimeBy(600_001L)
        runCurrent()
        assertEquals(59, notifier.reminders.size)
        assertEquals(59 to 590_000L, notifier.reminders.last())

        // 멈추면서 붙어 있던 알림도 걷는다. (setOngoing 이라 손으로 못 지운다)
        assertEquals(1, notifier.reminderCancelCount)
        assertFalse(controller.status.value.reserveAlerting)
        // 좌석을 잡았던 사실 자체는 화면에 남는다.
        assertEquals(WatchState.RESERVED, controller.status.value.state)

        advanceTimeBy(600_000L)
        runCurrent()
        assertEquals(59, notifier.reminders.size)
    }

    @Test
    fun `감시 종료를 누르면 재촉 알림이 멈춘다`() = runTest {
        val host = FakePageHost(json("AVAILABLE"))
        val notifier = RecordingNotifier()
        val controller = controllerFor(host, notifier, scope = backgroundScope)

        controller.start(selection, WatchConfig(autoReserveEnabled = true))
        runCurrent()
        advanceTimeBy(10_001L)
        runCurrent()
        assertEquals(1, notifier.reminders.size)

        controller.stop()
        assertEquals(1, notifier.reminderCancelCount)
        assertFalse(controller.status.value.reserveAlerting)

        advanceTimeBy(120_000L)
        runCurrent()
        // 종료한 뒤로는 한 건도 더 오지 않는다.
        assertEquals(1, notifier.reminders.size)
    }

    @Test
    fun `알림 끄기는 재촉만 멈추고 예약 상태는 그대로 둔다`() = runTest {
        val host = FakePageHost(json("AVAILABLE"))
        val notifier = RecordingNotifier()
        val controller = controllerFor(host, notifier, scope = backgroundScope)

        controller.start(selection, WatchConfig(autoReserveEnabled = true))
        runCurrent()

        controller.silenceReserveReminder()
        advanceTimeBy(120_000L)
        runCurrent()

        assertTrue(notifier.reminders.isEmpty())
        assertEquals(1, notifier.reminderCancelCount)
        // 결제는 아직 안 끝났다. 화면은 예약 상태 그대로여야 한다.
        assertEquals(WatchState.RESERVED, controller.status.value.state)
        assertFalse(controller.status.value.reserveAlerting)
    }

    @Test
    fun `앱이 백그라운드로 가도 재촉 알림은 계속된다`() = runTest {
        val host = FakePageHost(json("AVAILABLE"))
        val notifier = RecordingNotifier()
        val controller = controllerFor(host, notifier, scope = backgroundScope)

        controller.start(selection, WatchConfig(autoReserveEnabled = true))
        runCurrent()

        // 화면을 벗어난 순간이야말로 알림이 필요한 때다.
        controller.pause()
        advanceTimeBy(20_001L)
        runCurrent()

        assertEquals(2, notifier.reminders.size)
        assertEquals(WatchState.RESERVED, controller.status.value.state)
    }

    @Test
    fun `알림이 꺼져 있으면 재촉도 하지 않는다`() = runTest {
        val host = FakePageHost(json("AVAILABLE"))
        val notifier = RecordingNotifier()
        val controller = controllerFor(host, notifier, scope = backgroundScope)

        controller.start(
            selection,
            WatchConfig(autoReserveEnabled = true, notificationEnabled = false),
        )
        runCurrent()
        advanceTimeBy(120_000L)
        runCurrent()

        assertTrue(notifier.reminders.isEmpty())
        assertFalse(controller.status.value.reserveAlerting)
    }
}
