package dev.yslee.catchtrain.watcher

/**
 * 감시 루프 동작 파라미터.
 *
 * 페이지 갱신 방식은 설정하지 않는다. 항상 "조회하기" 버튼 클릭만 사용한다.
 * (DESIGN.md §10, dev.yslee.catchtrain.webview.PageOutcome)
 */
data class WatchConfig(
    /** 재조회 간격 하한. 실제 대기 시간은 [minIntervalMs]~[maxIntervalMs] 에서 매번 무작위로 정해진다. */
    val minIntervalMs: Long = ReloadScheduler.DEFAULT_MIN_INTERVAL_MS,
    /** 재조회 간격 상한 */
    val maxIntervalMs: Long = ReloadScheduler.DEFAULT_MAX_INTERVAL_MS,
    val notificationEnabled: Boolean = true,
    /**
     * 조건을 만족하면 그 열차의 [예약하기] 버튼까지 눌러 준다. (DESIGN.md §19)
     *
     * 누르는 것은 **예약하기 버튼 하나뿐**이다. 좌석 선택도 결제도 하지 않는다.
     * 즉시 예약이 가능한 좌석(AVAILABLE)에만 적용되며, 예약대기는 누르지 않는다.
     */
    val autoReserveEnabled: Boolean = true,
    /** 조건 만족 시 감시를 멈춘다. (DESIGN.md §34-4) */
    val stopOnMatch: Boolean = true,
    /** 화면 전환(form submit)이 시작된 경우 로딩 완료를 기다리는 최대 시간 */
    val pageTimeoutMs: Long = 20_000L,
    /** 화면 전환 없이(AJAX) 결과 표가 갱신되기를 기다리는 최대 시간 */
    val researchSettleMs: Long = 6_000L,
    /** 페이지 정착 직후 렌더링 안정화를 위한 짧은 대기 */
    val settleDelayMs: Long = 300L,
    /** 연속 오류 허용 횟수. 초과하면 감시를 중지한다. */
    val maxConsecutiveErrors: Int = 3,
    /**
     * 감시 가능한 페이지가 아닐 때 허용할 연속 횟수.
     *
     * 재시도 한 번이 곧 조회 요청 한 번이다. 차단 위험을 줄이기 위해 넉넉하게 두지 않는다.
     * (조회 버튼을 눌렀는데 결과가 안 읽히는 상황은 대개 재시도로 나아지지 않는다)
     */
    val maxUnknownPages: Int = 2,
    /** [예약하기] 클릭 후 예약 화면으로 넘어가기를 기다리는 최대 시간 */
    val reserveTimeoutMs: Long = 20_000L,
    /** [예약하기] 클릭 후 화면 전환 없이 DOM 변경을 기다리는 최대 시간 */
    val reserveSettleMs: Long = 6_000L,
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
     * [예약하기] 를 눌러 결제 화면까지 간 뒤, 사용자가 알아챌 때까지 다시 알리는 간격.
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
     * SRT 는 [예약하기] 로 잡아 둔 좌석을 이 시간 안에 결제하지 않으면 도로 푼다.
     * 그 뒤의 재촉은 이미 없는 좌석을 두고 재촉하는 셈이라, 사용자가 끄지 않아도
     * 여기서 스스로 멈춘다.
     *
     * [reserveReminderIntervalMs] 의 배수가 이 값에 닿는 순간 멈추므로,
     * 마지막 재촉은 한 주기 앞(9분 50초)이 마지막이다.
     */
    val reserveReminderMaxDurationMs: Long = 10 * 60_000L,
)
