package io.evercam.androidapp.video;

import android.app.ActionBar;
import android.app.ActionBar.OnNavigationListener;
import android.app.Activity;
import android.app.TaskStackBuilder;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.support.v4.app.NavUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.logentries.android.AndroidLogger;
import com.squareup.picasso.Picasso;

import org.freedesktop.gstreamer.GStreamer;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.concurrent.RejectedExecutionException;

import io.evercam.Camera;
import io.evercam.androidapp.CamerasActivity;
import io.evercam.androidapp.EvercamPlayApplication;
import io.evercam.androidapp.FeedbackActivity;
import io.evercam.androidapp.MainActivity;
import io.evercam.androidapp.ParentActivity;
import io.evercam.androidapp.R;
import io.evercam.androidapp.ViewCameraActivity;
import io.evercam.androidapp.authentication.EvercamAccount;
import io.evercam.androidapp.custom.CameraListAdapter;
import io.evercam.androidapp.custom.CustomToast;
import io.evercam.androidapp.custom.CustomedDialog;
import io.evercam.androidapp.custom.ProgressView;
import io.evercam.androidapp.dal.DbCamera;
import io.evercam.androidapp.dto.AppData;
import io.evercam.androidapp.dto.AppUser;
import io.evercam.androidapp.dto.EvercamCamera;
import io.evercam.androidapp.feedback.KeenHelper;
import io.evercam.androidapp.feedback.ShortcutFeedbackItem;
import io.evercam.androidapp.feedback.StreamFeedbackItem;
import io.evercam.androidapp.recordings.RecordingWebActivity;
import io.evercam.androidapp.tasks.CaptureSnapshotRunnable;
import io.evercam.androidapp.tasks.DeleteCameraTask;
import io.evercam.androidapp.utils.Commons;
import io.evercam.androidapp.utils.Constants;
import io.evercam.androidapp.utils.EnumConstants.DeleteType;
import io.evercam.androidapp.utils.PrefsManager;
import io.evercam.androidapp.utils.PropertyReader;
import io.evercam.androidapp.video.SnapshotManager.FileType;
import io.keen.client.java.KeenClient;

public class VideoActivity extends ParentActivity implements SurfaceHolder.Callback
{
    public static EvercamCamera evercamCamera;

    private final static String TAG = "VideoActivity";
    private String liveViewCameraId = "";

    private boolean showImagesVideo = false;

    /**
     * UI elements
     */
    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;
    private ProgressView progressView = null;
    private TextView offlineTextView;
    private TextView timeCountTextView;
    private RelativeLayout imageViewLayout;
    private ImageView imageView;
    private ImageView mediaPlayerView;
    private ImageView snapshotMenuView;
    private Animation fadeInAnimation = null;

    private long downloadStartCount = 0;
    private long downloadEndCount = 0;
    private BrowseJpgTask browseJpgTask;
    private boolean isProgressShowing = true;
    static boolean enableLogs = true;

    private final int MIN_SLEEP_INTERVAL = 200; // interval between two requests of
    // images
    private final int ADJUSTMENT_INTERVAL = 10; // how much milli seconds to increment
    // or decrement on image failure or
    // success
    private int sleepInterval = MIN_SLEEP_INTERVAL + 290; // starting image
    // interval
    private boolean startDownloading = false; // start making requests soon
    // after the image is received
    // first time. Until first image
    // is not received, do not make
    // requests
    private static long latestStartImageTime = 0; // time of the latest request
    // that has been made
    private int successiveFailureCount = 0; // how much successive image
    // requests have failed
    private Boolean isShowingFailureMessage = false;
    private Boolean optionsActivityStarted = false;

    public static String startingCameraID;
    private int defaultCameraIndex;

    private boolean paused = false;
    private boolean isPlayingJpg = false;// If true, stop trying video
    // URL for reconnecting.
    private boolean isJpgSuccessful = false; //Whether or not the JPG view ever
    //got successfully played

    private boolean end = false; // whether to end this activity or not

    private boolean editStarted = false;
    private boolean feedbackStarted = false;
    private boolean recordingsStarted = false;

    private Handler timerHandler = new Handler();
    private Thread timerThread;
    private Runnable timerRunnable;

    private TimeCounter timeCounter;

    private Date startTime;
    private AndroidLogger logger;
    private KeenClient client;
    private String username = "";

    /**
     * Gstreamer
     */
    private long native_app_data;

    private native void nativeRequestSample(String format); // supported values are png and jpeg
    private native void nativeSetUri(String uri, int connectionTimeout);
    private native void nativeInit();     // Initialize native code, build pipeline, etc
    private native void nativeFinalize(); // Destroy pipeline and shutdown native code
    private native void nativePlay();     // Set pipeline to PLAYING
    private native void nativePause();    // Set pipeline to PAUSED
    private static native boolean nativeClassInit(); // Initialize native class: cache Method IDs for callbacks
    private native void nativeSurfaceInit(Object surface);
    private native void nativeSurfaceFinalize();
    private native void nativeExpose();
    private long native_custom_data;      // Native code will use this to keep private data

    private final int TCP_TIMEOUT = 10 * 1000000; // 10 seconds in micro seconds

