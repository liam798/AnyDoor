package com.anydoor.internal

import com.anydoor.internal.ipc.CallResultProtocol

import com.anydoor.AnyDoorProvider
import com.anydoor.internal.ipc.IAnyDoorService
import com.anydoor.internal.ipc.ICallHandler
import com.anydoor.internal.ipc.CallPayload
import com.anydoor.CallResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
class ProviderTest {
    @Test
    fun providerReturnsSameServiceAndDispatchesCalls() {
        val controller = Robolectric.buildContentProvider(AnyDoorProvider::class.java).create()
        try {
            val provider = controller.get()
            val binder = provider.call("get_call_service", null, null)!!.getBinder("call_service")
            assertSame(binder, provider.call("get_call_service", null, null)!!.getBinder("call_service"))
            assertNull(provider.call("未知", null, null))
            val service = IAnyDoorService.Stub.asInterface(binder)
            val handler = object : ICallHandler.Stub() {
                override fun onCall(id: String, arg: CallPayload?) =
                    CallResultProtocol.encode(CallResult.DoneWith(arg?.value))
            }
            service.registerHandler("echo", handler)
            assertEquals("测试", service.call("echo", CallPayload("测试")).value)
            service.unregisterHandler("echo", handler)
            assertNull(service.call("echo", null))
        } finally {
            controller.shutdown()
        }
    }
}
