package dev.yslee.catchtrain.watcher

import dev.yslee.catchtrain.domain.SeatMatch
import dev.yslee.catchtrain.domain.Train

/**
 * 감시 상태. (DESIGN.md §8)
 *
 * [PAUSED] 는 원안 목록에는 없지만, §24 의 lifecycle pause/resume 을
 * 사용자가 구분해서 볼 수 있도록 추가했다.
 */
enum class WatchState {
    IDLE,
    LOADING,
    ANALYZING,
    WAITING,
    MATCHED,

    /**
     * 조건에 맞는 좌석을 찾아 **예매 2단계까지** 눌렀다. (DESIGN.md §38-6)
     *
     * 이 시점에서 페이지는 좌석 선택/결제 화면으로 넘어가 있고, 감시는 멈춘다.
     * 결제는 사용자가 직접 진행한다. (이 앱은 결제 단계를 건드리지 않는다)
     */
    RESERVED,

    /**
     * **1단계까지만 눌렀다.** 좌석 칸은 골라 뒀고 [예매] 는 사람이 눌러야 한다. (§38-6-1)
     *
     * 2단계 버튼이 허용목록에 없거나(`예약대기신청` / `입석+좌석 예매`), 누르기 전
     * 확인이 어긋난 경우다. 실패가 아니라 **일부러 멈춘 것**이다. (대원칙 3)
     *
     * 감시는 여기서 끝난다. 재조회를 한 번 더 하면 골라 둔 선택이 지워지기 때문이다.
     * 대신 결제 재촉 알림을 울려 사용자가 화면을 보게 만든다.
     */
    SEAT_SELECTED,

    ERROR,
    PAUSED,
    STOPPED,
    ;

    val isRunning: Boolean
        get() = this == LOADING || this == ANALYZING || this == WAITING

    /**
     * 화면이 꺼지면 안 되는 상태인지. (MainActivity 가 FLAG_KEEP_SCREEN_ON 을 켜고 끈다)
     *
     * 감시 중에는 화면이 꺼지면 감시가 멈추기 때문이고,
     * 발견/예약 직후에는 사용자가 곧바로 이어서 처리해야 하기 때문이다.
     */
    val keepsScreenOn: Boolean
        get() = isRunning || this == MATCHED || this == RESERVED || this == SEAT_SELECTED

    val label: String
        get() = when (this) {
            IDLE -> "대기"
            LOADING -> "페이지 로딩 중"
            ANALYZING -> "페이지 분석 중"
            WAITING -> "다음 확인 대기"
            MATCHED -> "좌석 발견"
            RESERVED -> "예매 누름"
            SEAT_SELECTED -> "좌석 골라 둠"
            ERROR -> "오류"
            PAUSED -> "일시정지"
            STOPPED -> "중지됨"
        }

    /** UI 표시용 신호등. (§8) */
    val indicator: String
        get() = when (this) {
            LOADING, ANALYZING, WAITING -> "🟢"
            MATCHED -> "🎯"
            RESERVED -> "🎫"
            SEAT_SELECTED -> "🎟"
            ERROR -> "🔴"
            PAUSED -> "🟡"
            IDLE, STOPPED -> "⚪"
        }
}

/** 에러 구분. (DESIGN.md §27) */
enum class WatchError {
    NETWORK_ERROR,
    PAGE_LOAD_ERROR,
    DOM_PARSE_ERROR,
    LOGIN_REQUIRED,
    SESSION_EXPIRED,
    UNKNOWN_PAGE,

    /**
     * 새로고침했지만 감시할 수 있는 화면에 닿지 못했다. (DESIGN.md §38-9)
     * 차단 안내나 오류 화면이 떠 있는 경우다.
     */
    REFRESH_FAILED,

    /**
     * WebView 가 화면에 없어 새로고침하지 않았다.
     * 조회 요청이 나가지 않은 상태라 차단 위험은 없다.
     */
    REFRESH_NOT_VISIBLE,

    /** 접속이 차단되었다. 재시도하지 않고 즉시 멈춘다. */
    BLOCKED,
    ;

    val title: String
        get() = when (this) {
            NETWORK_ERROR -> "네트워크 오류"
            PAGE_LOAD_ERROR -> "페이지 오류"
            DOM_PARSE_ERROR -> "분석 오류"
            LOGIN_REQUIRED -> "로그인 필요"
            SESSION_EXPIRED -> "세션 만료"
            UNKNOWN_PAGE -> "감시할 수 없는 페이지"
            REFRESH_FAILED -> "새로고침 실패"
            REFRESH_NOT_VISIBLE -> "화면이 보이지 않음"
            BLOCKED -> "접속 차단"
        }

