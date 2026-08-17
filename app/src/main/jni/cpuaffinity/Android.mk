LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE    := cpuaffinity
LOCAL_SRC_FILES := cpuaffinity.c
LOCAL_CFLAGS    := -O2 -Wall

include $(BUILD_SHARED_LIBRARY)
