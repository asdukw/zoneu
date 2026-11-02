package com.zoneu.app.update.patch

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 补丁清单（发布资产 `zoneu-patches.json`）。
 *
 * 一个文件承载设备端做决定所需的**全部**信息：格式号、目标包哈希、以及窗口内每个可达版本的
 * 基线哈希与补丁哈希/体积。这样一个请求就能判断「用补丁还是全量」，不必遍历 release 列表
 * 拼补丁链，也不会在多次 API 调用之间读到不一致的状态（见 design.md D3）。
 *
 * 清单**不是**信任源：它和 APK 一样来自同一个 release，最终判据仍是「产物必须对上整包 `sha256`
 * 与签名证书」。清单被替换只能让设备走全量或多下一次几 MiB 的补丁，装不上任何别的东西。
 */
@Serializable
internal data class PatchManifest(
  val format: Int,
  val to: Target,
  val patches: List<Entry>,
) {
  @Serializable
  data class Target(
    val versionCode: Int,
    val versionName: String,
    val sha256: String,
    /** 目标 APK 的字节数。用来在下载补丁**之前**判断磁盘空间够不够（见兼容判据 C6）。 */
    val size: Long = 0,
  )

  @Serializable
  data class Entry(
    val fromVersionCode: Int,
    val fromVersionName: String,
    /** 基线（用户设备上已安装的那份 APK）的 SHA-256。对不上就不下载补丁 —— 见兼容判据 C4。 */
    val baseSha256: String,
    val patch: String,
    val patchSha256: String,
    val patchSize: Long,
  )

  /** 本机能理解的格式号。将来改编码方式时递增，旧设备据此自动走全量。 */
  companion object {
    const val SUPPORTED_FORMAT = 1

    const val ASSET_NAME = "zoneu-patches.json"

    private val json =
      Json {
        ignoreUnknownKeys = true
        prettyPrint = true
      }

    fun encode(manifest: PatchManifest): String = json.encodeToString(serializer(), manifest)

    /**
     * 解析清单。任何问题都返回 null —— 调用方据此走全量，而不是把「清单坏了」报成更新失败。
     * **不做严格校验**：格式号不认识、字段缺失、JSON 形状不对，都是「没有可用的补丁」。
     */
    fun decode(text: String): PatchManifest? =
      runCatching { json.decodeFromString(serializer(), text) }
        .getOrNull()
        ?.takeIf { it.format > 0 && it.patches.all { entry -> entry.patchSize > 0 } }

    /**
     * 兼容判据里不需要读磁盘的那几条：格式号认识、目标哈希存在、本机版本在表内。
     * 基线字节比对照走 [Entry.baseSha256]，由调用方在下载补丁**之前**做。
     */
    fun select(manifest: PatchManifest, versionCode: Int): Entry? {
      if (manifest.format != SUPPORTED_FORMAT) return null
      return manifest.patches.firstOrNull { it.fromVersionCode == versionCode }
    }
  }
}
