#include <arpa/inet.h>
#include <errno.h>
#include <linux/ip.h>
#include <linux/netfilter.h>
#include <linux/netfilter/nfnetlink.h>
#include <linux/netfilter/nfnetlink_queue.h>
#include <linux/netlink.h>
#include <linux/tcp.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#ifndef SOL_NETLINK
#define SOL_NETLINK 270
#endif
#ifndef NETLINK_NO_ENOBUFS
#define NETLINK_NO_ENOBUFS 5
#endif

#define ZAPRET_BYPASS_MARK 0x20000u

static volatile sig_atomic_t g_running = 1;
static void on_sig(int sig) {
    (void)sig;
    g_running = 0;
}

static uint16_t checksum_acc(uint32_t sum, const uint8_t *buf, size_t len) {
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

static uint16_t ip4_checksum(const uint8_t *iph_bytes, size_t ip_len) {
    return htons(checksum_acc(0, iph_bytes, ip_len));
}

static uint16_t tcp4_checksum(uint32_t saddr_be, uint32_t daddr_be, const uint8_t *tcp_seg, size_t tcp_total_len) {
    uint32_t s = ntohl(saddr_be);
    uint32_t d = ntohl(daddr_be);
    uint32_t sum = 0;
    sum += (s >> 16) & 0xFFFFu;
    sum += s & 0xFFFFu;
    sum += (d >> 16) & 0xFFFFu;
    sum += d & 0xFFFFu;
    sum += (uint32_t)IPPROTO_TCP;
    sum += (uint32_t)tcp_total_len;
    return htons(checksum_acc(sum, tcp_seg, tcp_total_len));
}

static size_t build_decoy_client_hello(const char *sni, uint8_t *out, size_t max_out) {
    size_t sni_len = strlen(sni);
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
    memcpy(ext + ep, sni, sni_len); ep += sni_len;
    // ec_point_formats + supported_groups
    static const uint8_t tail_ext[] = {
        0x00, 0x0a, 0x00, 0x06, 0x00, 0x04, 0x00, 0x1d, 0x00, 0x17,
        0x00, 0x0b, 0x00, 0x02, 0x01, 0x00
    };
    memcpy(ext + ep, tail_ext, sizeof(tail_ext)); ep += sizeof(tail_ext);

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
    out[p++] = 0x00; // session id
    out[p++] = 0x00; out[p++] = 0x04; // 2 cipher suites
    out[p++] = 0xc0; out[p++] = 0x2b;
    out[p++] = 0xc0; out[p++] = 0x2f;
    out[p++] = 0x01; out[p++] = 0x00; // compression
    out[p++] = (uint8_t)(ep >> 8); out[p++] = (uint8_t)(ep & 0xFF);
    memcpy(out + p, ext, ep);
    p += ep;
    return p;
}

static size_t find_tls_midsld_offset(const uint8_t *payload, size_t len) {
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
                    // Locate registered SLD middle (e.g. inside "googlevideo" for rr4---sn-xxx.googlevideo.com).
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

static void send_raw_ipv4_tcp(
    int raw_fd,
    const uint8_t *orig_ip, size_t ip_len,
    const uint8_t *orig_tcp, size_t tcp_len,
    const uint8_t *payload, size_t payload_len,
    uint8_t ttl, uint16_t ip_id_offset, uint32_t seq_offset,
    int set_psh, int corrupt_tcp_sum
) {
    size_t tot_len = ip_len + tcp_len + payload_len;
    if (tot_len > 1500) return;
    uint8_t pkt[1500];
    memcpy(pkt, orig_ip, ip_len);
    memcpy(pkt + ip_len, orig_tcp, tcp_len);
    if (payload_len > 0) memcpy(pkt + ip_len + tcp_len, payload, payload_len);

    struct iphdr *iph = (struct iphdr *)pkt;
    struct tcphdr *tcph = (struct tcphdr *)(pkt + ip_len);

    iph->ttl = ttl;
    iph->id = htons((uint16_t)(ntohs(iph->id) + ip_id_offset));
    iph->tot_len = htons((uint16_t)tot_len);
    iph->check = 0;
    iph->check = ip4_checksum(pkt, ip_len);

    tcph->seq = htonl(ntohl(tcph->seq) + seq_offset);
    if (set_psh >= 0) tcph->psh = (uint16_t)(set_psh ? 1 : 0);
    tcph->check = 0;
    uint16_t sum = tcp4_checksum(iph->saddr, iph->daddr, pkt + ip_len, tcp_len + payload_len);
    tcph->check = corrupt_tcp_sum ? (uint16_t)(sum ^ 0xFFFFu) : sum;

    struct sockaddr_in dst;
    memset(&dst, 0, sizeof(dst));
    dst.sin_family = AF_INET;
    dst.sin_addr.s_addr = iph->daddr;
    dst.sin_port = 0;
    (void)sendto(raw_fd, pkt, tot_len, 0, (struct sockaddr *)&dst, sizeof(dst));
}

static size_t nfq_put_attr(uint8_t *buf, size_t off, uint16_t type, const void *data, size_t len) {
    struct nlattr *nla = (struct nlattr *)(buf + off);
    nla->nla_type = type;
    nla->nla_len = (uint16_t)NLA_HDRLEN + (uint16_t)len;
    if (len > 0 && data != NULL) memcpy(buf + off + NLA_HDRLEN, data, len);
    return off + NLMSG_ALIGN(nla->nla_len);
}

static int nfq_send_cmd(int nfq_fd, uint16_t res_id, uint8_t cmd, uint16_t pf, uint8_t copy_mode, uint32_t copy_range) {
    uint8_t buf[256];
    memset(buf, 0, sizeof(buf));
    struct nlmsghdr *nlh = (struct nlmsghdr *)buf;
    nlh->nlmsg_type = (NFNL_SUBSYS_QUEUE << 8) | NFQNL_MSG_CONFIG;
    nlh->nlmsg_flags = NLM_F_REQUEST | NLM_F_ACK;
    nlh->nlmsg_seq = 1;

    struct nfgenmsg *nfg = (struct nfgenmsg *)(buf + NLMSG_HDRLEN);
    nfg->nfgen_family = (uint8_t)pf;
    nfg->version = NFNETLINK_V0;
    nfg->res_id = htons(res_id);

    size_t off = NLMSG_HDRLEN + NLMSG_ALIGN(sizeof(*nfg));
    if (cmd > 0) {
        struct nfqnl_msg_config_cmd cfg_cmd;
        cfg_cmd.command = cmd;
        cfg_cmd._pad = 0;
        cfg_cmd.pf = htons(pf);
        off = nfq_put_attr(buf, off, NFQA_CFG_CMD, &cfg_cmd, sizeof(cfg_cmd));
    }
    if (copy_mode != 0xFF) {
        struct nfqnl_msg_config_params params;
        params.copy_range = htonl(copy_range);
        params.copy_mode = copy_mode;
        off = nfq_put_attr(buf, off, NFQA_CFG_PARAMS, &params, sizeof(params));
    }
    nlh->nlmsg_len = (uint32_t)off;

    struct sockaddr_nl peer;
    memset(&peer, 0, sizeof(peer));
    peer.nl_family = AF_NETLINK;
    if (sendto(nfq_fd, buf, off, 0, (struct sockaddr *)&peer, sizeof(peer)) < 0) return -1;

    uint8_t ack[512];
    ssize_t r = recv(nfq_fd, ack, sizeof(ack), 0);
    if (r < (ssize_t)sizeof(struct nlmsghdr)) return -1;
    struct nlmsghdr *anh = (struct nlmsghdr *)ack;
    if (anh->nlmsg_type == NLMSG_ERROR) {
        struct nlmsgerr *err = (struct nlmsgerr *)NLMSG_DATA(anh);
        if (err->error != 0) {
            errno = -err->error;
            return -1;
        }
    }
    return 0;
}

static void nfq_send_verdict(int nfq_fd, uint16_t queue_num, uint32_t pkt_id_be, uint32_t verdict) {
    uint8_t buf[128];
    memset(buf, 0, sizeof(buf));
    struct nlmsghdr *nlh = (struct nlmsghdr *)buf;
    nlh->nlmsg_type = (NFNL_SUBSYS_QUEUE << 8) | NFQNL_MSG_VERDICT;
    nlh->nlmsg_flags = NLM_F_REQUEST;

    struct nfgenmsg *nfg = (struct nfgenmsg *)(buf + NLMSG_HDRLEN);
    nfg->nfgen_family = AF_UNSPEC;
    nfg->version = NFNETLINK_V0;
    nfg->res_id = htons(queue_num);

    struct nfqnl_msg_verdict_hdr vh;
    vh.verdict = htonl(verdict);
    vh.id = pkt_id_be;

    size_t off = NLMSG_HDRLEN + NLMSG_ALIGN(sizeof(*nfg));
    off = nfq_put_attr(buf, off, NFQA_VERDICT_HDR, &vh, sizeof(vh));
    nlh->nlmsg_len = (uint32_t)off;

    struct sockaddr_nl peer;
    memset(&peer, 0, sizeof(peer));
    peer.nl_family = AF_NETLINK;
    (void)sendto(nfq_fd, buf, off, 0, (struct sockaddr *)&peer, sizeof(peer));
}

int main(int argc, char **argv) {
    uint16_t queue_num = 240;
    uint8_t fake_ttl = 5;
    const char *decoy_sni = "ozon.ru";
    int repeats = 2;
    int badsum = 1;
    int split_sni = 1;

    for (int i = 1; i + 1 < argc; i += 2) {
        if (strcmp(argv[i], "--queue") == 0) queue_num = (uint16_t)atoi(argv[i + 1]);
        else if (strcmp(argv[i], "--ttl") == 0) fake_ttl = (uint8_t)atoi(argv[i + 1]);
        else if (strcmp(argv[i], "--sni") == 0) decoy_sni = argv[i + 1];
        else if (strcmp(argv[i], "--repeats") == 0) repeats = atoi(argv[i + 1]);
        else if (strcmp(argv[i], "--badsum") == 0) badsum = atoi(argv[i + 1]);
        else if (strcmp(argv[i], "--split-sni") == 0) split_sni = atoi(argv[i + 1]);
    }
    if (fake_ttl < 1) fake_ttl = 1;
    if (fake_ttl > 32) fake_ttl = 32;
    if (repeats < 1) repeats = 1;
    if (repeats > 5) repeats = 5;

    signal(SIGINT, on_sig);
    signal(SIGTERM, on_sig);

    int nfq_fd = socket(AF_NETLINK, SOCK_RAW, NETLINK_NETFILTER);
    if (nfq_fd < 0) {
        fprintf(stderr, "zapret_root: netlink open failed: %s\n", strerror(errno));
        return 1;
    }
    int rcvbuf = 1024 * 1024;
    int one = 1;
    setsockopt(nfq_fd, SOL_SOCKET, SO_RCVBUF, &rcvbuf, sizeof(rcvbuf));
    setsockopt(nfq_fd, SOL_NETLINK, NETLINK_NO_ENOBUFS, &one, sizeof(one));

    struct sockaddr_nl local;
    memset(&local, 0, sizeof(local));
    local.nl_family = AF_NETLINK;
    if (bind(nfq_fd, (struct sockaddr *)&local, sizeof(local)) < 0) {
        fprintf(stderr, "zapret_root: netlink bind failed: %s\n", strerror(errno));
        close(nfq_fd);
        return 1;
    }

    (void)nfq_send_cmd(nfq_fd, 0, NFQNL_CFG_CMD_PF_UNBIND, AF_INET, 0xFF, 0);
    if (nfq_send_cmd(nfq_fd, 0, NFQNL_CFG_CMD_PF_BIND, AF_INET, 0xFF, 0) < 0 ||
        nfq_send_cmd(nfq_fd, queue_num, NFQNL_CFG_CMD_BIND, AF_UNSPEC, 0xFF, 0) < 0 ||
        nfq_send_cmd(nfq_fd, queue_num, 0, AF_UNSPEC, NFQNL_COPY_PACKET, 0xFFFFu) < 0) {
        fprintf(stderr, "zapret_root: nfq config failed: %s\n", strerror(errno));
        close(nfq_fd);
        return 1;
    }

    int raw_fd = socket(AF_INET, SOCK_RAW, IPPROTO_RAW);
    if (raw_fd < 0) {
        fprintf(stderr, "zapret_root: SOCK_RAW failed: %s\n", strerror(errno));
        close(nfq_fd);
        return 1;
    }
    uint32_t mark = ZAPRET_BYPASS_MARK;
    setsockopt(raw_fd, IPPROTO_IP, IP_HDRINCL, &one, sizeof(one));
    setsockopt(raw_fd, SOL_SOCKET, SO_MARK, &mark, sizeof(mark));

    uint8_t fake_tls[512];
    size_t fake_tls_len = build_decoy_client_hello(decoy_sni, fake_tls, sizeof(fake_tls));
    char fake_http[256];
    int fake_http_n = snprintf(fake_http, sizeof(fake_http), "GET / HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n\r\n", decoy_sni);
    size_t fake_http_len = (fake_http_n > 0) ? (size_t)fake_http_n : 0;

    printf("zapret_root: active nfq=%u fake_ttl=%u decoy=%s repeats=%d badsum=%d split_sni=%d\n",
           queue_num, fake_ttl, decoy_sni, repeats, badsum, split_sni);
    fflush(stdout);

    uint8_t rx[65536];
    while (g_running) {
        ssize_t n = recv(nfq_fd, rx, sizeof(rx), 0);
        if (n < 0) {
            if (errno == EINTR) continue;
            if (errno == ENOBUFS) continue;
            break;
        }
        struct nlmsghdr *nlh = (struct nlmsghdr *)rx;
        for (; NLMSG_OK(nlh, (unsigned int)n); nlh = NLMSG_NEXT(nlh, n)) {
            if (nlh->nlmsg_type == NLMSG_ERROR || nlh->nlmsg_type == NLMSG_DONE) continue;
            struct nfgenmsg *nfg = (struct nfgenmsg *)NLMSG_DATA(nlh);
            if ((size_t)nlh->nlmsg_len < NLMSG_HDRLEN + NLMSG_ALIGN(sizeof(*nfg))) continue;

            uint32_t pkt_id_be = 0;
            int has_id = 0;
            const uint8_t *pkt = NULL;
            size_t pkt_len = 0;

            size_t attr_off = NLMSG_HDRLEN + NLMSG_ALIGN(sizeof(*nfg));
            size_t msg_end = nlh->nlmsg_len;
            while (attr_off + NLA_HDRLEN <= msg_end) {
                const struct nlattr *attr = (const struct nlattr *)((const uint8_t *)nlh + attr_off);
                if (attr->nla_len < NLA_HDRLEN || attr_off + attr->nla_len > msg_end) break;
                const uint8_t *adata = (const uint8_t *)attr + NLA_HDRLEN;
                size_t alen = attr->nla_len - NLA_HDRLEN;
                if (attr->nla_type == NFQA_PACKET_HDR && alen >= sizeof(struct nfqnl_msg_packet_hdr)) {
                    const struct nfqnl_msg_packet_hdr *ph = (const struct nfqnl_msg_packet_hdr *)adata;
                    pkt_id_be = ph->packet_id;
                    has_id = 1;
                } else if (attr->nla_type == NFQA_PAYLOAD) {
                    pkt = adata;
                    pkt_len = alen;
                }
                attr_off += NLMSG_ALIGN(attr->nla_len);
            }
            if (!has_id) continue;

            uint32_t verdict = NF_ACCEPT;
            if (pkt != NULL && pkt_len >= 40 && (pkt[0] >> 4) == 4) {
                const struct iphdr *iph = (const struct iphdr *)pkt;
                size_t ip_len = (size_t)iph->ihl * 4;
                size_t ip_tot = ntohs(iph->tot_len);
                if (ip_tot > pkt_len) ip_tot = pkt_len;
                if (iph->protocol == IPPROTO_TCP && ip_len >= 20 && ip_len + 20 <= ip_tot) {
                    const struct tcphdr *tcph = (const struct tcphdr *)(pkt + ip_len);
                    size_t tcp_len = (size_t)tcph->doff * 4;
                    if (tcp_len >= 20 && ip_len + tcp_len <= ip_tot) {
                        const uint8_t *payload = pkt + ip_len + tcp_len;
                        size_t payload_len = ip_tot - ip_len - tcp_len;
                        int is_tls = payload_len >= 10 && payload[0] == 0x16 && payload[1] == 0x03 && payload[5] == 0x01;
                        int is_http = payload_len >= 12 && (
                            memcmp(payload, "GET ", 4) == 0 ||
                            memcmp(payload, "POST ", 5) == 0 ||
                            memcmp(payload, "HEAD ", 5) == 0
                        );
                        if (is_tls || is_http) {
                            const uint8_t *fake_p = is_tls ? fake_tls : (const uint8_t *)fake_http;
                            size_t fake_l = is_tls ? fake_tls_len : fake_http_len;
                            if (fake_l > 0) {
                                for (int r = 0; r < repeats; r++) {
                                    send_raw_ipv4_tcp(
                                        raw_fd,
                                        pkt, ip_len,
                                        pkt + ip_len, tcp_len,
                                        fake_p, fake_l,
                                        fake_ttl, (uint16_t)(100 + r), 0,
                                        1, badsum
                                    );
                                }
                            }
                            if (split_sni && is_tls && payload_len < 1400) {
                                size_t cut = find_tls_midsld_offset(payload, payload_len);
                                if (cut > 1 && cut < payload_len) {
                                    // Disorder real packet right at midsld: send part 2 first, part 1 second.
                                    send_raw_ipv4_tcp(
                                        raw_fd,
                                        pkt, ip_len,
                                        pkt + ip_len, tcp_len,
                                        payload + cut, payload_len - cut,
                                        iph->ttl, 200, (uint32_t)cut,
                                        tcph->psh, 0
                                    );
                                    send_raw_ipv4_tcp(
                                        raw_fd,
                                        pkt, ip_len,
                                        pkt + ip_len, tcp_len,
                                        payload, cut,
                                        iph->ttl, 201, 0,
                                        0, 0
                                    );
                                    verdict = NF_DROP;
                                }
                            }
                        }
                    }
                }
            }
            nfq_send_verdict(nfq_fd, queue_num, pkt_id_be, verdict);
        }
    }

    close(raw_fd);
    close(nfq_fd);
    return 0;
}
