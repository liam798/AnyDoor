# Binder 自定义载荷由类名反序列化，宿主自定义 Parcelable/Serializable 应自行保留。
-keep class com.anydoor.internal.ipc.** { *; }
-keep class com.anydoor.AnyDoor { public *; }
