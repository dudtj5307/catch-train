package dev.yslee.catchtrain.watcher

/**
 * 감시 루프 동작 파라미터.
 *
 * 페이지 갱신 방식은 설정하지 않는다. 항상 페이지 새로고침(F5)만 사용한다.
 * (DESIGN.md §10, §38-9, dev.yslee.catchtrain.webview.PageOutcome)
 */
data class WatchConfig(
    /** 재조회 간격 하한. 실제 대기 시간은 [minIntervalMs]~[maxIntervalMs] 에서 매번 무작위로 정해진다. */
    val minIntervalMs: Long = ReloadScheduler.DEFAULT_MIN_INTERVAL_MS,
    /** 재조회 간격 상한 */
    val maxIntervalMs: Long = ReloadScheduler.DEFAULT_MAX_INTERVAL_MS,
    val notificationEnabled: Boolean = true,
    /**
     * 조건을 만족하면 그 열차의 좌석 칸을 고르고 [예매] 까지 눌러 준다. (DESIGN.md §19, §38-6)
     *
     * 누르는 것은 **그 두 번뿐**이다. 좌석 선택도 결제도 하지 않는다.
     * 즉시 예약이 가능한 좌석(AVAILABLE)에만 적용되며, 예약대기는 누르지 않는다.
     */
    val autoReserveEnabled: Boolean = true,
    /** 조건 만족 시 감시를 멈춘다. (DESIGN.md §34-4) */
    val stopOnMatch: Boolean = true,
    /** 새로고침한 문서의 로딩 완료(onPageFinished)를 기다리는 최대 시간 */
    val pageTimeoutMs: Long = 20_000L,
    /**
     * 문서 로딩이 끝난 **뒤** SPA 가 열차 목록을 다시 그리기를 기다리는 최대 시간.
     *
     * 갱신이 새로고침으로 바뀌면서 6초에서 늘렸다. (DESIGN.md §38-9)
     * 예전에는 AJAX 응답만 기다리면 됐지만, 이제는 문서를 받은 뒤 React 번들이 돌고
     * 조회 API 를 친 다음에야 `li.tckList` 가 생긴다. 짧게 잡으면 좌석이 있는데도
     * 매번 `NO_TRAIN` 으로 읽고, 그 오판이 [maxUnknownPages] 를 소진시킨다.
     *
     * 여기서 기다리는 동안 **요청은 나가지 않는다.** DOM 을 읽기만 한다.
     */
    val researchSettleMs: Long = 12_000L,
    /** 페이지 정착 직후 렌더링 안정화를 위한 짧은 대기 */
    val settleDelayMs: Long = 300L,
    /**
     * 화면이 아직 [dev.yslee.catchtrain.parser.PageStatus.UNKNOWN_PAGE] 일 때
     * **제자리에서 다시 읽어 보는 간격.** (DESIGN.md §39)
     *
     * 읽기만 하므로 **요청이 나가지 않는다.** 그래서 [minIntervalMs] 와 무관하게
     * 짧아도 되고, 대원칙 2 의 차단 위험과도 무관하다.
     */
    val pageWaitPollMs: Long = 500L,
    /**
     * **접속 대기열에 걸린 화면을 기다려 주는 최대 시간.** (DESIGN.md §39)
     *
     * 코레일은 NetFunnel 대기열이 물려 있어서, 새로고침한 뒤 목록이 아니라 대기
     * 화면이 몇 분씩 떠 있을 수 있다. 그 화면은 `UNKNOWN_PAGE` 로 읽힌다.
     *
     * 예전에는 [researchSettleMs] 12초가 지나면 그대로 판정하고 다음 사이클로
     * 넘어갔는데, 다음 사이클의 첫 동작이 새로고침이다. **대기 중에 새로고침하면
     * 대기 순번이 날아가서 대기가 영영 끝나지 않는다.** 기본 간격이 0.1~0.3초라
     * 사실상 대기 화면을 계속 두들기는 꼴이었다.
     *
     * 그래서 목록이 나타날 때까지 **다음 사이클로 넘어가지 않고** 제자리에서 기다린다.
     * 기다리는 동안 하는 일은 DOM 읽기뿐이라 요청이 나가지 않는다.
     *
     * 이 값을 다 쓰면 그때는 대기가 아니라 다른 문제로 보고 [maxUnknownPages] 를
     * 한 번 소진한다.
     */
    val pageWaitMs: Long = 3 * 60_000L,
    /**
     * **이번 감시에서 목록을 아직 한 번도 못 본** 상태에서의 예산. (DESIGN.md §39)
     *
     * [pageWaitMs] 와 나누는 이유는 `UNKNOWN_PAGE` 의 원인이 둘이기 때문이다.
     * 목록을 본 적이 있으면 지금의 `UNKNOWN` 은 **일시적인 전이**(대기열·렌더링 중)일
     * 가능성이 높으니 길게 기다릴 값어치가 있다. 반대로 한 번도 못 봤다면 화면
     * 자체가 엉뚱한 곳(메인 화면 등)일 가능성이 높고, 그때 3분을 붙들면 사용자
     * 눈에는 앱이 멈춘 것으로 보인다. **짧게 끊고 안내를 띄우는 편이 낫다.**
     */
    val pageWaitFirstMs: Long = 10_000L,
    /** 연속 오류 허용 횟수. 초과하면 감시를 중지한다. */
    val maxConsecutiveErrors: Int = 3,
    /**
     * 감시 가능한 페이지가 아닐 때 허용할 연속 횟수.
     *
     * 재시도 한 번이 곧 조회 요청 한 번이다. 차단 위험을 줄이기 위해 넉넉하게 두지 않는다.
     * (조회 버튼을 눌렀는데 결과가 안 읽히는 상황은 대개 재시도로 나아지지 않는다)
     *
     * **세는 단위는 사이클이지 판독이 아니다.** [pageWaitMs] 동안 몇 번을 다시
     * 읽든 그것은 요청이 아니므로 여기에 하나도 반영되지 않는다. 한 번 늘어나려면
     * 새로고침이 실제로 한 번 더 나가야 한다. (DESIGN.md §39)
     */
    val maxUnknownPages: Int = 2,
    /** 예매 클릭 후 예약 화면으로 넘어가기를 기다리는 최대 시간 */
    val reserveTimeoutMs: Long = 20_000L,
    /** 예매 클릭 후 화면 전환 없이 DOM 변경을 기다리는 최대 시간 */
    val reserveSettleMs: Long = 6_000L,
    /**
     * **예매 2단계 전용** — [예매] 를 눌러 화면 전환이 시작된 뒤 기다리는 최대 시간.
     * (DESIGN.md §39)
     *
     * [reserveTimeoutMs] 와 따로 두는 이유는 대기열이 **여기에 가장 잘 붙기**
     * 때문이다. 조회는 사람이 많아도 통과되지만 예매는 그 순간 트래픽이 몰린다.
     * 전환이 시작되었다는 것은 **요청이 이미 나갔다**는 뜻이라, 여기서 포기하면
     * 몇 시간 기다린 좌석을 대기창 앞에서 그냥 버리는 셈이다. 넉넉히 기다린다.
     *
     * 1단계(좌석 고르기)와 되돌리기에는 쓰지 않는다. 그 둘은 화면 안에서 끝나는
     * 동작이라 오래 기다려 봐야 얻는 것이 없다.
     */
    val confirmTimeoutMs: Long = 90_000L,
    /**
     * **예매 2단계 전용** — 눌렀는데 화면 전환조차 시작되지 않았을 때의 대기 시간.
     * (DESIGN.md §39)
     *
     * 전환이 없다는 것은 헛방이었을 가능성이 높지만, 대기 안내가 화면 위에 겹쳐
     * 뜨는 형태라면 전환 없이 DOM 만 바뀐다. 그래서 [reserveSettleMs] 6초보다는
     * 길게, [confirmTimeoutMs] 보다는 훨씬 짧게 잡는다.
     */
    val confirmSettleMs: Long = 20_000L,
    /**
     * 같은 칸에서 "잔여석없음"을 만났을 때 다시 눌러 볼 최대 횟수. (DESIGN.md §19-2)
     *
     * 눌렀는데 남이 먼저 잡은 경우다. 취소표를 노릴 때는 흔한 일이라
     * 한 번 겪었다고 그 칸을 영영 포기하지는 않는다. 다만 표에는 열려 보이는데
     * 실제로는 계속 실패하는 상태(잔여석 표시 지연 등)일 수 있으므로,
     * 이 횟수를 넘기면 그 칸은 더 누르지 않고 알림만 남긴다.
     *
     * 재시도는 **다음 사이클에** 다시 좌석이 열려 보일 때만 일어난다.
     * 실패한 자리에서 곧바로 다시 누르지 않는다. (요청 폭주 = 차단 위험)
     */
    val maxSoldOutRetries: Int = 3,
    /**
     * 좌석을 잡은 뒤, 사용자가 알아챌 때까지 다시 알리는 간격.
     * (DESIGN.md §19-3)
     *
     * 좌석 발견 알림은 한 번뿐이라 놓치기 쉽다. 결제에는 제한 시간이 있어서
     * 못 보고 지나가면 잡은 좌석을 그대로 잃는다. 그래서 사용자가 [알림 끄기] 나
     * [감시 종료] 를 누를 때까지 이 간격으로 소리와 진동을 되풀이한다.
     */
    val reserveReminderIntervalMs: Long = 10_000L,
    /**
     * 결제 재촉 알림을 이어 가는 최대 시간. (DESIGN.md §19-3)
     *
     * 코레일은 [예매] 로 잡아 둔 좌석을 이 시간 안에 결제하지 않으면 도로 푼다.
     * 그 뒤의 재촉은 이미 없는 좌석을 두고 재촉하는 셈이라, 사용자가 끄지 않아도
     * 여기서 스스로 멈춘다.
     *
     * [reserveReminderIntervalMs] 의 배수가 이 값에 닿는 순간 멈추므로,
     * 마지막 재촉은 한 주기 앞(9분 50초)이 마지막이다.
     */
    val reserveReminderMaxDurationMs: Long = 10 * 60_000L,
)
