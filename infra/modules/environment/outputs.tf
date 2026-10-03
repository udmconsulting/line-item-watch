output "artifact_registry_repository" {
  value = "${var.region}-docker.pkg.dev/${local.environment_project_id}/${google_artifact_registry_repository.containers.repository_id}"
}

output "cloud_run_service_name" {
  value = try(google_cloud_run_v2_service.application[0].name, null)
}

output "cloud_run_uri" {
  description = "Provider-reported stable service URI. Compare with api_origin during acceptance."
  value       = try(google_cloud_run_v2_service.application[0].uri, null)
}

output "api_origin" {
  value = local.api_origin
}

output "oauth_redirect_uri" {
  value = "${local.api_origin}/integrations/hubspot/oauth/callback"
}

output "webhook_uri" {
  value = "${local.api_origin}/integrations/hubspot/webhooks"
}

output "cloud_sql_instance_connection_name" {
  value = google_sql_database_instance.postgres.connection_name
}

output "database_activation_policy" {
  description = "Planned Cloud SQL activation policy, exposed for lifecycle verification."
  value       = google_sql_database_instance.postgres.settings[0].activation_policy
}

output "migration_job_name" {
  value = try(google_cloud_run_v2_job.migration[0].name, null)
}

output "operator_job_name" {
  value = try(google_cloud_run_v2_job.operator[0].name, null)
}

output "synthetic_job_name" {
  value = try(google_cloud_run_v2_job.synthetic[0].name, null)
}

output "synthetic_evidence_bucket" {
  value = try(google_storage_bucket.synthetic_evidence[0].name, null)
}

output "deployer_service_account" {
  value = google_service_account.deployer.email
}

output "workload_identity_provider" {
  value = google_iam_workload_identity_pool_provider.github.name
}

output "production_global_ip" {
  value = local.create_production_edge ? google_compute_global_address.production[0].address : null
}

output "certificate_dns_authorization_record" {
  value = local.create_production_edge ? {
    name = google_certificate_manager_dns_authorization.production[0].dns_resource_record[0].name
    type = google_certificate_manager_dns_authorization.production[0].dns_resource_record[0].type
    data = google_certificate_manager_dns_authorization.production[0].dns_resource_record[0].data
  } : null
}

output "secret_container_names" {
  description = "Containers only. Secret payloads/versions are injected separately."
  value = {
    for key, secret in google_secret_manager_secret.configuration : key => secret.name
  }
}
