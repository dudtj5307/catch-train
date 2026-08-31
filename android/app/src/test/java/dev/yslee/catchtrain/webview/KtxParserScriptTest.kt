package dev.yslee.catchtrain.webview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 스크립트 조립 검증. (DESIGN.md §13, §38)
 *
 * JS 자체를 실행해 보지는 못하지만, 자리표시자가 남아 있거나 selector 주입이 빠지면
 * WebView 안에서 조용히 실패하고 로그에도 이유가 남지 않는다.
 * 그래서 조립 결과만이라도 확인해 둔다.
 *
 * 여기서 보는 것은 "문자열이 들어갔는가" 를 넘어, **실측으로 정한 규칙이 스크립트에
 * 실제로 박혀 있는가** 다. (완전일치 비교 / class 기반 판정 / 허용목록)
 */
class KtxParserScriptTest {

    private val placeholders =
        listOf("/*__CONFIG__*/", "/*__VIEW__*/", "/*__TAPPOINT__*/", "/*__SIGNATURE__*/")

    private val target = ReserveTarget(
        rowKey = "2:1a2b3c",
        rowIndex = 3,
        cellIndex = 0,
        trainNumber = "305",
        departureTime = "07:11",
        seatLabel = "일반실",
    )

    private fun assertAssembled(script: String) {
        placeholders.forEach { placeholder ->
            assertFalse("자리표시자가 남아 있다: $placeholder", script.contains(placeholder))
        }
    }

    @Test
    fun `모든 스크립트가 자리표시자 없이 조립된다`() {
        listOf(
            KtxParserScript.build(),
            KtxParserScript.buildSelectScript(1080, 1920, target),
            KtxParserScript.buildSelectConfirmScript(target),
            KtxParserScript.buildReserveScript(1080, 1920, target),
            KtxParserScript.buildReserveResultScript(),
            KtxParserScript.buildNoticePopupScript(1080, 1920),
            KtxParserScript.buildObserverScript(),
            KtxParserScript.buildProbeScript(),
            KtxParserScript.buildPageKindScript(),
            KtxParserScript.buildScrollTopScript(),
        ).forEach { assertAssembled(it) }
    }

    /**
     * 새로고침이 직전 스크롤 위치를 되살리면 목록이 그려지기 전의 짧은 문서 끝에 붙어
     * 화면이 맨 밑으로 튄다. 되살리기를 끄는 것이 이 스크립트의 핵심이다. (§38-9)
     */
    @Test
    fun `스크롤 스크립트는 되살리기를 끄고 맨 위로 올린다`() {
        val script = KtxParserScript.buildScrollTopScript()

        assertTrue(script.contains("history.scrollRestoration = 'manual'"))
        assertTrue(script.contains("window.scrollTo(0, 0)"))
        // 읽기와 스크롤뿐이다. 사이트 코드를 부르거나 요청을 내지 않는다.
        listOf("click(", "submit(", "fetch(", "location.href =").forEach { forbidden ->
            assertFalse("스크롤 스크립트가 페이지를 건드린다: $forbidden", script.contains(forbidden))
        }
    }

    /**
     * 좌석 상태는 class 로만 읽는다. 텍스트로 읽으면 `특실(매진임박)` 을 매진으로 오판한다.
     * (§38-2)
     */
    @Test
    fun `목록 분석 스크립트는 좌석 class 를 그대로 들고 있다`() {
        val script = KtxParserScript.build()

        assertTrue(script.contains("li.tckList"))
        assertTrue(script.contains("price_box"))
        listOf("gen", "spe", "sold_out_soon", "sold_out_wait", "wait", "active").forEach {
            assertTrue("class 토큰이 빠졌다: $it", script.contains("\"$it\""))
        }
        // 매진임박은 예약 가능이다. 판정 순서가 뒤집히면 살 수 있는 칸을 버린다.
        assertTrue(script.contains("ktxHas(tokens, KSEL.cls.soldOutSoon)) return 'AVAILABLE'"))
    }

    /**
     * 코레일은 AJAX 라 조회해도 URL 이 바뀌지 않는다. URL 로 화면 종류를 판정하면
     * 언제나 틀린 답을 얻는다. (§38-5)
     */
    @Test
    fun `페이지 종류는 URL 이 아니라 DOM 마커로 판정한다`() {
        val script = KtxParserScript.build()

        assertTrue(script.contains("hasListMarker"))
        assertFalse(
            "조회 결과 URL 이 판정에 쓰이고 있다",
            script.contains(KtxSelectors.SCHEDULE_URL),
        )
    }

    /**
     * 갱신은 새로고침(F5)이다. **어떤 스크립트도 조회 버튼을 찾지 않는다.** (§38-9)
     *
     * 모바일 폭에서 `열차조회` 는 `display:none` 인 조상 아래에 있어 rect 가 0×0 이고,
     * 그 자리에 실제로 보이는 것은 `다음날 (…) 조회` 뿐이다. 어느 쪽이든 스크립트가
     * 다시 찾기 시작하면 **사용자가 보던 날짜가 아닌 다음날을 조회하는** 사고로 이어진다.
     * 버튼 탐색이 슬그머니 되살아나는 것을 여기서 막는다.
     */
    @Test
    fun `어떤 스크립트도 조회 버튼을 찾지 않는다`() {
        val scripts = listOf(
            KtxParserScript.build(),
            KtxParserScript.buildSelectScript(1080, 1920, target),
            KtxParserScript.buildSelectConfirmScript(target),
            KtxParserScript.buildReserveScript(1080, 1920, target),
            KtxParserScript.buildReserveResultScript(),
            KtxParserScript.buildNoticePopupScript(1080, 1920),
            KtxParserScript.buildObserverScript(),
            KtxParserScript.buildProbeScript(),
            KtxParserScript.buildPageKindScript(),
        )

        scripts.forEach { script ->
            listOf("열차조회", "다음날", "btn_lookup", "btn_box").forEach { forbidden ->
                assertFalse("조회 버튼 탐색이 되살아났다: $forbidden", script.contains(forbidden))
            }
        }
    }