    val guide: String
        get() = when (this) {
            NETWORK_ERROR -> "네트워크 상태를 확인한 뒤 다시 시도하세요."
            PAGE_LOAD_ERROR -> "코레일 페이지를 불러오지 못했습니다."
            DOM_PARSE_ERROR -> "페이지 구조를 읽지 못했습니다. 조회 결과 화면인지 확인하세요."
            LOGIN_REQUIRED -> "WebView 에서 직접 로그인한 뒤 다시 시도하세요."
            SESSION_EXPIRED -> "세션이 만료되었습니다. 다시 로그인하세요."
            UNKNOWN_PAGE -> "열차 조회 결과 화면에서 감시를 시작하세요."
            REFRESH_FAILED ->
                "새로고침했지만 열차 목록 화면으로 돌아오지 못했습니다. " +
                    "차단 안내나 오류 화면이 떠 있는지 먼저 확인하고, " +
                    "조회 결과 화면에서 다시 시작하세요."
            REFRESH_NOT_VISIBLE ->
                "코레일 화면이 보이는 상태에서 감시하세요. " +
                    "이 앱은 사람이 볼 수 없는 화면은 새로고침하지 않습니다."
            BLOCKED ->
                "접속이 차단된 것으로 보입니다. 감시를 멈췄습니다. " +
                    "한동안 기다린 뒤 재조회 간격을 최대한 늘려서 다시 시작하세요."
        }
}

/**
 * 자동 예매가 **어느 단계**에서 끝났는지. (DESIGN.md §38-6)
 *
 * 코레일 예매는 두 번 누른다. 좌석 칸을 골라 `active` 를 붙이고(1단계), 화면 하단에
 * 나타난 예매 바에서 [예매] 를 누른다(2단계). 두 단계는 성질이 다르다 —
 * 1단계는 아직 아무것도 잡지 않은 상태이고, 2단계는 좌석을 잡는 되돌리기 어려운 동작이다.
 *
 * 그래서 실패를 볼 때 **어느 단계에서 멈췄는지**를 함께 봐야 한다.
 * [CONFIRM] 단계의 실패는 "1단계까지는 되어 있다"는 뜻이기도 하다.
 */
enum class ReserveStage {
    /** 좌석 칸 고르기 */
    SELECT,

    /** 하단 바의 [예매] */
    CONFIRM,
    ;

    val label: String
        get() = when (this) {
            SELECT -> "좌석 선택"
            CONFIRM -> "예매"
        }
}

/**
 * 자동 예매 클릭의 결과.
 *
 * 성공([CLICKED])이란 "[예매] 버튼을 눌렀고 페이지가 반응했다"는 뜻일 뿐,
 * 예약이 확정되었다는 뜻이 아니다. 좌석 선택/결제는 사용자가 직접 진행한다.
 *
 * 어느 단계의 결과인지는 [ReserveAttempt.stage] 가 들고 있다.
 */
enum class ReserveResult {
    /** 2단계 버튼을 눌렀고 화면이 반응했다. */
    CLICKED,

    /** 눌렀지만 화면이 바뀌지 않았다. 좌석이 방금 나갔을 수 있다. */
    NO_CHANGE,

    /**
     * 눌렀지만 "잔여석없음" 안내가 떴다. 그사이 다른 사람이 먼저 잡은 것이다.
     * 실패지만 오류는 아니다. 목록 화면으로 되돌린 뒤 감시를 계속한다. (§19-2)
     */
    SOLD_OUT,

    /** 조건에 맞는 편성을 화면에서 다시 찾지 못했다. (목록이 갱신되었을 수 있다) */
    ROW_NOT_FOUND,

    /** 좌석 칸을 특정하지 못했거나, 그사이 매진/예약대기로 바뀌었다. (1단계) */
    CELL_NOT_FOUND,

    /** 1단계를 눌렀지만 그 칸에 선택 표시가 붙지 않았다. */
    SEAT_NOT_SELECTED,

    /**
     * 2단계 버튼이 **누를 수 있는 문구가 아니었다.** (§38-6-1)
     * `예약대기신청` / `입석+좌석 예매` 가 여기다. 사람에게 넘긴다.
     */
    NOT_ALLOWED,

    /** 누르기 전 확인이 어긋났다. (선택 표시 없음 / 하단 바 등급 불일치) */
    MISMATCH,

