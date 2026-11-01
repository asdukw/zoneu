package com.zoneu.app

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.zoneu.app.ui.chat.ChatScreen
import com.zoneu.app.ui.chat.LocalWorldNavigation
import com.zoneu.app.ui.chat.WorldNavigation
import com.zoneu.app.ui.settings.AboutScreen
import com.zoneu.app.ui.settings.CharacterDetailScreen
import com.zoneu.app.ui.settings.CharacterScreen
import com.zoneu.app.ui.settings.ConnectorsScreen
import com.zoneu.app.ui.settings.ExecutionSettingsScreen
import com.zoneu.app.ui.settings.GmailConnectorScreen
import com.zoneu.app.ui.settings.ModelServicesScreen
import com.zoneu.app.ui.settings.PersonaScreen
import com.zoneu.app.ui.settings.ProviderDetailScreen
import com.zoneu.app.ui.settings.ProvidersScreen
import com.zoneu.app.ui.settings.ScheduledTaskRunScreen
import com.zoneu.app.ui.settings.ScheduledTaskScreen
import com.zoneu.app.ui.settings.SettingsScreen
import com.zoneu.app.ui.settings.VoiceScreen
import com.zoneu.app.ui.settings.WorldBindingScreen
import com.zoneu.app.ui.settings.WorldBookScreen
import com.zoneu.app.ui.shell.ZoneuBottomBarHost
import com.zoneu.app.ui.shell.ZoneuTab
import com.zoneu.app.ui.shell.ZoneuTabs

/**
 * 应用外壳：底部四个一级目的地 + 每个页签独立的返回栈。
 *
 * 页面本身仍然只认识 `onBack` / `onOpenX` 回调，不知道底部栏存在；
 * 要换页签时由这里把回调接到 [ZoneuTabs.select]。
 */
@Composable
fun MainNavigation() {
  val chatStack = rememberNavBackStack(Main)
  val characterStack = rememberNavBackStack(Characters)
  val worldStack = rememberNavBackStack(WorldBooks())
  val settingsStack = rememberNavBackStack(Settings)
  val tabs =
    remember(chatStack, characterStack, worldStack, settingsStack) {
      ZoneuTabs(
        chat = chatStack,
        characters = characterStack,
        worldBooks = worldStack,
        settings = settingsStack,
      )
    }
  val activity = LocalActivity.current

  CompositionLocalProvider(
    LocalWorldNavigation provides
      WorldNavigation(
        openLibrary = {
          tabs.popToRoot(ZoneuTab.WorldBooks)
          tabs.select(ZoneuTab.WorldBooks)
        },
        openBook = { id -> tabs.push(ZoneuTab.WorldBooks, WorldBooks(id)) },
        openBindings = { scope, id -> tabs.push(ZoneuTab.WorldBooks, WorldBindings(scope, id)) },
      ),
  ) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
      NavDisplay(
        backStack = tabs.stack,
        modifier = Modifier.weight(1f),
        onBack = { if (!tabs.back()) activity?.finish() },
        // 方向感：新页面从右侧轻轻推进，返回时反向。默认的整屏淡入在长列表上像闪一下。
        transitionSpec = {
          (slideInHorizontally { it / 8 } + fadeIn()) togetherWith
            (slideOutHorizontally { -it / 12 } + fadeOut())
        },
        popTransitionSpec = {
          (slideInHorizontally { -it / 12 } + fadeIn()) togetherWith
            (slideOutHorizontally { it / 8 } + fadeOut())
        },
        predictivePopTransitionSpec = { (fadeIn() + slideInHorizontally { -it / 12 }) togetherWith fadeOut() },
        entryProvider =
          entryProvider {
            entry<Main> {
              ChatScreen(
                onOpenSettings = { tabs.select(ZoneuTab.Settings) },
                // 酒馆侧栏的入口：直接进角色库，不必绕设置。
                onOpenCharacters = { tabs.select(ZoneuTab.Characters) },
                onOpenCharacterDetail = { id -> tabs.push(ZoneuTab.Chat, CharacterDetail(id)) },
              )
            }
            settingsEntries(tabs)
            worldEntries(tabs)
            scheduleEntries(tabs)
            tavernEntries(tabs)
          },
      )
      ZoneuBottomBarHost(current = tabs.current, onSelect = { tab -> tabs.select(tab) })
    }
  }
}

