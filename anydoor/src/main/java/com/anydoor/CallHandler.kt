package com.anydoor

import androidx.annotation.Keep

@Keep
fun interface CallHandler {

    /**
     * 统一的命令处理方法
     * @param callId 命令ID
     * @param arg 命令参数
     * @return CallResult 返回命令处理结果
     */
    fun onCall(callId: String, arg: Any?): CallResult
}
