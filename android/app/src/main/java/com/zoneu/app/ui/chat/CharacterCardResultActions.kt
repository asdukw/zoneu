package com.zoneu.app.ui.chat

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zoneu.app.agent.CharacterCardOperations
import com.zoneu.app.agent.CharacterStore
import com.zoneu.app.agent.ToolCallState
import com.zoneu.app.agent.ToolCallView
import com.zoneu.app.ui.settings.Glyph
import com.zoneu.app.ui.settings.GlyphKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** 只解析真实工具成功回执，不从模型正文或 Markdown 识别内部导航。 */
internal fun characterCardResultId(call: ToolCallView): String? {
  val supported = call.name in setOf("create_character_card", "import_character_card_url", "update_character_card")
  if (!supported || call.state != ToolCallState.Ok) return null
  val result = runCatching { Json.parseToJsonElement(call.result.orEmpty()) as? JsonObject }.getOrNull() ?: return null
  val kind = (result["kind"] as? JsonPrimitive)?.content
  val ok = (result["ok"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
  if (kind != CharacterCardOperations.RESULT_KIND || ok != true) return null
  return (result["characterId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    ?.takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")) }
}

@Composable
internal fun CharacterCardResultActions(calls: List<ToolCallView>, onOpen: (String) -> Unit) {
  val context = LocalContext.current
  calls.mapNotNull(::characterCardResultId).distinct().forEach { id ->
    val card by produceState<com.zoneu.app.agent.CharacterCard?>(null, id, calls) {
      value = withContext(Dispatchers.IO) { runCatching { CharacterStore.byId(context, id) }.getOrNull() }
    }
    ChatResultAction(
      label = card?.let { "查看角色卡 · ${it.name}" } ?: "查看角色卡（已删除或不存在）",
      glyph = GlyphKind.Person,
      onClick = {
        if (runCatching { CharacterStore.byId(context, id) }.getOrNull() == null) {
          Toast.makeText(context, "这张角色卡已删除、不存在或暂时无法读取", Toast.LENGTH_SHORT).show()
        } else {
          onOpen(id)
        }
      },
    )
  }
}

/** 工具成功后的一张结果入口：比一行 TextButton 更像「这里有一份东西可以打开」。 */
@Composable
internal fun ChatResultAction(label: String, glyph: GlyphKind, onClick: () -> Unit) {
  Surface(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    shape = MaterialTheme.shapes.small,
    color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Glyph(kind = glyph, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(8.dp))
      Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.weight(1f),
      )
      Glyph(
        kind = GlyphKind.Back,
        modifier = Modifier.size(16.dp).rotate(180f),
      )
    }
  }
}
