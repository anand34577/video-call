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
    go build -trimpath -ldflags="-s -w -X main.version=${VERSION}" -o /out/videocall-server ./cmd/server

# ---- runtime ----
FROM alpine:3.21
RUN adduser -D -u 10001 app && apk add --no-cache ca-certificates tzdata \
 && mkdir /data && chown app:app /data
COPY --from=server /out/videocall-server /usr/local/bin/videocall-server
# /data owned by app so a fresh named volume is writable; a bind mount keeps
# the host dir's owner instead (chown it to 10001, see README).
ENV DATA_DIR=/data
VOLUME ["/data"]
WORKDIR /data
USER app
EXPOSE 8443 8080
ENTRYPOINT ["videocall-server"]
