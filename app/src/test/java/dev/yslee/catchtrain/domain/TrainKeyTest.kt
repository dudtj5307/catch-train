package dev.yslee.catchtrain.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * 열차 식별 규칙. (DESIGN.md §38-4)
 *
 * 실측 근거는 코레일 동탄→김천구미 조회 결과다.
 * 07:11 에 305 와 381 이, 18:55 에 353 과 397 이 함께 있었다.
 */
class TrainKeyTest {

    private fun key(number: String, hour: Int, minute: Int = 0) =
        TrainKey(trainNumber = number, departureTime = LocalTime.of(hour, minute))

    @Test
    fun `번호가 같으면 같은 열차다`() {
        assertTrue(key("305", 7, 11).matches(key("305", 7, 11)))
    }

    @Test
    fun `출발 시각이 같아도 번호가 다르면 다른 열차다`() {
        // 이것이 SRT 규칙에서 실제로 깨지던 경우다.
        val ktx305 = key("305", 7, 11)
        val ktx381 = key("381", 7, 11)

        assertFalse(ktx305.matches(ktx381))
        assertFalse(ktx381.matches(ktx305))
    }

    @Test
    fun `18시 55분의 353 과 397 도 서로 다른 열차다`() {
        assertFalse(key("353", 18, 55).matches(key("397", 18, 55)))
    }

    @Test
    fun `번호가 같으면 시각을 다시 읽은 값이 달라도 같은 열차로 본다`() {
        // 재조회 사이에 사이트가 시각 표기를 바꾸는 경우까지 놓치지 않는다.
        // 한 조회 결과 안에서 번호는 유일하므로 번호만으로 충분하다.
        assertTrue(key("305", 7, 11).matches(key("305", 7, 12)))
    }

    @Test
    fun `한쪽 번호를 읽지 못하면 출발 시각으로 대체 판정한다`() {
        val known = key("305", 7, 11)
        val unread = key("", 7, 11)

        assertTrue(known.matches(unread))
        assertTrue(unread.matches(known))
        assertFalse(known.matches(key("", 8, 11)))
    }

    @Test
    fun `번호를 읽지 못한 키는 모호한 키로 표시된다`() {
        assertTrue(key("", 7, 11).ambiguous)
        assertFalse(key("305", 7, 11).ambiguous)
    }

    @Test
    fun `표시 문자열`() {
        assertEquals("07:11 305", key("305", 7, 11).label())
        assertEquals("07:11", key("", 7, 11).label())
    }

    @Test
    fun `선택은 번호로 유지된다`() {
        // 재조회로 목록 순서가 바뀌어도 체크해 둔 편성은 그대로 남아야 한다.
        val picked = key("381", 7, 11)
        val selection = WatchSelection.empty().toggle(picked, SeatClass.GENERAL)

        assertTrue(selection.contains(key("381", 7, 11), SeatClass.GENERAL))
        // 같은 시각의 다른 편성이 열려도 내 선택이 아니다.
        assertFalse(selection.contains(key("305", 7, 11), SeatClass.GENERAL))
    }
}
