package com.zoneu.app.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.zoneu.app.Characters
import com.zoneu.app.Main
import com.zoneu.app.Settings
import com.zoneu.app.WorldBooks
import com.zoneu.app.ui.settings.Glyph
import com.zoneu.app.ui.settings.GlyphKind

/** 底层导航的四个一级目的地。 */
internal enum class ZoneuTab(val label: String, val glyph: GlyphKind) {
  Chat("对话", GlyphKind.Chat),
  Characters("角色", GlyphKind.Person),
  WorldBooks("世界书", GlyphKind.Book),
  Settings("设置", GlyphKind.Cog),
}

/**
 * 每个页签一条独立返回栈。
 *
 * 共用一个栈时，从设置深处切回对话再切回来，用户会发现自己停在设置首页；
 * 四条栈让每个页签保留自己的历史，切页签是「换一摞纸」，不是「清空重来」。
 * 状态本身由 `rememberNavBackStack` 保存，旋转后仍在。
 */
@Stable
internal class ZoneuTabs(
  val chat: NavBackStack<NavKey>,
  val characters: NavBackStack<NavKey>,
  val worldBooks: NavBackStack<NavKey>,
  val settings: NavBackStack<NavKey>,
) {
  var current by mutableStateOf(ZoneuTab.Chat)
    private set

  val stack: NavBackStack<NavKey>
    get() =
      when (current) {
        ZoneuTab.Chat -> chat
        ZoneuTab.Characters -> characters
        ZoneuTab.WorldBooks -> worldBooks
        ZoneuTab.Settings -> settings
      }

  fun stackOf(tab: ZoneuTab): NavBackStack<NavKey> =
    when (tab) {
      ZoneuTab.Chat -> chat
      ZoneuTab.Characters -> characters
      ZoneuTab.WorldBooks -> worldBooks
      ZoneuTab.Settings -> settings
    }

  /** 点当前页签 = 回到该页签的根；点别的页签 = 保留它的历史。 */
  fun select(tab: ZoneuTab) {
    if (tab == current) popToRoot(tab) else current = tab
  }

  fun popToRoot(tab: ZoneuTab) {
    val target = stackOf(tab)
    while (target.size > 1) target.removeLastOrNull()
  }

  /** 从任意页面跳到某页签的具体目的地（例如工具回执打开一本世界书）。 */
  fun push(tab: ZoneuTab, key: NavKey) {
    current = tab
    stackOf(tab).add(key)
  }

  /** 返回：先退栈；退到底后再从非对话页签回对话；最后才交给系统结束 Activity。 */
  fun back(): Boolean {
    val target = stack
    if (target.size > 1) {
      target.removeLastOrNull()
      return true
    }
    if (current != ZoneuTab.Chat) {
      current = ZoneuTab.Chat
      return true
    }
    return false
  }
}

/**
 * 底部导航。
 *
 * 键盘弹出时整条收起：聊天的输入框紧贴底部，再留一条 80dp 的栏会把消息区压掉一小半，
 * 而打字时用户也不需要切页签。`isImeVisible` 是系统级的，不依赖聊天页本地状态。
 */
@Composable
internal fun ZoneuBottomBar(
  current: ZoneuTab,
  onSelect: (ZoneuTab) -> Unit,
  modifier: Modifier = Modifier,
) {
  NavigationBar(
    modifier = modifier,
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
    tonalElevation = 0.dp,
  ) {
    ZoneuTab.entries.forEach { tab ->
      NavigationBarItem(
        selected = tab == current,
        onClick = { onSelect(tab) },
        icon = { Glyph(kind = tab.glyph, modifier = Modifier.size(24.dp)) },
        label = { Text(text = tab.label, style = MaterialTheme.typography.labelMedium) },
        colors =
          NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
            selectedTextColor = MaterialTheme.colorScheme.onSurface,
            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
          ),
      )
    }
  }
}

/** 键盘可见时把底栏收起来；动画用 Expressive 的 spatial spec。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ZoneuBottomBarHost(current: ZoneuTab, onSelect: (ZoneuTab) -> Unit, modifier: Modifier = Modifier) {
  val imeVisible = WindowInsets.isImeVisible
  AnimatedVisibility(
    visible = !imeVisible,
    enter = slideInVertically { it } + fadeIn(),
    exit = slideOutVertically { it } + fadeOut(),
    modifier = modifier,
  ) {
    ZoneuBottomBar(current = current, onSelect = onSelect)
  }
}
