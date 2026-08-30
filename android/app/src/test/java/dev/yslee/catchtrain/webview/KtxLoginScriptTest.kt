package dev.yslee.catchtrain.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 로그인 여부 확인 스크립트/파서 검증. (§27-1, §38-7)
 *
 * JS 를 실제로 돌려보지는 못하므로, 조립 결과와 판정 규칙만 확인한다.
 */
class KtxLoginScriptTest {

    @Test
    fun `스크립트에 selector 가 주입되고 자리표시자가 남지 않는다`() {
        val script = KtxLoginScript.build()

        assertFalse("자리표시자가 남아 있다", script.contains("/*__CONFIG__*/"))
        assertTrue(script.contains("ul.h_top_right"))
        assertTrue(script.contains("btnGoLogin"))
        assertTrue(script.contains("btnGoLogout"))
        assertTrue(script.contains("로그아웃"))
    }

    /**
     * 모바일 메뉴의 `button.logoutBtn` 은 **클래스 이름이 고정이고 문구만 바뀐다.**
     * 비로그인 상태에서도 그 버튼이 있으므로, 클래스 이름으로 판정하면 항상 로그인으로 읽는다.
     * (2026-08-29 실측, §38-7)
     */
    @Test
    fun `클래스 이름이 거짓말하는 요소는 쓰지 않는다`() {
        val script = KtxLoginScript.build()

        assertFalse("logoutBtn 으로 판정하면 항상 로그인으로 읽는다", script.contains("logoutBtn"))
        assertFalse("loginY 는 두 상태 모두에 있다", script.contains("loginY"))
    }

    /**
     * **보이는 요소만 세면 안 된다.** (2026-08-29 `/ticket/search/list` 실측, §38-7)
     *
     * 폰 폭에서는 `ul.h_top_right` 와 `div.header_top` 이 `display:none` 이라
     * `a.btnGoLogin` 의 rect 가 0×0 이고 `offsetParent` 도 없다. 가시성으로 거르면
     * 링크도 문구도 하나도 안 잡혀 **언제나 UNKNOWN** 이 되고, 앱의 WebView 는 항상
     * 폰 폭이므로 로그인 확인이 통째로 죽는다. [열차조회] 버튼과 같은 함정이다 (§38-9).
     */
    @Test
    fun `가시성으로 후보를 거르지 않는다`() {
        val script = KtxLoginScript.build()

        assertFalse(
            "가시성으로 거르면 폰 폭에서 로그인 표시를 하나도 찾지 못한다",
            script.contains("if (isShown(nodes[k])) return") ||
                script.contains("if (!isShown(nodes[j])) continue"),
        )
        // 숨어 있었다는 사실은 판정이 아니라 로그(detail)에만 남는다.
        assertTrue(script.contains("(숨김)"))
    }

    /** 링크가 빗나갔을 때 볼 모바일 전체메뉴 영역이 문구 판정 범위에 들어 있어야 한다. */
    @Test
    fun `문구 판정은 모바일 전체메뉴까지 본다`() {
        val script = KtxLoginScript.build()

        assertTrue(script.contains("menuScopes"))
        assertTrue(script.contains("bottom_menu_choose"))
    }

    /**
     * 로그인 화면 본문에는 "…자동으로 로그아웃됩니다." 같은 안내가 있다.
     * 그래서 문구 비교는 반드시 완전 일치여야 하고, 본문 전체를 훑으면 안 된다.
     */
    @Test
    fun `문구는 완전 일치로만 비교한다`() {
        val script = KtxLoginScript.build()

        assertTrue("완전 일치 비교가 없다", script.contains("value === squash(texts[i])"))
        assertFalse(
            "본문 전체 텍스트를 훑으면 안내 문장에 걸린다",
            script.contains("document.body.innerText"),
        )
    }

    @Test
    fun `로그아웃 링크만 있으면 로그인 상태다`() {
        val result = KtxLoginParser.parse("""{"state":"LOGGED_IN","detail":"로그아웃"}""")

        assertEquals(LoginState.LOGGED_IN, result.state)
        assertFalse(result.blocksWatch)
    }

    @Test
    fun `로그인 링크만 있으면 감시를 막는다`() {
        val result = KtxLoginParser.parse("""{"state":"LOGGED_OUT","detail":"로그인"}""")

        assertEquals(LoginState.LOGGED_OUT, result.state)
        assertTrue(result.blocksWatch)
    }

    /** 판정 실패는 전부 UNKNOWN 이고, UNKNOWN 은 감시를 막지 않는다. (대원칙 6) */
    @Test
    fun `읽지 못한 결과는 감시를 막지 않는다`() {
        listOf(null, "", "null", "undefined", "그냥 문자열", """{"state":"WHAT"}""", """{}""")
            .forEach { raw ->
                val result = KtxLoginParser.parse(raw)
                assertEquals("입력=$raw", LoginState.UNKNOWN, result.state)
                assertFalse("입력=$raw", result.blocksWatch)
            }
    }

    /**
     * 메인 화면 판정. **여기가 참일 때만** 로그인 화면으로 보낸다. (§27-2)
     *
     * 조회 결과 화면(`/ticket/search/list`)이 여기에 걸리면 사용자가 넣어 둔 조회 조건이
     * 통째로 날아간다 (대원칙 4·5). 그래서 경로 끝으로만 본다.
     */
    @Test
    fun `메인 화면 URL 만 참이다`() {
        listOf(
            KtxSelectors.START_URL,
            "https://www.korail.com/ticket/main/",
            "https://www.korail.com/ticket/main?tab=1",
            "https://www.korail.com/ticket/main#none",
        ).forEach { assertTrue("메인이어야 한다: $it", KtxSelectors.isMainPage(it)) }

        listOf(
            null,
            "",
            KtxSelectors.SCHEDULE_URL,
            KtxSelectors.LOGIN_URL,
            "https://www.korail.com/ticket/main/detail",
            "https://example.com/ticket/main",
        ).forEach { assertFalse("메인이 아니어야 한다: $it", KtxSelectors.isMainPage(it)) }
    }

    /** evaluateJavascript 는 결과를 한 번 더 문자열로 감싸 줄 때가 있다. */
    @Test
    fun `문자열로 한 번 더 감싸인 결과도 읽는다`() {
        val raw = "\"{\\\"state\\\":\\\"LOGGED_OUT\\\",\\\"detail\\\":\\\"로그인\\\"}\""

        assertEquals(LoginState.LOGGED_OUT, KtxLoginParser.parse(raw).state)
    }
}
