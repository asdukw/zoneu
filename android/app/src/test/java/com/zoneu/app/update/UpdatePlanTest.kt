package com.zoneu.app.update

import com.zoneu.app.update.patch.PatchManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 兼容判据（C2–C6）逐条测一遍。
 *
 * 这些用例是**纯函数**测的（[UpdatePlanner.decide] 把清单原文、versionCode、基线哈希、可用空间
 * 全部显式传入）：判据链最怕的不是某一环写错，而是「本来该走全量的情况悄悄走了补丁」——
 * 那种错误在真机上的表现只是「下载失败了一次」，很难从日志倒推。所以每一条「不够格」都单独钉住。
 *
 * 判据 C7（产物必须过整包 sha256 与签名）不在这里，它在产物写完之后，见 `PatchInstaller`。
 */
class UpdatePlanTest {
  private val baseSha = "b".repeat(64)

  private val manifest =
    PatchManifest(
      format = PatchManifest.SUPPORTED_FORMAT,
      to = PatchManifest.Target(versionCode = 23, versionName = "2.2.0", sha256 = "a".repeat(64), size = 150L * 1024 * 1024),
      patches =
        listOf(
          PatchManifest.Entry(
            fromVersionCode = 22,
            fromVersionName = "2.1.0",
            baseSha256 = baseSha,
            patch = "zoneu-2.1.0-to-2.2.0-arm64-v8a.patch",
            patchSha256 = "c".repeat(64),
            patchSize = 4L * 1024 * 1024,
          ),
        ),
    )

  private fun text(manifest: PatchManifest = this.manifest) = PatchManifest.encode(manifest)

  private val url = "https://example.invalid/releases/download/v2.2.0/zoneu-patches.json"

  private fun decide(
    patchesUrl: String? = url,
    manifestText: String? = text(),
    versionCode: Int = 22,
    baseSha256: String = baseSha,
    usableSpace: Long = 4L * 1024 * 1024 * 1024,
  ) = UpdatePlanner.decide(patchesUrl, manifestText, versionCode, baseSha256, usableSpace)

  @Test
  fun `all criteria met selects the patch`() {
    val plan = decide()
    assertTrue("期望走补丁，实际：$plan", plan is UpdatePlan.Patch)
    plan as UpdatePlan.Patch
    assertEquals("zoneu-2.1.0-to-2.2.0-arm64-v8a.patch", plan.entry.patch)
    assertEquals("https://example.invalid/releases/download/v2.2.0/zoneu-2.1.0-to-2.2.0-arm64-v8a.patch", plan.url)
    assertEquals("a".repeat(64), plan.targetSha256)
  }

  @Test
  fun `no manifest asset in the release means full`() {
    assertEquals(UpdatePlan.Full, decide(patchesUrl = null))
  }

  @Test
  fun `unparsable manifest means full`() {
    assertEquals(UpdatePlan.Full, decide(manifestText = "{\"format\":"))
    assertEquals(UpdatePlan.Full, decide(manifestText = null))
  }

  @Test
  fun `unknown format version means full`() {
    assertEquals(UpdatePlan.Full, decide(manifestText = text(manifest.copy(format = 2))))
  }

  @Test
  fun `installed version outside the window means full`() {
    assertEquals(UpdatePlan.Full, decide(versionCode = 7))
  }

  @Test
  fun `baseline bytes that we did not publish mean full`() {
    // 重发过 tag / 换过签名材料 / 从别处安装 —— 都会走到这里。
    assertEquals(UpdatePlan.Full, decide(baseSha256 = "d".repeat(64)))
  }

  @Test
  fun `not enough free space means full`() {
    // 补丁 4 MiB + 目标 150 MiB 已经超出可用空间。
    assertEquals(UpdatePlan.Full, decide(usableSpace = 100L * 1024 * 1024))
  }

  @Test
  fun `space margin is included so a barely fitting patch is not attempted`() {
    val justEnough = 4L * 1024 * 1024 + 150L * 1024 * 1024 + UpdatePlanner.SPACE_MARGIN_BYTES
    assertTrue(decide(usableSpace = justEnough) is UpdatePlan.Patch)
    assertEquals(UpdatePlan.Full, decide(usableSpace = justEnough - 1))
  }

  @Test
  fun `patch url without a directory is rejected instead of mangled`() {
    assertEquals(null, UpdatePlanner.patchUrl("zoneu-patches.json", manifest.patches.first()))
    assertEquals(
      "https://host/dir/zoneu-2.1.0-to-2.2.0-arm64-v8a.patch",
      UpdatePlanner.patchUrl("https://host/dir/zoneu-patches.json", manifest.patches.first()),
    )
  }
}
