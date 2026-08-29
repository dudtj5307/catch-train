package dev.yslee.catchtrain.watcher

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** 로그 코드. (DESIGN.md §29) */
enum class LogCode {
    WATCH_START,
    WATCH_STOP,
    WATCH_PAUSED,
    WATCH_RESUMED,
    PAGE_LOAD_START,
    PAGE_LOAD_FINISHED,
    PAGE_SETTLE_TIMEOUT,
    PAGE_LOAD_ERROR,

    /** 조회하기 버튼 위치를 실제로 탭했다. detail 에 좌표와 어떤 버튼이었는지 남는다. */
    RESEARCH_CLICKED,

    /** 조회하기 버튼을 찾지 못했다. */
    RESEARCH_BUTTON_NOT_FOUND,

    /** 버튼은 찾았지만 화면에서 누를 수 없었다. (가려짐 / 화면 밖 / WebView 안 보임) */
    RESEARCH_NOT_TAPPABLE,

    /** 일부러 누르지 않고 이번 차례를 건너뛰었다. (팝업 창이 열려 있음) */
    RESEARCH_DEFERRED,

    /** 화면 전환 없이 결과 표가 갱신되었다. (AJAX) */
    PAGE_UPDATED,

    /** 차단 안내 페이지를 감지해 감시를 멈췄다. */
    BLOCKED_DETECTED,

    DOM_PARSE_START,
    DOM_PARSE_ERROR,
    DOM_WARNING,
    TRAIN_COUNT,
    SELECTION_CHECK,
    MATCH_COUNT,
    MATCH_DETAIL,
    NOTIFICATION_SENT,
    NOTIFICATION_SKIPPED,

    /** 자동 예매를 시도한다. detail 에 어떤 열차/좌석인지 남는다. */
    RESERVE_START,

    /** 1·2단계 버튼 위치를 실제로 탭했다. detail 에 좌표와 어떤 요소였는지 남는다. */
    RESERVE_CLICKED,

    /** **1단계** — 좌석 칸이 골라진 것을 확인했다. (§38-6) */
    RESERVE_SEAT_SELECTED,

    /** **2단계** — [예매] 를 눌러 예약 화면으로 넘어갔다. */
    RESERVE_SUCCEEDED,

    /**
     * **2단계를 사람에게 넘겼다.** 좌석은 골라져 있다. (§38-6-1)
     * 허용목록에 없는 버튼이거나 누르기 전 확인이 어긋난 경우다.
     */
    RESERVE_HANDOVER,

    /** 예매를 진행하지 못했다. (편성/칸을 특정 못 함, 가려짐, 오류 등) */
    RESERVE_FAILED,

    /** 눌렀지만 "잔여석없음" 안내가 떴다. 그사이 다른 사람이 먼저 잡은 경우다. */
    RESERVE_SOLD_OUT,

    /** 예약 실패 안내 화면에서 목록 화면으로 되돌아갔다. */
    RESERVE_DISMISSED,

    /** 목록 화면으로 되돌리지 못했다. (확인 버튼도, 뒤로 갈 이력도 없음) */
    RESERVE_DISMISS_FAILED,

    /** 자동 예약을 하지 않고 넘어갔다. (설정 꺼짐 / 이미 시도함) */
    RESERVE_SKIPPED,

    /** 결제 재촉 알림을 시작했다. detail 에 간격이 남는다. */
    RESERVE_REMINDER_START,

    /** 결제 재촉 알림을 한 번 보냈다. detail 에 몇 번째인지 남는다. */
    RESERVE_REMINDER_SENT,

    /** 결제 재촉 알림을 껐다. detail 에 무엇 때문인지 남는다. */
    RESERVE_REMINDER_STOP,
    NEXT_RELOAD,
    PAGE_STATUS,

    /** 감시 시작 전 로그인 여부를 확인했다. detail 에 판정 근거가 남는다. */
    LOGIN_STATE,
}

data class WatchLogEntry(
    val time: LocalTime,
    val code: LogCode,
    val detail: String? = null,
) {
    fun format(): String {
        val stamp = time.format(TIME_FORMAT)
        return if (detail.isNullOrBlank()) "[$stamp] $code" else "[$stamp] $code=$detail"
    }

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}

/**
 * 개발/디버깅용 로그 버퍼. (DESIGN.md §29)
 * 최근 [capacity] 건만 유지한다.
 */
class WatchLogger(
    private val capacity: Int = 300,
    private val clock: () -> LocalTime = { LocalTime.now() },
) {
    private val _entries = MutableStateFlow<List<WatchLogEntry>>(emptyList())
    val entries: StateFlow<List<WatchLogEntry>> = _entries.asStateFlow()

    fun log(code: LogCode, detail: String? = null) {
        val entry = WatchLogEntry(time = clock(), code = code, detail = detail)
        _entries.update { current ->
            val next = current + entry
            if (next.size > capacity) next.takeLast(capacity) else next
        }
    }

    fun clear() {
        _entries.value = emptyList()
    }

    /** 공유/복사용 텍스트 (최신이 아래) */
    fun dump(): String = _entries.value.joinToString("\n") { it.format() }
}
