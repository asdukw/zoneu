# Proposal

## Why

用户反馈"每次更新都很大"，实测成立：每次更新下载的是完整 APK（v2.2.0 = 163,479,578 B ≈ 155.9 MiB），
而这个体积与本次改动无关 —— v2.1.0→v2.2.0 只改了 60 个文件、+2570/−932 行，却要重下整包。

逐 zip 条目比对两个已发布的包（305 个条目）显示：**未变 297 条 81.2 MiB，变化 8 条 74.6 MiB**。
其中 63.0 MiB 是 `assets/dsh-runtime.zip`，而它内层 25,953 个条目里 25,945 个内容（CRC）完全相同，
只有 mtime 变了 —— 纯粹是重新打包造成的噪声。真正的内容变化只有 11.6 MiB（`classes2.dex` 11.0 +
`resources.arsc` 0.5 + 其余）。发版节奏又很密（最近 13 个应用版本 / 7 天），用户每次都被收全款。

补丁路径在 APK 上是可行的，因为补丁只用于**生产一份必须通过现有整包校验的字节**：现有
`sha256(整包) + 签名证书比对` 已经能兜住一切错误，补丁不承担任何信任职责。

## What Changes

- **新增矩阵式补丁发布**：每个 release 除全量 APK 外，附带"最近 6 个应用版本 → 本版"的补丁，以及
  一份 `zoneu-patches.json` 清单（带 `format` 版本号），设备端一次请求拿到全部判断依据。
- **新增设备侧"补丁优先、全量兜底"**：7 条兼容判据（清单存在 / 格式认识 / 本机版本在窗口内 /
  base 字节与清单相符 / 补丁完整 / 空间够 / 产物过整包校验与签名），任一不满足即静默回退全量。
- **新增可复现的运行时打包（补丁的前置条件）**：`dsh-runtime.zip` 两次装配必须逐字节相同。
  不修这条，每个补丁就是 74.6 MiB，矩阵成本失去意义。
- **新增 CI 门禁**：补丁往返验证（apply(旧包, patch) 的 sha256 必须等于新包 sha256）、清单自洽。
  补丁生成失败不阻断发布，该版本只发全量并在 summary 写明原因。
- **不改**：全量下载路径、断点续传、SHA-256 + 签名校验语义、不静默安装、不后台轮询。

**明确不做**（本 change 的非目标，避免范围蔓延）：

- 整包 bsdiff（设备侧合并峰值约 310 MiB，低端机会 OOM）；本 change 走条目级补丁。
- 链式补丁（窗口外直接走全量，与今天行为一致）。
- `dsh-runtime.zip` / `proot-rootfs.bin` 外置下载（那是 APK 体积问题，不是更新流量问题）。
- dex 压缩（`packaging.dex.useLegacyPackaging`）：**与补丁互斥** —— deflate 流位置敏感，
  改一个字节整条流重排，条目内 delta 会从 ~4 MiB 退化到接近 11 MiB。
- 运行时 zip 剔死文件（sourcemap/.d.ts/.pdb/win32，实测 29.5 MiB）与 release 开 R8：
  属 APK 体积优化，另开 change。

## Capabilities

### New Capabilities

- `app-updates`: 应用内更新的完整行为 —— 查版本、补丁优先与全量兜底的兼容判据、下载与校验、
  交系统安装器、发布侧补丁与清单契约。

### Modified Capabilities

（无。项目此前没有 spec，本次为首次建立。）

## Impact

- 代码：`android/app/src/main/java/com/zoneu/app/update/`（新增补丁下载与应用、清单解析、
  兼容判据；`AppUpdater` 负责挑选补丁资产）、`ui/components/UpdateProgress.kt`（"正在合并更新"状态）、
  新增补丁应用实现（Kotlin，条目级，流式）。
- 发布链路：`.github/workflows/release.yml`（签名后生成补丁 + 往返验证 + 上传资产 + provenance）、
  `tools/verify-apk.sh`（补丁不是必需要素，全量仍必须齐全）、`tools/bundle-dsh.ps1`（可复现打包）、
  `tools/` 新增补丁生成与校验工具。
- 文档：`docs/app-updater.md`（补丁路径与排障）、`docs/releasing.md`（补丁资产与不可变约定，
  含"重发 tag 必须重算补丁"）、`docs/build.md`（可复现性门禁）。
- 测试：补丁往返与清单/判据的单测（离线可跑，无需设备）；真机需验证一次补丁路径更新与一次
  预检失败回退全量。
