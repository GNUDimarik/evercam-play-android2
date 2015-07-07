LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

GSTREAMER_ROOT_ANDROID := /Users/liutingdu/Android3rdParty/gstreamer-1.0-android-arm-release-1.4.5
#GSTREAMER_ROOT_ANDROID := /home/dmitry/etc/android/gst_1.4.5

SHELL := PATH=/usr/bin:/bin:/usr/sbin:/sbin:/usr/local/bin /bin/bash

LOCAL_MODULE    := evercam
LOCAL_SRC_FILES := evercam.cpp mediaplayer.cpp eventloop.cpp
LOCAL_CPPFLAGS = -std=c++0x -fexceptions -frtti
LOCAL_SHARED_LIBRARIES := gstreamer_android
LOCAL_LDLIBS := -llog -landroid
include $(BUILD_SHARED_LIBRARY)

ifndef GSTREAMER_ROOT
ifndef GSTREAMER_ROOT_ANDROID
$(error GSTREAMER_ROOT_ANDROID is not defined!)
endif
GSTREAMER_ROOT        := $(GSTREAMER_ROOT_ANDROID)
endif

GSTREAMER_NDK_BUILD_PATH  := $(GSTREAMER_ROOT)/share/gst-android/ndk-build/

include $(GSTREAMER_NDK_BUILD_PATH)/plugins.mk
GSTREAMER_PLUGINS         := $(GSTREAMER_PLUGINS_CORE) $(GSTREAMER_PLUGINS_PLAYBACK) $(GSTREAMER_PLUGINS_CODECS) $(GSTREAMER_PLUGINS_NET) $(GSTREAMER_PLUGINS_SYS) $(GSTREAMER_PLUGINS_CODECS_RESTRICTED) $(GSTREAMER_PLUGINS_CODECS_GPL) $(GSTREAMER_PLUGINS_ENCODING)
GSTREAMER_EXTRA_DEPS      := gstreamer-video-1.0

include $(GSTREAMER_NDK_BUILD_PATH)/gstreamer-1.0.mk
