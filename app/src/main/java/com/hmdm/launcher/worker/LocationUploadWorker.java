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

package com.hmdm.launcher.worker;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.db.DatabaseHelper;
import com.hmdm.launcher.db.LocationTable;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.server.ServerService;
import com.hmdm.launcher.server.ServerServiceKeeper;

import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.ResponseBody;
import retrofit2.Response;

/**
 * Uploads saved locations to the devicelocations plugin of the server
 */
public class LocationUploadWorker extends Worker {

    // Amount of locations sent to server at once
    public static final int MAX_UPLOADED_LOCATIONS = 50;

    // Locations are collected once per minute and sent in batches to reduce the server load
    public static final int FIRE_PERIOD_MINS = 5;

    // If there's no Internet, retry in 15 minutes
    public static final int FIRE_PERIOD_RETRY_MINS = 15;

    private static final String WORK_TAG_LOCATIONS = "com.hmdm.launcher.WORK_TAG_LOCATIONS";

    private static boolean uploadScheduled = false;

    private Context context;
    private SettingsHelper settingsHelper;

    public static void scheduleUpload(Context context) {
        scheduleUpload(context, FIRE_PERIOD_MINS);
    }

    public static void scheduleUpload(Context context, int delayMins) {
        if (uploadScheduled) {
            return;
        }
        Log.i(Const.LOG_TAG, "LocationUploadWorker scheduled in " + delayMins + " mins");
        OneTimeWorkRequest uploadWorkRequest = new OneTimeWorkRequest.Builder(LocationUploadWorker.class)
                .addTag(Const.WORK_TAG_COMMON)
                .setInitialDelay(delayMins, TimeUnit.MINUTES)
                .build();
        uploadScheduled = true;
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_TAG_LOCATIONS, ExistingWorkPolicy.KEEP, uploadWorkRequest);
    }

    public LocationUploadWorker(
            @NonNull Context context,
            @NonNull WorkerParameters params) {
        super(context, params);
        this.context = context;
        settingsHelper = SettingsHelper.getInstance(context);
    }

    @Override
    // This is running in a background thread by WorkManager
    public Result doWork() {
        uploadScheduled = false;
        try {
            DatabaseHelper dbHelper = DatabaseHelper.instance(context);

            while (true) {
                List<LocationTable.Location> unsentItems = LocationTable.select(dbHelper.getReadableDatabase(), MAX_UPLOADED_LOCATIONS);
                if (unsentItems.size() == 0) {
                    return Result.success();
                }
                if (!upload(unsentItems)) {
                    Log.i(Const.LOG_TAG, "Failed to upload locations: retry in " + FIRE_PERIOD_RETRY_MINS + " mins");
                    scheduleUpload(context, FIRE_PERIOD_RETRY_MINS);
                    return Result.failure();
                }
                Log.i(Const.LOG_TAG, "Locations are uploaded: " + unsentItems.size());
                LocationTable.delete(dbHelper.getWritableDatabase(), unsentItems);
            }
        } catch (Exception e) {
            e.printStackTrace();
            scheduleUpload(context, FIRE_PERIOD_RETRY_MINS);
            return Result.failure();
        }
    }

    // Returns true on success and false on failure
    public boolean upload(List<LocationTable.Location> locations) {
        ServerService serverService = ServerServiceKeeper.getServerServiceInstance(context);
        ServerService secondaryServerService = ServerServiceKeeper.getSecondaryServerServiceInstance(context);
        Response<ResponseBody> response = null;

        try {
            response = serverService.sendLocations(settingsHelper.getServerProject(), settingsHelper.getDeviceId(), locations).execute();
            return response.isSuccessful();
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            if (response == null) {
                response = secondaryServerService.
                        sendLocations(settingsHelper.getServerProject(), settingsHelper.getDeviceId(), locations).execute();
                return response.isSuccessful();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }
}
