package com.example.srtwatcher.domain

/**
 * 중복 알림 방지 키. (DESIGN.md §20)
 *
 * 같은 열차 + 같은 좌석등급 조합은 한 번만 알린다.
 * 좌석이 매진으로 바뀌면 키를 제거하여, 다시 예약가능이 되면 새 알림을 보낸다.
 *
 * 날짜는 키에 넣지 않는다. 조회 결과 페이지 자체가 특정 날짜의 결과이고,
 * 사용자가 사이트에서 날짜를 바꿔 다시 조회하면 선택 자체가 초기화되기 때문이다.
 */
data class MatchKey(
    val trainKey: TrainKey,
    val seatClass: SeatClass,
)

fun SeatMatch.toKey(): MatchKey = MatchKey(trainKey = train.key, seatClass = seatClass)
