package com.zoneu.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zoneu.app.theme.zoneuColors

/**
 * 常驻反馈条：说明 / 成功 / 警告 / 错误。
 *
 * 深浅色各一套底色，别让深色模式下出现一块亮绿；右侧可以挂一个恢复动作
 * （「去开启」「重试」），横幅只报状态、不给出口的话用户只能自己找。
 */
object AppBanner {
  enum class Tone {
    Info,
    Success,
    Warning,
    Error,
  }

  @Composable
  operator fun invoke(
    text: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    action: (@Composable RowScope.() -> Unit)? = null,
  ) {
    val color = MaterialTheme.colorScheme
    val semantic = MaterialTheme.zoneuColors
    val (background, foreground) =
      when (tone) {
        Tone.Info -> color.secondaryContainer to color.onSecondaryContainer
        Tone.Success -> semantic.successContainer to semantic.onSuccessContainer
        Tone.Warning -> semantic.warningContainer to semantic.onWarningContainer
        Tone.Error -> color.errorContainer to color.onErrorContainer
      }
    Row(
      modifier =
        modifier
          .fillMaxWidth()
          .background(background, MaterialTheme.shapes.medium)
          .padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = foreground,
        modifier = Modifier.weight(1f, fill = action == null),
      )
      action?.invoke(this)
    }
  }
}
