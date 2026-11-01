package com.zoneu.app.theme

import androidx.compose.ui.unit.dp

/**
 * 间距与尺寸 token。
 *
 * 页面里不再写 13/14/18/22 这种值：间距只取 4 的倍数（另加 2dp 的细缝），
 * 触控目标统一 48dp，列表行最低 64dp。调整密度时改这里，而不是全仓库搜 `.dp`。
 */
object ZoneuSpacing {
  val hairline = 1.dp
  val xxs = 2.dp
  val xs = 4.dp
  val sm = 8.dp
  val md = 12.dp
  val lg = 16.dp
  val xl = 24.dp
  val xxl = 32.dp
  val xxxl = 48.dp
}

object ZoneuSize {
  /** 平台最小触控目标。 */
  val touchTarget = 48.dp

  /** 列表/设置行的最小高度。 */
  val rowMinHeight = 64.dp

  val iconSmall = 18.dp
  val icon = 24.dp
  val iconLarge = 32.dp

  /** 正文阅读宽度：平板/横屏上超过这个宽度一行太长。 */
  val contentMaxWidth = 760.dp

  /** 设置页的最大宽度（比正文宽一点，卡片列表不至于过窄）。 */
  val settingsMaxWidth = 840.dp

  /** 空态/英雄区的图形尺寸。 */
  val hero = 72.dp
}

/** 页面水平边距。聊天与设置共用，避免两处内容对不齐。 */
val ZoneuPageHorizontalPadding = ZoneuSpacing.lg
