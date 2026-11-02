package com.zoneu.app.update

import android.content.Context
import com.zoneu.app.update.patch.DeltaDecoder
import java.io.File

/**
 * 把补丁拼成目标 APK，并做**与全量同一道闸**的校验（判据 C7）。
 *
 * 三步不能省：
 * 1. 拼（[DeltaDecoder]，流式，内存只有几 KiB 缓冲）；
 * 2. 整包 `sha256` 必须等于清单里声明的目标哈希；
 * 3. 签名证书必须与当前安装包一致（与全量更新同一个判据）—— 换签名 = 另一个 App，
 *    装上去旧数据也读不到。
 *
 * 失败时**删掉产物**再抛错：一个 90% 像的包留在 cacheDir 里，下一次「安装」按钮就会把它递出去。
 */
internal object PatchInstaller {
  /**
   * @param plan 选中这条补丁时的判据结果（含目标哈希）
   * @param patch 已下载并通过 `patchSha256` 校验的补丁文件
   * @return 可直接交给系统安装器的 APK
   */
  fun merge(context: Context, plan: UpdatePlan.Patch, patch: File, version: String): File {
    val output = UpdateDownloader.apkFile(context, version)
    val produced = File(output.parentFile, "${output.name}.merging")
    produced.delete()
    try {
      DeltaDecoder.apply(File(context.applicationInfo.sourceDir), patch, produced)
      if (!produced.isFile || UpdateHttp.sha256(produced) != plan.targetSha256) {
        error("PATCH_OUTPUT_MISMATCH")
      }
      if (!UpdateDownloader.signerMatches(context, produced)) error("SIGNER_MISMATCH")
      output.delete()
      if (!produced.renameTo(output)) error("RENAME_FAILED")
    } catch (
      // 产物要删掉再抛出：这里可能是解码器的短码、inflate 异常或取消，对调用方是同一件事 ——
      // 只有「离开时要留下干净的 cacheDir」这一条共同处置。
      @Suppress("TooGenericExceptionCaught") error: Exception,
    ) {
      produced.delete()
      throw error
    } finally {
      // 补丁用过就没用了：留着只会在 cacheDir 里多占几 MiB（与语音模型「用完即删」同一条原则）。
      patch.delete()
    }
    return output
  }
}
