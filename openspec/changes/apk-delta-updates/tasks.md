# Tasks

## 1. 可复现打包（补丁的前置条件，先单独发一次）

- [x] 1.1 改 `tools/bundle-dsh.ps1`：`tar` 固定 `--mtime/--sort=name/--owner=0 --group=0 --numeric-owner`，
      zip 固定条目时间戳、按名称排序、去掉 extra field。验证：同一份源码连续装配两次，
      `sha256(android/app/src/main/assets/dsh-runtime.zip)` 相同（记录两次哈希到任务说明里）。
- [x] 1.2 在 `.github/workflows/assemble.yml` 加可复现性门禁：连续装配两次并比较哈希，不同则失败。
      验证：临时给打包脚本注入一个随机字段，门禁必须红；恢复后绿。
- [x] 1.3 在 `docs/build.md` 记录门禁、它的存在理由（补丁有效性依赖它）与失败时的排查入口。
      验证：文档里给出的命令按原样可跑通。

## 2. 补丁工具（离线可测，CI 与设备共用一套实现）

- [x] 2.1 实现 `ZNU1` 格式的生成与应用（纯 Kotlin，放在 app 的 `update/patch/` 下，不依赖 Android）：
      gear-hash CDC 分块 + copy/literal 配方 + 流式应用，解码端校验 `offset+len ≤ 基线长度`
      与写满 `targetSize`。验证：单测用构造的基线/目标对做往返，逐字节相等；对"目标更短 /
      target 中间插入导致位移 / 全是新字节 / 空基线"各有断言。
- [x] 2.2 加 Gradle 任务 `:app:generatePatch`（JVM 上直接调用同一套实现），让 CI 不需要第二份实现。
      验证：本地对两个真实 APK 生成补丁，应用回基线后与新 APK 的 `sha256` 相同。
- [x] 2.3 实现清单生成与自洽校验（`format` / `fromVersionCode` / `baseSha256` / `patchSha256` /
      `patchSize` 与资产逐项相符）。验证：单测覆盖"清单与资产不符必须失败"，以及 `format` 递增时
      旧设备侧解析的行为。

## 3. 设备侧：补丁优先、全量兜底

- [x] 3.1 `AppUpdater` 增加清单资产识别与解析（kotlinx.serialization），保持现有 APK 资产匹配不变。
      验证：单测用真实形状的 release JSON 断言"有清单/无清单/清单损坏"三种结果，且 `.patch`
      不会被误选成 APK。
- [x] 3.2 实现判据链：清单存在 → `format` 认识 → 本机 `versionCode` 在表内 → 空间够 →
      `sha256(sourceDir) == baseSha256`。验证：单测逐条造出"不满足"的输入，断言选择结果为全量；
      并断言预检失败时不发起补丁下载。
- [x] 3.3 `UpdateDownloader` 复用现有 `fetchInto` 取补丁并校验 `patchSha256`；`UpdateManager` 增加
      "合并中"状态，补丁路径失败时**不写 `Failed`**，直接转全量下载。验证：单测断言失败路径的
      状态序列为 `Downloading(patch) → Downloading(full)`，且不出现 `Failed`。
- [x] 3.4 补丁产物必须通过与全量相同的整包 `sha256` + 签名证书校验后才允许安装。验证：单测注入
      "产物哈希不符""签名不符"两种结果，断言不产生安装入口且回退全量。
- [x] 3.5 存储清理：只保留当前目标版本的半截文件与边车；已安装生效后的下一次启动清理该次下载的包
      （交付给安装器时不得立即删除）。验证：单测覆盖"安装生效后启动 → 文件被删"与
      "安装器放弃 → 文件保留"。
- [x] 3.6 界面文案三态（正在下载补丁 / 正在合并更新 / 静默转完整下载），复用
      `ui/components/UpdateProgress.kt`。验证：`progressLabel` 与弹窗文案的既有单测更新并通过。
- [x] 3.7 更新 `docs/app-updater.md`：补丁路径、判据表、回退语义、排障新增行。验证：文档与代码
      一致，按文档描述可在设置页观察到对应文案。
- [x] 3.8 跑门禁：`:app:testDebugUnitTest` + `spotlessCheck detekt :app:lintDebug`。验证：四条命令全绿。

## 4. 发布链路

- [x] 4.1 `release.yml` 在签名出包后取窗口内最近 6 个应用版本（排除 `rg-*`），下载其已发布 APK 并
      按其 `.sha256` 校验，然后生成补丁与清单。验证：本地用两个真实 release 跑一遍生成器，
      资产齐全且清单字段与实际资产相符。
- [x] 4.2 加往返验证门禁：`apply(旧包, patch)` 的 `sha256` 必须等于新包 `sha256`；失败则不上传该补丁。
      验证：故意改坏一个补丁字节，该补丁不上传且发布继续。
- [x] 4.3 补丁生成失败不阻断发布，原因写入 publish summary。验证：构造一次失败，确认 release 仍然
      产出全量包且 summary 有原因。
- [x] 4.4 provenance 覆盖新增资产；`docs/releasing.md` 写明补丁资产清单、不可变约定、
      "重发 tag 必须重算补丁"以及窗口资产不要当垃圾清理。验证：文档步骤按原样可执行。

## 5. 设备验证（CI 绿不等于能用）

- [ ] 5.1 真机走一次补丁路径更新：从上一版升到带补丁的新版，界面出现"正在下载补丁"与
      "正在合并更新"，安装成功，版本号变化。验证：设备上观察到的状态序列与安装结果。
      已完成设备上的合并核心：`PatchMergeDeviceTest` 用已安装 APK 当基线合并宿主生成的真实
      24.3 MB 补丁，232 MiB 基线 → 169 MiB 产物 647 ms，产物 sha256、签名证书与已安装包一致，
      补丁用完即删。界面状态序列与版本变化待首个带补丁的 release 发布后走一遍。
- [ ] 5.2 真机验证回退：人为制造基线不符（换签名材料或改 base 字节）与窗口外版本，两种情况下都
      静默走全量、界面不出现"更新失败"。验证：两种情形的界面表现与最终安装结果。
      已完成基线不符的设备验证：期望哈希改错时抛 `PATCH_OUTPUT_MISMATCH`，`cacheDir` 不留
      半成品。"静默转全量"与窗口外版本待在真实发布上验证。
- [x] 5.3 真机验证清理：安装生效后重启，`cacheDir` 里该次更新的下载物被删除。验证：`run-as`
      或设备文件列表对比更新前后。
      已验证（PLZ110 / Android 17，debug 包）：首次启动写入水位 1793608383011 且不删；造
      `update-9.9.9.apk` 后普通启动文件仍在（安装器取消，包要保留）；`adb install -r` 后启动
      `update-*` 计数 0，水位推进到 1793608440945。

## 6. 集成检查

- [x] 6.1 全量路径回归：debug 构建不参与更新、断点续传（中断后继续）、取消后进度不回 0。
      验证：真机或既有单测覆盖的三条路径均正常。
      已验证：debug 构建不自动检查更新（`UpdateReminder.kt:51`）；`UpdateDownloaderResumeTest`
      用本地 HTTP 服务器按 200/206/416 跑通续传与「取消保留半截文件供下次续传」。本 change
      未改动这三条路径。
- [x] 6.2 `CHANGELOG.md` 与 `AGENTS.md` 路由表（若新增文档）同步。验证：条目存在且指向正确文件。

## Workflow follow-up

- 按仓库评审要求（PR + `ci.yml` 四个 required check）合并后归档本 change。
- 归档后核对 `openspec/specs/app-updates/spec.md` 的 Purpose 与需求已正确落库。
