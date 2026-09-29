import {
  Divider,
  EmptyState,
  Flex,
  Heading,
  LoadingSpinner,
  Text,
} from "@hubspot/ui-extensions";
import type { ExtensionPointApiContext } from "@hubspot/ui-extensions";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  createTranslator,
  resolveFormatLocale,
  resolveMessageLanguage,
  type TranslationLogger,
} from "../i18n";
import {
  createDealAuditClient,
  DealAuditClientError,
  type DealAuditClient,
  type Fetcher,
  type RequestSection,
} from "../lib/client";
import type {
  AuditEvent,
  DealAuditResponse,
  LineItemSummary,
} from "../lib/contracts";
import { createValueFormatter, resolveTimeZone } from "../lib/formatting";
import { ErrorView } from "./ErrorView";
import { EventsSection } from "./EventsSection";
import { LineItemsSection } from "./LineItemsSection";

export type DealCardContext = ExtensionPointApiContext<"crm.record.tab">;

interface DealAuditCardProps {
  readonly context: DealCardContext;
  readonly fetcher: Fetcher;
  readonly copyText: (value: string) => void;
  readonly logMissingTranslation?: TranslationLogger;
}

type InitialState =
  | { readonly status: "loading" }
  | { readonly status: "error"; readonly error: DealAuditClientError }
  | { readonly status: "ready"; readonly data: DealAuditResponse };

interface SectionLoadState {
  readonly loading: boolean;
  readonly failed: boolean;
}

const idleSection: SectionLoadState = { loading: false, failed: false };

function asClientError(error: unknown): DealAuditClientError {
  return error instanceof DealAuditClientError
    ? error
    : new DealAuditClientError("MALFORMED_RESPONSE");
}

