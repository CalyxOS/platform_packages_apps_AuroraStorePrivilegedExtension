/*
 * Copyright (C) 2015-2016 Dominik Schürmann <dominik@dominikschuermann.de>
 * Copyright 2007, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.aurora.services;

import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * This service provides an API via AIDL IPC for the main AuroraStore app to install/delete packages.
 */
public class PrivilegedService extends Service {

    public static final String TAG = "PrivilegedExtension";
    private static final String BROADCAST_ACTION_INSTALL =
            "com.aurora.services.ACTION_INSTALL_COMMIT";
    private static final String BROADCAST_ACTION_UNINSTALL =
            "com.aurora.services.ACTION_UNINSTALL_COMMIT";
    private static final String BROADCAST_SENDER_PERMISSION =
            "android.permission.INSTALL_PACKAGE_UPDATES";
    private static final String EXTRA_LEGACY_STATUS = "android.content.pm.extra.LEGACY_STATUS";

    private AccessProtectionHelper accessProtectionHelper;
    private PackageInstaller packageInstaller;

    private IPrivilegedCallback mCallback;

    Context context = this;

    private boolean hasPrivilegedPermissionsImpl() {
        return getPackageManager().checkPermission(BROADCAST_SENDER_PERMISSION, getPackageName())
                == PackageManager.PERMISSION_GRANTED;
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final int returnCode = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE);
            if (returnCode == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                context.startActivity(intent.getParcelableExtra(Intent.EXTRA_INTENT));
            } else {
                final String packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
                final String extra = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                try {
                    mCallback.handleResultX(packageName, returnCode, extra);
                } catch (Exception e) {
                    Log.e(TAG, packageName + " " + extra, e);
                }
            }
        }
    };

    /**
     * Below function is copied mostly as-is from
     * https://android.googlesource.com/platform/packages/apps/PackageInstaller/+/06163dec5a23bb3f17f7e6279f6d46e1851b7d16
     */
    private void doSplitPackageStage(String packageName, List<Uri> uriList) {
        final PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        PackageInstaller.Session session = null;
        try {
            final int sessionId = packageInstaller.createSession(params);
            final byte[] buffer = new byte[65536];
            session = packageInstaller.openSession(sessionId);
            int apkId = -1;
            for (Uri uri : uriList) {
                final InputStream in = getContentResolver().openInputStream(uri);
                final OutputStream out = session.openWrite(packageName + "_" + ++apkId, 0, -1 /* sizeBytes, unknown */);
                try {
                    int c;
                    while ((c = in.read(buffer)) != -1) {
                        out.write(buffer, 0, c);
                    }
                    session.fsync(out);
                } finally {
                    IoUtils.closeQuietly(in);
                    IoUtils.closeQuietly(out);
                }
            }
            // Create a PendingIntent and use it to generate the IntentSender
            Intent broadcastIntent = new Intent(BROADCAST_ACTION_INSTALL);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    this /*context*/,
                    sessionId,
                    broadcastIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            session.commit(pendingIntent.getIntentSender());
        } catch (IOException e) {
            Log.d(TAG, "Failure", e);
            Toast.makeText(this, e.getLocalizedMessage(), Toast.LENGTH_LONG).show();
        } finally {
            IoUtils.closeQuietly(session);
        }
    }

    private final IPrivilegedService.Stub binder = new IPrivilegedService.Stub() {
        @Override
        public boolean hasPrivilegedPermissions() {
            boolean callerIsAllowed = accessProtectionHelper.isCallerAllowed();
            return callerIsAllowed && hasPrivilegedPermissionsImpl();
        }

        @Override
        public void installPackage(Uri packageURI, int flags, String installerPackageName,
                                   IPrivilegedCallback callback) {

        }

        @Override
        public void installSplitPackage(List<Uri> listURI, int flags, String installerPackageName,
                                        IPrivilegedCallback callback) {

        }

        @Override
        public void installPackageX(String packageName, Uri packageURI, int flags,
                                    String installerPackageName, IPrivilegedCallback callback) {
            installSplitPackageX(packageName, List.of(packageURI), flags, installerPackageName, callback);
        }

        @Override
        public void installSplitPackageX(String packageName, List<Uri> uriList, int flags,
                                         String installerPackageName, IPrivilegedCallback callback) {
            if (accessProtectionHelper.isCallerAllowed()) {
                doSplitPackageStage(packageName, uriList);
                mCallback = callback;
            } else {
                try {
                    callback.handleResultX(packageName, 1, "Installer not allowed");
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoteException", e);
                }
            }
        }

        @Override
        public void deletePackage(String packageName, int flags, IPrivilegedCallback callback) {
            deletePackageX(packageName, flags, "", callback);
        }

        @Override
        public void deletePackageX(String packageName, int flags,
                                   String installerPackageName, IPrivilegedCallback callback) {
            if (accessProtectionHelper.isCallerAllowed()) {
                packageInstaller.uninstall(packageName, PendingIntent.getBroadcast(
                        PrivilegedService.this, 0,
                        new Intent(BROADCAST_ACTION_UNINSTALL), PendingIntent.FLAG_UPDATE_CURRENT)
                        .getIntentSender());
                mCallback = callback;
            } else {
                try {
                    callback.handleResultX(packageName, 1, "Uninstaller not allowed");
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoteException", e);
                }
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        accessProtectionHelper = new AccessProtectionHelper(this);
        packageInstaller = getPackageManager().getPackageInstaller();

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BROADCAST_ACTION_INSTALL);
        intentFilter.addAction(BROADCAST_ACTION_UNINSTALL);
        registerReceiver(
                mBroadcastReceiver, intentFilter, BROADCAST_SENDER_PERMISSION, null /*scheduler*/);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mBroadcastReceiver);
    }

}