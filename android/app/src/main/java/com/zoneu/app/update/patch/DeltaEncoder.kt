package com.zoneu.app.update.patch

import com.zoneu.app.update.UpdateHttp
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.Arrays
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * 生成补丁：把 `target` 表达成「对 `base` 的 copy/literal 配方」，整体 deflate 后写进 `patch`。
 *
 * 只在发布侧（CI / 本地工具）运行 —— 设备侧只解码，不生成。共用同一个 Kotlin 实现是刻意的：
 * 两套实现（比如 CI 用 C 版 bsdiff）迟早会在边界上漂移，而漂移的表现是「用户下完补丁装不上」。
 *
 * 三条性质：
 * 1. **字节精确**：只有哈希命中**且逐字节相同**才发 `COPY`；其余一律 `LITERAL`。
 *    所以补丁在任何输入下都产出一份确定的字节，正确性最终由整包 `sha256` 兜底。
 * 2. **线性时间**：建一次基线索引 + 扫一遍目标，没有后缀排序。矩阵要跑 6 个基线，这条很实在。
 * 3. **合并相邻 COPY**：APK 里 80 MiB 未变字节会退化成几十条指令而不是几万条。
 */
internal object DeltaEncoder {
  /** 一个哈希值最多记几个候选偏移。同内容在基线里出现多次时（对齐填充、重复资源）多留几个。 */
  private const val MAX_CANDIDATES = 4

  /** 打包 `(offset, length)` 用：长度只占 14 位（[ContentChunker.MAX_CHUNK] 是 8 KiB）。 */
  private const val LENGTH_BITS = 14
  private const val LENGTH_MASK = (1L shl LENGTH_BITS) - 1

  private const val IO_BUFFER_BYTES = 1 shl 16

  /**
   * 一次生成的结果。`copyBytes` / `literalBytes` 用来判断「这个补丁值不值得发」——
   * 目标被整体重打包时（比如运行时归档换了依赖，deflate 流不重同步），literal 会接近全量。
   */
  data class Result(
    val targetSize: Long,
    val patchSize: Long,
    val copyBytes: Long,
    val literalBytes: Long,
  )

  fun encode(base: File, target: File, patch: File): Result {
    val index = buildIndex(base)
    val targetSize = target.length()
    val targetSha = UpdateHttp.sha256(target)
    val counters = Counters()
    writeOps(base, target, patch, index, targetSha, counters)
    return Result(targetSize, patch.length(), counters.copyBytes, counters.literalBytes)
  }

  private fun writeOps(
    base: File,
    target: File,
    patch: File,
    index: Map<Long, LongArray>,
    targetSha: String,
    counters: Counters,
  ) {
    RandomAccessFile(base, "r").use { baseFile ->
      RandomAccessFile(target, "r").use { targetFile ->
        deflateInto(patch, target, baseFile, targetFile, index, targetSha, counters)
      }
    }
  }

  private fun deflateInto(
    patch: File,
    target: File,
    baseFile: RandomAccessFile,
    targetFile: RandomAccessFile,
    index: Map<Long, LongArray>,
    targetSha: String,
    counters: Counters,
  ) {
    val deflated = DeflaterOutputStream(BufferedOutputStream(patch.outputStream(), IO_BUFFER_BYTES), Deflater(9))
    deflated.use { stream ->
      DataOutputStream(stream).use { out ->
        writeHeader(out, targetFile.length(), hexToBytes(targetSha))
        writeBlocks(index, target, BlockReader(baseFile), BlockReader(targetFile), OpWriter(out), counters)
      }
    }
  }

  private fun writeBlocks(
    index: Map<Long, LongArray>,
    targetFile: File,
    base: BlockReader,
    target: BlockReader,
    ops: OpWriter,
    counters: Counters,
  ) {
    ContentChunker.forEachBlock(targetFile) { offset, length, hash ->
      val candidates = if (hash == 0L) null else index[hash]
      val source = candidates?.let { findSource(it, base, target, offset, length) }
      if (source == null) {
        ops.literal(target.read(offset, length), length)
        counters.literalBytes += length
      } else {
        ops.copy(source, length)
        counters.copyBytes += length
      }
    }
    ops.flush()
  }

  /** 可变统计。用容器而不是返回值，是为了让搬运循环保持单出口。 */
  private class Counters {
    var copyBytes = 0L
    var literalBytes = 0L
  }

