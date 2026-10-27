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

package com.hmdm.launcher.pro;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.location.Location;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.R;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.json.Application;
import com.hmdm.launcher.json.ServerConfig;
import com.hmdm.launcher.util.LegacyUtils;
import com.hmdm.launcher.util.Utils;

import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * These functions are available in Pro-version only
 * In a free version, the class contains stubs
 */
public class ProUtils {

    public static boolean isPro() {
        return false;
    }

    // Overlay button used to exit the kiosk mode; only one instance is kept on the screen
    private static View kioskUnlockButton;

    // Lock task (COSU) kiosk mode is only available when Headwind MDM is the device owner,
    // otherwise startLockTask() would turn on a screen pinning which the user can exit
    public static boolean kioskModeRequired(Context context) {
        ServerConfig config = SettingsHelper.getInstance(context).getConfig();
        return config != null && config.isKioskMode() && Utils.isDeviceOwner(context);
    }

    public static void initCrashlytics(Context context) {
        // Stub
    }

    public static void sendExceptionToCrashlytics(Throwable e) {
        // Stub
    }

    // Start the service checking if the foreground app is allowed to the user (by usage statistics)
    public static boolean checkAccessibilityService(Context context) {
        // Stub
        return true;
    }

    // Pro-version
    public static boolean checkUsageStatistics(Context context) {
        // Stub
        return true;
    }

    // Add a transparent view on top of the status bar which prevents user interaction with the status bar
    public static View preventStatusBarExpansion(Activity activity) {
        // Stub
        return null;
    }

    // Add a transparent view on top of a swipeable area at the right (opens app list on Samsung tablets)
    public static View preventApplicationsList(Activity activity) {
        // Stub
        return null;
    }

