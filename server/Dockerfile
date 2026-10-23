# syntax=docker/dockerfile:1

# ---- build ----
FROM golang:1.26 AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
ARG VERSION=dev
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath \
      -ldflags "-s -w -X main.version=${VERSION}" \
      -o /out/listenly-server ./cmd/listenly-server \
 && mkdir -p /out/data

# ---- runtime: static, non-root, no shell ----
FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/listenly-server /listenly-server
# /data is owned by the distroless "nonroot" user (65532).
COPY --from=build --chown=65532:65532 /out/data /data
ENV LISTENLY_ADDR=:8080 \
    LISTENLY_DB=/data/listenly.db
VOLUME ["/data"]
EXPOSE 8080
USER 65532:65532
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD ["/listenly-server", "healthcheck"]
ENTRYPOINT ["/listenly-server"]
