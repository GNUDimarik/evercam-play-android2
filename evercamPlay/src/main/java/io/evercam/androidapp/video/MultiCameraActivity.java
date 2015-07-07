package io.evercam.androidapp.video;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;

import org.freedesktop.gstreamer.GStreamer;

import io.evercam.androidapp.EvercamPlayApplication;
import io.evercam.androidapp.R;

public class MultiCameraActivity extends Activity
{
    private final String TAG = "MultiCameraActivity";


    /*private native void nativeRequestSample(String format); // supported values are png and jpeg
    private native void nativeSetUri(String uri, int connectionTimeout);
    private native void nativeInit();     // Initialize native code, build pipeline, etc
    private native void nativeFinalize(); // Destroy pipeline and shutdown native code
    private native void nativePlay();     // Set pipeline to PLAYING
    private native void nativePause();    // Set pipeline to PAUSED
    private static native boolean nativeClassInit(); // Initialize native class: cache Method IDs for callbacks
    private native void nativeSurfaceInit(Object surface);
    private native void nativeSurfaceFinalize();
    private long native_custom_data;      // Native code will use this to keep private data
*/
    private final int TCP_TIMEOUT = 10 * 1000000; // 10 seconds in micro seconds

    static
    {
        System.loadLibrary("gstreamer_android");
        System.loadLibrary("evercam");
        //nativeClassInit();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        /**
         * Init Gstreamer
         */
        try
        {
            GStreamer.init(this);
        } catch (Exception e)
        {
            Log.e(TAG, e.getLocalizedMessage());
            finish();
            return;
        }

        //nativeInit();

        setContentView(R.layout.video_activity_layout);

        String rtspURL = "";
        GStreamerSurfaceView firstSurfaceView = (GStreamerSurfaceView) findViewById(R.id.surface_view_1);

        /*nativeSetUri(rtspURL, TCP_TIMEOUT);
        nativePlay();*/
    }

    @Override
    protected void onStart()
    {
        super.onStart();
    }

    @Override
    protected void onStop()
    {
        super.onStop();
    }

    @Override
    protected void onDestroy()
    {
        super.onDestroy();
    }
}
