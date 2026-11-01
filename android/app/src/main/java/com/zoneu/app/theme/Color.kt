package com.zoneu.app.theme

import androidx.compose.ui.graphics.Color

// 参照 ChatGPT / DeepSeek 手机端的聊天气质：白底、中性灰、冷蓝强调。
// 全局不用 Material You 动态取色，避免品牌色随机漂移。
//
// 所有前景/背景组合都要过 WCAG 2.2 AA：正文 4.5:1，组件边界等非文本元素 3:1。
// 品牌蓝 #4D6BFE 在白底上只有 4.33:1，所以浅色主色压到 #445FF5（白底 5.0:1）；
// 深色主色本来就是提亮版，不受影响。

// --- 主色：品牌蓝 ---
val ZoneuBlue = Color(0xFF445FF5)
val ZoneuBlueLight = Color(0xFFB6C2FF)

// --- 浅色 ---
val LightPrimary = ZoneuBlue
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFE0E6FF)
val LightOnPrimaryContainer = Color(0xFF0B1747)

val LightSecondary = Color(0xFF565E7A)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFDCE2F9)
val LightOnSecondaryContainer = Color(0xFF141B2C)

val LightTertiary = Color(0xFF00696E)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFF9CF1F3)
val LightOnTertiaryContainer = Color(0xFF002021)

val LightError = Color(0xFFB3261E)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFF9DEDC)
val LightOnErrorContainer = Color(0xFF410E0B)

val LightBackground = Color(0xFFF7F7FB)
val LightOnBackground = Color(0xFF17181C)
val LightSurface = Color(0xFFF7F7FB)
val LightOnSurface = Color(0xFF17181C)
val LightSurfaceVariant = Color(0xFFE7E8EE)

// 6F7683 在浅灰卡片上只有 4.19:1；压深一档，三种底色都过 4.5。
val LightOnSurfaceVariant = Color(0xFF5C6370)

// outline 是「组件边界」（输入框、开关、未选中控件），要 3:1；
// 装饰性分割线走 outlineVariant，可以更淡。
val LightOutline = Color(0xFF7A8290)
val LightOutlineVariant = Color(0xFFE2E4EA)
val LightScrim = Color(0xFF000000)
val LightInverseSurface = Color(0xFF2E3038)
val LightInverseOnSurface = Color(0xFFF1F1F5)
val LightInversePrimary = Color(0xFFB6C2FF)

// 表面梯度：从最低（浮起的卡）到最高（压在最上面的面板）。
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFFBFBFE)
val LightSurfaceContainer = Color(0xFFF1F2F7)
val LightSurfaceContainerHigh = Color(0xFFE9EAF1)
val LightSurfaceContainerHighest = Color(0xFFE1E3EA)
val LightSurfaceDim = Color(0xFFDCDDE5)
val LightSurfaceBright = Color(0xFFFFFFFF)

// 语义色：成功 / 警告。M3 的 error 之外，界面上只有这两类额外状态。
val LightSuccess = Color(0xFF1B7F4B)
val LightOnSuccess = Color(0xFFFFFFFF)
val LightSuccessContainer = Color(0xFFDDF3E4)
val LightOnSuccessContainer = Color(0xFF0B3D22)
val LightWarning = Color(0xFF8A5A00)
val LightOnWarning = Color(0xFFFFFFFF)
val LightWarningContainer = Color(0xFFFCEFD9)
val LightOnWarningContainer = Color(0xFF4A3000)

// --- 深色 ---
val DarkPrimary = ZoneuBlueLight
val DarkOnPrimary = Color(0xFF14224F)
val DarkPrimaryContainer = Color(0xFF26356B)
val DarkOnPrimaryContainer = Color(0xFFDDE3FF)

val DarkSecondary = Color(0xFFC0C6E0)
val DarkOnSecondary = Color(0xFF2A3048)
val DarkSecondaryContainer = Color(0xFF3A4160)
val DarkOnSecondaryContainer = Color(0xFFDDE2F5)

val DarkTertiary = Color(0xFF83D4D8)
val DarkOnTertiary = Color(0xFF00363A)
val DarkTertiaryContainer = Color(0xFF004F53)
val DarkOnTertiaryContainer = Color(0xFF9CF1F3)

val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnErrorContainer = Color(0xFFFFDAD6)

val DarkBackground = Color(0xFF101116)
val DarkOnBackground = Color(0xFFE4E5EA)
val DarkSurface = Color(0xFF101116)
val DarkOnSurface = Color(0xFFE4E5EA)
val DarkSurfaceVariant = Color(0xFF3F424C)
val DarkOnSurfaceVariant = Color(0xFFC3C6D0)
val DarkOutline = Color(0xFF8D919C)
val DarkOutlineVariant = Color(0xFF3F424C)
val DarkScrim = Color(0xFF000000)
val DarkInverseSurface = Color(0xFFE4E5EA)
val DarkInverseOnSurface = Color(0xFF2E3038)
val DarkInversePrimary = Color(0xFF3D5AF0)

val DarkSurfaceContainerLowest = Color(0xFF0B0C10)
val DarkSurfaceContainerLow = Color(0xFF15161B)
val DarkSurfaceContainer = Color(0xFF1C1E24)
val DarkSurfaceContainerHigh = Color(0xFF262931)
val DarkSurfaceContainerHighest = Color(0xFF31343D)
val DarkSurfaceDim = Color(0xFF101116)
val DarkSurfaceBright = Color(0xFF363943)

val DarkSuccess = Color(0xFF6FD79B)
val DarkOnSuccess = Color(0xFF00391C)
val DarkSuccessContainer = Color(0xFF1B3A2A)
val DarkOnSuccessContainer = Color(0xFF86E0AC)
val DarkWarning = Color(0xFFF2C46B)
val DarkOnWarning = Color(0xFF3F2E00)
val DarkWarningContainer = Color(0xFF3B2F1A)
val DarkOnWarningContainer = Color(0xFFF2C77E)

// 气泡：用户消息用主色容器，助手消息用表面梯度。改成语义别名，
// 避免以后再从 secondaryContainer 之类的通用槽位「借」一个颜色。
val LightUserBubble = LightPrimaryContainer
val LightOnUserBubble = LightOnPrimaryContainer
val DarkUserBubble = DarkPrimaryContainer
val DarkOnUserBubble = DarkOnPrimaryContainer
