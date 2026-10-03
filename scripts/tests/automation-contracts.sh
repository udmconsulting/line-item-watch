#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repository_root"

fail() {
  echo "Automation contract failed: $*" >&2
  exit 1
}

assert_contains() {
  local file="$1"
  local text="$2"
  grep -Fq "$text" "$file" || fail "$file is missing: $text"
}

assert_absent() {
  local file="$1"
  local text="$2"
  if grep -Fq "$text" "$file"; then
    fail "$file contains forbidden text: $text"
  fi
}

./scripts/liw help >/dev/null
./scripts/liw artifact build --help >/dev/null

command_status=0
./scripts/liw unknown-command >/dev/null 2>&1 || command_status=$?
[[ "$command_status" -eq 1 ]] || fail "unknown commands must exit 1"
command_status=0
./scripts/liw artifact build --revision short >/dev/null 2>&1 || command_status=$?
[[ "$command_status" -eq 1 ]] || fail "short revisions must exit 1"
command_status=0
./scripts/liw artifact build \
  --revision 0123456789abcdef0123456789abcdef01234567 \
  --app-image line-item-watch:latest >/dev/null 2>&1 || command_status=$?
[[ "$command_status" -eq 1 ]] || fail "latest application tags must exit 1"
command_status=0
./scripts/liw env park production \
  --plan /tmp/forbidden.tfplan \
  --confirm APPLY_STAGING_PARK >/dev/null 2>&1 || command_status=$?
[[ "$command_status" -eq 1 ]] || fail "production lifecycle mutation must exit 1"
command_status=0
./scripts/liw env park staging \
  --plan /tmp/forbidden.tfplan \
  --confirm wrong >/dev/null 2>&1 || command_status=$?
[[ "$command_status" -eq 1 ]] || fail "incorrect staging apply confirmation must exit 1 before Terraform"

temporary_directory="$(mktemp -d "${TMPDIR:-/tmp}/liw-automation-contracts.XXXXXX")"
trap 'rm -rf "$temporary_directory"' EXIT

expect_classifier_status() {
  local expected="$1"
  shift
  local actual=0
  python3 scripts/lib/terraform-plan-safety.py "$@" >/dev/null 2>&1 || actual=$?
  [[ "$actual" -eq "$expected" ]] \
    || fail "plan classifier exited $actual instead of $expected for $*"
}

cat >"$temporary_directory/no-changes.json" <<'JSON'
{"resource_changes": []}
JSON
expect_classifier_status 0 \
  --environment staging --operation plan "$temporary_directory/no-changes.json"
classifier_output="$(python3 scripts/lib/terraform-plan-safety.py \
  --environment staging --operation plan "$temporary_directory/no-changes.json")"
[[ "$classifier_output" == *"0 to add, 0 to change, 0 to destroy, 0 replacement(s)"* ]] \
  || fail "no-change plan counts are incorrect"

cat >"$temporary_directory/add-only.json" <<'JSON'
{
  "resource_changes": [
    {
      "address": "module.environment.google_secret_manager_secret.application[\"database-url\"]",
      "type": "google_secret_manager_secret",
      "change": {"actions": ["create"], "before": null, "after": {"secret_id": "liw-staging-database-url"}}
    }
  ]
}
JSON
expect_classifier_status 0 \
  --environment staging --operation plan "$temporary_directory/add-only.json"
classifier_output="$(python3 scripts/lib/terraform-plan-safety.py \
  --environment staging --operation plan "$temporary_directory/add-only.json")"
[[ "$classifier_output" == *"1 to add, 0 to change, 0 to destroy, 0 replacement(s)"* ]] \
  || fail "add-only plan counts are incorrect"

cat >"$temporary_directory/update-only.json" <<'JSON'
{
  "variables": {
    "staging_parked": {"value": false},
    "database_bootstrap_active": {"value": false}
  },
  "resource_changes": [
    {
      "address": "module.environment.google_sql_database_instance.postgres",
      "type": "google_sql_database_instance",
      "change": {"actions": ["update"], "before": {"activation_policy": "NEVER"}, "after": {"activation_policy": "ALWAYS"}}
    }
  ]
}
JSON
expect_classifier_status 0 \
  --environment staging --operation unpark "$temporary_directory/update-only.json"
classifier_output="$(python3 scripts/lib/terraform-plan-safety.py \
  --environment staging --operation unpark "$temporary_directory/update-only.json")"
[[ "$classifier_output" == *"0 to add, 1 to change, 0 to destroy, 0 replacement(s)"* ]] \
  || fail "update-only plan counts are incorrect"

