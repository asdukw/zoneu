package com.zoneu.app.update.patch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/**
 * 补丁的往返与边界：**生成 → 应用 → 逐字节比对**。
 *
 * 这些用例不需要设备也不需要网络，因为生成与应用都是纯 JVM 的（`update/patch/` 里没有 Android
 * 依赖）。真机上只剩「从 GitHub 下真包」的那一次，见 tasks 的组 5。
 *
 * 每个用例都同时断言两件事：产物与目标逐字节相同，以及**配方确实用上了 COPY**（否则「全 literal」
 * 也能通过往返，而那对流量毫无意义）。
 */
class DeltaRoundTripTest {
  @get:Rule
  val temporary = TemporaryFolder()

  @Test
  fun `identical files need almost no literal bytes`() {
    val payload = randomBytes(200_000, seed = 1)
    val result = roundTrip(payload, payload)
    assertEquals(payload.size.toLong(), result.declared.targetSize)
    assertArrayEquals(payload, result.output)
    // 完全相同的两份内容：所有字节都该来自 COPY。
    assertEquals(0L, result.stats.literalBytes)
    assertTrue("补丁应当远小于目标：${result.stats.patchSize}", result.stats.patchSize < 20_000)
  }

  @Test
  fun `insertion at the front only pays for the new bytes`() {
    val base = randomBytes(200_000, seed = 2)
    val inserted = randomBytes(4_096, seed = 3)
    val target = inserted + base
    val result = roundTrip(base, target)
    assertArrayEquals(target, result.output)
    // 插入点之后的内容虽然整体位移，但内容定义分块会重新对齐。
    assertTrue("COPY 覆盖太少：${result.stats.copyBytes}", result.stats.copyBytes > 150_000)
    assertTrue("literal 太多：${result.stats.literalBytes}", result.stats.literalBytes < 60_000)
  }

  @Test
  fun `small scattered edits are absorbed as literal runs`() {
    val base = randomBytes(120_000, seed = 4)
    val target = base.copyOf()
    for (offset in intArrayOf(1_000, 40_000, 90_000)) {
      target.fill(0x7F, offset, offset + 512)
    }
    val result = roundTrip(base, target)
    assertArrayEquals(target, result.output)
    assertTrue("改动只有 1.5 KiB，literal 不该超过 20 KiB：${result.stats.literalBytes}", result.stats.literalBytes < 20_000)
  }

  @Test
  fun `trailing partial block still matches`() {
    // 回归用例：块缓冲是复用的，若比对时整数组相比（而不是只比 length 个字节），
    // 「长度不足一块的尾块」会被判成不匹配，补丁里就多出一段本该是 COPY 的 literal。
    val base = randomBytes(100_000, seed = 16)
    val target = base.copyOf().also { it.fill(0x5A, 0, 3_000) }
    val result = roundTrip(base, target)
    assertArrayEquals(target, result.output)
    assertTrue("改动只有开头 3 KiB，literal 却是 ${result.stats.literalBytes}", result.stats.literalBytes < 16_384)
  }

  @Test
  fun `completely different content still round-trips`() {
    val base = randomBytes(50_000, seed = 5)
    val target = randomBytes(64_000, seed = 6)
    val result = roundTrip(base, target)
    assertArrayEquals(target, result.output)
    assertTrue("没有可复用内容时补丁应当接近目标大小：${result.stats.patchSize}", result.stats.patchSize > 40_000)
  }

  @Test
  fun `target shorter than base`() {
    val base = randomBytes(80_000, seed = 7)
    val target = base.copyOf(20_000)
    val result = roundTrip(base, target)
    assertArrayEquals(target, result.output)
  }

  @Test
  fun `empty base has to carry everything as literal`() {
    val target = randomBytes(30_000, seed = 8)
    val result = roundTrip(ByteArray(0), target)
    assertArrayEquals(target, result.output)
    assertEquals(target.size.toLong(), result.stats.literalBytes)
  }

  @Test
  fun `corrupted patch fails instead of writing a wrong file`() {
    val base = randomBytes(60_000, seed = 9)
    val target = randomBytes(60_000, seed = 10)
    val patch = encodeToFile(base, target)
    val bytes = patch.readBytes()
    // 从中间翻一个字节：deflate 流要么解不出来，要么解出错误的配方 —— 两种都必须失败。
    bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
    patch.writeBytes(bytes)
    val failure = runCatching { apply(base, patch) }.exceptionOrNull()
    assertTrue("坏补丁必须抛错，实际返回了正常结果", failure != null)
  }

  @Test
  fun `truncated patch fails`() {
    val base = randomBytes(60_000, seed = 11)
    val target = randomBytes(60_000, seed = 12)
    val patch = encodeToFile(base, target)
    val bytes = patch.readBytes()
    patch.writeBytes(bytes.copyOf(bytes.size / 2))
    assertTrue(runCatching { apply(base, patch) }.isFailure)
  }

  @Test
  fun `a different base yields different bytes, so the whole-file hash has to gate installation`() {
    val target = randomBytes(60_000, seed = 14)
    // 基线是「目标改掉开头 1000 字节」：配方里既有 literal 也有大段 COPY，才能体现基线内容的影响。
    val base = target.copyOf().also { it.fill(0, 0, 1_000) }
    val patch = encodeToFile(base, target)

    val withCorrectBase = File(temporary.newFolder(), "ok.apk")
    DeltaDecoder.apply(placeholderFile(base), patch, withCorrectBase)
    assertTrue(withCorrectBase.readBytes().contentEquals(target))

    // 换一个基线：配方照跑不误，但拼出来的字节不对 —— 这正是为什么最终判据必须是整包 sha256，
    // 而不是「配方跑完了」。
    val output = File(temporary.newFolder(), "out.apk")
    val declared = DeltaDecoder.apply(placeholderFile(randomBytes(60_000, seed = 15)), patch, output)
    assertEquals(target.size.toLong(), declared.targetSize)
    assertFalse(output.readBytes().contentEquals(target))
  }

  private data class RoundTrip(
    val output: ByteArray,
    val declared: DeltaDecoder.Declared,
    val stats: DeltaEncoder.Result,
  )

  private fun roundTrip(base: ByteArray, target: ByteArray): RoundTrip {
    val baseFile = placeholderFile(base)
    val targetFile = placeholderFile(target)
    val patch = File(temporary.newFolder(), "delta.patch")
    val stats = DeltaEncoder.encode(baseFile, targetFile, patch)
    val output = File(patch.parentFile, "output.apk")
    val declared = DeltaDecoder.apply(baseFile, patch, output)
    assertEquals("补丁体积与生成结果不一致", stats.patchSize, patch.length())
    return RoundTrip(output.readBytes(), declared, stats)
  }

  private fun encodeToFile(base: ByteArray, target: ByteArray): File {
    val patch = File(temporary.newFolder(), "delta.patch")
    DeltaEncoder.encode(placeholderFile(base), placeholderFile(target), patch)
    return patch
  }

  private fun apply(base: ByteArray, patch: File): DeltaDecoder.Declared {
    val output = File(patch.parentFile, "output.apk")
    return DeltaDecoder.apply(placeholderFile(base), patch, output)
  }

  private fun placeholderFile(bytes: ByteArray): File =
    File(temporary.newFolder(), "blob.bin").apply { writeBytes(bytes) }

  private fun randomBytes(size: Int, seed: Int): ByteArray =
    Random(seed).nextBytes(size)
}
