LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE    := olcvpnhide
LOCAL_SRC_FILES := module.cpp
LOCAL_CPPFLAGS  := -std=c++17 -fvisibility=hidden -fvisibility-inlines-hidden
LOCAL_LDFLAGS   := -Wl,--exclude-libs,ALL
LOCAL_LDLIBS    := -llog
include $(BUILD_SHARED_LIBRARY)
