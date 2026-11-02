package com.zoneu.app.ui.settings

import android.content.Context
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zoneu.app.agent.CharacterCard
import com.zoneu.app.agent.CharacterMemory
import com.zoneu.app.agent.CharacterPins
import com.zoneu.app.agent.CharacterStore
import com.zoneu.app.agent.SecondaryLogic
import com.zoneu.app.agent.WorldEntry
import com.zoneu.app.theme.ZoneuSpacing
import com.zoneu.app.theme.zoneuShapes
import com.zoneu.app.ui.chat.LocalWorldNavigation
import com.zoneu.app.ui.components.zoneuPageInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一张角色卡的详情页。
 *
 * 参考同类产品「作品页 → 新对话」的结构：侧栏那一行只够放名字，用户真正要判断的是
 * 「这张卡在演什么、开场是什么、世界设定有几条」—— 那些字段导入时都读过来了，之前却
 * 只显示了一行 tagline，等于白存。
 *
 * 导入卡可就地编辑并导出，归档字段的兼容范围通过报告说明。
 */
@Composable
fun CharacterDetailScreen(
  characterId: String,
  onBack: () -> Unit,
  onStartChat: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val appContext = LocalContext.current.applicationContext
  val viewModel: CharacterViewModel = viewModel { CharacterViewModel(appContext) }
  val state by viewModel.state.collectAsStateWithLifecycle()
  // 本地持一份，编辑保存后不用等列表刷新回来（列表是另一条流，回来晚一步就会闪一下旧文案）。
  var card by remember(characterId) { mutableStateOf<CharacterCard?>(null) }
  var memory by remember(characterId) { mutableStateOf("") }
  var loaded by remember(characterId) { mutableStateOf(false) }
  var editEpoch by remember(characterId) { mutableStateOf(0) }
  var pinned by remember(characterId) { mutableStateOf(false) }
  // 空白卡建完直接进编辑：它的第一个动作一定是「写名字」，没有别的可看。
  var editing by remember(characterId) { mutableStateOf(false) }

  // 读卡 / 读记忆 / 读收藏都是磁盘 IO，放进协程：在组合里同步读会让每次重组都摸一次主线程，
  // 内置卡多的时候进页会顿一下。
  LaunchedEffect(characterId) {
    val (restored, notes) =
      withContext(Dispatchers.IO) {
        runCatching { CharacterStore.byId(appContext, characterId) }.getOrNull() to
          CharacterMemory.notes(appContext, characterId)
      }
    card = restored
    memory = notes
    pinned = withContext(Dispatchers.IO) { CharacterPins.isPinned(appContext, characterId) }
    editing = restored?.name.isNullOrBlank()
    loaded = true
  }

  val current = card
  if (!loaded) {
    // 读卡期间先给进度，别闪一帧「角色不存在」。
    Surface(Modifier.fillMaxSize(), color = settingsPageColor()) {
      Column(
        modifier = Modifier.fillMaxSize().zoneuPageInsets(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        CircularProgressIndicator()
      }
    }
    return
  }
  if (editing && current != null) {
    CharacterEditor(
      card = current,
      memory = memory,
      editEpoch = editEpoch,
      appContext = appContext,
      viewModel = viewModel,
      onCard = { card = it },
      onMemory = { memory = it },
      onEditing = { editing = it },
      onBumpEpoch = { editEpoch++ },
    )
    return
  }

  CharacterDetailScreen(
    card = current,
    pinned = pinned,
    applying = state.applying,
    memory = memory,
    onBack = onBack,
    onStart = { id -> viewModel.start(id) { onStartChat() } },
    onTogglePin = { pinned = CharacterPins.toggle(appContext, characterId) },
    onEdit = { editing = true },
    onCardChanged = { card = it },
    onDelete = {
      if (current != null && viewModel.delete(current)) {
        CharacterPins.forget(appContext, characterId)
        onBack()
      } else {
        Toast.makeText(appContext, viewModel.state.value.message ?: "删除失败", Toast.LENGTH_LONG).show()
      }
    },
    modifier = modifier,
  )
}

/** 编辑态单独成一支：返回、重读、保存三条路径都在这里收口。 */
@Composable
private fun CharacterEditor(
  card: CharacterCard,
  memory: String,
  editEpoch: Int,
  appContext: Context,
  viewModel: CharacterViewModel,
  onCard: (CharacterCard?) -> Unit,
  onMemory: (String) -> Unit,
  onEditing: (Boolean) -> Unit,
  onBumpEpoch: () -> Unit,
) {
  key(editEpoch) {
    CharacterEditPage(
      card = card,
      memory = memory,
      onCancel = {
        onCard(runCatching { CharacterStore.byId(appContext, card.id) }.getOrNull())
        onEditing(false)
      },
      onReload = {
        val latest = runCatching { CharacterStore.readSnapshot(appContext, card.id) }
        if (latest.isSuccess) {
          onCard(latest.getOrNull()?.card)
          onBumpEpoch()
          if (latest.getOrNull()?.card == null) onEditing(false)
        } else {
          Toast.makeText(appContext, "读取失败，草稿仍保留", Toast.LENGTH_LONG).show()
        }
      },
      onSave = { updated, savedMemory ->
        val result = viewModel.save(updated, savedMemory)
        when (result) {
          is CharacterSaveResult.Saved -> {
            onCard(result.snapshot.card)
            onMemory(savedMemory)
            onEditing(false)
          }

          is CharacterSaveResult.MemoryFailed -> onCard(result.snapshot.card)

          is CharacterSaveResult.NotCommitted -> Unit
        }
        if (result !is CharacterSaveResult.Saved) {
          Toast.makeText(appContext, viewModel.state.value.message ?: "保存失败", Toast.LENGTH_LONG).show()
        }
        result
      },
    )
  }
}

@Composable
internal fun CharacterDetailScreen(
  card: CharacterCard?,
  pinned: Boolean,
  applying: Boolean,
  memory: String = "",
  onBack: () -> Unit,
  onStart: (String) -> Unit,
  onTogglePin: () -> Unit,
  onEdit: () -> Unit = {},
  onCardChanged: (CharacterCard) -> Unit = {},
  onDelete: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(modifier = modifier.fillMaxSize(), color = settingsPageColor()) {
    Column(Modifier.fillMaxSize().zoneuPageInsets()) {
      // 标题留给下面的大标题区：顶栏再写一遍名字，同一行信息就出现了两次。
      SettingsHeader(title = "", onBack = onBack)
      if (card == null) {
        MissingCard()
        return@Column
      }
      CharacterBody(
        card = card,
        pinned = pinned,
        applying = applying,
        memory = memory,
        onStart = { onStart(card.id) },
        onTogglePin = onTogglePin,
        onEdit = onEdit,
        onCardChanged = onCardChanged,
        onDelete = onDelete,
      )
    }
  }
}

/**
 * 从上到下的阅读顺序：这张卡是谁 → 开始演 → 它写了什么。
 *
 * 正文（人设 / 开场）默认只留五行：详情页是让人判断「要不要开这一场」的，
 * 不是阅读器；想读完点「展开全部」。导出、记忆、兼容报告这些低频项沉到底部。
 */
@Composable
private fun CharacterBody(
  card: CharacterCard,
  pinned: Boolean,
  applying: Boolean,
  memory: String,
  onStart: () -> Unit,
  onTogglePin: () -> Unit,
  onEdit: () -> Unit,
  onCardChanged: (CharacterCard) -> Unit,
  onDelete: () -> Unit,
) {
  Column(
    modifier =
      Modifier.fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = ZoneuSpacing.lg)
        .padding(bottom = ZoneuSpacing.xxl),
  ) {
    CharacterHero(card)
    Spacer(Modifier.height(ZoneuSpacing.xl))
    CharacterActions(
      pinned = pinned,
      applying = applying,
      editable = card.imported,
      onStart = onStart,
      onTogglePin = onTogglePin,
      onEdit = onEdit,
    )

    ProseSection("人设", card.description)
    ProseSection("性格与说话方式", card.personality)
    ProseSection("开场场景", card.scenario)
    ProseSection("开场白", card.firstMes)
    GreetingSection(card)
    WorldSection(card)

    MemoryCard(memory)
    SectionLabel("分享与备份")
    CardTransferGroup(card = card, onCardChanged = onCardChanged)
    Spacer(Modifier.height(ZoneuSpacing.sm))
    SharedWorldCardExport(card)

    AboutCard(card)
    Spacer(Modifier.height(ZoneuSpacing.sm))
    CardCompatibilityReport(card)
    DeleteCardSection(card, onDelete)
  }
}

