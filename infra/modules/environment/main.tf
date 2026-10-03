resource "google_project" "environment" {
  name                = var.project_name
  project_id          = var.project_id
  org_id              = var.organization_id
  billing_account     = var.billing_account_id
  auto_create_network = false
  labels              = local.labels
  deletion_policy     = "PREVENT"

  lifecycle {
    prevent_destroy = true
  }
}

resource "google_project_service" "service_usage" {
  project            = google_project.environment.project_id
  service            = "serviceusage.googleapis.com"
  disable_on_destroy = false
}

resource "google_project_service" "resource_manager" {
  project            = google_project.environment.project_id
  service            = "cloudresourcemanager.googleapis.com"
  disable_on_destroy = false

  depends_on = [google_project_service.service_usage]
}

resource "google_project_service" "required" {
  for_each = local.required_services

  project            = google_project.environment.project_id
  service            = each.value
  disable_on_destroy = false

  depends_on = [google_project_service.resource_manager]
}

resource "terraform_data" "environment_ready" {
  input = google_project.environment.project_id

  depends_on = [google_project_service.required]
}

resource "google_artifact_registry_repository" "containers" {
  project       = local.environment_project_id
  location      = var.region
  repository_id = "liw-containers"
  description   = "Immutable Line Item Watch OCI releases"
  format        = "DOCKER"
  labels        = local.labels

  cleanup_policy_dry_run = false

  cleanup_policies {
    id     = "retain-recent-releases"
    action = "KEEP"
    most_recent_versions {
      keep_count = 20
    }
  }

  cleanup_policies {
    id     = "retain-release-and-incident-tags"
    action = "KEEP"
    condition {
      tag_prefixes = ["release-", "rollback-", "incident-hold-"]
      tag_state    = "TAGGED"
    }
  }

  cleanup_policies {
    id     = "delete-stale-untagged"
    action = "DELETE"
    condition {
      older_than = "2592000s"
      tag_state  = "UNTAGGED"
    }
  }

  depends_on = [google_project_service.required]
}

resource "google_service_account" "runtime" {
  project      = local.environment_project_id
  account_id   = "${local.prefix}-runtime"
  display_name = "Line Item Watch ${var.environment} runtime"
}

resource "google_service_account" "migration" {
  project      = local.environment_project_id
  account_id   = "${local.prefix}-migration"
  display_name = "Line Item Watch ${var.environment} migration job"
}

resource "google_service_account" "operator" {
  project      = local.environment_project_id
  account_id   = "${local.prefix}-operator"
  display_name = "Line Item Watch ${var.environment} operator job"
}

resource "google_service_account" "deployer" {
  project      = local.environment_project_id
  account_id   = "${local.prefix}-deployer"
  display_name = "GitHub WIF ${var.environment} delivery"
}

resource "google_project_iam_member" "cloud_sql_client" {
  for_each = {
    runtime   = google_service_account.runtime.email
    migration = google_service_account.migration.email
    operator  = google_service_account.operator.email
  }

  project = local.environment_project_id
  role    = "roles/cloudsql.client"
  member  = "serviceAccount:${each.value}"
}

resource "google_project_iam_member" "log_writer" {
  for_each = {
    runtime   = google_service_account.runtime.email
    migration = google_service_account.migration.email
    operator  = google_service_account.operator.email
  }

  project = local.environment_project_id
  role    = "roles/logging.logWriter"
  member  = "serviceAccount:${each.value}"
}

resource "google_project_iam_member" "metric_writer" {
  for_each = {
    runtime   = google_service_account.runtime.email
    migration = google_service_account.migration.email
    operator  = google_service_account.operator.email
  }

  project = local.environment_project_id
  role    = "roles/monitoring.metricWriter"
  member  = "serviceAccount:${each.value}"
}

resource "google_secret_manager_secret" "configuration" {
  for_each = local.secret_ids

  project   = local.environment_project_id
  secret_id = "${local.prefix}-${each.value}"
  labels    = local.labels

  replication {
    user_managed {
      replicas {
        location = var.region
      }
    }
  }

  depends_on = [google_project_service.required]
}

resource "google_secret_manager_secret_iam_member" "service_access" {
  for_each = local.service_secret_ids

  project   = local.environment_project_id
  secret_id = google_secret_manager_secret.configuration[each.value].secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.runtime.email}"
}

resource "google_secret_manager_secret_iam_member" "migration_access" {
  for_each = local.migration_secret_ids

  project   = local.environment_project_id
  secret_id = google_secret_manager_secret.configuration[each.value].secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.migration.email}"
}

resource "google_secret_manager_secret_iam_member" "operator_access" {
  for_each = local.operator_secret_ids

  project   = local.environment_project_id
  secret_id = google_secret_manager_secret.configuration[each.value].secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.operator.email}"
}

resource "google_sql_database_instance" "postgres" {
  project             = local.environment_project_id
  name                = "${local.prefix}-postgres"
  region              = var.region
  database_version    = "POSTGRES_18"
  deletion_protection = var.database_deletion_protection

  settings {
    tier                        = var.database_tier
    edition                     = "ENTERPRISE"
    availability_type           = "ZONAL"
    activation_policy           = var.database_activation_policy
    disk_type                   = "PD_SSD"
    disk_size                   = var.database_disk_size_gb
    disk_autoresize             = true
    disk_autoresize_limit       = var.database_disk_autoresize_limit_gb
    deletion_protection_enabled = var.database_deletion_protection
    connector_enforcement       = "REQUIRED"
    user_labels                 = local.labels

    backup_configuration {
      enabled                        = true
      point_in_time_recovery_enabled = true
      location                       = "eu"
      start_time                     = "02:00"
      transaction_log_retention_days = var.database_transaction_log_retention_days

      backup_retention_settings {
        retained_backups = var.database_backup_retention_count
        retention_unit   = "COUNT"
      }
    }

    ip_configuration {
      ipv4_enabled = true
    }

    maintenance_window {
      day          = 7
      hour         = 3
      update_track = "stable"
    }

    final_backup_config {
      enabled        = var.database_deletion_protection
      retention_days = local.is_production ? 30 : 7
    }
  }

  lifecycle {
    prevent_destroy = true
  }

  depends_on = [google_project_service.required]
}

resource "google_sql_database" "application" {
  project         = local.environment_project_id
  name            = "line_item_watch"
  instance        = google_sql_database_instance.postgres.name
  deletion_policy = "ABANDON"

  lifecycle {
    prevent_destroy = true
  }
}
