# Local development

## Repository status

The repository contains HubSpot app project metadata, read-only feasibility probes, and one backend Maven module under `backend/`. The backend implements Platform Core identity/entitlements, HubSpot OAuth installation, encrypted refresh credentials, on-demand refresh, internal uninstall, the internal one-Deal `LINE_ITEM_WATCH` observation use case, a conditional authenticated webhook receiver, opt-in signal and reliability workers, tracked-scope reconciliation, provider-free replay/recovery, and a conditional signed Deal audit read endpoint. It exposes install/callback HTTP endpoints but no public baseline, business administration, recovery, or disconnect endpoint; reliability maintenance is service-operator CLI only.

## Prerequisites

- JDK 25
- Docker with Docker Compose

Maven 3.9.16 is supplied by `backend/mvnw`; a separately installed Maven is not required.

## Backend database and application

From the repository root, start the local-only PostgreSQL 18.6 service:

```sh
docker compose up -d postgres
docker compose ps
```

The Compose database publishes PostgreSQL only on the host loopback interface at `127.0.0.1:5433`; the tracked classpath `application-local.yml` uses the same port and matching, explicitly non-production credentials. Local launches run from `backend/`, so Spring Boot automatically loads the external profile-specific file `backend/config/application-local.yaml` when the `local` profile is active.

```sh
cd backend
cp config/application-local.example.yaml config/application-local.yaml
chmod 600 config/application-local.yaml
# Replace every placeholder in the ignored local file before OAuth operations.
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

The real local file is Git-ignored and must remain readable only by its developer (`chmod 600`). Its tracked sibling, `application-local.example.yaml`, contains only invalid placeholders and non-secret defaults. Environment variables remain supported and are the production configuration mechanism.

The preferred local launch is `SPRING_PROFILES_ACTIVE=local` with the tracked
JDBC datasource profile and the ignored file supplying only developer secrets
and intentional local overrides. If the datasource is overridden, use
Spring-compatible `spring.datasource.url`, username, and password settings, or
set `DATABASE_URL` to a JDBC URL together with the corresponding database
username and password variables. A CLI-style `postgres://` or `postgresql://`
URI is not interchangeable with the JDBC datasource URL accepted by this
application. Remove stale shell/task overrides instead of adding application
fallbacks for malformed local settings.

The local credential-encryption key must survive for as long as any local database credential encrypted with that key exists. Losing or changing the key requires controlled local credential re-establishment. Generating a different key under an existing key ID is invalid because the key ID identifies the encryption material used for the persisted ciphertext. Do not rely on ephemeral shell-only keys when local encrypted credentials outlive the shell session.

Begin installation at `http://localhost:8080/integrations/hubspot/oauth/install`. The HubSpot app configuration must contain the matching callback URL. `HUBSPOT_API_BASE_URL` and `HUBSPOT_AUTHORIZATION_BASE_URL` are optional test overrides; HTTPS is mandatory except for explicit localhost/loopback development endpoints. `HUBSPOT_CONNECT_TIMEOUT` and `HUBSPOT_READ_TIMEOUT` optionally override the bounded `5s` and `20s` defaults. Without the local profile, database variables are also mandatory. Never commit, print, or add local secrets to shell startup files.

Production secrets must remain external runtime configuration. Select a managed mechanism such as Vault, Azure Key Vault, Kubernetes Secrets, or an equivalent appropriate to the eventual hosting platform; the production choice remains TBD and production secret values must never be stored in repository configuration.

### Webhook receiver configuration

Webhook receipt is off by default. When off, no public URI is required and the controller is absent. Synthetic local tests use `https://webhook.example.test/integrations/hubspot/webhooks`; do not configure localhost HTTP as the canonical HubSpot URI.

To enable a deployed receiver, set both:

```text
HUBSPOT_WEBHOOK_ENABLED=true
HUBSPOT_WEBHOOK_PUBLIC_URI=https://<real-public-host>/integrations/hubspot/webhooks
```

The URI must be the exact absolute HTTPS target registered with HubSpot and may not contain user info, query, fragment, or a percent-encoded path. Do not commit the real host, a temporary tunnel, or a fake/placeholder target. Controlled P.4 genuine-delivery acceptance used separately authorized environment configuration; production deployment metadata remains outside this repository until a permanent target is selected.

### Signal-processing configuration

Processing is off by default and performs no HubSpot calls. Enable it only when the database should be consumed by this runtime:

```text
LINE_ITEM_WATCH_PROCESSING_ENABLED=true
```