/** 导入卡才可删；记忆与世界书绑定会一起消失，所以点击后先确认。 */
@Composable
private fun DeleteCardSection(card: CharacterCard, onDelete: () -> Unit) {
  if (!card.imported) return
  var confirming by remember { mutableStateOf(false) }
  Spacer(Modifier.height(ZoneuSpacing.xl))
  SettingsGroup {
    Column(
      modifier = Modifier.fillMaxWidth().clickable { confirming = true }.padding(16.dp),
    ) {
      Text(
        text = "删除这张卡",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.error,
      )
      Spacer(Modifier.height(ZoneuSpacing.xxs))
      Text(
        text = "角色记忆与共享世界书绑定会一起删除；聊过的会话会保留。此操作无法撤销。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
  if (confirming) {
    ConfirmDeleteCardDialog(
      name = card.name,
      onDismiss = { confirming = false },
      onConfirm = {
        confirming = false
        onDelete()
      },
    )
  }
}

/**
 * 世界设定：上面一行是「额外补的」，下面只读列出卡里自带的。
 *
 * 常驻的排前面（它们每轮都在，是这张卡的「底座」）。每条只显示前 160 字 ——
 * 详情页是让人判断「这张卡在演什么」的，不是阅读器；要改去编辑页。
 */
@Composable
private fun WorldSection(card: CharacterCard) {
  val worldNavigation = LocalWorldNavigation.current
  SectionLabel("世界设定")
  SettingsGroup {
    SettingsRow(
      glyph = GlyphKind.Book,
      title = "共享世界书",
      subtitle = "在卡自带的设定之外，再补一份给角色看",
      onClick = { worldNavigation.openBindings("character", card.id) },
    ) {
      ChevronRight()
    }
    if (card.world.isNotEmpty()) {
      SettingsDivider()
      Column(Modifier.padding(16.dp)) {
        card.world
          .sortedByDescending { it.constant }
          .forEach { entry ->
            Text(
              text = entryLabel(entry),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              text = entry.content.take(160) + if (entry.content.length > 160) "…" else "",
              style = MaterialTheme.typography.bodySmall,
              modifier = Modifier.padding(bottom = 10.dp),
            )
          }
      }
    }
  }
}

/**
 * 一条世界设定在列表里的一行标签：常驻 / 关键词 · 还需副词 · 排除词 · 概率 · 深度。
 *
 * 把编排参数摆出来，是因为「为什么这条设定没出现」只能从这里看出来 ——
 * 用户至少能自己判断是不是副关键词或概率把它挡掉了。
 */
private fun entryLabel(entry: WorldEntry): String {
  if (entry.constant) return "常驻 · 每轮都在"
  val parts = ArrayList<String>()
  parts += entry.keys.joinToString("、").ifBlank { "无关键词" }
  if (entry.secondary.isNotEmpty()) {
    parts += "还需${entry.secondaryLogic.joiner()}${entry.secondary.joinToString("、")}"
  }
  if (entry.exclude.isNotEmpty()) parts += "排除 ${entry.exclude.joinToString("、")}"
  if (entry.useProbability && entry.probability < 100) parts += "${entry.probability}% 概率"
  if (entry.sticky > 0) parts += "粘 ${entry.sticky} 轮"
  if (entry.cooldown > 0) parts += "冷却 ${entry.cooldown} 轮"
  if (entry.matchWholeWords) parts += "整词"
  if (entry.caseSensitive) parts += "区分大小写"
  when (entry.position) {
    WorldEntry.POSITION_BEFORE -> parts += "插在人设之前"
    WorldEntry.POSITION_AFTER -> parts += "插在人设之后"
    else -> parts += "深度 ${entry.depth} · ${roleLabel(entry.role)}"
  }
  return parts.joinToString(" · ")
}

/**
 * 次要关键词在标签里的连接词。
 *
 * 单独一只（而不是写在 [entryLabel] 里）是为了让那个函数留在 detekt 的圈复杂度阈值内 ——
 * 世界书每加一个字段，它就会多一条分支。
 */
private fun SecondaryLogic.joiner(): String =
  when (this) {
    SecondaryLogic.ANY -> " 或 "
    SecondaryLogic.ALL -> " 且 "
    SecondaryLogic.NOT_ALL -> " 不全有 "
    SecondaryLogic.NOT_ANY -> " 全无 "
  }

/** 深度条目的口吻，只在标签里出现，所以直接写死三个词。 */
private fun roleLabel(role: Int): String =
  when (role) {
    WorldEntry.ROLE_USER -> "user"
    WorldEntry.ROLE_ASSISTANT -> "assistant"
    else -> "system"
  }

/**
 * 备用开场白：v3 的 `alternate_greetings`，一张卡常用它区分不同场景（第一次见面 / 已经相识…）。
 *
 * 这里只读展示 —— 开场白是卡作者的文案，改它应该回卡里改；能选的是「这次用哪一条」，
 * 那件事放在空会话的页面上（选完就当第一条助手消息落库）。
 */
@Composable
private fun GreetingSection(card: CharacterCard) {
  if (card.alternateGreetings.isEmpty()) return
  SectionLabel("备用开场白 · ${card.alternateGreetings.size} 条")
  SettingsGroup {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      card.alternateGreetings.forEachIndexed { index, text ->
        Text(
          text = "${index + 2}. ${text.trim().lines().take(3).joinToString("\n")}",
          style = MaterialTheme.typography.bodyMedium,
          maxLines = 3,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

/**
 * 角色记忆：一张卡一份，注入到它自己的系统提示里。
 *
 * 摆在这一页是因为它和卡的正文一样属于「这张卡是什么」；但存在另一份文件里 ——
 * 记忆是随对话长出来的，不该混进从别处导入的卡正文。
 */
@Composable
private fun MemoryCard(notes: String) {
  SectionLabel("角色记忆")
  SettingsGroup {
    Text(
      text = notes.ifBlank { "还没有。点「编辑文案」可以写：你们之间发生过什么、约好过什么 —— 只对这张卡生效。" },
      style = MaterialTheme.typography.bodyMedium,
      color =
        if (notes.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.fillMaxWidth().padding(16.dp),
    )
  }
}

@Composable
private fun AboutCard(card: CharacterCard) {
  SectionLabel("关于这张卡")
  SettingsGroup {
    Column(Modifier.padding(16.dp)) {
      Detail("来源", if (card.imported) "导入的卡（存在应用私有目录）" else "内置卡")
      if (card.creator.isNotBlank()) Detail("作者", card.creator)
      if (card.license.isNotBlank()) Detail("许可", card.license)
      Detail(
        "运行边界",
        "默认不使用卡内的系统提示词、历史指令和示例对话；可在编辑角色的高级设置中开启。" +
          "原始卡保存在应用私有目录，未使用 SQLCipher 加密。",
      )
    }
  }
}

/**
 * 页头：头像 + 名字 + 一句话简介 + 标签。
 *
 * 不套分组卡片 —— 这一块就是页面本身，再裹一层白底只会把「谁」推得更远。
 * 标签做成中性色药丸：整行主色文字会把名字压掉，也读不出层级。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CharacterHero(card: CharacterCard) {
  Column(Modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      CharacterAvatar(card)
      Spacer(Modifier.width(ZoneuSpacing.lg))
      Column(Modifier.weight(1f)) {
        Text(
          text = card.name,
          style = MaterialTheme.typography.headlineMediumEmphasized,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        if (card.tagline.isNotBlank()) {
          Spacer(Modifier.height(ZoneuSpacing.xs))
          Text(
            text = card.tagline,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
    if (card.tags.isNotEmpty()) {
      Spacer(Modifier.height(ZoneuSpacing.lg))
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(ZoneuSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZoneuSpacing.sm),
      ) {
        card.tags.forEach { TagPill(it) }
      }
    }
  }
}

/**
 * 头像。卡片导入时带的图就存在卡目录的同名文件里；没有图时给一个中性的人形轮廓，
 * 而不是名字首字母 —— 一个大写字母看起来更像加载失败。
 */
@Composable
private fun CharacterAvatar(card: CharacterCard) {
  val context = LocalContext.current
  val avatar = remember(card.id) {
    CharacterStore.avatarFile(context, card.id)?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
  }
  if (avatar != null) {
    Image(
      bitmap = avatar,
      contentDescription = card.name,
      contentScale = ContentScale.Crop,
      modifier = Modifier.size(heroAvatarSize).clip(MaterialTheme.zoneuShapes.hero),
    )
    return
  }
  Box(
    modifier =
      Modifier.size(heroAvatarSize)
        .clip(MaterialTheme.zoneuShapes.hero)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    contentAlignment = Alignment.Center,
  ) {
    Glyph(
      kind = GlyphKind.Person,
      tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
      modifier = Modifier.size(36.dp),
    )
  }
}

@Composable
private fun TagPill(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier =
      Modifier
        .clip(MaterialTheme.zoneuShapes.pill)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .padding(horizontal = 10.dp, vertical = 4.dp),
  )
}

/**
 * 主操作与次操作分成两层：一场扮演只有「开始对话」是目的地，收藏与编辑是顺手做的。
 * 两个等宽胶囊按钮而不是把四个按钮塞进一行 —— 中文标签在窄屏上会一个词一行地折开。
 */
@Composable
private fun CharacterActions(
  pinned: Boolean,
  applying: Boolean,
  editable: Boolean,
  onStart: () -> Unit,
  onTogglePin: () -> Unit,
  onEdit: () -> Unit,
) {
  Button(
    onClick = onStart,
    enabled = !applying,
    shape = MaterialTheme.zoneuShapes.pill,
    modifier = Modifier.fillMaxWidth().height(52.dp),
  ) {
    Text(if (applying) "正在准备…" else "开始对话", style = MaterialTheme.typography.titleSmall)
  }
  Spacer(Modifier.height(ZoneuSpacing.sm))
  Row(horizontalArrangement = Arrangement.spacedBy(ZoneuSpacing.sm)) {
    val secondary = Modifier.weight(1f).height(44.dp)
    if (pinned) {
      FilledTonalButton(onClick = onTogglePin, shape = MaterialTheme.zoneuShapes.pill, modifier = secondary) {
        Text("已收藏")
      }
    } else {
      OutlinedButton(onClick = onTogglePin, shape = MaterialTheme.zoneuShapes.pill, modifier = secondary) {
        Text("收藏")
      }
    }
    // 内置卡的正文在 assets 里，改了会被下次打包覆盖 —— 只有导入/新建的卡能改。
    if (editable) {
      OutlinedButton(onClick = onEdit, shape = MaterialTheme.zoneuShapes.pill, modifier = secondary) {
        Text("编辑")
      }
    }
  }
}

/**
 * 一段长正文：默认五行，点一下展开。
 *
 * 人设动辄几千字，整段铺开会把开场白、世界设定全挤到几屏之外；用 `hasVisualOverflow`
 * 判断「这段真的被截了」才显示展开，短文本不会多出一行没用的按钮。
 */
@Composable
private fun ProseSection(title: String, body: String) {
  if (body.isBlank()) return
  val text = remember(body) { body.trim().replace("{{char}}", "角色").replace("{{user}}", "你") }
  var expanded by rememberSaveable(title, text) { mutableStateOf(false) }
  var truncated by remember(title, text) { mutableStateOf(false) }
  SectionLabel(title)
  SettingsGroup {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .then(if (truncated) Modifier.clickable { expanded = !expanded } else Modifier)
          .padding(16.dp),
    ) {
      Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = if (expanded) Int.MAX_VALUE else PROSE_COLLAPSED_LINES,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (!expanded) truncated = it.hasVisualOverflow },
      )
      if (truncated) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.padding(top = ZoneuSpacing.sm),
        ) {
          Text(
            text = if (expanded) "收起" else "展开全部",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
          )
          Glyph(
            kind = GlyphKind.Back,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp).padding(start = 2.dp).rotate(if (expanded) 90f else -90f),
          )
        }
      }
    }
  }
}

@Composable
private fun Detail(label: String, value: String) {
  Text(
    text = label,
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 8.dp),
  )
  Text(
    text = value,
    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Default, lineHeight = 18.sp),
    modifier = Modifier.padding(top = 2.dp),
  )
}

/** 卡被删了还停在详情页（从侧栏进来、期间删卡）：说清原因，给条退路。 */
@Composable
private fun MissingCard() {
  Column(Modifier.fillMaxSize().padding(24.dp)) {
    Text("这张卡不在了", style = MaterialTheme.typography.titleMediumEmphasized)
    Spacer(Modifier.height(6.dp))
    Text(
      text = "可能刚被删除，或者从别的设备同步掉了。已经聊过的会话不会跟着消失。",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** 页头头像尺寸：比正文行高出一大截，才压得住右边的名字与标签。 */
private val heroAvatarSize = 96.dp

/** 正文折叠时保留的行数：够看出文风，又不至于把后面的区块推走。 */
private const val PROSE_COLLAPSED_LINES = 5
