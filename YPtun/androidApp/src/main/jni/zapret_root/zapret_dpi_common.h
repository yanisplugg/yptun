#ifndef ZAPRET_DPI_COMMON_H
#define ZAPRET_DPI_COMMON_H

#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#ifdef _WIN32
#include <winsock2.h>
#else
#include <arpa/inet.h>
#endif

static uint16_t zapret_checksum_acc(uint32_t sum, const uint8_t *buf, size_t len) {
    while (len > 1) {
        sum += ((uint16_t)buf[0] << 8) | buf[1];
        buf += 2;
        len -= 2;
    }
    if (len == 1) {
        sum += ((uint16_t)buf[0] << 8);
    }
    while (sum >> 16) {
        sum = (sum & 0xFFFFu) + (sum >> 16);
    }
    return (uint16_t)(~sum & 0xFFFFu);
}

static uint16_t zapret_ip4_checksum(const uint8_t *iph_bytes, size_t ip_len) {
    return htons(zapret_checksum_acc(0, iph_bytes, ip_len));
}

static uint16_t zapret_tcp4_checksum(
    uint32_t saddr_be,
    uint32_t daddr_be,
    const uint8_t *tcp_seg,
    size_t tcp_total_len
) {
    uint32_t s = ntohl(saddr_be);
    uint32_t d = ntohl(daddr_be);
    uint32_t sum = 0;
    sum += (s >> 16) & 0xFFFFu;
    sum += s & 0xFFFFu;
    sum += (d >> 16) & 0xFFFFu;
    sum += d & 0xFFFFu;
    sum += (uint32_t)IPPROTO_TCP;
    sum += (uint32_t)tcp_total_len;
    return htons(zapret_checksum_acc(sum, tcp_seg, tcp_total_len));
}

/**
 * Rebuilds a raw IPv4+TCP segment from captured IP/TCP headers and a replacement payload.
 * Writes total length into [out_len]; returns 1 on success, 0 if segment exceeds buffer.
 */
static int zapret_build_ipv4_tcp_segment(
    uint8_t *pkt,
    size_t pkt_cap,
    size_t *out_len,
    const uint8_t *orig_ip,
    size_t ip_len,
    const uint8_t *orig_tcp,
    size_t tcp_len,
    const uint8_t *payload,
    size_t payload_len,
    uint8_t ttl,
    uint16_t ip_id_offset,
    uint32_t seq_offset,
    int set_psh,
    int corrupt_tcp_sum
) {
    size_t tot_len = ip_len + tcp_len + payload_len;
    if (ip_len < 20 || tcp_len < 20 || tot_len > pkt_cap) return 0;

    memcpy(pkt, orig_ip, ip_len);
    memcpy(pkt + ip_len, orig_tcp, tcp_len);
    if (payload_len > 0 && payload != NULL) {
        memcpy(pkt + ip_len + tcp_len, payload, payload_len);
    }

    // Standard IPv4 header byte offsets (avoids Linux-only vs Windows struct differences):
    // tot_len@2..3, id@4..5, ttl@8, check@10..11, saddr@12..15, daddr@16..19
    uint16_t orig_id;
    memcpy(&orig_id, pkt + 4, 2);
    uint16_t new_id = htons((uint16_t)(ntohs(orig_id) + ip_id_offset));
    memcpy(pkt + 4, &new_id, 2);
    pkt[8] = ttl;
    uint16_t tot_be = htons((uint16_t)tot_len);
    memcpy(pkt + 2, &tot_be, 2);
    pkt[10] = 0;
    pkt[11] = 0;
    uint16_t ip_sum = zapret_ip4_checksum(pkt, ip_len);
    memcpy(pkt + 10, &ip_sum, 2);

    // TCP header byte offsets inside pkt+ip_len:
    // seq@4..7, flags@13 (PSH=0x08), check@16..17
    uint8_t *tcp = pkt + ip_len;
    uint32_t orig_seq;
    memcpy(&orig_seq, tcp + 4, 4);
    uint32_t new_seq = htonl(ntohl(orig_seq) + seq_offset);
    memcpy(tcp + 4, &new_seq, 4);
    if (set_psh > 0) tcp[13] |= 0x08u;
    else if (set_psh == 0) tcp[13] &= (uint8_t)(~0x08u);
    tcp[16] = 0;
    tcp[17] = 0;

    uint32_t saddr_be, daddr_be;
    memcpy(&saddr_be, pkt + 12, 4);
    memcpy(&daddr_be, pkt + 16, 4);
    uint16_t sum = zapret_tcp4_checksum(saddr_be, daddr_be, tcp, tcp_len + payload_len);
    uint16_t final_sum = corrupt_tcp_sum ? (uint16_t)(sum ^ 0xFFFFu) : sum;
    memcpy(tcp + 16, &final_sum, 2);

    *out_len = tot_len;
    return 1;
}

