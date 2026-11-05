/*
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
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

package com.hmdm.launcher.pro.service;

import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.json.ServerConfig;
import com.hmdm.launcher.pro.AppAccessHelper;
import com.hmdm.launcher.pro.ProUtils;
import com.hmdm.launcher.ui.MainActivity;
import com.hmdm.launcher.util.RemoteLogger;

/**
 * Checks the foreground app by usage statistics when the permissive mode is off.
 * If the app is not allowed by the configuration, Headwind MDM is brought to the front
 * and shows the "application not allowed" screen.
 */
public class CheckForegroundApplicationService extends Service {

    private static final long CHECK_INTERVAL_MS = 1000;

    // The administrator may disable the control for a while (see AdminActivity)
    private static final long CONTROL_DISABLE_PERIOD_MS = 60000;

    // Don't flood the user with the block screen if the app is reopened at once
    private static final long REPEAT_BLOCK_INTERVAL_MS = 3000;

    // Usage events are only queried since the last check, the initial window is wider
    private static final long INITIAL_EVENT_WINDOW_MS = 60000;

    private HandlerThread handlerThread;
    private Handler handler;

    private long controlDisabledUntil = 0;
    private long lastEventQueryTime = 0;
    private String foregroundPackage;
    private String lastBlockedPackage;
    private long lastBlockedTime = 0;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case Const.ACTION_SERVICE_STOP:
                    stopSelf();
                    break;
                case Const.ACTION_STOP_CONTROL:
                    controlDisabledUntil = System.currentTimeMillis() + CONTROL_DISABLE_PERIOD_MS;
                    Log.i(Const.LOG_TAG, "CheckForegroundApplicationService: control disabled for 60 sec");
                    break;
                case Const.ACTION_ENABLE_SETTINGS:
                    AppAccessHelper.enableSettings();
                    break;
            }
        }
    };

    private final Runnable checkRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                checkForegroundApp();
            } catch (Exception e) {
                e.printStackTrace();
            }
            handler.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(Const.LOG_TAG, "CheckForegroundApplicationService: service started");

        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver);
        IntentFilter intentFilter = new IntentFilter(Const.ACTION_SERVICE_STOP);
        intentFilter.addAction(Const.ACTION_STOP_CONTROL);
        intentFilter.addAction(Const.ACTION_ENABLE_SETTINGS);
        LocalBroadcastManager.getInstance(this).registerReceiver(receiver, intentFilter);

        if (handlerThread == null) {
            handlerThread = new HandlerThread("CheckForegroundApplication");
            handlerThread.start();
            handler = new Handler(handlerThread.getLooper());
        }
        handler.removeCallbacks(checkRunnable);
        handler.post(checkRunnable);

        return Service.START_STICKY;
    }

    @Override
    public void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver);
        if (handlerThread != null) {
            handler.removeCallbacks(checkRunnable);
            handlerThread.quitSafely();
            handlerThread = null;
        }
        Log.i(Const.LOG_TAG, "CheckForegroundApplicationService: service stopped");
        super.onDestroy();
    }

    private void checkForegroundApp() {
        ServerConfig config = SettingsHelper.getInstance(this).getConfig();
        if (!AppAccessHelper.isAppControlRequired(config) ||
                System.currentTimeMillis() < controlDisabledUntil ||
                !ProUtils.checkUsageStatistics(this)) {
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) {
            return;
        }

        String pkg = getForegroundPackage();
        if (pkg != null && !AppAccessHelper.isAllowed(this, config, pkg)) {
            blockApp(pkg);
        }
    }

    // The foreground app is the last one resumed according to usage events
    private String getForegroundPackage() {
        UsageStatsManager usageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (usageStatsManager == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        long from = lastEventQueryTime > 0 ? lastEventQueryTime : now - INITIAL_EVENT_WINDOW_MS;
        lastEventQueryTime = now;

        UsageEvents events = usageStatsManager.queryEvents(from, now);
        UsageEvents.Event event = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            // ACTIVITY_RESUMED has the same value as the deprecated MOVE_TO_FOREGROUND
            if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                foregroundPackage = event.getPackageName();
            }
        }
        return foregroundPackage;
    }

    private void blockApp(String pkg) {
        long now = System.currentTimeMillis();
        if (pkg.equals(lastBlockedPackage) && now - lastBlockedTime < REPEAT_BLOCK_INTERVAL_MS) {
            return;
        }
        lastBlockedPackage = pkg;
        lastBlockedTime = now;
        RemoteLogger.log(this, Const.LOG_INFO, "App control: blocked foreground app " + pkg);

        // Move the not allowed app to the background, then show the block screen over the launcher
        Intent homeIntent = new Intent(this, MainActivity.class);
        homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            startActivity(homeIntent);
        } catch (Exception e) {
            e.printStackTrace();
        }
        Intent intent = new Intent(Const.ACTION_HIDE_SCREEN);
        intent.putExtra(Const.PACKAGE_NAME, pkg);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
