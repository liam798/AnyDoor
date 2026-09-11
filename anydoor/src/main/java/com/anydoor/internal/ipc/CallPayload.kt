package com.anydoor.internal.ipc

import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import java.io.Serializable
import kotlin.collections.iterator

private const val MAX_MAP_DEPTH = 32

/**
 * 调用参数与结果的统一传输载荷。
 * 支持跨进程安全的基础类型、Parcelable、Serializable、Bundle 和 Map<String, *>。
 * 同进程直调不序列化；跨进程拒绝不支持的对象，不缓存进程内引用。
 */
data class CallPayload(
    val value: Any? = null
) : Parcelable {

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        when (value) {
            null -> {
                parcel.writeInt(TYPE_NULL)
            }

            is String -> {
                parcel.writeInt(TYPE_STRING)
                parcel.writeString(value)
            }

            is Int -> {
                parcel.writeInt(TYPE_INT)
                parcel.writeInt(value)
            }

            is Long -> {
                parcel.writeInt(TYPE_LONG)
                parcel.writeLong(value)
            }

            is Float -> {
                parcel.writeInt(TYPE_FLOAT)
                parcel.writeFloat(value)
            }

            is Double -> {
                parcel.writeInt(TYPE_DOUBLE)
                parcel.writeDouble(value)
            }

            is Boolean -> {
                parcel.writeInt(TYPE_BOOLEAN)
                parcel.writeInt(if (value) 1 else 0)
            }

            is Bundle -> {
                parcel.writeInt(TYPE_BUNDLE)
                parcel.writeBundle(value)
            }

            is Parcelable -> {
                parcel.writeInt(TYPE_PARCELABLE)
                parcel.writeParcelable(value, flags)
            }

            is Map<*, *> -> {
                parcel.writeInt(TYPE_MAP)
                parcel.writeBundle(value.toBundle())
            }

            is Serializable -> {
                parcel.writeInt(TYPE_SERIALIZABLE)
                parcel.writeSerializable(value)
            }

            else -> {
                throw IllegalArgumentException("不支持跨进程传递：${value.javaClass.name}")
            }
        }
    }

    override fun describeContents(): Int = value.contentFlags()

    companion object {
        internal const val TYPE_NULL = 0
        internal const val TYPE_STRING = 1
        internal const val TYPE_INT = 2
        internal const val TYPE_LONG = 3
        internal const val TYPE_FLOAT = 4
        internal const val TYPE_DOUBLE = 5
        internal const val TYPE_BOOLEAN = 6
        internal const val TYPE_PARCELABLE = 7
        internal const val TYPE_MAP = 8
        internal const val TYPE_NON_SERIALIZABLE = 9
        internal const val TYPE_BUNDLE = 10
        internal const val TYPE_SERIALIZABLE = 11
        internal const val ENTRY_TYPE = "__bus_entry_type"
        internal const val ENTRY_STRING = "__bus_entry_string"
        internal const val ENTRY_INT = "__bus_entry_int"
        internal const val ENTRY_LONG = "__bus_entry_long"
        internal const val ENTRY_FLOAT = "__bus_entry_float"
        internal const val ENTRY_DOUBLE = "__bus_entry_double"
        internal const val ENTRY_BOOLEAN = "__bus_entry_boolean"
        internal const val ENTRY_PARCELABLE = "__bus_entry_parcelable"
        internal const val ENTRY_MAP = "__bus_entry_map"
        internal const val ENTRY_BUNDLE = "__bus_entry_bundle"
        internal const val ENTRY_SERIALIZABLE = "__bus_entry_serializable"

        @JvmField
        val CREATOR: Parcelable.Creator<CallPayload> = object : Parcelable.Creator<CallPayload> {
            override fun createFromParcel(parcel: Parcel): CallPayload {
                return when (parcel.readInt()) {
                    TYPE_NULL -> CallPayload(null)
                    TYPE_STRING -> CallPayload(parcel.readString())
                    TYPE_INT -> CallPayload(parcel.readInt())
                    TYPE_LONG -> CallPayload(parcel.readLong())
                    TYPE_FLOAT -> CallPayload(parcel.readFloat())
                    TYPE_DOUBLE -> CallPayload(parcel.readDouble())
                    TYPE_BOOLEAN -> CallPayload(parcel.readInt() != 0)
                    TYPE_PARCELABLE -> CallPayload(parcel.readParcelableCompat())
                    TYPE_MAP -> {
                        val bundle = parcel.readBundle(CallPayload::class.java.classLoader) ?: return CallPayload(null)
                        CallPayload(bundle.toPayloadMap())
                    }

                    TYPE_NON_SERIALIZABLE -> throw IllegalArgumentException("不支持进程内引用协议")

                    TYPE_BUNDLE -> CallPayload(parcel.readBundle(CallPayload::class.java.classLoader))
                    TYPE_SERIALIZABLE -> CallPayload(parcel.readSerializableCompat())

                    else -> throw IllegalArgumentException("未知调用载荷类型")
                }
            }

            override fun newArray(size: Int): Array<CallPayload?> {
                return arrayOfNulls(size)
            }
        }
    }
}

private fun Map<*, *>.toBundle(depth: Int = 1): Bundle {
    require(depth <= MAX_MAP_DEPTH) { "Map 嵌套不能超过 $MAX_MAP_DEPTH 层，禁止循环引用" }
    val bundle = Bundle()
    for ((key, value) in this) {
        require(key is String) { "Map 键必须为 String，实际为 ${key?.javaClass?.name}" }
        bundle.putBundle(key, value.toEntryBundle(depth))
    }
    return bundle
}

