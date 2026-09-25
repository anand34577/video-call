# syntax=docker/dockerfile:1

# The two build stages run on the builder's own arch ($BUILDPLATFORM) and Go
# cross-compiles to $TARGETARCH, so a linux/arm64 image builds natively on an
# amd64 runner instead of running npm + go under QEMU (slow, and npm can hang).

# ---- frontend build ----
FROM --platform=$BUILDPLATFORM node:22-alpine AS web
WORKDIR /src/web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

# ---- server build (UI embedded via go:embed) ----
FROM --platform=$BUILDPLATFORM golang:1.26-alpine AS server
WORKDIR /src
COPY server/go.mod server/go.sum ./
RUN go mod download
COPY server/ ./
RUN rm -rf static/dist
COPY --from=web /src/web/dist ./static/dist
ARG TARGETOS TARGETARCH
ARG VERSION=dev
RUN CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH \
    go build -trimpath -ldflags="-s -w -X visioncall/internal/config.Version=${VERSION}" -o /out/visioncall-server ./cmd/server

# ---- runtime ----
FROM alpine:3.21
RUN adduser -D -u 10001 app && apk add --no-cache ca-certificates tzdata \
 && mkdir /data && chown app:app /data
COPY --from=server /out/visioncall-server /usr/local/bin/visioncall-server
# /data owned by app so a fresh named volume is writable; a bind mount keeps
# the host dir's owner instead (chown it to 10001).
ENV DATA_DIR=/data
VOLUME ["/data"]
WORKDIR /data
USER app
# 8443 HTTPS app, 8080 plain HTTP, 7882/udp group-call media.
EXPOSE 8443/tcp 8080/tcp 7882/udp
# Port 8080 serves /api/healthz in every mode except HTTPS_REDIRECT=true, so
# fall back to the HTTPS port for that case.
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/api/healthz \
   || wget -q -O /dev/null --no-check-certificate https://127.0.0.1:8443/api/healthz \
   || exit 1
ENTRYPOINT ["visioncall-server"]
