#!/usr/bin/env bash

host_ip="$(ip addr show docker0 | grep -Po 'inet \K[\d.]+')"
docker run --rm --name keycloak \
    -v "$PWD/target/keycloak_http_webhook_provider.jar:/opt/jboss/keycloak/standalone/deployments/http_webhook_provider.jar" \
    -v "$PWD/keycloak/startup-script.sh:/opt/jboss/startup-scripts/startup-script.sh" \
    -e KEYCLOAK_USER="admin" \
    -e KEYCLOAK_PASSWORD="admin" \
    -e KEYCLOAK_WEBHOOK_URL="http://$host_ip:5000" \
    -p '8080:8080' \
    "$@" \
    jboss/keycloak:15.0.2
