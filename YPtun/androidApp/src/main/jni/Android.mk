ROOT_PATH := $(call my-dir)

include $(ROOT_PATH)/hev-socks5-tunnel/Android.mk

include $(CLEAR_VARS)
LOCAL_PATH := $(ROOT_PATH)
LOCAL_MODULE := YPtun_tun2socks
LOCAL_SRC_FILES := YPtun_tun2socks_jni.c
LOCAL_C_INCLUDES := $(ROOT_PATH)/hev-socks5-tunnel/src
LOCAL_SHARED_LIBRARIES := hev-socks5-tunnel
LOCAL_LDLIBS := -llog
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384
LOCAL_LDFLAGS += -Wl,-z,common-page-size=16384
include $(BUILD_SHARED_LIBRARY)