Optional settings are `LINE_ITEM_WATCH_PROCESSING_POLL_DELAY` (`1s`), `LINE_ITEM_WATCH_PROCESSING_MAX_PER_POLL` (`50`), `LINE_ITEM_WATCH_PROCESSING_LEASE_DURATION` (`2m`), `LINE_ITEM_WATCH_PROCESSING_MAX_ATTEMPTS` (`8`), `LINE_ITEM_WATCH_PROCESSING_BASE_BACKOFF` (`5s`), and `LINE_ITEM_WATCH_PROCESSING_MAX_BACKOFF` (`15m`). A terminal failure blocks later signals only for its Line Item. Use the P.8 reliability operator CLI for bounded inspection and controlled compare-and-set requeue after correcting the cause; there is no public recovery API.

### Deal audit read configuration

The P.6 endpoint is off by default, so local startup needs no public read origin. Enable it only behind the exact HTTPS origin registered for HubSpot signed fetches:

```text
HUBSPOT_UI_EXTENSION_ENABLED=true
HUBSPOT_UI_EXTENSION_PUBLIC_BASE_URI=https://<exact-public-host>
HUBSPOT_UI_EXTENSION_APP_ID=<numeric-app-id>
LINE_ITEM_WATCH_AUDIT_CURSOR_ACTIVE_KEY_ID=<safe-key-id>
LINE_ITEM_WATCH_AUDIT_CURSOR_ACTIVE_KEY=<canonical-base64-32-byte-key>
```

The cursor key is dedicated integrity material; do not reuse the OAuth client secret or credential-encryption key. During rotation, set `LINE_ITEM_WATCH_AUDIT_CURSOR_PREVIOUS_KEY_ID` and `LINE_ITEM_WATCH_AUDIT_CURSOR_PREVIOUS_KEY` only for the short window in which already-open cards may hold old cursors. The endpoint can remain disabled without cursor-key configuration.

Migration `008` requires `pg_trgm`. PostgreSQL 18 classifies it as a trusted extension, but the migration role still needs database `CREATE` privilege unless a database/platform owner pre-provisions the extension. Confirm this before deployment; migration failure intentionally blocks name search rather than falling back to an unindexed production query.

The base URI must be an origin only: no path, user info, query, or fragment. The endpoint is `GET /api/v1/line-item-watch/deals/{dealId}/audit`. It is not a local shared-secret API and cannot be called successfully without a current HubSpot v3 signature over the configured public URI and signed metadata. Do not use `Host` or forwarding headers as signature configuration. No CORS wildcard, cookie credential, provider lookup, or local rate limiter is introduced; production edge controls remain deployment work.

### Deal App Card profile configuration

The app manifest and card runtime both reference the HubSpot project-profile variable `LINE_ITEM_WATCH_API_ORIGIN`. Its value must exactly match `HUBSPOT_UI_EXTENSION_PUBLIC_BASE_URI`: a canonical HTTPS origin with no trailing slash, path, credentials, query, fragment, or environment-specific API path. Create a local project profile with `hs project profile add <name> --target-account <account-id>`, then add the variable to `src/hsprofile.<name>.json`:

```json
{
  "accountId": 123456,
  "variables": {
    "LINE_ITEM_WATCH_API_ORIGIN": "https://<exact-public-origin>"
  }
}
```

The numeric account ID and origin above are structural examples only. Use an approved account and reachable environment origin, keep environment profile files out of commits unless their values are intentionally public, and never put credentials in profile variables. The manifest permits only `${LINE_ITEM_WATCH_API_ORIGIN}/api/v1/line-item-watch/deals/`; the card appends the validated Deal ID and `/audit`. For local UI development, use the HubSpot CLI's HTTPS-origin proxy mapping rather than weakening the manifest to permit localhost.

Install and verify the isolated frontend package from `src/app/cards` with `npm ci`, `npm run typecheck`, `npm run lint`, and `npm test`. Run `hs project lint` from the repository root. Run `hs project validate --profile <name>` only when that profile exists and contains the variable. Neither command uploads or deploys the project.

### Temporary Deal App Card live-acceptance runbook

This workflow is for explicitly authorized acceptance in an isolated HubSpot account. It is not a production hosting design. A Cloudflare quick tunnel has a temporary hostname that can change every time it starts; never commit that hostname or use the tunnel as a permanent deployment target.

In a repository with multiple Git worktrees, perform this preflight in the exact terminal that will start the backend and run HubSpot commands:

```sh
pwd
git branch --show-current
git rev-parse HEAD
```

The backend runtime, `hs project validate`, and `hs project upload` must all come from the same intended feature worktree. Stop if the path, branch, or commit is not the reviewed source.

1. Confirm the local PostgreSQL runtime and the required HubSpot OAuth and credential-encryption configuration are already available. Start the backend or allow startup migrations only with explicit authorization. The P.6 application flow is externally read-only, but its PostgreSQL `REPEATABLE_READ` transaction must not be marked database read-only because connection and entitlement validation acquires shared row locks. That lock capability protects authorization state; it does not turn the endpoint into a business-data write.
2. Start the temporary public origin and retain its generated HTTPS hostname:

   ```sh
   cloudflared tunnel --url http://127.0.0.1:8080
   ```

