locals {
  custom_metric_alerts = {
    signal_backlog = {
      display_name = "Signal backlog"
      metric       = "custom.googleapis.com/line_item_watch/processing/backlog"
      threshold    = 100
      duration     = "300s"
      aligner      = "ALIGN_MAX"
    }
    signal_oldest_age = {
      display_name = "Signal oldest unprocessed age"
      metric       = "custom.googleapis.com/line_item_watch/processing/oldest_unprocessed_age_seconds"
      threshold    = 900
      duration     = "300s"
      aligner      = "ALIGN_MAX"
    }
    exhausted_signals = {
      display_name = "Exhausted Line Item signals"
      metric       = "custom.googleapis.com/line_item_watch/processing/exhausted_signals"
      threshold    = 0
      duration     = "60s"
      aligner      = "ALIGN_MAX"
    }
    reconciliation_stale = {
      display_name = "Reconciliation last success is stale"
      metric       = "custom.googleapis.com/line_item_watch/reconciliation/last_success_age_seconds"
      threshold    = 28800
      duration     = "300s"
      aligner      = "ALIGN_MAX"
    }
    reconciliation_never_run = {
      display_name = "Active connection never reconciled"
      metric       = "custom.googleapis.com/line_item_watch/reconciliation/never_reconciled_active_connections"
      threshold    = 0
      duration     = "900s"
      aligner      = "ALIGN_MAX"
    }
    suspected_gaps = {
      display_name = "Suspected audit-history gaps"
      metric       = "custom.googleapis.com/line_item_watch/reliability/suspected_gaps"
      threshold    = 0
      duration     = "60s"
      aligner      = "ALIGN_MAX"
    }
    webhook_failures = {
      display_name = "Valid webhook ingestion failures"
      metric       = "custom.googleapis.com/hubspot/webhook/ingestion/failures"
      threshold    = 0
      duration     = "60s"
      aligner      = "ALIGN_DELTA"
    }
  }
}

resource "google_monitoring_uptime_check_config" "public_readiness" {
  count = var.deploy_service && var.active_environment_monitoring ? 1 : 0

  project      = local.environment_project_id
  display_name = "${local.prefix} public readiness and certificate"
  timeout      = "10s"
  period       = "60s"

  monitored_resource {
    type = "uptime_url"
    labels = {
      project_id = local.environment_project_id
      host       = trimprefix(local.api_origin, "https://")
    }
  }

  http_check {
    path           = "/actuator/health/readiness"
    port           = 443
    use_ssl        = true
    validate_ssl   = true
    request_method = "GET"
  }

  checker_type = "STATIC_IP_CHECKERS"

  depends_on = [google_cloud_run_v2_service_iam_member.public_invoker]
}

resource "google_monitoring_alert_policy" "public_availability" {
  count = var.deploy_service && var.active_environment_monitoring ? 1 : 0

  project      = local.environment_project_id
  display_name = "${local.prefix}: public readiness or certificate failure"
  combiner     = "OR"

  conditions {
    display_name = "Readiness HTTPS check failed"
    condition_threshold {
      filter          = "metric.type=\"monitoring.googleapis.com/uptime_check/check_passed\" AND metric.label.check_id=\"${google_monitoring_uptime_check_config.public_readiness[0].uptime_check_id}\""
      comparison      = "COMPARISON_LT"
      threshold_value = 1
      duration        = "120s"

      aggregations {
        alignment_period   = "60s"
        per_series_aligner = "ALIGN_NEXT_OLDER"
      }
    }
  }

  notification_channels = var.notification_channel_ids
  user_labels           = local.labels
}

resource "google_monitoring_alert_policy" "http_5xx" {
  count = var.deploy_service && var.active_environment_monitoring ? 1 : 0

  project      = local.environment_project_id
  display_name = "${local.prefix}: HTTP 5xx ratio"
  combiner     = "OR"

  conditions {
    display_name = "5xx responses exceed initial threshold"
    condition_threshold {
      filter          = "resource.type=\"cloud_run_revision\" AND resource.label.service_name=\"${google_cloud_run_v2_service.application[0].name}\" AND metric.type=\"run.googleapis.com/request_count\" AND metric.label.response_code_class=\"5xx\""
      comparison      = "COMPARISON_GT"
      threshold_value = 5
      duration        = "300s"

      aggregations {
        alignment_period     = "60s"
        per_series_aligner   = "ALIGN_RATE"
        cross_series_reducer = "REDUCE_SUM"
        group_by_fields      = ["resource.label.service_name"]
      }
    }
  }

  notification_channels = var.notification_channel_ids
  user_labels           = local.labels
}

