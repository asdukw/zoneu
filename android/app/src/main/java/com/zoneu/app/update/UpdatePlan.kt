package com.zoneu.app.update

import android.content.Context
import com.zoneu.app.agent.appVersionCode
import com.zoneu.app.update.patch.PatchManifest

/** 这次更新走哪条路。 */
internal sealed interface UpdatePlan {
  /** 下整包。首装、跨窗口、基线不符、格式不认识、空间不够、清单缺失都落在这里。 */
  data object Full : UpdatePlan

  /**
   * 下补丁。带着目标包哈希与体积：前者是产物的判据，后者配合 [UpdatePlanner.SPACE_MARGIN_BYTES]
   * 在下载**之前**判断磁盘够不够。
   */
  data class Patch(
    val entry: PatchManifest.Entry,
    val url: String,
    val targetSha256: String,
    val targetSize: Long,
  ) : UpdatePlan
}

/**
 * 兼容判据（C1–C6）：决定这次用补丁还是全量。
 *
 * 判据的顺序按**代价从低到高**排：清单（一次请求）→ 格式号 → 版本 → 空间 → 基线字节（读 150 MiB）。
 * 基线比对方在最贵的位置是有意的：它挡住的是「重发过 tag / 换了签名材料 / 从别处安装」，
 * 放在下载补丁之前做，能省掉白下几 MiB。
 *
 * 判据 C7（产物必须过整包 `sha256` 与签名）不在这里 —— 它在产物写完之后，见 `PatchInstaller`。
 * 这条分工很重要：前面六条只是「值不值得试」，C7 才是「能不能装」。
 */
internal object UpdatePlanner {
  /**
   * 空间余量。补丁 + 目标包之外还要留一点给系统安装器与解压出来的原生库，
   * 卡着字节数刚好够用的结果是「下完装不上」。
   */
  const val SPACE_MARGIN_BYTES = 64L * 1024 * 1024

  /**
   * 纯函数版本的判据链：环境输入全部显式传入，好让每个「不够格」的分支都能单测到。
   *
   * @param manifestText 清单原文；`null`/解析失败都当「没有补丁」
   * @param versionCode 本机 versionCode（基线版本的键）
   * @param baseSha256 本机已安装 APK 的 SHA-256
   * @param usableSpace cacheDir 的可用字节数
   */
  fun decide(
    patchesUrl: String?,
    manifestText: String?,
    versionCode: Int,
    baseSha256: String,
    usableSpace: Long,
  ): UpdatePlan {
    if (patchesUrl == null || manifestText == null) return UpdatePlan.Full
    val manifest = PatchManifest.decode(manifestText) ?: return UpdatePlan.Full
    // 判据 C2/C3：格式号本机认识、本机版本在窗口内。
    val entry = PatchManifest.select(manifest, versionCode) ?: return UpdatePlan.Full
    // 判据 C4：基线必须是我们发布过的那份字节。
    if (!entry.baseSha256.equals(baseSha256, ignoreCase = true)) return UpdatePlan.Full
    // 判据 C6：空间够。
    if (usableSpace < entry.patchSize + manifest.to.size + SPACE_MARGIN_BYTES) return UpdatePlan.Full
    val url = patchUrl(patchesUrl, entry) ?: return UpdatePlan.Full
    return UpdatePlan.Patch(entry, url, manifest.to.sha256, manifest.to.size)
  }

  /** 补丁资产与清单在同一个 release 目录下，换掉最后一段文件名即可。 */
  fun patchUrl(patchesUrl: String, entry: PatchManifest.Entry): String? {
    val base = patchesUrl.substringBeforeLast('/', missingDelimiterValue = "")
    return if (base.isEmpty()) null else "$base/${entry.patch}"
  }

  /**
   * 带 Android 输入的入口：取清单、读自身 APK 算哈希、看磁盘。任何一步失败都落回全量 ——
   * 补丁是优化，失败**不该**冒成一条用户可见的错误（兜底路径会成功）。
   */
  fun resolve(context: Context, release: AppUpdater.Release): UpdatePlan {
    val url = release.patchesUrl ?: return UpdatePlan.Full
    val manifestText = runCatching { UpdateHttp.getText(url) }.getOrNull() ?: return UpdatePlan.Full
    val baseSha256 =
      runCatching { UpdateHttp.sha256(java.io.File(context.applicationInfo.sourceDir)) }.getOrNull()
        ?: return UpdatePlan.Full
    return decide(url, manifestText, appVersionCode(), baseSha256, context.cacheDir.usableSpace)
  }
}
