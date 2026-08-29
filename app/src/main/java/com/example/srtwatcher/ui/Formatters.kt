package com.example.srtwatcher.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 화면 표기용 포매터.
 *
 * 예전에는 역/날짜/시간 선택 다이얼로그(`PickerDialogs.kt`)에 함께 들어 있었다.
 * 조회 조건을 앱에서 입력받지 않게 되면서 다이얼로그는 전부 사라졌고,
 * 상태 패널이 쓰던 시각 표기만 남았다. 그 패널의 "마지막 확인" 칸까지 없앤 지금은
 * 쓰는 곳이 없다. 시각을 다시 보여줄 자리가 생기면 여기서 가져다 쓰면 된다.
 */

private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

/** 벽시계 시각(HH:mm:ss)을 보여줄 때. */
fun formatClock(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalTime()
        .format(CLOCK_FORMAT)
