package dev.yslee.catchtrain.parser

import dev.yslee.catchtrain.domain.Train
import org.json.JSONObject
import java.time.LocalTime

/**
 * JS 가 만든 행(row) JSON 하나 → [Train]. (DESIGN.md §6 parser/TrainParser.kt)
 *
 * 유효하지 않은 행은 null 을 돌려주고 이유를 [warnings] 에 남긴다.
 * 한 행이 깨져도 전체 분석이 실패하지 않도록 하는 것이 목적이다.
 */
object TrainParser {

    private val TIME_REGEX = Regex("([0-2]?\\d):([0-5]\\d)")

    fun parse(row: JSONObject, warnings: MutableList<String>): Train? {
        val trainNumber = row.optString("trainNumber").trim()
            .ifBlank { row.optString("trainType").trim() }
            .ifBlank { "SRT" }

        val departureTime = parseTime(row.optString("departureTime"))
        if (departureTime == null) {
            warnings += "출발시간 파싱 실패: ${row.optString("departureTime")}"
            return null
        }

        val arrivalTime = parseTime(row.optString("arrivalTime"))
        if (arrivalTime == null) {
            warnings += "도착시간 파싱 실패: ${row.optString("arrivalTime")}"
            return null
        }

        return Train(
            trainNumber = trainNumber,
            departureStation = row.optString("departureStation").trim(),
            arrivalStation = row.optString("arrivalStation").trim(),
            departureTime = departureTime,
            arrivalTime = arrivalTime,
            generalSeat = SeatParser.fromJs(
                jsStatus = row.optString("generalSeatStatus"),
                rawText = row.optString("generalSeatText"),
            ),
            firstClassSeat = SeatParser.fromJs(
                jsStatus = row.optString("firstClassSeatStatus"),
                rawText = row.optString("firstClassSeatText"),
            ),
        )
    }

    /**
     * "18:32", "18:32 도착", "출발 18:32" 등에서 시각을 뽑는다.
     * SRT 는 24:00 이후 표기를 쓰지 않으므로 24시 이상은 무효로 본다.
     */
    fun parseTime(raw: String?): LocalTime? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val match = TIME_REGEX.find(text) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour > 23 || minute > 59) return null
        return LocalTime.of(hour, minute)
    }
}
