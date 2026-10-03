variable "organization_id" {
  description = "Numeric ID of the existing Google Cloud organization that owns staging."
  type        = string

  validation {
    condition     = can(regex("^[0-9]+$", var.organization_id))
    error_message = "organization_id must be a numeric Google Cloud organization ID."
  }
}

variable "billing_account_id" {
  description = "Existing billing account attached to the Terraform-managed staging project. Supply locally with TF_VAR_billing_account_id."
  type        = string

  validation {
    condition     = can(regex("^[0-9A-Z]{6}-[0-9A-Z]{6}-[0-9A-Z]{6}$", var.billing_account_id))
    error_message = "billing_account_id must use the Google Cloud XXXXXX-XXXXXX-XXXXXX format."
  }
}

variable "project_id" {
  description = "Globally unique project ID for the Terraform-managed staging project."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{4,28}[a-z0-9]$", var.project_id))
    error_message = "project_id must be a valid 6-30 character Google Cloud project ID."
  }
}

variable "project_name" {
  description = "Display name for the Terraform-managed staging project."
  type        = string
}

variable "environment" {
  description = "Closed environment discriminator for this root."
  type        = string

  validation {
    condition     = var.environment == "staging"
    error_message = "The staging root requires environment=\"staging\"."
  }
}

variable "region" {
  description = "Google Cloud region for staging resources."
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

variable "staging_parked" {
  description = "True sets Cloud SQL activation to NEVER, Cloud Run min=0, and removes active application/DB probes and alerts."
  type        = bool
  default     = true
}

variable "database_bootstrap_active" {
  description = "First-create/recovery-only override that starts Cloud SQL with activation policy ALWAYS while the rest of staging stays parked. Return to false immediately after database creation."
  type        = bool
  default     = false

  validation {
    condition     = !var.database_bootstrap_active || (var.staging_parked && !var.provision_runtime && !var.deploy_service)
    error_message = "database_bootstrap_active may be true only for a parked foundation with provision_runtime=false and deploy_service=false."
  }
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
  default = 25
}

variable "artifact_registry_reader_members" {
  type    = set(string)
  default = []
}
