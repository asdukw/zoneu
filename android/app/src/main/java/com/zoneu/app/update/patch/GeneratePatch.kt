package com.zoneu.app.update.patch

import com.zoneu.app.update.UpdateHttp
import java.io.File
import kotlin.system.exitProcess

/**
 * 发布侧的补丁生成入口（`./gradlew :app:generatePatch`）。
 *
 * 用同一个 Kotlin 实现生成，而不是再写一份 CI 专用工具：两份实现迟早会在边界上漂移，而漂移的
 * 表现是「用户下完补丁装不上」。
 *
 * ```text
 * ./gradlew :app:generatePatch --args="\
 *   --out-dir build/patches \
 *   --target-apk android/app/build/outputs/apk/release/app-release.apk \
 *   --to-version-code 23 --to-version-name 2.2.0 \
 *   --base 22:2.1.0:/tmp/base-2.1.0.apk --base 21:2.0.0:/tmp/base-2.0.0.apk"
 * ```
 *
 * 四条刻意的行为：
 * 1. **薄补丁才算数**：配方达到目标包的 [maxRatio] 以上时（运行时归档被整体重打包就是这种情况），
 *    这一条**不写进清单**——省下的流量不够补偿一次失败的判断，用户直接下全量更省事。
 * 2. **每条都当场往返验证**：生成后立刻用基线 + 补丁拼一次，哈希必须等于目标包。这一步是
 *    「用户下完补丁装不上」的唯一结构性防线，失败的那条**不发布**（发布不因此中断）。
 * 3. **单条基线失败不拖垮整批**：某个基线生成失败/被跳过，其余照常产出，原因打到 stderr。
 * 4. **写完自检**：清单里每条补丁的 `size`/`sha256` 都必须与磁盘上的文件相符，否则非零退出。
 */
object GeneratePatch {
  private const val DEFAULT_MAX_RATIO = 0.6

  /**
   * CLI 入口。**显式泛捕获**：这个工具任何一类失败（文件不存在、参数写错、编码器抛错）对使用者
   * 都是同一件事 —— 打一行原因、非零退出。逐类捕获只会漏掉没预料到的那种，还把退出码搞乱。
   */
  @Suppress("TooGenericExceptionCaught")
  @JvmStatic
  fun main(args: Array<String>) {
    try {
      exitProcess(run(args))
    } catch (error: Exception) {
      System.err.println("generatePatch 失败：${error.message}")
      exitProcess(1)
    }
  }

  private fun run(args: Array<String>): Int {
    val options = Options.parse(args)
    val target = File(options.targetApk)
    require(target.isFile) { "找不到目标 APK：${options.targetApk}" }
    val outDir = File(options.outDir).apply { mkdirs() }
    val targetSha = UpdateHttp.sha256(target)

    val entries = mutableListOf<PatchManifest.Entry>()
    for (base in options.bases) {
      val entry = generateOne(base, target, targetSha, outDir, options.maxRatio, options.toVersionName)
      if (entry != null) entries.add(entry)
    }
    if (entries.isEmpty()) {
      System.err.println("没有任何可用的补丁：清单不会写出（设备端将全部走全量）")
      return 1
    }
    val manifest =
      PatchManifest(
        format = PatchManifest.SUPPORTED_FORMAT,
        to =
          PatchManifest.Target(
            versionCode = options.toVersionCode,
            versionName = options.toVersionName,
            sha256 = targetSha,
            size = target.length(),
          ),
        patches = entries,
      )
    verifyEntries(entries, outDir)
    val manifestFile = File(outDir, PatchManifest.ASSET_NAME)
    manifestFile.writeText(PatchManifest.encode(manifest))
    println("清单：${manifestFile.path}（${entries.size} 条补丁）")
    return 0
  }

