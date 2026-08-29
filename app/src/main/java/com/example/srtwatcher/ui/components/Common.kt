package com.example.srtwatcher.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.srtwatcher.ui.theme.AppColors
import com.example.srtwatcher.ui.theme.LabelMuted
import com.example.srtwatcher.ui.theme.StatusColors
import com.example.srtwatcher.watcher.WatchState

/**
 * 앱 공통 패널 카드.
 *
 * 그림자(elevation) 대신 1dp 테두리로 면을 구분한다. 어두운 배경에서는
 * 그림자가 거의 보이지 않아 테두리 쪽이 훨씬 또렷하다.
 */
@Composable
fun PanelCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    borderColor: Color = MaterialTheme.colorScheme.outline,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, borderColor),
        content = content,
    )
}

/** 설정 화면 섹션 머리말. 왼쪽에 강조색 세로 바를 세워 구획을 만든다. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 12.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = LabelMuted,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 감시 상태 점. 이모지 신호등을 대신한다. (DESIGN.md §8)
 *
 * 감시가 도는 중에는 점 바깥에 반투명 링을 하나 더 그려서
 * "살아 있다"는 느낌을 준다.
 */
@Composable
fun StatusDot(state: WatchState, modifier: Modifier = Modifier) {
    val color = state.dotColor()
    val running = state.isRunning
    Box(
        modifier = modifier
            .size(14.dp)
            .drawBehind {
                if (running) {
                    drawCircle(color = color.copy(alpha = 0.20f), radius = size.minDimension / 2f)
                }
                drawCircle(color = color, radius = 4.dp.toPx())
            },
    )
}

/** 상태별 신호등 색. 도메인 enum 에 Compose 타입을 넣지 않으려고 UI 쪽 확장으로 둔다. */
@Composable
fun WatchState.dotColor(): Color = when (this) {
    WatchState.LOADING, WatchState.ANALYZING, WatchState.WAITING -> StatusColors.Running
    WatchState.MATCHED, WatchState.RESERVED -> MaterialTheme.colorScheme.primary
    WatchState.ERROR -> MaterialTheme.colorScheme.error
    WatchState.PAUSED -> StatusColors.Paused
    WatchState.IDLE, WatchState.STOPPED -> StatusColors.Idle
}

/** 1dp 구분선. [androidx.compose.material3.HorizontalDivider] 보다 흐리다. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/** 모노스페이스 로그 한 줄이 앉는 터미널 톤 배경. */
@Composable
fun terminalBackground(): Color = AppColors.Terminal

/** 카드 안에서 행과 행을 나누는 얇은 선. 좌우 여백을 줘서 카드 테두리와 붙지 않게 한다. */
@Composable
fun RowDivider(modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(horizontal = 14.dp)) {
        Hairline()
    }
}
