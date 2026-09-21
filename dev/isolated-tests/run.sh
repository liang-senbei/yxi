#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "$0")/../.." && pwd)
mode=${1:-}
case "$mode" in build|run) ;; *) printf 'Usage: bash %s build | run app.yxi.…Test [native-claude-binary]\n' "$0" >&2; exit 2;; esac
test -x /usr/bin/docker
test -S /var/run/docker.sock
docker_local() {
    env -u DOCKER_CONTEXT -u DOCKER_HOST -u DOCKER_TLS_VERIFY -u DOCKER_CERT_PATH -u BUILDX_BUILDER \
        /usr/bin/docker --host unix:///var/run/docker.sock "$@"
}
test -z "$(git -C "$repo" status --porcelain)" || { echo 'Use a clean committed source snapshot.' >&2; exit 2; }
revision=$(git -C "$repo" rev-parse HEAD)
image_revision=$revision
if test "$mode" = run && test -n "${YXI_TEST_IMAGE_REVISION:-}"; then
    image_revision=$YXI_TEST_IMAGE_REVISION
    [[ "$image_revision" =~ ^[a-f0-9]{40}$ ]]
    git -C "$repo" cat-file -e "$image_revision^{commit}"
fi
image="yxi-isolated-tests:$image_revision"
cache="${XDG_CACHE_HOME:-$HOME/.cache}/yxi-isolated-tests"
mkdir -p "$cache"
chmod 700 "$cache"
run_root=$(mktemp -d "$cache/run.XXXXXX")
chmod 700 "$run_root"

if test "$mode" = build; then
    available=$(df -Pk "$cache" | awk 'END {print $4}')
    test "$available" -ge 4194304 || { echo 'At least 4 GiB free disk is required before building.' >&2; exit 2; }
    mkdir "$run_root/context"
    git -C "$repo" archive HEAD | tar -x -C "$run_root/context"
    docker_local build --builder default --platform linux/amd64 -f "$run_root/context/dev/isolated-tests/Dockerfile" \
        --build-arg "SOURCE_REVISION=$revision" -t "$image" "$run_root/context"
    docker_local image inspect "$image" > "$run_root/image.json"
    echo "Built $image; evidence: $run_root"
    exit 0
fi

test_class=${2:-}
[[ "$test_class" =~ ^app\.yxi\.[A-Za-z0-9_.]+Test$ ]] || { echo 'Provide one explicit project test class.' >&2; exit 2; }
native=${3:-}
mounts=()
if test -n "$native"; then
    native=$(readlink -f "$native")
    test -f "$native" && test -x "$native"
    test "$(od -An -tx1 -N4 "$native" | tr -d ' \n')" = 7f454c46 || { echo 'Native CLI must be an ELF executable, not a host wrapper.' >&2; exit 2; }
    mounts+=(--mount "type=bind,src=$native,dst=/opt/native/claude,readonly")
fi
test "$(docker_local image inspect --format '{{ index .Config.Labels "org.yxi.test-isolation" }}' "$image")" = 1
test "$(docker_local image inspect --format '{{ index .Config.Labels "org.yxi.source-revision" }}' "$image")" = "$image_revision"
image_id=$(docker_local image inspect --format '{{.Id}}' "$image")
mkdir "$run_root/results"
printf 'runner=%s\nimage-source=%s\ntest=%s\n' "$revision" "$image_revision" "$test_class" > "$run_root/source.txt"
run_id=$(python3 -c 'import uuid;print(uuid.uuid4())')
container_id=
cleanup() {
    if [[ "$container_id" =~ ^[a-f0-9]{64}$ ]] && \
       test "$(docker_local inspect --format '{{ index .Config.Labels "org.yxi.isolated-test" }}' "$container_id" 2>/dev/null || true)" = "$run_id"; then
        docker_local rm -f "$container_id" >/dev/null
    fi
}
trap cleanup EXIT
container_id=$(docker_local create --platform linux/amd64 --network none --ipc private \
    --cap-drop ALL --cap-add SETUID --cap-add SETGID --cap-add SYS_CHROOT \
    --security-opt no-new-privileges --memory 4g --memory-swap 4g --cpus 2 --pids-limit 512 \
    --label "org.yxi.isolated-test=$run_id" \
    --env "YXI_ISOLATED_TEST_RUN=$run_id" --env HOME=/sandbox/home \
    --env CLAUDE_CONFIG_DIR=/sandbox/home/.claude \
    --env CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1 --env DISABLE_TELEMETRY=1 --env DISABLE_AUTOUPDATER=1 \
    --env YXI_TEST_NATIVE_CLAUDE=/opt/native/claude \
    --mount "type=bind,src=$run_root/results,dst=/results" "${mounts[@]}" "$image_id" "$test_class")
docker_local inspect "$container_id" > "$run_root/container.json"
verify_args=("$run_root/container.json" "$run_id" "$run_root/results" "$image_id")
if test -n "$native"; then verify_args+=("$native"); fi
python3 "$repo/dev/isolated-tests/verify_container.py" "${verify_args[@]}"
# A timeout must also remove the labelled container, not just disconnect its client.
set +e
timeout 900 env -u DOCKER_CONTEXT -u DOCKER_HOST -u DOCKER_TLS_VERIFY -u DOCKER_CERT_PATH \
    /usr/bin/docker --host unix:///var/run/docker.sock start -a "$container_id" > "$run_root/run.log" 2>&1
client_result=$?
set -e
docker_local inspect "$container_id" > "$run_root/final-container.json"
exit_code=$(docker_local inspect --format '{{.State.ExitCode}}' "$container_id")
echo "Results: $run_root"
echo "Tested image source: $image_revision"
test "$client_result" = 0 && test "$exit_code" = 0
