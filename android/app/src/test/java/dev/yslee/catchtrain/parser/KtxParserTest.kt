package dev.yslee.catchtrain.parser

import dev.yslee.catchtrain.domain.SeatClass
import dev.yslee.catchtrain.domain.SeatStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class KtxParserTest {

    private val parser = KtxParser()

    /** 실측 형태 그대로. `[0]=일반실, [1]=특실` 이고 매진 칸에는 등급 class 가 없다. (§38-3) */
    private val sampleObject = """
        {
          "status": "TRAIN_LIST",
          "url": "https://www.korail.com/ticket/search/list",
          "title": "승차권 예매",
          "rowCount": 2,
          "trains": [
            {
              "trainNumber": "305",
              "trainType": "KTX-산천",
              "departureStation": "동탄",
              "arrivalStation": "김천구미",
              "departureTime": "07:11",
              "arrivalTime": "08:17",
              "generalSeatText": "일반실25,700원",
              "generalSeatStatus": "AVAILABLE",
              "generalSeatClass": "price_box fl-l gen",
              "generalCellIndex": 0,
              "firstClassSeatText": "매진",
              "firstClassSeatStatus": "SOLD_OUT",
              "firstClassSeatClass": "price_box fl-l sold_out",
              "firstClassCellIndex": 1,
              "rowKey": "2:abc123",
              "rowIndex": 0
            },
            {
              "trainNumber": "381",
              "trainType": "KTX-산천",
              "departureStation": "동탄",
              "arrivalStation": "김천구미",
              "departureTime": "07:11",
              "arrivalTime": "08:29",
              "generalSeatText": "매진",
              "generalSeatStatus": "SOLD_OUT",
              "generalSeatClass": "price_box fl-l sold_out",
              "generalCellIndex": 0,
              "firstClassSeatText": "특실(매진임박)37,200원",
              "firstClassSeatStatus": "AVAILABLE",
              "firstClassSeatClass": "price_box fl-l spe sold_out_soon",
              "firstClassCellIndex": 1,
              "rowKey": "2:def456",
              "rowIndex": 1
            }
          ],
          "warnings": []
        }
    """.trimIndent()

    @Test
    fun `객체 형태 결과를 파싱한다`() {
        val snapshot = parser.parse(sampleObject)

        assertEquals(PageStatus.TRAIN_LIST, snapshot.status)
        assertEquals(2, snapshot.trains.size)

        val first = snapshot.trains[0]
        assertEquals("305", first.trainNumber)
        assertEquals(LocalTime.of(7, 11), first.departureTime)
        assertEquals(LocalTime.of(8, 17), first.arrivalTime)
        assertEquals(SeatStatus.AVAILABLE, first.generalSeat)
        assertEquals(SeatStatus.SOLD_OUT, first.firstClassSeat)

        // 매진임박은 **살 수 있는 칸**이다. 문구에 "매진" 이 들어가는 함정. (§38-2)
        val second = snapshot.trains[1]
        assertEquals("381", second.trainNumber)
        assertEquals(SeatStatus.SOLD_OUT, second.generalSeat)
        assertEquals(SeatStatus.AVAILABLE, second.firstClassSeat)
    }

    /** 같은 출발 시각에 편성이 둘이다. 번호로 구분되어야 한다. (§38-4) */
    @Test
    fun `출발 시각이 겹쳐도 열차 번호로 구분된다`() {
        val trains = parser.parse(sampleObject).trains

        assertEquals(trains[0].departureTime, trains[1].departureTime)
        assertTrue("같은 열차로 읽히면 안 된다", !trains[0].key.matches(trains[1].key))
    }

    /** 좌석 칸 순번은 예매 1단계에서 어느 칸을 누를지 정하는 값이다. (§38-3) */
    @Test
    fun `좌석 칸 순번을 행 참조에 담는다`() {
        val snapshot = parser.parse(sampleObject)
        val ref = snapshot.rowRefOf(snapshot.trains[0])!!

        assertEquals("2:abc123", ref.rowKey)
        assertEquals(0, ref.cellIndexOf(SeatClass.GENERAL))
        assertEquals(1, ref.cellIndexOf(SeatClass.FIRST_CLASS))
        assertTrue(ref.usable)
    }

    @Test
    fun `문자열로 한 번 더 감싸인 결과도 파싱한다`() {
        // evaluateJavascript 가 JS 문자열을 돌려준 경우: JSON 문자열 리터럴로 도착한다.
        val quoted = org.json.JSONObject.quote(sampleObject)

        val snapshot = parser.parse(quoted)
        assertEquals(2, snapshot.trains.size)
    }

    @Test
    fun `열차 목록이 비면 NO_TRAIN 으로 강등한다`() {
        val raw = """{"status":"TRAIN_LIST","trains":[],"url":"","title":"","rowCount":0}"""
        assertEquals(PageStatus.NO_TRAIN, parser.parse(raw).status)
    }

    @Test
    fun `알 수 없는 status 는 UNKNOWN_PAGE 로 처리한다`() {
        val raw = """{"status":"WHAT_IS_THIS","trains":[]}"""
        assertEquals(PageStatus.UNKNOWN_PAGE, parser.parse(raw).status)
    }

    @Test
    fun `시간이 없는 행은 건너뛰고 경고를 남긴다`() {
        val raw = """
            {
              "status": "TRAIN_LIST",
              "trains": [
                {"trainNumber":"301","departureTime":"","arrivalTime":"","generalSeatStatus":"AVAILABLE"},
                {"trainNumber":"305","departureTime":"08:00","arrivalTime":"10:00","generalSeatStatus":"AVAILABLE"}
              ]
            }
        """.trimIndent()

        val snapshot = parser.parse(raw)
        assertEquals(1, snapshot.trains.size)
        assertEquals("305", snapshot.trains[0].trainNumber)
        assertTrue(snapshot.warnings.isNotEmpty())
    }

    /**
     * 번호를 읽지 못하면 **빈 문자열로 둔다.** 열차 종류로 메우면 모든 편성이 같은 값을
     * 갖게 되어 서로 다른 열차가 한 열차로 합쳐진다. (§38-4)
     */
    @Test
    fun `열차 번호를 읽지 못하면 종류로 메우지 않는다`() {
        val raw = """
            {
              "status": "TRAIN_LIST",
              "trains": [
                {"trainType":"KTX-산천","departureTime":"08:00","arrivalTime":"10:00"},
                {"trainType":"KTX-산천","departureTime":"09:00","arrivalTime":"11:00"}
              ]
            }
        """.trimIndent()

        val trains = parser.parse(raw).trains
        assertEquals(2, trains.size)
        assertEquals("", trains[0].trainNumber)
        assertTrue("번호가 없으면 모호한 키다", trains[0].key.ambiguous)
        assertTrue("시각이 다르면 다른 열차여야 한다", !trains[0].key.matches(trains[1].key))
        assertTrue(parser.parse(raw).warnings.isNotEmpty())
    }

    @Test
    fun `로그인 페이지 상태를 그대로 전달한다`() {
        val raw = """{"status":"LOGIN_REQUIRED","trains":[]}"""
        assertEquals(PageStatus.LOGIN_REQUIRED, parser.parse(raw).status)
    }

    @Test
    fun `빈 결과는 예외로 처리한다`() {
        assertThrows(DomParseException::class.java) { parser.parse(null) }
        assertThrows(DomParseException::class.java) { parser.parse("null") }
        assertThrows(DomParseException::class.java) { parser.parse("<html>") }
    }

    /**
     * JS 가 상태를 모르면 **class 로 먼저** 다시 판정한다. 텍스트는 마지막 대체 경로다.
     * 텍스트를 먼저 보면 `특실(매진임박)` 을 매진으로 오판한다. (§38-2)
     */
    @Test
    fun `JS 가 상태를 모르면 class 로 재판정한다`() {
        val raw = """
            {
              "status": "TRAIN_LIST",
              "trains": [
                {
                  "trainNumber":"305","departureTime":"08:00","arrivalTime":"10:00",
                  "generalSeatText":"매진","generalSeatStatus":"UNKNOWN",
                  "generalSeatClass":"price_box fl-l sold_out",
                  "firstClassSeatText":"특실(매진임박)37,200원","firstClassSeatStatus":"UNKNOWN",
                  "firstClassSeatClass":"price_box fl-l spe sold_out_soon"
                }
              ]
            }
        """.trimIndent()

        val train = parser.parse(raw).trains.single()
        assertEquals(SeatStatus.SOLD_OUT, train.generalSeat)
        assertEquals(SeatStatus.AVAILABLE, train.firstClassSeat)
    }

    /** class 도 없으면 텍스트로 본다. 그때도 "매진임박" 이 "매진" 보다 먼저다. */
    @Test
    fun `class 가 없으면 텍스트로 재판정한다`() {
        val raw = """
            {
              "status": "TRAIN_LIST",
              "trains": [
                {
                  "trainNumber":"305","departureTime":"08:00","arrivalTime":"10:00",
                  "generalSeatText":"매진임박","generalSeatStatus":"UNKNOWN",
                  "firstClassSeatText":"매진","firstClassSeatStatus":"UNKNOWN"
                }
              ]
            }
        """.trimIndent()

        val train = parser.parse(raw).trains.single()
        assertEquals(SeatStatus.AVAILABLE, train.generalSeat)
        assertEquals(SeatStatus.SOLD_OUT, train.firstClassSeat)
    }
}
