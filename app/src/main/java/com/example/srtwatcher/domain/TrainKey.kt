package com.example.srtwatcher.domain

import java.time.LocalTime

/**
 * 화면이 갱신되어도 "같은 열차"를 가리키기 위한 식별자.
 *
 * 사용자가 목록에서 체크한 열차는 재조회를 거쳐도 그대로 유지되어야 한다.
 * 표의 행 위치([com.example.srtwatcher.parser.RowRef.rowIndex])는 갱신마다 바뀔 수
 * 있으므로 위치가 아니라 **내용**으로 식별한다.
 *
 * 기준은 출발 시각이다. 한 구간의 조회 결과에서 출발 시각이 겹치는 편성은 없다.
 * 열차 번호는 파서가 읽어내지 못하는 경우가 있어([trainNumber] 가 빈 문자열)
 * 보조 확인용으로만 쓴다. 한쪽이라도 비어 있으면 시각만으로 같다고 본다.
 */
data class TrainKey(
    val trainNumber: String,
    val departureTime: LocalTime,
) {
    fun matches(other: TrainKey): Boolean {
        if (departureTime != other.departureTime) return false
        if (trainNumber.isBlank() || other.trainNumber.isBlank()) return true
        return trainNumber == other.trainNumber
    }

    /** "18:30 SRT 339" */
    fun label(): String = if (trainNumber.isBlank()) {
        departureTime.toString()
    } else {
        "$departureTime $trainNumber"
    }
}
