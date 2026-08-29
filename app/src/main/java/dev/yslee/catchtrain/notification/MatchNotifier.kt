package dev.yslee.catchtrain.notification

import dev.yslee.catchtrain.domain.SeatMatch

/**
 * 알림 발송 인터페이스.
 *
 * WatchController 가 Android 프레임워크에 직접 의존하지 않도록 분리한다.
 *
 * 알림에 필요한 정보(구간/시각/좌석등급)는 전부 [SeatMatch] 안에 있다.
 * 예전에는 사용자가 설정한 조건을 함께 넘겼지만, 이제 감시 대상은 사용자가
 * 화면에서 고른 열차 그 자체라 따로 붙일 조건이 없다.
 */
interface MatchNotifier {

    /**
     * @param extraCount 같은 사이클에서 함께 발견된 나머지 건수
     */
    fun notifyMatch(match: SeatMatch, extraCount: Int = 0)

    /**
     * [예약하기] 를 눌러 결제 화면까지 넘어갔는데 사용자가 아직 확인하지 못한 경우의
     * 재촉 알림. (DESIGN.md §19-3)
     *
     * 좌석 발견 알림([notifyMatch])과 달리 **사용자가 멈출 때까지 되풀이해서** 온다.
     * 결제에는 제한 시간이 있어서, 한 번 울리고 마는 알림은 놓치면 그대로 좌석을
     * 잃기 때문이다. 그래서 소리와 진동을 매번 다시 울린다.
     *
     * @param repeatIndex 몇 번째 재촉인지. 1 부터 센다.
     * @param elapsedMs [예약하기] 를 누른 뒤 흐른 시간
     */
    fun notifyReserveReminder(match: SeatMatch, repeatIndex: Int, elapsedMs: Long)

    /** 재촉 알림만 걷어낸다. 좌석 발견 알림은 건드리지 않는다. */
    fun cancelReserveReminder()

    fun cancelAll()
}
