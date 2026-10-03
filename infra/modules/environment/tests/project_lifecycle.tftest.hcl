mock_provider "google" {}

variables {
  organization_id    = "123456789012"
  billing_account_id = "000000-000000-000000"
  project_id         = "liw-test-staging-12345"
  project_name       = "LIW Test Staging"
  environment        = "staging"
  region             = "europe-west1"
  github_repository  = "example/line-item-watch"

  database_tier                           = "db-f1-micro"
  database_disk_size_gb                   = 10
  database_disk_autoresize_limit_gb       = 50
  database_activation_policy              = "NEVER"
  database_backup_retention_count         = 7
  database_transaction_log_retention_days = 3

  cloud_run_min_instances = 0
  cloud_run_max_instances = 1
}

run "foundation_without_runtime_inputs" {
  command = plan

  variables {
    provision_runtime             = false
    deploy_service                = false
    active_environment_monitoring = false
  }

  assert {
    condition     = google_project.environment.org_id == "123456789012"
    error_message = "The environment project must use the configured organization parent."
  }

  assert {
    condition     = google_project.environment.billing_account == "000000-000000-000000"
    error_message = "The environment project must attach the configured billing account."
  }

  assert {
    condition = (
      google_project.environment.labels.application == "line-item-watch" &&
      google_project.environment.labels.environment == "staging" &&
      google_project.environment.labels.managed_by == "terraform"
    )
    error_message = "The staging project must carry the mandatory environment labels."
  }

  assert {
    condition     = google_project.environment.deletion_policy == "PREVENT"
    error_message = "The provider-level project deletion policy must remain PREVENT."
  }

  assert {
    condition     = google_sql_database.application.deletion_policy == "ABANDON"
    error_message = "The application database must be abandoned rather than dropped if Terraform ownership is deliberately removed."
  }

  assert {
    condition     = google_project.environment.auto_create_network == false
    error_message = "Environment project creation must not implicitly create a default VPC."
  }

  assert {
    condition     = google_project_service.service_usage.service == "serviceusage.googleapis.com" && google_project_service.resource_manager.service == "cloudresourcemanager.googleapis.com"
    error_message = "Project bootstrap must explicitly manage Service Usage before Resource Manager."
  }

  assert {
    condition     = length(google_cloud_run_v2_service.application) == 0 && length(google_cloud_run_v2_job.migration) == 0 && length(google_cloud_run_v2_job.operator) == 0
    error_message = "Foundation mode must not create runtime consumers."
  }

  assert {
    condition     = length(google_monitoring_uptime_check_config.public_readiness) == 0 && length(google_monitoring_alert_policy.cloud_sql) == 0 && length(google_monitoring_alert_policy.custom_application) == 0
    error_message = "Parked foundation mode must not create active probes or database/worker alerts."
  }

  assert {
    condition     = strcontains(google_iam_workload_identity_pool_provider.github.attribute_condition, "assertion.ref == 'refs/heads/main'")
    error_message = "GitHub WIF must enforce refs/heads/main in GCP."
  }

  assert {
    condition     = length(google_iam_workload_identity_pool_provider.github.display_name) <= 32
    error_message = "The GitHub WIF provider display name must respect GCP's 32-character limit."
  }

  assert {
    condition = (
      strcontains(google_iam_workload_identity_pool_provider.github.attribute_condition, "assertion.repository == 'example/line-item-watch'") &&
      strcontains(google_iam_workload_identity_pool_provider.github.attribute_condition, "assertion.environment == 'staging'") &&
      strcontains(google_iam_workload_identity_pool_provider.github.attribute_condition, "assertion.ref == 'refs/heads/main'")
    )
    error_message = "GitHub WIF must remain bounded to the exact repository, environment, and main ref."
  }
}

run "runtime_requires_runtime_inputs" {
  command = plan

  variables {
    provision_runtime = true
    deploy_service    = false
  }

  expect_failures = [
    var.image_digest,
    var.hubspot_oauth_client_id,
    var.hubspot_app_id,
    var.credential_active_key_id,
    var.cursor_active_key_id,
    var.secret_versions,
  ]
}

