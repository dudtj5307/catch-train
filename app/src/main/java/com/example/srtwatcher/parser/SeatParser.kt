package com.example.srtwatcher.parser

import com.example.srtwatcher.domain.SeatStatus

/**
 * 좌석 칸의 텍스트 → [SeatStatus] 변환. (DESIGN.md §6 parser/SeatParser.kt)
 *
 * JS 쪽에서도 1차 판정을 하지만, JS 가 판정을 못한 경우와
 * 단위 테스트를 위해 Kotlin 쪽에도 같은 규칙을 둔다.
 * 규칙을 한 곳에서만 고칠 수 있도록 문자열 목록은 이 파일에 모은다.
 */
object SeatParser {

    private val SOLD_OUT_TOKENS = listOf("매진", "좌석없음", "잔여석없음", "없음")
    private val WAITING_TOKENS = listOf("예약대기", "대기", "입석")
    private val AVAILABLE_TOKENS = listOf("예약하기", "좌석선택", "예매하기", "선택하기", "가능", "있음")

    fun parse(rawText: String?): SeatStatus {
        val text = rawText?.replace(Regex("\\s+"), "")?.trim().orEmpty()
        if (text.isEmpty() || text == "-" || text.all { it == '-' }) return SeatStatus.UNKNOWN

        // 판정 순서가 중요하다. "잔여석없음" 은 "있음" 을 포함하지 않지만
        // "좌석없음" 계열을 먼저 걸러야 오판을 막을 수 있다.
        if (SOLD_OUT_TOKENS.any { text.contains(it) }) return SeatStatus.SOLD_OUT
        if (WAITING_TOKENS.any { text.contains(it) }) return SeatStatus.WAITING
        if (AVAILABLE_TOKENS.any { text.contains(it) }) return SeatStatus.AVAILABLE

        return SeatStatus.UNKNOWN
    }

    /** JS 가 이미 판정해서 보낸 enum 이름을 신뢰하고, 알 수 없으면 텍스트로 재판정한다. */
    fun fromJs(jsStatus: String?, rawText: String?): SeatStatus {
        val parsed = jsStatus?.let { name -> SeatStatus.entries.firstOrNull { it.name == name } }
        return if (parsed == null || parsed == SeatStatus.UNKNOWN) parse(rawText) else parsed
    }
}
