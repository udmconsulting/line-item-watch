variable "organization_id" {
  description = "Numeric ID of the existing Google Cloud organization that owns production."
  type        = string

  validation {
    condition     = can(regex("^[0-9]+$", var.organization_id))
    error_message = "organization_id must be a numeric Google Cloud organization ID."
  }
}

variable "billing_account_id" {
  description = "Existing billing account attached to the Terraform-managed production project. Supply locally with TF_VAR_billing_account_id."
  type        = string

  validation {
    condition     = can(regex("^[0-9A-Z]{6}-[0-9A-Z]{6}-[0-9A-Z]{6}$", var.billing_account_id))
    error_message = "billing_account_id must use the Google Cloud XXXXXX-XXXXXX-XXXXXX format."
  }
}

variable "project_id" {
  description = "Globally unique project ID for the Terraform-managed production project."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{4,28}[a-z0-9]$", var.project_id))
    error_message = "project_id must be a valid 6-30 character Google Cloud project ID."
  }
}

variable "project_name" {
  description = "Display name for the Terraform-managed production project."
  type        = string
}

variable "environment" {
  description = "Closed environment discriminator for this root."
  type        = string

  validation {
    condition     = var.environment == "production"
    error_message = "The production root requires environment=\"production\"."
  }
}

variable "region" {
  description = "Google Cloud region for production resources."
  type        = string
}

variable "image_digest" {
  type    = string
  default = ""
}

variable "provision_runtime" {
  type    = bool
  default = false
}

variable "deploy_service" {
  type    = bool
  default = false
}

variable "production_cloud_run_min_instances" {
  description = "Zero while accepting installs without continuous workers; one when active customers require continuous processing."
  type        = number
  default     = 0

  validation {
    condition     = contains([0, 1], var.production_cloud_run_min_instances)
    error_message = "production_cloud_run_min_instances must be 0 or 1. Scale max instances separately from measured load."
  }
}

variable "github_repository" { type = string }

variable "hubspot_oauth_client_id" {
  type    = string
  default = ""
}

variable "hubspot_app_id" {
  type    = string
  default = ""
}

variable "credential_active_key_id" {
  type    = string
  default = ""
}

variable "credential_previous_key_id" {
  type    = string
  default = ""
}

variable "cursor_active_key_id" {
  type    = string
  default = ""
}

variable "cursor_previous_key_id" {
  type    = string
  default = ""
}

variable "secret_versions" {
  type    = map(string)
  default = {}
}

variable "production_domain" {
  type    = string
  default = "api.lineitemwatch.com"
}

variable "dns_zone_name" {
  type    = string
  default = null
}

variable "notification_channel_ids" {
  type    = list(string)
  default = []
}

variable "create_environment_budget" {
  description = "Opt-in environment budget; the shared manual bootstrap budget remains the default guardrail."
  type        = bool
  default     = false
}

variable "monthly_budget_amount" {
  type    = number
  default = 155
}

variable "enable_production_synthetic" {
  type    = bool
  default = false
}

variable "synthetic_image_digest" {
  type    = string
  default = ""
}

variable "synthetic_secret_versions" {
  type    = map(string)
  default = {}
}

variable "synthetic_schedule" {
  type    = string
  default = "*/10 * * * *"
}

variable "synthetic_consecutive_failure_window_seconds" {
  type    = number
  default = 660
}

variable "synthetic_freshness_seconds" {
  type    = number
  default = 1200
}
