#ifndef CSQTT_FFI_H
#define CSQTT_FFI_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef void (*csqtt_tun_config_cb)(const char* config);
typedef void (*csqtt_log_cb)(const char* line);

/**
 * Returns the csqtt version string (static memory, do NOT free).
 */
const char* csqtt_ffi_version(void);

/**
 * Starts the csqtt client in-process using JSON options [options_json].
 * When the server sends tunnel configuration, [on_tun_config] is invoked.
 * Diagnostic and event log lines are passed to [on_log].
 * Returns NULL on success, or an allocated error message string on failure.
 * The caller must free non-NULL error strings with [csqtt_ffi_free_string].
 */
char* csqtt_ffi_start(
    const char* options_json,
    csqtt_tun_config_cb on_tun_config,
    csqtt_log_cb on_log
);

/**
 * Gracefully shuts down the running in-process csqtt client and joins worker threads.
 */
void csqtt_ffi_stop(void);

/**
 * Returns true if an in-process csqtt client is currently running.
 */
bool csqtt_ffi_is_running(void);

/**
 * Frees a string returned by [csqtt_ffi_start].
 */
void csqtt_ffi_free_string(char* ptr);

#ifdef __cplusplus
}
#endif

#endif /* CSQTT_FFI_H */
