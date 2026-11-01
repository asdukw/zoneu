package com.zoneu.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

private val LightColorScheme =
  lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    scrim = LightScrim,
    inverseSurface = LightInverseSurface,
    inverseOnSurface = LightInverseOnSurface,
    inversePrimary = LightInversePrimary,
    surfaceTint = LightPrimary,
    surfaceDim = LightSurfaceDim,
    surfaceBright = LightSurfaceBright,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
  )

private val DarkColorScheme =
  darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    scrim = DarkScrim,
    inverseSurface = DarkInverseSurface,
    inverseOnSurface = DarkInverseOnSurface,
    inversePrimary = DarkInversePrimary,
    surfaceTint = DarkPrimary,
    surfaceDim = DarkSurfaceDim,
    surfaceBright = DarkSurfaceBright,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
  )

/** 圆角只有五档，别在页面里随手写 14/18/22。页面需要语义形状时用 [ZoneuShapes]。 */
private val M3Shapes =
  Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
  )

/**
 * 语义形状：把「气泡、输入卡、卡片、英雄区」从具体半径里拆出来。
 *
 * M3 的五个 Shapes 槽位描述的是尺寸，不是用途；聊天页之前出现 22dp 气泡 + 26dp 输入卡，
 * 正是因为用途没有名字。改这里一处，所有气泡一起变。
 */
@Immutable
data class ZoneuShapes(
  /** 用户气泡：右下角收小，形成朝向输入框的「尾巴」感。 */
  val userBubble: Shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp),
  val assistantBubble: Shape = RoundedCornerShape(20.dp),
  val composer: Shape = RoundedCornerShape(28.dp),
  val card: Shape = RoundedCornerShape(20.dp),
  val hero: Shape = RoundedCornerShape(32.dp),
  val pill: Shape = RoundedCornerShape(50),
)

val LocalZoneuShapes = staticCompositionLocalOf { ZoneuShapes() }

val MaterialTheme.zoneuShapes: ZoneuShapes
  @Composable @ReadOnlyComposable
  get() = LocalZoneuShapes.current

/**
 * 当前是否深色，以**主题**为准而不是 `isSystemInDarkTheme()`。
 * 页面直接问系统，会在将来加主题切换时出现「主题是深色、页面按浅色画」的混搭。
 */
val LocalZoneuDarkTheme = staticCompositionLocalOf { false }

/**
 * M3 的 colorScheme 没有成功/警告两档，而横幅、徽章、任务状态要用它们区分
 * 「做成了」和「要注意」。容器色成对给出，页面不再自己拼十六进制。
 */
@Immutable
data class ZoneuColors(
  val success: Color,
  val onSuccess: Color,
  val successContainer: Color,
  val onSuccessContainer: Color,
  val warning: Color,
  val onWarning: Color,
  val warningContainer: Color,
  val onWarningContainer: Color,
  val userBubble: Color,
  val onUserBubble: Color,
)

private val LightZoneuColors =
  ZoneuColors(
    success = LightSuccess,
    onSuccess = LightOnSuccess,
    successContainer = LightSuccessContainer,
    onSuccessContainer = LightOnSuccessContainer,
    warning = LightWarning,
    onWarning = LightOnWarning,
    warningContainer = LightWarningContainer,
    onWarningContainer = LightOnWarningContainer,
    userBubble = LightUserBubble,
    onUserBubble = LightOnUserBubble,
  )

private val DarkZoneuColors =
  ZoneuColors(
    success = DarkSuccess,
    onSuccess = DarkOnSuccess,
    successContainer = DarkSuccessContainer,
    onSuccessContainer = DarkOnSuccessContainer,
    warning = DarkWarning,
    onWarning = DarkOnWarning,
    warningContainer = DarkWarningContainer,
    onWarningContainer = DarkOnWarningContainer,
    userBubble = DarkUserBubble,
    onUserBubble = DarkOnUserBubble,
  )

val LocalZoneuColors = staticCompositionLocalOf { LightZoneuColors }

val MaterialTheme.zoneuColors: ZoneuColors
  @Composable @ReadOnlyComposable
  get() = LocalZoneuColors.current

@Composable
fun ZoneuTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  CompositionLocalProvider(
    LocalZoneuDarkTheme provides darkTheme,
    LocalZoneuColors provides if (darkTheme) DarkZoneuColors else LightZoneuColors,
    LocalZoneuShapes provides ZoneuShapes(),
  ) {
    MaterialExpressiveTheme(
      colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
      motionScheme = MotionScheme.expressive(),
      typography = Typography,
      shapes = M3Shapes,
      content = content,
    )
  }
}
