# Local development

## Repository status

The repository currently contains HubSpot project metadata, read-only feasibility probes, and documentation. There is no production application build or test command yet.

## Verified commands

Run from the repository root:

```sh
# Validate every tracked JSON document.
for file in $(git ls-files '*.json'); do jq empty "$file"; done

# Check documentation and source edits for whitespace errors.
git diff --check
```

The feasibility probes and their required environment variables are documented under [technical feasibility evidence](../../README.md#technical-feasibility-evidence). They made live provider reads during the accepted spike and are not routine P.0 checks. Do not run them without an approved test account and explicit reason; never store or print the access token.

The earlier template suggested HubSpot CLI development commands, but no production component or locally verified CLI workflow currently exists. Add commands here only after testing them in this repository. HubSpot project validation/upload/deployment must never be conflated: validation may be used when available and non-destructive; upload or deployment requires explicit authorization.

## Required reading before implementation

- [Business overview](../business/product-overview.md)
- [Private Beta scope](../business/beta-v1.md)
- [Architecture overview](../architecture/system-overview.md)
- [Coding guidelines](coding-guidelines.md)
- [Testing](testing.md)
- [Security overview](../trust/security-overview.md)
- repository root [`AGENTS.md`](../../AGENTS.md)

Runtime, dependency installation, database, application startup, and test-runner commands remain TBD until production implementation establishes and verifies them.
