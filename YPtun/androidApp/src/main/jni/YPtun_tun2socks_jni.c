#include <jni.h>
#include <stdint.h>
#include <unistd.h>

#include "hev-main.h"

JNIEXPORT jint JNICALL
Java_org_olcbox_app_vpn_service_OlcboxVpnService_startTun2socksNative(
    JNIEnv *env, jobject thiz, jstring config_path, jint tun_fd)
{
    const char *path = (*env)->GetStringUTFChars(env, config_path, 0);
    if (path == 0) {
        close(tun_fd);
        return -1;
    }

    int result = hev_socks5_tunnel_main_from_file(path, tun_fd);
    (*env)->ReleaseStringUTFChars(env, config_path, path);
    close(tun_fd);
    return result;
}

JNIEXPORT void JNICALL
Java_org_olcbox_app_vpn_service_OlcboxVpnService_stopTun2socksNative(
    JNIEnv *env, jobject thiz)
{
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