private fun EntryProviderScope<NavKey>.settingsEntries(tabs: ZoneuTabs) {
  entry<Settings> {
    SettingsScreen(
      onOpenModelServices = { tabs.push(ZoneuTab.Settings, ModelServices) },
      onOpenExecution = { tabs.push(ZoneuTab.Settings, ExecutionSettings) },
      onOpenConnectors = { tabs.push(ZoneuTab.Settings, Connectors) },
      onOpenVoice = { tabs.push(ZoneuTab.Settings, Voice) },
      onOpenScheduledTasks = { tabs.push(ZoneuTab.Settings, ScheduledTasks) },
      onOpenCharacters = { tabs.select(ZoneuTab.Characters) },
      onOpenAbout = { tabs.push(ZoneuTab.Settings, About) },
    )
  }
  entry<ModelServices> {
    ModelServicesScreen(
      onBack = { tabs.back() },
      onOpenProviders = { tabs.push(ZoneuTab.Settings, Providers) },
    )
  }
  entry<ExecutionSettings> {
    ExecutionSettingsScreen(onBack = { tabs.back() })
  }
  entry<About> { AboutScreen(onBack = { tabs.back() }) }
  entry<Providers> {
    ProvidersScreen(
      onBack = { tabs.back() },
      onOpenProvider = { id -> tabs.push(ZoneuTab.Settings, ProviderDetail(id)) },
    )
  }
  entry<Connectors> {
    ConnectorsScreen(
      onBack = { tabs.back() },
      onOpenGmail = { tabs.push(ZoneuTab.Settings, GmailConnector) },
    )
  }
  entry<GmailConnector> {
    GmailConnectorScreen(onBack = { tabs.back() })
  }
  entry<ProviderDetail> { key ->
    ProviderDetailScreen(
      providerId = key.providerId,
      onBack = { tabs.back() },
    )
  }
  entry<Voice> {
    VoiceScreen(onBack = { tabs.back() })
  }
}

private fun EntryProviderScope<NavKey>.tavernEntries(tabs: ZoneuTabs) {
  entry<Persona> {
    PersonaScreen(onBack = { tabs.back() })
  }
  entry<Characters> {
    // 开始对话这件事交给详情页：点一行先看卡在演什么，再决定要不要开。
    CharacterScreen(
      onBack = { tabs.back() },
      onOpenDetail = { id -> tabs.push(ZoneuTab.Characters, CharacterDetail(id)) },
      root = true,
    )
  }
  entry<CharacterDetail> { key ->
    CharacterDetailScreen(
      characterId = key.characterId,
      onBack = { tabs.back() },
      onStartChat = {
        // 从角色库进来时对话栈本来就在根上；从聊天里的工具卡进来时再把它退干净。
        tabs.popToRoot(ZoneuTab.Chat)
        tabs.select(ZoneuTab.Chat)
      },
    )
  }
}

private fun EntryProviderScope<NavKey>.worldEntries(tabs: ZoneuTabs) {
  entry<WorldBooks> { key ->
    WorldBookScreen(onBack = { tabs.back() }, initialBookId = key.bookId)
  }
  entry<WorldBindings> { key ->
    WorldBindingScreen(key.scope, key.targetId, onBack = { tabs.back() })
  }
}

private fun EntryProviderScope<NavKey>.scheduleEntries(tabs: ZoneuTabs) {
  entry<ScheduledTasks> {
    ScheduledTaskScreen(
      onBack = { tabs.back() },
      onOpenRuns = { id -> tabs.push(ZoneuTab.Settings, ScheduledTaskRuns(id)) },
    )
  }
  entry<ScheduledTaskRuns> { key ->
    ScheduledTaskRunScreen(taskId = key.taskId, onBack = { tabs.back() })
  }
}