function appendUnique<T>(
  current: readonly T[],
  incoming: readonly T[],
  identity: (item: T) => string,
): T[] {
  const seen = new Set(current.map(identity));
  const uniqueIncoming = incoming.filter((item) => {
    const key = identity(item);
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
  return [...current, ...uniqueIncoming];
}

export function DealAuditCard({
  context,
  fetcher,
  copyText,
  logMissingTranslation,
}: DealAuditCardProps) {
  const language = resolveMessageLanguage(context.user.language);
  const translate = useMemo(
    () => createTranslator(language, logMissingTranslation),
    [language, logMissingTranslation],
  );
  const formatter = useMemo(
    () =>
      createValueFormatter(
        resolveFormatLocale(context.user.locale),
        resolveTimeZone(context.portal.timezone),
        language,
      ),
    [context.portal.timezone, context.user.locale, language],
  );
  const [attempt, setAttempt] = useState(0);
  const [initial, setInitial] = useState<InitialState>({ status: "loading" });
  const [lineItemsLoad, setLineItemsLoad] =
    useState<SectionLoadState>(idleSection);
  const [eventsLoad, setEventsLoad] = useState<SectionLoadState>(idleSection);
  const clientRef = useRef<DealAuditClient | null>(null);
  const generationRef = useRef(0);
  const lineItemsRequestRef = useRef(0);
  const eventsRequestRef = useRef(0);
  const lineItemsInFlightRef = useRef(false);
  const eventsInFlightRef = useRef(false);

  useEffect(() => {
    const generation = ++generationRef.current;
    ++lineItemsRequestRef.current;
    ++eventsRequestRef.current;
    lineItemsInFlightRef.current = false;
    eventsInFlightRef.current = false;
    clientRef.current = null;
    setInitial({ status: "loading" });
    setLineItemsLoad(idleSection);
    setEventsLoad(idleSection);

    let client: DealAuditClient;
    try {
      client = createDealAuditClient(
        context.variables?.LINE_ITEM_WATCH_API_ORIGIN,
        context.crm.objectId,
        fetcher,
      );
      clientRef.current = client;
    } catch (error) {
      setInitial({ status: "error", error: asClientError(error) });
      return;
    }

    void client
      .read()
      .then((data) => {
        if (generation === generationRef.current)
          setInitial({ status: "ready", data });
      })
      .catch((error: unknown) => {
        if (generation === generationRef.current) {
          setInitial({ status: "error", error: asClientError(error) });
        }
      });
  }, [attempt, context.crm.objectId, context.variables, fetcher]);

  const loadMore = useCallback(
    (section: Exclude<RequestSection, "initial">) => {
      const client = clientRef.current;
      if (!client || initial.status !== "ready") return;
      const currentLoad = section === "lineItems" ? lineItemsLoad : eventsLoad;
      const inFlightRef =
        section === "lineItems" ? lineItemsInFlightRef : eventsInFlightRef;
      const page =
        section === "lineItems"
          ? initial.data.lineItems.page
          : initial.data.events.page;
      if (
        inFlightRef.current ||
        currentLoad.loading ||
        !page.hasMore ||
        !page.nextCursor
      )
        return;

      const generation = generationRef.current;
      const requestRef =
        section === "lineItems" ? lineItemsRequestRef : eventsRequestRef;
      const request = ++requestRef.current;
      const setLoad =
        section === "lineItems" ? setLineItemsLoad : setEventsLoad;
      inFlightRef.current = true;
      setLoad({ loading: true, failed: false });

      void client
        .read(section, page.nextCursor)
        .then((next) => {
          if (
            generation !== generationRef.current ||
            request !== requestRef.current
          )
            return;
          setInitial((current) => {
            if (current.status !== "ready") return current;
            if (section === "lineItems") {
              return {
                status: "ready",
                data: {
                  ...current.data,
                  lineItems: {
                    items: appendUnique<LineItemSummary>(
                      current.data.lineItems.items,
                      next.lineItems.items,
                      (item) => item.lineItemId,
                    ),
                    page: next.lineItems.page,
                  },
                },
              };
            }
            return {
              status: "ready",
              data: {
                ...current.data,
                events: {
                  items: appendUnique<AuditEvent>(
                    current.data.events.items,
                    next.events.items,
                    (item) => item.eventId,
                  ),
                  page: next.events.page,
                },
              },
            };
          });
          inFlightRef.current = false;
          setLoad(idleSection);
        })
        .catch(() => {
          if (
            generation === generationRef.current &&
            request === requestRef.current
          ) {
            inFlightRef.current = false;
            setLoad({ loading: false, failed: true });
          }
        });
    },
    [eventsLoad, initial, lineItemsLoad],
  );

  if (initial.status === "loading") {
    return <LoadingSpinner label={translate("common.initialLoading")} />;
  }
  if (initial.status === "error") {
    return (
      <ErrorView
        error={initial.error}
        translate={translate}
        onRetry={() => setAttempt((value) => value + 1)}
        onCopy={copyText}
      />
    );
  }

  const { data } = initial;
  const entirelyEmpty =
    data.lineItems.items.length === 0 && data.events.items.length === 0;
  return (
    <Flex direction="column" gap="md">
      <Flex direction="column" gap="xs">
        <Heading>{translate("common.title")}</Heading>
        <Text>{translate("common.intro")}</Text>
      </Flex>
      {entirelyEmpty ? (
        <EmptyState title={translate("common.noHistoryTitle")}>
          <Text>{translate("common.noHistoryDescription")}</Text>
        </EmptyState>
      ) : (
        <>
          <LineItemsSection
            items={data.lineItems.items}
            page={data.lineItems.page}
            formatter={formatter}
            translate={translate}
            loadingMore={lineItemsLoad.loading}
            paginationFailed={lineItemsLoad.failed}
            onLoadMore={() => loadMore("lineItems")}
          />
          <Divider />
          <EventsSection
            items={data.events.items}
            lineItems={data.lineItems.items}
            page={data.events.page}
            formatter={formatter}
            translate={translate}
            loadingMore={eventsLoad.loading}
            paginationFailed={eventsLoad.failed}
            onLoadMore={() => loadMore("events")}
          />
        </>
      )}
    </Flex>
  );
}
