package com.example.srtwatcher.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 앱 디자인 토큰. (색 / 타이포 / 셰이프)
 *
 * 검정~회색 중립 계열 위에 강조색 하나(라임)만 올리는 구성이다.
 * Material3 의 기본 팔레트는 지정하지 않은 롤이 보라/분홍 계열로 채워지므로,
 * [SrtWatcherTheme] 에서 모든 롤을 여기 값으로 덮어쓴다.
 */
object AppColors {
    /** 앱 바탕. 순검정보다 아주 조금 밝게 둬서 OLED 번짐을 피한다. */
    val Background = Color(0xFF0A0B0D)
    val Surface = Color(0xFF0F1113)

    /** 카드 배경 (기본) */
    val SurfaceLow = Color(0xFF131619)
    val SurfaceMid = Color(0xFF16191D)

    /** 다이얼로그 / 떠 있는 표면 */
    val SurfaceHigh = Color(0xFF1D2126)
    val SurfaceTop = Color(0xFF23282E)

    /** URL pill, 보조 배경 */
    val SurfaceVariant = Color(0xFF22262B)

    /** 카드 테두리 */
    val Outline = Color(0xFF2E343B)

    /** 구분선(hairline). 테두리보다 한 단계 더 흐리다. */
    val OutlineFaint = Color(0xFF1E2227)

    val OnSurface = Color(0xFFE7EAED)
    val OnSurfaceMuted = Color(0xFF939CA6)

    /** 강조색. 버튼 채움 / 선택 상태 / 발견 표시에 쓴다. */
    val Lime = Color(0xFFC6F24E)

    /** 강조색 배경(틴트). 라임 위에는 [Background] 색 글씨를 올린다. */
    val LimeDim = Color(0xFF1F2A10)
    val LimeText = Color(0xFFDCF7A0)

    val Red = Color(0xFFFF5C5C)
    val RedDim = Color(0xFF2A1114)
    val RedText = Color(0xFFFFB4AB)

    /** 로그 패널 전용. 화면에서 가장 어두운 면. */
    val Terminal = Color(0xFF07080A)
}

/**
 * 감시 상태 신호등 색. Material3 롤이 아니라 상태 전용 색이라 따로 둔다.
 * (DESIGN.md §8 의 이모지 신호등을 색 점으로 대체한다)
 */
object StatusColors {
    val Running = Color(0xFF4ADE80)
    val Paused = Color(0xFFFBBF24)
    val Idle = Color(0xFF5B636C)
}

/** 헤더 로고. 자간을 넓힌 모노스페이스. */
val BrandTitle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 10.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 2.0.sp,
)

/** 상태 패널 수치(마지막 확인 / 다음 확인 등). 자릿수가 흔들리지 않게 모노스페이스. */
val MonoValue = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 15.sp,
    fontWeight = FontWeight.Medium,
)

/** 로그 / 열차 목록처럼 줄이 촘촘한 곳. */
val MonoDense = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    lineHeight = 16.sp,
)

/** 주소 표시줄 pill. */
val MonoUrl = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
)

/** 섹션 라벨 / 셀 라벨처럼 작고 흐린 글씨. */
val LabelMuted = TextStyle(
    fontSize = 11.sp,
    fontWeight = FontWeight.Medium,
    letterSpacing = 0.6.sp,
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)
