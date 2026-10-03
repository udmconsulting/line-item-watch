resource "google_service_account" "synthetic" {
  count = var.enable_production_synthetic ? 1 : 0

  project      = local.environment_project_id
  account_id   = "${local.prefix}-synthetic"
  display_name = "Line Item Watch production browser synthetic"
}

resource "google_service_account" "synthetic_scheduler" {
  count = var.enable_production_synthetic ? 1 : 0

  project      = local.environment_project_id
  account_id   = "${local.prefix}-synthetic-scheduler"
  display_name = "Invokes only the Line Item Watch synthetic job"
}

resource "google_project_iam_member" "synthetic_log_writer" {
  count = var.enable_production_synthetic ? 1 : 0

  project = local.environment_project_id
  role    = "roles/logging.logWriter"
  member  = "serviceAccount:${google_service_account.synthetic[0].email}"
}

resource "google_project_iam_member" "synthetic_metric_writer" {
  count = var.enable_production_synthetic ? 1 : 0

  project = local.environment_project_id
  role    = "roles/monitoring.metricWriter"
  member  = "serviceAccount:${google_service_account.synthetic[0].email}"
}

resource "google_secret_manager_secret_iam_member" "synthetic_access" {
  for_each = var.enable_production_synthetic ? local.synthetic_secret_ids : toset([])

  project   = local.environment_project_id
  secret_id = google_secret_manager_secret.configuration[each.value].secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.synthetic[0].email}"
}

resource "google_storage_bucket" "synthetic_evidence" {
  count = var.enable_production_synthetic ? 1 : 0

  project                     = local.environment_project_id
  name                        = "${local.environment_project_id}-liw-synthetic-evidence"
  location                    = var.region
  storage_class               = "STANDARD"
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  force_destroy               = false
  labels                      = local.labels

  lifecycle_rule {
    condition { age = 30 }
    action { type = "Delete" }
  }
}

resource "google_storage_bucket_iam_member" "synthetic_evidence_creator" {
  count = var.enable_production_synthetic ? 1 : 0

  bucket = google_storage_bucket.synthetic_evidence[0].name
  role   = "roles/storage.objectCreator"
  member = "serviceAccount:${google_service_account.synthetic[0].email}"
}

resource "google_cloud_run_v2_job" "synthetic" {
  count = var.enable_production_synthetic ? 1 : 0

  project             = local.environment_project_id
  name                = "${local.prefix}-synthetic"
  location            = var.region
  deletion_protection = true

  template {
    task_count  = 1
    parallelism = 1

    template {
      service_account       = google_service_account.synthetic[0].email
      timeout               = "300s"
      max_retries           = 0
      execution_environment = "EXECUTION_ENVIRONMENT_GEN2"

      containers {
        name  = "playwright"
        image = var.synthetic_image_digest

        resources {
          limits = { cpu = "1", memory = "1Gi" }
        }

        env {
          name  = "LIW_ASSURANCE_TARGET"
          value = "production"
        }
        env {
          name  = "LIW_RELEASE_DIGEST"
          value = trimprefix(regex("@sha256:[0-9a-f]{64}$", var.image_digest), "@")
        }
        env {
          name  = "LIW_EVIDENCE_BUCKET"
          value = google_storage_bucket.synthetic_evidence[0].name
        }
        env {
          name  = "GOOGLE_CLOUD_PROJECT"
          value = local.environment_project_id
        }
        env {
          name  = "LIW_API_ORIGIN"
          value = local.api_origin
        }

        dynamic "env" {
          for_each = {
            LIW_HUBSPOT_STORAGE_STATE_JSON = "synthetic-browser-state"
            LIW_ASSURANCE_FIXTURE_JSON     = "synthetic-fixture-config"
            LIW_HUBSPOT_RECORD_URL         = "synthetic-record-url"
          }
          content {
            name = env.key
            value_source {
              secret_key_ref {
                secret  = google_secret_manager_secret.configuration[env.value].secret_id
                version = var.synthetic_secret_versions[env.value]
              }
            }
          }
        }
      }
    }
  }

  depends_on = [
    google_project_service.required,
    google_secret_manager_secret_iam_member.synthetic_access,
    google_storage_bucket_iam_member.synthetic_evidence_creator,
  ]
}

resource "google_cloud_run_v2_job_iam_member" "synthetic_scheduler_invoker" {
  count = var.enable_production_synthetic ? 1 : 0

  project  = local.environment_project_id
  location = var.region
  name     = google_cloud_run_v2_job.synthetic[0].name
  role     = "roles/run.invoker"
  member   = "serviceAccount:${google_service_account.synthetic_scheduler[0].email}"
}