run "service_requires_its_secret_version" {
  command = plan

  variables {
    provision_runtime        = true
    deploy_service           = true
    image_digest             = "europe-west1-docker.pkg.dev/test/image@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    hubspot_oauth_client_id  = "test-client"
    hubspot_app_id           = "123456"
    credential_active_key_id = "credential-key-1"
    cursor_active_key_id     = "cursor-key-1"
    secret_versions = {
      hubspot-oauth-client-secret      = "1"
      credential-encryption-active-key = "1"
      cursor-integrity-active-key      = "1"
      database-migration-password      = "1"
      database-operator-password       = "1"
    }
  }

  expect_failures = [var.secret_versions]
}

run "parked_runtime_is_quiet" {
  command = plan

  variables {
    provision_runtime             = true
    deploy_service                = true
    active_environment_monitoring = false
    image_digest                  = "europe-west1-docker.pkg.dev/test/image@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    hubspot_oauth_client_id       = "test-client"
    hubspot_app_id                = "123456"
    credential_active_key_id      = "credential-key-1"
    cursor_active_key_id          = "cursor-key-1"
    secret_versions = {
      hubspot-oauth-client-secret      = "1"
      credential-encryption-active-key = "1"
      cursor-integrity-active-key      = "1"
      database-runtime-password        = "1"
      database-migration-password      = "1"
      database-operator-password       = "1"
    }
  }

  assert {
    condition     = length(google_cloud_run_v2_service.application) == 1 && google_cloud_run_v2_service.application[0].template[0].scaling[0].min_instance_count == 0
    error_message = "Parked staging must retain the service with zero minimum instances."
  }

  assert {
    condition     = google_sql_database_instance.postgres.settings[0].activation_policy == "NEVER"
    error_message = "Parked staging must stop Cloud SQL compute."
  }

  assert {
    condition     = length(google_monitoring_uptime_check_config.public_readiness) == 0 && length(google_monitoring_alert_policy.public_availability) == 0 && length(google_monitoring_alert_policy.cloud_run_utilization) == 0 && length(google_monitoring_alert_policy.cloud_sql) == 0 && length(google_monitoring_alert_policy.custom_application) == 0
    error_message = "Parked staging must remove active application, uptime, database, and worker alerts."
  }
}

run "active_runtime_restores_monitoring" {
  command = plan

  variables {
    provision_runtime             = true
    deploy_service                = true
    active_environment_monitoring = true
    database_activation_policy    = "ALWAYS"
    cloud_run_min_instances       = 1
    image_digest                  = "europe-west1-docker.pkg.dev/test/image@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    hubspot_oauth_client_id       = "test-client"
    hubspot_app_id                = "123456"
    credential_active_key_id      = "credential-key-1"
    cursor_active_key_id          = "cursor-key-1"
    secret_versions = {
      hubspot-oauth-client-secret      = "1"
      credential-encryption-active-key = "1"
      cursor-integrity-active-key      = "1"
      database-runtime-password        = "1"
      database-migration-password      = "1"
      database-operator-password       = "1"
    }
  }

  assert {
    condition     = length(google_monitoring_uptime_check_config.public_readiness) == 1 && length(google_monitoring_alert_policy.public_availability) == 1
    error_message = "Active staging must restore public readiness monitoring."
  }

  assert {
    condition     = length(google_monitoring_alert_policy.cloud_sql) == 4 && length(google_monitoring_alert_policy.custom_application) == 7
    error_message = "Active staging must restore database and worker/reliability alerts."
  }
}

run "production_project_labels_are_isolated" {
  command = plan

  variables {
    project_id                              = "liw-test-production-123"
    project_name                            = "LIW Test Production"
    environment                             = "production"
    database_tier                           = "db-custom-1-3840"
    database_disk_size_gb                   = 20
    database_disk_autoresize_limit_gb       = 100
    database_activation_policy              = "ALWAYS"
    database_backup_retention_count         = 14
    database_transaction_log_retention_days = 7
    provision_runtime                       = false
    deploy_service                          = false
    active_environment_monitoring           = true
  }

  assert {
    condition     = google_project.environment.labels.environment == "production" && google_project.environment.project_id == "liw-test-production-123"
    error_message = "Production must retain its own project ID and production label."
  }
}
