package com.zoneu.app.update

import android.content.Context
import android.util.Log
import com.zoneu.app.agent.appVersionName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 更新流程的**唯一状态源**。
 *
 * 以前提醒弹窗与 设置 → 版本更新 各写一份自己的状态机，于是同一件事有两套行为：
 * 弹窗里看不到进度、设置里不能取消、两边都能同时发起一次下载。把状态提到这里之后，
 * 谁先发起都一样，切页面不会丢，进度也只有一个。
 *
 * 四条刻意的性质：
 *
 * 1. **不后台轮询**（与连接器同一条原则）：只有 [check] 会联网，而它只由用户操作或
 *    进程启动时的提醒触发。
 * 2. **取消是「暂停」不是「放弃」**：半截文件留在 cacheDir，下次点更新从断点接着下。
 * 3. **失败带着 release**：网络断了之后重试直接回到下载（接着续传），而不是从头再查一次
 *    —— 查一遍再点一次是两次往返，用户看到的却只有「又失败了」。
 * 4. **进程活着期间下载不会因为切页面而中断**：状态与协程都挂在这个单例上，
 *    离开设置页 / 关掉弹窗都不影响。进程被杀则靠 `.part` 文件续传。
 */
object UpdateManager {
  /** 这次拉的是整包还是补丁。 */
  enum class Mode { FULL, PATCH }

  /** 更新链路的全部状态。UI 只读它，不做判断。 */
  sealed interface State {
    /** 还没查过。 */
    data object Idle : State

    data object Checking : State

    data class UpToDate(val version: String) : State

    /** 查到新版本，等用户点下载。 */
    data class Available(val release: AppUpdater.Release) : State

    /** [mode] 只影响界面文案（"正在下载补丁" / "正在下载完整包"），不影响任何判据。 */
    data class Downloading(
      val release: AppUpdater.Release,
      val progress: UpdateDownloader.Progress,
      val mode: Mode = Mode.FULL,
    ) : State

    /**
     * 补丁已下载完，正在把它拼成目标 APK 并校验。
     *
     * 独立成一个状态而不是复用 [Downloading]：这一步没有字节进度（配方是流式搬运，几十秒内结束），
     * 界面该显示不确定进度条 + 一句"正在合并更新"，而不是一个卡在 100% 的数字。
     */
    data class Merging(val release: AppUpdater.Release) : State

    /** 已下载且**校验通过**，等用户点安装。 */
    data class Ready(val version: String) : State

    /**
     * 缺「安装未知应用」授权。**带着 release**：用户去系统设置开完权限回来，
     * 下一步是接着下，而不是从头再查一次版本。
     */
    data class NeedsPermission(val release: AppUpdater.Release) : State

    /**
     * 失败。[release] 非空表示「版本是知道的，重试可以直接接着下」；
     * 为空则是检查阶段就失败了，重试得重新查。
     */
    data class Failed(val reason: String, val release: AppUpdater.Release? = null) : State
  }

  private val _state = MutableStateFlow<State>(State.Idle)
  val state: StateFlow<State> = _state.asStateFlow()

  // 单例的常驻作用域：下载不该跟着某个页面的生命周期走。
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  /**
   * 同一时间只允许一个更新操作。
   *
   * 单槽而不是按操作分槽：检查与下载本来就是串行的（先查到才可能下），而两个下载并行只会
   * 互相抢 cacheDir 里的同一个 `.part`。并发进来的第二次调用直接返回，不排队 ——
   * 用户看到的按钮状态本来就只有一个。
   */
  private var job: Job? = null

  /** 查一次最新版本。同一时间只允许一个任务 —— 提醒弹窗与设置页都会调它。 */
  fun check(context: Context) {
    if (job?.isActive == true) return
    val app = context.applicationContext
    _state.value = State.Checking
    job =
      scope.launch {
        val result = withContext(Dispatchers.IO) { AppUpdater.check(appVersionName()) }
        _state.value =
          when (result) {
            is AppUpdater.UpdateCheck.UpToDate -> State.UpToDate(result.current)
            is AppUpdater.UpdateCheck.Available -> State.Available(result.release)
            is AppUpdater.UpdateCheck.Failed -> State.Failed(result.reason)
          }
      }
  }

  /**
   * 推进下载：可更新、以及「刚去系统设置授完权又点回来」两种状态都从这里进。
   *
   * **续传是自动的** —— [UpdateDownloader.download] 自己看 cacheDir 里有没有这个版本的
   * 半截文件。缺「安装未知应用」授权时**直接把用户送到系统设置页**，而不是静静失败：
   * 那是新装用户的默认状态（adb 装的也一样），一句「失败」帮不了任何人。
   */
  fun download(context: Context) {
    val release = releaseForDownload() ?: return
    begin(context.applicationContext, release)
  }

