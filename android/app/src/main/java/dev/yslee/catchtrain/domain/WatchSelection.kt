package dev.yslee.catchtrain.domain

/**
 * 사용자가 [열차 선택] 목록에서 체크한 좌석 한 칸.
 *
 * 사이트 표의 한 칸(= [예약하기] 버튼 하나)에 그대로 대응한다.
 * 같은 열차라도 특실과 일반실은 서로 다른 칸이므로 따로 고른다.
 */
data class SeatSelection(
    val trainKey: TrainKey,
    val seatClass: SeatClass,
)

/**
 * 감시 대상. (구 `WatchCondition` 을 대체한다)
 *
 * 예전에는 설정 화면에서 구간/날짜/시간 범위/좌석 등급을 입력받아 조건으로 걸렀다.
 * 지금은 **사용자가 코레일 사이트에서 직접 조회한 결과**에서 원하는 칸을 체크하고,
 * 앱은 체크된 칸만 본다. 조회 조건은 사이트가 이미 알고 있으므로 앱이 중복해서
 * 들고 있을 이유가 없고, "이 열차의 이 좌석" 이라고 못 박는 편이 오클릭도 적다.
 */
data class WatchSelection(
    val seats: Set<SeatSelection> = emptySet(),
) {
    val isEmpty: Boolean get() = seats.isEmpty()

    val size: Int get() = seats.size

    /** 선택된 열차 수 (좌석 등급 중복 제거) */
    val trainCount: Int get() = seats.map { it.trainKey }.distinct().size

    fun contains(trainKey: TrainKey, seatClass: SeatClass): Boolean =
        seats.any { it.seatClass == seatClass && it.trainKey.matches(trainKey) }

    fun contains(train: Train, seatClass: SeatClass): Boolean = contains(train.key, seatClass)

    /** 체크를 켜고 끈다. 이미 있으면 지우고, 없으면 넣는다. */
    fun toggle(trainKey: TrainKey, seatClass: SeatClass): WatchSelection {
        val existing = seats.firstOrNull {
            it.seatClass == seatClass && it.trainKey.matches(trainKey)
        }
        return if (existing != null) {
            copy(seats = seats - existing)
        } else {
            copy(seats = seats + SeatSelection(trainKey, seatClass))
        }
    }

    /**
     * 지금 화면에 없는 열차의 선택을 지운다.
     *
     * 사용자가 사이트에서 날짜나 구간을 바꿔 다시 조회하면 이전 선택은 의미가 없다.
     * 그대로 두면 눈에 보이지 않는 조건이 남아 감시가 영영 걸리지 않는다.
     */
    fun retainOnly(trains: List<Train>): WatchSelection {
        if (trains.isEmpty()) return this
        val kept = seats.filter { seat -> trains.any { seat.trainKey.matches(it.key) } }.toSet()
        return if (kept.size == seats.size) this else copy(seats = kept)
    }

    fun validate(): String? = when {
        seats.isEmpty() -> "감시할 열차를 하나 이상 선택하세요."
        else -> null
    }

    companion object {
        fun empty(): WatchSelection = WatchSelection()
    }
}
