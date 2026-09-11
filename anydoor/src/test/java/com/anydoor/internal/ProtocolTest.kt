package com.anydoor.internal

import android.os.Binder
import android.os.Parcel
import android.os.RemoteException
import com.anydoor.CallResult
import com.anydoor.internal.ipc.CallPayload
import com.anydoor.internal.ipc.CallResultProtocol
import com.anydoor.internal.ipc.ICallCallback
import com.anydoor.internal.ipc.ICallHandler
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 33])
class ProtocolTest {
    private fun roundTrip(result: CallResult): CallResult {
        val payload = CallResultProtocol.encode(result) ?: return CallResultProtocol.decode(null)
        val parcel = Parcel.obtain()
        return try {
            payload.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            CallResultProtocol.decode(CallPayload.CREATOR.createFromParcel(parcel))
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun resultVariantsSurviveSerialization() {
        assertEquals(CallResult.Skip, roundTrip(CallResult.Skip))
        assertEquals(CallResult.Done, roundTrip(CallResult.Done))
        assertEquals(CallResult.DoneWith(null), roundTrip(CallResult.DoneWith(null)))
        assertEquals(CallResult.DoneWith("完成"), roundTrip(CallResult.DoneWith("完成")))
    }

    @Test
    fun businessMapCannotBeMistakenForResultEnvelope() {
        val result = CallResult.DoneWith(
            mapOf("__bus_call_result_type" to "done", "nested" to mapOf("value" to 42))
        )
        assertEquals(result, roundTrip(result))
    }

    @Test
    fun legacyPayloadFallbackIsPreserved() {
        for (value in listOf(42, mapOf("value" to "完成"), mapOf("__bus_call_result_type" to "future"))) {
            assertEquals(CallResult.DoneWith(value), CallResultProtocol.decode(CallPayload(value)))
        }
        assertEquals(
            CallResult.DoneWith(null),
            CallResultProtocol.decode(CallPayload(mapOf("__bus_call_result_type" to "done_with")))
        )
    }

    @Test
    fun unsupportedHandlerTransactionThrowsRemoteException() {
        assertThrows(RemoteException::class.java) {
            ICallHandler.Proxy(Binder()).onCall("echo", null)
        }
    }

    @Test
    fun unsupportedCallbackTransactionThrowsRemoteException() {
        assertThrows(RemoteException::class.java) {
            ICallCallback.Proxy(Binder()).onResult("echo", null)
        }
    }
}
