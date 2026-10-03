# Subprocessor register

Infrastructure and SaaS vendors that process customer data may be subprocessors. Record only selected and actually used vendors; do not add candidates to make the register appear complete.

| Vendor | Service / processing purpose | Data categories | Processing / data region | Status / effective or review date |
|---|---|---|---|---|
| Google Cloud | Selected hosting, managed PostgreSQL, secrets, artifact, logging, monitoring, and Terraform-state platform | Bootstrap Terraform metadata today; application/customer records, encrypted credentials, backups, operational metadata, and container artifacts only after environment provisioning | `europe-west1` for the state bucket and future regional resources; global production load balancer/DNS edge metadata | Selected in P.9 on 2026-10-01; bootstrap project/state bucket active on 2026-10-02 with no customer workload; legal/security review required before customer-data use |

## Maintenance rules

- Review a vendor's purpose, data access, region, security terms, deletion, and contractual role before integration.
- Update this register, the data inventory, security documentation, and customer-facing list in the same task that introduces or changes a relevant vendor.
- Record service and region changes with an effective/review date.
- Do not treat the external business platform itself, a controller, or a subprocessor as interchangeable legal roles without review.

Google Cloud already stores infrastructure state metadata in the protected bootstrap bucket, but is not yet an active customer-data subprocessor because neither staging nor production exists and no customer workload is hosted there. Contractual/security review and the customer-facing register must be completed before customer-data use. Separate error tracking, email, and billing vendors remain TBD.
