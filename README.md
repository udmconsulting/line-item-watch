# HubSpot Getting Started Project Template

This is the Getting Started project for HubSpot developer projects. It contains a private app, a CRM card written in React, and a serverless function that the CRM card is able to interact with. This code is intended to help developers get up and running with developer projects quickly and easily.

## Requirements

There are a few things that must be set up before you can make use of this getting started project.

- You must have an active HubSpot account.
- You must have the [HubSpot CLI](https://www.npmjs.com/package/@hubspot/cli) installed and set up.
- You must have access to developer projects.

## Usage

The HubSpot CLI enables you to run this project locally so that you may test and iterate quickly. Getting started is simple, just run this HubSpot CLI command in your project directory and follow the prompts:

`hs project dev`

## Technical feasibility spike

Gate 1 is a read-only probe that verifies whether HubSpot exposes the current
commercial properties and Deal associations for line items. It does not modify
HubSpot data.

The probe requires these environment variables:

- `HUBSPOT_ACCESS_TOKEN`: the static app access token. Never store it in this
  repository.
- `HUBSPOT_DEAL_ID`: the ACME test Deal record ID.

After setting both variables in the current shell, run:

```sh
node spike/read-line-items.mjs
```

The command prints a concise summary followed by machine-readable JSON with the
discovered property definitions, selected commercial property values, line-item
IDs, and Deal associations.

Gate 1 passes only when the output contains the Enterprise Licence line item,
its current quantity, unit price, and discount, a clear association back to the
ACME Deal, and the available commercial and billing fields exposed by the test
account.
