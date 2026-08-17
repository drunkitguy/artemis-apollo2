LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE    := cpuaffinity
LOCAL_SRC_FILES := cpuaffinity.c
# _GNU_SOURCE is also set at the top of the source; kept here so a future
# edit to the include order cannot quietly drop cpu_set_t again.
LOCAL_CFLAGS    := -O2 -Wall -D_GNU_SOURCE=1

include $(BUILD_SHARED_LIBRARY)
