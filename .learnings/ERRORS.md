# 错误与排障记录

## 2026-09-12：稳定 Provider 依赖导致中心死亡连带终止宿主

新增中心死亡设备测试后，首次重连返回空结果，系统日志随后记录宿主因
`depends on provider` 被终止。原发现链路使用 ContentResolver.call，
在死亡与重新获取的竞态中建立了稳定 Provider 依赖。
改用 acquireUnstableContentProviderClient，finally 释放客户端；仅在发现遇到
DeadObjectException 时重新获取一次。独立中心设备用例重跑通过，不用延时或业务重试掩盖故障。

## 2026-09-12：本地分配测量与 Android 编译类路径

Android 单测的编译类路径未包含 java.lang.management 和 com.sun.management，
直接引用 JVM 分配计数接口会编译失败。临时宿主测量脚本可通过反射访问运行 JVM 的接口，
不应为此修改 Android SDK 的依赖或将测量脚本放入发布源码。

## 2026-09-12：仪器化回调不能传递测试 APK 的匿名类名

CrossProcessTest 将匿名 ResultReceiver 子类作为 Intent Parcelable 传入普通子进程，
触发 BadParcelableException，因为该子进程只能加载宿主 APK，无法找到测试 APK 的匿名类。
发送前使用 ResultReceiver 自身的 Parcel 写入及 CREATOR 还原成系统类型，保留回调 Binder，
不要通过让业务子进程依赖测试类或吞掉反序列化异常绕过问题。

## 2026-09-11：模拟器连接状态不能代替就绪检查

`adb devices` 显示 device，但 shell 属性查询超时；Gradle 仪器化测试无法获取 API Level，
最终报告没有兼容设备。后续运行前先对系统属性查询设置有限超时。
编译成功、测试 APK 生成和设备测试通过应分别记录，不自动重启可能被其他任务使用的模拟器。

## 2026-09-11：共享 Gradle 分发与转换缓存缺失

构建先报类路径快照和 JDK 转换产物缺失，重试又报 Gradle 自身的
`IncrementalCompileTask` 类找不到；检查时共享 wrapper 分发目录已为空，原因未确认。
没有通过修改业务代码解决环境问题，也没有清理共享缓存。
从官方分发地址下载相同版本 Gradle 到临时目录后，Java 编译及 AAR/APK 构建恢复。
遇到此类错误应区分源码失败与构建环境缺失，验证结果必须以恢复环境后的实际执行为准。

## 2026-09-11：强制代理测试需要明确本地接口行为

API 23 测试中，裸 Binder 未设置描述符时调用 queryLocalInterface 发生空指针。
用于强制走 Proxy 的转发测试替身应覆盖 queryLocalInterface 并返回 null，
而不是依赖未初始化 Binder 的默认实现。修正测试替身后服务代理回归通过。

## 源码归档任务的类型差异

releaseSourcesJar 实际任务类型为 org.gradle.jvm.tasks.Jar，
不是脚本默认导入的 org.gradle.api.tasks.bundling.Jar。
按默认 Jar 类型配置既可能漏掉任务，也可能在显式类型校验时报错。
使用已确认存在的任务名配置去重，再通过强制生成和 ZIP 条目检查验证。
