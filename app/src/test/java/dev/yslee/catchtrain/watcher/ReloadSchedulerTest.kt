package dev.yslee.catchtrain.watcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ReloadSchedulerTest {

    private val scheduler = ReloadScheduler(random = Random(42))

    @Test
    fun `간격은 지정한 범위 안에서 뽑힌다`() {
        val values = (1..500).map { scheduler.nextInterval(1_000L, 3_000L) }

        assertTrue("범위를 벗어난 값이 있다", values.all { it in 1_000L..3_000L })
    }

    @Test
    fun `간격은 매번 같지 않다`() {
        val values = (1..50).map { scheduler.nextInterval(1_000L, 3_000L) }

        // 500ms 단위가 아니라 밀리초 단위로 흩어져야 한다.
        assertTrue("값이 충분히 랜덤하지 않다", values.distinct().size > 40)
        assertTrue("밀리초 단위로 흩어져야 한다", values.any { it % 500L != 0L })
    }

    @Test
    fun `범위가 하한 밖이면 하한으로 보정한다`() {
        assertEquals(
            ReloadScheduler.MIN_INTERVAL_MS,
            scheduler.nextInterval(-2_000L, -500L),
        )
    }

    @Test
    fun `범위가 상한 밖이면 상한으로 보정한다`() {
        assertEquals(
            ReloadScheduler.MAX_INTERVAL_MS,
            scheduler.nextInterval(10_000L, 30_000L),
        )
    }

    @Test
    fun `하한 0 이면 대기 없는 간격도 허용된다`() {
        assertEquals(0L, scheduler.nextInterval(0L, 0L))

        val values = (1..200).map { scheduler.nextInterval(0L, 200L) }
        assertTrue("범위를 벗어난 값이 있다", values.all { it in 0L..200L })
        assertTrue("하한 쪽 값도 뽑혀야 한다", values.any { it < 50L })
    }

    @Test
    fun `최소값과 최대값이 뒤집혀도 동작한다`() {
        val values = (1..100).map { scheduler.nextInterval(3_000L, 2_000L) }

        assertTrue(values.all { it in 2_000L..3_000L })
    }

    @Test
    fun `최소와 최대가 같으면 고정 간격이 된다`() {
        assertEquals(3_000L, scheduler.nextInterval(3_000L, 3_000L))
    }

    @Test
    fun `표시 문자열`() {
        assertEquals("1.0~3.0초", ReloadScheduler.formatRange(1_000L, 3_000L))
        assertEquals("2.0초", ReloadScheduler.formatRange(2_000L, 2_000L))
    }
}