    static
    {
        System.loadLibrary("gstreamer_android");
        System.loadLibrary("evercam");
        nativeClassInit();
    }

    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        try
        {
            super.onCreate(savedInstanceState);

            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

            initAnalyticsObjects();

            readShortcutCameraId();

            if(!liveViewCameraId.isEmpty())
            {
                startingCameraID = liveViewCameraId;
                liveViewCameraId = "";
            }

            launchSleepTimer();

            setDisplayOriention();

            if(this.getActionBar() != null)
            {
                this.getActionBar().setDisplayHomeAsUpEnabled(true);
            }

            /**
             * Init Gstreamer
             */
            try
            {
                GStreamer.init(this);
            } catch (Exception e)
            {
                Log.e(TAG, e.getLocalizedMessage());
                EvercamPlayApplication.sendCaughtException(this, e);
                finish();
                return;
            }
            nativeInit();

            setContentView(R.layout.video_activity_layout);

            initialPageElements();

            checkIsShortcutCameraExists();

            startPlay();
        }
        catch(OutOfMemoryError e)
        {
            Log.e(TAG, e.toString() + "-::OOM::-" + Log.getStackTraceString(e));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        // Here actually no matter what result is returned, all restart video
        // play, but keep the verbose code for future extension.
        if(requestCode == Constants.REQUEST_CODE_PATCH_CAMERA)
        {
            // Restart video playing no matter the patch is success or not.
            if(resultCode == Constants.RESULT_TRUE)
            {
                startPlay();
            }
            else
            {
                startPlay();
            }
        }
        else
        // If back from view camera or feedback
        {
            startPlay();
        }
    }

    @Override
    public void onResume()
    {
        try
        {
            super.onResume();
            this.paused = false;
            editStarted = false;
            feedbackStarted = false;
            recordingsStarted = false;

            if(optionsActivityStarted)
            {
                optionsActivityStarted = false;

                showProgressView();

                startDownloading = false;
                this.paused = false;
                this.end = false;
                this.isShowingFailureMessage = false;

                latestStartImageTime = SystemClock.uptimeMillis();

                if(browseJpgTask == null)
                {
                    // ignore if image thread is null
                }
                else if(browseJpgTask.getStatus() != AsyncTask.Status.RUNNING)
                {
                    browseJpgTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                }
                else if(browseJpgTask.getStatus() == AsyncTask.Status.FINISHED)
                {
                    createBrowseJpgTask();
                }
            }
        }
        catch(OutOfMemoryError e)
        {
            Log.e(TAG, e.toString() + "-::OOM::-" + Log.getStackTraceString(e));
        }
        catch(Exception e)
        {
            Log.e(TAG, e.toString());

            sendToMint(e);
        }
    }

    // When activity gets focused again
    @Override
    public void onRestart()
    {
        try
        {
            super.onRestart();
            paused = false;
            end = false;
            editStarted = false;
            feedbackStarted = false;
            recordingsStarted = false;

            if(optionsActivityStarted)
            {
                setCameraForPlaying(evercamCamera);

                createPlayer(evercamCamera);
            }
        }
        catch(OutOfMemoryError e)
        {
            Log.e(TAG, e.toString() + "-::OOM::-" + Log.getStackTraceString(e));
        }
    }

    @Override
    public void onPause()
    {
        super.onPause();

        if(!optionsActivityStarted)
        {
            this.paused = true;
        }
    }

    @Override
    public void onStop()
    {
        super.onStop();
        releasePlayer();
        end = true;
        if(!optionsActivityStarted)
        {
            if(browseJpgTask != null)
            {
                this.paused = true;
            }
            // Do not finish if user get into edit camera screen, feedback screen, or recording
            if(!editStarted && !feedbackStarted && !recordingsStarted)
            {
                this.finish();
            }
        }

        if(timeCounter != null)
        {
            timeCounter.stop();
            timeCounter = null;
        }
    }

    @Override
    protected void onDestroy()
    {
        nativeFinalize();
        super.onDestroy();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event)
    {
        // TODO: Reset the timer to keep screen awake
        launchSleepTimer();
        return super.dispatchTouchEvent(event);
    }

    private void checkIsShortcutCameraExists()
    {
        //It will refill global camera list in isUserLogged()
        if(MainActivity.isUserLogged(this))
        {
            username = AppData.defaultUser.getUsername();
            if(AppData.evercamCameraList.size() > 0)
            {
                boolean cameraIsAccessible = false;
                for(EvercamCamera camera : AppData.evercamCameraList)
                {
                    if(camera.getCameraId().equals(startingCameraID))
                    {
                        cameraIsAccessible = true;
                        break;
                    }
                }

                if(!cameraIsAccessible)
                {
                    EvercamAccount evercamAccount = new EvercamAccount(this);
                    AppUser matchedUser = null;

                    ArrayList<AppUser> userList = evercamAccount.retrieveUserList();
                    for(AppUser appUser : userList)
                    {
                        if(!appUser.getUsername().equals(username))
                        {
                            ArrayList<EvercamCamera> cameraList = new DbCamera(this).getCamerasByOwner(appUser.getUsername(), 500);
                            for(EvercamCamera camera : cameraList)
                            {
                                if(camera.getCameraId().equals(startingCameraID))
                                {
                                    matchedUser = appUser;
                                    break;
                                }
                            }
                        }
                    }

                    if(matchedUser != null)
                    {
                        CustomToast.showSuperToastShort(this, getString(R
                                .string.msg_switch_account) + " - " + matchedUser.getUsername());
                        evercamAccount.updateDefaultUser(matchedUser.getEmail());
                        checkIsShortcutCameraExists();
                    }
                    else
                    {
                        CustomToast.showSuperToastShort(this, getString(R
                                .string.msg_can_not_access_camera) + " - " + username);
                        new ShortcutFeedbackItem(this, AppData.defaultUser.getUsername(), startingCameraID,
                                ShortcutFeedbackItem.ACTION_TYPE_USE, ShortcutFeedbackItem.RESULT_TYPE_FAILED)
                                .sendToKeenIo(client);
                        navigateBackToCameraList();
                    }
                }
                else
                {
                    new ShortcutFeedbackItem(this, AppData.defaultUser.getUsername(), startingCameraID,
                            ShortcutFeedbackItem.ACTION_TYPE_USE, ShortcutFeedbackItem.RESULT_TYPE_SUCCESS)
                            .sendToKeenIo(client);;
                }
            }
        }
        else
        {
            //If no account signed in
            startActivity(new Intent(this, MainActivity.class));
            finish();
        }
    }

