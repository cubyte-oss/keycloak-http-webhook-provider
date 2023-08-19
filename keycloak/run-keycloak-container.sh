#!/usr/bin/env bash

set -euo pipefail

host_ip="$(ip route get 1.1.1.1 | grep src | cut -d' ' -f7)"

id="$(
  podman run -d --rm --name keycloak \
      -v "$PWD/target/keycloak_http_webhook_provider.jar:/opt/keycloak/providers/http_webhook_provider.jar" \
      -e DEBUG_PORT='*:8787' \
      -e KEYCLOAK_ADMIN="admin" \
      -e KEYCLOAK_ADMIN_PASSWORD="admin" \
      -e KEYCLOAK_WEBHOOK_URL="http://$host_ip:5000" \
      -p '8080:8080' \
      -p '8787:8787' \
      "$@" \
      quay.io/keycloak/keycloak:22.0.1 \
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
