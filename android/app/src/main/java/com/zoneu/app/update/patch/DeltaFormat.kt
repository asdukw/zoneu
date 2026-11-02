package com.zoneu.app.update.patch

/**
 * 补丁资产的线上格式常量。
 *
 * `patch 文件 = deflate( MAGIC | VERSION | targetSize | targetSha256 | op* )`
 *
 * 整体 deflate 而不是「先解压再用」：补丁是要从网上拉的那一份，压缩率直接等于用户流量。
 * `patchSha256`（清单里）覆盖的是**压缩后**的字节，设备先校验再解压，坏包不会进解码器。
 *
 * 格式号与清单的 `format` 一起演进：将来换编码方式时递增 [VERSION]，旧设备据此走全量
 * （见 `AppUpdater` 的兼容判据）。**不要**在同一个 [VERSION] 下改语义 —— 已发布的补丁会变得
 * 无法解码，而那正是「回退到全量」要避免的浪费。
 */
internal object DeltaFormat {
  /** 四个字节的魔数，先于版本号出现：拿到一个不认识的文件时能立刻说「这不是补丁」。 */
  val MAGIC = byteArrayOf(0x5A, 0x4E, 0x55, 0x31) // "ZNU1"

  const val VERSION = 1

  /** 从已安装 APK 的某段偏移拷字节。 */
  const val OP_COPY = 1

  /** 补丁里自带的字节。 */
  const val OP_LITERAL = 2

  const val SHA256_BYTES = 32

  // 失败原因用短码而不是异常原文：调用方要把它翻成用户能看懂的一句话（见 UpdateHttp.failReason）。
  const val ERR_FORMAT = "PATCH_FORMAT"
  const val ERR_VERSION = "PATCH_VERSION"
  const val ERR_CORRUPT = "PATCH_CORRUPT"
  const val ERR_TRUNCATED = "PATCH_TRUNCATED"
  const val ERR_SIZE_MISMATCH = "PATCH_SIZE_MISMATCH"
}
