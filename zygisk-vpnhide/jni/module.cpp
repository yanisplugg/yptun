// VPN-Hide Zygisk module for olcvpn (org.olcbox.app).
//
// Hides VPN network interfaces (tun*/ppp*/tap*/wg*/awg*) from apps so they can't detect the VPN by
// enumerating interfaces. We PLT-hook libc's getifaddrs() — the call Android's Java
// NetworkInterface.getNetworkInterfaces() and most native detectors go through — in every currently
// loaded library of the target process, then drop VPN entries from the returned linked list.
//
// Safety:
//  * Only ordinary app processes (uid >= 10000) are hooked; system_server and privileged uids are
//    left untouched, so a bug here can never bootloop the device (worst case: the one hooked app).
//  * The module's own package and the VPN app itself are skipped.
//  * Entries are merely UNLINKED from the list (never individually freed) so freeifaddrs() stays
//    correct regardless of how bionic allocated the nodes.

#include <android/log.h>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <sys/sysmacros.h>
#include <ifaddrs.h>
#include <net/if.h>
#include <set>
#include <string>

#include "zygisk.hpp"

#define LOG_TAG "olcvpnhide"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

using zygisk::Api;
using zygisk::AppSpecializeArgs;

// Our own app — never hide the VPN from the app that created it.
static const char *SELF_PACKAGE = "org.olcbox.app";

// Interface-name prefixes considered "VPN-looking" and hidden. rmnet*/wlan*/eth* are real and kept.
static bool is_vpn_iface(const char *name) {
    if (!name) return false;
    return strncmp(name, "tun", 3) == 0 ||
           strncmp(name, "ppp", 3) == 0 ||
           strncmp(name, "tap", 3) == 0 ||
           strncmp(name, "wg", 2) == 0 ||
           strncmp(name, "awg", 3) == 0;
}

static int (*orig_getifaddrs)(struct ifaddrs **) = nullptr;

// Replacement: call the real getifaddrs, then splice out VPN interfaces.
static int my_getifaddrs(struct ifaddrs **ifap) {
    int rc = orig_getifaddrs ? orig_getifaddrs(ifap) : -1;
    if (rc != 0 || ifap == nullptr || *ifap == nullptr) return rc;

    struct ifaddrs *head = *ifap;
    // Skip leading VPN entries.
    while (head && is_vpn_iface(head->ifa_name)) head = head->ifa_next;
    *ifap = head;
    // Unlink VPN entries from the middle/tail.
    for (struct ifaddrs *cur = head; cur && cur->ifa_next; ) {
        if (is_vpn_iface(cur->ifa_next->ifa_name)) {
            cur->ifa_next = cur->ifa_next->ifa_next; // drop it (intentionally not freed)
        } else {
            cur = cur->ifa_next;
        }
    }
    return rc;
}

class VpnHide : public zygisk::ModuleBase {
public:
    void onLoad(Api *api, JNIEnv *env) override {
        this->api = api;
        this->env = env;
    }

    void preAppSpecialize(AppSpecializeArgs *args) override {
        should_hook = false;
        // Only ordinary apps; never system/privileged processes (bootloop-safe).
        if (args->uid < 10000) return;

        if (args->nice_name) {
            const char *name = env->GetStringUTFChars(args->nice_name, nullptr);
            if (name) {
                // nice_name is the process name, which starts with the package id. Skip our own app.
                should_hook = strncmp(name, SELF_PACKAGE, strlen(SELF_PACKAGE)) != 0;
                env->ReleaseStringUTFChars(args->nice_name, name);
            } else {
                should_hook = true;
            }
        } else {
            should_hook = true;
        }
    }

    void postAppSpecialize(const AppSpecializeArgs *) override {
        if (!should_hook) return;
        install_hooks();
    }

private:
    Api *api = nullptr;
    JNIEnv *env = nullptr;
    bool should_hook = false;

    // PLT-hook getifaddrs in the libraries that actually call it. The Java path
    // (NetworkInterface.getNetworkInterfaces) goes through libjavacore.so's import of getifaddrs;
    // libnetd_client / libandroid_net are also relevant on some builds. Hooking every mapped .so
    // made pltHookCommit() fail, so we target the known callers by basename.
    void install_hooks() {
        FILE *maps = fopen("/proc/self/maps", "re");
        if (!maps) return;

        std::set<ino_t> done;
        char line[1024];
        int registered = 0;
        while (fgets(line, sizeof(line), maps)) {
            // Format: start-end perms offset dev_major:dev_minor inode path
            unsigned long start, end, off;
            unsigned int dmaj, dmin;
            ino_t inode = 0;
            char perms[8];
            char path[512];
            path[0] = '\0';
            int n = sscanf(line, "%lx-%lx %7s %lx %x:%x %lu %511[^\n]",
                           &start, &end, perms, &off, &dmaj, &dmin, &inode, path);
            if (n < 7 || inode == 0) continue;
            const char *base = strrchr(path, '/');
            base = base ? base + 1 : path;
            // Only libjavacore.so actually imports getifaddrs (the Java NetworkInterface path).
            // Registering libs that don't import it makes LSPlt's pltHookCommit() return false.
            if (strcmp(base, "libjavacore.so") != 0) continue;
            if (done.count(inode)) continue;
            done.insert(inode);

            api->pltHookRegister(makedev(dmaj, dmin), inode, "getifaddrs",
                                 (void *) my_getifaddrs, (void **) &orig_getifaddrs);
            registered++;
        }
        fclose(maps);

        if (registered == 0) return;
        api->pltHookCommit();
    }
};

REGISTER_ZYGISK_MODULE(VpnHide)
