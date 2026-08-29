package com.example.srtwatcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.srtwatcher.domain.SeatClass
import com.example.srtwatcher.domain.SeatMatch
import com.example.srtwatcher.domain.SeatStatus
import com.example.srtwatcher.domain.Train
import com.example.srtwatcher.domain.TrainKey
import com.example.srtwatcher.domain.WatchSelection
import com.example.srtwatcher.ui.components.Hairline
import com.example.srtwatcher.ui.components.PanelCard
import com.example.srtwatcher.ui.theme.AppColors
import com.example.srtwatcher.ui.theme.LabelMuted
import com.example.srtwatcher.ui.theme.MonoDense
import com.example.srtwatcher.ui.theme.MonoValue
import com.example.srtwatcher.watcher.ReserveAttempt
import com.example.srtwatcher.watcher.ReserveResult
import com.example.srtwatcher.watcher.WatchLogEntry
import com.example.srtwatcher.watcher.WatchState
import com.example.srtwatcher.watcher.WatchStatus
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * 감시 대상 요약. (DESIGN.md §21 상단부)
 *
 * 예전의 "조건 요약"(구간/날짜/시간/좌석등급) 자리를 대신한다.
 * 이제 감시 대상은 사용자가 [열차 선택] 목록에서 체크한 칸이므로,
 * 그 칸들을 그대로 보여주는 것이 조건을 보여주는 것이다.
 *
 * [searchSummary] 는 그 칸들이 어느 조회 결과에서 나온 것인지("수서 → 부산 · 2026.08.24(월)")
 * 를 되비친다. 앱이 가진 조건이 아니라 **화면에서 읽어낸 값**이라, 사용자가 사이트에서
 * 구간이나 날짜를 바꿔 다시 조회하면 함께 바뀐다. 읽어내지 못했으면 null 이고 줄이 사라진다.
 *
 * 감시 간격은 여기에 두지 않는다. 헤더 한 줄이 항상 보여 주고 있어 중복이다.
 */
