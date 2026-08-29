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
        // 열차 번호는 [dev.yslee.catchtrain.domain.TrainKey] 의 **주키**다. (DESIGN.md §38-4)
        // 읽지 못했다면 빈 문자열 그대로 둔다. 열차 종류("KTX-산천")로 메우면 그럴듯해
        // 보이지만 모든 편성이 같은 값을 갖게 되어 서로 다른 열차가 한 열차로 합쳐진다.
        // 빈 값은 TrainKey 가 출발 시각 대체 경로(ambiguous)로 처리한다.
        val trainNumber = row.optString("trainNumber").trim()
        if (trainNumber.isBlank()) {
            warnings += "열차 번호를 읽지 못했습니다 (출발 시각으로 구분합니다)"
        }

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
            // class 문자열을 함께 넘긴다. JS 가 판정하지 못한 칸을 Kotlin 쪽에서
            // 같은 규칙으로 다시 볼 수 있어야 한다. 텍스트는 마지막 대체 경로다. (§38-2)
            generalSeat = SeatParser.fromJs(
                jsStatus = row.optString("generalSeatStatus"),
                rawText = row.optString("generalSeatText"),
                classNames = row.optString("generalSeatClass"),
            ),
            firstClassSeat = SeatParser.fromJs(
                jsStatus = row.optString("firstClassSeatStatus"),
                rawText = row.optString("firstClassSeatText"),
                classNames = row.optString("firstClassSeatClass"),
            ),
        )
    }

    /**
     * "18:32", "18:32 도착", "출발 18:32" 등에서 시각을 뽑는다.
     * 코레일도 24:00 이후 표기를 쓰지 않으므로 24시 이상은 무효로 본다.
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
