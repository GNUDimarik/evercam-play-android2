package io.evercam.androidapp.tasks;

import android.os.AsyncTask;
import android.util.Log;

import java.util.ArrayList;

import io.evercam.API;
import io.evercam.Camera;
import io.evercam.EvercamException;
import io.evercam.androidapp.CamerasActivity;
import io.evercam.androidapp.EvercamPlayApplication;
import io.evercam.androidapp.R;
import io.evercam.androidapp.custom.CustomedDialog;
import io.evercam.androidapp.dal.DbCamera;
import io.evercam.androidapp.dto.AppData;
import io.evercam.androidapp.dto.AppUser;
import io.evercam.androidapp.dto.EvercamCamera;

public class LoadCameraListTask extends AsyncTask<Void, Boolean, Boolean>
{
    private AppUser user;
    private CamerasActivity camerasActivity;
    private String TAG = "LoadCameraListTask";
    public boolean reload = false;

    public LoadCameraListTask(AppUser user, CamerasActivity camerasActivity)
    {
        this.user = user;
        this.camerasActivity = camerasActivity;
    }

    @Override
    protected void onPreExecute()
    {
        if(user != null)
        {
            API.setUserKeyPair(user.getApiKey(), user.getApiId());
        }
        else
        {
            EvercamPlayApplication.sendCaughtException(camerasActivity,
                    camerasActivity.getString(R.string.exception_error_empty_user));
            CustomedDialog.showUnexpectedErrorDialog(camerasActivity);
            cancel(true);
        }
    }

    @Override
    protected Boolean doInBackground(Void... params)
    {
        try
        {
            boolean updateDB = false;

            // Step 1: Load camera list from Evercam
            Log.d(TAG, "Step 1: Load camera list from Evercam");
            ArrayList<EvercamCamera> databaseCameraList = new DbCamera(camerasActivity
                    .getApplicationContext()).getCamerasByOwner(user.getUsername(), 500);

            ArrayList<Camera> cameras = Camera.getAll(user.getUsername(), true, false);

            ArrayList<EvercamCamera> evercamCameras = new ArrayList<>();
            for(io.evercam.Camera camera : cameras)
            {
                EvercamCamera evercamCamera = new EvercamCamera().convertFromEvercam(camera);

                evercamCameras.add(evercamCamera);
            }

            //Publish camera list to UI before deciding to update database or not
            AppData.evercamCameraList = evercamCameras;
            reload = true;
            this.publishProgress(true);

            //Simply check total camera number matches or not
            if(databaseCameraList.size() != cameras.size())
            {
                updateDB = true;
            }

            // Step 2: Check if any new cameras different from local saved
            // cameras.
            Log.d(TAG, "Step 2: Check if any new cameras different from local saved cameras.");
            for(EvercamCamera camera : evercamCameras)
            {
                if(!databaseCameraList.contains(camera))
                {
                    Log.d(TAG, "new camera detected!" + camera.toString() + "\n");
                    updateDB = true;
                    break;
                }
                else
                {
                    if(!databaseCameraList.get(databaseCameraList.indexOf(camera)).hasThumbnailUrl())
                    {
                        Log.d(TAG, "Camera exists but need to update thumbnail URL:" + camera.toString() + "\n");
                        updateDB = true;
                        break;
                    }
                }
            }

            // Step 3: Check if any local camera no longer exists in Evercam
            Log.d(TAG, "Step 3: Check if any local camera no longer exists in Evercam");
            if(!updateDB)
            {
                for(EvercamCamera camera : databaseCameraList)
                {
                    if(!evercamCameras.contains(camera))
                    {
                        Log.d(TAG, "camera deleted!" + camera.getCameraId());
                        updateDB = true;
                        break;
                    }
                }
            }

            // Step 4: If any different camera, replace all local camera data.
            Log.d(TAG, "Step 4: If any different camera, replace all local camera data.");
            if(updateDB)
            {
                Log.d(TAG, "Updating db");
                DbCamera dbCamera = new DbCamera(camerasActivity);
                dbCamera.deleteCameraByOwner(user.getUsername());

                for (EvercamCamera evercamCamera : AppData.evercamCameraList)
                {
                    dbCamera.addCamera(evercamCamera);
                }
            }

            return true;
        }
        catch(EvercamException e)
        {
            Log.e(TAG, e.getMessage());
        }
        return false;
    }

    @Override
    protected void onProgressUpdate(Boolean... canLoad)
    {
        Log.d(TAG, "Done");

        camerasActivity.calculateLoadingTimeAndSend();

        CamerasActivity.camerasPerRow = camerasActivity.recalculateCameraPerRow();

        if(canLoad[0])
        {
            if(reload)
            {
                camerasActivity.removeAllCameraViews();
                camerasActivity.addAllCameraViews(true, true);
            }
        }
        else
        {
            //This should never happen because there is no publishProgress(false)
        }

        if(camerasActivity.reloadProgressDialog != null)
        {
            camerasActivity.reloadProgressDialog.dismiss();
        }
    }

    @Override
    protected void onPostExecute(Boolean success)
    {
        //Already handled in onProgressUpdate
    }
}