@Composable
fun SelectionSummaryCard(
    selection: WatchSelection,
    searchSummary: String?,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PanelCard(modifier = modifier) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "감시할 좌석",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "  ${selection.size}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (searchSummary != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = searchSummary,
                        style = MonoDense,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))

                if (selection.isEmpty) {
                    Text(
                        text = "아직 고른 좌석이 없습니다.\n" +
                            "아래 SRT 화면에서 조회한 뒤 [열차 선택] 에서 고르세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    selectionLines(selection).take(MAX_SHOWN_SELECTIONS).forEach { line ->
                        Text(
                            text = line,
                            style = MonoValue,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (selection.size > MAX_SHOWN_SELECTIONS) {
                        Text(
                            text = "외 ${selection.size - MAX_SHOWN_SELECTIONS}칸",
                            style = MonoDense,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            TextButton(
                onClick = onEdit,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text("선택 ›", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * "수서 → 부산 · 2026.08.24(월)" 한 줄. 아무것도 모르면 null 이라 줄 자체가 사라진다.
 *
 * 구간은 조회 결과 표의 행에서(= [trains]), 날짜는 조회 폼에서 읽은 값이다.
 * 둘 다 화면을 읽어 얻은 것이라 페이지가 바뀌면 다음 분석에서 함께 갱신된다.
 * 한쪽만 읽혔으면 읽힌 쪽만 보여 준다.
 */
fun searchSummaryOf(trains: List<Train>, searchDate: String): String? {
    val route = trains.firstOrNull()
        ?.takeIf { it.departureStation.isNotBlank() && it.arrivalStation.isNotBlank() }
        ?.let { "${it.departureStation} → ${it.arrivalStation}" }
    val date = formatSearchDate(searchDate)
    return when {
        route != null && date != null -> "$route  ·  $date"
        else -> route ?: date
    }
}

/** "2026-08-24" → "2026.08.24(월)". 읽을 수 없는 값이면 null. */
private fun formatSearchDate(raw: String): String? {
    val date = runCatching { LocalDate.parse(raw) }.getOrNull() ?: return null
    val day = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)
    return "%04d.%02d.%02d(%s)".format(date.year, date.monthValue, date.dayOfMonth, day)
}

/** "18:30 SRT 339  특실" 형태. 출발이 빠른 순으로 정렬한다. */
private fun selectionLines(selection: WatchSelection): List<String> =
    selection.seats
        .sortedWith(
            compareBy({ it.trainKey.departureTime }, { it.seatClass.ordinal }),
        )
        .map { "${it.trainKey.label()}  ${it.seatClass.label}" }

/**
 * 감시 상태 표시. (DESIGN.md §8, §21)
 *
 * 상태 이름과 감시 간격은 헤더 한 줄이 이미 보여 주므로 여기서 되풀이하지 않는다.
 * "마지막 확인 / 다음 확인 / 선택한 좌석 / 열린 좌석" 숫자 칸도 없앴다.
 * 남는 것은 헤더에 담기지 않는 것 — 오류 사유와 안내 문구뿐이라,
 * 할 말이 없으면 카드 자체를 띄우지 않는다.
 */
@Composable
fun StatusCard(
    status: WatchStatus,
    modifier: Modifier = Modifier,
) {
    val failed = status.state == WatchState.ERROR
    val matched = status.state == WatchState.MATCHED
    val error = status.error
    // 좌석을 찾은 상황은 아래 [MatchedCard] 가 훨씬 크게 알려 준다.
    val note = if (error == null && !matched) status.message?.takeIf { it.isNotBlank() } else null
    if (error == null && note == null) return

    PanelCard(
        modifier = modifier,
        containerColor = if (failed) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        borderColor = if (failed) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.outline
        },
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            if (error != null) {
                Text(
                    text = error.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = status.message ?: error.guide,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    text = note.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * ★ 열차 선택. 이 앱의 중심 화면이다.
 *
 * 사용자가 SRT 사이트에서 원하는 조건으로 조회를 마치면, [갱신] 로 그 결과를
 * 그대로 읽어와 여기에 펼친다. 체크한 칸(= 그 열차의 그 좌석 등급)만 감시하고,
 * 열리면 **그 칸의 [예약하기] 버튼만** 누른다.
 *
 * 좌석 열의 순서는 사이트와 똑같이 **특실이 왼쪽, 일반실이 오른쪽**이다.
 * 화면과 앱의 좌우가 다르면 잘못된 칸을 고르기 쉽다.
 *
 * 매진인 칸도 체크할 수 있다. 지금 매진인 좌석이 풀리기를 기다리는 것이
 * 이 앱의 목적이기 때문이다. 반대로 아예 없는 칸("-")은 체크할 수 없다.
 */
@Composable
fun TrainSelectPanel(
    trains: List<Train>,
    selection: WatchSelection,
    scanning: Boolean,
    onRefresh: () -> Unit,
    onToggle: (TrainKey, SeatClass) -> Unit,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PanelCard(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "열차 선택",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "선택 ${selection.size} / 조회 ${trains.size}",
                    style = MonoDense,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                if (selection.size > 0) {
                    TextButton(
                        onClick = onClearSelection,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Text("모두 해제", style = MaterialTheme.typography.labelMedium)
                    }
                }
                TextButton(
                    onClick = onRefresh,
                    enabled = !scanning,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text(
                        text = if (scanning) "갱신 중…" else "갱신",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // 열 머리말. 순서는 사이트와 같다. (특실 → 일반실)
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "출발 / 열차",
                    style = LabelMuted,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                SeatColumnLabel(SeatClass.FIRST_CLASS.label)
                SeatColumnLabel(SeatClass.GENERAL.label)
            }
            Hairline()

            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (trains.isEmpty()) {
                    Text(
                        text = "읽어온 열차가 없습니다.\n" +
                            "아래 SRT 화면에서 원하는 조건으로 조회한 뒤 [갱신] 를 누르세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                trains.forEach { train ->
                    TrainSelectRow(
                        train = train,
                        selection = selection,
                        onToggle = onToggle,
                    )
                }
            }

            if (trains.isNotEmpty()) {
                Hairline()
                Text(
                    text = "체크한 칸의 [예약하기] 버튼만 자동으로 누릅니다. " +
                        "예약대기는 성격이 다른 신청이라 누르지 않습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SeatColumnLabel(text: String) {
    Text(
        text = text,
        style = LabelMuted,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(SEAT_CELL_WIDTH),
    )
}

@Composable
private fun TrainSelectRow(
    train: Train,
    selection: WatchSelection,
    onToggle: (TrainKey, SeatClass) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = ROW_VERTICAL_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${train.departureTime} → ${train.arrivalTime}",
                style = MonoValue.copy(lineHeight = 20.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = train.trainNumber.ifBlank { "열차번호 미상" },
                style = MonoDense.copy(lineHeight = 15.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 사이트와 같은 순서: 특실이 왼쪽, 일반실이 오른쪽.
        SeatCheckCell(
            status = train.firstClassSeat,
            checked = selection.contains(train, SeatClass.FIRST_CLASS),
            onToggle = { onToggle(train.key, SeatClass.FIRST_CLASS) },
        )
        SeatCheckCell(
            status = train.generalSeat,
            checked = selection.contains(train, SeatClass.GENERAL),
            onToggle = { onToggle(train.key, SeatClass.GENERAL) },
        )
    }
}

/**
 * 좌석 한 칸. 체크박스 하나뿐이다.
 *
 * 예전에는 체크박스 아래에 "예약가능 / 매진" 을 함께 적었지만, 지금 상태는
 * 바로 아래 SRT 화면이 이미 보여 주고 있고 줄 높이만 두 배로 먹어서 뺐다.
 *
 * 상태가 [SeatStatus.UNKNOWN] 이면 그 열차에 그 등급이 없거나 읽지 못한 것이므로
 * 체크할 수 없다. 잘못 체크해 두면 영영 열리지 않는 칸을 기다리게 된다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeatCheckCell(
    status: SeatStatus,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val enabled = status != SeatStatus.UNKNOWN
    Column(
        modifier = Modifier
            .width(SEAT_CELL_WIDTH)
            .clickable(enabled = enabled, onClick = onToggle),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CompositionLocalProvider(
            LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle() },
                enabled = enabled,
            )
        }
    }
}

/**
 * 선택한 좌석이 열렸을 때 + 후속 동작. (DESIGN.md §19, §34-5)
 *
 * [reserve] 는 이번에 시도한 자동 [예약하기] 결과다. 성공하면 화면이 예약 단계로
 * 넘어가 [ReservedCard] 가 대신 뜨므로, 여기서는 실패했을 때만 사유를 알려준다.
 */
@Composable
fun MatchedCard(
    matches: List<SeatMatch>,
    reserve: ReserveAttempt?,
    onContinue: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PanelCard(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        borderColor = MaterialTheme.colorScheme.primary,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "선택한 좌석 ${matches.size}건이 열렸습니다",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            matches.take(MAX_SHOWN_MATCHES).forEach { match ->
                Text(
                    text = "${match.train.summary()}  [${match.seatClass.label} " +
                        "${match.train.seatStatusOf(match.seatClass).label}]",
                    style = MonoDense,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (matches.size > MAX_SHOWN_MATCHES) {
                Text(
                    text = "외 ${matches.size - MAX_SHOWN_MATCHES}건",
                    style = MonoDense,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "예약은 아래 SRT 화면에서 직접 진행하세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            if (reserve != null && !reserve.result.succeeded &&
                reserve.result != ReserveResult.SKIPPED
            ) {
                Text(
                    text = if (reserve.result == ReserveResult.SOLD_OUT) {
                        // 누르기는 눌렀다. 그사이 남이 먼저 잡았을 뿐이라 오류가 아니다.
                        "[예약하기] 를 눌렀지만 잔여석이 없었습니다 — 목록으로 돌아가 계속 감시합니다"
                    } else {
                        "[예약하기] 자동 클릭 실패 — ${reserve.result.label}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onContinue,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.small,
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                ) {
                    Text("계속 감시")
                }
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text("감시 종료")
                }
            }
        }
    }
}

/**
 * 자동 [예약하기] 까지 눌러서 예약 화면으로 넘어간 상태. (DESIGN.md §34-5)
 *
 * 여기서부터 SRT 페이지에 대고 앱이 하는 일은 없다. 좌석 선택과 결제는 사용자가
 * 직접 한다. 결제 제한 시간이 있어 서두르라는 안내를 가장 크게 둔다.
 *
 * 다만 사용자가 이 화면을 못 보고 있을 수 있으므로, 10초마다 소리와 진동으로
 * 재촉하는 알림이 따로 나간다. 좌석이 풀리는 10분까지만이다.
 * ([alerting], DESIGN.md §19-3)
 */
@Composable
fun ReservedCard(
    reserve: ReserveAttempt,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    alerting: Boolean = false,
    onSilence: () -> Unit = {},
) {
    PanelCard(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        borderColor = MaterialTheme.colorScheme.primary,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "🎫 [예약하기] 를 눌렀습니다",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = reserve.match.train.summary(),
                style = MonoDense,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = "좌석 등급: ${reserve.match.seatClass.label}",
                style = MonoDense,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "아래 SRT 화면에서 좌석 선택과 결제를 직접 진행하세요. " +
                    "결제 제한 시간이 있으니 서두르세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (alerting) {
                Text(
                    text = "🔔 10초마다 알림이 울립니다. 10분 안에 결제하지 않으면 좌석이 풀립니다.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (alerting) {
                    // 알림만 끄고 예약 화면은 그대로 둔다. 결제는 아직 안 끝났다.
                    Button(
                        onClick = onSilence,
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.small,
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                    ) {
                        Text("🔕 알림 끄기")
                    }
                }
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text("감시 종료")
                }
            }
        }
    }
}

/**
 * 개발용 로그 패널. (DESIGN.md §29)
 *
 * 목록 자체는 화면에서 가장 어두운 면에 얹어 터미널처럼 보이게 한다.
 */
@Composable
fun LogPanel(
    logs: List<WatchLogEntry>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PanelCard(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "로그",
                    style = LabelMuted,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${logs.size}",
                    style = MonoDense,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onClear,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Text("지우기", style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.height(4.dp))
            if (logs.isEmpty()) {
                Text(
                    text = "감시를 시작하면 로그가 표시됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .heightIn(max = 220.dp)
                        .fillMaxWidth()
                        .background(AppColors.Terminal, MaterialTheme.shapes.extraSmall)
                        .padding(vertical = 6.dp),
                    reverseLayout = true,
                ) {
                    items(logs.asReversed()) { entry ->
                        Text(
                            text = entry.format(),
                            style = MonoDense,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

private const val MAX_SHOWN_MATCHES = 5

/** 요약 카드에 몇 칸까지 펼쳐 보여줄지. */
private const val MAX_SHOWN_SELECTIONS = 4

/** 좌석 체크 칸 하나의 너비. 머리말과 본문이 같은 값을 써야 세로줄이 맞는다. */
private val SEAT_CELL_WIDTH = 78.dp

/** 열차 한 줄의 위아래 여백. 목록이 길어 한 화면에 최대한 많이 담는다. */
private val ROW_VERTICAL_PADDING = 1.dp
