#include <winsock2.h>
#include <windows.h>

#include "windivert.h"
#include "zapret_dpi_common.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef HANDLE (WINAPI *PFN_WinDivertOpen)(const char *filter, WINDIVERT_LAYER layer, INT16 priority, UINT64 flags);
typedef BOOL   (WINAPI *PFN_WinDivertRecv)(HANDLE handle, VOID *pPacket, UINT packetLen, UINT *pRecvLen, WINDIVERT_ADDRESS *pAddr);
typedef BOOL   (WINAPI *PFN_WinDivertSend)(HANDLE handle, const VOID *pPacket, UINT packetLen, UINT *pSendLen, const WINDIVERT_ADDRESS *pAddr);
typedef BOOL   (WINAPI *PFN_WinDivertShutdown)(HANDLE handle, WINDIVERT_SHUTDOWN how);
typedef BOOL   (WINAPI *PFN_WinDivertClose)(HANDLE handle);

static volatile LONG g_running = 1;
static HANDLE g_divert = INVALID_HANDLE_VALUE;
static PFN_WinDivertShutdown pWinDivertShutdown = NULL;
static PFN_WinDivertClose pWinDivertClose = NULL;
static PFN_WinDivertSend pWinDivertSend = NULL;

static void stop_divert_handle(void) {
    InterlockedExchange(&g_running, 0);
    HANDLE h = g_divert;
    if (h != INVALID_HANDLE_VALUE && pWinDivertShutdown != NULL) {
        pWinDivertShutdown(h, WINDIVERT_SHUTDOWN_BOTH);
    }
}

static BOOL WINAPI on_console_ctrl(DWORD ctrl_type) {
    (void)ctrl_type;
    stop_divert_handle();
    return TRUE;
}

static DWORD WINAPI stdin_watchdog_thread(LPVOID param) {
    (void)param;
    char buf[64];
    DWORD n = 0;
    HANDLE h_in = GetStdHandle(STD_INPUT_HANDLE);
    while (InterlockedCompareExchange(&g_running, 1, 1) == 1) {
        if (!ReadFile(h_in, buf, sizeof(buf), &n, NULL) || n == 0) {
            stop_divert_handle();
            break;
        }
    }
    return 0;
}

static DWORD WINAPI parent_watchdog_thread(LPVOID param) {
    DWORD ppid = (DWORD)(ULONG_PTR)param;
    HANDLE hp = OpenProcess(SYNCHRONIZE, FALSE, ppid);
    if (hp == NULL) {
        stop_divert_handle();
        return 0;
    }
    WaitForSingleObject(hp, INFINITE);
    CloseHandle(hp);
    stop_divert_handle();
    return 0;
}

static HMODULE load_windivert_next_to_exe(void) {
    char path[MAX_PATH];
    DWORD n = GetModuleFileNameA(NULL, path, MAX_PATH);
    if (n > 0 && n < MAX_PATH) {
        char *slash = strrchr(path, '\\');
        if (slash != NULL && (size_t)(slash - path + 16) < sizeof(path)) {
            strcpy(slash + 1, "WinDivert.dll");
            HMODULE mod = LoadLibraryA(path);
            if (mod != NULL) return mod;
        }
    }
    return LoadLibraryA("WinDivert.dll");
}

static void send_divert_ipv4_tcp(
    HANDLE divert,
    const WINDIVERT_ADDRESS *base_addr,
    const uint8_t *orig_ip, size_t ip_len,
    const uint8_t *orig_tcp, size_t tcp_len,
    const uint8_t *payload, size_t payload_len,
    uint8_t ttl, uint16_t ip_id_offset, uint32_t seq_offset,
    int set_psh, int corrupt_tcp_sum
) {
    uint8_t pkt[1500];
    size_t tot_len = 0;
    if (!zapret_build_ipv4_tcp_segment(
            pkt, sizeof(pkt), &tot_len,
            orig_ip, ip_len, orig_tcp, tcp_len, payload, payload_len,
            ttl, ip_id_offset, seq_offset, set_psh, corrupt_tcp_sum)) {
        return;
    }

    WINDIVERT_ADDRESS inj = *base_addr;
    inj.Impostor = 1;
    // Mark checksums finalized so NDIS hardware offload does not repair badsum fakes.
    inj.IPChecksum = 1;
    inj.TCPChecksum = 1;
    pWinDivertSend(divert, pkt, (UINT)tot_len, NULL, &inj);
}

