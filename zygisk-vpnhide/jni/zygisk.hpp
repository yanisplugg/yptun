/* Copyright 2022-2023 John "topjohnwu" Wu — Zygisk C++ API (version 4).
 * Faithful reproduction of the official header (ABI-compatible with Magisk / Zygisk Next / ReZygisk).
 */
#pragma once

#include <jni.h>
#include <sys/types.h>

#define ZYGISK_API_VERSION 4

namespace zygisk {

struct Api;
struct AppSpecializeArgs;
struct ServerSpecializeArgs;

class ModuleBase {
public:
    virtual void onLoad([[maybe_unused]] Api *api, [[maybe_unused]] JNIEnv *env) {}
    virtual void preAppSpecialize([[maybe_unused]] AppSpecializeArgs *args) {}
    virtual void postAppSpecialize([[maybe_unused]] const AppSpecializeArgs *args) {}
    virtual void preServerSpecialize([[maybe_unused]] ServerSpecializeArgs *args) {}
    virtual void postServerSpecialize([[maybe_unused]] const ServerSpecializeArgs *args) {}
};

struct AppSpecializeArgs {
    jint &uid;
    jint &gid;
    jintArray &gids;
    jint &runtime_flags;
    jobjectArray &rlimits;
    jint &mount_external;
    jstring &se_info;
    jstring &nice_name;
    jstring &instruction_set;
    jstring &app_data_dir;

    jintArray *&fds_to_ignore;
    jboolean *&is_child_zygote;
    jboolean *&is_top_app;
    jobjectArray *&pkg_data_info_list;
    jobjectArray *&allowlisted_data_info_list;
    jboolean *&mount_data_dirs;
    jboolean *&mount_storage_dirs;

    AppSpecializeArgs() = delete;
};

struct ServerSpecializeArgs {
    jint &uid;
    jint &gid;
    jintArray &gids;
    jint &runtime_flags;
    jlong &permitted_capabilities;
    jlong &effective_capabilities;

    ServerSpecializeArgs() = delete;
};

namespace internal {
struct api_table;
template <class T>
void entry_impl(api_table *, JNIEnv *);
}

enum Option : int {
    FORCE_DENYLIST_UNMOUNT = 0,
    DLCLOSE_MODULE_LIBRARY = 1,
};

enum StateFlag : uint32_t {
    PROCESS_GRANTED_ROOT = (1u << 0),
    PROCESS_ON_DENYLIST = (1u << 1),
};

struct Api {
    int connectCompanion();
    int getModuleDir();
    void setOption(Option opt);
    uint32_t getFlags();
    bool exemptFd(int fd);
    void hookJniNativeMethods(JNIEnv *env, const char *className, JNINativeMethod *methods, int numMethods);
    void pltHookRegister(dev_t dev, ino_t inode, const char *symbol, void *newFunc, void **oldFunc);
    bool pltHookCommit();

private:
    internal::api_table *tbl;
    template <class T>
    friend void internal::entry_impl(internal::api_table *, JNIEnv *);
};

namespace internal {

struct module_abi {
    long api_version;
    ModuleBase *impl;

    void (*preAppSpecialize)(ModuleBase *, AppSpecializeArgs *);
    void (*postAppSpecialize)(ModuleBase *, const AppSpecializeArgs *);
    void (*preServerSpecialize)(ModuleBase *, ServerSpecializeArgs *);
    void (*postServerSpecialize)(ModuleBase *, const ServerSpecializeArgs *);

    module_abi(ModuleBase *module) : api_version(ZYGISK_API_VERSION), impl(module) {
        preAppSpecialize = [](ModuleBase *m, AppSpecializeArgs *args) { m->preAppSpecialize(args); };
        postAppSpecialize = [](ModuleBase *m, const AppSpecializeArgs *args) { m->postAppSpecialize(args); };
        preServerSpecialize = [](ModuleBase *m, ServerSpecializeArgs *args) { m->preServerSpecialize(args); };
        postServerSpecialize = [](ModuleBase *m, const ServerSpecializeArgs *args) { m->postServerSpecialize(args); };
    }
};

struct api_table {
    // These first 2 entries are permanent, shall never change
    void *impl;
    bool (*registerModule)(api_table *, module_abi *);

    void (*hookJniNativeMethods)(JNIEnv *, const char *, JNINativeMethod *, int);
    void (*pltHookRegister)(dev_t, ino_t, const char *, void *, void **);
    bool (*exemptFd)(int);
    bool (*pltHookCommit)();
    int (*connectCompanion)(void * /* impl */);
    void (*setOption)(void * /* impl */, Option);
    int (*getModuleDir)(void * /* impl */);
    uint32_t (*getFlags)(void * /* impl */);
};

template <class T>
void entry_impl(api_table *table, JNIEnv *env) {
    ModuleBase *module = new T();
    if (!table->registerModule(table, new module_abi(module))) return;
    auto api = new Api();
    api->tbl = table;
    module->onLoad(api, env);
}

}

inline int Api::connectCompanion() {
    return tbl->connectCompanion ? tbl->connectCompanion(tbl->impl) : -1;
}
inline int Api::getModuleDir() {
    return tbl->getModuleDir ? tbl->getModuleDir(tbl->impl) : -1;
}
inline void Api::setOption(Option opt) {
    if (tbl->setOption) tbl->setOption(tbl->impl, opt);
}
inline uint32_t Api::getFlags() {
    return tbl->getFlags ? tbl->getFlags(tbl->impl) : 0;
}
inline bool Api::exemptFd(int fd) {
    return tbl->exemptFd && tbl->exemptFd(fd);
}
inline void Api::hookJniNativeMethods(JNIEnv *env, const char *className, JNINativeMethod *methods, int numMethods) {
    if (tbl->hookJniNativeMethods) tbl->hookJniNativeMethods(env, className, methods, numMethods);
}
inline void Api::pltHookRegister(dev_t dev, ino_t inode, const char *symbol, void *newFunc, void **oldFunc) {
    if (tbl->pltHookRegister) tbl->pltHookRegister(dev, inode, symbol, newFunc, oldFunc);
}
inline bool Api::pltHookCommit() {
    return tbl->pltHookCommit && tbl->pltHookCommit();
}

}

extern "C" [[gnu::visibility("default")]] [[maybe_unused]]
void zygisk_module_entry(zygisk::internal::api_table *, JNIEnv *);

#define REGISTER_ZYGISK_MODULE(clazz) \
extern "C" [[gnu::visibility("default")]] [[maybe_unused]] \
void zygisk_module_entry(zygisk::internal::api_table *table, JNIEnv *env) { \
    zygisk::internal::entry_impl<clazz>(table, env); \
}

#define REGISTER_ZYGISK_COMPANION(func) \
extern "C" [[gnu::visibility("default")]] [[maybe_unused]] \
void zygisk_companion_entry(int client) { func(client); }
