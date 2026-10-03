locals {
  environment_project_id     = terraform_data.environment_ready.output
  environment_project_number = google_project.environment.number
  prefix                     = "liw-${var.environment}"
  is_production              = var.environment == "production"
  labels = merge(var.labels, {
    application = "line-item-watch"
    environment = var.environment
    managed_by  = "terraform"
  })

  required_services = setunion(toset([
    "artifactregistry.googleapis.com",
    "iam.googleapis.com",
    "iamcredentials.googleapis.com",
    "logging.googleapis.com",
    "monitoring.googleapis.com",
    "run.googleapis.com",
    "secretmanager.googleapis.com",
    "sqladmin.googleapis.com",
    "sts.googleapis.com",
    ]), local.is_production ? toset([
    "certificate-manager.googleapis.com",
    "compute.googleapis.com",
    "dns.googleapis.com",
    ]) : toset([]), var.enable_production_synthetic ? toset([
    "cloudscheduler.googleapis.com",
    "storage.googleapis.com",
  ]) : toset([]))

  secret_ids = setunion(toset([
    "hubspot-oauth-client-secret",
    "credential-encryption-active-key",
    "credential-encryption-previous-key",
    "cursor-integrity-active-key",
    "cursor-integrity-previous-key",
    "database-runtime-password",
    "database-migration-password",
    "database-operator-password",
    ]), var.enable_production_synthetic ? toset([
    "synthetic-browser-state",
    "synthetic-fixture-config",
    "synthetic-record-url",
  ]) : toset([]))

  synthetic_secret_ids = toset([
    "synthetic-browser-state",
    "synthetic-fixture-config",
    "synthetic-record-url",
  ])

  service_secret_ids = toset([
    "hubspot-oauth-client-secret",
    "credential-encryption-active-key",
    "credential-encryption-previous-key",
    "cursor-integrity-active-key",
    "cursor-integrity-previous-key",
    "database-runtime-password",
  ])

  migration_secret_ids = toset(["database-migration-password"])

  operator_secret_ids = toset([
    "hubspot-oauth-client-secret",
    "credential-encryption-active-key",
    "credential-encryption-previous-key",
    "cursor-integrity-active-key",
    "cursor-integrity-previous-key",
    "database-operator-password",
  ])

  cloud_run_service_name           = "${local.prefix}-service"
  github_wif_provider_display_name = "LIW ${var.environment} GitHub"
  deterministic_run_app_origin     = "https://${local.cloud_run_service_name}-${local.environment_project_number}.${var.region}.run.app"
  api_origin                       = var.enable_production_load_balancer ? "https://${var.production_domain}" : local.deterministic_run_app_origin
  create_production_edge           = var.enable_production_load_balancer && var.deploy_service
}
