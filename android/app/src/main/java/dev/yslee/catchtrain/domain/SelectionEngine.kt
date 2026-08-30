package dev.yslee.catchtrain.domain

/**
 * 선택 판정 엔진. (구 `ConditionEngine` 을 대체한다)
 *
 * WebView / DOM / Android 프레임워크에 대한 의존이 전혀 없는 순수 로직이므로
 * 단위 테스트가 가능하고, 향후 PC Extension 과 개념을 공유할 수 있다. (DESIGN §32)
 *
 * 판정 규칙은 단순하다.
 *   1. 사용자가 체크한 (열차, 좌석등급) 칸만 본다.
 *   2. 그 칸이 **지금 바로 예약 가능**([SeatStatus.AVAILABLE])하면 발견이다.
 *
 * 예약대기([SeatStatus.WAITING])는 발견으로 보지 않는다. 즉시 예약이 아니라
 * 대기 신청이고, 사용자가 고른 것은 그 칸의 [예약하기] 버튼이기 때문이다.
 * 대기 신청은 화면에서 직접 하면 된다.
 *
 * 구간/날짜/시간 판정은 사라졌다. 사용자가 사이트에서 직접 조회한 결과를
 * 그대로 쓰므로 앱이 다시 거를 이유가 없다.
 */
class SelectionEngine {

    fun match(trains: List<Train>, selection: WatchSelection): MatchResult {
        if (selection.isEmpty) {
            return MatchResult.noMatch("선택된 열차 없음")
        }
        if (trains.isEmpty()) {
            return MatchResult.noMatch("조회된 열차 없음")
        }

        val selected = trains.flatMap { train ->
            SeatClass.entries
                .filter { selection.contains(train.key, it) }
                .map { SeatMatch(train, it) }
        }
        if (selected.isEmpty()) {
            return MatchResult.noMatch("선택한 열차가 이번 조회 결과에 없음")
        }

        val matches = selected
            .filter { it.train.seatStatusOf(it.seatClass) == SeatStatus.AVAILABLE }
            // 같은 사이클에 여러 칸이 열리면 먼저 출발하는 열차, 그다음 일반실을 먼저 본다.
            // (SeatClass 의 선언 순서가 일반실 → 특실 이다)
            .sortedWith(compareBy({ it.train.departureTime }, { it.seatClass.ordinal }))

        return if (matches.isEmpty()) {
            MatchResult.noMatch("선택한 좌석이 아직 나오지 않음")
        } else {
            MatchResult(
                matched = true,
                train = matches.first().train,
                reason = "선택한 좌석 발견",
                matches = matches,
            )
        }
    }
}