    // Invisible button in the top left corner drawn over the kiosk app
    // MainActivity opens the password dialog after several clicks on it
    public static View createKioskUnlockButton(Activity activity) {
        removeKioskUnlockButton(activity);

        int size = activity.getResources().getDimensionPixelSize(R.dimen.app_list_item_size) / 2;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(size, size,
                Utils.OverlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSPARENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;

        View view = new View(activity);
        view.setBackgroundColor(Color.TRANSPARENT);
        try {
            getWindowManager(activity).addView(view, params);
        } catch (Exception e) {
            // No permission to draw overlays
            e.printStackTrace();
            return null;
        }
        kioskUnlockButton = view;
        return view;
    }

    private static void removeKioskUnlockButton(Context context) {
        if (kioskUnlockButton != null) {
            try { getWindowManager(context).removeView(kioskUnlockButton); }
            catch (Exception e) { e.printStackTrace(); }
            kioskUnlockButton = null;
        }
    }

    private static WindowManager getWindowManager(Context context) {
        return (WindowManager) context.getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
    }

    public static boolean isKioskAppInstalled(Context context) {
        ServerConfig config = SettingsHelper.getInstance(context).getConfig();
        if (config == null || config.getMainApp() == null || config.getMainApp().trim().equals("")) {
            return false;
        }
        try {
            context.getPackageManager().getPackageInfo(config.getMainApp(), 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static boolean isKioskModeRunning(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        return am != null && am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
    }

    public static Intent getKioskAppIntent(String kioskApp, Activity activity) {
        if (kioskApp == null) {
            return null;
        }
        Intent intent = activity.getPackageManager().getLaunchIntentForPackage(kioskApp);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return intent;
    }

    // Start COSU kiosk mode
    // If Headwind MDM itself is the kiosk app, its own task is locked and the caller should draw the content
    public static boolean startCosuKioskMode(String kioskApp, Activity activity, boolean enableSettings) {
        if (!Utils.isDeviceOwner(activity)) {
            Log.e(Const.LOG_TAG, "Kiosk mode requires Headwind MDM to be the device owner");
            return false;
        }
        updateKioskAllowedApps(kioskApp, activity, enableSettings);
        updateKioskOptions(activity);

        try {
            if (activity.getPackageName().equals(kioskApp)) {
                activity.startLockTask();
                return true;
            }

            Intent intent = getKioskAppIntent(kioskApp, activity);
            if (intent == null) {
                Log.e(Const.LOG_TAG, "Kiosk app " + kioskApp + " is not installed or has no launcher activity");
                return false;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setLockTaskEnabled(true);
                activity.startActivity(intent, options.toBundle());
            } else {
                // Lock the launcher task; the allowed kiosk app can then be started in a new task
                activity.startLockTask();
                activity.startActivity(intent);
            }
            return true;
        } catch (Exception e) {
            Log.e(Const.LOG_TAG, "Failed to start kiosk mode: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // Set/update kiosk mode options (lock tack features)
    public static void updateKioskOptions(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !Utils.isDeviceOwner(activity)) {
            return;
        }
        ServerConfig config = SettingsHelper.getInstance(activity).getConfig();
        if (config == null) {
            return;
        }

        int features = DevicePolicyManager.LOCK_TASK_FEATURE_NONE;
        boolean home = isTrue(config.getKioskHome());
        if (home) {
            features |= DevicePolicyManager.LOCK_TASK_FEATURE_HOME;
            // Overview and notifications are only available together with the Home button
            if (isTrue(config.getKioskRecents())) {
                features |= DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW;
            }
            if (isTrue(config.getKioskNotifications())) {
                features |= DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS;
            }
        }
        if (isTrue(config.getKioskSystemInfo())) {
            features |= DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO;
        }
        if (isTrue(config.getKioskKeyguard())) {
            features |= DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD;
        }
        if (!isTrue(config.getKioskLockButtons())) {
            // Power button menu is available unless the buttons are locked
            features |= DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS;
        }

        try {
            DevicePolicyManager dpm = (DevicePolicyManager) activity.getSystemService(Context.DEVICE_POLICY_SERVICE);
            dpm.setLockTaskFeatures(LegacyUtils.getAdminComponentName(activity), features);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static boolean isTrue(Boolean value) {
        return value != null && value;
    }

    // Update app list in the kiosk mode
    // Allowed apps are: Headwind MDM, the kiosk app, applications marked "use in kiosk" in the configuration
    // and the system settings if they're requested (for example, to setup WiFi)
    // Other configuration apps (mostly hidden system apps) are not allowed, otherwise the user
    // could escape the kiosk app through them (share dialogs, browsers, launchers)
    public static void updateKioskAllowedApps(String kioskApp, Activity activity, boolean enableSettings) {
        if (!Utils.isDeviceOwner(activity)) {
            return;
        }
        Set<String> packages = new LinkedHashSet<>();
        packages.add(activity.getPackageName());
        if (kioskApp != null && !kioskApp.trim().equals("")) {
            packages.add(kioskApp);
        }
        ServerConfig config = SettingsHelper.getInstance(activity).getConfig();
        if (config != null && config.getApplications() != null) {
            for (Application application : config.getApplications()) {
                if ((application.getType() == null || application.getType().equals(Application.TYPE_APP)) &&
                        application.isUseKiosk() && !application.isRemove() && application.getPkg() != null) {
                    packages.add(application.getPkg());
                }
            }
        }
        if (enableSettings) {
            ResolveInfo settings = activity.getPackageManager().resolveActivity(
                    new Intent(Settings.ACTION_SETTINGS), 0);
            packages.add(settings != null ? settings.activityInfo.packageName : "com.android.settings");
        }

        try {
            DevicePolicyManager dpm = (DevicePolicyManager) activity.getSystemService(Context.DEVICE_POLICY_SERVICE);
            dpm.setLockTaskPackages(LegacyUtils.getAdminComponentName(activity), packages.toArray(new String[0]));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Exit the kiosk mode
    // stopLockTask() works only for the activity which started the lock task, so the reliable way
    // is to clear the allowed package list: the system then exits lock task for all their tasks
    public static void unlockKiosk(Activity activity) {
        removeKioskUnlockButton(activity);
        try {
            activity.stopLockTask();
        } catch (Exception e) {
            // Lock task was not started by this activity
        }
        if (Utils.isDeviceOwner(activity)) {
            try {
                DevicePolicyManager dpm = (DevicePolicyManager) activity.getSystemService(Context.DEVICE_POLICY_SERVICE);
                dpm.setLockTaskPackages(LegacyUtils.getAdminComponentName(activity), new String[0]);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static void processConfig(Context context, ServerConfig config) {
        // Stub
    }

    public static void processLocation(Context context, Location location, String provider) {
        // Stub    
    }

    public static String getAppName(Context context) {
        return context.getString(R.string.app_name);
    }

    public static String getCopyright(Context context) {
        return "(c) " + Calendar.getInstance().get(Calendar.YEAR) + " " + context.getString(R.string.vendor);
    }
}
