# Runtime 架构 (权威说明)

> 适用范围: `app/src/main/java/io/github/totomika/pocketmcp/runtime` + `host` + `mcp/ServiceManager`。
> 本文是线程模型与生命周期的唯一权威出处; 代码注释只做就地说明。
> 历史: 本分支早期修复以调用点补丁方式进行 (详见 git log), 2026-08 重构
> (commits 0b0f1d9 / b2c437f / 352f965) 后收敛为下述架构: JS 入口收敛到
> `RuntimeEntry.runJs`; Manager 内置 ioDispatcher 执行上下文; 异常一致性闭环。

## 1. 总览

一条脚本的生命周期链路 (启动/停止路径), 每一层标注其执行域:

```
┌──────────────────────────┐
│  UI / ViewModel          │  执行域 ①: Main (Android 主线程)
│  (不包线程, 不 withContext)│
└────────────┬─────────────┘
             │ suspend 调用: startService / stopService / addScript... (ServiceManager 内部切线程)
             ▼
┌──────────────────────────┐
│  ServiceManager          │  执行域 ②: manager ioDispatcher (默认 Dispatchers.IO)
│  (服务 CRUD / 启动停止 /  │  每个 public suspend 方法内部 withContext(ioDispatcher)
│   rebuildTools 补偿)      │  持 ServiceManager.mutex (下文 mutex ①)
└────────────┬─────────────┘
             │ acquire / release / updateRuntimeConfig / destroyAll
             ▼
┌──────────────────────────┐
│  RuntimeManager          │  同一 executor 域 ② (默认 Dispatchers.IO)
│  runtimes: Map<ns, Entry>│  持 RuntimeManager.mutex (下文 mutex ②)
│  (refCount / 中毒重建)    │
└────────────┬─────────────┘
             │ RuntimeFactory.create / startBackgroundJobs
             ▼
┌──────────────────────────┐
│  RuntimeEntry            │  执行域 ③: runtime 专属单线程 dispatcher
│  1 脚本 = 1 实例          │  newSingleThreadContext("quickjs-$namespace")
│  quickJs / refCount /    │  所有普通 JS 执行 (evaluate) 经 runJs 于该线程运行
│  poison / callQueue(8)   │
└────────────┬─────────────┘
             │ quickJs.evaluate / asyncFunction (jsMutex / jsResultMutex)
             ▼
┌──────────────────────────┐
│  QuickJS native          │  无 dispatcher 概念; JS 在"谁调用 evaluate 谁执行"
│  (libquickjs.so)         │  的线程上同步运行
└──────────────────────────┘
```

**工具的调用路径** (与生命周期路径正交, 同为单线程保证):

```
Ktor / MCP SDK (Server.addTool handler)
  └─▶ ToolBridge.callHandler ─▶ RuntimeManager.getRuntime (不增引用)
        └─▶ RuntimeEntry.callQueue (并发上限 8, 背压)
              └─▶ QuickJsBridge.callHandler = runtime.runJs { 两次 evaluate }
                    (async IIFE 启动 + 读全局结果, 详见 §6/§4)
```

## 2. 线程模型

### 2.1 三个执行域

| 域 | 承载者 | 说明 |
| --- | --- | --- |
| ① Main | UI / ViewModel | Android 主线程; 仅发起 suspend 调用, 不持有任何 runtime 锁 |
| ② manager ioDispatcher | `ServiceManager` / `RuntimeManager` | 每个 public suspend 方法内部 `withContext(ioDispatcher)`; 默认 `Dispatchers.IO`, 可经构造参数注入 (`AppContainer` 未覆盖, 均用默认) |
| ③ runtime 专属单线程 dispatcher | `RuntimeEntry.dispatcher` | 每脚本一条 (`newSingleThreadContext("quickjs-$namespace")`), 由 `RuntimeFactory.create` 创建 |

**关键事实**: quickjs-kt 的 `evaluate` 拿 jsMutex 后在**调用方线程**同步执行 native
JS —— 单线程 dispatcher 本身并不串行化任何东西; "JS 只在一个线程上跑"是靠
`runJs` 封装 (所有入口强制经它派发) 保证的, 而非调度器保证。

两个补充事实 (均来自代码注释, 决定并发工具的串行性):

- `evaluate` 在 `awaitAsyncJobs` 期间还持有 jsResultMutex: 工具 handler await
  异步 I/O 时, 其它工具调用的 `evaluate` 进不来, 同 runtime 的 in-flight 调用
  天然串行 (见 `ToolBridge.callHandler` 注释)。
