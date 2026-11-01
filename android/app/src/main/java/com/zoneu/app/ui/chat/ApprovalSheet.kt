package com.zoneu.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zoneu.app.agent.ApprovalDecision
import com.zoneu.app.agent.ApprovalHub
import com.zoneu.app.agent.PendingApproval

/**
 * 审批面板：dsh 的 `approval/request` 到了，模型想执行一个需要用户点头的操作。
 *
 * 语义照抄 dsh（见 `docs/approvals.md`）：
 * - **只有一次性授权**（`allowed-once`）—— 没有「总是允许」，所以面板上不出现那个选项；
 * - **拒绝**（`rejected`）与**关闭/取消**（`cancelled`）都不授权，区别只在模型看到的措辞；
 * - 面板同时只显示一个请求（[ApprovalHub] 保证），第二个请求会被直接取消。
 *
 * 展示的 `displayReason` / `reason` 来自发起方，**可能包含用户内容**（收件人、主题等）；
 * 这里只渲染成纯文本，不写日志、不拼接成可点击的链接。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalSheet(pending: PendingApproval) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val detail = pending.displayReason["zh"] ?: pending.displayReason["en"] ?: pending.reason

  ModalBottomSheet(
    onDismissRequest = { ApprovalHub.dismiss() },
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
  ) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
      Text(
        "需要你确认",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(8.dp))
      // 显示中文动作名，不显示原始工具 id：用户要判断的是「它要做什么」，
      // 不是记住 `tap_node` 这种内部命名。具体的对象与参数来自 displayReason。
      Text(
        text = toolDisplayName(pending.toolName),
        style = MaterialTheme.typography.headlineSmallEmphasized,
      )
      Spacer(Modifier.height(8.dp))
      Text(
        text = detail?.takeIf { it.isNotBlank() } ?: "模型想替你执行一次需要确认的操作。",
        style = MaterialTheme.typography.bodyMedium,
      )
      Spacer(Modifier.height(10.dp))
      Text(
        "这是一次性授权，只对这一次操作有效。拒绝不会中断任务，模型会收到「未获允许」。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(12.dp))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { ApprovalHub.decide(ApprovalDecision.REJECT) }) { Text("拒绝") }
        Spacer(Modifier.width(8.dp))
        Button(onClick = { ApprovalHub.decide(ApprovalDecision.ALLOW_ONCE) }) { Text("允许一次") }
      }
    }
  }
}
