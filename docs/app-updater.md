# 应用内更新：查版本 / 断点续传 / 交安装器

**什么时候读**：改 `update/`、更新弹窗、设置页「版本更新」、发布资产命名之前。

## 1. 分层：谁管什么

| 位置 | 管什么 |
|---|---|
| `update/AppUpdater.kt` | 查 GitHub Releases、挑出 apk + `.sha256` 配对的资产、交系统安装器 |
| `update/UpdateDownloader.kt` | 取字节：断点续传、取消、SHA-256 与**签名证书**比对 |
| `update/UpdateManager.kt` | 唯一状态源（`StateFlow`），提醒弹窗与设置页共用 |
| `update/UpdatePlan.kt` | 兼容判据（补丁还是全量）—— 纯函数 `decide()` + 带 Android 输入的 `resolve()` |
| `update/PatchInstaller.kt` | 把补丁拼成目标包，并做与全量同一道闸的校验 |
| `update/patch/` | 补丁格式（`ZNU1`）、分块、生成器、解码器、清单。纯 JVM，无 Android 依赖 |
| `ui/components/UpdateProgress.kt` | 进度条与文案，两处共用 |

合在一个里 detekt 的「一个 object 最多 20 个函数」会先炸，而那条规则在这里是对的。

## 2. 补丁优先、全量兜底

每个 release 除整包外还发 `zoneu-patches.json` 与「最近 6 个应用版本 → 本版」的补丁。
设备端**只有七条判据全过**才用补丁（`UpdatePlanner.decide`）：

| # | 判据 | 不满足 |
|---|---|---|
| C1 | release 里有 `zoneu-patches.json` | 全量 |
| C2 | 清单 `format` 本机认识（当前 1） | 全量 |
| C3 | 本机 `versionCode` 在清单里（窗口内） | 全量 |
| C4 | `sha256(sourceDir) == entry.baseSha256`（基线是我们发布过的那份字节） | 全量，**且不下载补丁** |
| C5 | `sha256(补丁) == entry.patchSha256` | 删掉，全量 |
| C6 | `usableSpace ≥ patchSize + 目标包 + 64 MiB` | 全量 |
| C7 | 产物整包 `sha256 == 目标哈希` 且签名证书 == 当前包 | 删产物，全量 |

C4 与 C7 是全部的安全感来源：**C4 保证补丁落在它被生成时的那份字节上，C7 保证结果必须过整包校验**。
补丁本身不承担任何信任职责 —— 它只是生产一份必须过 C7 的字节。清单同理：它只能让设备走全量或
多下一次几 MiB 的补丁，装不上任何别的东西。

三条与实现绑定的性质：

- **补丁路径的任何失败都不表现为「更新失败」**。判据不过、补丁下载失败、合并抛错，一律静默转全量
  （`UpdateManager.downloadOrPatch`）。兜底会成功，弹一句错误只会让用户以为更新坏了。
- **C4 的基线比对发生在下载补丁之前**（读一次 `sourceDir`，约 1–2 秒），比白下几 MiB 便宜。
- **合并阶段不可取消**：那是几十秒内结束的一步，半途而废只会留下半成品。

## 3. 硬规则

- **状态是单例的**：提醒弹窗与设置 → 版本更新共用 `UpdateManager` 的同一个 `StateFlow`。
  关掉弹窗**不会中断下载**；「后台继续」就是关掉它。
- **断点续传靠 cacheDir 里的 `.part` + `.etag` 边车**，两者都跨进程存活（实测重启模拟器后
  仍在，接着下）。三条不能改的细节：
  1. 用 **`If-Range` 而不是裸 `Range`** —— 同名文件在服务器上换了内容时，裸 Range 会把两份
     不同的字节拼在一起（哈希最终会拦下，但已经白下了几十 MB）；
  2. **206 的 `Content-Length` 是剩余长度**，总长要加回已落盘的 offset，否则进度条从中间起跳
     然后卡在 100% 之前；
  3. **416 不能直接失败** —— 本地那份比服务器上的还长时（换过 release / 上次写坏了），
     丢掉本地那份、去掉 Range 重新问一次。
- **续传不放松校验**：哈希与签名仍是对**整包**算的，拼接出来的字节必须整份对得上。
- **缺「安装未知应用」是新装用户的默认状态**（`adb install` 装的也一样），不是异常：
  点「更新」应当直接把用户送到系统设置页，而不是回一句「失败」。状态里带着 release，
  授完权回来点一次就接着下，不用重查版本。
