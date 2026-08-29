package dev.yslee.catchtrain.parser

import dev.yslee.catchtrain.domain.Train
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * [SrtPageParser] 기본 구현. (DESIGN.md §15)
 *
 * WebView.evaluateJavascript 의 결과는 두 가지 형태로 올 수 있다.
 *   1. JS 가 객체를 반환 → 결과가 그대로 JSON 객체 문자열
 *   2. JS 가 JSON.stringify(...) 결과(문자열)를 반환 → 한 번 더 감싸인 JSON 문자열
 * 둘 다 처리한다.
 */
class SrtParser : SrtPageParser {

    override fun parse(rawJson: String?): PageSnapshot {
        val raw = rawJson?.trim()
        if (raw.isNullOrEmpty() || raw == "null" || raw == "undefined") {
            throw DomParseException("DOM 분석 결과가 비어 있습니다.")
        }

        val root = toJsonObject(raw)
        val warnings = mutableListOf<String>()

        val rows = root.optJSONArray("trains") ?: JSONArray()
        val trains = mutableListOf<Train>()
        // trains 와 rowRefs 는 항상 같은 순서/길이를 유지해야 한다. 함께 채운다.
        val rowRefs = mutableListOf<RowRef>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val train = TrainParser.parse(row, warnings) ?: continue
            trains += train
            rowRefs += RowRef(
                rowKey = row.optString("rowKey"),
                rowIndex = row.optInt("rowIndex", i),
                generalCellIndex = row.optInt("generalCellIndex", -1),
                firstClassCellIndex = row.optInt("firstClassCellIndex", -1),
            )
        }

        val jsWarnings = root.optJSONArray("warnings")
        if (jsWarnings != null) {
            for (i in 0 until jsWarnings.length()) {
                warnings += jsWarnings.optString(i)
            }
        }

        val declaredStatus = root.optString("status").ifBlank { PageStatus.UNKNOWN_PAGE.name }
        val status = runCatching { PageStatus.valueOf(declaredStatus) }
            .getOrElse { PageStatus.UNKNOWN_PAGE }

        // JS 가 테이블은 찾았지만 유효한 행이 하나도 없으면 NO_TRAIN 으로 강등한다.
        val effectiveStatus = if (status == PageStatus.TRAIN_LIST && trains.isEmpty()) {
            PageStatus.NO_TRAIN
        } else {
            status
        }

        return PageSnapshot(
            status = effectiveStatus,
            url = root.optString("url"),
            title = root.optString("title"),
            trains = trains,
            rowCount = root.optInt("rowCount", rows.length()),
            searchDate = root.optString("searchDate"),
            warnings = warnings.filter { it.isNotBlank() },
            rowRefs = rowRefs,
        )
    }

    private fun toJsonObject(raw: String): JSONObject {
        val value = try {
            JSONTokener(raw).nextValue()
        } catch (e: Exception) {
            throw DomParseException("DOM 분석 결과가 JSON 이 아닙니다.", e)
        }

        return when (value) {
            is JSONObject -> value
            is String -> try {
                JSONObject(value)
            } catch (e: Exception) {
                throw DomParseException("DOM 분석 결과 문자열을 JSON 으로 읽을 수 없습니다.", e)
            }
            else -> throw DomParseException("예상하지 못한 DOM 분석 결과 형식: ${value::class.java.simpleName}")
        }
    }
}
