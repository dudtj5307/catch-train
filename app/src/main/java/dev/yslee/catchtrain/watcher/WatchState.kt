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
     * 조건에 맞는 좌석을 찾아 [예약하기] 까지 눌렀다.
     *
     * 이 시점에서 SRT 페이지는 좌석 선택/결제 화면으로 넘어가 있고, 감시는 멈춘다.
     * 결제는 사용자가 직접 진행한다. (이 앱은 결제 단계를 건드리지 않는다)
     */
    RESERVED,

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
        get() = isRunning || this == MATCHED || this == RESERVED

    val label: String
        get() = when (this) {
            IDLE -> "대기"
            LOADING -> "페이지 로딩 중"
            ANALYZING -> "페이지 분석 중"
            WAITING -> "다음 확인 대기"
            MATCHED -> "좌석 발견"
            RESERVED -> "예약하기 누름"
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
     * "조회하기" 버튼을 찾지 못했다.
     * 이 앱은 reload 로 갱신하지 않으므로, 버튼이 없으면 감시를 진행할 수 없다.
     */
    RESEARCH_BUTTON_NOT_FOUND,

    /**
     * 버튼은 찾았지만 화면에서 직접 누를 수 없었다.
     * 조회 요청이 나가지 않은 상태라 차단 위험은 없다.
     */
    RESEARCH_BUTTON_NOT_TAPPABLE,

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
            RESEARCH_BUTTON_NOT_FOUND -> "조회 버튼을 찾지 못함"
            RESEARCH_BUTTON_NOT_TAPPABLE -> "조회 버튼을 누를 수 없음"
            BLOCKED -> "접속 차단"
        }

    val guide: String
        get() = when (this) {
            NETWORK_ERROR -> "네트워크 상태를 확인한 뒤 다시 시도하세요."
            PAGE_LOAD_ERROR -> "SRT 페이지를 불러오지 못했습니다."
            DOM_PARSE_ERROR -> "페이지 구조를 읽지 못했습니다. 조회 결과 화면인지 확인하세요."
            LOGIN_REQUIRED -> "WebView 에서 직접 로그인한 뒤 다시 시도하세요."
            SESSION_EXPIRED -> "세션이 만료되었습니다. 다시 로그인하세요."
            UNKNOWN_PAGE -> "열차 조회 결과 화면에서 감시를 시작하세요."
            RESEARCH_BUTTON_NOT_FOUND ->
                "화면에 [조회하기] 버튼이 보이는 조회 결과 페이지에서 감시를 시작하세요. " +
                    "차단 안내나 오류 화면이 떠 있는지 먼저 확인하세요."
            RESEARCH_BUTTON_NOT_TAPPABLE ->
                "[조회하기] 버튼이 화면에 보이도록 WebView 를 넓히거나 스크롤한 뒤 다시 시도하세요. " +
                    "이 앱은 버튼을 URL 로 호출하지 않고 화면에서 직접 누르기 때문에, " +
                    "버튼이 가려져 있으면 누를 수 없습니다."
            BLOCKED ->
                "접속이 차단된 것으로 보입니다. 감시를 멈췄습니다. " +
                    "한동안 기다린 뒤 재조회 간격을 최대한 늘려서 다시 시작하세요."
        }
}

/**
 * 자동 [예약하기] 클릭의 결과.
 *
 * 성공([CLICKED])이란 "예약하기 버튼을 눌렀고 페이지가 반응했다"는 뜻일 뿐,
 * 예약이 확정되었다는 뜻이 아니다. 좌석 선택/결제는 사용자가 직접 진행한다.
 */
enum class ReserveResult {
    /** 버튼을 눌렀고 화면이 넘어갔다. */
    CLICKED,

    /** 눌렀지만 화면이 바뀌지 않았다. 좌석이 방금 나갔을 수 있다. */
    NO_CHANGE,

    /**
     * 눌렀지만 "잔여석없음" 안내가 떴다. 그사이 다른 사람이 먼저 잡은 것이다.
     * 실패지만 오류는 아니다. 목록 화면으로 되돌린 뒤 감시를 계속한다. (§19-2)
     */
    SOLD_OUT,

    /** 조건에 맞는 행을 화면에서 다시 찾지 못했다. (표가 바뀌었을 수 있다) */
    ROW_NOT_FOUND,

    /** 그 행에 [예약하기] 버튼이 없었다. */
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
            CLICKED -> "예약하기 누름"
            NO_CHANGE -> "눌렀지만 화면이 바뀌지 않음"
            SOLD_OUT -> "잔여석 없음 (다른 사람이 먼저 예약)"
            ROW_NOT_FOUND -> "해당 열차 행을 찾지 못함"
            BUTTON_NOT_FOUND -> "예약하기 버튼 없음"
            NOT_TAPPABLE -> "예약하기 버튼을 누를 수 없음"
            FAILED -> "페이지 오류"
            SKIPPED -> "시도하지 않음"
        }
}

/** 어떤 좌석에 대해 [예약하기] 를 눌렀고 어떻게 되었는지. */
data class ReserveAttempt(
    val match: SeatMatch,
    val result: ReserveResult,
    val detail: String = "",
)

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
    /** 이번 감시에서 마지막으로 시도한 자동 [예약하기] 결과. 시도한 적이 없으면 null. */
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
