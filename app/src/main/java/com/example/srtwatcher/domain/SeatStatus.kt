package com.example.srtwatcher.domain

/** 좌석 상태. (DESIGN.md §7) */
enum class SeatStatus {
    /** 예약 가능 (예약하기 / 좌석선택 버튼 노출) */
    AVAILABLE,

    /** 예약대기 또는 입석 등 즉시 예약이 아닌 상태 */
    WAITING,

    /** 매진 */
    SOLD_OUT,

    /** 해당 칸이 없거나 상태를 판별할 수 없음 */
    UNKNOWN,
    ;

    val label: String
        get() = when (this) {
            AVAILABLE -> "예약가능"
            WAITING -> "예약대기"
            SOLD_OUT -> "매진"
            UNKNOWN -> "-"
        }
}
