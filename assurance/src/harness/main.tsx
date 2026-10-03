import {
  RenderedNodeType,
  createRenderer,
  type RenderedChildNode,
  type RenderedElementNode,
  type RenderedFragmentNode,
} from "@hubspot/ui-extensions/testing";
import { DealAuditCard } from "../../../src/app/cards/components/DealAuditCard";
import {
  context as baseContext,
  event,
  jsonResponse,
  lineItem,
  page,
  response,
} from "../../../src/app/cards/test/fixtures";
import type { Fetcher } from "../../../src/app/cards/lib/client";

const parameters = new URLSearchParams(window.location.search);
const scenario = parameters.get("scenario") ?? "healthy";
const language = parameters.get("language") === "hu" ? "hu" : "en";
const context = {
  ...baseContext,
  user: {
    ...baseContext.user,
    language,
    locale: language === "hu" ? "hu-HU" : "en-US",
  },
};

const primary = lineItem("2002", "Enterprise support");
const secondary = lineItem("2003", "Implementation services");
const healthyResponse = response(
  [primary, secondary],
  [event("first", "2002")],
  page(10, true, "line-items-page-2"),
  page(20, true, "events-page-2"),
);

const initialResponse =
  scenario === "warning"
    ? {
        ...healthyResponse,
        reliability: {
          ...healthyResponse.reliability,
          coverageState: "POSSIBLE_GAP" as const,
          possibleGapSince: "2026-09-28T10:00:00Z",
        },
      }
    : healthyResponse;

const fetcher: Fetcher = async (url) => {
  await new Promise((resolve) => setTimeout(resolve, 120));
  const request = new URL(url);
  if (scenario === "error") {
    return jsonResponse(
      {
        error: {
          code: "INTERNAL_ERROR",
          correlationId: "8bd0d958-d3db-4214-b235-99fbcf70a812",
        },
      },
      500,
    );
  }
  const lineItemSearch = request.searchParams.get("lineItemSearch");
  const lineItemId = request.searchParams.get("lineItemId");
  if (request.searchParams.has("lineItemsCursor")) {
    return jsonResponse(
      response(
        [lineItem("2004", "Training")],
        [],
        page(10),
        page(0),
      ),
    );
  }
  if (request.searchParams.has("eventsCursor")) {
    return jsonResponse(response([], [event("second")], page(0), page(20)));
  }
  if (lineItemSearch) {
    const matches = [primary, secondary].filter((item) =>
      String(item.latest.name.value)
        .toLowerCase()
        .includes(lineItemSearch.toLowerCase()),
    );
    return jsonResponse(response(matches, [event()], page(10), page(20)));
  }
  if (lineItemId) {
    return jsonResponse(response([primary, secondary], [event()], page(10), page(20)));
  }
  return jsonResponse(initialResponse);
};

const renderer = createRenderer("crm.record.tab");
renderer.render(
  <DealAuditCard
    context={context}
    fetcher={fetcher}
    copyText={(value) => navigator.clipboard?.writeText(value)}
  />,
);

const appElement = document.querySelector<HTMLDivElement>("#app");
if (!appElement) throw new Error("Missing assurance root");
const app: HTMLDivElement = appElement;

function textOf(node: RenderedElementNode): string {
  return node.text ?? "";
}

function fragmentChildren(value: unknown): readonly RenderedChildNode[] {
  if (
    value &&
    typeof value === "object" &&
    "nodeType" in value &&
    value.nodeType === RenderedNodeType.Fragment
  ) {
    return (value as RenderedFragmentNode).childNodes;
  }
  return [];
}

function renderChildren(parent: HTMLElement, children: readonly RenderedChildNode[]) {
  for (const child of children) parent.append(renderNode(child));
}

function interactive(
  element: HTMLElement,
  node: RenderedElementNode,
  event: string,
  argument?: () => unknown,
) {
  element.addEventListener(event, () => {
    node.trigger("onChange" as never, argument?.() as never);
    queueMicrotask(renderLatest);
  });
}

function renderNode(node: RenderedChildNode): Node {
  if (node.nodeType === RenderedNodeType.Text) {
    return document.createTextNode(node.text);
  }
  const props = node.props as Record<string, unknown>;
  if (node.name === "Heading") {
    const heading = document.createElement("h2");
    heading.textContent = textOf(node);
    return heading;
  }
  if (node.name === "Text") {
    const text = document.createElement("p");
    text.textContent = textOf(node);
    return text;
  }
  if (node.name === "Button" || node.name === "LoadingButton") {
    const button = document.createElement("button");
    button.textContent = textOf(node);
    button.disabled = Boolean(props.loading);
    button.addEventListener("click", () => {
      node.trigger("onClick" as never);
      queueMicrotask(renderLatest);
    });
    return button;
  }
  if (node.name === "SearchInput" || node.name === "Input") {
    const label = document.createElement("label");
    label.append(String(props.label ?? ""));
    const input = document.createElement("input");
    input.type = node.name === "SearchInput" ? "search" : "text";
    input.name = String(props.name ?? "");
    input.value = String(props.value ?? "");
    input.placeholder = String(props.placeholder ?? "");
    interactive(input, node, "input", () => input.value);
    label.append(input);
    return label;
  }
  if (node.name === "Select") {
    const label = document.createElement("label");
    label.append(String(props.label ?? ""));
    const select = document.createElement("select");
    select.name = String(props.name ?? "");
    const options = (props.options ?? []) as Array<{ label: string; value: string }>;
    for (const option of options) {
      const item = document.createElement("option");
      item.value = option.value;
      item.textContent = option.label;
      item.selected = option.value === props.value;
      select.append(item);
    }
    interactive(select, node, "change", () => select.value);
    label.append(select);
    return label;
  }
  if (node.name === "Accordion") {
    const details = document.createElement("details");
    details.open = true;
    const summary = document.createElement("summary");
    summary.textContent = String(props.title ?? "");
    details.append(summary);
    renderChildren(details, node.childNodes);
    return details;
  }
  if (node.name === "Alert" || node.name === "ErrorState" || node.name === "EmptyState") {
    const section = document.createElement("section");
    if (node.name === "Alert" || node.name === "ErrorState") section.role = "alert";
    const title = document.createElement("h3");
    title.textContent = String(props.title ?? "");
    section.append(title);
    renderChildren(section, [...fragmentChildren(props.children), ...node.childNodes]);
    return section;
  }
  if (node.name === "LoadingSpinner") {
    const status = document.createElement("div");
    status.role = "status";
    status.textContent = String(props.label ?? "Loading");
    return status;
  }
  if (node.name === "Divider") return document.createElement("hr");
  if (node.name === "StatusTag") {
    const tag = document.createElement("span");
    tag.className = "status";
    tag.textContent = textOf(node);
    return tag;
  }
  const container = document.createElement(node.name === "Flex" ? "div" : "section");
  if (props.direction === "row") container.className = "row";
  renderChildren(container, node.childNodes);
  return container;
}

let lastTree = "";

function renderLatest() {
  const root = renderer.getRootNode();
  const nextTree = root.toString();
  if (nextTree === lastTree) return;
  lastTree = nextTree;
  app.replaceChildren();
  renderChildren(app, root.childNodes);
}

renderLatest();
window.setInterval(renderLatest, 50);
