package com.zoneu.app.update.patch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 清单的解析与选择。
 *
 * 设备端对清单的态度是「任何不对都当没有补丁」：解析失败、格式号不认识、条目体积为 0，
 * 一律落回全量。这些断言就是在钉这条行为 —— 反过来说，「清单坏了」永远不该冒成一条用户可见的
 * 错误，因为兜底路径会成功。
 */
class PatchManifestTest {
  private val sample =
    PatchManifest(
      format = PatchManifest.SUPPORTED_FORMAT,
      to = PatchManifest.Target(versionCode = 23, versionName = "2.2.0", sha256 = "a".repeat(64), size = 163_479_578),
      patches =
        listOf(
          PatchManifest.Entry(
            fromVersionCode = 22,
            fromVersionName = "2.1.0",
            baseSha256 = "b".repeat(64),
            patch = "zoneu-2.1.0-to-2.2.0-arm64-v8a.patch",
            patchSha256 = "c".repeat(64),
            patchSize = 4_194_304,
          ),
        ),
    )

  @Test
  fun `encode and decode round-trip`() {
    val decoded = PatchManifest.decode(PatchManifest.encode(sample))
    assertEquals(sample, decoded)
  }

  @Test
  fun `select finds the entry for the installed version`() {
    val decoded = requireNotNull(PatchManifest.decode(PatchManifest.encode(sample)))
    val entry = PatchManifest.select(decoded, 22)
    assertEquals("zoneu-2.1.0-to-2.2.0-arm64-v8a.patch", entry?.patch)
  }

  @Test
  fun `select returns null for a version outside the window`() {
    val decoded = requireNotNull(PatchManifest.decode(PatchManifest.encode(sample)))
    assertNull(PatchManifest.select(decoded, 7))
  }

  @Test
  fun `unknown format means no patch`() {
    val future = sample.copy(format = PatchManifest.SUPPORTED_FORMAT + 1)
    val decoded = requireNotNull(PatchManifest.decode(PatchManifest.encode(future)))
    assertNull(PatchManifest.select(decoded, 22))
  }

  @Test
  fun `malformed json decodes to null instead of throwing`() {
    assertNull(PatchManifest.decode("{\"format\":1,"))
    assertNull(PatchManifest.decode("not json at all"))
    assertNull(PatchManifest.decode("{}"))
  }

  @Test
  fun `entries without a patch file are rejected`() {
    val broken = sample.copy(patches = sample.patches.map { it.copy(patchSize = 0) })
    assertNull(PatchManifest.decode(PatchManifest.encode(broken)))
  }

  @Test
  fun `unknown fields are ignored so a newer manifest still parses`() {
    val json =
      """
      {
        "format": 1,
        "to": { "versionCode": 23, "versionName": "2.2.0", "sha256": "${"a".repeat(64)}", "extra": 1 },
        "patches": [],
        "note": "后加的字段"
      }
      """.trimIndent()
    assertEquals(0, PatchManifest.decode(json)?.patches?.size)
  }
}