- 竞态的另一面: jsMutex/jsResultMutex 之外的调用方取消、I/O 慢等不会阻塞
  dispatcher 线程本身, 这正是"探针探测线程可用性"能区分"卡死"与"慢 I/O"的前提。

### 2.2 runJs: 唯一 JS 入口

```kotlin
suspend fun <T> runJs(block: suspend QuickJs.() -> T): T =
    withContext(dispatcher) { quickJs.block() }
```

- **不变量**: 除 `runJs` 外, 任何代码不得直接调用 `quickJs.evaluate` —— 该约束
  由封装层强制, 不靠调用点自律。app 内所有 evaluate 调用点均已核对经 `runJs`:
  `RuntimeFactory` (injectMcpObject / extractTools / 脚本顶层 evaluate)、
  `QuickJsBridge` (两次 evaluate)、`HealthChecker` (探针 evaluate)、host API
  注入期 glue (`HostApiInjector` / `SystemApi` / `SqlApi` / `FetchApi` /
  `KvApi` / `FsApi` / `CryptoHost`)。
- **例外意识**: `quickJs.function` / `asyncFunction` 的**绑定注册**是 define
  操作, 不执行 JS, 可直接 `entry.quickJs.xxx` 注册; 其 Kotlin lambda 由库
  在 QuickJS dispatcher 线程回调 (JS→Kotlin 方向, 不构成对不变量的违反)。
- **违反后果**: 一旦 JS 在其它线程上运行, "dispatcher 空闲 ⟹ jsMutex 空闲"
  的探测前提被破坏, `safeCloseQuickJs` 对 `close()` 的安全判断失效 —— 会误判
  空闲并对被死循环持有的 jsMutex 自旋卡死 (见 §5)。

### 2.3 Manager 执行上下文

`RuntimeManager` 与 `ServiceManager` 的所有 suspend 公共方法内部
`withContext(ioDispatcher)`: 调用方无论在什么线程 (含 Main), manager 操作必然
离开调用方上下文执行。这取代了原先散落在各 ViewModel 的
`withContext(Dispatchers.Default)` 补丁; **ViewModel 无需也不应再包线程**。

注意区分: `RuntimeManager.getRuntime` 是非 suspend 的普通函数, 直接查
`ConcurrentHashMap` (读侧线程安全: Ktor 请求线程高频无锁读与 mutex 内的结构性
写入并发, 故用 CHM 而非 HashMap; 写侧仍由 mutex 串行化), 只用于已存在 runtime
的只读查找, 如 ToolBridge。

### 2.4 native 访问亲和

native 状态 (memoryLimit / memoryUsage / maxStackSize) 的访问原则是 **与 JS
同线程**, 避免并发访问 native ctx。代码中存在三种形态, 需按形态区分:

1. **经 `runJs` 的内联访问** (主流): `RuntimeManager.updateRuntimeConfig` 在
   `runJs` 内 set `memoryLimit` / `maxStackSize`; `HealthChecker.runProbe` 在
   `runJs` 内读 `memoryLimit` + `memoryUsage` (与 JS 存活探针同一次派发)。
2. **create 早期的直接 set** (注释明确的例外): `RuntimeFactory.create` 在
   dispatcher 上尚无任何 JS 执行时, 由调用方线程直接
   `qjs.memoryLimit = ...` / `qjs.maxStackSize = ...` (无竞争, 见该处注释)。