cat >"$temporary_directory/destroy.json" <<'JSON'
{
  "resource_changes": [
    {
      "address": "module.environment.google_secret_manager_secret.application[\"database-url\"]",
      "type": "google_secret_manager_secret",
      "change": {"actions": ["delete"], "before": {"secret_id": "liw-staging-database-url"}, "after": null}
    }
  ]
}
JSON
expect_classifier_status 2 \
  --environment staging --operation plan "$temporary_directory/destroy.json"

cat >"$temporary_directory/replace.json" <<'JSON'
{
  "resource_changes": [
    {
      "address": "module.environment.google_artifact_registry_repository.containers",
      "type": "google_artifact_registry_repository",
      "change": {"actions": ["delete", "create"], "before": {"repository_id": "old"}, "after": {"repository_id": "new"}}
    }
  ]
}
JSON
expect_classifier_status 2 \
  --environment staging --operation plan "$temporary_directory/replace.json"
classifier_output="$(python3 scripts/lib/terraform-plan-safety.py \
  --environment staging --operation plan "$temporary_directory/replace.json" 2>&1 || true)"
[[ "$classifier_output" == *"1 to add, 0 to change, 1 to destroy, 1 replacement(s)"* ]] \
  || fail "replacement plan counts are incorrect"

cat >"$temporary_directory/mixed.json" <<'JSON'
{
  "resource_changes": [
    {
      "address": "module.environment.google_secret_manager_secret.application[\"one\"]",
      "type": "google_secret_manager_secret",
      "change": {"actions": ["create"], "before": null, "after": {"secret_id": "one"}}
    },
    {
      "address": "module.environment.google_sql_database_instance.postgres",
      "type": "google_sql_database_instance",
      "change": {"actions": ["update"], "before": {"activation_policy": "NEVER"}, "after": {"activation_policy": "ALWAYS"}}
    },
    {
      "address": "module.environment.google_secret_manager_secret.application[\"two\"]",
      "type": "google_secret_manager_secret",
      "change": {"actions": ["delete"], "before": {"secret_id": "two"}, "after": null}
    }
  ]
}
JSON
expect_classifier_status 2 \
  --environment staging --operation plan "$temporary_directory/mixed.json"
classifier_output="$(python3 scripts/lib/terraform-plan-safety.py \
  --environment staging --operation plan "$temporary_directory/mixed.json" 2>&1 || true)"
[[ "$classifier_output" == *"1 to add, 1 to change, 1 to destroy, 0 replacement(s)"* ]] \
  || fail "mixed plan counts are incorrect"

cat >"$temporary_directory/unsafe.json" <<'JSON'
{
  "variables": {
    "staging_parked": {"value": true},
    "database_bootstrap_active": {"value": false}
  },
  "resource_changes": [
    {
      "address": "module.environment.google_project.environment",
      "type": "google_project",
      "change": {"actions": ["delete"], "before": {"project_id": "udm-liw-staging-example"}, "after": null}
    }
  ]
}
JSON
expect_classifier_status 2 \
  --environment staging --operation plan "$temporary_directory/unsafe.json"

cat >"$temporary_directory/park.json" <<'JSON'
{
  "variables": {
    "staging_parked": {"value": true},
    "database_bootstrap_active": {"value": false}
  },
  "resource_changes": [
    {
      "address": "module.environment.google_monitoring_alert_policy.cloud_sql[\"availability\"]",
      "type": "google_monitoring_alert_policy",
      "change": {"actions": ["delete"], "before": {"display_name": "staging SQL availability"}, "after": null}
    }
  ]
}
JSON
python3 scripts/lib/terraform-plan-safety.py \
  --environment staging --operation park "$temporary_directory/park.json" >/dev/null
expect_classifier_status 2 \
  --environment staging --operation unpark "$temporary_directory/park.json"

cat >"$temporary_directory/unsafe-lifecycle.json" <<'JSON'
{
  "variables": {
    "staging_parked": {"value": false},
    "database_bootstrap_active": {"value": true}
  },
  "resource_changes": []
}
JSON
expect_classifier_status 2 \
  --environment staging --operation park "$temporary_directory/unsafe-lifecycle.json"

cat >"$temporary_directory/unrelated-lifecycle-change.json" <<'JSON'
{
  "variables": {
    "staging_parked": {"value": false},
    "database_bootstrap_active": {"value": false}
  },
  "resource_changes": [
    {
      "address": "module.environment.google_project_iam_member.unrelated",
      "type": "google_project_iam_member",
      "change": {"actions": ["create"], "before": null, "after": {"role": "roles/viewer"}}
    }
  ]
}
JSON
expect_classifier_status 2 \
  --environment staging --operation unpark "$temporary_directory/unrelated-lifecycle-change.json"
expect_classifier_status 2 \
  --environment production --operation unpark "$temporary_directory/update-only.json"

