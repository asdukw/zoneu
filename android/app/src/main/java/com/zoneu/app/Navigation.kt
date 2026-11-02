package com.zoneu.app

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.zoneu.app.ui.settings.TavernScreen
import com.zoneu.app.ui.settings.VoiceScreen
import com.zoneu.app.ui.settings.WorldBindingScreen
import com.zoneu.app.ui.settings.WorldBookScreen

/**
 * 单栈导航：聊天是根，其余页面都从它推进来。
 *
 * 不再有底部页签 —— 角色与世界书是低频入口，挂在设置里的「角色与世界」一项下面，
 * 世界书更是角色的附加设定（角色卡自带世界书，额外的才是这里新增的）。
 */
@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(Main)
  val activity = LocalActivity.current

  CompositionLocalProvider(
    LocalWorldNavigation provides
      WorldNavigation(
        openLibrary = { backStack.add(WorldBooks()) },
        openBook = { id -> backStack.add(WorldBooks(id)) },
        openBindings = { scope, id -> backStack.add(WorldBindings(scope, id)) },
      ),
  ) {
    NavDisplay(
      backStack = backStack,
      onBack = { if (backStack.size > 1) backStack.removeLastOrNull() else activity?.finish() },
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
              onOpenSettings = { backStack.add(Settings) },
              // 酒馆侧栏的入口：直接进角色库，不必绕设置。
              onOpenCharacters = { backStack.add(Characters) },
              onOpenCharacterDetail = { id -> backStack.add(CharacterDetail(id)) },
            )
          }
          settingsEntries(backStack)
          worldEntries(backStack)
          scheduleEntries(backStack)
          tavernEntries(backStack)
        },
    )
  }
}

private fun EntryProviderScope<NavKey>.settingsEntries(backStack: MutableList<NavKey>) {
  entry<Settings> {
    SettingsScreen(
      onBack = { backStack.removeLastOrNull() },
      onOpenModelServices = { backStack.add(ModelServices) },
      onOpenExecution = { backStack.add(ExecutionSettings) },
      onOpenConnectors = { backStack.add(Connectors) },
      onOpenVoice = { backStack.add(Voice) },
      onOpenScheduledTasks = { backStack.add(ScheduledTasks) },
      onOpenTavern = { backStack.add(Tavern) },
      onOpenAbout = { backStack.add(About) },
    )
  }
  entry<ModelServices> {
    ModelServicesScreen(
      onBack = { backStack.removeLastOrNull() },
      onOpenProviders = { backStack.add(Providers) },
    )
  }
  entry<ExecutionSettings> {
    ExecutionSettingsScreen(onBack = { backStack.removeLastOrNull() })
  }
  entry<About> { AboutScreen(onBack = { backStack.removeLastOrNull() }) }
  entry<Providers> {
    ProvidersScreen(
      onBack = { backStack.removeLastOrNull() },
      onOpenProvider = { id -> backStack.add(ProviderDetail(id)) },
    )
  }
  entry<Connectors> {
    ConnectorsScreen(
      onBack = { backStack.removeLastOrNull() },
      onOpenGmail = { backStack.add(GmailConnector) },
    )
  }
  entry<GmailConnector> {
    GmailConnectorScreen(onBack = { backStack.removeLastOrNull() })
  }
  entry<ProviderDetail> { key ->
    ProviderDetailScreen(
      providerId = key.providerId,
      onBack = { backStack.removeLastOrNull() },
    )
  }
  entry<Voice> {
    VoiceScreen(onBack = { backStack.removeLastOrNull() })
  }
}

private fun EntryProviderScope<NavKey>.tavernEntries(backStack: MutableList<NavKey>) {
  entry<Tavern> {
    TavernScreen(
      onBack = { backStack.removeLastOrNull() },
      onOpenCharacters = { backStack.add(Characters) },
      onOpenWorldBooks = { backStack.add(WorldBooks()) },
    )
  }
  entry<Persona> {
    PersonaScreen(onBack = { backStack.removeLastOrNull() })
  }
  entry<Characters> {
    // 开始对话这件事交给详情页：点一行先看卡在演什么，再决定要不要开。
    CharacterScreen(
      onBack = { backStack.removeLastOrNull() },
      onOpenDetail = { id -> backStack.add(CharacterDetail(id)) },
    )
  }
  entry<CharacterDetail> { key ->
    CharacterDetailScreen(
      characterId = key.characterId,
      onBack = { backStack.removeLastOrNull() },
      onStartChat = { repeat(backStack.size - 1) { backStack.removeLastOrNull() } },
    )
  }
}

private fun EntryProviderScope<NavKey>.worldEntries(backStack: MutableList<NavKey>) {
  entry<WorldBooks> { key ->
    WorldBookScreen(onBack = { backStack.removeLastOrNull() }, initialBookId = key.bookId)
  }
  entry<WorldBindings> { key ->
    WorldBindingScreen(key.scope, key.targetId, onBack = { backStack.removeLastOrNull() })
  }
}

private fun EntryProviderScope<NavKey>.scheduleEntries(backStack: MutableList<NavKey>) {
  entry<ScheduledTasks> {
    ScheduledTaskScreen(
      onBack = { backStack.removeLastOrNull() },
      onOpenRuns = { id -> backStack.add(ScheduledTaskRuns(id)) },
    )
  }
  entry<ScheduledTaskRuns> { key ->
    ScheduledTaskRunScreen(taskId = key.taskId, onBack = { backStack.removeLastOrNull() })
  }
}
