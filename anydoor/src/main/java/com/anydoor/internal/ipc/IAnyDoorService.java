package com.anydoor.internal.ipc;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/** AnyDoor 服务 IPC 契约；当前仅包含调用与处理器注册事务。 */
public interface IAnyDoorService extends IInterface {
    String DESCRIPTOR = "com.anydoor.internal.ipc.IAnyDoorService";
    // 保留调用事务编号，移除数据事务但不复用其编号。
    int TRANSACTION_CALL_ASYNC = 3;
    int TRANSACTION_CALL = 4;
    int TRANSACTION_REGISTER_HANDLER = 7;
    int TRANSACTION_UNREGISTER_HANDLER = 8;

    boolean callAsync(String callId, CallPayload arg, ICallCallback callback) throws RemoteException;
    CallPayload call(String callId, CallPayload arg) throws RemoteException;
    void registerHandler(String callId, ICallHandler handler) throws RemoteException;
    void unregisterHandler(String callId, ICallHandler handler) throws RemoteException;

    abstract class Stub extends Binder implements IAnyDoorService {
        public Stub() { attachInterface(this, DESCRIPTOR); }

        public static IAnyDoorService asInterface(IBinder binder) {
            if (binder == null) return null;
            IInterface local = binder.queryLocalInterface(DESCRIPTOR);
            return local instanceof IAnyDoorService ? (IAnyDoorService) local : new Proxy(binder);
        }

        @Override public IBinder asBinder() { return this; }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            switch (code) {
                case INTERFACE_TRANSACTION:
                    reply.writeString(DESCRIPTOR);
                    return true;
                case TRANSACTION_CALL_ASYNC: {
                    data.enforceInterface(DESCRIPTOR);
                    String id = data.readString();
                    CallPayload arg = readPayload(data);
                    boolean accepted = callAsync(id, arg, ICallCallback.Stub.asInterface(data.readStrongBinder()));
                    reply.writeNoException();
                    reply.writeInt(accepted ? 1 : 0);
                    return true;
                }
                case TRANSACTION_CALL: {
                    data.enforceInterface(DESCRIPTOR);
                    CallPayload result = call(data.readString(), readPayload(data));
                    reply.writeNoException();
                    writePayload(reply, result);
                    return true;
                }
                case TRANSACTION_REGISTER_HANDLER:
                case TRANSACTION_UNREGISTER_HANDLER: {
                    data.enforceInterface(DESCRIPTOR);
                    String id = data.readString();
                    ICallHandler handler = ICallHandler.Stub.asInterface(data.readStrongBinder());
                    if (code == TRANSACTION_REGISTER_HANDLER) registerHandler(id, handler);
                    else unregisterHandler(id, handler);
                    reply.writeNoException();
                    return true;
                }
                default: return super.onTransact(code, data, reply, flags);
            }
        }

        private static CallPayload readPayload(Parcel parcel) {
            return parcel.readInt() == 0 ? null : CallPayload.CREATOR.createFromParcel(parcel);
        }

        private static void writePayload(Parcel parcel, CallPayload payload) {
            parcel.writeInt(payload == null ? 0 : 1);
            if (payload != null) payload.writeToParcel(parcel, 0);
        }

        private static final class Proxy implements IAnyDoorService {
            private final IBinder remote;
            Proxy(IBinder remote) { this.remote = remote; }
            @Override public IBinder asBinder() { return remote; }

            private void transact(int code, Parcel data, Parcel reply) throws RemoteException {
                if (!remote.transact(code, data, reply, 0)) {
                    throw new RemoteException("AnyDoor 不支持该调用事务");
                }
                reply.readException();
            }

            @Override
            public boolean callAsync(String id, CallPayload arg, ICallCallback callback) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(id);
                    writePayload(data, arg);
                    data.writeStrongBinder(callback == null ? null : callback.asBinder());
                    transact(TRANSACTION_CALL_ASYNC, data, reply);
                    return reply.readInt() != 0;
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }

            @Override
            public CallPayload call(String id, CallPayload arg) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(id);
                    writePayload(data, arg);
                    transact(TRANSACTION_CALL, data, reply);
                    return readPayload(reply);
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }

            @Override
            public void registerHandler(String id, ICallHandler handler) throws RemoteException {
                updateHandler(TRANSACTION_REGISTER_HANDLER, id, handler);
            }

            @Override
            public void unregisterHandler(String id, ICallHandler handler) throws RemoteException {
                updateHandler(TRANSACTION_UNREGISTER_HANDLER, id, handler);
            }

            private void updateHandler(int code, String id, ICallHandler handler) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(id);
                    data.writeStrongBinder(handler == null ? null : handler.asBinder());
                    transact(code, data, reply);
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }
        }
    }
}
