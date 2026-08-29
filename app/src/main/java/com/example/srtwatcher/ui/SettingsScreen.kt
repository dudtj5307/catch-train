package com.example.srtwatcher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToLong
import com.example.srtwatcher.storage.AppSettings
import com.example.srtwatcher.ui.components.PanelCard
import com.example.srtwatcher.ui.components.RowDivider
import com.example.srtwatcher.ui.components.SectionLabel
import com.example.srtwatcher.ui.theme.MonoValue
import com.example.srtwatcher.viewmodel.WatchViewModel
import com.example.srtwatcher.watcher.ReloadScheduler

/**
 * 설정 화면. (DESIGN.md §21, §23)
 *
 * **감시 조건은 여기에 없다.** 구간/날짜/시간/좌석 등급을 앱에 입력하던 화면은
 * 없앴다. 조회는 사용자가 SRT 사이트에서 직접 하고, 감시 대상은 메인 화면의
 * [열차 선택] 목록에서 체크한 칸이다. 같은 조건을 두 군데에 입력하면
 * 어긋났을 때 원인을 찾기 어렵다.
 *
 * 여기 남은 것은 앱이 어떻게 동작할지(간격/알림/자동 예약)뿐이다.
 *
 * WebView 를 detach 하지 않기 위해 별도 화면 전환 대신
 * 전체 화면 오버레이(Surface)로 띄운다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    viewModel: WatchViewModel,
    onGoHome: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "설정",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "닫기",
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                SectionLabel("감시 동작")
                PanelCard {
                    IntervalRangeSetting(
                        minIntervalMs = settings.minIntervalMs,
                        maxIntervalMs = settings.maxIntervalMs,
                        onChange = viewModel::setReloadIntervalRange,
                    )
                    RowDivider()
                    SwitchRow(
                        label = "알림 사용",
                        description = "선택한 좌석이 열리면 Android 알림을 표시",
                        checked = settings.notificationEnabled,
                        onCheckedChange = viewModel::setNotificationEnabled,
                    )
                    SwitchRow(
                        label = "[예약하기] 자동 클릭",
                        description = "좌석이 열리면 그 칸의 예약하기 버튼까지 눌러 줍니다. " +
                            "좌석 선택과 결제는 직접 진행합니다",
                        checked = settings.autoReserveEnabled,
                        onCheckedChange = viewModel::setAutoReserveEnabled,
                    )
                    SwitchRow(
                        label = "발견 시 감시 중지",
                        description = "불필요한 요청을 계속 보내지 않습니다",
                        checked = settings.stopOnMatch,
                        onCheckedChange = viewModel::setStopOnMatch,
                    )
                }

                // 헤더에 있던 [SRT 홈] 이 [설정] 으로 바뀌면서 이 자리로 옮겨왔다.
                // 로그인이나 세션이 꼬였을 때 시작 페이지로 되돌아오는 통로다.
                SectionLabel("페이지")
                PanelCard {
                    SettingRow(
                        label = "SRT 홈으로 이동",
                        value = "",
                        onClick = onGoHome,
                    )
                }

                Spacer(Modifier.height(20.dp))
                Text(
                    text = "· 조회 조건(구간/날짜/시간)은 아래 SRT 화면에서 직접 지정합니다. " +
                        "조회를 마친 뒤 [열차 선택] 에서 [갱신] 를 누르면 목록이 채워집니다.\n" +
                        "· 앱은 체크한 칸의 [예약하기] 버튼만 누릅니다. " +
                        "좌석 선택과 결제는 사용자가 직접 진행합니다.\n" +
                        "· 갱신은 페이지의 [조회하기] 버튼을 눌러서만 합니다. " +
                        "조회하기 버튼이 보이는 결과 화면에서 감시를 시작하세요.\n" +
                        "· 재조회 간격은 " +
                        "${formatSeconds(ReloadScheduler.MIN_INTERVAL_MS)}~" +
                        "${formatSeconds(ReloadScheduler.MAX_INTERVAL_MS)}초 범위에서 조정합니다. " +
                        "사이트 이용정책과 요청 제한을 준수하세요.\n" +
                        "· 짧은 간격을 오래 유지하면 접속이 차단될 수 있습니다. " +
                        "차단된 뒤에는 간격을 넉넉히 늘리는 편이 안전합니다.\n" +
                        "· 화면이 켜져 있고 앱이 앞에 있을 때만 감시합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}

/** 값이 비어 있으면 라벨만 있는 이동 항목으로 쓴다. */
@Composable
private fun SettingRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) {
            Text(
                text = value,
                style = MonoValue,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = "  ›",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label)
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 재조회 간격 범위. [ReloadScheduler.MIN_INTERVAL_MS]~[ReloadScheduler.MAX_INTERVAL_MS]
 * (0.0~3.0초) 안에서 조정한다. (DESIGN.md §11)
 *
 * 고정 간격 대신 범위를 받아, 매 사이클 이 범위 안에서 무작위로 대기한다.
 * 요청이 정확히 같은 주기로 반복되지 않게 하기 위함이다.
 *
 * 입력 방법은 두 가지다.
 *   1) 위쪽 숫자 입력칸 : 초 단위로 직접 타이핑 (0.1초 단위로 스냅)
 *   2) 아래쪽 구간 슬라이더 / 프리셋 : 기존과 동일한 범위 선택
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntervalRangeSetting(
    minIntervalMs: Long,
    maxIntervalMs: Long,
    onChange: (Long, Long) -> Unit,
) {
    val lowerBound = ReloadScheduler.MIN_INTERVAL_MS.toFloat()
    val upperBound = ReloadScheduler.MAX_INTERVAL_MS.toFloat()
    val steps = ((ReloadScheduler.MAX_INTERVAL_MS - ReloadScheduler.MIN_INTERVAL_MS) /
        ReloadScheduler.STEP_MS).toInt() - 1

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "재조회 간격 (랜덤)", modifier = Modifier.weight(1f))
            Text(
                text = ReloadScheduler.formatRange(minIntervalMs, maxIntervalMs),
                style = MonoValue,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // 1) 직접 입력 : 최소 / 최대 (초)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SecondsField(
                label = "최소(초)",
                valueMs = minIntervalMs,
                // 최소가 최대를 넘어서면 최대를 같이 밀어 올린다.
                onCommit = { onChange(it, maxOf(it, maxIntervalMs)) },
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "~",
                modifier = Modifier.padding(horizontal = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SecondsField(
                label = "최대(초)",
                valueMs = maxIntervalMs,
                onCommit = { onChange(minOf(it, minIntervalMs), it) },
                modifier = Modifier.weight(1f),
            )
        }

        // 2) 구간 선택
        val start = minIntervalMs.toFloat().coerceIn(lowerBound, upperBound)
        val end = maxIntervalMs.toFloat().coerceIn(start, upperBound)
        RangeSlider(
            value = start..end,
            onValueChange = { range ->
                onChange(snapToStep(range.start), snapToStep(range.endInclusive))
            },
            valueRange = lowerBound..upperBound,
            steps = if (steps > 0) steps else 0,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickRangeChip("0.1~0.3초", 100L, 300L, minIntervalMs, maxIntervalMs, onChange)
            QuickRangeChip("0.5~1.5초", 500L, 1_500L, minIntervalMs, maxIntervalMs, onChange)
            QuickRangeChip("1~3초", 1_000L, 3_000L, minIntervalMs, maxIntervalMs, onChange)
        }
        Text(
            text = "확인할 때마다 이 범위 안에서 대기 시간을 새로 뽑습니다(밀리초 단위). " +
                "0초에 가까울수록 요청이 몰려 차단 위험이 커집니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * 초 단위 숫자 입력칸. 유효한 숫자가 되는 순간 바로 반영한다.
 *
 * 입력 중에는 사용자가 친 글자를 그대로 두고, 포커스가 빠지거나
 * 슬라이더/프리셋으로 값이 바뀌면 정규화된 표시("1.0")로 되돌린다.
 */
@Composable
private fun SecondsField(
    label: String,
    valueMs: Long,
    onCommit: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf(formatSeconds(valueMs)) }
    var focused by remember { mutableStateOf(false) }

    LaunchedEffect(valueMs, focused) {
        if (!focused) text = formatSeconds(valueMs)
    }

    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val filtered = raw.filter { it.isDigit() || it == '.' }.take(MAX_SECONDS_INPUT_CHARS)
            text = filtered
            val seconds = filtered.toDoubleOrNull()
            if (seconds != null) onCommit(snapSeconds(seconds))
        },
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        textStyle = MonoValue,
        singleLine = true,
        shape = MaterialTheme.shapes.extraSmall,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Done,
        ),
        modifier = modifier.onFocusChanged { focused = it.isFocused },
    )
}

