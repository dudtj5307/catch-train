package dev.yslee.catchtrain.domain

/** 조건을 만족한 (열차, 좌석등급) 한 건. */
data class SeatMatch(
    val train: Train,
    val seatClass: SeatClass,
) {
    fun describe(): String =
        "${train.trainNumber} ${train.departureTime} ${seatClass.label} ${train.seatStatusOf(seatClass).label}"
}

/**
 * 조건 판정 결과. (DESIGN.md §7)
 *
 * DESIGN 원안의 `matched / train / reason` 을 유지하면서,
 * UI 의 "발견된 좌석 N" 표시를 위해 전체 목록 [matches] 를 함께 제공한다.
 */
data class MatchResult(
    val matched: Boolean,
    val train: Train?,
    val reason: String?,
    val matches: List<SeatMatch> = emptyList(),
) {
    val matchedSeatClass: SeatClass?
        get() = matches.firstOrNull()?.seatClass

    companion object {
        fun noMatch(reason: String) = MatchResult(
            matched = false,
            train = null,
            reason = reason,
        )
    }
}
