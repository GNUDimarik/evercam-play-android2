#include "mediaplayer.h"
#include <string>
#include <algorithm>
#include <gst/video/video.h>
#include <pthread.h>
#include "debug.h"
#include "eventloop.h"

using namespace evercam;

MediaPlayer::MediaPlayer(const EventLoop &loop)
    : m_tcp_timeout(0), m_target_state(GST_STATE_NULL)
    , m_sample_failed_handler(0)
    , m_sample_ready_handler(0),
      m_window(0), m_initialized(false)
{
    LOGD("MediaPlayer::MediaPlayer()");
    initialize(loop);
}

MediaPlayer::~MediaPlayer()
{
    LOGD("~MediaPlayer::MediaPlayer()");
    stop();
}

void MediaPlayer::play()
{
    GstState currentTarget = m_target_state;
    m_target_state = GST_STATE_PLAYING;
    gst_element_set_state(msp_pipeline.get(), m_target_state);

    if (currentTarget == GST_STATE_PAUSED)
        mfn_stream_sucess_handler();
}

void MediaPlayer::pause()
{
    m_target_state = GST_STATE_PAUSED;
    gst_element_set_state(msp_pipeline.get(), m_target_state);
}

void MediaPlayer::stop()
{
    m_target_state = GST_STATE_NULL;
    gst_element_set_state(msp_pipeline.get(), m_target_state);
}

/* Handle sample conversion */
void MediaPlayer::process_converted_sample(GstSample *sample, GError *err, ConvertSampleContext *data)
{
    gst_caps_unref(data->caps);

    if (err == NULL) {
        if (sample != NULL) {
            GstBuffer *buf = gst_sample_get_buffer(sample);
            GstMapInfo info;
            gst_buffer_map (buf, &info, GST_MAP_READ);
            data->player->m_sample_ready_handler(info.data, info.size);
            gst_buffer_unmap (buf, &info);
        }
    }
    else {
        LOGD("Conversion error %s", err->message);
        g_error_free(err);
        data->player->m_sample_failed_handler();
    }

    if (sample != NULL)
        gst_sample_unref(sample);

    //FIXME: last sample?
    if (data->player->msp_last_sample.get() != data->sample)
        gst_sample_unref(data->sample);

    gst_caps_unref(data->caps);
}

/* Sample pthread function */
void *MediaPlayer::convert_thread_func(void *arg)
{
    ConvertSampleContext *data = (ConvertSampleContext*) arg;
    GError *err = NULL;

    if (data->caps != NULL && data->sample != NULL) {
        GstSample *sample = gst_video_convert_sample(data->sample, data->caps, GST_CLOCK_TIME_NONE, &err);
        process_converted_sample(sample, err, data);
        g_free(data);
    }

    return NULL;
}

/* Asynchronous function for converting frame */
void MediaPlayer::convert_sample(ConvertSampleContext *ctx)
{
    pthread_t thread;

    if (pthread_create(&thread, NULL, convert_thread_func, ctx) != 0)
        LOGE("Strange, but can't create sample conversion thread");
}

void MediaPlayer::requestSample(const std::string &fmt)
{
    if (!msp_pipeline)
        return;

    std::string format(fmt);
    std::transform(format.begin(), format.end(), format.begin(), ::tolower);
    if (format != "png"  && format != "jpeg") {
        LOGE("Unsupported image format %s", format.c_str());
        return;
    }

    GstSample *sample;

    if (msp_last_sample)
        sample = msp_last_sample.get();
    else
        g_object_get(msp_pipeline.get(), "sample", &sample, NULL);

    if (sample) {
        ConvertSampleContext *ctx = reinterpret_cast<ConvertSampleContext *> (g_malloc(sizeof(ConvertSampleContext)));
        memset(ctx, 0, sizeof(ConvertSampleContext));
        gchar *img_fmt = g_strdup_printf("image/%s", format.c_str());
        LOGD("img fmt == %s", img_fmt);
        ctx->caps = gst_caps_new_simple (img_fmt, NULL);
        g_free(img_fmt);
        ctx->sample = sample;
        ctx->player = this;
        convert_sample(ctx);
    }
    else {
        LOGD("Can't get sample");
        m_sample_failed_handler();
    }
}

void MediaPlayer::setUri(const std::string& uri)
{
    LOGD("uri %s", uri.c_str());
    g_object_set(msp_pipeline.get(), "uri", uri.c_str(), NULL);
    m_target_state = GST_STATE_READY;
    gst_element_set_state (msp_pipeline.get(), GST_STATE_READY);
}

void MediaPlayer::setTcpTimeout(int value)
{
    m_tcp_timeout = value;
    GstElement *src;
    g_object_get(msp_pipeline.get(), "source", &src, nullptr);

    if (src)
        g_object_set(src, "tcp-timeout", m_tcp_timeout, NULL);
    else
        LOGE("MediaPLayer: can't get src element");
}

