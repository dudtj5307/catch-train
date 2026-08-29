package com.example.srtwatcher.storage

import com.example.srtwatcher.watcher.ReloadScheduler
import com.example.srtwatcher.watcher.WatchConfig

/**
 * 저장되는 앱 설정 전체. (DESIGN.md §23)
 *
 * 감시 **대상**은 여기에 없다. 어떤 열차의 어느 좌석을 볼지는 사용자가 매번
 * 조회 결과 화면에서 직접 고르는 것이므로([com.example.srtwatcher.domain.WatchSelection])
 * 저장하지 않는다. 지난번에 고른 열차를 되살려도 오늘 조회 결과에는 없다.
 *
 * 페이지 갱신 방식도 설정 항목이 아니다. 항상 "조회하기" 버튼 클릭만 사용한다. (§10)
 */
data class AppSettings(
    /** 재조회 간격 범위(하한). 실제 간격은 이 범위에서 매 사이클 무작위로 정해진다. */
    val minIntervalMs: Long = ReloadScheduler.DEFAULT_MIN_INTERVAL_MS,
    /** 재조회 간격 범위(상한) */
    val maxIntervalMs: Long = ReloadScheduler.DEFAULT_MAX_INTERVAL_MS,
    val notificationEnabled: Boolean = true,
    /** 선택한 좌석이 열리면 [예약하기] 버튼까지 눌러 준다. 결제는 하지 않는다. */
    val autoReserveEnabled: Boolean = true,
    val stopOnMatch: Boolean = true,
) {
    /** UI 표시용 "2.0~5.0초" */
    val intervalLabel: String
        get() = ReloadScheduler.formatRange(minIntervalMs, maxIntervalMs)

    fun toWatchConfig(): WatchConfig {
        val (min, max) = ReloadScheduler.clampRange(minIntervalMs, maxIntervalMs)
        return WatchConfig(
            minIntervalMs = min,
            maxIntervalMs = max,
            notificationEnabled = notificationEnabled,
            autoReserveEnabled = autoReserveEnabled,
            stopOnMatch = stopOnMatch,
        )
    }
}
