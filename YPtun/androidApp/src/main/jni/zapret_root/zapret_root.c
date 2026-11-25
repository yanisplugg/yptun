#include "zapret_dpi_common.h"

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

static void send_raw_ipv4_tcp(
    int raw_fd,
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

    const struct iphdr *iph = (const struct iphdr *)pkt;
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
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--self-test") == 0) {
            return run_zapret_dpi_self_test("zapret_root");
        }
    }

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
    size_t fake_tls_len = zapret_build_decoy_client_hello(decoy_sni, fake_tls, sizeof(fake_tls));
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
                        int kind = zapret_is_dpi_payload(payload, payload_len);
                        if (kind != 0) {
                            const uint8_t *fake_p = (kind == 1) ? fake_tls : (const uint8_t *)fake_http;
                            size_t fake_l = (kind == 1) ? fake_tls_len : fake_http_len;
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
                            if (split_sni && kind == 1 && payload_len < 1400) {
                                size_t cut = zapret_find_tls_midsld_offset(payload, payload_len);
                                if (cut > 1 && cut < payload_len) {
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
