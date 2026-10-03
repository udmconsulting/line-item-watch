mock_provider "google" {}

variables {
  organization_id    = "123456789012"
  billing_account_id = "000000-000000-000000"
  project_id         = "liw-test-staging-12345"
  project_name       = "LIW Test Staging"
  environment        = "staging"
  region             = "europe-west1"
  github_repository  = "example/line-item-watch"
  provision_runtime  = false
  deploy_service     = false
}

run "parked_first_create_starts_only_database" {
  command = plan

  variables {
    staging_parked            = true
    database_bootstrap_active = true
  }

  assert {
    condition     = module.environment.database_activation_policy == "ALWAYS"
    error_message = "Initial Cloud SQL creation must use activation policy ALWAYS."
  }

  assert {
    condition     = module.environment.cloud_run_service_name == null && module.environment.migration_job_name == null && module.environment.operator_job_name == null
    error_message = "The database bootstrap override must not provision runtime services or jobs."
  }
}

run "parked_steady_state_stops_database" {
  command = plan

  variables {
    staging_parked            = true
    database_bootstrap_active = false
  }

  assert {
    condition     = module.environment.database_activation_policy == "NEVER"
    error_message = "Normal parked staging must set Cloud SQL activation policy to NEVER."
  }
}

run "active_staging_starts_database" {
  command = plan

  variables {
    staging_parked            = false
    database_bootstrap_active = false
  }

  assert {
    condition     = module.environment.database_activation_policy == "ALWAYS"
    error_message = "Active staging must set Cloud SQL activation policy to ALWAYS."
  }
}

run "bootstrap_rejects_active_staging" {
  command = plan

  variables {
    staging_parked            = false
    database_bootstrap_active = true
  }

  expect_failures = [var.database_bootstrap_active]
}
