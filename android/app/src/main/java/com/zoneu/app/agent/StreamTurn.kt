package com.zoneu.app.agent

import com.zoneu.app.data.db.MessageStatus

/**
 * 一轮响应要用到的宿主动作（由 `ChatViewModel` 提供）。
 *
 * 用一包函数而不是接口：接口的实现会计进调用方的方法数（那个类已经贴着 detekt 阈值），
 * 而这里要的只是「九个动作的引用」。打包还有一个好处 —— 加一个动作时编译器会提醒
 * 每个构造点补上，不会像可选参数那样漏传后静默失效。
 */
data class TurnHost(
  val appendText: (id: String, text: String, replace: Boolean) -> Unit,
  val markReasoning: (id: String, text: String) -> Unit,
  val markThinkingStarted: (id: String, at: Long) -> Unit,
  val markThinkingMs: (id: String, ms: Long) -> Unit,
  val flush: suspend (id: String, content: StringBuilder, reasoning: StringBuilder, status: MessageStatus, error: String?) -> Unit,
  val finishStream: (id: String, error: String?) -> Unit,
  /**
   * 模型问完、用户答完：把答案落成一条用户消息，并开一条新的助手消息接着写，返回新消息 id。
   * 落库失败返回 null（这一轮照常继续，只是答案不单独成条）。
   */
  val splitForAnswer: suspend (oldId: String, answer: String) -> String?,
  val startToolCall: suspend (messageId: String, sessionId: String, event: AgentStreamEvent.ToolCallStarted) -> Unit,
  val finishToolCall: suspend (messageId: String, event: AgentStreamEvent.ToolCallFinished) -> Unit,
  val finishRunningToolCalls: suspend (messageId: String) -> Unit,
  val timing: ChatTiming,
  /** 界面刷新的最小间隔（毫秒）：逐帧重算 Markdown 太贵。 */
  val uiFlushMs: Long,
)

/**
 * 一轮响应的可变状态与收帧逻辑。
 *
 * 抽成类不是为了复用，而是为了**把 `when` 的每个分支收成一个小函数** —— 七个分支挤在
 * 一个函数里，改一处要读一百行，复杂度也压不下来。这里的状态只活在一轮之内。
 *
 * 从 `ChatViewModel` 里搬出来（它当时贴着 LargeClass 阈值）：对内只依赖 [TurnHost] 这一包
 * 动作，搬走之后那个类不再随「多一种流式事件」而变长。
 */
