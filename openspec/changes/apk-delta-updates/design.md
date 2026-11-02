# Design

## Context

见 `proposal.md` — Why。设计只依赖三条现状：

- 更新链路已分三层：`AppUpdater`（查版本/挑资产/交安装器）、`UpdateDownloader`（取字节，含
  `If-Range` 续传与 206/416 分支）、`UpdateManager`（唯一状态源 `StateFlow`）。
- 校验锚点是"release 里的 `.sha256` + 下载包签名证书 == 当前安装包签名"，两者都对整包算。
  补丁不需要新锚点，只需要**产出一份必须过这两道校验的字节**。
- 已安装 APK 自身可读（`applicationInfo.sourceDir`），所以基线字节不需要额外下载。

实测约束（v2.1.0 → v2.2.0，305 个条目）：

| 项 | 值 |
|---|---|
| 全量 | 155.9 MiB |
| 未变条目 | 297 条 / 81.2 MiB |
| 变化条目 | 8 条 / 74.6 MiB |
| 其中 `dsh-runtime.zip` | 63.0 MiB（内层 25,953 条里 25,945 条内容相同，只有 mtime 不同） |
| 其中 `classes2.dex` | 11.0 MiB（内容定义分块比对：新版 37% 的字节在旧版原样存在） |
| `classes.dex` / `classes3.dex` | 逐字节未变（说明 AGP 的 dex 产出本身可复现） |

## Goals / Non-Goals

**Goals:**

- 常规更新从 155.9 MiB 降到个位数 MiB（可复现修复后 ~4–8 MiB）。
- 补丁路径的失败率不影响可用性：任何异常都落回全量，用户不会看到"更新失败"。
- 发布侧成本随版本线性：每个 release 只多发窗口内 6 个补丁，不重跑历史。

**Non-Goals:**

- 整包 bsdiff、链式补丁、载荷外置、dex 压缩、剔死文件、R8（理由见 `proposal.md` 非目标）。

## Decisions

### D1 粒度：内容定义分块的 copy/literal 配方，不解析 zip 结构

产物必须与目标 APK **逐字节相同**（才过整包 `sha256`）。条目级重建这条路要求精确复原所有
头部偏移（条目大小一变，本地头与中央目录偏移整体位移）、`AndroidManifest.xml` 与
`resources.arsc` 的字段细节，以及**无法从旧包推导**的 APK Signing Block（只能整块当字面量搬）。
代码量与出错面都远大于收益。

所以：对**整个文件**做内容定义分块（CDC），命中就发 `COPY`，否则发 `LITERAL`。签名块、头部字段、
偏移位移全部自动正确，应用侧内存 O(块)。

**为什么不整包 bspatch**：`bspatch` 需要旧包 + 新包同时在内存（~310 MiB），低端机会 OOM；
生成侧还要对 156 MiB 做后缀排序，矩阵 6 个基线是分钟级。

**实测**（v2.1.0 `classes2.dex` 11.0 MiB → v2.2.0 11.0 MiB，块均值 1/2/8 KiB）：

| 块均值 | 配方 | deflate 后 |
|---|---|---|
| 1 KiB | 8.24 MiB | 3.34 MiB |
| 2 KiB | 8.65 MiB | 3.43 MiB |
| 8 KiB | 9.53 MiB | 3.65 MiB |

其余 81 MiB 未变字节退化成约 0.2–0.4 MiB 的 `COPY` 指令 → **一个补丁 ≈ 4 MiB**（与目标一致）。
取块均值 2 KiB（1 KiB 只快 0.1 MiB，却把指令数翻倍）。

**端到端实测**（`:app:generatePatch`，含往返验证）：

| 场景 | 补丁 |
|---|---|
| v2.1.0 → v2.2.0（两个真实发布包，运行时归档被重新打包过） | 25.9 MiB |
| 同上，但运行时归档保持字节不变 | **3.9 MiB** |

第二行就是 D1 修复生效之后的常态 —— 也是这个 change 的目标值。

**Alternatives**：条目级 bsdiff + zip 重建（被上面两条否掉）；整包 bspatch（被内存否掉）。

### D2 补丁格式：`ZNU1` + 整体 deflate

