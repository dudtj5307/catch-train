package dev.yslee.catchtrain.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yslee.catchtrain.domain.SeatClass
import dev.yslee.catchtrain.domain.SeatMatch
import dev.yslee.catchtrain.domain.SeatStatus
import dev.yslee.catchtrain.domain.Train
import dev.yslee.catchtrain.domain.TrainKey
import dev.yslee.catchtrain.domain.WatchSelection
import dev.yslee.catchtrain.ui.components.Hairline
import dev.yslee.catchtrain.ui.components.PanelCard
import dev.yslee.catchtrain.ui.theme.AppColors
import dev.yslee.catchtrain.ui.theme.LabelMuted
import dev.yslee.catchtrain.ui.theme.MonoDense
import dev.yslee.catchtrain.ui.theme.MonoValue
import dev.yslee.catchtrain.watcher.ReserveAttempt
import dev.yslee.catchtrain.watcher.ReserveResult
import dev.yslee.catchtrain.watcher.WatchLogEntry
import dev.yslee.catchtrain.watcher.WatchState
import dev.yslee.catchtrain.watcher.WatchStatus
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
    modifier: Modifier = Modifier,
) {
    PanelCard(modifier = modifier) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "감시중인 좌석",
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
                            "아래 코레일 화면에서 조회한 뒤 [열차 선택] 에서 고르세요.",
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

/** "07:11 305  특실" 형태. 출발이 빠른 순으로 정렬한다. */
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
    val error = status.error
    // 안내 문구를 띄우지 않는 상태들:
    //  - MATCHED  : 아래 [MatchedCard] 가 훨씬 크게 알려 준다.
    //  - RESERVED : [예매] 를 누른 뒤 화면은 코레일 예약 화면이다. 무엇을 눌렀고 이제
    //               무엇을 해야 하는지는 그 화면이 그대로 보여 주므로, 같은 말을 위에
    //               한 번 더 적으면 정작 봐야 할 화면만 밀어낸다. (§19-3, §38-6)
    val silent = status.state == WatchState.MATCHED || status.state == WatchState.RESERVED
    val note = if (error == null && !silent) status.message?.takeIf { it.isNotBlank() } else null
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
 * 사용자가 코레일 사이트에서 원하는 조건으로 조회를 마치면, [갱신] 로 그 결과를
 * 그대로 읽어와 여기에 펼친다. 체크한 칸(= 그 열차의 그 좌석 등급)만 감시하고,
 * 열리면 **그 칸을 고른 뒤 [예매] 까지만** 누른다.
 *
 * 좌석 열의 순서는 사이트와 똑같이 **일반실이 왼쪽, 특실이 오른쪽**이다. (§38-3)
 * SRT 와 반대이니 옮겨 적을 때 주의할 것 — 화면과 앱의 좌우가 다르면
 * 잘못된 칸을 고르기 쉽다.
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
                // 코레일과 같은 순서: 일반실이 왼쪽, 특실이 오른쪽. (§38-3)
                SeatColumnLabel(SeatClass.GENERAL.label)
                SeatColumnLabel(SeatClass.FIRST_CLASS.label)
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
                            "아래 코레일 화면에서 원하는 조건으로 조회한 뒤 [갱신] 를 누르세요.",
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
                    text = "체크한 칸을 고른 뒤 [예매] 까지만 자동으로 누릅니다. " +
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
        // 코레일과 같은 순서: 일반실이 왼쪽, 특실이 오른쪽. **SRT 와 반대다.** (§38-3)
        SeatCheckCell(
            status = train.generalSeat,
            checked = selection.contains(train, SeatClass.GENERAL),
            onToggle = { onToggle(train.key, SeatClass.GENERAL) },
        )
        SeatCheckCell(
            status = train.firstClassSeat,
            checked = selection.contains(train, SeatClass.FIRST_CLASS),
            onToggle = { onToggle(train.key, SeatClass.FIRST_CLASS) },
        )
    }
}

/**
 * 좌석 한 칸. 체크박스 하나뿐이다.
 *
 * 예전에는 체크박스 아래에 "예약가능 / 매진" 을 함께 적었지만, 지금 상태는
 * 바로 아래 코레일 화면이 이미 보여 주고 있고 줄 높이만 두 배로 먹어서 뺐다.
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
 * [reserve] 는 이번에 시도한 자동 예매 결과다. 성공하면 이 카드가 사라지고
 * 코레일 예약 화면이 그대로 드러나므로, 여기서는 실패했을 때만 사유를 알려준다.
 * (그때 앱이 띄우는 것은 재촉 알림을 끄는 [ReserveAlertCard] 뿐이다)
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
                text = "예매는 아래 코레일 화면에서 직접 진행하세요.",
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
                        "[예매] 를 눌렀지만 잔여석이 없었습니다 — 목록으로 돌아가 계속 감시합니다"
                    } else {
                        "자동 예매 ${reserve.stage.label} 단계 실패 — ${reserve.result.label}"
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
 * 결제 재촉 알림을 끄는 카드. (DESIGN.md §19-3, §34-5, §38-6)
 *
 * 자동 예매가 끝나면([WatchState.RESERVED] / [WatchState.SEAT_SELECTED]) 어느 열차의
 * 어떤 좌석인지, 지금 무엇을 해야 하는지는 **바로 아래 코레일 화면이 그대로 보여 준다.**
 * 같은 내용을 위에 한 번 더 적어 봐야 정작 봐야 할 예약 화면만 밀어낸다.
 * 그래서 앱 화면에 남기는 것은 코레일 화면에 없는 것 하나뿐이다 —
 * **10초마다 울리는 재촉 알림을 끄는 버튼.**
 *
 * 재촉이 울리지 않는 동안([alerting] = false)에는 할 말이 없으므로 카드를 띄우지 않는다.
 * 감시를 다시 시작하는 것은 아래 조작 바의 [감시 시작] 이 맡는다.
 */
