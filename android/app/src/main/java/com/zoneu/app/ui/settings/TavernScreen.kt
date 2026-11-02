package com.zoneu.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zoneu.app.ui.components.zoneuPageInsets

/**
 * 酒馆：角色扮演的两个子页。
 *
 * 角色卡是主体；世界书是附加设定 —— 角色卡通常自带世界书，这里补的是「卡里没有、
 * 但希望角色知道」的那部分。两者都不影响日常助手，所以入口收在设置里，不占一级导航。
 */
@Composable
fun TavernScreen(
  onBack: () -> Unit,
  onOpenCharacters: () -> Unit,
  onOpenWorldBooks: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(modifier = modifier.fillMaxSize(), color = settingsPageColor()) {
    Column(Modifier.fillMaxSize().zoneuPageInsets()) {
      SettingsHeader(title = "酒馆", onBack = onBack)
      Column(
        modifier =
          Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
      ) {
        Text(
          text = "角色扮演相关的一切都在这里，日常助手不受影响。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(start = 4.dp, bottom = 12.dp),
        )
        SettingsGroup {
          SettingsRow(
            glyph = GlyphKind.Person,
            title = "角色卡",
            subtitle = "导入、编辑，选一张开始扮演",
            onClick = onOpenCharacters,
          ) { ChevronRight() }
          SettingsDivider()
          SettingsRow(
            glyph = GlyphKind.Book,
            title = "世界书",
            subtitle = "角色卡自带设定之外，再补一份给角色看",
            onClick = onOpenWorldBooks,
          ) { ChevronRight() }
        }
      }
    }
  }
}
