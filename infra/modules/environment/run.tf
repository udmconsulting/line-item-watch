locals {
  common_environment = {
    APPLICATION_ENVIRONMENT                      = var.environment
    CLOUD_SQL_INSTANCE_CONNECTION_NAME           = google_sql_database_instance.postgres.connection_name
    DATABASE_NAME                                = google_sql_database.application.name
    GCP_METRICS_EXPORT_ENABLED                   = tostring(var.enable_metrics_export)
    GCP_PROJECT_ID                               = local.environment_project_id
    HUBSPOT_API_BASE_URL                         = "https://api.hubapi.com"
    HUBSPOT_AUTHORIZATION_BASE_URL               = "https://app.hubspot.com/oauth/authorize"
    HUBSPOT_CREDENTIAL_KEY_ID                    = var.credential_active_key_id
    HUBSPOT_CREDENTIAL_PREVIOUS_KEY_ID           = var.credential_previous_key_id
    HUBSPOT_OAUTH_CLIENT_ID                      = var.hubspot_oauth_client_id
    HUBSPOT_OAUTH_REDIRECT_URI                   = "${local.api_origin}/integrations/hubspot/oauth/callback"
    HUBSPOT_UI_EXTENSION_APP_ID                  = var.hubspot_app_id
    HUBSPOT_UI_EXTENSION_ENABLED                 = "true"
    HUBSPOT_UI_EXTENSION_PUBLIC_BASE_URI         = local.api_origin
    HUBSPOT_WEBHOOK_ENABLED                      = "true"
    HUBSPOT_WEBHOOK_PUBLIC_URI                   = "${local.api_origin}/integrations/hubspot/webhooks"
    LINE_ITEM_WATCH_AUDIT_CURSOR_ACTIVE_KEY_ID   = var.cursor_active_key_id
    LINE_ITEM_WATCH_AUDIT_CURSOR_PREVIOUS_KEY_ID = var.cursor_previous_key_id
    RELEASE_REVISION                             = var.image_digest
  }

  shared_secret_environment = {
    HUBSPOT_OAUTH_CLIENT_SECRET             = "hubspot-oauth-client-secret"
    HUBSPOT_CREDENTIAL_ENCRYPTION_KEY       = "credential-encryption-active-key"
    LINE_ITEM_WATCH_AUDIT_CURSOR_ACTIVE_KEY = "cursor-integrity-active-key"
  }

  optional_secret_environment = merge(
    var.credential_previous_key_id == "" ? {} : {
      HUBSPOT_CREDENTIAL_PREVIOUS_ENCRYPTION_KEY = "credential-encryption-previous-key"
    },
    var.cursor_previous_key_id == "" ? {} : {
      LINE_ITEM_WATCH_AUDIT_CURSOR_PREVIOUS_KEY = "cursor-integrity-previous-key"
    },
  )
}

resource "google_cloud_run_v2_service" "application" {
  count = var.deploy_service ? 1 : 0

  project             = local.environment_project_id
  name                = local.cloud_run_service_name
  location            = var.region
  deletion_protection = local.is_production
  ingress = var.enable_production_load_balancer ? (
    "INGRESS_TRAFFIC_INTERNAL_LOAD_BALANCER"
  ) : "INGRESS_TRAFFIC_ALL"

  template {
    service_account                  = google_service_account.runtime.email
    timeout                          = "300s"
    execution_environment            = "EXECUTION_ENVIRONMENT_GEN2"
    max_instance_request_concurrency = var.cloud_run_concurrency

    scaling {
      min_instance_count = var.cloud_run_min_instances
      max_instance_count = var.cloud_run_max_instances
    }

    containers {
      name  = "application"
      image = var.image_digest

      ports {
        name           = "http1"
        container_port = 8080
      }

      resources {
        cpu_idle          = false
        startup_cpu_boost = true
        limits = {
          cpu    = "1"
          memory = "1Gi"
        }
      }

      startup_probe {
        initial_delay_seconds = 5
        timeout_seconds       = 3
        period_seconds        = 5
        failure_threshold     = 24
        http_get {
          path = "/actuator/health/liveness"
          port = 8080
        }
      }

      liveness_probe {
        initial_delay_seconds = 30
        timeout_seconds       = 3
        period_seconds        = 10
        failure_threshold     = 3
        http_get {
          path = "/actuator/health/liveness"
          port = 8080
        }
      }

      dynamic "env" {
        for_each = merge(local.common_environment, {
          APPLICATION_RUNTIME_ROLE            = "SERVICE"
          DATABASE_USERNAME                   = "liw_app"
          DATABASE_POOL_MAXIMUM_SIZE          = "10"
          LINE_ITEM_WATCH_PROCESSING_ENABLED  = "true"
          LINE_ITEM_WATCH_RELIABILITY_ENABLED = "true"
        })
        content {
          name  = env.key
          value = env.value
        }
      }

      dynamic "env" {
        for_each = merge(local.shared_secret_environment, local.optional_secret_environment, {
          DATABASE_PASSWORD = "database-runtime-password"
        })
        content {
          name = env.key
          value_source {
            secret_key_ref {
              secret  = google_secret_manager_secret.configuration[env.value].secret_id
              version = var.secret_versions[env.value]
            }
          }
        }
      }
    }
  }

  lifecycle {
    precondition {
      condition     = can(regex("@sha256:[0-9a-f]{64}$", var.image_digest))
      error_message = "Cloud Run deployments require an immutable OCI digest."
    }
    precondition {
      condition     = !var.enable_production_load_balancer || var.production_domain != null
      error_message = "production_domain is required when the load balancer is enabled."
    }
  }

  depends_on = [
    google_project_service.required,
    google_secret_manager_secret_iam_member.service_access,
  ]
}