class StreamTurn(
  private val sessionId: String,
  draftId: String,
  base: String = "",
  private val host: TurnHost,
) {
  val content = StringBuilder(base)
  val reasoning = StringBuilder()
  var failure: Throwable? = null
  var stopped = false
  var completed = false
    private set
  private var statusOnly = false
  private var baseLength = base.length

  /** 本轮的 draft 会在「用户回答提问」处换一条：答案要单独成一条用户消息。 */
  private var draftId = draftId

  /** callId → 调用开始帧（名字与参数）：收尾时要靠它认出是哪个工具。 */
  private val startedCalls = mutableMapOf<String, AgentStreamEvent.ToolCallStarted>()
  private var receivedAnswer = false
  val hasAdoptedAnswer: Boolean get() = receivedAnswer && !statusOnly && content.length > baseLength

  // 增量合并：UI 最多每 uiFlushMs 刷一次；DB 那边另有 timing.flushIntervalMs 的节流。
  private var lastFlush = host.timing.clock()
  private var lastUiFlush = host.timing.clock()

  // 本步起点：StepFinal 只替换**这一步**追加的文本（多步回答的前几步不能被吃掉）。
  // 续写时从已有正文的末尾起算 —— 否则一次 StepFinal 就会把前半段吃掉。
  private var stepStart = content.length
  private var reasoningStepStart = 0

  // 思考时长：第一次推理增量起算，第一条正文到达时结算（只活在这一轮，不入库）
  private var thinkingStartedAt: Long? = null

  suspend fun handle(event: AgentStreamEvent) {
    when (event) {
      is AgentStreamEvent.ReasoningDelta -> onReasoning(event)

      is AgentStreamEvent.Status -> onStatus(event.text)

      is AgentStreamEvent.ToolCallStarted -> {
        event.callId?.let { startedCalls[it] = event }
        host.startToolCall(draftId, sessionId, event)
      }

      is AgentStreamEvent.ToolCallFinished -> {
        host.finishToolCall(draftId, event)
        splitAfterAnswer(event)
      }

      is AgentStreamEvent.TextDelta -> onTextDelta(event)

      is AgentStreamEvent.StepFinal -> onStepFinal(event)

      AgentStreamEvent.Completed -> completed = true
    }
  }

  private suspend fun onReasoning(event: AgentStreamEvent.ReasoningDelta) {
    if (thinkingStartedAt == null) {
      val started = host.timing.clock()
      thinkingStartedAt = started
      // 界面据此把「思考中 1.2 秒」实时往上走；结算时换成 thinkingMs。
      host.markThinkingStarted(draftId, started)
    }
    if (event.startsStep) reasoningStepStart = reasoning.length
    reasoning.append(event.text)
    val now = host.timing.clock()
    if (event.startsStep || now - lastUiFlush >= host.uiFlushMs) {
      lastUiFlush = now
      host.markReasoning(draftId, reasoning.toString())
    }
    throttleFlush(now)
  }

  /**
   * `ask_user_question` 收尾：用户答了什么，要在聊天里**看得见**。
   *
   * 答案本来是工具结果，混在助手这条消息里就成了一行折叠的过程；这里把当前这条助手消息
   * 落定，插一条用户消息（答案），再开一条新的助手消息接着写后面的话。
   * 解析不出答案（结果不是预期形状）就什么都不做 —— 工具本身已经成功，不因为展示失败重跑。
   */
  private suspend fun splitAfterAnswer(event: AgentStreamEvent.ToolCallFinished) {
    if (!event.ok) return
    val call = event.callId?.let { startedCalls.remove(it) } ?: return
    if (call.name != "ask_user_question") return
    val answer = UserQuestionAnswers.displayText(call.arguments, event.result) ?: return
    // 这一段只有占位文案（「正在启动 dsh 运行时…」）时不能把它固化进聊天记录：
    // 封存之后不再有增量来覆盖它，它会一直挂着。清空正文，只留过程块里的提问卡。
    if (statusOnly) {
      content.clear()
      statusOnly = false
      host.appendText(draftId, "", true)
    }
    // 增量上屏是节流的：收尾前先把缓冲对齐到界面，否则最后那句话会随缓冲一起被清掉。
    if (content.isNotEmpty()) host.appendText(draftId, content.toString(), true)
    host.flush(draftId, content, reasoning, MessageStatus.Complete, null)
    val next = host.splitForAnswer(draftId, answer) ?: return
    draftId = next
    content.clear()
    reasoning.clear()
    stepStart = 0
    reasoningStepStart = 0
    baseLength = 0
    statusOnly = false
  }

  private suspend fun onStatus(text: String) {
    // 占位文案只在这一步还什么都没说时才上屏。模型常「先说一句，再调工具」，
    // 旧实现会把它整段抹成「正在调用 xxx…」，正文从界面和库里一起消失。
    // 续写也不能盖掉已有的前半段，所以 base 非空时同样跳过。
    if (baseLength > 0 || content.length > baseLength) return
    statusOnly = true
    content.clear()
    content.append(text)
    host.appendText(draftId, text, true)
  }

  private suspend fun onTextDelta(event: AgentStreamEvent.TextDelta) {
    if (event.text.isNotBlank()) receivedAnswer = true
    settleThinking()
    // 第一条正文到达时清掉状态文案：否则「正在调用 xxx…」会留在气泡里。
    if (statusOnly) {
      statusOnly = false
      content.clear()
    }
    if (event.startsStep) stepStart = content.length
    content.append(event.text)
    val now = host.timing.clock()
    if (event.startsStep || now - lastUiFlush >= host.uiFlushMs) {
      lastUiFlush = now
      // UI 镜像直接取缓冲快照：坐标与缓冲永远一致，不必推理增量拼接的边界。
      host.appendText(draftId, content.toString(), true)
    }
    throttleFlush(now)
  }

  private suspend fun onStepFinal(event: AgentStreamEvent.StepFinal) {
    settleThinking()
    // 增量只是先给眼睛看的；这一步的成品到了就**只替换本步**追加的部分。
    event.reasoning?.let { full ->
      reasoning.setLength(reasoningStepStart.coerceAtMost(reasoning.length))
      reasoning.append(full)
      host.markReasoning(draftId, reasoning.toString())
    }
    event.text?.let { full ->
      if (full.isNotBlank()) receivedAnswer = true
      content.setLength(stepStart.coerceAtMost(content.length))
      content.append(full)
      host.appendText(draftId, content.toString(), true)
    }
    lastUiFlush = host.timing.clock()
    lastFlush = lastUiFlush
    host.flush(draftId, content, reasoning, MessageStatus.Streaming, null)
  }

  /** 节流落库：增量可能每帧都来，200ms 一次已足够「进程被杀也不丢太多」。 */
  private suspend fun throttleFlush(now: Long) {
    if (now - lastFlush < host.timing.flushIntervalMs) return
    lastFlush = now
    host.flush(draftId, content, reasoning, MessageStatus.Streaming, null)
  }

  private fun settleThinking() {
    val started = thinkingStartedAt ?: return
    thinkingStartedAt = null
    host.markThinkingMs(draftId, (host.timing.clock() - started).coerceAtLeast(0))
  }

  /** 收尾：定状态、把最后的缓冲对齐到界面与库，再把还挂着的工具卡落成中断态。 */
  suspend fun finish() {
    val status =
      when {
        stopped -> MessageStatus.Stopped
        failure != null -> MessageStatus.Error
        else -> MessageStatus.Complete
      }
    val error = failure?.let { it.message ?: it.javaClass.simpleName }
    if (statusOnly && (failure != null || stopped)) {
      content.clear()
      host.appendText(draftId, "", true)
    }
    // 收尾前把 UI 与本地缓冲对齐：增量是合并刷新的（≤50ms），最后几个字可能还没上屏
    if (content.isNotEmpty()) host.appendText(draftId, content.toString(), true)
    if (reasoning.isNotEmpty()) host.markReasoning(draftId, reasoning.toString())
    host.flush(draftId, content, reasoning, status, error)
    // 这一轮没跑完就被停/出错时，把还挂在「执行中」的工具卡落成中断态。
    if (stopped || failure != null) host.finishRunningToolCalls(draftId)
    // 思考中被打断也要结算时长，否则界面会永远停在「思考中」。
    settleThinking()
    host.finishStream(draftId, error)
  }
}
