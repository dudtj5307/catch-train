package dev.yslee.catchtrain.parser

import dev.yslee.catchtrain.domain.SeatClass
import dev.yslee.catchtrain.domain.SeatStatus

/**
 * 좌석 칸 → [SeatStatus] 변환.
 *
 * 판정 경로가 둘이다. **코레일에서는 [fromClassNames] 가 정답이고 [parse] 는 대체 경로다.**
 * (DESIGN.md §38-2)
 *
 * 코레일은 좌석 상태를 `div.price_box` 의 class 로 표현한다. 텍스트는 사람이 읽을
 * 문구일 뿐이라 그대로 믿으면 안 된다 — `특실(매진임박) 37,200원` 은 **살 수 있는 칸**인데
 * "매진" 을 부분일치로 찾으면 매진으로 오판한다. 실측에서 10편성 중 3편성이 이 상태였다.
 *
 * JS 쪽에서도 1차 판정을 하지만, JS 가 판정을 못한 경우와 단위 테스트를 위해
 * Kotlin 쪽에도 같은 규칙을 둔다. 규칙을 한 곳에서만 고칠 수 있도록 목록은 이 파일에 모은다.
 */
object SeatParser {

    // --- class 기반 판정 (코레일) ---------------------------------------------

    /** 매진임박. 문구에 "매진" 이 들어가지만 **아직 예약 가능하다.** */
    private const val SOLD_OUT_SOON = "sold_out_soon"

    /** 매진 */
    private val SOLD_OUT_CLASSES = setOf("sold_out", "sold_out_wait")

    /** 예약대기 */
    private const val WAIT = "wait"

    /** 등급이 드러나는 class. 매진 칸에는 붙지 않는다. */
    private const val GENERAL = "gen"
    private const val FIRST_CLASS = "spe"

    /** 사용자가 1단계에서 고른 칸에 붙는다. 상태가 아니라 선택 표시다. (§38-6) */
    private const val ACTIVE = "active"

    private fun tokensOf(classNames: String?): Set<String> =
        classNames?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    /**
     * `div.price_box` 의 class 문자열로 좌석 상태를 판정한다.
     *
     * **부분일치를 쓰지 않고 class 토큰 완전일치로만 본다.** `sold_out_soon` 안에
     * `sold_out` 이, `sold_out_wait` 안에 `wait` 가 들어 있어서 부분일치로는
     * 순서를 아무리 맞춰도 언젠가 어긋난다.
     */
    fun fromClassNames(classNames: String?): SeatStatus {
        val tokens = tokensOf(classNames)
        if (tokens.isEmpty()) return SeatStatus.UNKNOWN

        // 매진임박이 매진보다 먼저다. 문구가 아니라 class 로 보므로 순서 의존은 없지만,
        // 읽는 사람이 헷갈리지 않도록 위에 둔다.
        if (SOLD_OUT_SOON in tokens) return SeatStatus.AVAILABLE
        if (tokens.any { it in SOLD_OUT_CLASSES }) return SeatStatus.SOLD_OUT
        if (WAIT in tokens) return SeatStatus.WAITING
        if (GENERAL in tokens || FIRST_CLASS in tokens) return SeatStatus.AVAILABLE

        return SeatStatus.UNKNOWN
    }

    /**
     * class 에서 좌석 등급을 읽는다. 읽어낼 수 없으면 null.
     *
     * **매진 칸(`sold_out` / `sold_out_wait` / `wait`)에는 등급 class 가 붙지 않는다.**
     * 그런 칸의 등급은 `li` 안에서의 **위치**로 정해야 한다 (§38-3, 일반실이 왼쪽).
     * 그래서 이 함수가 null 을 돌려주는 것은 정상이며, 호출부는 위치로 보정해야 한다.
     */
    fun seatClassOf(classNames: String?): SeatClass? {
        val tokens = tokensOf(classNames)
        return when {
            GENERAL in tokens -> SeatClass.GENERAL
            FIRST_CLASS in tokens -> SeatClass.FIRST_CLASS
            else -> null
        }
    }

    /** 1단계에서 이 칸을 눌러 선택된 상태인가. (§38-6-1 의 확인 1) */
    fun isSelected(classNames: String?): Boolean = ACTIVE in tokensOf(classNames)

    // --- 텍스트 기반 판정 (대체 경로) ------------------------------------------

    /** "매진" 을 포함하지만 매진이 아닌 문구. 반드시 [SOLD_OUT_TOKENS] 보다 먼저 본다. */
    private val AVAILABLE_DESPITE_SOLD_OUT_TOKENS = listOf("매진임박")

    private val SOLD_OUT_TOKENS = listOf("매진", "좌석없음", "잔여석없음", "없음")
    private val WAITING_TOKENS = listOf("예약대기", "대기", "입석")
    private val AVAILABLE_TOKENS = listOf("예약하기", "좌석선택", "예매하기", "선택하기", "가능", "있음")

    /**
     * 좌석 칸의 텍스트로 판정한다.
     *
     * class 를 읽지 못했을 때만 쓰는 대체 경로다. 코레일에서는 [fromClassNames] 를 먼저 본다.
     */
    fun parse(rawText: String?): SeatStatus {
        val text = rawText?.replace(Regex("\\s+"), "")?.trim().orEmpty()
        if (text.isEmpty() || text == "-" || text.all { it == '-' }) return SeatStatus.UNKNOWN

        // 판정 순서가 중요하다.
        // "매진임박" 은 "매진" 을 포함하므로 반드시 먼저 걸러야 한다. (§38-2)
        if (AVAILABLE_DESPITE_SOLD_OUT_TOKENS.any { text.contains(it) }) return SeatStatus.AVAILABLE
        if (SOLD_OUT_TOKENS.any { text.contains(it) }) return SeatStatus.SOLD_OUT
        if (WAITING_TOKENS.any { text.contains(it) }) return SeatStatus.WAITING
        if (AVAILABLE_TOKENS.any { text.contains(it) }) return SeatStatus.AVAILABLE

        return SeatStatus.UNKNOWN
    }

    /** JS 가 이미 판정해서 보낸 enum 이름을 신뢰하고, 알 수 없으면 class → 텍스트 순으로 재판정한다. */
    fun fromJs(jsStatus: String?, rawText: String?, classNames: String? = null): SeatStatus {
        val parsed = jsStatus?.let { name -> SeatStatus.entries.firstOrNull { it.name == name } }
        if (parsed != null && parsed != SeatStatus.UNKNOWN) return parsed

        val byClass = fromClassNames(classNames)
        return if (byClass != SeatStatus.UNKNOWN) byClass else parse(rawText)
    }
}
