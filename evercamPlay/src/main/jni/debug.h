#ifndef DEBUG_H
#define DEBUG_H

#if defined(__ANDROID__)
#include <android/log.h>
#define LOGD(...) __android_log_print(ANDROID_LOG_INFO, "evercam", __VA_ARGS__);
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "evercam", __VA_ARGS__);
#elif defined (__linux__)
#include <cstdio>
#define LOGD(...) fprintf(stdout, __VA_ARGS__)
#define LOGE(...) fprintf(stderr, __VA_ARGS__)
#else
#error "Please define debug stuff"
#endif

#endif // DEBUG_H