void MediaPlayer::recordVideo(const std::string& /* fileName */) throw (std::runtime_error)
{
    // TODO
}

void MediaPlayer::setVideoLoadedHandler(StreamSuccessHandler handler)
{
    mfn_stream_sucess_handler = handler;
}

void MediaPlayer::setVideoLoadingFailedHandler(StreamFailedHandler handler)
{
    mfn_stream_failed_handler = handler;
}

void MediaPlayer::setSampleReadyHandler(SampleReadyHandler handler)
{
   m_sample_ready_handler = handler;
}

void MediaPlayer::setSampleFailedHandler(SampleFailedHandler handler)
{
   m_sample_failed_handler = handler;
}

void MediaPlayer::setSurface(ANativeWindow *window)
{
    if (m_window != 0) {
        ANativeWindow_release (m_window);
        if (m_window == window) {
            gst_video_overlay_expose(GST_VIDEO_OVERLAY (msp_pipeline.get()));
            gst_video_overlay_expose(GST_VIDEO_OVERLAY (msp_pipeline.get()));
        } else
            m_initialized = false;
    }

    m_window = window;
    gst_video_overlay_set_window_handle (GST_VIDEO_OVERLAY (msp_pipeline.get()), reinterpret_cast<guintptr> (m_window));
    m_initialized = true;
}

void MediaPlayer::expose()
{
    if (m_window) {
        gst_video_overlay_expose(GST_VIDEO_OVERLAY (msp_pipeline.get()));
        gst_video_overlay_expose(GST_VIDEO_OVERLAY (msp_pipeline.get()));
    }
}

void MediaPlayer::releaseSurface()
{
    if (m_window != 0) {
        ANativeWindow_release(m_window);
        m_window = 0;
    }

    m_initialized = false;
}

bool MediaPlayer::isInitialized() const
{
    return m_initialized;
}

void MediaPlayer::initialize(const EventLoop& loop) throw (std::runtime_error)
{
    if (!msp_pipeline) {
        GError *err = nullptr;
        GstElement *pipeline = gst_parse_launch("playbin", &err);

        if (!pipeline) {

            std::string error_message = "Could not to create pipeline";

            if (err) {
                error_message = err->message;
                g_error_free(err);
            }

            throw std::runtime_error(error_message);
        }

        GstBus *bus = gst_element_get_bus (pipeline);
        GSource *bus_source = gst_bus_create_watch (bus);
        g_source_set_callback (bus_source, (GSourceFunc) gst_bus_async_signal_func, NULL, NULL);
        gint res = g_source_attach (bus_source, loop.msp_main_ctx.get());
        LOGD("res %d ctx %p pipeline %p", res, loop.msp_main_ctx.get(), pipeline);
        g_source_unref (bus_source);
        g_signal_connect (G_OBJECT (bus), "message::error", (GCallback) handle_bus_error, const_cast<MediaPlayer*> (this));
        gst_object_unref (bus);

        g_signal_connect (pipeline, "source-setup", G_CALLBACK (handle_source_setup), const_cast<MediaPlayer*> (this));
        g_signal_connect (pipeline, "video-changed", G_CALLBACK (handle_video_changed), const_cast<MediaPlayer*> (this));
        g_object_set (pipeline, "buffer-size", 0, NULL);
        gst_element_set_state(pipeline, GST_STATE_READY);

        msp_pipeline = std::shared_ptr<GstElement>(pipeline, gst_object_unref);
    }
}

void MediaPlayer::handle_bus_error(GstBus *, GstMessage *message, MediaPlayer *self)
{
    GError *err;
    gchar *debug;

    gst_message_parse_error (message, &err, &debug);
    LOGE ("MediaPlayer::handle_bus_error: %s\n", err->message);
    g_error_free (err);
    g_free (debug);

    if (self->m_target_state == GST_STATE_PLAYING)
        self->mfn_stream_failed_handler();

    self->m_target_state == GST_STATE_NULL;
}

void MediaPlayer::handle_source_setup(GstElement *, GstElement *src, MediaPlayer *self)
{
    if (self->m_tcp_timeout > 0)
         g_object_set (G_OBJECT (src), "tcp-timeout", self->m_tcp_timeout, NULL);

     g_object_set (G_OBJECT (src), "latency", 0, NULL);
     g_object_set (G_OBJECT (src), "drop-on-latency", 1, NULL);
     g_object_set (G_OBJECT (src), "protocols", 4, NULL);
}

void MediaPlayer::handle_video_changed(GstElement *playbin,  MediaPlayer *self)
{
    // Don't catch bad_function_call, it should be thrown if user didn't set handler

    LOGD("MediaPlayer::handle_video_changed");

    if (self->m_target_state == GST_STATE_PLAYING)
        self->mfn_stream_sucess_handler();

    self->m_target_state = GST_STATE_NULL;
}