/** 자주 쓰는 간격 프리셋. 현재 값과 같으면 강조색으로 채워진다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickRangeChip(
    label: String,
    min: Long,
    max: Long,
    currentMin: Long,
    currentMax: Long,
    onChange: (Long, Long) -> Unit,
) {
    val selected = currentMin == min && currentMax == max
    FilterChip(
        selected = selected,
        onClick = { onChange(min, max) },
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        shape = MaterialTheme.shapes.extraSmall,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = MaterialTheme.colorScheme.outline,
            selectedBorderColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

private fun snapToStep(raw: Float): Long {
    val stepped = (raw / ReloadScheduler.STEP_MS).roundToLong() * ReloadScheduler.STEP_MS
    return ReloadScheduler.clamp(stepped)
}

/** "1.5" → 1_500ms. 0.1초 단위로 반올림하고 허용 범위로 clamp 한다. */
private fun snapSeconds(seconds: Double): Long {
    val ms = (seconds * 1000.0).roundToLong()
    val stepped = (ms.toDouble() / ReloadScheduler.STEP_MS).roundToLong() * ReloadScheduler.STEP_MS
    return ReloadScheduler.clamp(stepped)
}

/** 1_500ms → "1.5" */
private fun formatSeconds(ms: Long): String = "%.1f".format(ms / 1000.0)

/** "0.0" ~ "3.0" 을 담을 정도면 충분하다. */
private const val MAX_SECONDS_INPUT_CHARS = 4
