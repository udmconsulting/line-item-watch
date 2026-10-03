terraform {
  required_version = "= 1.13.5"

  backend "gcs" {
    bucket = "udm-liw-tfstate-1007247511793"
    prefix = "line-item-watch/production"
  }

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "= 8.5.0"
    }
  }
}

provider "google" {
  region = var.region
}
