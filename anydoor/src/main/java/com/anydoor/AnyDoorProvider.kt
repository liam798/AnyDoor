package com.anydoor

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.anydoor.internal.AnyDoorService

/** 托管唯一调用服务，直接提供 Binder；默认仅允许同 UID 访问。 */
class AnyDoorProvider : ContentProvider() {
    internal companion object {
        const val METHOD_GET_SERVICE = "get_call_service"
        const val KEY_SERVICE = "call_service"
    }

    private val service = AnyDoorService()

    override fun onCreate(): Boolean {
        AnyDoor.initialize(context ?: return false)
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        check(Binder.getCallingUid() == Process.myUid()) { "AnyDoor 只允许同一应用访问" }
        return if (method == METHOD_GET_SERVICE) Bundle().apply {
            putBinder(KEY_SERVICE, service.asBinder())
        } else null
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = 0
}
