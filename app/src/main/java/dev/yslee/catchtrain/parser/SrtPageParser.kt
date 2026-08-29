package dev.yslee.catchtrain.parser

/**
 * DOM 분석 결과(JSON 문자열) → 도메인 모델 변환 담당. (DESIGN.md §15)
 *
 * UI 는 DOM selector 를 알 필요가 없다.
 */
interface SrtPageParser {

    /**
     * @param rawJson WebView.evaluateJavascript 가 돌려준 원본 문자열.
     *                (JS 가 객체를 반환한 경우와 문자열을 반환한 경우 모두 처리한다)
     * @throws DomParseException 문자열이 예상한 구조가 아닐 때
     */
    fun parse(rawJson: String?): PageSnapshot
}

class DomParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
