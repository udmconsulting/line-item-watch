#!/usr/bin/env python3
"""Classify a Terraform saved plan before Line Item Watch environment changes."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any


CONDITIONAL_MONITORING_PREFIXES = (
    "module.environment.google_monitoring_uptime_check_config.public_readiness",
    "module.environment.google_monitoring_alert_policy.public_availability",
    "module.environment.google_monitoring_alert_policy.http_5xx",
    "module.environment.google_monitoring_alert_policy.latency",
    "module.environment.google_monitoring_alert_policy.cloud_run_utilization",
    "module.environment.google_monitoring_alert_policy.cloud_sql",
    "module.environment.google_monitoring_alert_policy.custom_application",
)

LIFECYCLE_UPDATE_PREFIXES = (
    "module.environment.google_sql_database_instance.postgres",
    "module.environment.google_cloud_run_v2_service.application",
)

SHARED_IDENTIFIERS = (
    "udm-liw-bootstrap-01",
    "udm-liw-bootstrap-02",
    "udm-liw-tfstate-1007247511793",
)

VALID_RESOURCE_ACTIONS = {
    ("no-op",),
    ("read",),
    ("create",),
    ("update",),
    ("delete",),
    ("create", "delete"),
    ("delete", "create"),
}


class InvalidPlan(ValueError):
    """The input is JSON, but not a supported Terraform plan shape."""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("plan_json", type=Path)
    parser.add_argument("--environment", choices=("staging", "production"), required=True)
    parser.add_argument("--operation", choices=("plan", "park", "unpark"), required=True)
    parser.add_argument("--require-no-changes", action="store_true")
    return parser.parse_args()


def strings(value: Any):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for nested in value.values():
            yield from strings(nested)
    elif isinstance(value, list):
        for nested in value:
            yield from strings(nested)


def matches_address_prefix(address: str, prefixes: tuple[str, ...]) -> bool:
    return any(address == prefix or address.startswith(f"{prefix}[") for prefix in prefixes)


def contains_environment_marker(value: str, environment: str) -> bool:
    return bool(
        re.search(rf"(^|[^a-z0-9]){re.escape(environment)}([^a-z0-9]|$)", value.lower())
    )


def require_mapping(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise InvalidPlan(f"{label} must be an object")
    return value


def require_list(value: Any, label: str) -> list[Any]:
    if not isinstance(value, list):
        raise InvalidPlan(f"{label} must be an array")
    return value


def main() -> int:
    args = parse_args()
    try:
        plan = require_mapping(
            json.loads(args.plan_json.read_text(encoding="utf-8")), "plan"
        )
        variables = require_mapping(plan.get("variables", {}), "variables")
        resource_changes = require_list(
            plan.get("resource_changes", []), "resource_changes"
        )
        output_changes = require_mapping(
            plan.get("output_changes", {}), "output_changes"
        )
    except (OSError, json.JSONDecodeError, InvalidPlan) as error:
        print(f"INVALID PLAN JSON: {error}", file=sys.stderr)
        return 3

    additions = changes = destroys = replacements = 0
    findings: list[str] = []
    changed_addresses: list[str] = []
    changed_outputs: list[str] = []

    if args.operation in {"park", "unpark"}:
        if args.environment != "staging":
            findings.append(f"{args.operation} classification is staging-only")
        expected_parked = args.operation == "park"
        try:
            actual_parked = require_mapping(
                variables.get("staging_parked", {}), "variables.staging_parked"
            ).get("value")
            bootstrap_active = require_mapping(
                variables.get("database_bootstrap_active", {}),
                "variables.database_bootstrap_active",
            ).get("value")
        except InvalidPlan as error:
            print(f"INVALID PLAN JSON: {error}", file=sys.stderr)
            return 3
        if actual_parked is not expected_parked:
            findings.append(
                f"{args.operation} plan must set staging_parked={str(expected_parked).lower()}"
            )
        if bootstrap_active is not False:
            findings.append("lifecycle plan must set database_bootstrap_active=false")

    for index, raw_change in enumerate(resource_changes):
        try:
            change = require_mapping(raw_change, f"resource_changes[{index}]")
            change_body = require_mapping(
                change.get("change"), f"resource_changes[{index}].change"
            )
            actions = require_list(
                change_body.get("actions"),
                f"resource_changes[{index}].change.actions",
            )
            if not all(isinstance(action, str) for action in actions):
                raise InvalidPlan(
                    f"resource_changes[{index}].change.actions must contain strings"
                )
            action_tuple = tuple(actions)
            if action_tuple not in VALID_RESOURCE_ACTIONS:
                raise InvalidPlan(
                    f"resource_changes[{index}] has unsupported actions: {actions}"
                )
        except InvalidPlan as error:
            print(f"INVALID PLAN JSON: {error}", file=sys.stderr)
            return 3

        address = str(change.get("address", "<unknown>"))
        resource_type = str(change.get("type", ""))
        if actions in (["no-op"], ["read"]):
            continue
        changed_addresses.append(address)

        has_create = "create" in actions
        has_update = "update" in actions
        has_delete = "delete" in actions
        if has_create:
            additions += 1
        if has_update:
            changes += 1
        if has_delete:
            destroys += 1
        if has_create and has_delete:
            replacements += 1
            findings.append(f"replacement forbidden: {address}")

        if args.operation in {"park", "unpark"}:
            if has_create and not (
                args.operation == "unpark"
                and matches_address_prefix(address, CONDITIONAL_MONITORING_PREFIXES)
            ):
                findings.append(f"unexpected create for {args.operation}: {address}")
            if has_update and not matches_address_prefix(
                address, LIFECYCLE_UPDATE_PREFIXES
            ):
                findings.append(f"unexpected update for {args.operation}: {address}")

        if has_delete:
            if resource_type == "google_project":
                findings.append(f"environment project deletion forbidden: {address}")
            elif args.operation != "park" or not matches_address_prefix(
                address, CONDITIONAL_MONITORING_PREFIXES
            ):
                findings.append(f"unexpected destroy for {args.operation}: {address}")

        if args.environment == "staging" and "production" in address.lower():
            findings.append(f"cross-environment address in staging plan: {address}")
        if args.environment == "production" and "staging" in address.lower():
            findings.append(f"cross-environment address in production plan: {address}")

        value_strings = set(strings(change_body))
        other_environment = "production" if args.environment == "staging" else "staging"
        for value in value_strings:
            for identifier in SHARED_IDENTIFIERS:
                if identifier in value:
                    findings.append(
                        f"shared/bootstrap identifier touched by {address}: {identifier}"
                    )
            if contains_environment_marker(value, other_environment):
                findings.append(
                    f"{other_environment} value in {args.environment} change: {address}"
                )

    for name, raw_output in output_changes.items():
        try:
            output = require_mapping(raw_output, f"output_changes.{name}")
            output_actions = require_list(
                output.get("actions"), f"output_changes.{name}.actions"
            )
        except InvalidPlan as error:
            print(f"INVALID PLAN JSON: {error}", file=sys.stderr)
            return 3
        if output_actions not in (["no-op"], ["read"]):
            changed_outputs.append(name)
            if args.operation in {"park", "unpark"}:
                findings.append(
                    f"unexpected output change for {args.operation}: {name}"
                )

    print(
        f"Plan classification: {additions} to add, {changes} to change, "
        f"{destroys} to destroy, {replacements} replacement(s)"
    )
    if changed_addresses:
        print("Changed resource addresses:")
        for address in changed_addresses:
            print(f"  - {address}")
    else:
        print("Changed resource addresses: NONE")

    if changed_outputs:
        print("Changed outputs:")
        for name in changed_outputs:
            print(f"  - {name}")
    else:
        print("Changed outputs: NONE")

    if args.require_no_changes and (changed_addresses or changed_outputs):
        findings.append("post-apply plan must contain no resource or output changes")

    if findings:
        print("UNSAFE PLAN:", file=sys.stderr)
        for finding in sorted(set(findings)):
            print(f"  - {finding}", file=sys.stderr)
        return 2

    print("Safety classification: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
