#!/bin/sh
set -eu

role=$(printf '%s' "${APPLICATION_RUNTIME_ROLE:-SERVICE}" | tr '[:lower:]' '[:upper:]')
case "$role" in
  SERVICE)
    role_profile=service
    ;;
  MIGRATE)
    role_profile=migrate
    ;;
  OPERATOR)
    role_profile=operator
    ;;
  *)
    echo "Unsupported APPLICATION_RUNTIME_ROLE" >&2
    exit 64
    ;;
esac

if [ -z "${SPRING_PROFILES_ACTIVE:-}" ]; then
  if [ -n "${CLOUD_SQL_INSTANCE_CONNECTION_NAME:-}" ]; then
    SPRING_PROFILES_ACTIVE="${role_profile},gcp"
  else
    SPRING_PROFILES_ACTIVE="${role_profile}"
  fi
  export SPRING_PROFILES_ACTIVE
fi

exec java -jar /opt/app/application.jar "$@"
