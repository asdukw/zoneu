package com.zoneu.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zoneu.app.agent.LlmPreset
import com.zoneu.app.theme.LocalZoneuDarkTheme
import com.zoneu.app.theme.ZoneuSize
import com.zoneu.app.theme.ZoneuSpacing
import com.zoneu.app.theme.zoneuColors
import com.zoneu.app.theme.zoneuShapes
import com.zoneu.app.ui.components.AppBanner
import com.zoneu.app.ui.components.ProviderModelIcon

/*
 * 设置页共用的小部件：分组卡片、行、状态徽章、提供商头像、顶栏、底部页签。
 *
 * 视觉对齐参考图：浅灰页面 + 白色圆角分组卡片 + 线稿图标 + 语义色徽章。
 * 颜色不用 Material You 动态取色（全局主题已经这么定了），保证品牌色不漂移。
 */

/**
 * 设置页与聊天页共用同一个页面底色。
 *
 * 之前设置页刷 surfaceContainer、聊天页用 background，Nav3 交叉淡入时整屏会闪一下；
 * 分组卡片靠「比页面白一档/深一档」拉开层次，而不是靠改整页底色。
 */
@Composable
internal fun settingsPageColor(): Color = MaterialTheme.colorScheme.background

@Composable
internal fun settingsCardColor(): Color =
  if (LocalZoneuDarkTheme.current) {
    MaterialTheme.colorScheme.surfaceContainer
  } else {
    MaterialTheme.colorScheme.surfaceContainerLowest
  }

@Composable
internal fun SectionLabel(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelLargeEmphasized,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = ZoneuSpacing.sm, top = ZoneuSpacing.xl, bottom = ZoneuSpacing.sm),
  )
}

@Composable
internal fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.zoneuShapes.card,
    color = settingsCardColor(),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Column { content() }
  }
}

@Composable
internal fun SettingsDivider() {
  HorizontalDivider(
    modifier = Modifier.padding(start = 56.dp),
    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
  )
}

/**
 * 一行设置。[onClick] 为空或 [enabled] 为假时不可点；[trailing] 放状态徽章或箭头。
 * 图标与文案对不齐是这类页面最常见的视觉 bug —— 图标固定 24dp、标题 16sp，行高统一 64dp 起。
 */
@Composable
internal fun SettingsRow(
  glyph: GlyphKind,
  title: String,
  subtitle: String,
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null,
  enabled: Boolean = true,
  trailing: @Composable RowScope.() -> Unit = {},
) {
  val clickable = onClick != null && enabled
  ListItem(
    modifier =
      modifier
        .fillMaxWidth()
        .heightIn(min = ZoneuSize.rowMinHeight)
        .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier),
    headlineContent = {
      Text(
        text = title,
        style = MaterialTheme.typography.bodyLarge,
        color =
          if (enabled) {
            MaterialTheme.colorScheme.onSurface
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
          },
      )
    },
    supportingContent = {
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.6f),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    },
    leadingContent = {
      Box(
        modifier =
          Modifier.size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
      ) {
        Glyph(
          kind = glyph,
          modifier = Modifier.size(22.dp),
          tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.45f),
        )
      }
    },
    trailingContent = { Row(verticalAlignment = Alignment.CenterVertically) { trailing() } },
    colors =
      ListItemDefaults.colors(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        leadingContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        supportingContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        trailingContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
      ),
  )
}

@Composable
internal fun ChevronRight() {
  Glyph(
    kind = GlyphKind.Back,
    modifier = Modifier.size(18.dp).rotate(180f),
    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
  )
}

internal enum class BadgeTone {
  Enabled,
  Disabled,
  Info,
  Muted,
}

/** 语义色徽章：绿=启用、琥珀=禁用、蓝=数量/信息、灰=未支持。深浅色各一套，保证对比度。 */
@Composable
internal fun StatusBadge(text: String, tone: BadgeTone, modifier: Modifier = Modifier) {
  val scheme = MaterialTheme.colorScheme
  val semantic = MaterialTheme.zoneuColors
  val (background, foreground) =
    when (tone) {
      BadgeTone.Enabled -> semantic.successContainer to semantic.onSuccessContainer
      BadgeTone.Disabled -> semantic.warningContainer to semantic.onWarningContainer
      BadgeTone.Info -> scheme.secondaryContainer to scheme.onSecondaryContainer
      BadgeTone.Muted -> scheme.surfaceContainerHigh to scheme.onSurfaceVariant
    }
  Text(
    text = text,
    style = MaterialTheme.typography.labelSmall,
    color = foreground,
    modifier =
      modifier
        .clip(MaterialTheme.zoneuShapes.pill)
        .background(background)
        .padding(horizontal = ZoneuSpacing.sm, vertical = 3.dp),
  )
}

