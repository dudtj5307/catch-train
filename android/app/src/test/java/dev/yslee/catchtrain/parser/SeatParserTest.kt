package dev.yslee.catchtrain.parser

import dev.yslee.catchtrain.domain.SeatClass
import dev.yslee.catchtrain.domain.SeatStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // --- 코레일 class 기반 판정 (DESIGN.md §38-2) ------------------------------
    // 아래 class 조합은 전부 실측에서 나온 것이다. (동탄→김천구미, 10편성)

    @Test
    fun `매진임박은 살 수 있는 칸이다`() {
        // 실측 10편성 중 3편성이 이 상태였다. 텍스트로 읽으면 전부 놓친다.
        assertEquals(SeatStatus.AVAILABLE, SeatParser.fromClassNames("price_box fl-l spe sold_out_soon"))
        assertEquals(SeatStatus.AVAILABLE, SeatParser.parse("특실(매진임박) 37,200원 5%적립"))
    }

    @Test
    fun `예약 가능한 칸`() {
        assertEquals(SeatStatus.AVAILABLE, SeatParser.fromClassNames("price_box fl-l gen"))
        assertEquals(SeatStatus.AVAILABLE, SeatParser.fromClassNames("price_box fl-l spe"))
    }

    @Test
    fun `매진인 칸`() {
        assertEquals(SeatStatus.SOLD_OUT, SeatParser.fromClassNames("price_box fl-l sold_out"))
        assertEquals(SeatStatus.SOLD_OUT, SeatParser.fromClassNames("price_box fl-l sold_out_wait"))
    }

    @Test
    fun `예약대기는 WAITING 이고 sold_out_wait 와 섞이지 않는다`() {
        assertEquals(SeatStatus.WAITING, SeatParser.fromClassNames("price_box fl-l wait"))
        // sold_out_wait 안에 wait 가 들어 있다. 부분일치로 읽으면 여기서 어긋난다.
        assertEquals(SeatStatus.SOLD_OUT, SeatParser.fromClassNames("price_box fl-l sold_out_wait"))
    }

    @Test
    fun `선택 표시는 상태 판정을 바꾸지 않는다`() {
        assertEquals(SeatStatus.AVAILABLE, SeatParser.fromClassNames("price_box fl-l active gen"))
        assertEquals(SeatStatus.WAITING, SeatParser.fromClassNames("price_box fl-l active wait"))

        assertTrue(SeatParser.isSelected("price_box fl-l active gen"))
        assertFalse(SeatParser.isSelected("price_box fl-l gen"))
    }

    @Test
    fun `등급은 class 에서 읽고 매진 칸은 읽지 못한다`() {
        assertEquals(SeatClass.GENERAL, SeatParser.seatClassOf("price_box fl-l gen"))
        assertEquals(SeatClass.FIRST_CLASS, SeatParser.seatClassOf("price_box fl-l spe sold_out_soon"))

        // 매진 칸에는 등급 class 가 붙지 않는다. 위치로 보정해야 한다. (§38-3)
        assertNull(SeatParser.seatClassOf("price_box fl-l sold_out"))
        assertNull(SeatParser.seatClassOf("price_box fl-l wait"))
    }

    @Test
    fun `모르는 class 는 UNKNOWN`() {
        assertEquals(SeatStatus.UNKNOWN, SeatParser.fromClassNames(null))
        assertEquals(SeatStatus.UNKNOWN, SeatParser.fromClassNames(""))
        assertEquals(SeatStatus.UNKNOWN, SeatParser.fromClassNames("price_box fl-l"))
    }

    @Test
    fun `JS 가 판정을 못하면 텍스트보다 class 를 먼저 믿는다`() {
        // 문구에는 "매진" 이 들어 있지만 class 는 살 수 있는 칸이라고 말한다. class 가 맞다.
        assertEquals(
            SeatStatus.AVAILABLE,
            SeatParser.fromJs("UNKNOWN", "특실(매진임박) 37,200원", "price_box fl-l spe sold_out_soon"),
        )
    }
}