resource "google_cloud_scheduler_job" "synthetic" {
  count = var.enable_production_synthetic ? 1 : 0

  project          = local.environment_project_id
  region           = var.region
  name             = "${local.prefix}-synthetic"
  description      = "Runs the read-only production HubSpot browser journey"
  schedule         = var.synthetic_schedule
  time_zone        = "Etc/UTC"
  attempt_deadline = "320s"

  retry_config {
    retry_count = 0
  }

  http_target {
    http_method = "POST"
    uri         = "https://run.googleapis.com/v2/projects/${local.environment_project_id}/locations/${var.region}/jobs/${google_cloud_run_v2_job.synthetic[0].name}:run"
    body        = base64encode("{}")

    oauth_token {
      service_account_email = google_service_account.synthetic_scheduler[0].email
    }
  }

  depends_on = [google_cloud_run_v2_job_iam_member.synthetic_scheduler_invoker]
}

resource "google_monitoring_metric_descriptor" "synthetic_product_failed" {
  count = var.enable_production_synthetic ? 1 : 0

  project      = local.environment_project_id
  type         = "custom.googleapis.com/line_item_watch/synthetic/product_failed"
  metric_kind  = "GAUGE"
  value_type   = "INT64"
  display_name = "Line Item Watch synthetic product failure"
}

resource "google_monitoring_metric_descriptor" "synthetic_success" {
  count = var.enable_production_synthetic ? 1 : 0

  project      = local.environment_project_id
  type         = "custom.googleapis.com/line_item_watch/synthetic/success"
  metric_kind  = "GAUGE"
  value_type   = "INT64"
  display_name = "Line Item Watch synthetic success heartbeat"
}

resource "google_logging_metric" "synthetic_failure" {
  for_each = var.enable_production_synthetic ? toset([
    "PRODUCT_FAILURE",
    "SYNTHETIC_AUTH_FAILURE",
    "SYNTHETIC_HARNESS_FAILURE",
  ]) : toset([])

  project = local.environment_project_id
  name    = "liw_synthetic_${lower(each.value)}"
  filter  = "resource.type=\"cloud_run_job\" resource.labels.job_name=\"${google_cloud_run_v2_job.synthetic[0].name}\" jsonPayload.schema=\"line-item-watch.synthetic-result.v1\" jsonPayload.outcome=\"FAIL\" jsonPayload.failureClass=\"${each.value}\""

  metric_descriptor {
    metric_kind = "DELTA"
    value_type  = "INT64"
  }
}

resource "google_monitoring_alert_policy" "synthetic_warning" {
  for_each = google_logging_metric.synthetic_failure

  project      = local.environment_project_id
  display_name = "${local.prefix}: ${replace(each.key, "_", " ")}"
  combiner     = "OR"
  severity     = "WARNING"

  conditions {
    display_name = "One ${each.key} result"
    condition_threshold {
      filter          = "resource.type=\"cloud_run_job\" AND metric.type=\"logging.googleapis.com/user/${each.value.name}\""
      comparison      = "COMPARISON_GT"
      threshold_value = 0
      duration        = "0s"

      aggregations {
        alignment_period     = "60s"
        per_series_aligner   = "ALIGN_DELTA"
        cross_series_reducer = "REDUCE_SUM"
      }
    }
  }

  notification_channels = var.notification_channel_ids
  user_labels           = local.labels
}

resource "google_monitoring_alert_policy" "synthetic_product_critical" {
  count = var.enable_production_synthetic ? 1 : 0

  project      = local.environment_project_id
  display_name = "${local.prefix}: consecutive synthetic product failures"
  combiner     = "OR"
  severity     = "CRITICAL"

  conditions {
    display_name = "At least two observations, all product failures"
    condition_prometheus_query_language {
      query               = <<-EOT
        count_over_time(custom_googleapis_com:line_item_watch_synthetic_product_failed{monitored_resource="global"}[${var.synthetic_consecutive_failure_window_seconds}s]) >= 2
        and min_over_time(custom_googleapis_com:line_item_watch_synthetic_product_failed{monitored_resource="global"}[${var.synthetic_consecutive_failure_window_seconds}s]) == 1
      EOT
      duration            = "0s"
      evaluation_interval = "30s"
    }
  }

  notification_channels = var.notification_channel_ids
  user_labels           = local.labels

  depends_on = [google_monitoring_metric_descriptor.synthetic_product_failed]
}

resource "google_monitoring_alert_policy" "synthetic_stale" {
  count = var.enable_production_synthetic ? 1 : 0

  project      = local.environment_project_id
  display_name = "${local.prefix}: synthetic success heartbeat stale"
  combiner     = "OR"
  severity     = "CRITICAL"

  conditions {
    display_name = "No successful browser journey within freshness window"
    condition_absent {
      filter   = "resource.type=\"global\" AND metric.type=\"custom.googleapis.com/line_item_watch/synthetic/success\""
      duration = "${var.synthetic_freshness_seconds}s"
    }
  }

  notification_channels = var.notification_channel_ids
  user_labels           = local.labels
}
