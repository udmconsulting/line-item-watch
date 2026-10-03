module "environment" {
  source = "../modules/environment"

  organization_id    = var.organization_id
  billing_account_id = var.billing_account_id
  project_id         = var.project_id
  project_name       = var.project_name
  environment        = var.environment
  region             = var.region
  image_digest       = var.image_digest
  github_repository  = var.github_repository
  provision_runtime  = var.provision_runtime
  deploy_service     = var.deploy_service

  database_tier                           = "db-f1-micro"
  database_disk_size_gb                   = 10
  database_disk_autoresize_limit_gb       = 50
  database_activation_policy              = var.database_bootstrap_active || !var.staging_parked ? "ALWAYS" : "NEVER"
  database_deletion_protection            = true
  database_backup_retention_count         = 7
  database_transaction_log_retention_days = 3

  cloud_run_min_instances       = var.staging_parked ? 0 : 1
  cloud_run_max_instances       = 1
  cloud_run_concurrency         = 20
  active_environment_monitoring = !var.staging_parked

  enable_production_load_balancer = false

  hubspot_oauth_client_id          = var.hubspot_oauth_client_id
  hubspot_app_id                   = var.hubspot_app_id
  credential_active_key_id         = var.credential_active_key_id
  credential_previous_key_id       = var.credential_previous_key_id
  cursor_active_key_id             = var.cursor_active_key_id
  cursor_previous_key_id           = var.cursor_previous_key_id
  secret_versions                  = var.secret_versions
  notification_channel_ids         = var.notification_channel_ids
  create_environment_budget        = var.create_environment_budget
  monthly_budget_amount            = var.monthly_budget_amount
  artifact_registry_reader_members = var.artifact_registry_reader_members
}
