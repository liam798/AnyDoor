# AnyDoor（任意门）

独立的 Android 同应用进程间调用 SDK。通过 `AnyDoor` 注册处理器、同步调用和异步获取结果，
支持主进程、普通子进程及子进程之间经中心转发。

SDK 包名为 `com.anydoor`。不提供数据存储、事件订阅、网络通信、远程对象管理或进程保活。

## 文档

| 文档 | 内容 |
| --- | --- |
| [API 与接入](docs/API与接入.md) | Kotlin / Java、返回语义、生命周期、子进程及迁移 |
| [SDK 源码接入规范](docs/SDK源码接入规范.md) | 第三方 AdsApi 等业务源码 API 的命名、协议和交付 |
| [目录与职责](docs/目录与职责.md) | 源码结构、组件所有权和依赖关系 |
| [架构与竞品分析](docs/架构与竞品分析.md) | 进程与线程模型、性能取舍、相关方案 |
| [验证与发布](docs/验证与发布.md) | 工具链、构建、测试证据及生产验收门槛 |
| [工程约定](AGENTS.md) | 修改本仓库时应遵守的约束 |

## 快速接入

在每个使用 SDK 的进程中初始化，通常放在 `Application.onCreate`：

```kotlin
import com.anydoor.AnyDoor

AnyDoor.initialize(this)
```

保存处理器实例，在其业务生命周期开始和结束时分别注册、注销：

```kotlin
import com.anydoor.AnyDoor
import com.anydoor.CallHandler
import com.anydoor.CallResult

class EchoEndpoint {
    private val handler = CallHandler { _, arg -> CallResult.DoneWith(arg) }

    fun register() = AnyDoor.registerHandler("echo", handler)
    fun unregister() = AnyDoor.unregisterHandler("echo", handler)
}
```

注册完成后，调用方在工作线程调用：

```kotlin
val result = AnyDoor.call("echo", "你好")
val accepted = AnyDoor.callAsync("echo", "你好") { callId, value ->
    println("$callId: $value")
}
AnyDoor.callAsync("echo", "不需要回调")
```

异步回调直接使用函数类型，不需要单独的公开回调接口。
`accepted` 仅表示是否入队，`null` 不能区分无结果和调用失败。初始化或服务发现失败仍可能抛异常。
回调不保证在主线程，异步提交也包含同步服务发现和 Binder 事务，不是端到端非阻塞 API。

## 进程配置

SDK 自动合并 `com.anydoor.AnyDoorProvider`，authority 为 `${applicationId}.anydoor`，
默认不导出、运行于应用默认进程；宿主通常不需要手动声明。
中心需要运行在独立子进程时，在宿主 Manifest 的 application 内合并同名组件：

```xml
<provider
    android:name="com.anydoor.AnyDoorProvider"
    android:authorities="${applicationId}.anydoor"
    android:process=":anydoor"
    android:exported="false" />
```

SDK 不负责启动任意业务子进程。中心死亡后按需重连，但宿主必须重新注册处理器；
在途请求不自动重放。独立 UID 的隔离进程不在支持范围内。

## 构建

当前配置：最低 API 23、compileSdk 35、Java/Kotlin 字节码目标 17。
完整工具链和测试说明见 [验证与发布](docs/验证与发布.md)。

```bash
./gradlew :anydoor:assembleRelease :sample:assembleDebug
./gradlew :anydoor:testDebugUnitTest :anydoor:lintRelease
./gradlew :sample:connectedDebugAndroidTest
```

当前本地 Maven 发布坐标为 `com.anydoor:anydoor:0.1.0`，版本以
`gradle/libs.versions.toml` 为准。仓库未配置公共远端发布仓库，
不能将该坐标视为已经可以从 Maven Central 下载。

## 使用边界

- 同一 ID 的处理器按中心收到的注册顺序组成责任链，首个消费结果结束分发，不是广播。
- 异步队列最多等待 256 个请求，单线程执行；慢处理器和慢回调都会阻塞后续任务。
- 不支持 SDK 级执行超时、取消、自动重试或注册恢复。
- SDK 自己编解码的 Map 最多嵌套 32 层，结果协议包装也计入层数；传递小载荷。
- 当前没有真机长期性能或亿级装机验收结论，构建成功不等于生产可用。

## 来源与分发

实现起源于 XMSupport3 的 `base/bus`、`base/binder`，当前作为独立工程维护，不依赖原工程。
更改包名不改变原始源码的权利归属；分发前仍须确认原始授权，本仓库未新增开源许可证。