3. **便捷属性 / asyncFunction 回调内的直接读**: `RuntimeEntry.memoryUsage`
   属性直读 `quickJs.memoryUsage` (未包 runJs, 是便捷 API); `FsApi` 在
   asyncFunction lambda 初始运行于 QuickJS dispatcher 线程时直读
   memoryLimit/memoryUsage (见其注释 "asyncFunction lambda 初始运行在 QuickJs
   dispatcher 上")。二者均为**读**且天然落在 dispatcher 线程, 不破坏不变量,
   但新增类似直接访问时须先确认所在线程。

## 3. 生命周期与引用计数

**核心原则: 1 脚本 = 1 Runtime。** `RuntimeManager.runtimes: Map<namespace,
RuntimeEntry>`; 多个服务引用同一脚本时共享同一 runtime, 以引用计数管理。
工具调用的 `getRuntime` 不持有/不增加引用, 只保证存活期间的只读可见性。

### acquire / release 语义 (`RuntimeManager`)

- `acquire(namespace, scriptCode, runtimeConfig)` (mutex ② 内, ioDispatcher 上):
  - 已存在且**未中毒**: `refCount++`, 直接返回既有实例 (runtimeConfig 忽略,
    动态变更走 `updateRuntimeConfig`)。
  - 已存在但**已中毒**: 走重建路径 (下述)。
  - 不存在: `runtimeFactory.create` → `startBackgroundJobs` → `refCount = 1`
    → 入 map。create 含 30s 硬超时 (见 §7), 期间持 mutex ②。
- `release(namespace)`: `refCount--`; 归零 (`<= 0`) 时 `destroy()` + 移除。
- `getRuntime` / `activeCount`: 只读, 不加锁不加引用。
- `destroyAll`: 遍历全部 entry, 逐个 `runCatching { entry.destroy() }` (单个
  失败不中断其余, 退出路径尽力而为), 最后清空 map。**兜底特性**: destroy 永不
  阻塞 (见 §4/§5), 因此该循环不会因某个死循环脚本而卡死。

### 中毒重建路径 (acquire 遇 poisoned)

```text
preservedRef = existing.refCount     // 保留旧计数, 不能重置为 1
existing.destroy()                   // 探测式销毁: 死循环卡死时直接孤儿化, 永不阻塞本 mutex
runtimes.remove(namespace)
entry = runtimeFactory.create(...)   // 重建 (重新 evaluate, 含 30s 硬超时)
startBackgroundJobs(entry)
entry.refCount = preservedRef + 1    // +1 = 当前调用者的新引用
runtimes[namespace] = entry
```

保留旧 refCount 的原因: 其他服务仍持有对旧 runtime 的引用, 重建后它们的引用
转移到新 runtime; 若重置为 1, 其他服务 release 时会过早销毁新 runtime。

### create 阶段 (`RuntimeFactory`) 与失败清理

创建顺序: `newSingleThreadContext("quickjs-$namespace")` → `QuickJs.create`
→ 构造 `RuntimeEntry` → **尽早**注册 `onDestroy` (在 injectAll 之前, 保证注入
中途失败时 `onDestroy` 单一清理路径覆盖已注入资源, 无 double-run) → set
memoryLimit/maxStackSize → 注入 mcp 对象 → host 第 0 层 (console/timer/crypto)
→ host 第 1-4 层 (kv/sql/fs/fetch/system) → `evaluate` 脚本顶层 (注册工具,
30s 硬超时) → `extractTools`。

失败清理 (`catch (ex: Exception)`, 清理全程 NonCancellable):

```text
runCatching { entry?.onDestroy?.invoke() }   // host API 资源清理, 失败仅记录, 不得顶掉原始异常
entry?.scope?.cancel()
safeCloseQuickJs(quickJs, dispatcher)        // 防 evaluate 半卡死: 顶层死循环导致 evaluate
                                             // 被取消时 jsMutex 仍被线程持有, 直接 close 会自旋
dispatcher.close()
throw ex
```

不清理的代价: JNI global ref 会 pin 住 native runtime 和一条真实 OS 线程
(`QuickJs.initGlobals` 的 `NewGlobalRef` 只有 close 才释放)。NonCancellable 的
原因: 若调用方协程在 evaluate 期间被取消, 默认上下文下清理会被
CancellationException 跳过 → 泄漏。

**超时语义**: 顶层 evaluate 的 `TimeoutCancellationException` 必须转为普通
`IllegalStateException` 抛出 —— 它属于 `CancellationException`, 直接上抛会被
协程机制当作"取消"静默吞掉, 调用方无法感知创建失败。

### 销毁决策树

见 §4 (按中毒原因分决策) + §5 (孤儿化契约)。

## 4. 毒化 (Poisoning) 生命周期

### 三种原因与判定来源

```kotlin
enum class PoisonReason {
    /** 工具调用超时 + 探针确认 dispatcher 被死循环占用 (线程救不回, 销毁时直接孤儿化) */
    STUCK_DISPATCHER,
    /** QuickJS async 基础设施损坏 (死 promise / 引擎级异常), dispatcher 多半仍空闲 (销毁时先探测, 可正常回收线程) */
    BRIDGE_CORRUPTED,
    /** 健康检查连续失败 (探针超时或内存超限) */
    HEALTH_CHECK_FAILED,
}
```

| 原因 | 判定来源 | 资源状态 |
| --- | --- | --- |
| `STUCK_DISPATCHER` | `ToolBridge.callHandler`: 工具 withTimeout 超时后, `withTimeoutOrNull(PROBE_TIMEOUT_MS){ withContext(runtime.dispatcher){} }` 未在 2s 内返回 ⇒ 线程被死循环占用 → `poison(STUCK_DISPATCHER)` | 线程救不回 |
| `BRIDGE_CORRUPTED` | `QuickJsBridge.callHandler`: 第一次 evaluate 抛引擎级异常 (OOM、"Result promise not found" 等) → `poison` + rethrow; 或第二次 evaluate 读到 `__bridge_result == null` (handler 卡在死 promise 上) → `poison` + 抛 `IllegalStateException` | 引擎损坏, dispatcher 多半仍空闲 |
| `HEALTH_CHECK_FAILED` | `HealthChecker.run`: 每 30s 探针, 连续 3 次失败 (探针 2s 超时 或 内存超限) → `onUnhealthy` 回调 → `poison(HEALTH_CHECK_FAILED)` (由 `RuntimeFactory.startBackgroundJobs` 接入) | 不确定, 由销毁时探测决定 |

### 首个诊断者获胜

```kotlin
@Synchronized fun poison(reason: PoisonReason) { if (_poisonReason == null) _poisonReason = reason }
```

最初的原因最可信, 后续来源不覆盖。

### 毒化后的用户可见行为

- 工具调用被拒: `ToolBridge.callHandler` 见 `runtime.poisoned` 直接返回
  errorResult ("Runtime is unresponsive (previous call timed out). Restart the
  service to recover."), 不再排队等待永不返回的调用。
- 服务重启触发重建: 下一次 `RuntimeManager.acquire` (服务 start /
  restart / rebuildTools) 遇到 poisoned entry 时走 §3 重建路径。

### destroy 的分原因决策 (`RuntimeEntry.destroy`)

```text
destroy()
├─ onDestroy?.invoke()            // runCatching, 失败仅记录
├─ healthChecker?.cancel()
├─ scope.cancel()                 // SupervisorJob 作用域, 停后台 Job
├─ callQueue.close()              // 拒绝新的并发入队
├─ poisonReason == STUCK_DISPATCHER ?
│   ├─ 是 → OrphanLedger.onOrphaned(ns, "stuck dispatcher (poisoned)")
│   │        // ToolBridge 探针已确认线程被占, 无需再探测, 直接孤儿化
│   └─ 否 → safeCloseQuickJs(quickJs, dispatcher) ?
│       ├─ true  → (dispatcher as? ExecutorCoroutineDispatcher)?.close()
│       │          // 探测空闲 ⇒ close 成功 ⇒ 线程池可正常关闭
│       └─ false → OrphanLedger.onOrphaned(ns, "dispatcher probe timeout")
└─ 全程 withContext(NonCancellable)
```

- **为何 STUCK 直接孤儿化**: 探测会把一个"排在死循环之后的挂起任务"等满 2s,
  与既成事实的卡死结论相比纯属浪费; 直接记账更诚实。
- **为何其余原因先探测**: `BRIDGE_CORRUPTED` / `HEALTH_CHECK_FAILED` 的引擎
  损坏不必然占住线程, 先探测可大概率正常回收空闲线程, 避免白白泄漏。
- **NonCancellable 纪律**: 若调用方协程在 stop 中途被取消 (如 viewModelScope
  销毁), 默认上下文下第一个挂起点抛 CancellationException 跳过清理 → 泄漏。
- **禁区**: 禁止在本 runtime 自己的 dispatcher 线程上调用 `destroy` (探测会
  自死锁, 见 `RuntimeEntry.destroy` KDoc)。

## 5. 孤儿化契约 (Orphanization Contract)

### 为什么存在

- quickjs-kt **没有暴露 JS_SetInterruptHandler**: native 死循环不可中断。
- `close()` 对被持有的 jsMutex 自旋: 库实现为无 yield 的 tight-loop
  withLockSync (见 quickjs-kt `Mutex.ext.kt`), 一旦 `evaluate` 死循环期间有人
  调 `close()`, 调用方线程永久卡死。

### 契约内容

- **取舍**: 泄漏 1 条已卡死的线程 + 1 个 native ctx, 换取调用方
  (`RuntimeManager.mutex` 持有者 / Main 线程) 快速返回, 系统不死。
  (死循环中毒前已占用该核心, 孤儿化不新增系统级阻塞; 进程死亡时全部回收。)
- **显式而非静默**: `OrphanLedger` 以 AtomicLong 累计孤儿化次数,
  `onOrphaned(namespace, reason)` 输出 `Log.w` (含累计值), 泄漏可观测。
- **不阻塞**: 孤儿化路径不含任何探测/close 等待, `destroy` 及其调用方
  (含 `acquire` 重建、`release`、`destroyAll`) 永不因死循环脚本而阻塞。

### 何时触发 / 何时不会触发

触发 (`OrphanLedger.onOrphaned`):

1. `destroy()` 且 `poisonReason == STUCK_DISPATCHER` (已确认卡死, 直接记账);
2. `destroy()` 且非 STUCK 原因但 `safeCloseQuickJs` 探针超时
   (dispatcher 忙, 判定 close 会自旋);
3. `RuntimeFactory.create` 失败路径且 `safeCloseQuickJs` 探针超时
   (典型: 脚本顶层死循环触发 30s 硬超时后被取消, 线程仍被 native 死循环占用) ——
   与 destroy 路径同价 (1 线程 + 1 native ctx), 同入账本。

不会触发:

1. `safeCloseQuickJs` 快速路径: `quickJs == null` (create 早期失败) 或
   `isClosed` → 直接视为已清理;
2. 探测空闲 → `quickJs.close()` 成功 → dispatcher 正常关闭回收;
3. `RejectedExecutionException` (dispatcher 已关闭, 重复销毁) → 视为已清理。

### 已知边界与长期选项

- **无上限**: 每次孤儿化泄漏 1 线程 + 1 native ctx; 反复中毒重建会累积,
  `OrphanLedger` 只有日志计数, **未接 UI**, 用户无感知。
- 长期选项: 上游 (quickjs-kt) 暴露中断句柄后可整体删除 §4/§5/§6 的探测与
  孤儿化机制; 或将脚本执行放入独立进程隔离 (进程死亡兜底回收)。

## 6. 探针体系 (Probe System)

### 单一出处 `RuntimePolicy.PROBE_TIMEOUT_MS = 2_000L`

此前的 2s 探测超时在 ToolBridge / RuntimeEntry / HealthChecker 三处各有一份
拷贝, 语义相关却互不引用; 重构后收敛到 `RuntimePolicy`。三处消费:

| 消费点 | 用法 | 判定 |
| --- | --- | --- |
| `ToolBridge.callHandler` (工具超时后) | `withTimeoutOrNull(2s){ withContext(runtime.dispatcher){} }` | null ⇒ dispatcher 被死循环占用 → `STUCK_DISPATCHER` |
| `safeCloseQuickJs` (销毁探测) | `withTimeoutOrNull(2s){ withContext(dispatcher){true} }` | 超时 ⇒ close 会自旋 ⇒ 孤儿化; `RejectedExecutionException` ⇒ 已关闭 ⇒ 视为已清理 |
| `HealthChecker.runProbe` (健康探针) | `withTimeoutOrNull(2s){ runJs { evaluate("1") + 内存检查 } }` | 超时或异常 ⇒ 本次不健康 |

### 推理: "dispatcher 空闲 ⟹ jsMutex 空闲或仅有界持有"

- 探测任务排在 dispatcher 队列**尾部**: 若线程被死循环占用, 探测挂起,
  `withTimeoutOrNull` 超时返回 null → 判定忙, 不碰 `close()`。
- 该推理依赖 **runJs 不变量**: JS 只在 dispatcher 线程执行, 因此"dispatcher
  空闲"排除了"死循环 evaluate 正在运行"这一唯一能导致 close 永久自旋的情形。
- **µs 级例外**: asyncFunction 的 resolve/reject 会在非 dispatcher 线程
  (resumption 线程, 如 IO) 短暂获取 jsMutex (µs 级 native resolve, 有界持有)。
  dispatcher 空闲只保证 jsMutex 空闲或仅被有界持有, `close()` 的自旋等它无妨。
- 前提被破坏的后果: 若有人在其它线程直接 evaluate 且死循环, 探测会误判空闲
  并对 `close()` 自旋卡死 (即 §2.2 不变量被违反的代价)。

### 为什么 ToolBridge 探针不走 evaluate

工具超时后 handler 的 IO job 可能仍在飞 (慢 I/O, 非卡死)。探针若用
evaluate, 其贪婪的 `awaitAsyncJobs` 会 join 超时后仍未结束的 IO job, 把
"慢 I/O" 误判成"卡死"。因此探针只用 `withContext(runtime.dispatcher){}`
测线程可用性, **不碰 JS**; evaluate 留给 HealthChecker 的定期探针 (它不受
in-flight 工具调用约束, 且要同时度量 JS 存活)。

## 7. 超时与常量一览

| 常量 | 值 | 出处 | 含义 |
| --- | --- | --- | --- |
| `SCRIPT_EVALUATE_TIMEOUT_MS` | 30_000 | `RuntimePolicy` | create 阶段脚本顶层 evaluate 硬超时; 超时 → `IllegalStateException` (防顶层死循环永久占住 `RuntimeManager.mutex`, P1-B) |
| `PROBE_TIMEOUT_MS` | 2_000 | `RuntimePolicy` | dispatcher 忙闲探测超时; ToolBridge 超时探针 / safeCloseQuickJs 销毁探测 / HealthChecker 健康探针共用; 量级需容忍正常慢 I/O |
| `DEFAULT_TIMEOUT_MS` | 30_000 | `ToolBridge` | 工具调用默认超时 (脚本未声明 timeoutMs 或声明非法时) |
| `MIN_TIMEOUT_MS` | 1_000 | `ToolBridge` | 单工具超时下限 (防脚本误传过小值) |
| `MAX_TIMEOUT_MS` | 180_000 | `ToolBridge` | 单工具超时上限 (防脚本声明过长拖垮整个 runtime) |
| `PROBE_INTERVAL_MS` | 30_000 | `HealthChecker` | 健康探针间隔; 连续 `MAX_FAILURES` 次失败 → 毒化 (30s × 3 次) |
| `MAX_FAILURES` | 3 | `HealthChecker` | 连续失败阈值; 达成后 `onUnhealthy` → `poison(HEALTH_CHECK_FAILED)`, 并重置计数避免重复触发 |
| `MEMORY_THRESHOLD_RATIO` | 0.9 | `HealthChecker` | 内存使用率阈值: `memoryUsage.memoryUsedSize > memoryLimit * 0.9` 判为不健康 (memoryLimit 取 runtime 实际值, 默认 16 MiB) |
| `QUEUE_DEPTH` | 8 | `RuntimeEntry` | `callQueue` 并发上限 (FIFO Channel); 同时 in-flight 工具调用数满则 `send` 背压 |
| `DEFAULT_MEMORY_LIMIT` | 16 MiB | `RuntimeFactory` | 默认内存上限 |
| `DEFAULT_MAX_STACK_SIZE` | 512 KiB | `RuntimeFactory` | 默认栈上限 |
| `UNLIMITED` | `Long.MAX_VALUE` | `RuntimeFactory` | "无限制"的实际值 (QuickJS 的 0 = "0 字节可用", 需转极大值); `isUnlimited()` 判断 |
| `GRACE_PERIOD` | 1_000 | `McpServiceInstance` | Ktor stop 优雅等待时间 |
| `TIMEOUT` | 2_000 | `McpServiceInstance` | Ktor stop 超时时间 |

工具超时钳制 (`ToolBridge.clampToolTimeout`): 脚本经 `mcp.tool(..., {
timeoutMs })` 声明, 存于 `ToolDefinition.timeoutMs`; null / 非正 → 默认 30s;
否则夹到 [1s, 180s]。

## 8. 服务层协作 (ServiceManager)

`ServiceManager` 持有 `RuntimeManager` 引用; 服务启动/停止驱动引用计数,
`McpServerFactory` 创建 Ktor + MCP SDK Server 并注册工具, `ToolBridge` 把
tools/call 转发到 JS handler。运行实例封装为 `McpServiceInstance`
(service / mcpServer / ktorServer / registeredTools / scriptNamespaces)。

### start / stop / restart 与引用计数

- `startService` → `startServiceInternal` (持锁): 端口预检 (`isPortAvailable`)
  → `ensureCodeLoaded` → `serverFactory.create` (对每个 enabled 脚本
  `acquire` + 注册 `namespace_toolName` 工具 + 启动 Ktor) → 成功才
  `activeServices[serviceId] = instance` + manifest `enabled = true`。
  **失败路径**: catch 内以 `NonCancellable` 对全部 enabledRefs `release` 再
  rethrow —— 调用方协程被取消时也必须完成释放, 否则引用计数泄漏, 后续 acquire
  会误复用/误销毁 runtime。
- `stopService` → `stopServiceInternal` (持锁): 从 activeServices 移除 →
  `svcInstance.stop()` (Ktor stop, 1s/2s) → **finally** 内 `NonCancellable`
  对 `scriptNamespaces` 逐个 `release`。无论 Ktor stop 是否异常、调用方协程
  是否被取消, release 必须完成 (引用计数泄漏 → 重启时误复用/误销毁)。
- `restartService` (单个) / `restartServicesForScript` (脚本代码变更后全部重启):
  "全部停止 → 全部启动"在同一个 mutex 内原子执行; 个别启动失败仅记账计数,
  不中断其余。
- `restoreEnabledServices`: 前台服务重启时恢复所有 `enabled=true` 的服务。
- `destroyAll`: 逐个 `runCatching { stop + release }` + 清空 (退出路径尽力而为)。

### rebuildTools 的补偿语义 (增量 acquire)

脚本增删/勾选变化时 `rebuildTools`: 先 removeTool 全部旧工具, 再遍历 enabled
refs。**对本服务已持有的 namespace (retained) 不重复 acquire, 只重新注册工具**;
仅对新增 namespace `acquire` (记入 `acquired` 集合)。

> 为什么增量: 若每次 rebuild 对全部 enabled refs 重新 acquire, 保留者的 refCount
> 会随 rebuild 次数无界上浮且无对应 release —— 运行中服务每 toggle/add/remove 一次
> 就 +1, runtime 永不销毁 (每脚本每服务泄漏 1 线程 + native ctx)。引用只在两种
> 情况变化: 新增 namespace → acquire; 移除 namespace → release。
> 已持有的毒化 runtime 不经此路径重建 (其 toolRegistry 仍有效, 调用被 ToolBridge
> 拒绝); 恢复依赖服务 stop/start (refCount 归零 → 全新 create)。

- 中途失败: `NonCancellable` 只释放 `acquired` (runCatching 逐个), 原有引用
  **不动** —— 若失败发生在 release removed 之前, 被移除者的旧引用这一次不会
  释放, 引用计数**暂时偏高**; "偏高比错误释放安全": 错误释放会让仍在用该
  runtime 的其它服务踩空 (其入队排队、调用将悬挂)。下次成功 rebuild 或服务
  停止时会走到 release。
- 成功完成后: `removed = scriptNamespaces - newNamespaces` 逐个 release,
  再更新 `scriptNamespaces` —— 引用计数与 manifest 严格一致。

### 双 mutex 结构 (嵌套顺序)

```
ServiceManager.mutex (mutex ①, 服务级: CRUD / 启动停止 / rebuildTools)
  └─▶ RuntimeManager.mutex (mutex ②, runtime 级: acquire / release)
        └─▶ (runtime 生命周期操作, e.g. destroy 探测 ≤ 2s 或直接孤儿化; create ≤ 30s)
```

固定顺序 ① → ②, 无反向获取; `runtimeManager.getRuntime` 不加锁。两把锁都在
ioDispatcher 线程上持有, 不在 Main。虚拟机锁期间的时间上界: create 30s 硬超时、
destroy 探测 ≤ 2s (孤儿化路径 0 等待)。

## 9. 已知限制与未来方向

1. **JS 不可中断是所有复杂度的根因**: quickjs-kt 无 JS_SetInterruptHandler,
   native 死循环不可中断, 逼出了探针体系 (§6)、分原因毒化 (§4) 与孤儿化契约
   (§5)。若上游暴露中断句柄, 第 4/5/6 节可**整体删除**。
2. **OrphanLedger 目前仅日志**: 孤儿化只计入 log + AtomicLong, 未接 UI, 用户
   无感知; 无上限 (反复中毒重建会累积泄漏 1 线程 + 1 native ctx/次)。
3. **重建时的锁竞争**: 中毒重建路径在 `RuntimeManager.mutex` 内执行 create
   (≤ 30s), 期间其它 acquire 串行等待; 属设计取舍, 无并发隔离。
4. 可选方向: 进程隔离执行脚本 (进程死亡兜底回收孤儿化资源), 或等待上游
   中断句柄后简化本架构。