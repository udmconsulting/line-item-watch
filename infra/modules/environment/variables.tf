variable "project_id" {
  description = "Globally unique ID of the Terraform-managed project for exactly one environment."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{4,28}[a-z0-9]$", var.project_id))
    error_message = "project_id must be a valid 6-30 character Google Cloud project ID."
  }
}

variable "project_name" {
  description = "Display name of the Terraform-managed environment project."
  type        = string

  validation {
    condition     = length(trimspace(var.project_name)) >= 4 && length(var.project_name) <= 30
    error_message = "project_name must contain 4-30 characters."
  }
}

variable "organization_id" {
  description = "Numeric ID of the existing organization that owns the environment project."
  type        = string

  validation {
    condition     = can(regex("^[0-9]+$", var.organization_id))
    error_message = "organization_id must be numeric."
  }
}

variable "billing_account_id" {
  description = "Existing billing account attached to the Terraform-managed environment project."
  type        = string

  validation {
    condition     = can(regex("^[0-9A-Z]{6}-[0-9A-Z]{6}-[0-9A-Z]{6}$", var.billing_account_id))
    error_message = "billing_account_id must use the Google Cloud XXXXXX-XXXXXX-XXXXXX format."
  }
}

variable "environment" {
  description = "Closed deployment environment name."
  type        = string

  validation {
    condition     = contains(["staging", "production"], var.environment)
    error_message = "environment must be staging or production."
  }
}

variable "region" {
  description = "Google Cloud region for environment resources."
  type        = string

  validation {
    condition     = trimspace(var.region) != ""
    error_message = "region must not be empty."
  }
}

variable "image_digest" {
  description = "Immutable OCI reference including @sha256:...; never a mutable tag. It is not dereferenced until provision_runtime is true."
  type        = string
  default     = ""

  validation {
    condition     = !var.provision_runtime || can(regex("@sha256:[0-9a-f]{64}$", var.image_digest))
    error_message = "image_digest must end with an immutable sha256 digest when provision_runtime is true."
  }
}

variable "provision_runtime" {
  description = "Bootstrap gate for Cloud Run jobs/service. Set false until the registry image and explicit secret versions exist; never turn off after runtime creation."
  type        = bool
  default     = false
}

variable "deploy_service" {
  description = "Migration gate for the HTTP/worker service and production edge. Set false until MIGRATE and grant finalization succeed; never turn off after service creation."
  type        = bool
  default     = false

  validation {
    condition     = !var.deploy_service || var.provision_runtime
    error_message = "deploy_service requires provision_runtime=true."
  }
}

variable "secret_versions" {
  description = "Explicit Secret Manager versions. Values are version numbers, never latest."
  type        = map(string)
  default     = {}

  validation {
    condition = alltrue([
      for version in values(var.secret_versions) : can(regex("^[1-9][0-9]*$", version))
    ])
    error_message = "Every configured secret version must be an explicit positive integer."
  }

  validation {
    condition = !var.provision_runtime || length(setsubtract(
      toset(concat(
        [
          "hubspot-oauth-client-secret",
          "credential-encryption-active-key",
          "cursor-integrity-active-key",
          "database-migration-password",
          "database-operator-password",
        ],
        var.deploy_service ? ["database-runtime-password"] : [],
        var.credential_previous_key_id == "" ? [] : ["credential-encryption-previous-key"],
        var.cursor_previous_key_id == "" ? [] : ["cursor-integrity-previous-key"],
      )),
      toset(keys(var.secret_versions)),
    )) == 0
    error_message = "provision_runtime requires explicit versions for job-consumed secrets; deploy_service additionally requires database-runtime-password, and configured previous key IDs require matching versions."
  }
}

variable "database_tier" {
  type = string
}

variable "database_disk_size_gb" {
  type = number
}

variable "database_disk_autoresize_limit_gb" {
  type = number
}

variable "database_activation_policy" {
  description = "ALWAYS normally; staging may use NEVER only while deliberately parked."
  type        = string
  default     = "ALWAYS"

  validation {
    condition     = contains(["ALWAYS", "NEVER"], var.database_activation_policy)
    error_message = "database_activation_policy must be ALWAYS or NEVER."
  }
}

variable "database_deletion_protection" {
  type    = bool
  default = true
}

variable "database_backup_retention_count" {
  type = number
}

variable "database_transaction_log_retention_days" {
  type = number
}

variable "cloud_run_min_instances" {
  type = number
}

variable "cloud_run_max_instances" {
  type = number
}

variable "cloud_run_concurrency" {
  type    = number
  default = 20
}

variable "enable_production_load_balancer" {
  type    = bool
  default = false
}

variable "production_domain" {
  description = "Required only when the production load balancer is enabled."
  type        = string
  default     = null
}

