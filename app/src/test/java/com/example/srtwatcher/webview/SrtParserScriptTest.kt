package com.example.srtwatcher.webview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 스크립트 조립 검증. (DESIGN.md §13)
 *
 * JS 자체를 실행해 보지는 못하지만, 자리표시자가 남아 있거나 selector 주입이
 * 빠지면 WebView 안에서 조용히 실패하고 로그에도 이유가 남지 않는다.
 * 그래서 조립 결과만이라도 확인해 둔다.
 */
class SrtParserScriptTest {

    private val placeholders = listOf("/*__CONFIG__*/", "/*__VIEW__*/", "/*__TAPPOINT__*/", "/*__SIGNATURE__*/")

    private fun assertAssembled(script: String) {
        placeholders.forEach { placeholder ->
            assertFalse("자리표시자가 남아 있다: $placeholder", script.contains(placeholder))
        }
    }

    @Test
    fun `예약 결과 판별 스크립트에 실패 문구와 URL 힌트가 들어간다`() {
        val script = SrtParserScript.buildReserveResultScript()

        assertAssembled(script)
        assertTrue(script.contains("잔여석없음"))
        assertTrue(script.contains("confirmReservationInfo"))
        // 본문은 innerText 로만 읽는다. <script> 안의 안내 문구에 걸리면 안 된다.
        assertTrue(script.contains("document.body.innerText"))
    }

    @Test
    fun `재조회 버튼 탐색 스크립트는 그대로 조립된다`() {
        val script = SrtParserScript.buildLocateScript(1080, 1920)

        assertAssembled(script)
        assertTrue(script.contains("조회하기"))
        assertTrue(script.contains("inquery_btn"))
    }
}
