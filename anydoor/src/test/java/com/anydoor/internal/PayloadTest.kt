package com.anydoor.internal

import android.os.Parcel
import android.os.Parcelable
import android.os.Bundle
import com.anydoor.internal.ipc.CallPayload
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.Serializable

data class ParcelValue(val number: Int) : Parcelable {
    override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeInt(number)
    override fun describeContents() = 0
    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<ParcelValue> {
            override fun createFromParcel(source: Parcel) = ParcelValue(source.readInt())
            override fun newArray(size: Int): Array<ParcelValue?> = arrayOfNulls(size)
        }
    }
}

data class SerialValue(val number: Int) : Serializable

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 33])
class PayloadTest {
    private fun roundTrip(value: Any?): Any? {
        val parcel = Parcel.obtain()
        return try {
            CallPayload(value).writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            CallPayload.CREATOR.createFromParcel(parcel).value
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun nestedMapRoundTrip() {
        val value = mapOf("整数" to 7, "嵌套" to mapOf("空" to null, "字符串" to "任意门"))
        assertEquals(value, roundTrip(value))
    }

    @Test
    fun customParcelableRoundTrip() {
        assertEquals(ParcelValue(42), roundTrip(ParcelValue(42)))
        val nested = mapOf("自定义" to ParcelValue(42))
        assertEquals(nested, roundTrip(nested))
    }

    @Test
    fun customSerializableRoundTrip() {
        assertEquals(SerialValue(42), roundTrip(SerialValue(42)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedObject() { roundTrip(Any()) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedNestedObject() { roundTrip(mapOf("对象" to Any())) }

    private fun nestedMap(depth: Int): Any? {
        var value: Any? = 42
        repeat(depth) { value = mapOf("值" to value) }
        return value
    }

    @Test
    fun acceptsMapDepthLimit() {
        val value = nestedMap(32)
        assertEquals(value, roundTrip(value))
        assertEquals(0, CallPayload(value).describeContents())
    }

    @Test
    fun rejectsMapBeyondDepthLimit() {
        val value = nestedMap(33)
        assertThrows(IllegalArgumentException::class.java) { roundTrip(value) }
        assertThrows(IllegalArgumentException::class.java) { CallPayload(value).describeContents() }
    }

    @Test
    fun rejectsCyclicMapWithoutStackOverflow() {
        val value = linkedMapOf<String, Any?>()
        value["自身"] = value
        assertThrows(IllegalArgumentException::class.java) { roundTrip(value) }
        assertThrows(IllegalArgumentException::class.java) { CallPayload(value).describeContents() }
    }

    @Test
    fun sharedMapIsNotTreatedAsCycle() {
        val shared = mapOf("值" to 42)
        val value = mapOf("甲" to shared, "乙" to shared)
        assertEquals(value, roundTrip(value))
    }

    @Test
    fun decoderRejectsOverDeepMapFromPeer() {
        var value = Bundle()
        repeat(32) {
            val entry = Bundle().apply {
                putInt(CallPayload.ENTRY_TYPE, CallPayload.TYPE_MAP)
                putBundle(CallPayload.ENTRY_MAP, value)
            }
            value = Bundle().apply { putBundle("值", entry) }
        }
        val parcel = Parcel.obtain()
        try {
            parcel.writeInt(CallPayload.TYPE_MAP)
            parcel.writeBundle(value)
            parcel.setDataPosition(0)
            assertThrows(IllegalArgumentException::class.java) {
                CallPayload.CREATOR.createFromParcel(parcel)
            }
        } finally {
            parcel.recycle()
        }
    }
}
