package com.example.srtwatcher.domain

import java.time.LocalTime

/**
 * DOM 에서 추출한 한 편성의 열차 정보. (DESIGN.md §7)
 *
 * 이 모델은 WebView / DOM selector 와 완전히 분리되어 있으며,
 * SelectionEngine 과 UI 는 이 모델만 알고 있으면 된다.
 */
data class Train(
    val trainNumber: String,
    val departureStation: String,
    val arrivalStation: String,
    val departureTime: LocalTime,
    val arrivalTime: LocalTime,
    val generalSeat: SeatStatus,
    val firstClassSeat: SeatStatus,
) {
    /** 재조회 후에도 같은 열차를 알아보기 위한 식별자. (사용자 선택을 유지하는 데 쓴다) */
    val key: TrainKey
        get() = TrainKey(trainNumber = trainNumber, departureTime = departureTime)

    fun seatStatusOf(seatClass: SeatClass): SeatStatus = when (seatClass) {
        SeatClass.GENERAL -> generalSeat
        SeatClass.FIRST_CLASS -> firstClassSeat
    }

    /** "SRT 339  수서 → 부산  18:30 → 21:05" */
    fun summary(): String = buildString {
        append(trainNumber)
        append("  ")
        append(departureStation)
        append(" → ")
        append(arrivalStation)
        append("  ")
        append(departureTime.toString())
        append(" → ")
        append(arrivalTime.toString())
    }
}
