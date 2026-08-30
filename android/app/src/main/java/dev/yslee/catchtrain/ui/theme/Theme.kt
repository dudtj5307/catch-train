package dev.yslee.catchtrain.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 검정/회색 + 라임 강조 다크 팔레트.
 *
 * 롤을 하나도 빠뜨리지 않고 지정한다. 비워 두면 Material3 기본값(보라/분홍)이
 * 들어와 헤더나 카드 배경이 엉뚱한 색으로 보인다.
 */
private val DarkColors = darkColorScheme(
    primary = AppColors.Lime,
    onPrimary = AppColors.Background,
    primaryContainer = AppColors.LimeDim,
    onPrimaryContainer = AppColors.LimeText,
    inversePrimary = AppColors.LimeDim,

    secondary = AppColors.OnSurfaceMuted,
    onSecondary = AppColors.Background,
    secondaryContainer = AppColors.SurfaceVariant,
    onSecondaryContainer = AppColors.OnSurface,

    tertiary = AppColors.Lime,
    onTertiary = AppColors.Background,
    tertiaryContainer = AppColors.LimeDim,
    onTertiaryContainer = AppColors.LimeText,

    background = AppColors.Background,
    onBackground = AppColors.OnSurface,
    surface = AppColors.Surface,
    onSurface = AppColors.OnSurface,
    surfaceVariant = AppColors.SurfaceVariant,
    onSurfaceVariant = AppColors.OnSurfaceMuted,

    // 표면 고도(elevation)에 primary 를 섞는 M3 기본 동작을 끈다.
    // 켜 두면 떠 있는 카드가 전부 라임빛으로 물든다.
    surfaceTint = Color.Transparent,

    surfaceBright = AppColors.SurfaceHigh,
    surfaceDim = AppColors.Background,
    surfaceContainerLowest = AppColors.Terminal,
    surfaceContainerLow = AppColors.SurfaceLow,
    surfaceContainer = AppColors.SurfaceMid,
    surfaceContainerHigh = AppColors.SurfaceHigh,
    surfaceContainerHighest = AppColors.SurfaceTop,

    inverseSurface = AppColors.OnSurface,
    inverseOnSurface = AppColors.Background,

    error = AppColors.Red,
    onError = AppColors.Background,
    errorContainer = AppColors.RedDim,
    onErrorContainer = AppColors.RedText,

    outline = AppColors.Outline,
    outlineVariant = AppColors.OutlineFaint,
    scrim = Color(0xCC000000),
)

/**
 * 앱 테마. 시스템 설정과 무관하게 항상 다크로 고정한다.
 *
 * WebView 안의 코레일 페이지는 흰 배경 그대로 둔다. 강제 다크(algorithmic darkening)는
 * 페이지 레이아웃을 깨뜨릴 수 있어 쓰지 않는다.
 */
@Composable
fun CatchTrainTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        shapes = AppShapes,
        content = content,
    )
}
