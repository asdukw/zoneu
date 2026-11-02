package com.zoneu.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.zoneu.app.agent.CardInput
import com.zoneu.app.agent.WorldBook
import com.zoneu.app.agent.WorldBookException
import com.zoneu.app.agent.WorldBookFormat
import com.zoneu.app.agent.WorldBookRepository
import com.zoneu.app.agent.worldBookRepository
import com.zoneu.app.theme.zoneuShapes
import com.zoneu.app.ui.components.AppBanner
import com.zoneu.app.ui.components.zoneuPageInsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WorldBookScreen(onBack: () -> Unit, initialBookId: String? = null) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val ui = remember { WorldBookUi(context.worldBookRepository(), scope) }
  var selected by remember(initialBookId) { mutableStateOf(initialBookId) }
  LifecycleResumeEffect(Unit) {
    if (selected == null) ui.run { ui.refresh() }
    onPauseOrDispose { }
  }
  val id = selected
  val goBack: () -> Unit = { if (id == null) onBack() else selected = null }
  // 详情页是同一路由里的内部状态：不拦系统返回，返回键会直接退出整个世界书页。
  BackHandler(enabled = id != null, onBack = goBack)
  WorldPage(if (id == null) "世界书" else "共享世界书", goBack) {
    ui.message?.let {
      AppBanner(text = it, tone = if (ui.messageIsError) AppBanner.Tone.Error else AppBanner.Tone.Success)
    }
    if (ui.busy) Text("正在处理…")
    if (id == null) {
      WorldLibrary(ui, context) { selected = it }
    } else {
      WorldBookDetail(ui, id, onDeleted = { selected = null })
    }
  }
}

@Composable
internal fun WorldPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
  Surface(Modifier.fillMaxSize(), color = settingsPageColor()) {
    Column(Modifier.fillMaxSize().zoneuPageInsets()) {
      SettingsHeader(title, onBack)
      Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) { content() }
    }
  }
}

/** 搜索 + 分类筛选 + 三个入口。拆出来是为了让列表本身只剩「筛」和「画」。 */
@Composable
private fun WorldToolbar(
  query: String,
  onQueryChange: (String) -> Unit,
  categories: List<String>,
  category: String?,
  onCategory: (String?) -> Unit,
  busy: Boolean,
  onCreate: () -> Unit,
  onImport: () -> Unit,
  onRefresh: () -> Unit,
) {
  OutlinedTextField(
    value = query,
    onValueChange = onQueryChange,
    singleLine = true,
    shape = RoundedCornerShape(28.dp),
    placeholder = { Text("搜索世界书") },
    leadingIcon = {
      Glyph(GlyphKind.Search, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
    },
    modifier = Modifier.fillMaxWidth(),
  )
  if (categories.isNotEmpty()) {
    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      FilterChip(selected = category == null, onClick = { onCategory(null) }, label = { Text("全部") })
      categories.forEach { value ->
        FilterChip(selected = category == value, onClick = { onCategory(value) }, label = { Text(value) })
      }
    }
  }
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Button(onClick = onCreate, enabled = !busy) { Text("创建世界书") }
    OutlinedButton(onClick = onImport, enabled = !busy) { Text("从文件导入") }
    TextButton(onClick = onRefresh, enabled = !busy) { Text("刷新") }
  }
}

@Composable
private fun WorldBookRow(book: WorldBook, onOpen: (String) -> Unit) {
  SettingsGroup {
    SettingsRow(
      GlyphKind.Sparkles,
      book.name,
      "${book.entries.size} 条 · ${if (book.enabled) "启用" else "停用"} · ${book.category} ${book.tags.joinToString("、")}",
      onClick = { onOpen(book.id) },
    ) { ChevronRight() }
  }
}

@Composable
private fun WorldEmptyState() {
  Surface(shape = MaterialTheme.zoneuShapes.card, color = settingsCardColor(), modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Glyph(GlyphKind.Book, Modifier.size(28.dp), MaterialTheme.colorScheme.onSurfaceVariant)
      Text("还没有共享世界书", style = MaterialTheme.typography.titleMedium)
      Text(
        text = "建一本给角色共用，或者从文件导入。角色卡里的旧设定不会自动搬进来。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    }
  }
}

@Composable
private fun WorldLibrary(ui: WorldBookUi, context: Context, onOpen: (String) -> Unit) {
  var query by remember { mutableStateOf("") }
  var category by remember { mutableStateOf<String?>(null) }
  var creating by remember { mutableStateOf(false) }
  val newBook = remember(creating) { WorldBook() }
  val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) {
      ui.run {
        val text = withContext(Dispatchers.IO) {
          context.contentResolver.openInputStream(uri)?.use { String(CardInput.read(it), Charsets.UTF_8) }
            ?: throw WorldBookException("WORLD_IMPORT_FAILED")
        }
        val saved = ui.repository.create(WorldBookFormat.importText(text))
        ui.refresh()
        onOpen(saved.id)
      }
    }
  }
  Text(
    text = "独立保存，可由多个角色共享。默认绑定只影响新会话；卡内设定仍随角色卡更新。",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  WorldToolbar(
    query = query,
    onQueryChange = { query = it },
    categories = ui.books.map { it.category }.filter { it.isNotBlank() }.distinct().sorted(),
    category = category,
    onCategory = { category = it },
    busy = ui.busy,
    onCreate = { creating = true },
    onImport = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
    onRefresh = { ui.run { ui.refresh() } },
  )
  ui.books.filter { book ->
    (category == null || book.category == category) &&
      (listOf(book.name, book.category) + book.tags).any { it.contains(query, ignoreCase = true) }
  }.sortedBy { it.name }.forEach { book -> WorldBookRow(book = book, onOpen = onOpen) }
  if (ui.books.isEmpty()) WorldEmptyState()
  if (creating) {
    WorldHeaderDialog(newBook, onDismiss = { creating = false }) { draft ->
      ui.run {
        val saved = ui.repository.create(draft)
        ui.refresh()
        creating = false
        onOpen(saved.id)
      }
    }
  }
}

internal suspend fun writeWorldDocument(context: Context, uri: Uri, text: String) = withContext(Dispatchers.IO) {
  context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    ?: throw WorldBookException("WORLD_EXPORT_FAILED")
}
