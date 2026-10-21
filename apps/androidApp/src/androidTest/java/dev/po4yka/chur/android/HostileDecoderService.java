package dev.po4yka.chur.android;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.system.Os;
import android.system.OsConstants;
import java.io.FileDescriptor;

/**
 * An isolated child that follows the decoder protocol but returns a hostile
 * page region: one far larger than any page, or one shorter than it claims.
 * It is Java because the test APK process has no Kotlin runtime.
 */
public class HostileDecoderService extends Service {
    static final String OVERSIZED = "image/x-hostile-oversized";
    static final String SHORT = "image/x-hostile-short";
    private static final String TOKEN = "dev.po4yka.chur.imports.Derivative";
    private static final int EDGE = 64;

    private final Binder endpoint = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(TOKEN);
            if (code == FIRST_CALL_TRANSACTION) {
                data.readString();
                IBinder callback = data.readStrongBinder();
                Parcel call = Parcel.obtain();
                try {
                    call.writeInterfaceToken(TOKEN);
                    callback.transact(FIRST_CALL_TRANSACTION, call, null, 0);
                } finally { call.recycle(); }
                reply.writeInt(1);
                reply.writeInt(0);
                return true;
            }
            if (code != FIRST_CALL_TRANSACTION + 2) return false;
            IBinder sink = data.readStrongBinder();
            data.readStrongBinder();
            String type = data.readString();
            int length = EDGE * EDGE * 4;
            try {
                FileDescriptor memory = Os.memfd_create("hostile-page", OsConstants.MFD_CLOEXEC);
                ParcelFileDescriptor region = ParcelFileDescriptor.dup(memory);
                Os.close(memory);
                // A sparse 3 GiB region costs nothing until something maps or fills it.
                Os.ftruncate(region.getFileDescriptor(), OVERSIZED.equals(type) ? 3L << 30 : 100);
                Parcel result = Parcel.obtain();
                try {
                    result.writeInterfaceToken(TOKEN);
                    result.writeInt(1);
                    result.writeInt(EDGE);
                    result.writeInt(EDGE);
                    result.writeInt(1);
                    result.writeInt(length);
                    result.writeTypedObject(region, 0);
                    sink.transact(FIRST_CALL_TRANSACTION, result, null, 0);
                } finally { result.recycle(); region.close(); }
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
            return true;
        }
    };

    @Override
    public IBinder onBind(Intent intent) { return endpoint; }
}
