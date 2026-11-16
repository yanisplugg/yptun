/*
 ============================================================================
 Name        : hev-socks5-tunnel.h
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2019 - 2023 hev
 Description : Socks5 Tunnel
 ============================================================================
 */

#ifndef __HEV_SOCKS5_TUNNEL_H__
#define __HEV_SOCKS5_TUNNEL_H__

#include <stdint.h>
#include "hev-list.h"

typedef int (*HevSocks5TunnelConnectionFilter) (int protocol,
                                                const char *src_ip,
                                                uint16_t src_port,
                                                const char *dst_ip,
                                                uint16_t dst_port);

void hev_socks5_tunnel_set_connection_filter (HevSocks5TunnelConnectionFilter filter);

int hev_socks5_tunnel_init (int tun_fd);
void hev_socks5_tunnel_fini (void);

int hev_socks5_tunnel_run (void);
void hev_socks5_tunnel_stop (void);

void hev_socks5_tunnel_stats (size_t *tx_packets, size_t *tx_bytes,
                              size_t *rx_packets, size_t *rx_bytes);

void hev_socks5_tunnel_update_session (HevListNode *node);

#endif /* __HEV_SOCKS5_TUNNEL_H__ */