  /** 失败之后重试：知道版本就接着下（续传），不知道就重新查。 */
  fun retry(context: Context) {
    val failed = _state.value as? State.Failed ?: return
    val release = failed.release
    if (release == null) check(context) else begin(context.applicationContext, release)
  }

  private fun releaseForDownload(): AppUpdater.Release? =
    when (val current = _state.value) {
      is State.Available -> current.release
      is State.NeedsPermission -> current.release
      else -> null
    }

  private fun begin(app: Context, release: AppUpdater.Release) {
    if (!AppUpdater.canInstall(app)) {
      _state.value = State.NeedsPermission(release)
      requestInstallPermission(app)
      return
    }
    startDownload(app, release)
  }

  private fun startDownload(app: Context, release: AppUpdater.Release) {
    if (job?.isActive == true) return
    _state.value = State.Downloading(release, UpdateDownloader.Progress(0, -1))
    job =
      scope.launch {
        val outcome = runCatching { downloadOrPatch(app, release) }
        outcome
          .onSuccess {
            _state.value = State.Ready(release.version)
            install(app)
          }
          .onFailure { error ->
            // 取消不是失败：半截文件留着，状态退回「可更新」让用户再点一次就能接着下。
            if (error is CancellationException) throw error
            // 失败原因翻译成用户能行动的一句话（连不上 GitHub = 开 VPN，服务器 404 = 重试没用），
            // 而不是把异常消息原样甩出去。见 UpdateHttp.failReason。
            _state.value = State.Failed(UpdateHttp.failReason(error), release)
          }
      }
  }

  /**
   * 先试补丁，不行就整包。
   *
   * 「不行」包括判据不过（[UpdatePlanner.resolve] 直接给 [UpdatePlan.Full]）和补丁路径上任何一步
   * 抛错。**两种情况都不写 [State.Failed]**：兜底路径会成功，报错只会让用户以为更新坏了。
   */
  private suspend fun downloadOrPatch(app: Context, release: AppUpdater.Release): File {
    val plan = withContext(Dispatchers.IO) { UpdatePlanner.resolve(app, release) }
    if (plan !is UpdatePlan.Patch) return downloadFull(app, release)
    val patch =
      runCatching {
        val file =
          UpdateDownloader.downloadPatch(app, release, plan.entry, plan.url) { progress ->
            _state.value = State.Downloading(release, progress, Mode.PATCH)
          }
        _state.value = State.Merging(release)
        // 合并是 150 MiB 级的随机读 + 顺序写：必须在 IO 线程上，否则主线程会 ANR。
        withContext(Dispatchers.IO) { PatchInstaller.merge(app, plan, file, release.version) }
      }
    val merged = patch.getOrNull()
    if (merged != null) return merged
    val error = patch.exceptionOrNull()
    // 取消不是失败：交给调用方按 CancellationException 处理（半截文件留着续传）。
    if (error is CancellationException) throw error
    Log.w("zoneu", "补丁路径失败，改用整包：${error?.javaClass?.simpleName}: ${error?.message}")
    return downloadFull(app, release)
  }

  private suspend fun downloadFull(app: Context, release: AppUpdater.Release): File =
    UpdateDownloader.download(app, release) { progress ->
      _state.value = State.Downloading(release, progress, Mode.FULL)
    }

  /**
   * 取消下载。
   *
   * 状态先退回 [State.Available] 再取消协程：反过来写的话，协程被取消后 `onFailure` 里的
   * `CancellationException` 分支还没跑，UI 会有一瞬间停在「下载中」。
   */
  fun cancel() {
    val downloading = _state.value as? State.Downloading ?: return
    _state.value = State.Available(downloading.release)
    job?.cancel()
    job = null
  }

  /** 把已下载校验过的包交给系统安装器。用户在安装器里退回来时，再点一次就是这里。 */
  fun install(context: Context) {
    val version = (_state.value as? State.Ready)?.version ?: return
    val app = context.applicationContext
    runCatching { app.startActivity(AppUpdater.installIntent(app, UpdateDownloader.apkFile(app, version))) }
      .onSuccess {
        // 装上了之后，比它旧的「跳过」记录就没意义了。
        UpdatePreferences.pruneAtOrBelow(app, version)
      }
      .onFailure { error -> _state.value = State.Failed(error.javaClass.simpleName, null) }
  }

  /**
   * 去系统设置开「安装未知应用」。
   *
   * 不自动重试：授权是异步的（用户可能切走很久），回来之后点一下「更新」就接着下 ——
   * 状态里带着 release，所以不需要重查版本。
   */
  fun requestInstallPermission(context: Context) {
    val app = context.applicationContext
    runCatching { app.startActivity(AppUpdater.unknownSourcesIntent(app)) }
      .onFailure { _state.value = State.Failed("NO_SETTINGS", releaseForDownload()) }
  }
}