```
patch 资产 = deflate( ZNU1 | version | targetSize | targetSha256 | op* )
op := COPY    baseOffset(u64), length(u32)   <- 从已安装 APK 的该偏移拷字节
    | LITERAL length(u32), bytes             <- 补丁里自带的字节
```

- 块用 gear-hash CDC（均值 2 KiB / min 512 / max 8 KiB），base 与 target 两侧同参数分块；
  哈希命中**且逐字节相同**才发 `COPY`，否则 `LITERAL`——编码器保证字节精确，最终仍由整包
  `sha256` 兜底。
- 补丁文件是 raw deflate，`patchSha256` 覆盖压缩后的字节，设备先校验再解压。
- `targetSize` 让解码端能边写边校验长度（写满才算成功），不必先解压到内存。
- **编码器带一个拒绝条件**：配方 ≥ 全量的 60% 时不出补丁（运行时归档被整体重打包就是这种情况，
  deflate 流不重同步）。设备端见到这种版本直接走全量。

**Alternatives**：直接存 bsdiff 三段（要带后缀排序的生成器）；把清单塞进补丁头部（设备端反而
需要先下补丁才知道要不要用，多一次往返）。

### D3 清单：latest release 里一个 `zoneu-patches.json`

```json
{ "format": 1,
  "to": { "versionCode": 23, "versionName": "2.2.0", "sha256": "…" },
  "patches": [
    { "fromVersionCode": 22, "fromVersionName": "2.1.0", "baseSha256": "…",
      "patch": "zoneu-2.1.0-to-2.2.0-arm64-v8a.patch",
      "patchSha256": "…", "patchSize": 7340032 } ] }
```

- 一个文件 = 一次请求拿到全部判断依据，设备端不需要遍历 release 列表拼链，也不会在多次
  API 调用之间读到不一致的状态。
- `format` 是"设备能否理解这份清单与补丁格式"的开关。将来换格式时递增，旧设备自动落回全量。
- 命名与现有 `AppUpdater.parseRelease` 的资产匹配（`startsWith("zoneu-") && endsWith("-arm64-v8a.apk")`）
  不冲突：`.patch` 不会被当成 APK 选中。

**Alternatives**：每个补丁配一个 json（N 次请求）；把基线信息编进 tag 名（脆弱、不可扩展）。

### D4 矩阵窗口：最近 6 个应用版本

- 按 `versionCode` 取最近 6 个应用版本（排除 `rg-*` 这类非应用 release）。
- **按版本数而不是天数**：实测发版节奏不规律（0.10→0.16 一天三个，1.0→2.0 隔六天），
  版本数更能保证"每个补丁都对应真实用户可能停留的版本"。
- 生成时从 GitHub 取这 6 个已发布 APK（下载后校验其 `.sha256`，不信任缓存副本），
  或使用 CI 缓存 + 同样校验；**必须基于已发布的签名字节**，不能用构建中间物。
- 窗口外一律全量：与今天的行为一致，不会更差。

### D5 设备侧判据顺序（把最便宜的检查放最前）

```
latest release → 清单存在? → format 认识? → 本机 versionCode 在表内?
  → 空间够? → base sha256 相符? → 下载补丁 → patch sha256 相符?
  → 应用 → 整包 sha256 + 签名 → 安装
任一环失败 → 清理 → 全量
```

- **base sha256 预检**放在下载补丁之前：读一次 `sourceDir`（156 MiB，约 1–2 秒，IO 线程）
  比白下 8 MiB 便宜。这一步同时挡住"重发过 tag / 换过签名材料 / 从别处安装"三种基线不符。
- 空间预检按 `补丁 + 产物 + 余量` 算；峰值 ≈ 已装 156 MiB + cacheDir 内 ~164 MiB。

### D6 回退语义与状态机

- `UpdateManager` 增加"合并中"状态（补丁下载完成 → 应用 → 校验），复用现有进度条组件。
- 补丁路径失败**不写 `Failed`**，直接转 `Downloading(full)`：兜底会成功，报错只会误导。
- 界面文案三态："正在下载补丁" → "正在合并更新" → 失败时静默变"正在下载完整包"。