variable "dns_zone_name" {
  description = "Optional existing Cloud DNS managed-zone name. Null emits DNS record inputs only."
  type        = string
  default     = null
}

variable "hubspot_oauth_client_id" {
  description = "Nonsecret environment-specific HubSpot OAuth client ID."
  type        = string
  default     = ""

  validation {
    condition     = !var.provision_runtime || trimspace(var.hubspot_oauth_client_id) != ""
    error_message = "hubspot_oauth_client_id is required when provision_runtime is true."
  }
}

variable "hubspot_app_id" {
  description = "Nonsecret numeric HubSpot application ID."
  type        = string
  default     = ""

  validation {
    condition     = !var.provision_runtime || can(regex("^[0-9]+$", var.hubspot_app_id))
    error_message = "hubspot_app_id must be numeric when provision_runtime is true."
  }
}

variable "credential_active_key_id" {
  type    = string
  default = ""

  validation {
    condition     = !var.provision_runtime || can(regex("^[A-Za-z0-9._-]+$", var.credential_active_key_id))
    error_message = "credential_active_key_id is required when provision_runtime is true."
  }
}

variable "credential_previous_key_id" {
  type    = string
  default = ""
}

variable "cursor_active_key_id" {
  type    = string
  default = ""

  validation {
    condition     = !var.provision_runtime || can(regex("^[A-Za-z0-9._-]+$", var.cursor_active_key_id))
    error_message = "cursor_active_key_id is required when provision_runtime is true."
  }
}

variable "cursor_previous_key_id" {
  type    = string
  default = ""
}

variable "enable_metrics_export" {
  type    = bool
  default = true
}

variable "notification_channel_ids" {
  description = "Pre-existing Monitoring notification channel resource names."
  type        = list(string)
  default     = []
}

variable "active_environment_monitoring" {
  description = "Whether active runtime, public readiness, Cloud SQL availability, and worker reliability alerting is present. False is the declarative parked-staging state."
  type        = bool
  default     = true
}

variable "create_environment_budget" {
  description = "Opt in to a project-filtered environment budget in addition to the manual shared bootstrap guardrail."
  type        = bool
  default     = false
}

variable "monthly_budget_amount" {
  description = "Planning threshold in billing-account currency; null skips budget creation."
  type        = number
  default     = null
}

variable "labels" {
  type    = map(string)
  default = {}
}

variable "github_repository" {
  description = "GitHub owner/repository allowed to federate into this project."
  type        = string

  validation {
    condition     = can(regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", var.github_repository))
    error_message = "github_repository must use the exact owner/repository form."
  }
}

variable "artifact_registry_reader_members" {
  description = "Additional IAM members allowed to pull immutable images for promotion."
  type        = set(string)
  default     = []
}

variable "enable_production_synthetic" {
  description = "Creates the production-only read-only HubSpot browser job, scheduler, evidence bucket, and alerts."
  type        = bool
  default     = false

  validation {
    condition     = !var.enable_production_synthetic || var.environment == "production"
    error_message = "The recurring browser synthetic may be enabled only in production."
  }

  validation {
    condition     = !var.enable_production_synthetic || var.deploy_service
    error_message = "The recurring browser synthetic requires deploy_service=true."
  }
}

variable "synthetic_image_digest" {
  description = "Immutable Playwright runner image. Required when enable_production_synthetic is true."
  type        = string
  default     = ""

  validation {
    condition     = !var.enable_production_synthetic || can(regex("@sha256:[0-9a-f]{64}$", var.synthetic_image_digest))
    error_message = "synthetic_image_digest must be an immutable OCI digest when the production synthetic is enabled."
  }
}

variable "synthetic_secret_versions" {
  description = "Explicit versions for browser state, record URL, and fixture contract; never latest."
  type        = map(string)
  default     = {}

  validation {
    condition = !var.enable_production_synthetic || (
      length(setsubtract(toset(["synthetic-browser-state", "synthetic-fixture-config", "synthetic-record-url"]), toset(keys(var.synthetic_secret_versions)))) == 0 &&
      alltrue([for version in values(var.synthetic_secret_versions) : can(regex("^[1-9][0-9]*$", version))])
    )
    error_message = "All synthetic secrets require explicit positive integer versions."
  }
}

variable "synthetic_schedule" {
  description = "Production browser cadence in Cloud Scheduler cron syntax; ten minutes by default."
  type        = string
  default     = "*/10 * * * *"
}

variable "synthetic_consecutive_failure_window_seconds" {
  description = "Retest window matching two configured production browser intervals."
  type        = number
  default     = 660
}

variable "synthetic_freshness_seconds" {
  description = "Maximum age of the production synthetic success heartbeat."
  type        = number
  default     = 1200
}
