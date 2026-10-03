resource "google_iam_workload_identity_pool" "github" {
  project                   = local.environment_project_id
  workload_identity_pool_id = "${local.prefix}-github"
  display_name              = "GitHub Actions ${var.environment}"
  description               = "Repository-scoped federation; no service-account JSON keys"

  depends_on = [google_project_service.required]
}

resource "google_iam_workload_identity_pool_provider" "github" {
  project                            = local.environment_project_id
  workload_identity_pool_id          = google_iam_workload_identity_pool.github.workload_identity_pool_id
  workload_identity_pool_provider_id = "github"
  display_name                       = local.github_wif_provider_display_name

  attribute_mapping = {
    "google.subject"             = "assertion.sub"
    "attribute.actor"            = "assertion.actor"
    "attribute.repository"       = "assertion.repository"
    "attribute.repository_owner" = "assertion.repository_owner"
    "attribute.ref"              = "assertion.ref"
    "attribute.environment"      = "assertion.environment"
  }
  attribute_condition = "assertion.repository == '${var.github_repository}' && assertion.environment == '${var.environment}' && assertion.ref == 'refs/heads/main'"

  oidc {
    issuer_uri = "https://token.actions.githubusercontent.com"
  }
}

resource "google_service_account_iam_member" "github_federation" {
  service_account_id = google_service_account.deployer.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github.name}/attribute.repository/${var.github_repository}"
}

resource "google_project_iam_member" "deployer_run_developer" {
  project = local.environment_project_id
  role    = "roles/run.developer"
  member  = "serviceAccount:${google_service_account.deployer.email}"
}

resource "google_artifact_registry_repository_iam_member" "deployer_writer" {
  project    = local.environment_project_id
  location   = google_artifact_registry_repository.containers.location
  repository = google_artifact_registry_repository.containers.name
  role       = "roles/artifactregistry.writer"
  member     = "serviceAccount:${google_service_account.deployer.email}"
}

resource "google_artifact_registry_repository_iam_member" "promotion_readers" {
  for_each = var.artifact_registry_reader_members

  project    = local.environment_project_id
  location   = google_artifact_registry_repository.containers.location
  repository = google_artifact_registry_repository.containers.name
  role       = "roles/artifactregistry.reader"
  member     = each.value
}

resource "google_service_account_iam_member" "deployer_runtime_user" {
  for_each = merge({
    runtime   = google_service_account.runtime.name
    migration = google_service_account.migration.name
    operator  = google_service_account.operator.name
    }, var.enable_production_synthetic ? {
    synthetic = google_service_account.synthetic[0].name
  } : {})

  service_account_id = each.value
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${google_service_account.deployer.email}"
}
