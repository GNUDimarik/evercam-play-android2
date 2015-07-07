#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <gst/gst.h>
#include <gst/video/video.h>
#include <pthread.h>

#include "mediaplayer.h"
#include "eventloop.h"

GST_DEBUG_CATEGORY_STATIC (debug_category);
#define GST_CAT_DEFAULT debug_category

/*
 * These macros provide a way to store the native pointer to CustomData, which might be 32 or 64 bits, into
 * a jlong, which is always 64 bits, without warnings.
 */
#if GLIB_SIZEOF_VOID_P == 8
# define GET_CUSTOM_DATA(env, thiz, fieldID) (CustomData *)env->GetLongField (thiz, fieldID)
# define SET_CUSTOM_DATA(env, thiz, fieldID, data) env->SetLongField (thiz, fieldID, (jlong)data)
#else
# define GET_CUSTOM_DATA(env, thiz, fieldID) (CustomData *)(jint)env->GetLongField (thiz, fieldID)
# define SET_CUSTOM_DATA(env, thiz, fieldID, data) env->SetLongField (thiz, fieldID, (jlong)(jint)data)
#endif

/* Structure to contain all our information, so we can pass it to callbacks */
typedef struct _CustomData {
    jobject app;                  /* Application instance, used to call its methods. A global reference is kept. */
    evercam::EventLoop *loop;
    evercam::MediaPlayer *player;
} CustomData;

namespace {
    CustomData *g_data;
}

/* These global variables cache values which are not changing during execution */
static pthread_t gst_app_thread;
static pthread_key_t current_jni_env;
static JavaVM *java_vm;
static jfieldID custom_data_field_id;
static jmethodID on_stream_loaded_method_id;
static jmethodID on_stream_load_failed_method_id;
static jmethodID on_request_sample_failed_method_id;
static jmethodID on_request_sample_seccess_method_id;