int main(int argc, char **argv) {
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--self-test") == 0) {
            return run_zapret_dpi_self_test("zapret_root_win");
        }
    }

    uint8_t fake_ttl = 5;
    const char *decoy_sni = "ozon.ru";
    int repeats = 2;
    int badsum = 1;
    int split_sni = 1;
    DWORD parent_pid = 0;

    for (int i = 1; i + 1 < argc; i += 2) {
        if (strcmp(argv[i], "--ttl") == 0) fake_ttl = (uint8_t)atoi(argv[i + 1]);
        else if (strcmp(argv[i], "--sni") == 0) decoy_sni = argv[i + 1];
        else if (strcmp(argv[i], "--repeats") == 0) repeats = atoi(argv[i + 1]);
        else if (strcmp(argv[i], "--badsum") == 0) badsum = atoi(argv[i + 1]);
        else if (strcmp(argv[i], "--split-sni") == 0) split_sni = atoi(argv[i + 1]);
        else if (strcmp(argv[i], "--parent-pid") == 0) parent_pid = (DWORD)strtoul(argv[i + 1], NULL, 10);
    }
    if (fake_ttl < 1) fake_ttl = 1;
    if (fake_ttl > 32) fake_ttl = 32;
    if (repeats < 1) repeats = 1;
    if (repeats > 5) repeats = 5;

    HMODULE wd = load_windivert_next_to_exe();
    if (wd == NULL) {
        fprintf(stderr, "zapret_root_win: failed to load WinDivert.dll (err=%lu)\n", GetLastError());
        return 1;
    }

    PFN_WinDivertOpen pWinDivertOpen = (PFN_WinDivertOpen)(void *)GetProcAddress(wd, "WinDivertOpen");
    PFN_WinDivertRecv pWinDivertRecv = (PFN_WinDivertRecv)(void *)GetProcAddress(wd, "WinDivertRecv");
    pWinDivertSend = (PFN_WinDivertSend)(void *)GetProcAddress(wd, "WinDivertSend");
    pWinDivertShutdown = (PFN_WinDivertShutdown)(void *)GetProcAddress(wd, "WinDivertShutdown");
    pWinDivertClose = (PFN_WinDivertClose)(void *)GetProcAddress(wd, "WinDivertClose");
    if (!pWinDivertOpen || !pWinDivertRecv || !pWinDivertSend || !pWinDivertShutdown || !pWinDivertClose) {
        fprintf(stderr, "zapret_root_win: missing exports in WinDivert.dll\n");
        FreeLibrary(wd);
        return 1;
    }

    static const char *FILTER =
        "outbound and !loopback and !impostor and ip and tcp and "
        "(tcp.DstPort == 80 or tcp.DstPort == 443) and tcp.PayloadLength > 0";

    HANDLE h = pWinDivertOpen(FILTER, WINDIVERT_LAYER_NETWORK, 0, 0);
    if (h == INVALID_HANDLE_VALUE) {
        fprintf(stderr, "zapret_root_win: WinDivertOpen failed (err=%lu, run as Administrator)\n", GetLastError());
        FreeLibrary(wd);
        return 1;
    }
    g_divert = h;

    SetConsoleCtrlHandler(on_console_ctrl, TRUE);
    HANDLE h_stdin_th = CreateThread(NULL, 0, stdin_watchdog_thread, NULL, 0, NULL);
    HANDLE h_parent_th = (parent_pid != 0)
        ? CreateThread(NULL, 0, parent_watchdog_thread, (LPVOID)(ULONG_PTR)parent_pid, 0, NULL)
        : NULL;

    uint8_t fake_tls[512];
    size_t fake_tls_len = zapret_build_decoy_client_hello(decoy_sni, fake_tls, sizeof(fake_tls));
    char fake_http[256];
    int fake_http_n = snprintf(fake_http, sizeof(fake_http), "GET / HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n\r\n", decoy_sni);
    size_t fake_http_len = (fake_http_n > 0) ? (size_t)fake_http_n : 0;

    printf("zapret_root_win: active windivert fake_ttl=%u decoy=%s repeats=%d badsum=%d split_sni=%d\n",
           fake_ttl, decoy_sni, repeats, badsum, split_sni);
    fflush(stdout);

    uint8_t rx[65536];
    while (InterlockedCompareExchange(&g_running, 1, 1) == 1) {
        UINT rx_len = 0;
        WINDIVERT_ADDRESS addr;
        if (!pWinDivertRecv(h, rx, sizeof(rx), &rx_len, &addr)) {
            break;
        }

        int forward_original = 1;
        if (rx_len >= 40 && (rx[0] >> 4) == 4) {
            size_t ip_len = (size_t)(rx[0] & 0x0Fu) * 4;
            uint32_t saddr_be, daddr_be;
            memcpy(&saddr_be, rx + 12, 4);
            memcpy(&daddr_be, rx + 16, 4);
            uint32_t saddr = ntohl(saddr_be);
            uint32_t daddr = ntohl(daddr_be);
            // Skip synthetic Wintun / sing-box FakeIP subnets so we only inject on physical NIC egress.
            int is_tun_subnet = ((saddr & 0xFFFF0000u) == 0xAC130000u) || ((daddr & 0xFFFE0000u) == 0xC6120000u);
            if (!is_tun_subnet && rx[9] == IPPROTO_TCP && ip_len >= 20 && ip_len + 20 <= rx_len) {
                const uint8_t *tcp = rx + ip_len;
                size_t tcp_len = (size_t)((tcp[12] >> 4) & 0x0Fu) * 4;
                if (tcp_len >= 20 && ip_len + tcp_len <= rx_len) {
                    const uint8_t *payload = tcp + tcp_len;
                    size_t payload_len = rx_len - ip_len - tcp_len;
                    int kind = zapret_is_dpi_payload(payload, payload_len);
                    if (kind != 0) {
                        const uint8_t *fake_p = (kind == 1) ? fake_tls : (const uint8_t *)fake_http;
                        size_t fake_l = (kind == 1) ? fake_tls_len : fake_http_len;
                        if (fake_l > 0) {
                            for (int r = 0; r < repeats; r++) {
                                send_divert_ipv4_tcp(
                                    h, &addr,
                                    rx, ip_len, tcp, tcp_len,
                                    fake_p, fake_l,
                                    fake_ttl, (uint16_t)(100 + r), 0,
                                    1, badsum
                                );
                            }
                        }
                        if (split_sni && kind == 1 && payload_len < 1400) {
                            size_t cut = zapret_find_tls_midsld_offset(payload, payload_len);
                            if (cut > 1 && cut < payload_len) {
                                int orig_psh = (tcp[13] & 0x08u) ? 1 : 0;
                                send_divert_ipv4_tcp(
                                    h, &addr,
                                    rx, ip_len, tcp, tcp_len,
                                    payload + cut, payload_len - cut,
                                    rx[8], 200, (uint32_t)cut,
                                    orig_psh, 0
                                );
                                send_divert_ipv4_tcp(
                                    h, &addr,
                                    rx, ip_len, tcp, tcp_len,
                                    payload, cut,
                                    rx[8], 201, 0,
                                    0, 0
                                );
                                forward_original = 0;
                            }
                        }
                    }
                }
            }
        }

        if (forward_original) {
            pWinDivertSend(h, rx, rx_len, NULL, &addr);
        }
    }

    g_divert = INVALID_HANDLE_VALUE;
    pWinDivertClose(h);
    if (h_stdin_th) CloseHandle(h_stdin_th);
    if (h_parent_th) CloseHandle(h_parent_th);
    FreeLibrary(wd);
    return 0;
}
