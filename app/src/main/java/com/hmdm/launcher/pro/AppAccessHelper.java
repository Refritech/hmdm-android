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

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.util.Log;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.json.Application;
import com.hmdm.launcher.json.ServerConfig;
import com.hmdm.launcher.util.LegacyUtils;
import com.hmdm.launcher.util.Utils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * App control when the permissive mode is off: only the apps listed in the configuration are allowed.
 * Not allowed apps having a launcher icon are suspended by the device owner,
 * and the foreground app is checked by CheckForegroundApplicationService.
 */
public class AppAccessHelper {

    // Packages suspended by Headwind MDM, stored to unsuspend them when they become allowed
    private static final String PREFERENCES_BLOCKED_PACKAGES = "PREFERENCES_BLOCKED_PACKAGES";

    // Settings opened by Headwind MDM itself (WiFi setup, admin panel) are allowed for this period
    private static final long SETTINGS_ENABLE_PERIOD_MS = 5 * 60 * 1000;
    private static volatile long settingsEnabledUntil = 0;

    // System packages which must never be blocked, otherwise the device can't show
    // permission dialogs, take calls or install apps
    private static final Set<String> ALWAYS_ALLOWED = new HashSet<>(Arrays.asList(
            "android",
            Const.SYSTEM_UI_PACKAGE_NAME,
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.managedprovisioning",
            "com.android.server.telecom",
            "com.android.phone",
            "com.android.incallui",
            "com.samsung.android.incallui",
            "com.android.emergency"
    ));

    public static void enableSettings() {
        settingsEnabledUntil = System.currentTimeMillis() + SETTINGS_ENABLE_PERIOD_MS;
    }

    // App control is only active when the permissive mode is off; in the kiosk mode, apps are controlled by lock task
    public static boolean isAppControlRequired(ServerConfig config) {
        return config != null && !config.isPermissive() && !config.isKioskMode();
    }

    public static boolean isAllowed(Context context, ServerConfig config, String pkg) {
        if (pkg == null || pkg.equals(context.getPackageName()) || ALWAYS_ALLOWED.contains(pkg)) {
            return true;
        }
        if (isConfigurationApp(config, pkg)) {
            return true;
        }
        if (pkg.equals(getSettingsPackage(context))) {
            return System.currentTimeMillis() < settingsEnabledUntil;
        }
        // Calls must always work, and the keyboard is required by any app
        return pkg.equals(getDefaultDialer(context)) || pkg.equals(getDefaultInputMethodPackage(context));
    }

    public static String getSettingsPackage(Context context) {
        ResolveInfo settings = context.getPackageManager().resolveActivity(new Intent(Settings.ACTION_SETTINGS), 0);
        return settings != null ? settings.activityInfo.packageName : Const.SETTINGS_PACKAGE_NAME;
    }

    // Suspend not allowed apps having a launcher icon, and unsuspend apps which became allowed
    // The settings app is not suspended because Headwind MDM opens it for the administrator,
    // it is controlled by CheckForegroundApplicationService instead
    public static void updateSuspendedPackages(Context context, ServerConfig config) {
        if (!Utils.isDeviceOwner(context)) {
            return;
        }
        SharedPreferences preferences = context.getSharedPreferences(Const.PREFERENCES, Context.MODE_PRIVATE);
        Set<String> previouslyBlocked = new HashSet<>(preferences.getStringSet(PREFERENCES_BLOCKED_PACKAGES, new HashSet<>()));

        Set<String> blocked = new HashSet<>();
        if (isAppControlRequired(config)) {
            String settingsPackage = getSettingsPackage(context);
            for (String pkg : getLauncherPackages(context)) {
                if (!pkg.equals(settingsPackage) && !isAllowed(context, config, pkg)) {
                    blocked.add(pkg);
                }
            }
        }

        // Packages locked by the "locked_packages" app setting stay suspended
        Set<String> unblocked = new HashSet<>(previouslyBlocked);
        unblocked.removeAll(blocked);
        unblocked.removeAll(getLockedPackages(context));

        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = LegacyUtils.getAdminComponentName(context);
        try {
            if (!unblocked.isEmpty()) {
                dpm.setPackagesSuspended(admin, unblocked.toArray(new String[0]), false);
                Log.i(Const.LOG_TAG, "App control: unsuspended " + unblocked);
            }
            if (!blocked.isEmpty()) {
                // Some packages (default dialer, active admins) can't be suspended
                String[] failed = dpm.setPackagesSuspended(admin, blocked.toArray(new String[0]), true);
                if (failed != null && failed.length > 0) {
                    Log.w(Const.LOG_TAG, "App control: failed to suspend " + Arrays.toString(failed));
                    blocked.removeAll(Arrays.asList(failed));
                }
                Log.i(Const.LOG_TAG, "App control: suspended " + blocked);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        preferences.edit().putStringSet(PREFERENCES_BLOCKED_PACKAGES, blocked).apply();
    }

    private static boolean isConfigurationApp(ServerConfig config, String pkg) {
        if (config == null || config.getApplications() == null) {
            return false;
        }
        for (Application application : config.getApplications()) {
            if ((application.getType() == null || application.getType().equals(Application.TYPE_APP)) &&
                    !application.isRemove() && pkg.equals(application.getPkg())) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> getLauncherPackages(Context context) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        Set<String> packages = new HashSet<>();
        for (ResolveInfo info : context.getPackageManager().queryIntentActivities(intent, 0)) {
            packages.add(info.activityInfo.packageName);
        }
        return packages;
    }

    private static String getDefaultDialer(Context context) {
        try {
            TelecomManager telecomManager = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
            return telecomManager != null ? telecomManager.getDefaultDialerPackage() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // Only the current keyboard is allowed: all enabled input methods can't be used here because
    // regular apps may provide one (for example, the Google app provides the voice typing input method)
    private static String getDefaultInputMethodPackage(Context context) {
        String defaultInputMethod = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (defaultInputMethod == null) {
            return null;
        }
        ComponentName component = ComponentName.unflattenFromString(defaultInputMethod);
        return component != null ? component.getPackageName() : null;
    }

    private static Set<String> getLockedPackages(Context context) {
        String lockedPackages = SettingsHelper.getInstance(context).getAppPreference(context.getPackageName(), "locked_packages");
        Set<String> packages = new HashSet<>();
        if (lockedPackages != null) {
            for (String pkg : lockedPackages.split(",")) {
                packages.add(pkg.trim());
            }
        }
        return packages;
    }
}