resource "google_monitoring_alert_policy" "latency" {
  count = var.deploy_service && var.active_environment_monitoring ? 1 : 0

  project      = local.environment_project_id
  display_name = "${local.prefix}: p95 request latency"
  combiner     = "OR"

  conditions {
    display_name = "p95 latency exceeds 2 seconds"
    condition_threshold {
      filter          = "resource.type=\"cloud_run_revision\" AND resource.label.service_name=\"${google_cloud_run_v2_service.application[0].name}\" AND metric.type=\"run.googleapis.com/request_latencies\""
      comparison      = "COMPARISON_GT"
      threshold_value = 2000
      duration        = "300s"

      aggregations {
        alignment_period   = "60s"
        per_series_aligner = "ALIGN_PERCENTILE_95"
      }
    }
  }

  notification_channels = var.notification_channel_ids
  user_labels           = local.labels
}

resource "google_monitoring_alert_policy" "cloud_run_utilization" {
  for_each = var.deploy_service && var.active_environment_monitoring ? {
    cpu = {
      display_name = "Cloud Run CPU"
      metric       = "run.googleapis.com/container/cpu/utilizations"
      threshold    = 0.8
    }
    memory = {
      display_name = "Cloud Run memory"
      metric       = "run.googleapis.com/container/memory/utilizations"
      threshold    = 0.85
    }
  } : {}

  project      = local.environment_project_id
  display_name = "${local.prefix}: ${each.value.display_name} high"
  combiner     = "OR"

  conditions {
    display_name = "${each.value.display_name} exceeds initial threshold"
    condition_threshold {
      filter          = "resource.type=\"cloud_run_revision\" AND resource.label.service_name=\"${google_cloud_run_v2_service.application[0].name}\" AND metric.type=\"${each.value.metric}\""
      comparison      = "COMPARISON_GT"
      threshold_value = each.value.threshold
      duration        = "300s"

      aggregations {
        alignment_period   = "60s"
        per_series_aligner = "ALIGN_PERCENTILE_95"
      }
    }
  }

  notification_channels = var.notification_channel_ids
  user_labels           = local.labels
}

resource "google_monitoring_alert_policy" "cloud_sql" {
  for_each = var.active_environment_monitoring ? {
    unavailable = {
      display_name = "Cloud SQL unavailable"
      metric       = "cloudsql.googleapis.com/database/up"
      comparison   = "COMPARISON_LT"
      threshold    = 1
    }
    cpu = {
      display_name = "Cloud SQL CPU high"
      metric       = "cloudsql.googleapis.com/database/cpu/utilization"
      comparison   = "COMPARISON_GT"
      threshold    = 0.8
    }
    connections = {
      display_name = "Cloud SQL connection utilization high"
      metric       = "cloudsql.googleapis.com/database/postgresql/num_backends_by_state"
      comparison   = "COMPARISON_GT"
      threshold    = 80
    }
    disk = {
      display_name = "Cloud SQL disk utilization high"
      metric       = "cloudsql.googleapis.com/database/disk/utilization"
      comparison   = "COMPARISON_GT"
      threshold    = 0.8
    }
  } : {}

  project      = local.environment_project_id
  display_name = "${local.prefix}: ${each.value.display_name}"
  combiner     = "OR"

  conditions {
    display_name = each.value.display_name
    condition_threshold {
      filter          = "resource.type=\"cloudsql_database\" AND resource.label.database_id=\"${local.environment_project_id}:${google_sql_database_instance.postgres.name}\" AND metric.type=\"${each.value.metric}\""
      comparison      = each.value.comparison
      threshold_value = each.value.threshold
      duration        = "300s"

      aggregations {
        alignment_period     = "60s"
        per_series_aligner   = "ALIGN_MEAN"
        cross_series_reducer = "REDUCE_MAX"
      }
    }
  }

  notification_channels = var.notification_channel_ids
  user_labels           = local.labels
}

resource "google_monitoring_alert_policy" "custom_application" {
  for_each = var.active_environment_monitoring ? local.custom_metric_alerts : {}

  project      = local.environment_project_id
  display_name = "${local.prefix}: ${each.value.display_name}"
  combiner     = "OR"

  conditions {
    display_name = each.value.display_name
    condition_threshold {
      filter          = "resource.type=\"global\" AND metric.type=\"${each.value.metric}\""
      comparison      = "COMPARISON_GT"
      threshold_value = each.value.threshold
      duration        = each.value.duration

      aggregations {
        alignment_period     = "60s"
        per_series_aligner   = each.value.aligner
        cross_series_reducer = "REDUCE_MAX"
      }
    }
  }

  notification_channels = var.notification_channel_ids
  user_labels           = local.labels
}

resource "google_billing_budget" "environment" {
  count = var.create_environment_budget && var.monthly_budget_amount != null ? 1 : 0

  billing_account = var.billing_account_id
  display_name    = "${local.prefix} monthly planning budget"

  budget_filter {
    projects = ["projects/${local.environment_project_number}"]
  }

  amount {
    specified_amount {
      currency_code = "USD"
      units         = tostring(floor(var.monthly_budget_amount))
    }
  }

  threshold_rules {
    threshold_percent = 0.5
  }
  threshold_rules {
    threshold_percent = 0.8
  }
  threshold_rules {
    threshold_percent = 1.0
  }
}