    private void launchSleepTimer()
    {
        try
        {
            if(timerThread != null)
            {
                timerThread = null;
                timerHandler.removeCallbacks(timerRunnable);
            }

            final int sleepTime = getSleepTime();
            if(sleepTime != 0)
            {
                timerRunnable = new Runnable()
                {
                    @Override
                    public void run()
                    {
                        VideoActivity.this.getWindow().clearFlags(WindowManager.LayoutParams
                                .FLAG_KEEP_SCREEN_ON);
                    }
                };
                timerThread = new Thread()
                {
                    @Override
                    public void run()
                    {
                        timerHandler.postDelayed(timerRunnable, sleepTime);
                    }
                };
                timerThread.start();
            }
        }
        catch(Exception e)
        {
            // Catch this exception and send by Google Analytics
            // This should not influence user using the app
            EvercamPlayApplication.sendCaughtException(this, e);
        }
    }

    private void initAnalyticsObjects()
    {
        String logentriesToken = getPropertyReader()
                .getPropertyStr(PropertyReader.KEY_LOGENTRIES_TOKEN);
        if(!logentriesToken.isEmpty())
        {
            logger = AndroidLogger.getLogger(getApplicationContext(), logentriesToken, false);
        }

        client = KeenHelper.getClient(this);
    }

