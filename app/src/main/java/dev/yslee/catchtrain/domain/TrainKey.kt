package dev.yslee.catchtrain.domain

import java.time.LocalTime

/**
 * 화면이 갱신되어도 "같은 열차"를 가리키기 위한 식별자.
 *
 * 사용자가 목록에서 체크한 열차는 재조회를 거쳐도 그대로 유지되어야 한다.
 * 목록의 행 위치([dev.yslee.catchtrain.parser.RowRef.rowIndex])는 갱신마다 바뀔 수
 * 있으므로 위치가 아니라 **내용**으로 식별한다.
 *
 * **기준은 열차 번호다.** (DESIGN.md §38-4)
 *
 * 예전에는 출발 시각을 주키로 썼다. "한 구간의 조회 결과에서 출발 시각이 겹치는 편성은
 * 없다" 는 전제였고 SRT 에서는 성립했지만, **코레일에서는 실제로 깨진다.**
 * 실측(동탄→김천구미)에서 07:11 에 305 와 381 이, 18:55 에 353 과 397 이 함께 있었다.
 * 시각을 주키로 두면 사용자가 305 를 체크했는데 381 의 좌석이 열렸을 때 발견으로 처리되고,
 * 자동 클릭이 엉뚱한 편성을 잡는다.
 *
 * 코레일은 `span.num` 에 번호가 항상 나오므로 번호를 못 읽을 걱정은 SRT 보다 적다.
 * 그래도 번호를 읽지 못한 경우([trainNumber] 가 빈 문자열)를 위해 시각 대체 경로를 남긴다.
 * 이 경로는 **같은 시각에 편성이 둘 이상이면 구분하지 못한다** — 그래서 대체일 뿐이다.
 */
data class TrainKey(
    val trainNumber: String,
    val departureTime: LocalTime,
) {
    /** 번호를 읽어내지 못해 출발 시각에 기대야 하는 키인가. (모호할 수 있다) */
    val ambiguous: Boolean get() = trainNumber.isBlank()

    fun matches(other: TrainKey): Boolean {
        // 양쪽 다 번호가 있으면 번호만으로 판정한다.
        // 한 조회 결과 안에서 열차 번호는 유일하다.
        if (trainNumber.isNotBlank() && other.trainNumber.isNotBlank()) {
            return trainNumber == other.trainNumber
        }
        // 한쪽이라도 번호가 없으면 시각으로 대체한다. (모호할 수 있는 경로)
        return departureTime == other.departureTime
    }

    /** "07:11 305" */
    fun label(): String = if (trainNumber.isBlank()) {
        departureTime.toString()
    } else {
        "$departureTime $trainNumber"
    }
}