- **从 application context 起系统页必须带 `FLAG_ACTIVITY_NEW_TASK`**：`UpdateManager` 拿的是
  application context，`unknownSourcesIntent` 少这个 flag 会 `ActivityNotFoundException`，
  现象是「更新没完成：NO_SETTINGS」（模拟器实测踩过一次）。
- **不静默安装、不后台轮询**（与连接器同一条原则）：只在用户点的时候联网，确认框留给系统安装器。
- **下载失败即停，不自动重试**：下载循环内不重试（会让人以为还在下），半截 `.part` 留着，
  用户重新点「更新」时续传。失败原因用 `UpdateHttp.failReason` 翻成可执行的说明，不直接显示
  异常原文；读到的长度不足 `Content-Length` 是 `TRANSFER_TRUNCATED`，不是下载完成。
- **安装生效后清理下载物**：判据是 `PackageInfo.lastUpdateTime` 的水位
  （`UpdateDownloader.cleanupAfterInstall`，启动时跑）。装上了 → 水位变 → 删掉 `cacheDir/update-*`；
  用户在安装器里取消 → 水位不变 → **包留着**，再点「安装」不用重下。**不能**在交付安装器时立刻删：
  安装器是异步读那个文件的。
- **补丁的有效性依赖可复现打包**：`dsh-runtime.zip` 两次装配必须逐字节相同（见
  [build.md](build.md) 的可复现性门禁）。不成立时补丁会退化到接近全量 —— 实测 v2.1.0→v2.2.0
  是 25.9 MiB，运行时归档字节稳定时同样一次更新只要 3.9 MiB。
- **debug 包不参与应用内更新**：debug 与 release 签名不同，下载的 release APK 必然装不上，
  自动检查（`UpdateReminder`）与设置页入口都只在 `!BuildConfig.DEBUG` 时生效。
  日常真机装 debug（见 [device-verification.md](device-verification.md) §1），更新走 `adb install -r`；release 的更新流程在
  CI / 可随时清空的设备上验证。

## 4. 验证

续传的分支逻辑在 `UpdateDownloaderResumeTest` 里**用本地 HTTP 服务器真跑一遍**
（200 / 206 / 416 三种回应各自的处置、取消后半截文件还在）—— 这些不需要设备，
而真跑一次 116MB 的下载既慢又不可重复。

## 5. 排障

| 现象 | 原因 / 处理 |
|---|---|
| 更新时下载失败报 `failed to connect to github.com` | 模拟器 `adb emu network speed` 会把 NAT 弄坏（宿主能连通、模拟器连不上同一个 IP）→ 冷启一次（`-no-snapshot-load`）。`-no-snapshot-save` 只防存，**不防读** |
| 点「更新」回一句「更新没完成：NO_SETTINGS」 | 从 application context 起系统设置页少了 `FLAG_ACTIVITY_NEW_TASK` → `AppUpdater.unknownSourcesIntent` |
| 进度条一直显示「已下载 0 B」 | 服务器没给 `Content-Length`（分块传输）→ 显示不确定进度条，不编一个假百分比。**不要**改成「卡在 0% 的确定进度条」 |
| 界面显示「正在合并更新」后一路失败并改下整包 | 补丁路径的兜底（正常行为）。真正的原因在 logcat：`补丁路径失败，改用整包：<异常类>: <短码>`。短码含义见 `DeltaFormat`（`PATCH_FORMAT` / `PATCH_VERSION` / `PATCH_CORRUPT` / `PATCH_TRUNCATED` / `PATCH_SIZE_MISMATCH`）与 `PatchInstaller`（`PATCH_OUTPUT_MISMATCH` / `SIGNER_MISMATCH` / `RENAME_FAILED`） |
| 每次更新都下整包，从不走补丁 | 先看清单有没有这个基线版本（C3）、`baseSha256` 对不对（C4 —— 重发过 tag 或换过签名材料就会不符）、剩余空间够不够（C6）。三条都不满足时**不报错**是设计 |
| 取消下载后进度条又从 0% 开始 | 半截文件没被复用：`update-<版本>.apk.part` 与它的 `.etag` 要一起留着；`.etag` 丢了就只能裸 `Range` |
