package com.zoneu.app.ui.settings

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.zoneu.app.agent.CharacterStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 列表里的卡图缩略：按目标像素降采样，解码放 IO 线程。
 *
 * 列表和侧栏每行都挂着它，不能每次重组都把原图整张读进内存；没有图时给中性人形，
 * 和详情页同一套占位，不再是名字首字。
 */
@Composable
internal fun CharacterThumb(cardId: String, name: String, size: Dp, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val px = with(LocalDensity.current) { size.roundToPx() }
  val loaded by
    produceState<ImageBitmap?>(null, cardId, px) {
      value =
        withContext(Dispatchers.IO) {
          // 侧栏里可能有已经删掉的卡：读不到就当没有图，不能让一行缩略图把界面带崩。
          runCatching { CharacterStore.avatarFile(context, cardId)?.let { decodeThumb(it, px) } }.getOrNull()
        }
    }
  Box(
    modifier =
      modifier
        .size(size)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    contentAlignment = Alignment.Center,
  ) {
    val image = loaded
    if (image != null) {
      Image(
        bitmap = image,
        contentDescription = name,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
    } else {
      Glyph(
        kind = GlyphKind.Person,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.size(size * 0.55f),
      )
    }
  }
}

/** 只取大约 [targetPx] 的像素：inSampleSize 是 2 的幂，按 BitmapFactory 的规则收缩。 */
private fun decodeThumb(file: File, targetPx: Int): ImageBitmap? {
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeFile(file.path, bounds)
  var sample = 1
  while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) sample *= 2
  return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    ?.asImageBitmap()
}
