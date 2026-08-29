package com.example.srtwatcher.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 로그인 여부 확인 스크립트/파서 검증.
 *
 * JS 를 실제로 돌려보지는 못하므로, 조립 결과와 판정 규칙만 확인한다.
 */
class SrtLoginScriptTest {

    @Test
    fun `스크립트에 selector 가 주입되고 자리표시자가 남지 않는다`() {
        val script = SrtLoginScript.build()

        assertFalse("자리표시자가 남아 있다", script.contains("/*__CONFIG__*/"))
        assertTrue(script.contains(".login_wrap"))
        assertTrue(script.contains("selectLoginForm"))
        assertTrue(script.contains("로그아웃"))
        assertTrue(script.contains("logout"))
    }

    /**
     * 로그인 화면 본문에는 "…자동으로 로그아웃됩니다." 라는 안내가 있다. (2026-08-23 실측)
     * 그래서 문구 비교는 반드시 완전 일치여야 하고, 본문 전체를 훑으면 안 된다.
     */
    @Test
    fun `문구는 완전 일치로만 비교한다`() {
        val script = SrtLoginScript.build()

        assertTrue("완전 일치 비교가 없다", script.contains("value === squash(texts[i])"))
        assertFalse(
            "본문 전체 텍스트를 훑으면 안내 문장에 걸린다",
            script.contains("document.body.innerText"),
        )
    }

    @Test
    fun `로그아웃 링크만 있으면 로그인 상태다`() {
        val result = SrtLoginParser.parse("""{"state":"LOGGED_IN","detail":"로그아웃"}""")

        assertEquals(LoginState.LOGGED_IN, result.state)
        assertFalse(result.blocksWatch)
    }

    @Test
    fun `로그인 링크만 있으면 감시를 막는다`() {
        val result = SrtLoginParser.parse("""{"state":"LOGGED_OUT","detail":"로그인"}""")

        assertEquals(LoginState.LOGGED_OUT, result.state)
        assertTrue(result.blocksWatch)
    }

    /** 판정 실패는 전부 UNKNOWN 이고, UNKNOWN 은 감시를 막지 않는다. */
    @Test
    fun `읽지 못한 결과는 감시를 막지 않는다`() {
        listOf(null, "", "null", "undefined", "그냥 문자열", """{"state":"WHAT"}""", """{}""")
            .forEach { raw ->
                val result = SrtLoginParser.parse(raw)
                assertEquals("입력=$raw", LoginState.UNKNOWN, result.state)
                assertFalse("입력=$raw", result.blocksWatch)
            }
    }

    /** evaluateJavascript 는 결과를 한 번 더 문자열로 감싸 줄 때가 있다. */
    @Test
    fun `문자열로 한 번 더 감싸인 결과도 읽는다`() {
        val raw = "\"{\\\"state\\\":\\\"LOGGED_OUT\\\",\\\"detail\\\":\\\"로그인\\\"}\""

        assertEquals(LoginState.LOGGED_OUT, SrtLoginParser.parse(raw).state)
    }
}