private fun Bundle.toPayloadMap(depth: Int = 1): Map<String, Any?> {
    require(depth <= MAX_MAP_DEPTH) { "Map 嵌套不能超过 $MAX_MAP_DEPTH 层" }
    classLoader = CallPayload::class.java.classLoader
    val map = LinkedHashMap<String, Any?>()
    for (key in keySet()) {
        val entry = getBundle(key)
        requireNotNull(entry) { "Map 键 '$key' 缺少编码条目" }
        map[key] = entry.toEntryValue(depth)
    }
    return map
}

private fun Any?.toEntryBundle(depth: Int): Bundle {
    val bundle = Bundle()
    when (this) {
        null -> bundle.putInt(CallPayload.ENTRY_TYPE, CallPayload.TYPE_NULL)
        is String -> {
            bundle.putInt(CallPayload.ENTRY_TYPE, CallPayload.TYPE_STRING)
            bundle.putString(CallPayload.ENTRY_STRING, this)
        }

        is Int -> {
            bundle.putInt(CallPayload.ENTRY_TYPE, CallPayload.TYPE_INT)
            bundle.putInt(CallPayload.ENTRY_INT, this)
        }

        is Long -> {
            bundle.putInt(CallPayload.ENTRY_TYPE, CallPayload.TYPE_LONG)
            bundle.putLong(CallPayload.ENTRY_LONG, this)
        }

        is Float -> {
            bundle.putInt(CallPayload.ENTRY_TYPE, CallPayload.TYPE_FLOAT)
            bundle.putFloat(CallPayload.ENTRY_FLOAT, this)
        }

        is Double -> {
            bundle.putInt(CallPayload.ENTRY_TYPE, CallPayload.TYPE_DOUBLE)
            bundle.putDouble(CallPayload.ENTRY_DOUBLE, this)
        }

        is Boolean -> {
            bundle.putInt(CallPayload.ENTRY_TYPE, CallPayload.TYPE_BOOLEAN)
            bundle.putBoolean(CallPayload.ENTRY_BOOLEAN, this)
        }

        is Bundle -> {
            bundle.putInt(CallPayload.ENTRY_TYPE, CallPayload.TYPE_BUNDLE)
            bundle.putBundle(CallPayload.ENTRY_BUNDLE, this)
        }

        is Parcelable -> {
            bundle.putInt(CallPayload.ENTRY_TYPE, CallPayload.TYPE_PARCELABLE)
            bundle.putParcelable(CallPayload.ENTRY_PARCELABLE, this)
        }

        is Map<*, *> -> {
            bundle.putInt(CallPayload.ENTRY_TYPE, CallPayload.TYPE_MAP)
            bundle.putBundle(CallPayload.ENTRY_MAP, toBundle(depth + 1))
        }

        is Serializable -> {
            bundle.putInt(CallPayload.ENTRY_TYPE, CallPayload.TYPE_SERIALIZABLE)
            bundle.putSerializable(CallPayload.ENTRY_SERIALIZABLE, this)
        }

        else -> {
            throw IllegalArgumentException("不支持跨进程传递：${javaClass.name}")
        }
    }
    return bundle
}

private fun Bundle.toEntryValue(depth: Int): Any? {
    classLoader = CallPayload::class.java.classLoader
    return when (getInt(CallPayload.ENTRY_TYPE)) {
        CallPayload.TYPE_NULL -> null
        CallPayload.TYPE_STRING -> getString(CallPayload.ENTRY_STRING)
        CallPayload.TYPE_INT -> getInt(CallPayload.ENTRY_INT)
        CallPayload.TYPE_LONG -> getLong(CallPayload.ENTRY_LONG)
        CallPayload.TYPE_FLOAT -> getFloat(CallPayload.ENTRY_FLOAT)
        CallPayload.TYPE_DOUBLE -> getDouble(CallPayload.ENTRY_DOUBLE)
        CallPayload.TYPE_BOOLEAN -> getBoolean(CallPayload.ENTRY_BOOLEAN)
        CallPayload.TYPE_PARCELABLE -> getParcelableCompat(CallPayload.ENTRY_PARCELABLE)
        CallPayload.TYPE_MAP -> getBundle(CallPayload.ENTRY_MAP)?.toPayloadMap(depth + 1)
        CallPayload.TYPE_BUNDLE -> getBundle(CallPayload.ENTRY_BUNDLE)
        CallPayload.TYPE_SERIALIZABLE -> getSerializableCompat(CallPayload.ENTRY_SERIALIZABLE)
        CallPayload.TYPE_NON_SERIALIZABLE -> throw IllegalArgumentException("不支持进程内引用协议")
        else -> throw IllegalArgumentException("未知 Map 条目类型")
    }
}

private fun Any?.contentFlags(depth: Int = 0): Int = when (this) {
    is Parcelable -> describeContents()
    is Map<*, *> -> {
        require(depth < MAX_MAP_DEPTH) { "Map 嵌套不能超过 $MAX_MAP_DEPTH 层，禁止循环引用" }
        values.fold(0) { flags, value -> flags or value.contentFlags(depth + 1) }
    }
    else -> 0
}

private fun Parcel.readParcelableCompat(): Parcelable? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        readParcelable(CallPayload::class.java.classLoader, Parcelable::class.java)
    } else {
        @Suppress("DEPRECATION")
        readParcelable(CallPayload::class.java.classLoader)
    }
}

private fun Parcel.readSerializableCompat(): Serializable? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        readSerializable(CallPayload::class.java.classLoader, Serializable::class.java)
    } else {
        @Suppress("DEPRECATION")
        readSerializable()
    }
}

private fun Bundle.getParcelableCompat(key: String): Parcelable? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelable(key, Parcelable::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelable(key)
    }
}

private fun Bundle.getSerializableCompat(key: String): Serializable? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getSerializable(key, Serializable::class.java)
    } else {
        @Suppress("DEPRECATION")
        getSerializable(key)
    }
}
