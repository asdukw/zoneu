param(
  # 要打包的运行时目录（bundle-dsh.ps1 的 staging 树）。
  [Parameter(Mandatory = $true)][string]$SourceDir,
  # 输出 zip 路径。
  [Parameter(Mandatory = $true)][string]$ZipPath
)

# 把运行时目录打成 **可复现** 的 zip：同一份内容两次打包必须逐字节相同。
#
# 为什么不能直接用 ZipFile::CreateFromDirectory：它把每个文件的 mtime 写进条目，
# 而 staging 树是每次装配现解出来的，于是「内容 99.97% 相同的两个版本」会产出
# 两个完全不同的 zip。实测 v2.1.0 -> v2.2.0 的 dsh-runtime.zip 内层 25953 个条目里
# 25945 个内容（CRC）完全相同、只有时间戳不同，却让每次更新多传 63 MiB。
# 补丁的价值完全取决于这条性质（见 openspec/changes/apk-delta-updates/design.md D7）。
#
# 三个刻意的选择：
#   1. **固定时间戳**：1980-01-01 00:00 是 zip 的 DOS 时间下限。用本地时间的墙钟构造，
#      .NET 就不会额外写 extended-timestamp 扩展字段 —— 换成 UTC 会让 Windows 开发机
#      与 UTC 时区的 CI 产出不同的字节（实测：offset 与机器本地时区不一致时 .NET 会补字段）。
#   2. **条目按名称 ordinal 排序**：目录枚举顺序取决于文件系统，不能依赖。
#   3. **不写目录条目**：App 侧解压时对每个文件都会 mkdirs 父目录（见 NodeRuntime 的
#      installDshRuntime），目录条目是纯冗余。
$ErrorActionPreference = "Stop"

$root = (Resolve-Path -LiteralPath $SourceDir).Path
$root = $root.TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
$outFull = [System.IO.Path]::GetFullPath($ZipPath)

# zip 能表示的最早时间。固定成常量而不是「现在」或「文件的 mtime」。
$fixedTime = [DateTimeOffset]::new([DateTime]::new(1980, 1, 1, 0, 0, 0, [DateTimeKind]::Local))

$files = [System.IO.Directory]::EnumerateFiles($root, '*', [System.IO.SearchOption]::AllDirectories)
$entries = [System.Collections.Generic.List[object]]::new()
foreach ($full in $files) {
  if ([System.IO.Path]::GetFullPath($full) -eq $outFull) { continue }
  $rel = $full.Substring($root.Length).TrimStart([char[]]@('\', '/'))
  $entries.Add([pscustomobject]@{ Name = ($rel -replace '\\', '/'); Full = $full })
}
if ($entries.Count -eq 0) { throw "运行时目录是空的：$root" }

$names = [string[]]($entries | ForEach-Object { $_.Name })
$sortedNames = [string[]]$names.Clone()
[Array]::Sort($sortedNames, [StringComparer]::Ordinal)
$byName = @{}
foreach ($entry in $entries) { $byName[$entry.Name] = $entry.Full }

$outDir = Split-Path -Parent $outFull
if ($outDir) { New-Item -ItemType Directory -Force -Path $outDir | Out-Null }
if (Test-Path -LiteralPath $outFull) { Remove-Item -LiteralPath $outFull -Force }

Write-Host "packing $($sortedNames.Count) entries -> $outFull"
[System.Reflection.Assembly]::LoadWithPartialName("System.IO.Compression.FileSystem") | Out-Null
$archive = [System.IO.Compression.ZipFile]::Open(
  $outFull,
  [System.IO.Compression.ZipArchiveMode]::Create
)
try {
  foreach ($name in $sortedNames) {
    $entry = $archive.CreateEntry($name, [System.IO.Compression.CompressionLevel]::Optimal)
    # 时间戳必须在打开条目流**之前**设：本地文件头是随第一次写入落盘的。
    $entry.LastWriteTime = $fixedTime
    $input = [System.IO.File]::OpenRead($byName[$name])
    try {
      $output = $entry.Open()
      try { $input.CopyTo($output) } finally { $output.Dispose() }
    } finally {
      $input.Dispose()
    }
  }
} finally {
  $archive.Dispose()
}

# 打完立刻验「这确实是个 zip」：前四个字节必须是 PK\x03\x04。
# 这类错误在设备上表现成「解压失败 / 运行时起不来」，不值得留到那一步才发现。
$head = [System.IO.File]::ReadAllBytes($outFull)[0..3]
if ($head[0] -ne 0x50 -or $head[1] -ne 0x4B -or $head[2] -ne 0x03 -or $head[3] -ne 0x04) {
  throw "打出来的不是 zip（头四字节不是 PK）：$outFull"
}

# 条目名必须是正斜杠：反斜杠是不合规的 zip，App 侧 ZipInputStream 会把它当成扁平文件名，
# 解出来是 `node_modules.bin\cordis` 这样的文件而不是目录树，运行时起不来。
# 本脚本按构造只写 '/'，这条断言留着是为了下次有人改回 CreateFromDirectory 时立刻报错。
$verify = [System.IO.Compression.ZipFile]::OpenRead($outFull)
try {
  $bad = $verify.Entries | Where-Object { $_.FullName.Contains([char]92) } | Select-Object -First 1
  if ($null -ne $bad) {
    throw "zip 条目名用了反斜杠（$($bad.FullName)）—— 这个包在设备上解不出目录树。"
  }
} finally {
  $verify.Dispose()
}
Write-Host "done: $([math]::Round((Get-Item $outFull).Length / 1MB, 1))MB"
