package com.anydoor.internal.ipc;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public interface ICallCallback extends IInterface {
    String DESCRIPTOR = ICallCallback.class.getName();
    int TRANSACTION_ON_RESULT = 1;

    void onResult(String callId, @Nullable CallPayload result) throws RemoteException;

    abstract class Stub extends Binder implements ICallCallback {

        public static ICallCallback asInterface(IBinder binder) {
            if (binder == null) {
                return null;
            }

            IInterface iin = binder.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof ICallCallback) {
                return (ICallCallback) iin;
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
                case TRANSACTION_ON_RESULT: {
                    data.enforceInterface(DESCRIPTOR);
                    String callId = data.readString();
                    CallPayload dataValue = data.readInt() != 0 ? CallPayload.CREATOR.createFromParcel(data) : null;
                    onResult(callId, dataValue);
                    reply.writeNoException();
                    return true;
                }
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    class Proxy implements ICallCallback {
        private final IBinder remote;

        public Proxy(IBinder remote) {
            this.remote = remote;
        }

        @Override
        public IBinder asBinder() {
            return remote;
        }

        @Override
        public void onResult(String callId, CallPayload dataValue) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeString(callId);
                if (dataValue != null) {
                    data.writeInt(1);
                    dataValue.writeToParcel(data, 0);
                } else {
                    data.writeInt(0);
                }
                if (!remote.transact(TRANSACTION_ON_RESULT, data, reply, 0)) {
                    throw new RemoteException("回调不支持结果事务");
                }
                reply.readException();
            } finally {
                data.recycle();
                reply.recycle();
            }
        }
    }
}
