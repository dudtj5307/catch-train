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

    /** 페이지를 새로고침(F5)했다. detail 에 새로고침 직전의 목록 상태가 남는다. (§38-9) */
    RESEARCH_TRIGGERED,

    /** 갱신이 목표 화면에 닿지 못했다. */
    RESEARCH_FAILED,

    /** WebView 가 화면에 없어 갱신하지 않았다. 요청은 나가지 않았다. */
    RESEARCH_SKIPPED_HIDDEN,

    /** 일부러 갱신하지 않고 이번 차례를 건너뛰었다. (팝업 창이 열려 있음) */
    RESEARCH_DEFERRED,

    /** 새로고침 뒤 목록이 다시 그려졌다. */
    PAGE_UPDATED,

    /**
     * WebView 안에서 난 JS 오류/경고. (DESIGN.md §38-10)
     *
     * 감시와 직접 상관없는 로그지만 여기 있어야 한다. 코레일 화면은 React SPA 라
     * 역/지역 선택 창 같은 것이 **페이지 안에서** 그려진다. 그게 안 뜰 때 앱이 할 수 있는
     * 일은 없고, 원인은 이 로그에만 남는다. `chrome://inspect` 없이 보라고 흘려 둔 것이다.
     */
    PAGE_CONSOLE,

    /**
     * 역 선택 창 진단 결과. 사람이 [진단] 을 눌렀을 때만 남는다. (DESIGN.md §38-10)
     *
     * [PAGE_CONSOLE] 과 짝이다. 콘솔은 "오류가 났는가" 만 알려주는데, 이 창이 안 뜨는
     * 경우는 오류 없이 조용히 아무 일도 일어나지 않는 쪽이 더 흔하다.
     */
    STATION_PROBE,

    /**
     * 문서를 받은 뒤 `100vh` 를 확인한 결과. (DESIGN.md §38-10)
     *
     * 코레일의 역/날짜/인원 선택 창은 `height:100vh` 하나로 화면을 채운다.
     * WebView 에서 그 값이 0 이 되면 창은 열려도 보이지 않는다. 보정은 조용히 걸리므로
     * "보정이 걸렸는가 / 애초에 멀쩡했는가" 를 가르는 줄이 여기 남는다.
     */
    VIEWPORT_FIX,

    /** 차단 안내 페이지를 감지해 감시를 멈췄다. */
    BLOCKED_DETECTED,

    DOM_PARSE_START,
    DOM_PARSE_ERROR,
    DOM_WARNING,

    /**
     * 화면이 아직 열차 목록이 아니라 **제자리에서 기다리기 시작했다.** (DESIGN.md §39)
     *
     * 접속 대기열에 걸렸거나 SPA 가 아직 목록을 안 그린 경우다. 이 줄이 보이면
     * 그 사이 **새로고침은 나가지 않았다** — 대기 중에 새로고침하면 대기 순번이 날아간다.
     */
    PAGE_WAIT_START,

    /** 기다리는 중. detail 에 경과 시간과 몇 번째 판독인지 남는다. (DESIGN.md §39) */
    PAGE_WAIT_TICK,

    /** 기다린 끝에 화면이 확정됐다. detail 에 무엇으로 확정됐는지와 걸린 시간이 남는다. */
    PAGE_WAIT_DONE,

    /**
     * 예산을 다 쓸 때까지 화면이 확정되지 않았다. (DESIGN.md §39)
     * 여기서 비로소 `UNKNOWN_PAGE` 로 판정하고 연속 횟수를 하나 센다.
     */
    PAGE_WAIT_TIMEOUT,
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

    /**
     * **메인 화면**에서 로그인 여부를 확인한 결과. (DESIGN.md §27-2)
     *
     * [LOGIN_STATE] 와 목적이 다르다. 그쪽은 사람이 [감시 시작] 을 눌렀을 때 한 번이고,
     * 이쪽은 메인 문서를 받을 때마다 자동으로 돈다. 비로그인이 **확실할 때만** 로그인
     * 화면으로 보내는데, 사용자가 아무것도 누르지 않았는데 화면이 바뀌는 자리라
     * 왜 보냈는지(또는 왜 안 보냈는지)가 남아야 한다.
     */
    LOGIN_REDIRECT,
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
