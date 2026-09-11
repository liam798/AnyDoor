package com.anydoor

import com.anydoor.internal.AnyDoorConnection
import com.anydoor.internal.AnyDoorService
import com.example.ads.api.AdsApi
import com.example.ads.internal.AdsEndpoint
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 验证实际交付的示例源码，不复制封装逻辑；不代表真实跨进程验收。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
class AdsSourceApiTest {
    private val connectionField = AnyDoor::class.java.getDeclaredField("connection").apply { isAccessible = true }
    private val bindingsField = AnyDoor::class.java.getDeclaredField("bindings").apply { isAccessible = true }
    private lateinit var service: AnyDoorService

    @Before
    fun setup() {
        resetClient()
        service = AnyDoorService()
        connectionField.set(null, AnyDoorConnection { service.asBinder() })
    }

    @After
    fun cleanup() {
        service.shutdown()
        resetClient()
    }

    private fun resetClient() {
        connectionField.set(null, null)
        (bindingsField.get(null) as MutableMap<*, *>).clear()
    }

    @Test
    fun preservesPlacementAndBooleanResults() {
        var ready = true
        var received: String? = null
        val endpoint = AdsEndpoint { placement ->
            received = placement
            ready
        }
        endpoint.register()
        assertTrue(AdsApi.isReady(" Reward "))
        assertEquals(" Reward ", received)
        ready = false
        assertFalse(AdsApi.isReady(" Reward "))
    }

    @Test
    fun rejectsBlankPlacementBeforeDiscovery() {
        var discoveries = 0
        connectionField.set(null, AnyDoorConnection { discoveries++; service.asBinder() })
        for (placement in listOf("", " ", "\t\n")) {
            assertThrows(IllegalArgumentException::class.java) { AdsApi.isReady(placement) }
        }
        assertEquals(0, discoveries)
    }

    @Test
    fun missingHandlerIsNotAFalseResult() {
        val error = assertThrows(IllegalStateException::class.java) { AdsApi.isReady("reward") }
        assertEquals("广告查询未获得有效响应", error.message)
    }

    @Test
    fun wrongResponseTypeIsRejected() {
        AnyDoor.registerHandler("example.ads.v1.isReady", CallHandler { _, _ ->
            CallResult.DoneWith("false")
        })
        val error = assertThrows(IllegalStateException::class.java) { AdsApi.isReady("reward") }
        assertEquals("广告查询结果类型不符合协议", error.message)
    }

    @Test
    fun sdkFailureIsNotAFalseResult() {
        AdsEndpoint { error("模拟 SDK 查询失败") }.register()
        val error = assertThrows(IllegalStateException::class.java) { AdsApi.isReady("reward") }
        assertEquals("广告查询未获得有效响应", error.message)
    }

    @Test
    fun registrationCanBeRepeatedAndRemoved() {
        val endpoint = AdsEndpoint { true }
        endpoint.register()
        endpoint.register()
        assertTrue(AdsApi.isReady("reward"))
        endpoint.unregister()
        endpoint.unregister()
        assertThrows(IllegalStateException::class.java) { AdsApi.isReady("reward") }
        endpoint.register()
        assertTrue(AdsApi.isReady("reward"))
    }

    @Test
    fun initializationAndDiscoveryFailuresPropagate() {
        connectionField.set(null, null)
        assertThrows(IllegalStateException::class.java) { AdsApi.isReady("reward") }
        val failure = IllegalStateException("模拟服务发现失败")
        connectionField.set(null, AnyDoorConnection { throw failure })
        assertSame(failure, assertThrows(IllegalStateException::class.java) { AdsApi.isReady("reward") })
    }
}