### D7 可复现打包的落点

实测内层 25,945/25,953 条目 CRC 相同、只有 `date_time` 不同，所以修复点集中在打包这一步：

- `tar`：固定 `--mtime`、`--sort=name`、`--owner=0 --group=0 --numeric-owner`。
- zip：固定条目 `date_time`、条目按名称排序、去掉 extra field（`-X`）。
- 门禁：连续装配两次比 SHA-256，放进 `assemble.yml`（纯离线，不需要设备）。

### D8 发布链路

```
tag → 装配 → 签名 → 出 APK
            → 取窗口内 6 个已发布 APK（下载 + 校验 sha256）
            → 生成补丁 → 往返验证（apply 后 sha256 == 新 APK sha256）
            → 上传：APK + .sha256 + patches.json + 每个 patch + .sha256
            → provenance 覆盖新增资产
```

- 补丁生成或往返验证失败：**不阻断发布**，该补丁不上传，publish summary 写明原因
  （补丁是优化，不是发布正确性的一部分；一个没验证过的补丁发出去只会白耗用户流量）。
- 不可变约定：补丁与清单随 tag 冻结；重发 tag 必须重算并替换（与 Immutable releases 的
  "先删 release" 规则一致，写进 `docs/releasing.md`）。

### D9 明确不做的两件相邻的事

- **dex 压缩**（`packaging.dex.useLegacyPackaging`）：deflate 流位置敏感，dex 改一字节整条流
  重排，条目内 delta 会从 ~4 MiB 退化到接近 11 MiB。开它等于拆掉本设计的前提。
- **运行时归档剔死文件**（sourcemap/.d.ts/.pdb/win32，实测 29.5 MiB）：属 APK 体积优化，
  与更新流量无关；它会让某个版本的补丁一次性变大，适合单独一个 change。

## Risks / Trade-offs

| 风险 | 缓解 |
|---|---|
| 补丁应用出错产出错误字节 | 产物必须过整包 SHA-256 + 签名（与全量同一道闸）；不符即丢，转全量 |
| 基线不符（重发 tag / 换签名 / 别处安装） | base sha256 预检，且发生在下载补丁之前 |
| 设备剩余空间不足 | 空间预检；峰值约 320 MiB（含已装包） |
| 配方解码的边界 bug（越界 COPY / 长度不符） | 解码端逐条校验 offset+len ≤ 基线长度、写满 targetSize；单测 + CI 对真实产物做往返验证 |
| 补丁路径的失败被误显示为"更新失败" | 状态机层面禁止：补丁失败只转 `Downloading(full)`，不写 `Failed` |
| 矩阵资产累积 | 窗口固定 6 版，每 release 约 24–48 MiB；写进 `docs/releasing.md` 以免被当垃圾清理 |
| 用户跨 6 个版本才更新 | 落回全量，与今天一致；链式补丁留作后续（不改资产契约即可加） |
| CI 时长增加 | 6 次旧包下载 + 6 次往返验证，约 5 分钟；旧包可缓存但要按 `.sha256` 校验 |
| 可复现修复本身改变产物 | 修复后的第一个版本，其补丁会一次性偏大（`dsh-runtime.zip` 与上一版不同），之后恢复正常 |

## Migration Plan

1. **先单独发一次"可复现打包"**：它只改 `bundle-dsh.ps1` + 加 CI 门禁，不改任何运行时行为。
   这一版的 `dsh-runtime.zip` 与上一版字节不同（一次性代价），之后不再变化。
2. **再发补丁能力**：第一个带补丁的 release 里，窗口内的旧版本都没有补丁资产 →
   清单只列出能生成的补丁；设备端没有命中就走全量。**自然降级，不需要迁移逻辑**。
3. 回滚：删掉清单资产即可让所有设备回到全量路径（`format` 不认识或清单缺失都落回全量）。
   客户端不需要回滚版本。

## Open Questions

- 窗口取 6 版是否需要随发版节奏调整：等有实际数据（例如补丁命中率）再定，不影响本设计。
- 是否加链式补丁：只在"用户经常跨窗口更新"被证实后才值得做，资产契约已为它留好位置。
