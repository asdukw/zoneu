package com.zoneu.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zoneu.app.theme.ZoneuSize
import com.zoneu.app.theme.ZoneuSpacing
import com.zoneu.app.theme.zoneuShapes
import com.zoneu.app.ui.settings.Glyph
import com.zoneu.app.ui.settings.GlyphKind

/**
 * 设置/次级页面的统一骨架：Scaffold + TopAppBar + SnackbarHost。
 *
 * 之前 18 个页面各写一份 `Surface + Column + safeDrawingPadding + 48dp 返回圆钮`，
 * 结果 inset、返回文案和标题字号各不相同。这里只留一个入口；页面内容通过
 * [content] 拿到 Scaffold 已经扣掉系统栏和顶栏的 [PaddingValues]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZoneuPageScaffold(
  title: String,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  onBack: (() -> Unit)? = null,
  actions: @Composable RowScope.() -> Unit = {},
  snackbarHost: @Composable () -> Unit = {},
  bottomBar: @Composable () -> Unit = {},
  content: @Composable (PaddingValues) -> Unit,
) {
  Scaffold(
    modifier = modifier,
    containerColor = MaterialTheme.colorScheme.background,
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(
              text = title,
              style = MaterialTheme.typography.titleLargeEmphasized,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
              Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        },
        navigationIcon = {
          if (onBack != null) {
            IconButton(onClick = onBack) {
              Glyph(kind = GlyphKind.Back, modifier = Modifier.size(ZoneuSize.icon))
            }
          }
        },
        actions = actions,
        colors =
          TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
          ),
      )
    },
    snackbarHost = snackbarHost,
    bottomBar = bottomBar,
    content = content,
  )
}

/** 一条设置分组上方的说明文字。 */
@Composable
fun ZoneuSectionTitle(text: String, modifier: Modifier = Modifier) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelLargeEmphasized,
    color = MaterialTheme.colorScheme.primary,
    modifier = modifier.padding(start = ZoneuSpacing.xs, bottom = ZoneuSpacing.sm),
  )
}

/** 分组卡片：比页面底色高一档的容器，圆角走语义 token。 */
@Composable
fun ZoneuCard(
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  Card(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.zoneuShapes.card,
    colors =
      CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
      ),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
  ) {
    Column(content = content)
  }
}

/** 空态：图标（可为空）+ 标题 + 说明 + 可选动作。用于设置列表、世界书、任务记录。 */
@Composable
internal fun ZoneuEmptyState(
  title: String,
  modifier: Modifier = Modifier,
  body: String? = null,
  glyph: GlyphKind? = null,
  action: (@Composable () -> Unit)? = null,
) {
  Column(
    modifier = modifier.fillMaxWidth().padding(horizontal = ZoneuSpacing.xl, vertical = ZoneuSpacing.xxl),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(ZoneuSpacing.sm),
  ) {
    if (glyph != null) {
      Glyph(
        kind = glyph,
        modifier = Modifier.size(ZoneuSize.iconLarge),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Text(text = title, style = MaterialTheme.typography.titleMediumEmphasized)
    if (body != null) {
      Text(
        text = body,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
      )
    }
    action?.invoke()
  }
}

/**
 * 页面内边距：只让出状态栏/刘海和左右两侧。
 *
 * 底部由应用外壳的 NavigationBar 或输入框的 imePadding 负责；页面再各让一次
 * navigation bar inset，就会在底栏上方多出一条 24dp 的空带。
 */
@Composable
fun Modifier.zoneuPageInsets(): Modifier =
  windowInsetsPadding(
    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
  )