3. Create the ignored `src/hsprofile.acceptance.json` locally. It contains no credential; the repository's `/src/hsprofile.*.json` rule keeps it untracked.

   ```json
   {
     "accountId": "<TEST_ACCOUNT_ID>",
     "variables": {
       "LINE_ITEM_WATCH_API_ORIGIN": "https://<TEMPORARY_HOST>"
     }
   }
   ```

4. Start the backend from the repository root with the same canonical origin. Existing local profile configuration must separately supply the OAuth client secret and credential-encryption key; do not put either in the HubSpot project profile.

   ```sh
   SPRING_PROFILES_ACTIVE=local \
   HUBSPOT_UI_EXTENSION_ENABLED=true \
   HUBSPOT_UI_EXTENSION_APP_ID=<APP_ID> \
   HUBSPOT_UI_EXTENSION_PUBLIC_BASE_URI=https://<TEMPORARY_HOST> \
   LINE_ITEM_WATCH_AUDIT_CURSOR_ACTIVE_KEY_ID=<KEY_ID> \
   LINE_ITEM_WATCH_AUDIT_CURSOR_ACTIVE_KEY=<BASE64_32_BYTE_KEY> \
   ./backend/mvnw -f backend/pom.xml spring-boot:run
   ```

   `LINE_ITEM_WATCH_API_ORIGIN` and `HUBSPOT_UI_EXTENSION_PUBLIC_BASE_URI` must resolve to the same canonical HTTPS origin, without a path, query, fragment, credentials, or trailing slash.

5. Check local health without disclosing configuration:

   ```sh
   curl http://127.0.0.1:8080/actuator/health
   ```

6. Validate the resolved project before any upload:

   ```sh
   hs project validate --profile=acceptance
   ```

   Validation is read-only. Upload/deployment is a separate HubSpot mutation and requires explicit authorization:

   ```sh
   hs project upload --profile=acceptance
   ```

   HubSpot project upload auto-deploys the resulting build. A changed tunnel hostname changes the resolved `permittedUrls.fetch` value, so it requires another validation and separately authorized upload; editing only the ignored profile cannot update an already deployed build.

7. If the card is not already placed, the conceptual HubSpot path is **Settings → Data Management → Objects → Deals → Record Customization → Default view → Add middle-column card → Card library → Line Item Watch**. Layout placement is a HubSpot mutation and also requires explicit authorization.
8. Perform the manual checks in [Testing](testing.md). Do not change CRM records merely to manufacture audit events or pagination volume.

After acceptance, choose the HubSpot cleanup or rollback outcome before stopping infrastructure: a deployed build that still permits and calls the quick-tunnel origin will point to a dead hostname after the tunnel stops. Once that decision is intentional and authorized, stop the backend and `cloudflared`, remove the ignored `src/hsprofile.acceptance.json`, and verify with `git status --short` and `git ls-files src/hsprofile.acceptance.json` that no acceptance profile or origin became tracked. Removing the local profile alone does not change the deployed HubSpot build.

Run the complete backend verification suite with Docker available:

```sh
cd backend
./mvnw verify
```

The suite starts PostgreSQL 18.6 through Testcontainers, applies and upgrade-tests Liquibase migrations, validates with Hibernate, exercises OAuth/credential concurrency, checkpoint/signal/audit persistence and isolation, deterministic reconstruction and worker concurrency, webhook lifecycle races, signed-read authentication/API contracts and pagination, provider HTTP contracts, and ArchUnit rules. It does not call live HubSpot or use the Compose database. Guarded live P.3 harnesses and the provider-free retained P.4 evidence harness are documented in [Testing](testing.md). Controlled P.4 genuine-delivery acceptance is complete.

## Verified commands

Run from the repository root:

```sh
# Validate every tracked JSON document.
for file in $(git ls-files '*.json'); do jq empty "$file"; done

# Check documentation and source edits for whitespace errors.
git diff --check
```

The feasibility probes and their required environment variables are documented under [technical feasibility evidence](../../README.md#technical-feasibility-evidence). They made live provider reads during the accepted spike and are not routine P.0 checks. Do not run them without an approved test account and explicit reason; never store or print the access token.

The HubSpot app component is configured under `src/app`. HubSpot project validation/upload/deployment must never be conflated: validation is non-destructive, while upload auto-deploys a build and requires explicit authorization.

## Required reading before implementation

- [Business overview](../business/product-overview.md)
- [Private Beta scope](../business/beta-v1.md)
- [Architecture overview](../architecture/system-overview.md)
- [Coding guidelines](coding-guidelines.md)
- [Testing](testing.md)
- [Security overview](../trust/security-overview.md)
- repository root [`AGENTS.md`](../../AGENTS.md)

Production deployment and operations commands remain TBD.