cat >"$temporary_directory/cross-environment.json" <<'JSON'
{
  "resource_changes": [
    {
      "address": "module.environment.google_project_iam_member.production_writer",
      "type": "google_project_iam_member",
      "change": {"actions": ["create"], "before": null, "after": {"project": "production"}}
    },
    {
      "address": "module.environment.google_storage_bucket.state",
      "type": "google_storage_bucket",
      "change": {"actions": ["create"], "before": null, "after": {"name": "udm-liw-tfstate-1007247511793"}}
    }
  ]
}
JSON
expect_classifier_status 2 \
  --environment staging --operation plan "$temporary_directory/cross-environment.json"

printf '{not-json\n' >"$temporary_directory/invalid.json"
expect_classifier_status 3 \
  --environment staging --operation plan "$temporary_directory/invalid.json"
printf '[]\n' >"$temporary_directory/invalid-shape.json"
expect_classifier_status 3 \
  --environment staging --operation plan "$temporary_directory/invalid-shape.json"

cat >"$temporary_directory/output-change.json" <<'JSON'
{
  "resource_changes": [],
  "output_changes": {
    "api_origin": {"actions": ["update"], "before": "old", "after": "new"}
  }
}
JSON
expect_classifier_status 2 \
  --environment staging --operation plan --require-no-changes \
  "$temporary_directory/output-change.json"

deliver_public=".github/workflows/deliver-staging.yml"
deliver_reusable=".github/workflows/_deliver-staging.yml"
production_public=".github/workflows/promote-production.yml"
production_reusable=".github/workflows/_promote-production.yml"

assert_contains "$deliver_public" "workflow_dispatch:"
assert_absent "$deliver_public" "branches: [main]"
assert_contains "$deliver_public" "revision:"
assert_contains "$deliver_public" "artifact_only:"
assert_contains "$deliver_public" "run_migrations:"
assert_contains "$deliver_public" "verify:"
assert_contains "$deliver_reusable" "workflow_call:"
assert_contains "$deliver_reusable" "environment: staging"
assert_contains "$deliver_reusable" 'refs/heads/main'
assert_contains "$deliver_reusable" 'workload_identity_provider:'
assert_absent "$deliver_reusable" 'credentials_json:'
assert_absent "$deliver_public" 'secrets:'
assert_contains "$deliver_reusable" 'Required staging Environment value is empty:'
assert_contains "$deliver_reusable" 'GCP_STAGING_PROJECT_ID'
assert_contains "$deliver_reusable" 'GCP_STAGING_WIF_PROVIDER'
assert_contains "$deliver_reusable" 'GCP_STAGING_DEPLOYER_SERVICE_ACCOUNT'
assert_contains "$deliver_reusable" 'GCP_STAGING_ARTIFACT_REPOSITORY'
assert_contains "$deliver_reusable" 'artifact_only=true requires run_migrations=false and verify=false'
assert_contains "$deliver_reusable" "grep -Eq '^sha256:[0-9a-f]{64}$'"

assert_contains "$production_public" "workflow_dispatch:"
assert_contains "$production_public" "staging_digest:"
assert_contains "$production_public" "staging_synthetic_digest:"
assert_contains "$production_public" "run_migrations:"
assert_contains "$production_public" "verify:"
assert_contains "$production_reusable" "workflow_call:"
assert_contains "$production_reusable" "environment: production"
assert_contains "$production_reusable" 'refs/heads/main'
assert_contains "$production_reusable" 'workload_identity_provider:'
assert_absent "$production_reusable" 'credentials_json:'
assert_absent "$production_public" 'secrets:'
assert_contains "$production_reusable" "^sha256:[0-9a-f]{64}$"
assert_absent "$production_reusable" 'test "$REVISION" = "$GITHUB_SHA"'
assert_absent "$production_reusable" 'docker build'
assert_contains "$production_reusable" './scripts/liw artifact inspect'
assert_contains "$production_reusable" 'test "$RUN_MIGRATIONS" = true'
assert_contains "$production_reusable" 'test "$VERIFY" = true'

command_status=0
./scripts/liw infra apply staging >/dev/null 2>&1 || command_status=$?
[[ "$command_status" -eq 1 ]] || fail "generic Terraform apply must not be exposed"

if grep -R -E '(--image|IMAGE=|image:)[^#\n]*:latest' .github/workflows scripts \
    --exclude='automation-contracts.sh'; then
  fail "mutable latest image deployment is forbidden"
fi

if grep -R -E '(GOOGLE_APPLICATION_CREDENTIALS|service[_-]?account.*\.json|credentials_json:)' \
    .github/workflows scripts --exclude='automation-contracts.sh'; then
  fail "static GCP credential configuration is forbidden"
fi

echo "Automation contracts verified."
