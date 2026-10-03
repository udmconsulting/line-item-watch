resource "google_compute_region_network_endpoint_group" "serverless" {
  count = local.create_production_edge ? 1 : 0

  project               = local.environment_project_id
  name                  = "${local.prefix}-serverless-neg"
  region                = var.region
  network_endpoint_type = "SERVERLESS"

  cloud_run {
    service = google_cloud_run_v2_service.application[0].name
  }
}

resource "google_compute_backend_service" "application" {
  count = local.create_production_edge ? 1 : 0

  project               = local.environment_project_id
  name                  = "${local.prefix}-backend"
  protocol              = "HTTP"
  load_balancing_scheme = "EXTERNAL_MANAGED"
  timeout_sec           = 30

  backend {
    group = google_compute_region_network_endpoint_group.serverless[0].id
  }

  log_config {
    enable      = true
    sample_rate = 1.0
  }
}

resource "google_compute_url_map" "https" {
  count = local.create_production_edge ? 1 : 0

  project         = local.environment_project_id
  name            = "${local.prefix}-https"
  default_service = google_compute_backend_service.application[0].id
}

resource "google_certificate_manager_dns_authorization" "production" {
  count = local.create_production_edge ? 1 : 0

  project     = local.environment_project_id
  name        = "${local.prefix}-dns-authorization"
  description = "DNS authorization for ${var.production_domain}"
  domain      = var.production_domain
  location    = "global"
}

resource "google_certificate_manager_certificate" "production" {
  count = local.create_production_edge ? 1 : 0

  project     = local.environment_project_id
  name        = "${local.prefix}-certificate"
  description = "Google-managed certificate for ${var.production_domain}"
  location    = "global"

  managed {
    domains            = [var.production_domain]
    dns_authorizations = [google_certificate_manager_dns_authorization.production[0].id]
  }
}

resource "google_certificate_manager_certificate_map" "production" {
  count = local.create_production_edge ? 1 : 0

  project     = local.environment_project_id
  name        = "${local.prefix}-certificate-map"
  description = "Certificate map for the Line Item Watch production edge"
}

resource "google_certificate_manager_certificate_map_entry" "production" {
  count = local.create_production_edge ? 1 : 0

  project      = local.environment_project_id
  name         = "${local.prefix}-certificate-map-entry"
  map          = google_certificate_manager_certificate_map.production[0].name
  hostname     = var.production_domain
  certificates = [google_certificate_manager_certificate.production[0].id]
}

resource "google_compute_target_https_proxy" "production" {
  count = local.create_production_edge ? 1 : 0

  project         = local.environment_project_id
  name            = "${local.prefix}-https-proxy"
  url_map         = google_compute_url_map.https[0].id
  certificate_map = "//certificatemanager.googleapis.com/${google_certificate_manager_certificate_map.production[0].id}"
}

resource "google_compute_global_address" "production" {
  count = local.create_production_edge ? 1 : 0

  project = local.environment_project_id
  name    = "${local.prefix}-global-address"
}

resource "google_compute_global_forwarding_rule" "https" {
  count = local.create_production_edge ? 1 : 0

  project               = local.environment_project_id
  name                  = "${local.prefix}-https"
  ip_address            = google_compute_global_address.production[0].id
  port_range            = "443"
  target                = google_compute_target_https_proxy.production[0].id
  load_balancing_scheme = "EXTERNAL_MANAGED"
}

resource "google_compute_url_map" "http_redirect" {
  count = local.create_production_edge ? 1 : 0

  project = local.environment_project_id
  name    = "${local.prefix}-http-redirect"

  default_url_redirect {
    https_redirect         = true
    redirect_response_code = "MOVED_PERMANENTLY_DEFAULT"
    strip_query            = false
  }
}

resource "google_compute_target_http_proxy" "redirect" {
  count = local.create_production_edge ? 1 : 0

  project = local.environment_project_id
  name    = "${local.prefix}-http-redirect"
  url_map = google_compute_url_map.http_redirect[0].id
}

resource "google_compute_global_forwarding_rule" "http" {
  count = local.create_production_edge ? 1 : 0

  project               = local.environment_project_id
  name                  = "${local.prefix}-http"
  ip_address            = google_compute_global_address.production[0].id
  port_range            = "80"
  target                = google_compute_target_http_proxy.redirect[0].id
  load_balancing_scheme = "EXTERNAL_MANAGED"
}

resource "google_dns_record_set" "application" {
  count = local.create_production_edge && var.dns_zone_name != null ? 1 : 0

  project      = local.environment_project_id
  managed_zone = var.dns_zone_name
  name         = "${var.production_domain}."
  type         = "A"
  ttl          = 300
  rrdatas      = [google_compute_global_address.production[0].address]
}

resource "google_dns_record_set" "certificate_authorization" {
  count = local.create_production_edge && var.dns_zone_name != null ? 1 : 0

  project      = local.environment_project_id
  managed_zone = var.dns_zone_name
  name         = google_certificate_manager_dns_authorization.production[0].dns_resource_record[0].name
  type         = google_certificate_manager_dns_authorization.production[0].dns_resource_record[0].type
  ttl          = 300
  rrdatas      = [google_certificate_manager_dns_authorization.production[0].dns_resource_record[0].data]
}