resource "google_cloud_run_v2_service_iam_member" "public_invoker" {
  count = var.deploy_service ? 1 : 0

  project  = local.environment_project_id
  location = google_cloud_run_v2_service.application[0].location
  name     = google_cloud_run_v2_service.application[0].name
  role     = "roles/run.invoker"
  member   = "allUsers"
}

resource "google_cloud_run_v2_job" "migration" {
  count = var.provision_runtime ? 1 : 0

  project             = local.environment_project_id
  name                = "${local.prefix}-migrate"
  location            = var.region
  deletion_protection = local.is_production

  template {
    task_count  = 1
    parallelism = 1

    template {
      service_account       = google_service_account.migration.email
      timeout               = "900s"
      max_retries           = 0
      execution_environment = "EXECUTION_ENVIRONMENT_GEN2"

      containers {
        name  = "migration"
        image = var.image_digest

        resources {
          limits = {
            cpu    = "1"
            memory = "1Gi"
          }
        }

        dynamic "env" {
          for_each = merge(local.common_environment, {
            APPLICATION_RUNTIME_ROLE            = "MIGRATE"
            DATABASE_USERNAME                   = "liw_migrator"
            DATABASE_POOL_MAXIMUM_SIZE          = "3"
            HUBSPOT_UI_EXTENSION_ENABLED        = "false"
            HUBSPOT_WEBHOOK_ENABLED             = "false"
            LINE_ITEM_WATCH_PROCESSING_ENABLED  = "false"
            LINE_ITEM_WATCH_RELIABILITY_ENABLED = "false"
          })
          content {
            name  = env.key
            value = env.value
          }
        }

        dynamic "env" {
          for_each = {
            DATABASE_PASSWORD = "database-migration-password"
          }
          content {
            name = env.key
            value_source {
              secret_key_ref {
                secret  = google_secret_manager_secret.configuration[env.value].secret_id
                version = var.secret_versions[env.value]
              }
            }
          }
        }
      }
    }
  }

  depends_on = [
    google_project_service.required,
    google_secret_manager_secret_iam_member.migration_access,
  ]
}

resource "google_cloud_run_v2_job" "operator" {
  count = var.provision_runtime ? 1 : 0

  project             = local.environment_project_id
  name                = "${local.prefix}-operator"
  location            = var.region
  deletion_protection = local.is_production

  template {
    task_count  = 1
    parallelism = 1

    template {
      service_account       = google_service_account.operator.email
      timeout               = "1800s"
      max_retries           = 0
      execution_environment = "EXECUTION_ENVIRONMENT_GEN2"

      containers {
        name  = "operator"
        image = var.image_digest

        resources {
          limits = {
            cpu    = "1"
            memory = "1Gi"
          }
        }

        dynamic "env" {
          for_each = merge(local.common_environment, {
            APPLICATION_RUNTIME_ROLE            = "OPERATOR"
            DATABASE_USERNAME                   = "liw_operator"
            DATABASE_POOL_MAXIMUM_SIZE          = "3"
            HUBSPOT_UI_EXTENSION_ENABLED        = "false"
            HUBSPOT_WEBHOOK_ENABLED             = "false"
            LINE_ITEM_WATCH_PROCESSING_ENABLED  = "false"
            LINE_ITEM_WATCH_RELIABILITY_ENABLED = "false"
          })
          content {
            name  = env.key
            value = env.value
          }
        }

        dynamic "env" {
          for_each = merge(local.shared_secret_environment, local.optional_secret_environment, {
            DATABASE_PASSWORD = "database-operator-password"
          })
          content {
            name = env.key
            value_source {
              secret_key_ref {
                secret  = google_secret_manager_secret.configuration[env.value].secret_id
                version = var.secret_versions[env.value]
              }
            }
          }
        }
      }
    }
  }

  depends_on = [
    google_project_service.required,
    google_secret_manager_secret_iam_member.operator_access,
  ]
}
