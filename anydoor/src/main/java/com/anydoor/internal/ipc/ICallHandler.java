package com.anydoor.internal.ipc;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

import androidx.annotation.NonNull;

public interface ICallHandler extends IInterface {
    String DESCRIPTOR = ICallHandler.class.getName();
    int TRANSACTION_ON_CALL = 1;

    /**
     * 统一的命令处理方法
     *
     * @param callId 命令ID
     * @param arg   命令参数
     * @return 编码后的处理结果；null 表示继续传递给下一个处理器
     */
    CallPayload onCall(String callId, CallPayload arg) throws RemoteException;

    abstract class Stub extends Binder implements ICallHandler {

        public static ICallHandler asInterface(IBinder binder) {
            if (binder == null) {
                return null;
            }

            IInterface iin = binder.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof ICallHandler) {
                return (ICallHandler) iin;
            }

            return new Proxy(binder);
        }

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        public boolean onTransact(int code, @NonNull Parcel data, Parcel reply, int flags) throws RemoteException {
            switch (code) {
                case INTERFACE_TRANSACTION: {
                    reply.writeString(DESCRIPTOR);
                    return true;
                }
                case TRANSACTION_ON_CALL: {
                    data.enforceInterface(DESCRIPTOR);
                    String callId = data.readString();
                    CallPayload arg = data.readInt() != 0 ? CallPayload.CREATOR.createFromParcel(data) : null;
                    CallPayload result = onCall(callId, arg);
                    reply.writeNoException();
                    if (result != null) {
                        reply.writeInt(1);
                        result.writeToParcel(reply, 0);
                    } else {
                        reply.writeInt(0);
                    }
                    return true;
                }
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    class Proxy implements ICallHandler {
        private final IBinder remote;

        public Proxy(IBinder remote) {
            this.remote = remote;
        }

        @Override
        public IBinder asBinder() {
            return remote;
        }

        @Override
        public CallPayload onCall(String callId, CallPayload arg) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            CallPayload result;
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeString(callId);
                if (arg != null) {
                    data.writeInt(1);
                    arg.writeToParcel(data, 0);
                } else {
                    data.writeInt(0);
                }
                if (!remote.transact(TRANSACTION_ON_CALL, data, reply, 0)) {
                    throw new RemoteException("处理器不支持调用事务");
                }
                reply.readException();
                if (reply.readInt() != 0) {
                    result = CallPayload.CREATOR.createFromParcel(reply);
                } else {
                    result = null;
                }
            } finally {
                data.recycle();
                reply.recycle();
            }
            return result;
        }
    }
}