static size_t zapret_build_decoy_client_hello(const char *sni, uint8_t *out, size_t max_out) {
    size_t sni_len = (sni != NULL) ? strlen(sni) : 0;
    if (sni_len == 0 || sni_len > 120) {
        sni = "ozon.ru";
        sni_len = strlen(sni);
    }
    uint8_t ext[256];
    size_t ep = 0;
    uint16_t entry_len = (uint16_t)(1 + 2 + sni_len);
    uint16_t sni_ext_len = (uint16_t)(2 + entry_len);
    ext[ep++] = 0x00; ext[ep++] = 0x00;
    ext[ep++] = (uint8_t)(sni_ext_len >> 8); ext[ep++] = (uint8_t)(sni_ext_len & 0xFF);
    ext[ep++] = (uint8_t)(entry_len >> 8); ext[ep++] = (uint8_t)(entry_len & 0xFF);
    ext[ep++] = 0x00;
    ext[ep++] = (uint8_t)(sni_len >> 8); ext[ep++] = (uint8_t)(sni_len & 0xFF);
    memcpy(ext + ep, sni, sni_len);
    ep += sni_len;

    static const uint8_t tail_ext[] = {
        0x00, 0x0a, 0x00, 0x06, 0x00, 0x04, 0x00, 0x1d, 0x00, 0x17,
        0x00, 0x0b, 0x00, 0x02, 0x01, 0x00
    };
    memcpy(ext + ep, tail_ext, sizeof(tail_ext));
    ep += sizeof(tail_ext);

    size_t body_len = 2 + 32 + 1 + 2 + 4 + 2 + 2 + ep;
    size_t hs_len = 4 + body_len;
    size_t total_len = 5 + hs_len;
    if (total_len > max_out) return 0;

    size_t p = 0;
    out[p++] = 0x16; out[p++] = 0x03; out[p++] = 0x01;
    out[p++] = (uint8_t)(hs_len >> 8); out[p++] = (uint8_t)(hs_len & 0xFF);
    out[p++] = 0x01;
    out[p++] = 0x00;
    out[p++] = (uint8_t)(body_len >> 8); out[p++] = (uint8_t)(body_len & 0xFF);
    out[p++] = 0x03; out[p++] = 0x03;
    for (int i = 0; i < 32; i++) out[p++] = (uint8_t)((i * 37 + 19) & 0xFF);
    out[p++] = 0x00;
    out[p++] = 0x00; out[p++] = 0x04;
    out[p++] = 0xc0; out[p++] = 0x2b;
    out[p++] = 0xc0; out[p++] = 0x2f;
    out[p++] = 0x01; out[p++] = 0x00;
    out[p++] = (uint8_t)(ep >> 8); out[p++] = (uint8_t)(ep & 0xFF);
    memcpy(out + p, ext, ep);
    p += ep;
    return p;
}

static size_t zapret_find_tls_midsld_offset(const uint8_t *payload, size_t len) {
    if (len < 44) return 0;
    if (payload[0] != 0x16 || payload[1] != 0x03 || payload[5] != 0x01) return 0;
    size_t rec_len = ((size_t)payload[3] << 8) | payload[4];
    size_t end = 5 + rec_len;
    if (end > len) end = len;
    size_t p = 5 + 4 + 2 + 32;
    if (p >= end) return 0;
    size_t sess_len = payload[p];
    p += 1 + sess_len;
    if (p + 2 > end) return 0;
    size_t cipher_len = ((size_t)payload[p] << 8) | payload[p + 1];
    p += 2 + cipher_len;
    if (p >= end) return 0;
    size_t comp_len = payload[p];
    p += 1 + comp_len;
    if (p + 2 > end) return 0;
    size_t ext_len = ((size_t)payload[p] << 8) | payload[p + 1];
    p += 2;
    size_t ext_end = p + ext_len;
    if (ext_end > end) ext_end = end;
    while (p + 4 <= ext_end) {
        uint16_t etype = ((uint16_t)payload[p] << 8) | payload[p + 1];
        uint16_t elen = ((uint16_t)payload[p + 2] << 8) | payload[p + 3];
        p += 4;
        if (p + elen > ext_end) break;
        if (etype == 0x0000 && elen >= 5) {
            size_t list_len = ((size_t)payload[p] << 8) | payload[p + 1];
            size_t q = p + 2;
            size_t list_end = q + list_len;
            if (list_end > p + elen) list_end = p + elen;
            while (q + 3 <= list_end) {
                uint8_t ntype = payload[q];
                size_t nlen = ((size_t)payload[q + 1] << 8) | payload[q + 2];
                q += 3;
                if (q + nlen > list_end) break;
                if (ntype == 0 && nlen >= 4) {
                    size_t last_dot = nlen;
                    size_t prev_dot = nlen;
                    for (size_t i = nlen; i > 0; i--) {
                        if (payload[q + i - 1] == '.') {
                            if (last_dot == nlen) {
                                last_dot = i - 1;
                            } else {
                                prev_dot = i - 1;
                                break;
                            }
                        }
                    }
                    if (last_dot < nlen) {
                        size_t sld_start = (prev_dot < last_dot) ? (prev_dot + 1) : 0;
                        size_t sld_len = last_dot - sld_start;
                        if (sld_len >= 2) {
                            return q + sld_start + (sld_len / 2);
                        }
                    }
                    return q + (nlen / 2);
                }
                q += nlen;
            }
        }
        p += elen;
    }
    return 0;
}

