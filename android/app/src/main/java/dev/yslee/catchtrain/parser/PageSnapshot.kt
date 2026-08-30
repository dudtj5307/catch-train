package dev.yslee.catchtrain.parser

import dev.yslee.catchtrain.domain.SeatClass
import dev.yslee.catchtrain.domain.Train

/**
 * 현재 페이지가 어떤 종류의 페이지인지. (DESIGN.md §27 의 에러 구분과 연결된다)
 */
enum class PageStatus {
    /** 열차 조회 결과 테이블을 찾음 */
    TRAIN_LIST,

    /** 조회 결과 페이지지만 열차가 없음 */
    NO_TRAIN,

    /** 로그인 화면 */
    LOGIN_REQUIRED,

    /** 세션이 만료되어 다시 로그인해야 함 */
    SESSION_EXPIRED,

    /**
     * 접속 차단 / 비정상 접근 안내 페이지.
     * 이 상태에서 재조회를 계속하면 차단이 길어지므로 즉시 중지한다.
     */
    BLOCKED,

    /** 감시할 수 있는 페이지가 아님 (메인 화면, 안내 페이지 등) */
    UNKNOWN_PAGE,
    ;

    /**
     * **이 판정을 그대로 믿고 다음 단계로 가도 되는가.** (DESIGN.md §39)
     *
     * [UNKNOWN_PAGE] 만 false 다. 나머지 다섯은 화면에서 무언가를 **확실히 알아본**
     * 결과라 더 기다려도 답이 바뀌지 않는다 — 목록이 있거나(TRAIN_LIST), 결과
     * 컨테이너는 있는데 0건이거나(NO_TRAIN), 로그인/세션/차단 안내가 떠 있다.
     *
     * [UNKNOWN_PAGE] 는 **"아직 아무것도 못 알아봤다"** 는 뜻이고 원인이 둘이다.
     * 화면이 정말 엉뚱한 곳이거나, **아직 그려지는 중**이거나. 접속 대기열에 걸린
     * 화면도 여기로 온다. 화면만 보고는 그 둘을 가를 수 없으므로
     * [dev.yslee.catchtrain.watcher.WatchController] 는 이 값이 나와도 곧바로
     * 판정하지 않고 제자리에서 다시 읽어 본다.
     */
    val isSettled: Boolean
        get() = this != UNKNOWN_PAGE
}

/**
 * DOM 한 번 분석의 결과.
 *
 * DESIGN.md §15 의 `parse(rawJson): List<Train>` 을 확장한 형태다.
 * 감시 루프가 §27 의 에러 상태를 구분하려면 열차 목록만으로는 부족하므로
 * 페이지 종류와 경고 메시지를 함께 담는다.
 */
data class PageSnapshot(
    val status: PageStatus,
    val url: String,
    val title: String,
    val trains: List<Train>,
    val rowCount: Int = 0,
    /**
     * 조회 폼이 들고 있던 출발일 ("2026-08-24"). 읽어내지 못했으면 빈 문자열.
     *
     * 감시 조건은 여전히 앱이 갖지 않는다. 이 값은 **지금 보고 있는 화면이 어느 날짜의
     * 결과인지**를 사용자에게 되비쳐 주기 위한 표시용이며, 판정에는 쓰지 않는다.
     */
    val searchDate: String = "",
    val warnings: List<String> = emptyList(),
    /**
     * [trains] 와 같은 순서, 같은 길이의 행 참조 목록.
     *
     * [Train] 은 DOM 을 전혀 모르는 도메인 모델이어야 하므로, 화면에서 그 열차가
     * 어디에 그려져 있는지는 여기에 따로 담는다. [예약하기] 를 누를 때만 쓴다.
     */
    val rowRefs: List<RowRef> = emptyList(),
) {

    /** [train] 이 화면의 어느 행에서 나왔는지. 알 수 없으면 null. */
    fun rowRefOf(train: Train): RowRef? {
        val index = trains.indexOfFirst { it === train }
            .takeIf { it >= 0 }
            ?: trains.indexOf(train)
        return if (index >= 0) rowRefs.getOrNull(index) else null
    }
}

/**
 * 열차 한 편성이 화면의 어느 행/어느 칸에 그려져 있는지. (DESIGN.md §19)
 *
 * [rowKey] 는 분석 시점에 그 행이 담고 있던 내용을 요약한 값이다.
 * 예약하기를 누르기 직전에 같은 값을 다시 계산해서, 정말 같은 행인지 확인한다.
 * 위치([rowIndex])만 믿으면 표가 갱신된 순간 엉뚱한 열차를 예약하게 된다.
 */
data class RowRef(
    val rowKey: String,
    val rowIndex: Int,
    val generalCellIndex: Int = -1,
    val firstClassCellIndex: Int = -1,
) {
    /** 좌석 등급에 해당하는 칸의 위치. 특정하지 못했으면 -1. */
    fun cellIndexOf(seatClass: SeatClass): Int = when (seatClass) {
        SeatClass.GENERAL -> generalCellIndex
        SeatClass.FIRST_CLASS -> firstClassCellIndex
    }

    val usable: Boolean get() = rowKey.isNotBlank()
}
