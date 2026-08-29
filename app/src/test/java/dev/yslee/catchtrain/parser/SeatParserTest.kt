package dev.yslee.catchtrain.parser

import dev.yslee.catchtrain.domain.SeatStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class SeatParserTest {

    @Test
    fun `예약 가능 표기`() {
        assertEquals(SeatStatus.AVAILABLE, SeatParser.parse("예약하기"))
        assertEquals(SeatStatus.AVAILABLE, SeatParser.parse(" 좌석선택 "))
        assertEquals(SeatStatus.AVAILABLE, SeatParser.parse("예약 가능"))
    }

    @Test
    fun `매진 표기`() {
        assertEquals(SeatStatus.SOLD_OUT, SeatParser.parse("매진"))
        assertEquals(SeatStatus.SOLD_OUT, SeatParser.parse("좌석없음"))
        assertEquals(SeatStatus.SOLD_OUT, SeatParser.parse("잔여석없음"))
    }

    @Test
    fun `예약대기와 입석은 WAITING`() {
        assertEquals(SeatStatus.WAITING, SeatParser.parse("예약대기"))
        assertEquals(SeatStatus.WAITING, SeatParser.parse("입석+좌석"))
    }

    @Test
    fun `빈 값과 대시는 UNKNOWN`() {
        assertEquals(SeatStatus.UNKNOWN, SeatParser.parse(null))
        assertEquals(SeatStatus.UNKNOWN, SeatParser.parse(""))
        assertEquals(SeatStatus.UNKNOWN, SeatParser.parse("-"))
        assertEquals(SeatStatus.UNKNOWN, SeatParser.parse("---"))
    }

    @Test
    fun `모르는 문자열은 UNKNOWN`() {
        assertEquals(SeatStatus.UNKNOWN, SeatParser.parse("38,000원"))
    }

    @Test
    fun `JS 판정을 우선하고 UNKNOWN 이면 텍스트로 보정한다`() {
        assertEquals(SeatStatus.SOLD_OUT, SeatParser.fromJs("SOLD_OUT", "예약하기"))
        assertEquals(SeatStatus.AVAILABLE, SeatParser.fromJs("UNKNOWN", "예약하기"))
        assertEquals(SeatStatus.AVAILABLE, SeatParser.fromJs(null, "예약하기"))
        assertEquals(SeatStatus.UNKNOWN, SeatParser.fromJs("이상한값", "38,000원"))
    }
}
