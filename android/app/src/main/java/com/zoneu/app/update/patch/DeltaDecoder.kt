package com.zoneu.app.update.patch

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * 应用补丁：按 `COPY/LITERAL` 配方把已安装 APK 拼成目标 APK。
 *
 * 解码是**流式**的：内存只有几个 8 KiB 缓冲，不把 150 MiB 的包整份读进堆里。这一点很重要 ——
 * 「整包 bspatch」需要旧包 + 新包同时在内存（约 310 MiB），在低端机上就是一次 OOM。
 *
 * 三条校验，缺一不可：
 * 1. `COPY` 的 `offset + length` 不得越过基线文件末尾（配方坏了/被篡改时立刻停，而不是写坏包）；
 * 2. 写出的总长度必须正好等于头部声明的 `targetSize`；
 * 3. 配方结束后不得还有剩余指令。
 *
 * 这三条**不是**安全边界 —— 真正的边界是调用方对产物做整包 `sha256` 与签名比对（与全量更新同一道闸）。
 * 它们只是让「配方坏了」这件事在写盘阶段就失败，而不是产出一个 99% 像的包再去下载全量。
 */
internal object DeltaDecoder {
  private const val BUFFER_BYTES = ContentChunker.MAX_CHUNK

  /** 头部声明的目标信息。调用方拿它先对一遍 release 里的哈希，再决定是否安装。 */
  data class Declared(val targetSize: Long, val targetSha256: String)

  fun apply(base: File, patch: File, output: File): Declared {
    val declared =
      InflaterInputStream(patch.inputStream().buffered(), Inflater(), BUFFER_BYTES).use { inflated ->
        val input = DataInputStream(inflated.buffered())
        val header = readHeader(input)
        copyAll(input, base, output, header.targetSize)
        header
      }
    if (output.length() != declared.targetSize) {
      output.delete()
      error(DeltaFormat.ERR_SIZE_MISMATCH)
    }
    return declared
  }

  /** 打开基线与新文件，把配方搬进去。抽出来只为让 [apply] 不再嵌套四层。 */
  private fun copyAll(input: DataInputStream, base: File, output: File, targetSize: Long) {
    RandomAccessFile(base, "r").use { baseFile ->
      BufferedOutputStream(output.outputStream(), BUFFER_BYTES).use { out ->
        copyOps(input, baseFile, out, targetSize)
      }
    }
  }

  private fun readHeader(input: DataInputStream): Declared {
    val magic = ByteArray(DeltaFormat.MAGIC.size)
    input.readFully(magic)
    if (!magic.contentEquals(DeltaFormat.MAGIC)) error(DeltaFormat.ERR_FORMAT)
    val version = input.readInt()
    if (version != DeltaFormat.VERSION) error(DeltaFormat.ERR_VERSION)
    val targetSize = input.readLong()
    val sha = ByteArray(DeltaFormat.SHA256_BYTES)
    input.readFully(sha)
    if (targetSize <= 0) error(DeltaFormat.ERR_CORRUPT)
    return Declared(targetSize, sha.joinToString("") { "%02x".format(it) })
  }

  /** 按配方搬运字节，直到写满 `targetSize`。 */
  private fun copyOps(
    input: DataInputStream,
    baseFile: RandomAccessFile,
    out: BufferedOutputStream,
    targetSize: Long,
  ) {
    val buffer = ByteArray(BUFFER_BYTES)
    val baseLength = baseFile.length()
    var written = 0L
    while (written < targetSize) {
      when (input.readUnsignedByte()) {
        DeltaFormat.OP_COPY -> {
          val offset = input.readLong()
          val length = input.readInt()
          if (offset < 0 || length <= 0 || offset + length > baseLength) error(DeltaFormat.ERR_CORRUPT)
          if (written + length > targetSize) error(DeltaFormat.ERR_CORRUPT)
          baseFile.seek(offset)
          var remaining = length
          while (remaining > 0) {
            val step = minOf(remaining, buffer.size)
            baseFile.readFully(buffer, 0, step)
            out.write(buffer, 0, step)
            remaining -= step
          }
          written += length
        }

        DeltaFormat.OP_LITERAL -> {
          val length = input.readInt()
          if (length <= 0 || written + length > targetSize) error(DeltaFormat.ERR_CORRUPT)
          var remaining = length
          while (remaining > 0) {
            val step = minOf(remaining, buffer.size)
            input.readFully(buffer, 0, step)
            out.write(buffer, 0, step)
            remaining -= step
          }
          written += length
        }

        else -> error(DeltaFormat.ERR_CORRUPT)
      }
    }
    // 写满了还要确认配方真的结束了：尾部多出指令说明头部与配方对不上。
    if (input.read() >= 0) error(DeltaFormat.ERR_CORRUPT)
  }
}