extern "C" {

/*
 * Private methods
 */

/* Register this thread with the VM */
static JNIEnv *attach_current_thread (void) {
    JNIEnv *env;
    JavaVMAttachArgs args;

    GST_DEBUG ("Attaching thread %p", g_thread_self ());
    args.version = JNI_VERSION_1_4;
    args.name = NULL;
    args.group = NULL;

    if (java_vm->AttachCurrentThread (&env, &args) < 0) {
        GST_ERROR ("Failed to attach current thread");
        return NULL;
    }

    return env;
}

/* Unregister this thread from the VM */
static void detach_current_thread (void *env) {
    GST_DEBUG ("Detaching thread %p", g_thread_self ());
    java_vm->DetachCurrentThread ();
}

/* Retrieve the JNI environment for this thread */
static JNIEnv *get_jni_env (void) {
    JNIEnv *env = reinterpret_cast<JNIEnv*> (pthread_getspecific (current_jni_env));

    if (env == nullptr) {
        env = attach_current_thread ();
        pthread_setspecific (current_jni_env, env);
    }

    return env;
}

static void handle_stream_loading_error()
{
    GST_DEBUG("handle_stream_loading_error");
    CustomData *data = g_data;
    if (!data) return;
    JNIEnv *env = get_jni_env ();

    env->CallVoidMethod (data->app, on_stream_load_failed_method_id);

    if (env->ExceptionCheck ()) {
        GST_ERROR ("Failed to call Java method");
        env->ExceptionClear ();
    }
}

// handle video channel setup
static void handle_stream_loaded()
{
    CustomData *data = g_data;
    if (!data) return;
    JNIEnv *env = get_jni_env ();

    env->CallVoidMethod (data->app, on_stream_loaded_method_id);

    if (env->ExceptionCheck ()) {
        GST_ERROR ("Failed to call Java method");
        env->ExceptionClear ();
    }
}



static void handle_sample_failed() {
    JNIEnv *env = get_jni_env ();

     env->CallVoidMethod (g_data->app, on_request_sample_failed_method_id);

     if (env->ExceptionCheck ()) {
         GST_ERROR ("Failed to call Java method");
         env->ExceptionClear ();
     }
}


static void handle_sample_ready (unsigned char *d, size_t size) {
    JNIEnv *env = get_jni_env ();
    jbyteArray array = env->NewByteArray(size);
    env->SetByteArrayRegion(array, 0, size, reinterpret_cast<const jbyte*> (d));
    env->CallVoidMethod (g_data->app, on_request_sample_seccess_method_id, array, size);

    if (env->ExceptionCheck ()) {
        GST_ERROR ("Failed to call Java method");
        env->ExceptionClear ();
    }
}

/* Main method for the native code. This is executed on its own thread. */
static void *app_function (void *userdata) {
    JavaVMAttachArgs args;
    CustomData *data = (CustomData *)userdata;

    try {
        GST_DEBUG ("Creating pipeline in CustomData at %p", data);
        data->loop = new evercam::EventLoop();
        data->player = new evercam::MediaPlayer(*data->loop);
        evercam::MediaPlayer::StreamSuccessHandler onVideoLoaded = handle_stream_loaded;
        evercam::MediaPlayer::StreamFailedHandler onVideoLoadingFailed = handle_stream_loading_error;
        evercam::MediaPlayer::SampleFailedHandler onSampleFailed = handle_sample_failed;
        evercam::MediaPlayer::SampleReadyHandler onSampleReady = handle_sample_ready;
        data->player->setSampleFailedHandler(onSampleFailed);
        data->player->setSampleReadyHandler(onSampleReady);
        data->player->setVideoLoadedHandler(onVideoLoaded);
        data->player->setVideoLoadingFailedHandler(onVideoLoadingFailed);
        data->loop->exec();
        GST_DEBUG("Exitting from app function");
    }

    catch (std::exception &e) {
        GST_DEBUG("exception %s", e.what());
    }

    return NULL;
}

/*
 * Java Bindings
 */

/* Instruct the native code to create its internal data structure, pipeline and thread */
static void gst_native_init (JNIEnv* env, jobject thiz) {
    //gst_init(NULL, NULL);
    CustomData *data = g_new0 (CustomData, 1);
    g_data = data;
    SET_CUSTOM_DATA (env, thiz, custom_data_field_id, data);
    GST_DEBUG_CATEGORY_INIT (debug_category, "evercam", 0, "Android evercam");
    gst_debug_set_threshold_for_name("evercam", GST_LEVEL_LOG);
    GST_DEBUG ("Created CustomData at %p", data);
    data->app = env->NewGlobalRef (thiz);
    GST_DEBUG ("Created GlobalRef for app object at %p", data->app);
    pthread_create (&gst_app_thread, NULL, &app_function, data);
    //app_function(data);
}

/* Quit the main loop, remove the native thread and free resources */
static void gst_native_finalize (JNIEnv* env, jobject thiz) {
    CustomData *data = GET_CUSTOM_DATA (env, thiz, custom_data_field_id);
    if (!data) return;

    GST_DEBUG ("Quitting main loop...");
    data->loop->quit();
    GST_DEBUG ("Waiting for thread to finish...");
    pthread_join (gst_app_thread, NULL);
    GST_DEBUG ("Deleting GlobalRef for app object at %p", data->app);
    env->DeleteGlobalRef (data->app);
    GST_DEBUG ("Freeing CustomData at %p", data);
    delete data->loop;
    delete data->player;
    g_free (data);
    SET_CUSTOM_DATA (env, thiz, custom_data_field_id, NULL);
    GST_DEBUG ("Done finalizing");
}

/* Set pipeline to PLAYING state */
static void gst_native_play (JNIEnv* env, jobject thiz) {
    CustomData *data = GET_CUSTOM_DATA (env, thiz, custom_data_field_id);
    if (!data) return;
    data->player->play();
}

/* Set pipeline to PAUSED state */
static void gst_native_pause (JNIEnv* env, jobject thiz) {
    CustomData *data = GET_CUSTOM_DATA (env, thiz, custom_data_field_id);
    if (!data) return;
    data->player->pause();
}

/* Set playbin's URI */
void gst_native_set_uri (JNIEnv* env, jobject thiz, jstring uri, jint timeout) {
    CustomData *data = GET_CUSTOM_DATA (env, thiz, custom_data_field_id);
    const char *char_uri = env->GetStringUTFChars (uri, NULL);
    data->player->setTcpTimeout(timeout);
    data->player->setUri(char_uri);
    env->ReleaseStringUTFChars (uri, char_uri);
}

/* Set playbin's URI */
void gst_native_request_sample (JNIEnv* env, jobject thiz, jstring format) {
    CustomData *data = GET_CUSTOM_DATA (env, thiz, custom_data_field_id);
    if (!data)
        return;

    const char *fmt = env->GetStringUTFChars (format, NULL);

    //FIXME: need to delete these handles (leak)
    /*SampleFailed *sample_failed = new SampleFailed(data);
    SampleReady *sample_ready = new SampleReady(data);

    data->player->subscriberSampleReady(sample_ready);
    data->player->subscriberSampleFailed(sample_failed);*/
    data->player->requestSample(fmt);

    env->ReleaseStringUTFChars (format, fmt);
}

/* Static class initializer: retrieve method and field IDs */
static jboolean gst_native_class_init (JNIEnv* env, jclass klass) {
    custom_data_field_id = env->GetFieldID (klass, "native_custom_data", "J");
    on_stream_loaded_method_id = env->GetMethodID (klass, "onVideoLoaded", "()V");
    on_stream_load_failed_method_id = env->GetMethodID (klass, "onVideoLoadFailed", "()V");
    on_request_sample_failed_method_id = env->GetMethodID (klass, "onSampleRequestFailed", "()V");
    on_request_sample_seccess_method_id = env->GetMethodID (klass, "onSampleRequestSuccess", "([BI)V");


    if (!custom_data_field_id || !on_stream_loaded_method_id || !on_stream_load_failed_method_id || !on_request_sample_failed_method_id || !on_request_sample_seccess_method_id) {
        /* We emit this message through the Android log instead of the GStreamer log because the later
         * has not been initialized yet.
         */
        __android_log_print (ANDROID_LOG_ERROR, "evercam", "The calling class does not implement all necessary interface methods");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

static void gst_native_surface_init (JNIEnv *env, jobject thiz, jobject surface) {
    CustomData *data = GET_CUSTOM_DATA (env, thiz, custom_data_field_id);
    if (!data) return;
    ANativeWindow *new_native_window = ANativeWindow_fromSurface(env, surface);
    GST_DEBUG ("Received surface %p (native window %p)", surface, new_native_window);
    data->player->setSurface(new_native_window);
}

static void gst_native_surface_finalize (JNIEnv *env, jobject thiz) {
    CustomData *data = GET_CUSTOM_DATA (env, thiz, custom_data_field_id);
    if (!data) return;
    data->player->stop();
    data->player->releaseSurface();
}

/* List of implemented native methods */
static JNINativeMethod native_methods[] = {
    { "nativeInit", "()V", (void *) gst_native_init},
    { "nativeFinalize", "()V", (void *) gst_native_finalize},
    { "nativePlay", "()V", (void *) gst_native_play},
    { "nativePause", "()V", (void *) gst_native_pause},
    { "nativeRequestSample", "(Ljava/lang/String;)V", (void *) gst_native_request_sample},
    { "nativeSetUri", "(Ljava/lang/String;I)V", (void *) gst_native_set_uri},
    { "nativeSurfaceInit", "(Ljava/lang/Object;)V", (void *) gst_native_surface_init},
    { "nativeSurfaceFinalize", "()V", (void *) gst_native_surface_finalize},
    { "nativeClassInit", "()Z", (void *) gst_native_class_init}

};

/* Library initializer */
jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = NULL;

    java_vm = vm;

    if (vm->GetEnv((void**) &env, JNI_VERSION_1_4) != JNI_OK) {
        __android_log_print (ANDROID_LOG_ERROR, "evercam", "Could not retrieve JNIEnv");
        return 0;
    }
    jclass klass = env->FindClass ("io/evercam/androidapp/video/VideoActivity");
    env->RegisterNatives (klass, native_methods, G_N_ELEMENTS(native_methods));

    pthread_key_create (&current_jni_env, detach_current_thread);

    return JNI_VERSION_1_4;
}

} // extern "C"