static int zapret_is_dpi_payload(const uint8_t *payload, size_t payload_len) {
    if (payload_len >= 10 && payload[0] == 0x16 && payload[1] == 0x03 && payload[5] == 0x01) {
        return 1; // TLS ClientHello
    }
    if (payload_len >= 12 && (
        memcmp(payload, "GET ", 4) == 0 ||
        memcmp(payload, "POST ", 5) == 0 ||
        memcmp(payload, "HEAD ", 5) == 0
    )) {
        return 2; // Plain HTTP request
    }
    return 0;
}

static int run_zapret_dpi_self_test(const char *tag) {
    uint8_t ch[512];
    size_t ch_len = zapret_build_decoy_client_hello("rr4---sn-jvhnu5g.googlevideo.com", ch, sizeof(ch));
    if (ch_len == 0 || zapret_is_dpi_payload(ch, ch_len) != 1) {
        fprintf(stderr, "%s self-test failed: invalid ClientHello (%zu)\n", tag, ch_len);
        return 1;
    }
    size_t cut = zapret_find_tls_midsld_offset(ch, ch_len);
    if (cut == 0 || cut >= ch_len) {
        fprintf(stderr, "%s self-test failed: midsld offset=%zu\n", tag, cut);
        return 1;
    }
    const char *tail = (const char *)(ch + cut);
    if (strncmp(tail, "evideo.com", 10) != 0) {
        fprintf(stderr, "%s self-test failed: expected 'evideo.com' at cut, got '%.12s'\n", tag, tail);
        return 1;
    }

    uint8_t fake_ip[20] = {
        0x45, 0x00, 0x00, 0x28, 0x12, 0x34, 0x40, 0x00,
        0x40, 0x06, 0x00, 0x00, 0x0a, 0x00, 0x00, 0x01,
        0x01, 0x01, 0x01, 0x01
    };
    uint8_t fake_tcp[20] = {
        0xd4, 0x31, 0x01, 0xbb, 0x00, 0x00, 0x03, 0xe8,
        0x00, 0x00, 0x00, 0x01, 0x50, 0x18, 0xff, 0xff,
        0x00, 0x00, 0x00, 0x00
    };
    uint8_t out_good[1500], out_bad[1500];
    size_t out_good_len = 0, out_bad_len = 0;
    if (!zapret_build_ipv4_tcp_segment(out_good, sizeof(out_good), &out_good_len,
            fake_ip, sizeof(fake_ip), fake_tcp, sizeof(fake_tcp), ch, 16, 5, 100, 0, 1, 0)) {
        fprintf(stderr, "%s self-test failed: build good segment\n", tag);
        return 1;
    }
    if (!zapret_build_ipv4_tcp_segment(out_bad, sizeof(out_bad), &out_bad_len,
            fake_ip, sizeof(fake_ip), fake_tcp, sizeof(fake_tcp), ch, 16, 5, 100, 0, 1, 1)) {
        fprintf(stderr, "%s self-test failed: build badsum segment\n", tag);
        return 1;
    }
    if (out_good_len != 56 || out_bad_len != 56 || out_good[8] != 5) {
        fprintf(stderr, "%s self-test failed: len=%zu ttl=%u\n", tag, out_good_len, out_good[8]);
        return 1;
    }
    uint16_t good_ip_sum = zapret_ip4_checksum(out_good, 20);
    uint16_t good_tcp_sum, bad_tcp_sum;
    memcpy(&good_tcp_sum, out_good + 36, 2);
    memcpy(&bad_tcp_sum, out_bad + 36, 2);
    if (good_ip_sum != 0 || (uint16_t)(good_tcp_sum ^ bad_tcp_sum) != 0xFFFFu) {
        fprintf(stderr, "%s self-test failed: bad checksum verification\n", tag);
        return 1;
    }

    printf("%s self-test OK (ch_len=%zu midsld=%zu)\n", tag, ch_len, cut);
    return 0;
}

#endif
