#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repository_root"

assert_contains() {
  local file="$1"
  local text="$2"
  grep -Fq "$text" "$file" || {
    echo "Missing contract in $file: $text" >&2
    exit 1
  }
}

assert_absent() {
  local file="$1"
  local text="$2"
  if grep -Fq "$text" "$file"; then
    echo "Forbidden contract in $file: $text" >&2
    exit 1
  fi
}

module="infra/modules/environment"

assert_contains "$module/main.tf" 'resource "google_project" "environment"'
assert_contains "$module/main.tf" 'org_id              = var.organization_id'
assert_contains "$module/main.tf" 'billing_account     = var.billing_account_id'
assert_contains "$module/main.tf" 'auto_create_network = false'
assert_contains "$module/main.tf" 'deletion_policy     = "PREVENT"'
assert_contains "$module/main.tf" 'deletion_policy = "ABANDON"'
assert_contains "$module/main.tf" 'prevent_destroy = true'
test "$(grep -Fc 'prevent_destroy = true' "$module/main.tf")" -eq 3
assert_contains "$module/main.tf" 'resource "terraform_data" "environment_ready"'
assert_contains "$module/main.tf" 'depends_on = [google_project_service.required]'
assert_absent "$module/main.tf" 'data "google_project"'

assert_contains "$module/locals.tf" 'application = "line-item-watch"'
assert_contains "$module/locals.tf" 'environment = var.environment'
assert_contains "$module/locals.tf" 'managed_by  = "terraform"'
assert_contains "$module/identity.tf" "assertion.ref == 'refs/heads/main'"
assert_contains "$module/identity.tf" 'display_name                       = local.github_wif_provider_display_name'
assert_contains "$module/locals.tf" 'github_wif_provider_display_name = "LIW ${var.environment} GitHub"'
assert_contains "$module/monitoring.tf" 'var.deploy_service && var.active_environment_monitoring'
assert_contains "$module/monitoring.tf" 'var.active_environment_monitoring ? local.custom_metric_alerts : {}'
assert_contains "$module/variables.tf" '!var.provision_runtime || can(regex("@sha256:[0-9a-f]{64}$", var.image_digest))'

assert_absent "infra/staging/versions.tf" 'project = var.project_id'
assert_absent "infra/production/versions.tf" 'project = var.project_id'
assert_absent "infra/staging/terraform.tfvars.example" 'billing_account_id ='
assert_absent "infra/production/terraform.tfvars.example" 'billing_account_id ='

assert_contains "infra/staging/variables.tf" 'variable "database_bootstrap_active"'
assert_contains "infra/staging/variables.tf" '!var.database_bootstrap_active || (var.staging_parked && !var.provision_runtime && !var.deploy_service)'
assert_contains "infra/staging/main.tf" 'var.database_bootstrap_active || !var.staging_parked ? "ALWAYS" : "NEVER"'
assert_absent "infra/production/variables.tf" 'database_bootstrap_active'
assert_absent "infra/production/main.tf" 'database_bootstrap_active'

assert_contains "infra/staging/versions.tf" 'prefix = "line-item-watch/staging"'
assert_contains "infra/production/versions.tf" 'prefix = "line-item-watch/production"'

staging_prefix="$(awk -F'"' '/prefix =/ { print $2 }' infra/staging/versions.tf)"
production_prefix="$(awk -F'"' '/prefix =/ { print $2 }' infra/production/versions.tf)"
test "$staging_prefix" != "$production_prefix"

if grep -R -E 'billing_account_id[[:space:]]*=[[:space:]]*"[0-9A-Z]{6}-[0-9A-Z]{6}-[0-9A-Z]{6}"' \
  infra/staging infra/production infra/modules/environment \
  --exclude='*.tftest.hcl' --exclude='terraform.tfvars.example'; then
  echo "A billing account ID appears hardcoded in Terraform configuration." >&2
  exit 1
fi

echo "Terraform project/lifecycle contracts verified."
