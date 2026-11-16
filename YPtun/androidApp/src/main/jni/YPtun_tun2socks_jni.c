#include <jni.h>
#include <stdint.h>
#include <unistd.h>
#include <string.h>

#include "hev-main.h"
#include "hev-socks5-tunnel.h"

static JavaVM *g_jvm = NULL;
static jclass g_vpn_service_class = NULL;
static jmethodID g_check_allowed_method = NULL;

static void
init_jni (JNIEnv *env)
{
    if (!g_jvm) {
        (*env)->GetJavaVM (env, &g_jvm);
    }
    if (!g_vpn_service_class) {
        jclass cls = (*env)->FindClass (env, "org/olcbox/app/vpn/service/OlcboxVpnService");
        if (cls) {
            g_vpn_service_class = (*env)->NewGlobalRef (env, cls);
            (*env)->DeleteLocalRef (env, cls);
            g_check_allowed_method = (*env)->GetStaticMethodID (
                env, g_vpn_service_class, "checkConnectionAllowed",
                "(ILjava/lang/String;ILjava/lang/String;I)Z");
        }
    }
}

JNIEXPORT jint JNICALL
JNI_OnLoad (JavaVM *vm, void *reserved)
{
    g_jvm = vm;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv (vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) {
        init_jni (env);
    }
    return JNI_VERSION_1_6;
}

static int
yptun_connection_filter (int protocol,
                         const char *src_ip,
                         uint16_t src_port,
                         const char *dst_ip,
                         uint16_t dst_port)
{
    // Fast path: packets not originating from the local TUN address (e.g. forwarded hotspot / tethering)
    // must always pass unimpeded.
    if (strcmp (src_ip, "10.0.88.88") != 0 &&
        strcmp (src_ip, "fdfe:dcba:9876::1") != 0) {
        return 1;
    }

    if (!g_jvm || !g_vpn_service_class || !g_check_allowed_method) {
        return 1;
    }

    JNIEnv *env = NULL;
    jint get_res = (*g_jvm)->GetEnv (g_jvm, (void **)&env, JNI_VERSION_1_6);
    int need_detach = 0;
    if (get_res == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThread (g_jvm, &env, NULL) != 0) {
            return 1;
        }
        need_detach = 1;
    } else if (get_res != JNI_OK || !env) {
        return 1;
    }

    jstring jsrc = (*env)->NewStringUTF (env, src_ip);
    jstring jdst = (*env)->NewStringUTF (env, dst_ip);
    if (!jsrc || !jdst) {
        if (jsrc) (*env)->DeleteLocalRef (env, jsrc);
        if (jdst) (*env)->DeleteLocalRef (env, jdst);
        if (need_detach) (*g_jvm)->DetachCurrentThread (g_jvm);
        return 1;
    }

    jboolean allowed = (*env)->CallStaticBooleanMethod (
        env, g_vpn_service_class, g_check_allowed_method,
        (jint)protocol, jsrc, (jint)src_port, jdst, (jint)dst_port);

    if ((*env)->ExceptionCheck (env)) {
        (*env)->ExceptionClear (env);
        allowed = JNI_TRUE;
    }

    (*env)->DeleteLocalRef (env, jsrc);
    (*env)->DeleteLocalRef (env, jdst);

    if (need_detach) {
        (*g_jvm)->DetachCurrentThread (g_jvm);
    }

    return allowed ? 1 : 0;
}

JNIEXPORT jint JNICALL
Java_org_olcbox_app_vpn_service_OlcboxVpnService_startTun2socksNative(
    JNIEnv *env, jobject thiz, jstring config_path, jint tun_fd)
{
    init_jni (env);
    hev_socks5_tunnel_set_connection_filter (yptun_connection_filter);

    const char *path = (*env)->GetStringUTFChars(env, config_path, 0);
    if (path == 0) {
        hev_socks5_tunnel_set_connection_filter (NULL);
        close(tun_fd);
        return -1;
    }

    int result = hev_socks5_tunnel_main_from_file(path, tun_fd);
    (*env)->ReleaseStringUTFChars(env, config_path, path);
    hev_socks5_tunnel_set_connection_filter (NULL);
    close(tun_fd);
    return result;
}

JNIEXPORT void JNICALL
Java_org_olcbox_app_vpn_service_OlcboxVpnService_stopTun2socksNative(
    JNIEnv *env, jobject thiz)
{
    hev_socks5_tunnel_set_connection_filter (NULL);
    hev_socks5_tunnel_quit();
}

JNIEXPORT jlongArray JNICALL
Java_org_olcbox_app_vpn_service_OlcboxVpnService_getTun2socksStatsNative(
    JNIEnv *env, jobject thiz)
{
    size_t tx_packets = 0;
    size_t tx_bytes = 0;
    size_t rx_packets = 0;
    size_t rx_bytes = 0;
    jlong values[4];

    hev_socks5_tunnel_stats(&tx_packets, &tx_bytes, &rx_packets, &rx_bytes);
    values[0] = (jlong) tx_packets;
    values[1] = (jlong) tx_bytes;
    values[2] = (jlong) rx_packets;
    values[3] = (jlong) rx_bytes;

    jlongArray result = (*env)->NewLongArray(env, 4);
    if (result == 0) {
        return 0;
    }
    (*env)->SetLongArrayRegion(env, result, 0, 4, values);
    return result;
}