    private int getSleepTime()
    {
        final String VALUE_NEVER = "0";

        String valueString = PrefsManager.getSleepTimeValue(this);
        if(!valueString.equals(VALUE_NEVER))
        {
            return Integer.valueOf(valueString) * 1000;
        }
        else
        {
            return 0;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.video_menu, menu);

        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu)
    {
        MenuItem viewItem = menu.findItem(R.id.video_menu_view_camera);
        MenuItem shortcutItem = menu.findItem(R.id.video_menu_create_shortcut);

        if(evercamCamera != null)
        {
            //Hide 'Edit' option for shared camera
            if(evercamCamera.canEdit())
            {
                viewItem.setVisible(true);
            }
            else
            {
                viewItem.setVisible(true);
            }

            if(evercamCamera.isOffline())
            {
                shortcutItem.setVisible(false);
            }
            else
            {
                shortcutItem.setVisible(true);
            }
        }
        else
        {
            Log.e(TAG, "EvercamCamera is null");
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        int itemId = item.getItemId();
        try
        {
            if(itemId == R.id.video_menu_delete_camera)
            {
                CustomedDialog.getConfirmRemoveDialog(VideoActivity.this, new DialogInterface.OnClickListener()
                {

                    @Override
                    public void onClick(DialogInterface warningDialog, int which)
                    {
                        if(evercamCamera.canDelete())
                        {
                            new DeleteCameraTask(evercamCamera.getCameraId(), VideoActivity.this, DeleteType.DELETE_OWNED).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                        }
                        else
                        {
                            new DeleteCameraTask(evercamCamera.getCameraId(), VideoActivity.this, DeleteType.DELETE_SHARE).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                        }
                    }
                }, R.string.msg_confirm_remove_camera).show();
            }
            else if(itemId == R.id.video_menu_view_camera)
            {
                editStarted = true;
                Intent viewIntent = new Intent(VideoActivity.this, ViewCameraActivity.class);
                startActivityForResult(viewIntent, Constants.REQUEST_CODE_VIEW_CAMERA);
            }
            else if(itemId == android.R.id.home)
            {
                navigateBackToCameraList();
            }
            else if(itemId == R.id.video_menu_feedback)
            {
                feedbackStarted = true;
                Intent feedbackIntent = new Intent(VideoActivity.this, FeedbackActivity.class);
                feedbackIntent.putExtra(Constants.BUNDLE_KEY_CAMERA_ID, evercamCamera.getCameraId());
                startActivityForResult(feedbackIntent, Constants.REQUEST_CODE_FEEDBACK);
            }
            else if(itemId == R.id.video_menu_view_snapshots)
            {
                SnapshotManager.showSnapshotsInGalleryForCamera(this, evercamCamera.getCameraId());
            }
            else if(itemId == R.id.video_menu_create_shortcut)
            {
                Bitmap bitmap = getBitmapFromImageView(imageView);
                HomeShortcut.create(getApplicationContext(), evercamCamera, bitmap);
                CustomToast.showSuperToastShort(this, R.string.msg_shortcut_created);
                EvercamPlayApplication.sendEventAnalytics(this, R.string.category_shortcut, R
                        .string.action_shortcut_create, R.string.label_shortcut_create);
                new ShortcutFeedbackItem(this, AppData.defaultUser.getUsername(), evercamCamera.getCameraId(),
                        ShortcutFeedbackItem.ACTION_TYPE_CREATE, ShortcutFeedbackItem.RESULT_TYPE_SUCCESS)
                        .sendToKeenIo(client);
                getMixpanel().sendEvent(R.string.mixpanel_event_create_shortcut, new JSONObject()
                        .put("Camera ID", evercamCamera.getCameraId()));
            }
            else if(itemId == R.id.video_menu_view_recordings)
            {
                recordingsStarted = true;
                Intent recordingIntent = new Intent(this, RecordingWebActivity.class);
                recordingIntent.putExtra(Constants.BUNDLE_KEY_CAMERA_ID, evercamCamera.getCameraId());
                startActivity(recordingIntent);
            }
        }
        catch(OutOfMemoryError e)
        {
            Log.e(TAG, e.toString() + "-::OOM::-" + Log.getStackTraceString(e));
        }
        catch(Exception e)
        {
            Log.e(TAG, e.toString() + "::" + Log.getStackTraceString(e));

            sendToMint(e);
        }
        return true;
    }

    @Override
    public void onBackPressed()
    {
        navigateBackToCameraList();
    }

    private void navigateBackToCameraList()
    {
        if(CamerasActivity.activity == null)
        {
            if (android.os.Build.VERSION.SDK_INT >= 16)
            {
                Intent upIntent = NavUtils.getParentActivityIntent(this);
                TaskStackBuilder.create(this)
                        .addNextIntentWithParentStack(upIntent)
                        .startActivities();
            }
        }

        finish();
    }


    private void readShortcutCameraId()
    {
        Intent liveViewIntent = this.getIntent();
        if(liveViewIntent != null && liveViewIntent.getExtras() != null)
        {
            EvercamPlayApplication.sendEventAnalytics(this, R.string.category_shortcut,
                    R.string.action_shortcut_use, R.string.label_shortcut_use);

            try
            {
                if(evercamCamera != null)
                {
                    getMixpanel().sendEvent(R.string.mixpanel_event_use_shortcut, new JSONObject().put("Camera ID", evercamCamera.getCameraId()));
                }
            }
            catch(JSONException e)
            {
                e.printStackTrace();
            }

            liveViewCameraId = liveViewIntent.getExtras().getString(HomeShortcut.KEY_CAMERA_ID, "");
        }
    }

    private void startPlay()
    {
        sendToLogentries(logger, "User: " + username + " is viewing camera: " + startingCameraID);

        paused = false;
        end = false;

        checkNetworkStatus();

        loadCamerasToActionBar();
    }

    public static boolean startPlayingVideoForCamera(Activity activity, String cameraId)
    {
        startingCameraID = cameraId;
        Intent intent = new Intent(activity, VideoActivity.class);

        activity.startActivityForResult(intent, Constants.REQUEST_CODE_DELETE_CAMERA);

        return false;
    }

    private void setCameraForPlaying(EvercamCamera evercamCamera)
    {
        try
        {
            VideoActivity.evercamCamera = evercamCamera;

            showImagesVideo = false;

            downloadStartCount = 0;
            downloadEndCount = 0;
            isProgressShowing = false;

            startDownloading = false;
            latestStartImageTime = 0;
            successiveFailureCount = 0;
            isShowingFailureMessage = false;

            optionsActivityStarted = false;

            mediaPlayerView.setVisibility(View.GONE);
            snapshotMenuView.setVisibility(View.GONE);

            paused = false;
            end = false;

            surfaceView.setVisibility(View.GONE);
            imageView.setVisibility(View.VISIBLE);
            showProgressView();

            loadImageFromCache(VideoActivity.evercamCamera);

            if(!evercamCamera.isOffline())
            {
                startDownloading = true;
            }

            showProgressView();
        }
        catch(Exception e)
        {
            Log.e(TAG, e.toString() + "::" + Log.getStackTraceString(e));
            sendToMint(e);
            EvercamPlayApplication.sendCaughtException(this, e);
            CustomedDialog.showUnexpectedErrorDialog(VideoActivity.this);
        }
    }

    // Loads image from cache. First image gets loaded correctly and hence we
    // can start making requests concurrently as well
    public void loadImageFromCache(EvercamCamera camera)
    {
        imageView.setImageDrawable(null);

//        Bitmap cacheBitmap = EvercamFile.loadBitmapForCamera(this, cameraId);
//        imageView.setImageBitmap(cacheBitmap);
        if(camera.hasThumbnailUrl())
        {
            Picasso.with(this).load(camera.getThumbnailUrl()).into(imageView);
        }
        else
        {
            Log.e(TAG, camera.toString());
        }
    }

    private void startMediaPlayerAnimation()
    {
        if(fadeInAnimation != null)
        {
            fadeInAnimation.cancel();
            fadeInAnimation.reset();

            snapshotMenuView.clearAnimation();
            mediaPlayerView.clearAnimation();
        }

        fadeInAnimation = AnimationUtils.loadAnimation(VideoActivity.this, R.anim.fadein);

        fadeInAnimation.setAnimationListener(new Animation.AnimationListener()
        {
            @Override
            public void onAnimationStart(Animation animation)
            {
                // TODO Auto-generated method stub
            }

            @Override
            public void onAnimationRepeat(Animation animation)
            {
                // TODO Auto-generated method stub
            }

            @Override
            public void onAnimationEnd(Animation animation)
            {

                if(!paused)
                {
                    mediaPlayerView.setVisibility(View.GONE);
                    snapshotMenuView.setVisibility(View.GONE);
                }
                else
                {
                    mediaPlayerView.setVisibility(View.VISIBLE);
                    if(surfaceView.getVisibility() != View.VISIBLE)
                    {
                        snapshotMenuView.setVisibility(View.VISIBLE);
                    }
                }

                int orientation = VideoActivity.this.getResources().getConfiguration().orientation;
                if(!paused && orientation == Configuration.ORIENTATION_LANDSCAPE)
                {
                    VideoActivity.this.getActionBar().hide();
                }
            }
        });

        mediaPlayerView.startAnimation(fadeInAnimation);
        snapshotMenuView.startAnimation(fadeInAnimation);
    }

    /**
     * **********
     * Surface
     * ***********
     */

    @Override
    public void surfaceCreated(SurfaceHolder surfaceHolder)
    {
        Log.d("GStreamer", "Surface created: " + surfaceHolder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder surfaceholder, int format, int width, int height)
    {
        Log.d("GStreamer", "Surface changed to format " + format + " width " + width + " height " + height);
        onMediaSizeChanged(width, height);
        nativeSurfaceInit(surfaceHolder.getSurface());
        nativeExpose();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder surfaceholder)
    {
        Log.d("GStreamer", "Surface destroyed");
        nativeSurfaceFinalize();
    }

    /**
     * **********
     * Player
     * ***********
     */

    private String createUri(EvercamCamera camera)
    {
        String uri = "rtsp://" + camera.getUsername() + ":" + camera.getPassword() + "@"
                + camera.getExternalRtspUrl().replaceFirst("rtsp://","");
        return uri;
    }

    private void createPlayer(EvercamCamera camera)
    {
        startTime = new Date();

        if(camera.hasRtspUrl())
        {
            Log.e(TAG, "uri " + createUri(camera));
            nativeSetUri(createUri(camera), TCP_TIMEOUT);
            play(camera);
        }
        else
        {
            //If no RTSP URL exists, start JPG view straight away
            showImagesVideo = true;
            createBrowseJpgTask();
        }
    }

    private void play(EvercamCamera camera)
    {
        nativePlay();
    }

    private void releasePlayer()
    {
        nativePause();
    }

    private void restartPlay()
    {

        nativePlay();
    }

    private void pausePlayer()
    {
        nativePause();
    }

    // when screen gets rotated
    @Override
    public void onConfigurationChanged(Configuration newConfig)
    {
        try
        {
            super.onConfigurationChanged(newConfig);

            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            int orientation = newConfig.orientation;
            if(orientation == Configuration.ORIENTATION_PORTRAIT)
            {
                getWindow().setFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN,
                        WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
                this.getActionBar().show();
            }
            else
            {
                getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                        WindowManager.LayoutParams.FLAG_FULLSCREEN);

                if(!paused && !end && !isProgressShowing) this.getActionBar().hide();
                else this.getActionBar().show();
            }

            this.invalidateOptionsMenu();
        }
        catch(Exception e)
        {
            EvercamPlayApplication.sendCaughtException(this, e);
            sendToMint(e);
        }
    }

    private void showMediaFailureDialog()
    {
        CustomedDialog.getCanNotPlayDialog(this, new DialogInterface.OnClickListener()
        {

            @Override
            public void onClick(DialogInterface dialog, int which)
            {

                VideoActivity.this.getActionBar().show();
                paused = true;
                isShowingFailureMessage = false;
                dialog.dismiss();
                hideProgressView();
                //	timeCounter.stop();
            }
        }).show();
        isShowingFailureMessage = true;
        showImagesVideo = false;
    }

    private void setDisplayOriention()
    {
        /** Force landscape if it's enabled in settings */
        boolean forceLandscape = PrefsManager.isForceLandscape(this);
        if(forceLandscape)
        {
            this.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        }

        int orientation = this.getResources().getConfiguration().orientation;
        if(orientation == Configuration.ORIENTATION_PORTRAIT)
        {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
        }
        else
        {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
    }

    private void checkNetworkStatus()
    {
        if(!Commons.isOnline(this))
        {
            CustomedDialog.getNoInternetDialog(this, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    paused = true;
                    dialog.dismiss();
                    hideProgressView();
                }
            }).show();
        }
    }

