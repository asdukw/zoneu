package com.zoneu.app.ui.settings

import androidx.compose.runtime.Composable

@Composable
internal fun EntertainmentSection(onOpenTavern: () -> Unit) {
  SectionLabel("酒馆")
  SettingsGroup {
    // 角色卡与世界书都是酒馆的子页：一个是主体，一个是附加设定，不跟日常助手混在一起。
    SettingsRow(
      glyph = GlyphKind.Sparkles,
      title = "酒馆",
      subtitle = "角色卡与额外世界书，仅用于角色扮演",
      onClick = onOpenTavern,
    ) { ChevronRight() }
  }
}
