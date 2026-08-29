package dev.yslee.catchtrain.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class SelectionEngineTest {

    private val engine = SelectionEngine()

    private fun train(
        number: String,
        hour: Int,
        general: SeatStatus = SeatStatus.SOLD_OUT,
        firstClass: SeatStatus = SeatStatus.SOLD_OUT,
    ) = Train(
        trainNumber = number,
        departureStation = "수서",
        arrivalStation = "부산",
        departureTime = LocalTime.of(hour, 30),
        arrivalTime = LocalTime.of(hour + 2, 35),
        generalSeat = general,
        firstClassSeat = firstClass,
    )

    private fun selectionOf(vararg seats: Pair<Train, SeatClass>) =
        WatchSelection(seats.map { SeatSelection(it.first.key, it.second) }.toSet())

    @Test
    fun `선택한 칸이 예약가능이면 발견이다`() {
        val target = train("SRT 339", 18, general = SeatStatus.AVAILABLE)
        val result = engine.match(listOf(target), selectionOf(target to SeatClass.GENERAL))

        assertTrue(result.matched)
        assertEquals(1, result.matches.size)
        assertEquals(SeatClass.GENERAL, result.matches.single().seatClass)
    }

    @Test
    fun `선택하지 않은 칸이 열려도 발견이 아니다`() {
        // 특실이 열렸지만 사용자가 고른 것은 일반실이다.
        val target = train("SRT 339", 18, firstClass = SeatStatus.AVAILABLE)
        val result = engine.match(listOf(target), selectionOf(target to SeatClass.GENERAL))

        assertFalse(result.matched)
        assertTrue(result.matches.isEmpty())
    }

    @Test
    fun `선택하지 않은 열차는 아예 보지 않는다`() {
        val picked = train("SRT 339", 18)
        val other = train("SRT 341", 19, general = SeatStatus.AVAILABLE)

        val result = engine.match(listOf(picked, other), selectionOf(picked to SeatClass.GENERAL))

        assertFalse(result.matched)
    }

    @Test
    fun `예약대기는 발견으로 보지 않는다`() {
        val target = train("SRT 339", 18, general = SeatStatus.WAITING)
        val result = engine.match(listOf(target), selectionOf(target to SeatClass.GENERAL))

        assertFalse(result.matched)
        assertEquals("선택한 좌석이 아직 나오지 않음", result.reason)
    }

    @Test
    fun `한 열차에서 특실과 일반실을 모두 고르면 두 건으로 집계한다`() {
        val target = train(
            "SRT 339",
            18,
            general = SeatStatus.AVAILABLE,
            firstClass = SeatStatus.AVAILABLE,
        )
        val result = engine.match(
            listOf(target),
            selectionOf(target to SeatClass.GENERAL, target to SeatClass.FIRST_CLASS),
        )

        assertEquals(2, result.matches.size)
        // 같은 열차라면 일반실을 먼저 본다. (더 싼 쪽)
        assertEquals(SeatClass.GENERAL, result.matches.first().seatClass)
    }

    @Test
    fun `여러 열차가 동시에 열리면 먼저 출발하는 열차가 앞에 온다`() {
        val late = train("SRT 341", 19, general = SeatStatus.AVAILABLE)
        val early = train("SRT 339", 18, general = SeatStatus.AVAILABLE)

        val result = engine.match(
            listOf(late, early),
            selectionOf(late to SeatClass.GENERAL, early to SeatClass.GENERAL),
        )

        assertEquals("SRT 339", result.matches.first().train.trainNumber)
        assertEquals("SRT 339", result.train?.trainNumber)
    }

    @Test
    fun `열차 번호를 못 읽어도 출발 시각이 같으면 같은 열차로 본다`() {
        val picked = train("SRT 339", 18, general = SeatStatus.AVAILABLE)
        // 다음 조회에서는 파서가 열차 번호를 읽지 못했다.
        val reparsed = picked.copy(trainNumber = "")

        val result = engine.match(listOf(reparsed), selectionOf(picked to SeatClass.GENERAL))

        assertTrue(result.matched)
    }

    @Test
    fun `선택이 비어 있으면 사유를 남긴다`() {
        val target = train("SRT 339", 18, general = SeatStatus.AVAILABLE)
        val result = engine.match(listOf(target), WatchSelection.empty())

        assertFalse(result.matched)
        assertEquals("선택된 열차 없음", result.reason)
    }

    @Test
    fun `선택한 열차가 이번 조회 결과에 없으면 사유를 남긴다`() {
        val picked = train("SRT 339", 18, general = SeatStatus.AVAILABLE)
        val different = train("SRT 501", 7, general = SeatStatus.AVAILABLE)

        val result = engine.match(listOf(different), selectionOf(picked to SeatClass.GENERAL))

        assertFalse(result.matched)
        assertEquals("선택한 열차가 이번 조회 결과에 없음", result.reason)
    }

    @Test
    fun `화면에 없는 열차의 선택은 정리한다`() {
        val picked = train("SRT 339", 18)
        val other = train("SRT 341", 19)
        val selection = selectionOf(picked to SeatClass.GENERAL, other to SeatClass.FIRST_CLASS)

        val kept = selection.retainOnly(listOf(other))

        assertEquals(1, kept.size)
        assertTrue(kept.contains(other, SeatClass.FIRST_CLASS))
    }

    @Test
    fun `토글은 같은 칸을 다시 누르면 해제한다`() {
        val target = train("SRT 339", 18)
        val on = WatchSelection.empty().toggle(target.key, SeatClass.FIRST_CLASS)
        assertTrue(on.contains(target, SeatClass.FIRST_CLASS))
        assertFalse(on.contains(target, SeatClass.GENERAL))

        val off = on.toggle(target.key, SeatClass.FIRST_CLASS)
        assertTrue(off.isEmpty)
    }
}
