# API 与接入

第三方 SDK 在本 API 上提供 AdsApi 等源码封装时，遵循 [SDK 源码接入规范](SDK源码接入规范.md)。
该规范不改变 AnyDoor 核心 API，也不引入新的运行时协议模块。

## 依赖

仓库内示例使用 `implementation project(':anydoor')`。
外部宿主使用本地 Maven 发布物时，在其仓库配置中加入 `mavenLocal()`：

```groovy
dependencies {
    implementation 'com.anydoor:anydoor:0.1.0'
}
```

先在本仓库执行 `./gradlew :anydoor:publishReleasePublicationToMavenLocal`。
直接引用 AAR 不会自动解析 POM 依赖，宿主还需配置 Kotlin 标准库与 AndroidX Annotation；
具体版本见 `gradle/libs.versions.toml`，不在多个文档复制版本清单。

## 公开签名

以下为 `com.anydoor.AnyDoor` 的方法签名：

```kotlin
fun initialize(context: Context)
fun call(callId: String, arg: Any?): Any?
fun callAsync(callId: String, arg: Any?, callback: ((String, Any?) -> Unit)? = null): Boolean
fun registerHandler(callId: String, handler: CallHandler)
fun unregisterHandler(callId: String, handler: CallHandler)
```

各方法有 `@JvmStatic`。callAsync 通过 `@JvmOverloads` 同时提供 Java 双参数重载。
异步回调参数依次为调用 ID、结果；不需要结果时省略回调或传 null。
公开处理器为 `fun interface CallHandler`，方法是
`onCall(callId: String, arg: Any?): CallResult`。

## 调用语义

| 接口/情况 | 当前行为 |
| --- | --- |
| initialize | 幂等，仅保存应用级连接依赖，不发起 IPC |
| 未初始化 | 除 initialize 外的方法抛 IllegalStateException |
| 服务发现失败 | call、callAsync、registerHandler 的发现异常向调用方传播 |
| call | 返回最终业务值；无处理器、全部 Skip、Done、DoneWith(null) 均返回 null |
| call 执行异常 | 捕获普通 Exception，记录日志并返回 null |
| callAsync | true 仅代表中心接受入队；空白 ID、队满或提交 Exception 返回 false |
| 异步执行异常 | 中心捕获普通 Exception 后尝试回调 null；不保证进程死亡后仍有回调 |
| registerHandler | 同 ID、同实例、同服务下幂等；发现后的注册异常记录日志，无成功返回值 |
| unregisterHandler | 只注销同一实例；没有绑定时无操作；旧服务已死时释放本地绑定且不发现新服务 |
| 存活服务注销失败 | 记录日志并保留绑定，允许显式重试 |

空白 ID 不注册、不分发。但 call、callAsync、registerHandler 仍可能先执行服务发现，
不能依赖空白 ID 绕过初始化或发现错误。`Error` 不属于上述 Exception 处理保证。
提交失败可能发生在中心已收到请求之后，false 不是“业务绝对未执行”的证明，不应据此盲目重试。

## 结果责任链

| 处理器返回 | 行为 |
| --- | --- |
| CallResult.Skip | 继续下一个处理器 |
| CallResult.Done | 停止分发，无业务返回值 |
| CallResult.DoneWith(value) | 停止分发，向调用者返回 value |

同一 ID 的不同处理器按中心收到注册的顺序执行。并发注册没有跨进程业务优先级保证。
调用者接收业务值，而不是 CallResult 包装对象。

## 生命周期

在每个调用或注册处理器的进程中初始化。保存处理器实例，不要用一个新 lambda 注销旧 lambda。
完整注册/注销写法见 [README](../README.md)。

AnyDoor 持有处理器强引用，宿主应在业务生命周期结束时注销，避免无意持有 Activity 等对象。
注销不会取消已取得快照的调用；参数也不应在提交后由调用方继续修改。
中心死亡后，下次需要服务的调用按需重连；宿主显式重新注册原处理器实例，
SDK 不自动重放业务。处理器端进程重启后同样需要重新执行注册。

同步调用、注册/注销和冷连接可能阻塞；业务应安排在合适的工作线程。
异步只改变中心执行方式，不保证提交无阻塞、回调在主线程或处理器全局串行。

## Java 接入

在已经初始化的业务工作线程中：

```java
import com.anydoor.AnyDoor;
import com.anydoor.CallHandler;
import com.anydoor.CallResult;
import kotlin.Unit;

CallHandler handler = (id, arg) -> CallResult.doneWith(arg);
AnyDoor.registerHandler("echo", handler);
Object result = AnyDoor.call("echo", "你好");
boolean accepted = AnyDoor.callAsync("echo", "你好", (id, value) -> {
    System.out.println(id + ": " + value);
    return Unit.INSTANCE;
});
AnyDoor.callAsync("echo", "无需回调");
```

生命周期结束后使用原 handler 调用 `AnyDoor.unregisterHandler("echo", handler)`；
异步任务尚未执行时注销可能使其找不到处理器，不要紧跟提交就结束处理器生命周期。
Java 也可使用 `CallResult.skip()`、`done()` 和 `doneWith(value)`。

## 载荷

CallPayload 显式支持 null、String、Int、Long、Float、Double、Boolean、
Bundle、Parcelable、Map 和 Serializable。Map 键必须为 String，值按同样的载荷规则递归转换。
SDK 编码、解码和内容标记遍历支持最多 32 层 Map，超限或 Map 循环引用抛 IllegalArgumentException。
结果协议外包的一层 Map 也计入限制，因此跨进程处理器的 DoneWith 业务 Map 最多 31 层。
共享同一个子 Map 不等于循环引用。

此限制不深入 Bundle、Parcelable、Serializable 内部对象图，也不是总字节预算。
自定义载荷类型必须在接收端可加载，宿主负责其混淆规则。SDK 的 consumer 规则保留内部 IPC 类，
不负责自动保留所有业务 Parcelable/Serializable。
优先使用小载荷，避免传完整图片、文件或庞大对象图；不支持的类型在序列化时失败。
同进程直调不经过 Parcel，不能将“本地可用”当作跨进程兼容证据。

## 子进程配置

宿主通过 Provider 的 `android:process` 决定中心位置，见 [README](../README.md)。
业务进程由宿主组件启动。示例中 `RemoteService` 在 `:remote`，
`RemoteEndpointService` 在 `:endpoint`；它们不是 SDK 自动发现或保活的服务。
仅示例模块提供 `-PanydoorProcess=:anydoor` 构建开关，SDK 本身不读取该 Gradle 属性。

## 从早期实现迁移

- 所有 SDK import、Manifest Provider 和自定义混淆规则改为 `com.anydoor`。
- 本地 Maven 坐标改为 `com.anydoor:anydoor`；示例 applicationId 为 `com.anydoor.sample`，
  与旧示例是不同应用身份，不会自动迁移旧示例数据。
- 不再使用独立 CallCallback；改为函数回调，Java 返回 Unit.INSTANCE。
- 服务及连接实现为 AnyDoorService、AnyDoorConnection，服务 Binder 契约为 IAnyDoorService。
- Binder 描述符和公开 JVM 签名已变化，所有调用端必须重新编译并一起升级，不能混用早期二进制。
- authority 仍从宿主 applicationId 派生；宿主无需为了 SDK 包名而修改自己的 applicationId。
- 不再使用旧 Provider、服务池、二级客户端门面或全局 Context 容器；不要同时保留同 authority 的旧组件。

`internal` 不是稳定 API。发现 method/key 当前仍是 `get_call_service` / `call_service`，
角色命名调整未改变这两个内部字段，也不承诺旧版本兼容。
