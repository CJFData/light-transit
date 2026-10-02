#!/usr/bin/env bash
# Host-side driver: builds a tool the way CI does, behind the build-time Maven
# proxy.
#
#   build container ──(internal network)──> proxy container ──> Google /
#                                                               Central /
#                                                               Plugin Portal /
#                                                               JitPack
#
# The build container is attached only to an --internal Docker network, so the
# proxy is the only host it can reach. The proxy runs from the same builder
# image (see bin/maven-proxy.sh) and is also attached to the default network.
#
# usage: local-build.sh --image IMAGE --dev-repo PATH --output-dir DIR
#                       [--tool-path PATH] [--git-url URL]
#
# --dev-repo must be a git checkout; its HEAD is the commit that gets built.
# On exit, DIR holds the builder outputs plus proxy-access.log.

set -Eeuo pipefail

usage() {
    cat <<'USAGE' >&2
usage: local-build.sh --image IMAGE --dev-repo PATH --output-dir DIR
                      [--tool-path PATH] [--git-url URL]
USAGE
    exit 64
}

IMAGE=""
DEV_REPO=""
OUTPUT_DIR=""
TOOL_PATH="tool"
GIT_URL=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --image) IMAGE="$2"; shift 2 ;;
        --dev-repo) DEV_REPO="$2"; shift 2 ;;
        --output-dir) OUTPUT_DIR="$2"; shift 2 ;;
        --tool-path) TOOL_PATH="$2"; shift 2 ;;
        --git-url) GIT_URL="$2"; shift 2 ;;
        -h|--help) usage ;;
        *) echo "unknown flag: $1" >&2; usage ;;
    esac
done

[[ -n "$IMAGE" && -n "$DEV_REPO" && -n "$OUTPUT_DIR" ]] || usage

DEV_REPO="$(cd "$DEV_REPO" && pwd)"
mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR="$(cd "$OUTPUT_DIR" && pwd)"
# The container's builder user must be able to write here.
chmod 777 "$OUTPUT_DIR"

GIT_REF="$(git -C "$DEV_REPO" rev-parse HEAD)"
: "${GIT_URL:=file://$DEV_REPO}"

SUFFIX="$$-$RANDOM"
NETWORK="light-build-$SUFFIX"
PROXY="light-proxy-$SUFFIX"

cleanup() {
    # The access log is the proxy's stdout; its error log goes to stderr.
    docker logs "$PROXY" > "$OUTPUT_DIR/proxy-access.log" 2>/dev/null || true
    docker rm -f "$PROXY" >/dev/null 2>&1 || true
    docker network rm "$NETWORK" >/dev/null 2>&1 || true
}
trap cleanup EXIT

CONTAINER_HARDENING=(
    --platform=linux/amd64
    --security-opt=no-new-privileges
    --cap-drop=ALL
    --tmpfs /tmp:exec,mode=1777
)

docker network create --internal "$NETWORK" >/dev/null

echo ">> starting proxy"
docker run -d --name "$PROXY" "${CONTAINER_HARDENING[@]}" \
    --entrypoint /opt/light-builder/bin/maven-proxy.sh \
    "$IMAGE" >/dev/null
docker network connect --alias maven-proxy "$NETWORK" "$PROXY"

for _ in $(seq 1 30); do
    if docker exec "$PROXY" curl -fsS -o /dev/null http://127.0.0.1:8080/healthz 2>/dev/null; then
        break
    fi
    sleep 1
done
docker exec "$PROXY" curl -fsS -o /dev/null http://127.0.0.1:8080/healthz

# Fail fast if the internal network is not actually isolated.
echo ">> checking build network isolation"
if docker run --rm "${CONTAINER_HARDENING[@]}" --network "$NETWORK" \
    --entrypoint curl "$IMAGE" -fsS -m 10 -o /dev/null https://repo.maven.apache.org/ 2>/dev/null; then
    echo "build network can reach the internet directly" >&2
    exit 1
fi
docker run --rm "${CONTAINER_HARDENING[@]}" --network "$NETWORK" \
    --entrypoint curl "$IMAGE" -fsS -m 10 -o /dev/null http://maven-proxy:8080/healthz

echo ">> building $GIT_REF"
docker run --rm "${CONTAINER_HARDENING[@]}" --network "$NETWORK" \
    --tmpfs /home/builder:uid=1001,gid=1001,mode=1777 \
    -e LIGHT_MAVEN_PROXY=http://maven-proxy:8080/ \
    -e LIGHT_IMAGE_DIGEST="$IMAGE" \
    -v "$DEV_REPO:/source:ro" \
    -v "$OUTPUT_DIR:/out" \
    "$IMAGE" \
    --git-url "$GIT_URL" --git-ref "$GIT_REF" \
    --dev-repo /source --tool-path "$TOOL_PATH" --output-dir /out

echo ">> done; artifacts in $OUTPUT_DIR"
