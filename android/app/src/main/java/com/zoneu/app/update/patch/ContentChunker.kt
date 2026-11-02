package com.zoneu.app.update.patch

import java.io.File

/**
 * 内容定义分块（content-defined chunking，gear hash）。
 *
 * 为什么不是「按固定 2 KiB 切」：APK 里插进一段字节之后，后面所有固定边界都会错位，于是
 * **整条尾部再也匹配不上**。内容定义的分块边界只取决于边界附近的内容，插入点之后很快重新对齐，
 * 这正是整文件配方能工作的原因。
 *
 * 参数取「均值 2 KiB / 最小 512 / 最大 8 KiB」：实测（v2.1.0 → v2.2.0 的 `classes2.dex`）
 * 均值 1 KiB 只比 2 KiB 少 0.09 MiB 的补丁，却把指令条数翻倍；8 KiB 则明显变差。
 *
 * 只在**编码侧**使用。解码侧只是照着 `COPY/LITERAL` 指令搬运字节，不需要知道分块规则 ——
 * 所以将来改这些参数不会让旧补丁失效。
 */
internal object ContentChunker {
  const val MIN_CHUNK = 512
  const val MAX_CHUNK = 8 * 1024

  /** 读文件的块大小。与分块大小无关，只影响 IO 次数。 */
  private const val IO_BUFFER_BYTES = 1 shl 16

  /** 边界判定掩码：`hash and MASK == 0` 时切一刀，均值因此是 2^(位数+1) 字节。 */
  const val MASK = (1L shl 11) - 1

  /**
   * gear 表：256 个固定的 64 位随机数。
   *
   * 用固定种子的 LCG 现算而不是写死一个常量表：表本身不进源码，且改动它只会影响**新生成**
   * 的补丁大小（解码不需要它）。**不要**换成 `Random` 或时间种子 —— 那会让同一对输入两次
   * 生成不同字节的补丁，而发布侧要能对同一对输入复现出同一份资产。
   */
  private val GEAR = LongArray(256).also { table ->
    var state = -0x6e5c9a8d3f2b1e0fL
    for (index in table.indices) {
      state = state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
      table[index] = state
    }
  }

  /**
   * 按内容流式切块。回调给出 `(offset, length, hash)`；最后一段可能短于 [MIN_CHUNK]，
   * 但仍带上它真实的 hash —— 两份完全相同的内容（比如没有变化的条目）靠它把尾巴也走 COPY，
   * 否则每个文件的末尾都要白付一段 literal。
   */
  fun forEachBlock(file: File, onBlock: (offset: Long, length: Int, hash: Long) -> Unit) {
    val splitter = BlockSplitter(onBlock)
    val buffer = ByteArray(IO_BUFFER_BYTES)
    file.inputStream().buffered(buffer.size).use { input ->
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        splitter.feed(buffer, read)
      }
    }
    splitter.finish()
  }

  /** 切块状态机。独立成类是为了让 [forEachBlock] 只剩「读文件」一件事（也避开嵌套深度门禁）。 */
  private class BlockSplitter(private val onBlock: (offset: Long, length: Int, hash: Long) -> Unit) {
    private var hash = 0L
    private var blockStart = 0L
    private var position = 0L

    fun feed(buffer: ByteArray, read: Int) {
      for (index in 0 until read) {
        hash = (hash shl 1) + GEAR[buffer[index].toInt() and 0xFF]
        position++
        val length = position - blockStart
        if (isBoundary(length)) {
          onBlock(blockStart, length.toInt(), hash)
          blockStart = position
          hash = 0L
        }
      }
    }

    /** 尾部残块也要给出：两份完全相同的内容靠它把最后一段也走 COPY。 */
    fun finish() {
      if (position > blockStart) onBlock(blockStart, (position - blockStart).toInt(), hash)
    }

    private fun isBoundary(length: Long): Boolean =
      (length >= MIN_CHUNK && (hash and MASK) == 0L) || length >= MAX_CHUNK
  }
}
