package com.example.srtwatcher.watcher

import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * 재조회 주기를 Android(Native) 쪽에서 관리한다. (DESIGN.md §10, §11, §34-3)
 *
 * JavaScript 의 setInterval 을 쓰지 않고 코루틴으로 대기하므로,
 * 감시를 중지하면 예약된 재조회도 즉시 취소된다(코루틴 취소).
 *
 * 간격은 고정값이 아니라 [최소, 최대] 범위에서 사이클마다 무작위로 뽑는다.
 * 일정한 주기로 요청이 반복되면 자동화로 판단되기 쉬우므로,
 * 밀리초 단위까지 흩어진 값을 사용한다.
 */
class ReloadScheduler(
    private val tickMs: Long = TICK_MS,
    private val random: Random = Random.Default,
) {

    /**
     * [minMs]~[maxMs] 사이(양끝 포함)에서 이번 사이클의 대기 시간을 뽑는다.
     * 값이 뒤집혀 들어와도 안전하도록 정렬 후 clamp 한다.
     */
    fun nextInterval(minMs: Long, maxMs: Long): Long {
        val lo = clamp(minOf(minMs, maxMs))
        val hi = clamp(maxOf(minMs, maxMs))
        if (hi <= lo) return lo
        return lo + random.nextLong(hi - lo + 1)
    }

    /**
     * [intervalMs] 만큼 기다린다. 기다리는 동안 남은 시간을 [onRemaining] 으로 알려
     * UI 가 "다음 확인 약 N초 후" 를 표시할 수 있게 한다.
     */
    suspend fun waitForNext(intervalMs: Long, onRemaining: (Long) -> Unit = {}) {
        var remaining = clamp(intervalMs)
        onRemaining(remaining)
        while (remaining > 0) {
            val step = if (remaining < tickMs) remaining else tickMs
            delay(step)
            remaining -= step
            onRemaining(if (remaining < 0) 0 else remaining)
        }
    }

    companion object {
        /**
         * 조정 가능한 간격의 하한. 0 이면 대기 없이 곧바로 다음 조회를 한다.
         * (실제로는 페이지 갱신을 기다리는 시간이 있어 요청이 무한정 몰리지는 않는다.)
         */
        const val MIN_INTERVAL_MS = 0L

        /** 조정 가능한 간격의 상한. 슬라이더와 직접 입력 모두 이 값까지만 받는다. (§11) */
        const val MAX_INTERVAL_MS = 3_000L
        /**
         * 처음 켰을 때의 간격. 설정 화면의 첫 번째 프리셋(0.1~0.3초)과 같은 값이다.
         * 취소표는 나오자마자 사라지므로 기본값을 짧게 잡는다.
         * 짧은 간격을 오래 유지하면 차단 위험이 커진다는 안내는 설정 화면에 있다.
         */
        const val DEFAULT_MIN_INTERVAL_MS = 100L
        const val DEFAULT_MAX_INTERVAL_MS = 300L

        /** 슬라이더와 직접 입력이 스냅되는 단위(0.1초). */
        const val STEP_MS = 100L
        private const val TICK_MS = 250L

        fun clamp(intervalMs: Long): Long = intervalMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)

        /** 최소값이 최대값보다 큰 상태를 허용하지 않는다. */
        fun clampRange(minMs: Long, maxMs: Long): Pair<Long, Long> {
            val lo = clamp(minOf(minMs, maxMs))
            val hi = clamp(maxOf(minMs, maxMs))
            return lo to hi
        }

        /** "2.0~5.0초" 형태의 표시용 문자열 */
        fun formatRange(minMs: Long, maxMs: Long): String {
            val (lo, hi) = clampRange(minMs, maxMs)
            return if (lo == hi) {
                "${"%.1f".format(lo / 1000.0)}초"
            } else {
                "${"%.1f".format(lo / 1000.0)}~${"%.1f".format(hi / 1000.0)}초"
            }
        }
    }
}