  /**
   * 随机读定长片段，缓冲按实例复用（基线与目标各一个，互不覆盖）。
   *
   * 复用同一个 ByteArray 是有意的：一次比对两段最多 8 KiB，为每块分配新数组会在 150 MiB 的包上
   * 产生几十万次分配。代价是**返回值只在下次 read 之前有效** —— 调用方用完即比，不持有。
   */
  private class BlockReader(private val handle: RandomAccessFile) {
    private val buffer = ByteArray(ContentChunker.MAX_CHUNK)

    fun read(offset: Long, length: Int): ByteArray {
      handle.seek(offset)
      handle.readFully(buffer, 0, length)
      return buffer
    }

    /**
     * 只比较前 `length` 个字节。
     *
     * **不要**写成 `Arrays.equals(buffer, other)`：两个缓冲都是复用的，块尾之后是上一次读剩下的
     * 内容，整数组比较会把「内容其实相同、长度不足 8 KiB 的尾块」判成不匹配 —— 现象是补丁里多出
     * 十几 MiB 的 literal（真实 APK 上实测过：36 MiB -> 46 MiB），而单测因为两侧脏字节恰好一样
     * 而全绿。
     */
    fun matches(offset: Long, other: ByteArray, length: Int): Boolean {
      handle.seek(offset)
      var compared = 0
      while (compared < length) {
        val step = minOf(length - compared, buffer.size)
        handle.readFully(buffer, 0, step)
        if (!Arrays.equals(buffer, 0, step, other, compared, compared + step)) return false
        compared += step
      }
      return true
    }
  }

  private fun writeHeader(out: DataOutputStream, targetSize: Long, targetSha: ByteArray) {
    out.write(DeltaFormat.MAGIC)
    out.writeInt(DeltaFormat.VERSION)
    out.writeLong(targetSize)
    out.write(targetSha)
  }

  /** 基线索引：块哈希 → 若干候选（打包成 `offset shl 14 or length`）。 */
  private fun buildIndex(base: File): Map<Long, LongArray> {
    val index = HashMap<Long, LongArray>(1 shl 17)
    ContentChunker.forEachBlock(base) { offset, length, hash ->
      if (hash != 0L) {
        val packed = (offset shl LENGTH_BITS) or length.toLong()
        val existing = index[hash]
        index[hash] =
          when {
            existing == null -> longArrayOf(packed)
            existing.size >= MAX_CANDIDATES -> existing
            else -> existing + packed
          }
      }
    }
    return index
  }

  /** 找到能**逐字节**对上这一段目标字节的基线偏移；找不到返回 null（发 LITERAL）。 */
  private fun findSource(
    candidates: LongArray,
    base: BlockReader,
    target: BlockReader,
    offset: Long,
    length: Int,
  ): Long? {
    // 逐字节比：哈希只是候选筛选，碰撞时不能把错的字节当命中（否则补丁会产出坏包）。
    val wanted = target.read(offset, length)
    for (packed in candidates) {
      if (unpackLength(packed) != length) continue
      val candidateOffset = packed ushr LENGTH_BITS
      if (base.matches(candidateOffset, wanted, length)) return candidateOffset
    }
    return null
  }

  private fun unpackLength(packed: Long): Int = (packed and LENGTH_MASK).toInt()

  private fun hexToBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }

  /**
   * 指令写入 + 相邻 `COPY` 合并。
   *
   * 合并不是优化癖：一条 COPY 指令 13 字节，未变的 80 MiB 按 2 KiB 分块是 4 万条、约 0.5 MiB；
   * 合并之后同长度只剩几十条。`target` 是顺序扫描的，所以「相邻」只需看基线偏移是否接上。
   */
  private class OpWriter(private val out: DataOutputStream) {
    private var pendingOffset = -1L
    private var pendingLength = 0

    fun copy(offset: Long, length: Int) {
      if (pendingOffset >= 0 && pendingOffset + pendingLength == offset) {
        pendingLength += length
        return
      }
      flush()
      pendingOffset = offset
      pendingLength = length
    }

    fun literal(bytes: ByteArray, length: Int) {
      flush()
      out.writeByte(DeltaFormat.OP_LITERAL)
      out.writeInt(length)
      out.write(bytes, 0, length)
    }

    fun flush() {
      if (pendingOffset < 0) return
      out.writeByte(DeltaFormat.OP_COPY)
      out.writeLong(pendingOffset)
      out.writeInt(pendingLength)
      pendingOffset = -1L
      pendingLength = 0
    }
  }
}