  private fun generateOne(
    base: Base,
    target: File,
    targetSha: String,
    outDir: File,
    maxRatio: Double,
    toVersionName: String,
  ): PatchManifest.Entry? {
    val baseFile = File(base.path)
    if (!baseFile.isFile) {
      System.err.println("跳过 ${base.versionName}：基线 APK 不存在（${base.path}）")
      return null
    }
    val name = "zoneu-${base.versionName}-to-$toVersionName-arm64-v8a.patch"
    val patchFile = File(outDir, name)
    val result = DeltaEncoder.encode(baseFile, target, patchFile)
    val ratio = result.patchSize.toDouble() / result.targetSize
    if (ratio >= maxRatio) {
      System.err.println(
        "跳过 ${base.versionName}：补丁 ${result.patchSize / 1024} KiB 达到目标包的 " +
          "${(ratio * 100).toInt()}%（阈值 ${(maxRatio * 100).toInt()}%），直接发全量更省",
      )
      patchFile.delete()
      return null
    }
    if (!roundTrips(baseFile, patchFile, targetSha, outDir)) {
      System.err.println(
        "跳过 ${base.versionName}：补丁往返验证失败（拼出来的字节与目标包哈希不符）——" +
          "这是编码器/解码器的 bug，不是网络问题；该补丁不发布",
      )
      patchFile.delete()
      return null
    }
    println(
      "${base.versionName} -> $toVersionName：补丁 ${result.patchSize / 1024} KiB" +
        "（copy ${result.copyBytes / 1024} KiB / literal ${result.literalBytes / 1024} KiB，往返已验证）",
    )
    return PatchManifest.Entry(
      fromVersionCode = base.versionCode,
      fromVersionName = base.versionName,
      baseSha256 = base.sha256,
      patch = name,
      patchSha256 = UpdateHttp.sha256(patchFile),
      patchSize = patchFile.length(),
    )
  }

  /** 往返验证：基线 + 补丁拼出来的字节必须就是目标包。比任何单测都硬。 */
  @Suppress("TooGenericExceptionCaught") // 解码器可能抛 inflate 异常，也可能抛我们自己的短码；对调用方是一件事：这条补丁不发。
  private fun roundTrips(base: File, patch: File, targetSha: String, scratchDir: File): Boolean {
    val output = File(scratchDir, "${patch.name}.roundtrip.apk")
    return try {
      DeltaDecoder.apply(base, patch, output)
      UpdateHttp.sha256(output) == targetSha
    } catch (error: Exception) {
      System.err.println("往返验证抛错：${error.javaClass.simpleName}: ${error.message}")
      false
    } finally {
      output.delete()
    }
  }

  /** 清单自洽：文件在、大小对、哈希对。对不上就不该发出去。 */
  private fun verifyEntries(entries: List<PatchManifest.Entry>, outDir: File) {
    for (entry in entries) {
      val file = File(outDir, entry.patch)
      require(file.isFile) { "清单指向的补丁不存在：${entry.patch}" }
      require(file.length() == entry.patchSize) { "补丁体积与清单不符：${entry.patch}" }
      require(UpdateHttp.sha256(file) == entry.patchSha256) { "补丁哈希与清单不符：${entry.patch}" }
    }
  }

  private data class Base(val versionCode: Int, val versionName: String, val path: String, val sha256: String) {
    companion object {
      fun parse(raw: String): Base {
        // code:name:path，path 里可能有冒号（Windows 盘符），所以只切前两段。
        val parts = raw.split(':', limit = 3)
        require(parts.size == 3) { "--base 的格式是 <versionCode>:<versionName>:<apk 路径>，收到：$raw" }
        val path = parts[2]
        val file = File(path)
        require(file.isFile) { "基线 APK 不存在：$path" }
        return Base(parts[0].toIntOrNull() ?: error("versionCode 不是整数：${parts[0]}"), parts[1], path, UpdateHttp.sha256(file))
      }
    }
  }

  private data class Options(
    val outDir: String,
    val targetApk: String,
    val toVersionCode: Int,
    val toVersionName: String,
    val bases: List<Base>,
    val maxRatio: Double,
  ) {
    companion object {
      fun parse(args: Array<String>): Options {
        var outDir: String? = null
        var targetApk: String? = null
        var toVersionCode: Int? = null
        var toVersionName: String? = null
        var maxRatio = DEFAULT_MAX_RATIO
        val bases = mutableListOf<Base>()
        var index = 0
        while (index < args.size) {
          val key = args[index]
          val value = args.getOrNull(index + 1) ?: error("$key 缺少取值")
          when (key) {
            "--out-dir" -> outDir = value
            "--target-apk" -> targetApk = value
            "--to-version-code" -> toVersionCode = value.toIntOrNull() ?: error("versionCode 不是整数：$value")
            "--to-version-name" -> toVersionName = value
            "--max-ratio" -> maxRatio = value.toDoubleOrNull() ?: error("max-ratio 不是小数：$value")
            "--base" -> bases.add(Base.parse(value))
            else -> error("未知参数：$key")
          }
          index += 2
        }
        return Options(
          outDir = requireNotNull(outDir) { "缺少 --out-dir" },
          targetApk = requireNotNull(targetApk) { "缺少 --target-apk" },
          toVersionCode = requireNotNull(toVersionCode) { "缺少 --to-version-code" },
          toVersionName = requireNotNull(toVersionName) { "缺少 --to-version-name" },
          bases = bases,
          maxRatio = maxRatio,
        )
      }
    }
  }
}
