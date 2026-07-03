package com.pda.service;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

public interface RealTimeImageCallback extends IInterface {
    void onImageInfo(ParcelFileDescriptor fileDescriptor, int byteCount, int width, int height) throws RemoteException;

    abstract class Stub extends Binder implements RealTimeImageCallback {
        private static final String DESCRIPTOR = "com.pda.service.RealTimeImageCallback";
        static final int TRANSACTION_onImageInfo = 1;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static RealTimeImageCallback asInterface(IBinder binder) {
            if (binder == null) {
                return null;
            }
            IInterface localInterface = binder.queryLocalInterface(DESCRIPTOR);
            if (localInterface instanceof RealTimeImageCallback) {
                return (RealTimeImageCallback) localInterface;
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
            if (code == TRANSACTION_onImageInfo) {
                data.enforceInterface(DESCRIPTOR);
                ParcelFileDescriptor descriptor = data.readInt() != 0
                    ? ParcelFileDescriptor.CREATOR.createFromParcel(data)
                    : null;
                onImageInfo(descriptor, data.readInt(), data.readInt(), data.readInt());
                reply.writeNoException();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }

        private static final class Proxy implements RealTimeImageCallback {
            private final IBinder remote;

            private Proxy(IBinder remote) {
                this.remote = remote;
            }

            @Override
            public IBinder asBinder() {
                return remote;
            }

            @Override
            public void onImageInfo(ParcelFileDescriptor fileDescriptor, int byteCount, int width, int height) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    if (fileDescriptor != null) {
                        data.writeInt(1);
                        fileDescriptor.writeToParcel(data, 0);
                    } else {
                        data.writeInt(0);
                    }
                    data.writeInt(byteCount);
                    data.writeInt(width);
                    data.writeInt(height);
                    remote.transact(TRANSACTION_onImageInfo, data, reply, 0);
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }
        }
    }
}
