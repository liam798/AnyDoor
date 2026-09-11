package com.anydoor.internal.ipc

import com.anydoor.CallResult

internal object CallResultProtocol {
    private const val KEY_TYPE = "__bus_call_result_type"
    private const val KEY_VALUE = "__bus_call_result_value"
    private const val TYPE_DONE = "done"
    private const val TYPE_DONE_WITH = "done_with"

    fun encode(result: CallResult): CallPayload? {
        return when (result) {
            CallResult.Skip -> null
            CallResult.Done -> CallPayload(
                mapOf(
                    KEY_TYPE to TYPE_DONE
                )
            )

            is CallResult.DoneWith -> CallPayload(
                mapOf(
                    KEY_TYPE to TYPE_DONE_WITH,
                    KEY_VALUE to result.value
                )
            )
        }
    }

    fun decode(payload: CallPayload?): CallResult {
        if (payload == null) {
            return CallResult.Skip
        }
        val value = payload.value
        if (value !is Map<*, *>) {
            return CallResult.DoneWith(value)
        }
        val type = value[KEY_TYPE] as? String ?: return CallResult.DoneWith(value)
        return when (type) {
            TYPE_DONE -> CallResult.Done
            TYPE_DONE_WITH -> CallResult.DoneWith(value[KEY_VALUE])

            else -> CallResult.DoneWith(value)
        }
    }
}
