#!/usr/bin/env bash

set -euo pipefail

config_file="${1?no config file}"
shift 1
config_file="$(readlink -f "$config_file")"
config_dir="$(dirname "$config_file")"
config_filename="$(basename "$config_file")"

id="$(
  podman run -d --rm --name keycloak \
      -v "$PWD/target/keycloak_http_webhook_provider.jar:/opt/keycloak/providers/http_webhook_provider.jar:ro" \
      -v "$config_dir:/config:ro" \
      -e DEBUG_PORT='*:8787' \
      -e KEYCLOAK_ADMIN="admin" \
      -e KEYCLOAK_ADMIN_PASSWORD="admin" \
      -e KEYCLOAK_WEBHOOK_CONFIG_FILE="/config/$config_filename" \
      -e KEYCLOAK_WEBHOOK_CONFIG_WATCH="true" \
      --net=host \
      "$@" \
      quay.io/keycloak/keycloak:24.0.5 \
      start-dev \
      --debug \
      --db dev-file \
      --hostname-url http://localhost:8080/auth \
      --hostname-strict=false \
      --proxy=edge \
      --http-enabled true \
      --http-relative-path /auth
)"

add_http_listener() {
    for i in {1..10}; do
        curl --silent --head --fail http://localhost:8080/auth && \
        podman exec "$id" /opt/keycloak/bin/kcadm.sh update events/config -s eventsListeners+=http_webhook \
            --no-config --server http://localhost:8080/auth --user admin --password admin --realm master && \
            exit 0
        sleep 2
    done
    exit 1
}

finish() {
  podman kill "$id"
}
trap finish EXIT

add_http_listener &

podman container logs -f "$id"