    private void initialPageElements()
    {
        imageViewLayout = (RelativeLayout) this.findViewById(R.id.camera_view_layout);
        imageView = (ImageView) this.findViewById(R.id.img_camera1);
        mediaPlayerView = (ImageView) this.findViewById(R.id.ivmediaplayer1);
        snapshotMenuView = (ImageView) this.findViewById(R.id.player_savesnapshot);

        surfaceView = (SurfaceView) findViewById(R.id.surface_view);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);

        progressView = ((ProgressView) imageViewLayout.findViewById(R.id.ivprogressspinner1));

        progressView.setMinimumWidth(mediaPlayerView.getWidth());
        progressView.setMinimumHeight(mediaPlayerView.getHeight());
        progressView.canvasColor = Color.TRANSPARENT;

        isProgressShowing = true;
        progressView.setVisibility(View.VISIBLE);

        offlineTextView = (TextView) findViewById(R.id.offline_text_view);
        timeCountTextView = (TextView) findViewById(R.id.time_text_view);

        /** The click listener for pause/play button */
        mediaPlayerView.setOnClickListener(new OnClickListener()
        {

            @Override
            public void onClick(View v)
            {
                if(end)
                {
                    Toast.makeText(VideoActivity.this, R.string.msg_try_again,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                if(isProgressShowing) return;
                if(paused) // video is currently paused. Now we need to
                // resume it.
                {
                    timeCountTextView.setVisibility(View.VISIBLE);
                    showProgressView();

                    mediaPlayerView.setImageBitmap(null);
                    mediaPlayerView.setVisibility(View.VISIBLE);
                    snapshotMenuView.setVisibility(View.VISIBLE);
                    mediaPlayerView.setImageResource(android.R.drawable.ic_media_pause);

                    startMediaPlayerAnimation();

                    //If playing url is not null, resume rtsp stream
                    if(evercamCamera != null && !evercamCamera.getExternalRtspUrl().isEmpty())
                    {
                        restartPlay();
                    }
                    //Otherwise restart jpg view
                    else
                    {
                        //Don't need to do anything because image thread is listening
                    }
                    paused = false;
                }
                else
                // video is currently playing. Now we need to pause video
                {
                    timeCountTextView.setVisibility(View.GONE);
                    mediaPlayerView.clearAnimation();
                    snapshotMenuView.clearAnimation();
                    if(fadeInAnimation != null && fadeInAnimation.hasStarted() &&
                            !fadeInAnimation.hasEnded())
                    {
                        fadeInAnimation.cancel();
                        fadeInAnimation.reset();
                    }
                    mediaPlayerView.setVisibility(View.VISIBLE);
                    snapshotMenuView.setVisibility(View.VISIBLE);

                    mediaPlayerView.setImageBitmap(null);
                    mediaPlayerView.setImageResource(android.R.drawable.ic_media_play);

                    pausePlayer();

                    paused = true; // mark the images as paused. Do not stop
                    // threads, but do not show the images
                    // showing up
                }
            }
        });

        /**
         * The click listener of camera live view layout, including both RTSP and JPG view
         *  Once clicked, if camera view is playing, show the pause menu, otherwise do nothing.
         */
        imageViewLayout.setOnClickListener(new OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                if(end)
                {
                    Toast.makeText(VideoActivity.this, R.string.msg_try_again,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                if(isProgressShowing) return;

                if(!paused && !end) // video is currently playing. Show pause button
                {
                    if(mediaPlayerView.getVisibility() == View.VISIBLE)
                    {
                        mediaPlayerView.setVisibility(View.GONE);
                        snapshotMenuView.setVisibility(View.GONE);
                        mediaPlayerView.clearAnimation();
                        snapshotMenuView.clearAnimation();
                        fadeInAnimation.reset();
                    }
                    else
                    {
                        VideoActivity.this.getActionBar().show();
                        mediaPlayerView.setImageResource(android.R.drawable.ic_media_pause);

                        mediaPlayerView.setVisibility(View.VISIBLE);
                        snapshotMenuView.setVisibility(View.VISIBLE);

                        startMediaPlayerAnimation();
                    }
                }
            }
        });

        snapshotMenuView.setOnClickListener(new OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                //Hide pause/snapshot menu if the live view is not paused
                if(!paused)
                {
                    mediaPlayerView.setVisibility(View.GONE);
                    snapshotMenuView.setVisibility(View.GONE);
                    mediaPlayerView.clearAnimation();
                    snapshotMenuView.clearAnimation();
                    fadeInAnimation.reset();
                }

                if(imageView.getVisibility() == View.VISIBLE)
                {
                    Bitmap bitmap = getBitmapFromImageView(imageView);

                    processSnapshot(bitmap, FileType.JPG);
                }
                else if(surfaceView.getVisibility() == View.VISIBLE)
                {
                    nativeRequestSample("jpeg");
                }
            }
        });
    }

    private Bitmap getBitmapFromImageView(ImageView imageView)
    {
        final Bitmap bitmap;
        if(imageView.getDrawable() instanceof BitmapDrawable)
        {
            bitmap = ((BitmapDrawable) imageView.getDrawable()).getBitmap();
        }
        else
        {
            Drawable drawable = imageView.getDrawable();
            bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(),
                    drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.draw(canvas);
        }
        return bitmap;
    }

    // Hide progress view
    void hideProgressView()
    {
        imageViewLayout.findViewById(R.id.ivprogressspinner1).setVisibility(View.GONE);
        isProgressShowing = false;
    }

    void showProgressView()
    {
        progressView.canvasColor = Color.TRANSPARENT;
        progressView.setVisibility(View.VISIBLE);
        isProgressShowing = true;
    }

    private void createBrowseJpgTask()
    {
        browseJpgTask = new BrowseJpgTask();
        browseJpgTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void startTimeCounter()
    {
        if(timeCounter == null)
        {
            String timezone = "Etc/UTC";
            if(evercamCamera != null)
            {
                timezone = evercamCamera.getTimezone();
            }
            timeCounter = new TimeCounter(this, timezone);
        }
        if(!timeCounter.isStarted())
        {
            timeCounter.start();
        }
    }
    private void processSnapshot(Bitmap btm, SnapshotManager.FileType type)
    {
        final Bitmap bitmap = btm;
        final SnapshotManager.FileType fileType = type;

        if (bitmap != null)
        {
            CustomedDialog.getConfirmSnapshotDialog(VideoActivity.this, bitmap,
                new DialogInterface.OnClickListener()
                {
                    @Override
                    public void onClick(DialogInterface dialog, int which)
                    {
                        String path = SnapshotManager.createFilePath
                            (evercamCamera.getCameraId(), fileType);

                        new Thread(new CaptureSnapshotRunnable(VideoActivity
                                  .this, path, bitmap)).start();
                        }
                }).show();
        }
    }

    // Handle stream loaded
    private void onVideoLoaded()
    {
        Log.d(TAG, "onVideoLoaded()");
        runOnUiThread(new Runnable()
        {
            public void run()
            {
                //View gets played, show time count, and start buffering
                isPlayingJpg = false;
                hideProgressView();
                imageView.setVisibility(View.GONE);
                surfaceView.setVisibility(View.VISIBLE);
                startTimeCounter();

                //And send to Google Analytics
                EvercamPlayApplication.sendEventAnalytics(VideoActivity.this,
                        R.string.category_streaming_rtsp,
                        R.string.action_streaming_rtsp_success,
                        R.string.label_streaming_rtsp_success);

                StreamFeedbackItem successItem = new StreamFeedbackItem(VideoActivity
                        .this, AppData.defaultUser.getUsername(), true);
                successItem.setCameraId(evercamCamera.getCameraId());
                successItem.setUrl(createUri(evercamCamera));
                successItem.setType(StreamFeedbackItem.TYPE_RTSP);
                if(startTime != null)
                {
                    float timeDifferenceFloat = Commons.calculateTimeDifferenceFrom
                            (startTime);
                    Log.d(TAG, "Time difference: " + timeDifferenceFloat + " seconds");
                    successItem.setLoadTime(timeDifferenceFloat);
                    startTime = null;
                }

                sendToLogentries(logger, successItem.toJson());

                successItem.sendToKeenIo(client);
            }
        });
    }
    // Handle stream loading failed
    private void onVideoLoadFailed()
    {
        Log.d(TAG, "onVideoLoadFailed()");
        runOnUiThread(new Runnable()
        {
            public void run()
            {
                EvercamPlayApplication.sendEventAnalytics(VideoActivity.this,
                        R.string.category_streaming_rtsp,
                        R.string.action_streaming_rtsp_failed,
                        R.string.label_streaming_rtsp_failed);
                StreamFeedbackItem failedItem = new StreamFeedbackItem
                        (VideoActivity.this, AppData.defaultUser.getUsername(),
                                false);
                failedItem.setCameraId(evercamCamera.getCameraId());
                failedItem.setUrl(createUri(evercamCamera));
                failedItem.setType(StreamFeedbackItem.TYPE_RTSP);


                sendToLogentries(logger, failedItem.toJson());
                failedItem.sendToKeenIo(client);

                isPlayingJpg = true;
                CustomToast.showInBottom(VideoActivity.this, R.string.msg_switch_to_jpg);
                showImagesVideo = true;
                createBrowseJpgTask();
            }
        });
    }

    private void onSampleRequestSuccess(byte[] data, int size)
    {
        final byte [] imageData = data;
        final int imageSize = size;

        runOnUiThread(new Runnable() {
            public void run() {
                final Bitmap bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageSize);
                processSnapshot(bitmap, FileType.JPG);
            }
        });
    }

    private void onSampleRequestFailed()
    {
        runOnUiThread(new Runnable() {
            public void run() {
                Log.d(TAG, "onSampleRequestFailed");
                CustomToast.showInCenterLong(VideoActivity.this, "Requesting snapshot failed");
            }
        });
    }

    public class BrowseJpgTask extends AsyncTask<String, String, String>
    {
        @Override
        protected String doInBackground(String... params)
        {
            while(!end && !isCancelled() && showImagesVideo)
            {
                try
                {
                    // wait for starting
                    while(!startDownloading)
                    {
                        Thread.sleep(500);
                    }

                    if(!paused) // if application is paused, do not send the
                    // requests. Rather wait for the play
                    // command
                    {
                        DownloadImageTask downloadImageTask = new DownloadImageTask(evercamCamera
                                .getCameraId());

                        if(downloadStartCount - downloadEndCount < 9)
                        {
                            downloadImageTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                        }

                        if(downloadStartCount - downloadEndCount > 9 && sleepInterval < 2000)
                        {
                            sleepInterval += ADJUSTMENT_INTERVAL;
                            Log.d(TAG, "Sleep interval adjusted to: " + sleepInterval);
                        }
                        else if(sleepInterval >= MIN_SLEEP_INTERVAL)
                        {
                            sleepInterval -= ADJUSTMENT_INTERVAL;
                            Log.d(TAG, "Sleep interval adjusted to: " + sleepInterval);
                        }
                    }
                }
                catch(RejectedExecutionException ree)
                {
                    Log.e(TAG, ree.toString() + "-::REE::-" + Log.getStackTraceString(ree));

                }
                catch(OutOfMemoryError e)
                {
                    Log.e(TAG, e.toString() + "-::OOM::-" + Log.getStackTraceString(e));
                }
                catch(Exception ex)
                {
                    downloadStartCount--;
                    Log.e(TAG, ex.toString() + "-::::-" + Log.getStackTraceString(ex));
                    sendToMint(ex);
                }
                try
                {
                    Thread.currentThread();
                    Thread.sleep(sleepInterval, 50);
                }
                catch(Exception e)
                {
                    EvercamPlayApplication.sendCaughtException(VideoActivity.this, e);
                    Log.e(TAG, e.toString() + "-::::-" + Log.getStackTraceString(e));
                }
            }
            return null;
        }
    }

    private class DownloadImageTask extends AsyncTask<Void, Void, Drawable>
    {
        private long myStartImageTime;
        private String successUrl = "";//Only used for data collection
        private String cameraId = "";

        public DownloadImageTask(String cameraId)
        {
            this.cameraId = cameraId;
        }

        @Override
        protected Drawable doInBackground(Void... params)
        {
            if(!showImagesVideo)
            {
                return null;
            }
            Drawable response = null;
            if(!paused && !end)
            {
                try
                {
                    myStartImageTime = SystemClock.uptimeMillis();
                    downloadStartCount++;
                    Camera camera = Camera.getById(cameraId, false);
                    InputStream stream = camera.getSnapshotFromEvercam();
                    response = Drawable.createFromStream(stream, "src");
                    if(response != null)
                    {
                        successiveFailureCount = 0;
                    }
                }
                catch(Exception e)
                {
                    Log.e(TAG, "Request snapshot from Evercam error: " + e.toString());
                    successiveFailureCount++;
                }
                catch(OutOfMemoryError e)
                {
                    Log.e(TAG, e.toString() + "-::OOM::-" + Log.getStackTraceString(e));
                    successiveFailureCount++;
                }
                finally
                {
                    downloadEndCount++;
                }
            }
            else
            {
                Log.d(TAG, "Paused or ended");
            }
            return response;
        }

        @Override
        protected void onPostExecute(Drawable result)
        {
            try
            {
                if(!showImagesVideo) return;

                Log.d(TAG, "Failure count:" + successiveFailureCount);

                if(!paused && !end)
                {
                    if(result != null)
                    {
                        Log.d(TAG, "result not null");
                        if(result.getIntrinsicWidth() > 0 && result.getIntrinsicHeight() > 0)
                        {
                            if(myStartImageTime >= latestStartImageTime)
                            {
                                latestStartImageTime = myStartImageTime;

                                if(mediaPlayerView.getVisibility() != View.VISIBLE &&
                                        VideoActivity.this.getResources().getConfiguration()
                                                .orientation == Configuration.ORIENTATION_LANDSCAPE)
                                    VideoActivity.this.getActionBar().hide();

                                if(showImagesVideo && cameraId.equals(evercamCamera.getCameraId()))
                                {
                                    //Only update JPG when the image belongs to the current camera
                                    imageView.setImageDrawable(result);
                                }
                                else if(!cameraId.equals(evercamCamera.getCameraId()))
                                {
                                    Log.e(TAG, "Image received but not to show");
                                }

                                hideProgressView();

                                //Image received, start time counter, need more tests
                                startTimeCounter();

                                if(!isJpgSuccessful)
                                {
                                    //Successfully played JPG view, send Google Analytics event
                                    isJpgSuccessful = true;
                                    EvercamPlayApplication.sendEventAnalytics(VideoActivity.this,
                                            R.string.category_streaming_jpg,
                                            R.string.action_streaming_jpg_success,
                                            R.string.label_streaming_jpg_success);
                                    StreamFeedbackItem successItem = new StreamFeedbackItem
                                            (VideoActivity.this, AppData.defaultUser.getUsername
                                                    (), true);
                                    successItem.setCameraId(evercamCamera.getCameraId());
                                    successItem.setUrl(successUrl);
                                    successItem.setType(StreamFeedbackItem.TYPE_JPG);
                                    sendToLogentries(logger, successItem.toJson());
                                    successItem.sendToKeenIo(client);
                                }
                                else
                                {
                                    //Log.d(TAG, "Jpg success but already reported");
                                }
                            }
                            else
                            {
                                if(enableLogs) Log.i(TAG, "downloaded image discarded. ");
                            }
                        }
                    }
                    else
                    {
                        Log.d(TAG, "result is null");
                        if(successiveFailureCount > 10 && !isShowingFailureMessage)
                        {
                            Log.d(TAG, "successiveFailureCount > 5 && !isShowingFailureMessage");
                            if(myStartImageTime >= latestStartImageTime)
                            {
                                Log.d(TAG, "myStartImageTime >= latestStartImageTime");
                                showMediaFailureDialog();
                                browseJpgTask.cancel(true);

                                //Failed to play JPG view, send Google Analytics event
                                EvercamPlayApplication.sendEventAnalytics(VideoActivity.this,
                                        R.string.category_streaming_jpg,
                                        R.string.action_streaming_jpg_failed,
                                        R.string.label_streaming_jpg_failed);

                                //Send Feedback
                                StreamFeedbackItem failedItem = new StreamFeedbackItem
                                        (VideoActivity.this, AppData.defaultUser.getUsername(),
                                                false);
                                failedItem.setCameraId(evercamCamera.getCameraId());
                                failedItem.setUrl(evercamCamera.getExternalSnapshotUrl());
                                failedItem.setType(StreamFeedbackItem.TYPE_JPG);
                                sendToLogentries(logger, failedItem.toJson());
                                failedItem.sendToKeenIo(client);
                            }
                        }
                    }
                }
                else
                {
                    Log.d(TAG, "paused or ended");
                }
            }
            catch(OutOfMemoryError e)
            {
                if(enableLogs) Log.e(TAG, e.toString() + "-::OOM::-" + Log.getStackTraceString(e));
            }
            catch(Exception e)
            {
                if(enableLogs) Log.e(TAG, e.toString());
                sendToMint(e);
            }

            startDownloading = true;
        }
    }

    private String[] getCameraNameArray(ArrayList<EvercamCamera> cameraList)
    {
        ArrayList<String> cameraNames = new ArrayList<>();

        for(int count = 0; count < cameraList.size(); count++)
        {
            EvercamCamera camera = cameraList.get(count);

            cameraNames.add(camera.getName());
            if(cameraList.get(count).getCameraId().equals(startingCameraID))
            {
                defaultCameraIndex = cameraNames.size() - 1;
            }
        }

        String[] cameraNameArray = new String[cameraNames.size()];
        cameraNames.toArray(cameraNameArray);

        return cameraNameArray;
    }

    private void loadCamerasToActionBar()
    {
        String[] cameraNames;

        final ArrayList<EvercamCamera> onlineCameraList = new ArrayList<>();
        final ArrayList<EvercamCamera> cameraList;

        //If is not showing offline cameras, the offline cameras should be excluded from list
        if(PrefsManager.showOfflineCameras(VideoActivity.this))
        {
            cameraList = AppData.evercamCameraList;
        }
        else
        {
            for(EvercamCamera evercamCamera : AppData.evercamCameraList)
            {
                if(!evercamCamera.isOffline())
                {
                    onlineCameraList.add(evercamCamera);
                }
            }

            cameraList = onlineCameraList;
        }

        cameraNames = getCameraNameArray(cameraList);

        CameraListAdapter adapter = new CameraListAdapter(VideoActivity.this,
                R.layout.live_view_spinner, R.id.spinner_camera_name_text, cameraNames, cameraList);
        VideoActivity.this.getActionBar().setNavigationMode(ActionBar.NAVIGATION_MODE_LIST);
        OnNavigationListener navigationListener = new OnNavigationListener()
        {
            @Override
            public boolean onNavigationItemSelected(int itemPosition, long itemId)
            {
                //Stop time counter when another camera selected
                if(timeCounter != null)
                {
                    timeCounter.stop();
                    timeCounter = null;
                }

                if(browseJpgTask != null && browseJpgTask.getStatus() != AsyncTask.Status.RUNNING)
                {
                    browseJpgTask.cancel(true);
                }
                browseJpgTask = null;
                showImagesVideo = false;

                evercamCamera = cameraList.get(itemPosition);


                if(evercamCamera.isOffline())
                {
                    // If camera is offline, show offline msg and stop video
                    // playing.
                    offlineTextView.setVisibility(View.VISIBLE);
                    progressView.setVisibility(View.GONE);

                    // Hide video elements if switch to an offline camera.
                    surfaceView.setVisibility(View.GONE);
                    imageView.setVisibility(View.GONE);
                }
                else
                {
                    offlineTextView.setVisibility(View.GONE);

                    setCameraForPlaying(cameraList.get(itemPosition));
                    createPlayer(evercamCamera);
                }
                return false;
            }
        };

        getActionBar().setListNavigationCallbacks(adapter, navigationListener);
        getActionBar().setSelectedNavigationItem(defaultCameraIndex);
    }

    private void onMediaSizeChanged (int width, int height) {
        Log.i ("GStreamer", "Media size changed to " + width + "x" + height);
        final GStreamerSurfaceView gstreamerSurfaceView = (GStreamerSurfaceView) this.findViewById(R.id.surface_view);
        gstreamerSurfaceView.media_width = width;
        gstreamerSurfaceView.media_height = height;
        runOnUiThread(new Runnable() {
            public void run() {
                gstreamerSurfaceView.requestLayout();
            }
        });
    }
}