/** 提供商头像：品牌色圆 + 字母（参考图用 logo；这里用字母，免得为几个图标引资源包）。 */
@Composable
internal fun ProviderAvatar(
  preset: LlmPreset,
  name: String,
  modifier: Modifier = Modifier,
  size: Int = 44,
) {
  ProviderModelIcon(
    preset = preset,
    name = name,
    modifier = modifier,
    size = size,
    shape = CircleShape,
  )
}

@Composable
internal fun BackCircle(onClick: () -> Unit, modifier: Modifier = Modifier) {
  Box(
    modifier =
      modifier
        .size(ZoneuSize.touchTarget)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .clickable(onClick = onClick)
        .semantics { contentDescription = "返回" },
    contentAlignment = Alignment.Center,
  ) {
    Glyph(GlyphKind.Back, Modifier.size(22.dp), MaterialTheme.colorScheme.onSurface)
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsHeader(
  title: String,
  onBack: () -> Unit,
  actions: @Composable RowScope.() -> Unit = {},
) {
  // 用真正的 TopAppBar，而不是自绘 Row：滚动色、标题排版和返回按钮的触控目标
  // 都交给 M3；windowInsets 置零，因为页面根节点已经让过状态栏了。
  TopAppBar(
    modifier = Modifier.testTag("settings_top_bar"),
    title = {
      Text(
        text = title,
        style = MaterialTheme.typography.titleLargeEmphasized,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    },
    navigationIcon = {
      IconButton(onClick = onBack) {
        Glyph(kind = GlyphKind.Back, modifier = Modifier.size(ZoneuSize.icon))
      }
    },
    actions = actions,
    colors =
      TopAppBarDefaults.topAppBarColors(
        containerColor = Color.Transparent,
        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
      ),
    windowInsets = WindowInsets(0),
  )
}

@Composable
internal fun BigTitle(title: String, subtitle: String? = null) {
  // 不在这里加水平内边距：调用方（ProvidersScreen 的滚动列）已经有了 16dp，
  // 再加一层会让标题比搜索框和卡片多缩进 16dp。
  Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
    Text(
      text = title,
      style = MaterialTheme.typography.headlineLargeEmphasized,
    )
    if (subtitle != null) {
      Spacer(Modifier.height(6.dp))
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
internal fun InfoBanner(text: String, modifier: Modifier = Modifier) {
  AppBanner(text = text, tone = AppBanner.Tone.Info, modifier = modifier)
}

/** 提供商详情页的三个页签。 */
internal enum class DetailTab(val label: String, val glyph: GlyphKind) {
  Config("配置", GlyphKind.Sliders),
  Models("模型", GlyphKind.Box),
  Advanced("高级设置", GlyphKind.Cog),
}

/** 底部页签条：选中的一项带一层淡色药丸底，与参考图一致。 */
@Composable
internal fun DetailTabBar(selected: DetailTab, onSelect: (DetailTab) -> Unit) {
  SingleChoiceSegmentedButtonRow(
    modifier = Modifier.fillMaxWidth().padding(horizontal = ZoneuSpacing.lg, vertical = ZoneuSpacing.sm),
  ) {
    DetailTab.entries.forEachIndexed { index, tab ->
      SegmentedButton(
        selected = tab == selected,
        onClick = { onSelect(tab) },
        shape = SegmentedButtonDefaults.itemShape(index = index, count = DetailTab.entries.size),
        icon = {
          Glyph(
            kind = tab.glyph,
            modifier = Modifier.size(ZoneuSize.iconSmall),
          )
        },
        label = { Text(text = tab.label, style = MaterialTheme.typography.labelMedium) },
      )
    }
  }
}

/** 提供商摘要：设置首页与「模型与服务」页共用，两处不能说出不同的状态。 */
internal fun providerSubtitle(state: SettingsViewModel.UiState): String {
  val name = state.providerName ?: return "还没有配置 AI 模型"
  val keyHint = if (state.configured) "" else " · 需要补充 API Key"
  val model = state.providerModel.ifBlank { "尚未选择模型" }
  return "$name · $model$keyHint"
}
