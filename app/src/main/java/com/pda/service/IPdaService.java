package com.pda.service;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

public interface IPdaService extends IInterface {
    void registerRealTimeImageCallback(RealTimeImageCallback callback) throws RemoteException;

    void unregisterRealTimeImageCallback(RealTimeImageCallback callback) throws RemoteException;

    abstract class Stub extends Binder implements IPdaService {
        private static final String DESCRIPTOR = "com.pda.service.IPdaService";
        static final int TRANSACTION_registerRealTimeImageCallback = 1;
        static final int TRANSACTION_unregisterRealTimeImageCallback = 2;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static IPdaService asInterface(IBinder binder) {
            if (binder == null) {
                return null;
            }
            IInterface localInterface = binder.queryLocalInterface(DESCRIPTOR);
            if (localInterface instanceof IPdaService) {
                return (IPdaService) localInterface;
            }
            return new Proxy(binder);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            switch (code) {
                case TRANSACTION_registerRealTimeImageCallback:
                    data.enforceInterface(DESCRIPTOR);
                    registerRealTimeImageCallback(RealTimeImageCallback.Stub.asInterface(data.readStrongBinder()));
                    reply.writeNoException();
                    return true;
                case TRANSACTION_unregisterRealTimeImageCallback:
                    data.enforceInterface(DESCRIPTOR);
                    unregisterRealTimeImageCallback(RealTimeImageCallback.Stub.asInterface(data.readStrongBinder()));
                    reply.writeNoException();
                    return true;
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }

        private static final class Proxy implements IPdaService {
            private final IBinder remote;

            private Proxy(IBinder remote) {
                this.remote = remote;
            }

            @Override
            public IBinder asBinder() {
                return remote;
            }

            @Override
            public void registerRealTimeImageCallback(RealTimeImageCallback callback) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeStrongBinder(callback != null ? callback.asBinder() : null);
                    remote.transact(TRANSACTION_registerRealTimeImageCallback, data, reply, 0);
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public void unregisterRealTimeImageCallback(RealTimeImageCallback callback) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeStrongBinder(callback != null ? callback.asBinder() : null);
                    remote.transact(TRANSACTION_unregisterRealTimeImageCallback, data, reply, 0);
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }
        }
    }
}
