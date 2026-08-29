package com.example.srtwatcher.parser

import com.example.srtwatcher.domain.SeatStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class SrtParserTest {

    private val parser = SrtParser()

    private val sampleObject = """
        {
          "status": "TRAIN_LIST",
          "url": "https://etk.srail.kr/hpg/hra/01/selectScheduleList.do",
          "title": "승차권 예약",
          "rowCount": 2,
          "trains": [
            {
              "trainNumber": "SRT 339",
              "departureStation": "수서",
              "arrivalStation": "부산",
              "departureTime": "18:30",
              "arrivalTime": "21:05",
              "generalSeatText": "예약하기",
              "generalSeatStatus": "AVAILABLE",
              "firstClassSeatText": "매진",
              "firstClassSeatStatus": "SOLD_OUT"
            },
            {
              "trainNumber": "SRT 341",
              "departureStation": "수서",
              "arrivalStation": "부산",
              "departureTime": "19:00",
              "arrivalTime": "21:35",
              "generalSeatText": "매진",
              "generalSeatStatus": "SOLD_OUT",
              "firstClassSeatText": "-",
              "firstClassSeatStatus": "UNKNOWN"
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
        assertEquals("SRT 339", first.trainNumber)
        assertEquals(LocalTime.of(18, 30), first.departureTime)
        assertEquals(LocalTime.of(21, 5), first.arrivalTime)
        assertEquals(SeatStatus.AVAILABLE, first.generalSeat)
        assertEquals(SeatStatus.SOLD_OUT, first.firstClassSeat)

        val second = snapshot.trains[1]
        assertEquals(SeatStatus.SOLD_OUT, second.generalSeat)
        assertEquals(SeatStatus.UNKNOWN, second.firstClassSeat)
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
                {"trainNumber":"SRT 1","departureTime":"","arrivalTime":"","generalSeatStatus":"AVAILABLE"},
                {"trainNumber":"SRT 2","departureTime":"08:00","arrivalTime":"10:00","generalSeatStatus":"AVAILABLE"}
              ]
            }
        """.trimIndent()

        val snapshot = parser.parse(raw)
        assertEquals(1, snapshot.trains.size)
        assertEquals("SRT 2", snapshot.trains[0].trainNumber)
        assertTrue(snapshot.warnings.isNotEmpty())
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

    @Test
    fun `JS 가 상태를 모르면 텍스트로 재판정한다`() {
        val raw = """
            {
              "status": "TRAIN_LIST",
              "trains": [
                {
                  "trainNumber":"SRT 5","departureTime":"08:00","arrivalTime":"10:00",
                  "generalSeatText":"예약하기","generalSeatStatus":"UNKNOWN",
                  "firstClassSeatText":"잔여석없음","firstClassSeatStatus":"UNKNOWN"
                }
              ]
            }
        """.trimIndent()

        val train = parser.parse(raw).trains.single()
        assertEquals(SeatStatus.AVAILABLE, train.generalSeat)
        assertEquals(SeatStatus.SOLD_OUT, train.firstClassSeat)
    }
}
