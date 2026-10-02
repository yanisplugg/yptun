#ifndef SNOLC_FFI_H
#define SNOLC_FFI_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Returns the snolc version string (static memory, do NOT free).
 */
const char* snolc_ffi_version(void);

/**
 * Starts the snolc engine in-process using the configuration at [toml_path].
 * Returns NULL on success, or an allocated error message string on failure.
 * The caller must free non-NULL error strings with [snolc_ffi_free_string].
 */
char* snolc_ffi_start(const char* toml_path);

/**
 * Gracefully shuts down the running in-process snolc engine and joins worker threads.
 */
void snolc_ffi_stop(void);

/**
 * Returns true if an in-process snolc engine is currently running.
 */
bool snolc_ffi_is_running(void);

/**
 * Frees a string returned by [snolc_ffi_start].
 */
void snolc_ffi_free_string(char* ptr);

#ifdef __cplusplus
}
#endif

#endif /* SNOLC_FFI_H */