    /**
     * **안내 창이 가로막았는데 앱이 누를 수 있는 창이 아니었다.** (§38-6-2)
     *
     * 열차에 따라 [예매] 뒤에 안내 창이 하나 끼어들고, 그 [확인] 을 누르기 전에는
     * 화면이 넘어가지 않는다. 아는 안내면 앱이 눌러 주지만 아니면 손대지 않는다 —
     * 차단·예약실패 안내의 [확인] 은 조회 조건을 날린다 (대원칙 5).
     * 좌석은 골라져 있으니 사용자가 그 [확인] 만 눌러 주면 이어진다.
     */
    NOTICE_BLOCKED,

    /** 하단 예매 바나 그 안의 버튼을 찾지 못했다. */
    BUTTON_NOT_FOUND,

    /** 버튼은 찾았지만 화면에서 누를 수 없었다. (가려짐 / 화면 밖) */
    NOT_TAPPABLE,

    /** 페이지 오류 */
    FAILED,

    /** 시도하지 않았다. (설정이 꺼져 있거나, 예약대기 등 즉시 예약이 아닌 좌석) */
    SKIPPED,
    ;

    val succeeded: Boolean
        get() = this == CLICKED

    val label: String
        get() = when (this) {
            CLICKED -> "예매 누름"
            NO_CHANGE -> "눌렀지만 화면이 바뀌지 않음"
            SOLD_OUT -> "잔여석 없음 (다른 사람이 먼저 예약)"
            ROW_NOT_FOUND -> "해당 열차를 목록에서 찾지 못함"
            CELL_NOT_FOUND -> "좌석 칸을 누르지 못함"
            SEAT_NOT_SELECTED -> "좌석이 선택되지 않음"
            NOT_ALLOWED -> "예매 버튼이 아니라 누르지 않음"
            MISMATCH -> "고른 좌석과 화면이 맞지 않아 누르지 않음"
            NOTICE_BLOCKED -> "안내 창에 가로막힘"
            BUTTON_NOT_FOUND -> "예매 버튼 없음"
            NOT_TAPPABLE -> "예매 버튼을 누를 수 없음"
            FAILED -> "페이지 오류"
            SKIPPED -> "시도하지 않음"
        }
}

/**
 * 어떤 좌석에 대해 자동 예매를 어디까지 진행했고 어떻게 되었는지.
 *
 * [stage] 가 [ReserveStage.CONFIRM] 이면 **1단계는 성공한 것**이다.
 * 좌석 칸은 골라져 있으므로, 실패했더라도 사용자가 화면에서 [예매] 만 누르면 된다.
 */
data class ReserveAttempt(
    val match: SeatMatch,
    val result: ReserveResult,
    val stage: ReserveStage = ReserveStage.CONFIRM,
    val detail: String = "",
) {
    /** 좌석 칸까지는 골라져 있는가. (2단계에 닿았다는 것은 1단계가 성공했다는 뜻이다) */
    val seatSelected: Boolean get() = stage == ReserveStage.CONFIRM
}

/**
 * 감시 상태 스냅샷. UI 는 이 값만 보고 화면을 그린다. (§21)
 */
data class WatchStatus(
    val state: WatchState = WatchState.IDLE,
    val lastCheckedAt: Long? = null,
    val nextCheckInMs: Long? = null,
    val cycleCount: Int = 0,
    val trainCount: Int = 0,
    val foundCount: Int = 0,
    val matches: List<SeatMatch> = emptyList(),
    val trains: List<Train> = emptyList(),
    /**
     * 지금 화면에 떠 있는 조회 결과의 출발일 ("2026-08-24"). 모르면 빈 문자열.
     *
     * 구간은 [trains] 의 행에서 읽히지만 날짜는 표에 없어 조회 폼에서 따로 읽는다.
     * 둘 다 화면 표시 전용이다. 감시 판정은 [matches] 만 본다.
     */
    val searchDate: String = "",
    /** 이번 감시에서 마지막으로 시도한 자동 예매 결과. 시도한 적이 없으면 null. */
    val reserve: ReserveAttempt? = null,
    /**
     * 결제 재촉 알림이 울리고 있는지. (DESIGN.md §19-3)
     *
     * [WatchState.RESERVED] 로 넘어가면 켜지고, 사용자가 [알림 끄기] 나 [감시 종료] 를
     * 누르면 꺼진다. UI 는 이 값으로 [알림 끄기] 버튼을 보일지 정한다.
     */
    val reserveAlerting: Boolean = false,
    val error: WatchError? = null,
    val message: String? = null,
)