    /** 매진·예약대기 칸은 누르지 않는다. (§18, §38-8) */
    @Test
    fun `1단계는 예약 가능한 칸만 누른다`() {
        val script = KtxParserScript.buildSelectScript(1080, 1920, target)

        assertTrue(script.contains(target.rowKey))
        assertTrue(script.contains("SEAT_NOT_AVAILABLE"))
        assertTrue(script.contains("status !== 'AVAILABLE'"))
        // 주키는 열차 번호다. 같은 시각에 다른 편성이 있다. (§38-4)
        assertTrue(script.contains("ROW_MISMATCH"))
        assertTrue(script.contains("305"))
    }

    /**
     * 2단계는 확인 세 가지를 모두 통과해야 좌표를 돌려준다. (§38-6-1)
     * 허용목록에 없는 버튼(`예약대기신청` / `입석+좌석 예매`)은 누르지 않는다.
     */
    @Test
    fun `2단계는 확인을 통과한 예매 버튼만 누른다`() {
        val script = KtxParserScript.buildReserveScript(1080, 1920, target)

        // 확인 1·2·3
        assertTrue(script.contains("NOT_SELECTED"))
        assertTrue(script.contains("LABEL_MISMATCH"))
        assertTrue(script.contains("NOT_ALLOWED"))
        assertTrue("허용 문구 완전일치가 없다", script.contains("label === ktxNorm(CFG.texts[i])"))
        assertTrue(script.contains("예약대기신청"))
        assertTrue(script.contains("reservbtn"))
        // 1단계와 같은 편성/칸을 봐야 한다.
        assertTrue(script.contains(target.rowKey))
    }

    /**
     * 1단계로 칸을 고르면 `active` 가 붙는다. 그 class 가 요약값에 들어가면
     * 2단계에서 같은 편성을 찾지 못한다.
     */
    @Test
    fun `편성 요약값은 선택 표시를 빼고 계산한다`() {
        val script = KtxParserScript.buildSelectScript(1080, 1920, target)

        assertTrue(script.contains("if (tokens[t] !== KSEL.cls.active) kept.push(tokens[t]);"))
    }

    @Test
    fun `예약 결과 판별은 본문 문구와 목록 유무를 함께 본다`() {
        val script = KtxParserScript.buildReserveResultScript()

        assertTrue(script.contains("잔여석없음"))
        assertTrue(script.contains("marker !== '' && rows === 0"))
        // 본문은 innerText 로만 읽는다. <script> 안의 안내 문구에 걸리면 안 된다.
        assertTrue(script.contains("document.body.innerText"))
    }

    /** AJAX 라 화면 전환이 없다. 서명 대상이 목록이 아니면 갱신을 영영 못 알아챈다. (§38-5) */
    @Test
    fun `서명 대상은 목록 영역이다`() {
        val script = KtxParserScript.buildObserverScript()

        assertTrue(script.contains("div.tckWrap"))
        assertTrue(script.contains("state.sig = ktxSignature()"))
    }

    /**
     * [예매] 뒤에 끼어드는 안내 창은 **제목과 버튼 문구가 완전일치할 때만** 누른다.
     * (§38-6-2)
     *
     * 넓게 잡으면 차단 안내나 예약실패 안내의 [확인] 까지 누르게 되는데, 그 [확인] 은
     * 조회 폼을 새로 여는 링크라 **사용자가 넣어 둔 조회 조건을 통째로 날린다** (대원칙 5).
     */
    @Test
    fun `안내 창은 제목과 버튼 문구가 완전일치할 때만 누른다`() {
        val script = KtxParserScript.buildNoticePopupScript(1080, 1920)

        assertTrue(script.contains("ReactModal__Overlay"))
        assertTrue(script.contains("이용안내"))
        assertTrue(script.contains("확인"))
        // 부분일치로 바뀌면 아는 창이 아닌 것까지 누르게 된다.
        assertTrue("제목 완전일치가 없다", script.contains("title === ktxNorm(CFG.titles[t])"))
        assertTrue("버튼 완전일치가 없다", script.contains("label === ktxNorm(CFG.texts[y])"))
        listOf("TITLE_MISMATCH", "NOT_ALLOWED", "BUTTON_AMBIGUOUS").forEach {
            assertTrue("판정 이유가 빠졌다: $it", script.contains(it))
        }
    }

    /** 차단·예약실패 안내의 [확인] 은 조회 조건을 날린다. 그런 창은 아예 손대지 않는다. */
    @Test
    fun `안내 창 스크립트는 차단과 예약실패 문구를 만나면 손을 뗀다`() {
        val script = KtxParserScript.buildNoticePopupScript(1080, 1920)

        assertTrue(script.contains("REFUSED"))
        assertTrue(script.contains("잔여석없음"))
        assertTrue(script.contains("매크로"))
    }

    /** 탐색 스크립트는 좌표만 돌려준다. 클릭은 Kotlin 쪽 진짜 터치가 한다. (대원칙 1) */
    @Test
    fun `안내 창 스크립트는 아무것도 클릭하지 않는다`() {
        val script = KtxParserScript.buildNoticePopupScript(1080, 1920)

        listOf(".click(", "dispatchEvent", "submit(", "location.href =").forEach { forbidden ->
            assertFalse("스크립트가 페이지를 건드린다: $forbidden", script.contains(forbidden))
        }
    }
}
