package com.zoneu.app.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `ask_user_question` 的问答在聊天里的**显示文本**。
 *
 * 工具参数里是问题（`questions[]`，含选项标签），工具结果是 `{ answers: [] }`，只有 id
 * 与被选中的标签。两边都解析不了就返回 null —— 工具本身不受影响，只是不插那条用户消息。
 *
 * 形状见 [docs/user-questions.md](../../../../../../../../../../docs/user-questions.md)：
 * 单选时 `custom` 覆盖 `selected`，多选时补充；空答案来自「跳过」，不是没答。
 */
internal object UserQuestionAnswers {
  fun displayText(arguments: String, result: String?): String? {
    if (result.isNullOrBlank()) return null
    return runCatching {
      val questions = questionTexts(arguments)
      val answers = Json.parseToJsonElement(result).jsonObject["answers"].arrays()
      val lines =
        answers.mapNotNull { element ->
          val answer = element.jsonObject
          val id = answer["id"].text().orEmpty()
          val custom = answer["custom"].text()?.takeIf { it.isNotBlank() }
          val selected = answer["selected"].arrays().mapNotNull { it.text() }.filter { it.isNotBlank() }
          val value = custom ?: selected.joinToString("、").ifBlank { "跳过" }
          // 只有一个问题时，气泡里再复述一遍问题只是噪音。
          questions[id]?.takeIf { questions.size > 1 }?.let { "$it：$value" } ?: value
        }
      lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }.getOrNull()
  }

  private fun questionTexts(arguments: String): Map<String, String> =
    runCatching {
      Json
        .parseToJsonElement(arguments)
        .jsonObject["questions"]
        .arrays()
        .mapNotNull { element ->
          val question = element.jsonObject
          val id = question["id"].text() ?: return@mapNotNull null
          id to (question["question"].text() ?: "").trim()
        }.toMap()
    }.getOrDefault(emptyMap())

  private fun JsonElement?.arrays(): List<JsonElement> = (this as? JsonArray)?.toList().orEmpty()

  private fun JsonElement?.text(): String? = this?.jsonPrimitive?.contentOrNull
}
