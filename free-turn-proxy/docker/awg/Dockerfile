# syntax=docker/dockerfile:1
# Образ AWG-сервиса: amneziawg-go в форграунде + amneziawg-tools (awg, awg-quick).
ARG GO_VERSION=1.26.7
ARG ALPINE_VERSION=3.23
ARG AWG_GO_VERSION=v3.1.20260814
ARG AWG_TOOLS_VERSION=v3.1.20260812

FROM --platform=$BUILDPLATFORM golang:${GO_VERSION}-alpine AS go
ARG AWG_GO_VERSION
ARG TARGETARCH
RUN apk add --no-cache git
ENV CGO_ENABLED=0 GOOS=linux

RUN GOARCH=$TARGETARCH go install "github.com/amnezia-vpn/amneziawg-go/v3@${AWG_GO_VERSION}" \
 && install -D "$(find /go/bin -type f -name amneziawg-go | head -n1)" /out/amneziawg-go

FROM alpine:${ALPINE_VERSION} AS tools
ARG AWG_TOOLS_VERSION
RUN apk add --no-cache git build-base linux-headers \
 && git clone --depth 1 --branch "${AWG_TOOLS_VERSION}" https://github.com/amnezia-vpn/amneziawg-tools.git /src \
 && make -C /src/src

FROM alpine:${ALPINE_VERSION}
RUN apk add --no-cache bash iproute2 iptables
COPY --from=go /out/amneziawg-go /usr/bin/amneziawg-go
COPY --from=tools /src/src/wg /usr/bin/awg
COPY --from=tools /src/src/wg-quick/linux.bash /usr/bin/awg-quick
COPY docker/awg/start.sh /usr/local/bin/ft-awg-start
RUN sed -i 's/\r$//' /usr/local/bin/ft-awg-start \
 && chmod +x /usr/bin/amneziawg-go /usr/bin/awg /usr/bin/awg-quick /usr/local/bin/ft-awg-start

ENTRYPOINT ["/usr/local/bin/ft-awg-start"]
