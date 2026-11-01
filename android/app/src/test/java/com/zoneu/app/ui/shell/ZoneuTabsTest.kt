package com.zoneu.app.ui.shell

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.zoneu.app.CharacterDetail
import com.zoneu.app.Characters
import com.zoneu.app.Main
import com.zoneu.app.Settings
import com.zoneu.app.WorldBooks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneuTabsTest {
  private fun tabs(): ZoneuTabs =
    ZoneuTabs(
      chat = NavBackStack(Main),
      characters = NavBackStack(Characters),
      worldBooks = NavBackStack(WorldBooks()),
      settings = NavBackStack(Settings),
    )

  @Test
  fun `back pops the active stack first`() {
    val tabs = tabs()
    tabs.push(ZoneuTab.Settings, CharacterDetail("card"))
    assertTrue(tabs.back())
    assertEquals(1, tabs.settings.size)
    assertEquals(ZoneuTab.Settings, tabs.current)
  }

  @Test
  fun `back at a non-chat root returns to chat`() {
    val tabs = tabs()
    tabs.select(ZoneuTab.Characters)
    assertTrue(tabs.back())
    assertEquals(ZoneuTab.Chat, tabs.current)
  }

  @Test
  fun `back at the chat root leaves the app`() {
    val tabs = tabs()
    assertFalse(tabs.back())
  }

  @Test
  fun `selecting the current tab pops to its root`() {
    val tabs = tabs()
    tabs.push(ZoneuTab.WorldBooks, WorldBooks("book"))
    tabs.select(ZoneuTab.WorldBooks)
    assertEquals(1, tabs.worldBooks.size)
    assertEquals(WorldBooks(), tabs.worldBooks.first())
  }

  @Test
  fun `switching tabs keeps each tab history`() {
    val tabs = tabs()
    tabs.push(ZoneuTab.Settings, CharacterDetail("card"))
    tabs.select(ZoneuTab.Chat)
    tabs.select(ZoneuTab.Settings)
    assertEquals(ZoneuTab.Settings, tabs.current)
    assertEquals(2, tabs.settings.size)
  }
}