@Composable
fun ReserveAlertCard(
    onSilence: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PanelCard(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        borderColor = MaterialTheme.colorScheme.primary,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "🔔 10초마다 알림이 울립니다. 10분 안에 결제하지 않으면 좌석이 풀립니다.",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            // 알림만 끄고 예약 화면은 그대로 둔다. 결제는 아직 안 끝났다.
            Button(
                onClick = onSilence,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                shape = MaterialTheme.shapes.small,
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
            ) {
                Text("🔕 알림 끄기")
            }
        }
    }
}

/**
 * 개발용 로그 패널. (DESIGN.md §29)
 *
 * 목록 자체는 화면에서 가장 어두운 면에 얹어 터미널처럼 보이게 한다.
 *
 * ### 왜 줄마다 Text 를 두지 않고 한 덩어리인가
 *
 * 로그를 **PC 로 옮겨야** 고칠 수 있다. 그래서 손으로 끌어 고르는 것과
 * [복사] 둘 다 되어야 한다.
 *
 * `LazyColumn` + 줄마다 `Text` 는 그 둘을 못 한다. 화면 밖으로 나간 줄은 조합에서
 * 빠지므로 **끌어서 고를 수 있는 범위가 지금 보이는 화면까지**로 잘린다.
 * 줄 사이 경계에서 선택이 끊기기도 한다. 그래서 전체를 문자열 하나로 만들어
 * [SelectionContainer] 안의 `Text` 하나에 담고, 스크롤을 바깥에서 준다.
 * 버퍼는 `WatchLogger` 가 300줄로 묶어 두므로 한 번에 배치해도 부담이 없다.
 */
@Composable
fun LogPanel(
    logs: List<WatchLogEntry>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 역 선택 창 진단. (DESIGN.md §38-10)
     *
     * 이 창은 페이지가 스스로 그리는 react-modal 이라 안 뜰 때 앱이 대신 띄울 수 없다.
     * 로그 창에 둔 이유가 그것이다 — 여기서 할 수 있는 일은 원인을 남기는 것뿐이다.
     */
    onProbeStation: (() -> Unit)? = null,
    /** 클립보드에 담은 직후. 몇 줄이었는지 넘긴다. 안내 문구는 부르는 쪽이 정한다. */
    onCopied: (Int) -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    val scroll = rememberScrollState()

    // `WatchLogger.dump()` 와 같은 순서(오래된 것이 위). 복사한 것과 화면이 어긋나면 안 된다.
    val text = remember(logs) { logs.joinToString("\n") { it.format() } }

    // 새 줄이 붙으면 맨 아래로 따라간다. 단 **사용자가 위로 올려 둔 동안에는 가만히 둔다** —
    // 끌어서 고르는 중에 화면이 움직이면 선택이 끊긴다.
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(scroll.isScrollInProgress) {
        if (!scroll.isScrollInProgress) {
            follow = scroll.value >= scroll.maxValue - FOLLOW_BOTTOM_SLACK_PX
        }
    }
    // maxValue 를 키로 두는 것이 중요하다. 글이 늘어난 **다음** 배치까지 기다렸다가 내려간다.
    LaunchedEffect(scroll.maxValue) {
        if (follow) scroll.scrollTo(scroll.maxValue)
    }

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
                if (onProbeStation != null) {
                    LogPanelButton("역 진단", onClick = onProbeStation)
                }
                LogPanelButton(
                    label = "복사",
                    enabled = logs.isNotEmpty(),
                    onClick = {
                        clipboard.setText(AnnotatedString(text))
                        onCopied(logs.size)
                    },
                )
                LogPanelButton("지우기", onClick = onClear)
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
                SelectionContainer {
                    Text(
                        text = text,
                        style = MonoDense,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .fillMaxWidth()
                            .background(AppColors.Terminal, MaterialTheme.shapes.extraSmall)
                            .verticalScroll(scroll)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * 로그 패널 머리말의 작은 버튼. 셋이 한 줄에 들어가야 해서 좌우 여백을 줄인다.
 * (기본 [TextButton] 여백이면 좁은 화면에서 [지우기] 가 잘린다)
 */
@Composable
private fun LogPanelButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

private const val MAX_SHOWN_MATCHES = 5

/** 요약 카드에 몇 칸까지 펼쳐 보여줄지. */
private const val MAX_SHOWN_SELECTIONS = 4

/** 좌석 체크 칸 하나의 너비. 머리말과 본문이 같은 값을 써야 세로줄이 맞는다. */
private val SEAT_CELL_WIDTH = 78.dp

/** 열차 한 줄의 위아래 여백. 목록이 길어 한 화면에 최대한 많이 담는다. */
private val ROW_VERTICAL_PADDING = 1.dp

/**
 * 로그 창이 "맨 아래에 있다" 고 볼 여유(px). 손을 뗀 위치가 이 안이면 새 줄을 계속 따라간다.
 * 한 줄 높이보다 조금 작게 둔다 — 일부러 위로 올렸는지와 구분되면 된다.
 */
private const val FOLLOW_BOTTOM_SLACK_PX = 24
