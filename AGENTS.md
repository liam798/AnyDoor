# AnyDoor 工程约定

## 范围

- 回复、文档和新增注释使用简体中文。
- 独立 Android SDK，不依赖或修改 XMSupport3，不引入广告、网络、远程配置或组件框架。
- SDK 当前只负责同应用跨进程调用，不新增数据读写、事件订阅、持久化或保活模块。
- 服务命名表达整体角色，不代表扩展范围已获批准。

## 结构

- SDK 位于 `anydoor/`，宿主示例和仪器化测试位于 `sample/`。
- SDK 根包为 `com.anydoor`，示例包为 `com.anydoor.sample`。
- AnyDoor 是唯一客户端入口，不增加 BusManager/getBus 或二级客户端门面。
- 根包保留 AnyDoorProvider、CallHandler 和 CallResult；异步回调直接使用函数类型，不创建独立公开回调类。
- AnyDoorConnection、AnyDoorService、HandlerRegistry 位于 internal；Binder 接口和编解码位于 internal/ipc。
- 内部实现不是稳定 API，禁止通过公开门面暴露其类型；Provider 默认不导出且限制同 UID。
- 不新增全局 Context 容器。业务代码不得在服务端注册表锁内执行。

## 修改与文档

- 手工编辑使用 apply_patch，保留用户未提交的改动。
- SDK、插件和依赖版本统一维护在 `gradle/libs.versions.toml`。
- 不提交密钥、签名配置、local.properties、构建缓存和临时验证产物。
- 变更 API、包名或协议时同步接入、目录和迁移说明，明确源码、二进制及协议兼容性。
- 文档描述当前状态，不逐轮追加互相矛盾的目录；历史故障与经验写入 `.learnings/`。
- 不将未经执行的测试、未发布坐标或未经测量的性能作为已确认事实。

## 验证

```bash
./gradlew :anydoor:assembleRelease :sample:assembleDebug :sample:assembleDebugAndroidTest
./gradlew :anydoor:testDebugUnitTest :anydoor:lintRelease
./gradlew :sample:connectedDebugAndroidTest
./gradlew :sample:connectedDebugAndroidTest -PanydoorProcess=:anydoor
```

修改 IPC 时验证序列化、回调、注销、处理器死亡、中心死亡和按需重连。
设备不可用时记录阻塞，不把构建或 Robolectric 测试等同于真实跨进程验收。
生产门槛和验证记录统一维护在 `docs/验证与发布.md`。
