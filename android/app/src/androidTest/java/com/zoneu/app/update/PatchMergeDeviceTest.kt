package com.zoneu.app.update

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zoneu.app.update.patch.PatchManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 真机上的补丁合并：用**当前安装包自己**当基线，把宿主生成的真实补丁拼回目标包。
 *
 * 单测跑不了这一层的三件事：真机上的内存/IO 行为（流式解码在低端机上会不会炸）、
 * 与已安装包**逐字节比对签名证书**（要真的读一次已安装 APK）、以及 150 MiB 级的实际耗时。
 *
 * 补丁由宿主生成并 push 到设备，路径与目标哈希通过 instrumentation 参数传入：
 *
 * ```text
 * adb shell am instrument -w \
 *   -e patchPath /data/local/tmp/x.patch -e expectedSha <target sha256> \
 *   -e class com.zoneu.app.update.PatchMergeDeviceTest com.zoneu.app.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * 没有传参时两个用例都直接跳过 —— 日常 `connectedAndroidTest` 不会因为它们需要外部夹具而红。
 */
@RunWith(AndroidJUnit4::class)
class PatchMergeDeviceTest {
  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
  private val arguments = InstrumentationRegistry.getArguments()

  @Test
  fun baseApkHashIsStableWhileTheTestRuns() {
    // 前置自检：基线就是安装包本身，C4 在设备上读的就是这个文件。读一次要 1-2 秒。
    val base = File(context.applicationInfo.sourceDir)
    assertTrue("已安装 APK 不可读：$base", base.isFile)
    val first = UpdateHttp.sha256(base)
    assertEquals("同一个文件两次哈希不一致", first, UpdateHttp.sha256(base))
    assertEquals(64, first.length)
  }

  @Test
  fun mergeProducesTheExpectedTargetAndKeepsTheSigner() {
    val fixture = fixture() ?: return
    val base = File(context.applicationInfo.sourceDir)
    val startedAt = System.currentTimeMillis()
    val output = PatchInstaller.merge(context, fixture.plan, fixture.patch, VERSION)
    val elapsed = System.currentTimeMillis() - startedAt
    println(
      "merge ${base.length() / 1048576}MiB + ${fixture.patch.length() / 1024}KiB -> " +
        "${output.length() / 1048576}MiB in ${elapsed}ms",
    )

    assertEquals("产物哈希与清单不符", fixture.expectedSha, UpdateHttp.sha256(output))
    // 签名必须与**已安装包**一致：换签名 = 另一个 App，装上去旧数据也读不到。
    assertTrue("产物签名与已安装包不一致", UpdateDownloader.signerMatches(context, output))
    // 补丁用完即删，产物留在 cacheDir 等安装器。
    assertFalse("补丁应当用完即删", fixture.patch.exists())
    assertTrue(output.isFile)
    output.delete()
  }

  @Test
  fun outputThatDoesNotMatchTheExpectedHashIsRejectedAndRemoved() {
    val fixture = fixture() ?: return
    // C7 的反面：同样的补丁、错误的期望哈希 —— 必须抛错，且不在 cacheDir 里留下任何半成品。
    // 注意要改的是 plan 里的期望值（判据读的是它），不是夹具上那份副本。
    val wrong = fixture.copy(plan = fixture.plan.copy(targetSha256 = "0".repeat(64)))
    val failure = runCatching { PatchInstaller.merge(context, wrong.plan, wrong.patch, VERSION) }.exceptionOrNull()
    assertTrue("哈希不符时必须失败，实际成功", failure != null)
    assertEquals("PATCH_OUTPUT_MISMATCH", failure?.message)

    val merged = UpdateDownloader.apkFile(context, VERSION)
    assertFalse("失败的产物不该留下：${merged.name}", merged.exists())
    assertFalse("中间的半成品不该留下：${merged.name}.merging", File(merged.path + ".merging").exists())
  }

  private data class Fixture(
    val patch: File,
    val expectedSha: String,
    val plan: UpdatePlan.Patch,
  )

  private fun fixture(): Fixture? {
    val patchPath = arguments.getString("patchPath") ?: return null
    val expectedSha = arguments.getString("expectedSha") ?: return null
    val pushed = File(patchPath)
    assertTrue("补丁不存在：$patchPath", pushed.isFile)
    // 复制到应用自己的 cacheDir 再测：PatchInstaller 会「用完即删」，
    // 直接测 push 上去的那份会让第二个用例找不到文件。
    val patch = File(context.cacheDir, "device-test-${System.nanoTime()}.patch").apply { pushed.copyTo(this) }
    val plan =
      UpdatePlan.Patch(
        entry =
          PatchManifest.Entry(
            fromVersionCode = 0,
            fromVersionName = "fixture",
            baseSha256 = UpdateHttp.sha256(File(context.applicationInfo.sourceDir)),
            patch = patch.name,
            patchSha256 = UpdateHttp.sha256(patch),
            patchSize = patch.length(),
          ),
        url = "",
        targetSha256 = expectedSha,
        targetSize = 0,
      )
    return Fixture(patch, expectedSha, plan)
  }

  private companion object {
    /** 与真实更新区分开：失败时不会清掉用户真正的下载物。 */
    const val VERSION = "device-test"
  }
}
