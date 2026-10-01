import {
  Alert,
  Button,
  DateInput,
  Divider,
  EmptyState,
  Flex,
  Heading,
  Input,
  LoadingButton,
  LoadingSpinner,
  SearchInput,
  Select,
  StatusTag,
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
  type DealAuditRequestQuery,
  type Fetcher,
  type RequestSection,
} from "../lib/client";
import {
  AUDIT_EVENT_TYPES,
  MONITORED_PROPERTIES,
  type AuditEvent,
  type AuditEventType,
  type DealAuditResponse,
  type LineItemSummary,
  type MonitoredProperty,
} from "../lib/contracts";
import {
  createValueFormatter,
  nextCalendarDate,
  portalDateStartInstant,
  resolveTimeZone,
  type CalendarDate,
} from "../lib/formatting";
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
  readonly requiresRestart: boolean;
}

interface FilterDraft {
  readonly lineItemSearch: string;
  readonly eventType: "" | AuditEventType;
  readonly field: "" | MonitoredProperty;
  readonly lineItemId: string;
  readonly from: CalendarDate | null;
  readonly through: CalendarDate | null;
}

interface AppliedFilters {
  readonly request: DealAuditRequestQuery;
  readonly lineItemSearch?: string;
  readonly eventType?: AuditEventType;
  readonly field?: MonitoredProperty;
  readonly lineItemId?: string;
  readonly from?: CalendarDate;
  readonly through?: CalendarDate;
}

const MAX_LINE_ITEMS = 50;
const MAX_EVENTS = 100;
const idleSection: SectionLoadState = {
  loading: false,
  failed: false,
  requiresRestart: false,
};
const emptyDraft: FilterDraft = {
  lineItemSearch: "",
  eventType: "",
  field: "",
  lineItemId: "",
  from: null,
  through: null,
};
const emptyFilters: AppliedFilters = { request: {} };

function asClientError(error: unknown): DealAuditClientError {
  return error instanceof DealAuditClientError
    ? error
    : new DealAuditClientError("MALFORMED_RESPONSE");
}

function appendUnique<T>(
  current: readonly T[],
  incoming: readonly T[],
  identity: (item: T) => string,
  limit: number,
): T[] {
  const seen = new Set(current.map(identity));
  const result = [...current];
  for (const item of incoming) {
    const key = identity(item);
    if (!seen.has(key) && result.length < limit) {
      seen.add(key);
      result.push(item);
    }
  }
  return result;
}

function dateLabel(value: CalendarDate): string {
  return `${value.year}-${String(value.month).padStart(2, "0")}-${String(value.date).padStart(2, "0")}`;
}

function toDateInput(value: CalendarDate) {
  return { year: value.year, month: value.month - 1, date: value.date };
}

function fromDateInput(value: { year: number; month: number; date: number }) {
  return { year: value.year, month: value.month + 1, date: value.date };
}

function hasEventFilters(filters: AppliedFilters): boolean {
  return Boolean(
    filters.eventType ||
    filters.field ||
    filters.lineItemId ||
    filters.from ||
    filters.through,
  );
}

function draftFromApplied(filters: AppliedFilters): FilterDraft {
  return {
    lineItemSearch: filters.lineItemSearch ?? "",
    eventType: filters.eventType ?? "",
    field: filters.field ?? "",
    lineItemId: filters.lineItemId ?? "",
    from: filters.from ?? null,
    through: filters.through ?? null,
  };
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
  const timeZone = resolveTimeZone(context.portal.timezone);
  const formatter = useMemo(
    () =>
      createValueFormatter(
        resolveFormatLocale(context.user.locale),
        timeZone,
        language,
      ),
    [context.user.locale, language, timeZone],
  );
  const [initial, setInitial] = useState<InitialState>({ status: "loading" });
  const [draft, setDraft] = useState<FilterDraft>(emptyDraft);
  const [applied, setApplied] = useState<AppliedFilters>(emptyFilters);
  const [validation, setValidation] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [refreshFailed, setRefreshFailed] = useState(false);
  const [lastRefreshed, setLastRefreshed] = useState<Date | null>(null);
  const [lineItemsLoad, setLineItemsLoad] =
    useState<SectionLoadState>(idleSection);
  const [eventsLoad, setEventsLoad] = useState<SectionLoadState>(idleSection);
  const clientRef = useRef<DealAuditClient | null>(null);
  const appliedRef = useRef<AppliedFilters>(emptyFilters);
  const generationRef = useRef(0);
  const lineItemsRequestRef = useRef(0);
  const eventsRequestRef = useRef(0);
  const lineItemsInFlightRef = useRef(false);
  const eventsInFlightRef = useRef(false);

  const readFirstPage = useCallback(
    (filters: AppliedFilters, preserve: boolean) => {
      const client = clientRef.current;
      if (!client) return;
      const generation = ++generationRef.current;
      ++lineItemsRequestRef.current;
      ++eventsRequestRef.current;
      lineItemsInFlightRef.current = false;
      eventsInFlightRef.current = false;
      setLineItemsLoad(idleSection);
      setEventsLoad(idleSection);
      setRefreshFailed(false);
      if (preserve) setRefreshing(true);
      else setInitial({ status: "loading" });

      void client
        .read("initial", undefined, filters.request)
        .then((data) => {
          if (generation !== generationRef.current) return;
          setInitial({ status: "ready", data });
          setLastRefreshed(new Date());
          setRefreshing(false);
        })
        .catch((error: unknown) => {
          if (generation !== generationRef.current) return;
          setRefreshing(false);
          if (preserve) setRefreshFailed(true);
          else setInitial({ status: "error", error: asClientError(error) });
        });
    },
    [],
  );

  useEffect(() => {
    ++generationRef.current;
    clientRef.current = null;
    setInitial({ status: "loading" });
    setDraft(emptyDraft);
    setApplied(emptyFilters);
    appliedRef.current = emptyFilters;
    setValidation(null);
    setRefreshing(false);
    setRefreshFailed(false);
    setLastRefreshed(null);
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
    readFirstPage(emptyFilters, false);
    const generation = generationRef;
    const currentClient = clientRef;
    return () => {
      ++generation.current;
      currentClient.current = null;
    };
  }, [context.crm.objectId, context.variables, fetcher, readFirstPage]);

  const applyFilters = useCallback(
    (nextDraft: FilterDraft) => {
      const search = nextDraft.lineItemSearch.trim();
      if (
        search &&
        (Array.from(search).length < 2 || Array.from(search).length > 100)
      ) {
        setValidation(translate("common.searchValidation"));
        return;
      }
      const lineItemId = nextDraft.lineItemId.trim();
      if (lineItemId && !/^[1-9][0-9]{0,254}$/.test(lineItemId)) {
        setValidation(translate("common.lineItemIdValidation"));
        return;
      }
      try {
        const from = nextDraft.from
          ? portalDateStartInstant(nextDraft.from, timeZone)
          : undefined;
        const to = nextDraft.through
          ? portalDateStartInstant(
              nextCalendarDate(nextDraft.through),
              timeZone,
            )
          : undefined;
        if (from && to && from >= to) {
          setValidation(translate("common.dateRangeValidation"));
          return;
        }
        const eventType = nextDraft.field
          ? "PROPERTY_CHANGED"
          : nextDraft.eventType || undefined;
        const filters: AppliedFilters = {
          request: {
            ...(search ? { lineItemSearch: search } : {}),
            ...(eventType ? { eventType } : {}),
            ...(nextDraft.field ? { field: nextDraft.field } : {}),
            ...(lineItemId ? { lineItemId } : {}),
            ...(from ? { from } : {}),
            ...(to ? { to } : {}),
          },
          ...(search ? { lineItemSearch: search } : {}),
          ...(nextDraft.eventType ? { eventType: nextDraft.eventType } : {}),
          ...(nextDraft.field ? { field: nextDraft.field } : {}),
          ...(lineItemId ? { lineItemId } : {}),
          ...(nextDraft.from ? { from: nextDraft.from } : {}),
          ...(nextDraft.through ? { through: nextDraft.through } : {}),
        };
        setValidation(null);
        setDraft({ ...nextDraft, lineItemSearch: search, lineItemId });
        setApplied(filters);
        appliedRef.current = filters;
        readFirstPage(filters, true);
      } catch {
        setValidation(translate("common.dateRangeValidation"));
      }
    },
    [readFirstPage, timeZone, translate],
  );

  const removeFilter = useCallback(
    (name: keyof Omit<AppliedFilters, "request">) => {
      const next: FilterDraft = {
        ...draftFromApplied(applied),
        ...(name === "lineItemSearch" ? { lineItemSearch: "" } : {}),
        ...(name === "eventType" ? { eventType: "" as const } : {}),
        ...(name === "field" ? { field: "" as const } : {}),
        ...(name === "lineItemId" ? { lineItemId: "" } : {}),
        ...(name === "from" ? { from: null } : {}),
        ...(name === "through" ? { through: null } : {}),
      };
      applyFilters(next);
    },
    [applied, applyFilters],
  );

  const loadMore = useCallback(
    (section: Exclude<RequestSection, "initial">) => {
      const client = clientRef.current;
      if (!client || initial.status !== "ready" || refreshing) return;
      const currentLoad = section === "lineItems" ? lineItemsLoad : eventsLoad;
      const inFlightRef =
        section === "lineItems" ? lineItemsInFlightRef : eventsInFlightRef;
      const page =
        section === "lineItems"
          ? initial.data.lineItems.page
          : initial.data.events.page;
      const itemCount =
        section === "lineItems"
          ? initial.data.lineItems.items.length
          : initial.data.events.items.length;
      const cap = section === "lineItems" ? MAX_LINE_ITEMS : MAX_EVENTS;
      if (
        inFlightRef.current ||
        currentLoad.loading ||
        itemCount >= cap ||
        !page.hasMore ||
        !page.nextCursor
      )
        return;

      const cursor = page.nextCursor;
      const generation = generationRef.current;
      const requestRef =
        section === "lineItems" ? lineItemsRequestRef : eventsRequestRef;
      const request = ++requestRef.current;
      const setLoad =
        section === "lineItems" ? setLineItemsLoad : setEventsLoad;
      inFlightRef.current = true;
      setLoad({ ...idleSection, loading: true });

      void client
        .read(section, cursor, appliedRef.current.request)
        .then((next) => {
          if (
            generation !== generationRef.current ||
            request !== requestRef.current
          )
            return;
          const nextPage =
            section === "lineItems" ? next.lineItems.page : next.events.page;
          if (nextPage.hasMore && nextPage.nextCursor === cursor) {
            throw new DealAuditClientError("MALFORMED_RESPONSE");
          }
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
                      MAX_LINE_ITEMS,
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
                    MAX_EVENTS,
                  ),
                  page: next.events.page,
                },
              },
            };
          });
          inFlightRef.current = false;
          setLoad(idleSection);
        })
        .catch((error: unknown) => {
          if (
            generation === generationRef.current &&
            request === requestRef.current
          ) {
            inFlightRef.current = false;
            const clientError = asClientError(error);
            setLoad({
              loading: false,
              failed: true,
              requiresRestart: clientError.code === "INVALID_REQUEST",
            });
          }
        });
    },
    [eventsLoad, initial, lineItemsLoad, refreshing],
  );

  if (initial.status === "loading") {
    return <LoadingSpinner label={translate("common.initialLoading")} />;
  }
  if (initial.status === "error") {
    return (
      <ErrorView
        error={initial.error}
        translate={translate}
        onRetry={() => readFirstPage(appliedRef.current, false)}
        onCopy={copyText}
      />
    );
  }

  const { data } = initial;
  const entirelyEmpty =
    data.lineItems.items.length === 0 &&
    data.events.items.length === 0 &&
    !applied.lineItemSearch &&
    !hasEventFilters(applied);
  const activeFilterCount = [
    applied.lineItemSearch,
    applied.eventType,
    applied.field,
    applied.lineItemId,
    applied.from,
    applied.through,
  ].filter(Boolean).length;
  const restartResults = () => readFirstPage(appliedRef.current, true);
  return (
    <Flex direction="column" gap="md">
      <Flex direction="column" gap="xs">
        <Heading>{translate("common.title")}</Heading>
        <Text>{translate("common.intro")}</Text>
      </Flex>
      <Flex direction="column" gap="sm">
        <Heading>{translate("common.filters")}</Heading>
        <SearchInput
          label={translate("common.searchLabel")}
          name="lineItemSearch"
          value={draft.lineItemSearch}
          placeholder={translate("common.searchPlaceholder")}
          description={translate("common.searchDescription")}
          onChange={(value) =>
            setDraft((current) => ({ ...current, lineItemSearch: value }))
          }
        />
        <Select
          label={translate("common.eventTypeLabel")}
          name="eventType"
          value={draft.eventType}
          options={[
            { label: translate("common.anyEventType"), value: "" },
            ...AUDIT_EVENT_TYPES.map((value) => ({
              label: translate(`events.${value}`),
              value,
            })),
          ]}
          onChange={(value) =>
            setDraft((current) => ({
              ...current,
              eventType: value as "" | AuditEventType,
              field: value === "PROPERTY_CHANGED" ? current.field : "",
            }))
          }
        />
        <Select
          label={translate("common.fieldLabel")}
          name="field"
          value={draft.field}
          options={[
            { label: translate("common.anyField"), value: "" },
            ...MONITORED_PROPERTIES.map((value) => ({
              label: translate(`fields.${value}`),
              value,
            })),
          ]}
          onChange={(value) =>
            setDraft((current) => ({
              ...current,
              field: value as "" | MonitoredProperty,
              eventType: value ? "" : current.eventType,
            }))
          }
        />
        <Input
          label={translate("common.lineItemIdLabel")}
          name="lineItemId"
          value={draft.lineItemId}
          description={translate("common.lineItemIdDescription")}
          onChange={(value) =>
            setDraft((current) => ({ ...current, lineItemId: value }))
          }
        />
        <Flex direction="row" gap="sm">
          <DateInput
            label={translate("common.fromDateLabel")}
            name="fromDate"
            timezone="portalTz"
            format="YYYY-MM-DD"
            {...(draft.from ? { value: toDateInput(draft.from) } : {})}
            onChange={(value) =>
              setDraft((current) => ({
                ...current,
                from: fromDateInput(value),
              }))
            }
          />
          <DateInput
            label={translate("common.throughDateLabel")}
            name="throughDate"
            timezone="portalTz"
            format="YYYY-MM-DD"
            {...(draft.through ? { value: toDateInput(draft.through) } : {})}
            onChange={(value) =>
              setDraft((current) => ({
                ...current,
                through: fromDateInput(value),
              }))
            }
          />
        </Flex>
        {validation ? (
          <Alert
            title={translate("common.filterValidationTitle")}
            variant="warning"
          >
            <Text>{validation}</Text>
          </Alert>
        ) : null}
        <Flex direction="row" gap="sm">
          <Button onClick={() => applyFilters(draft)}>
            {translate("common.applyFilters")}
          </Button>
          {activeFilterCount > 0 ? (
            <Button
              variant="secondary"
              onClick={() => {
                setDraft(emptyDraft);
                applyFilters(emptyDraft);
              }}
            >
              {translate("common.clearAll")}
            </Button>
          ) : null}
          <LoadingButton
            loading={refreshing}
            onClick={() => readFirstPage(appliedRef.current, true)}
          >
            {refreshing
              ? translate("common.refreshing")
              : translate("common.refresh")}
          </LoadingButton>
        </Flex>
        {activeFilterCount > 0 ? (
          <Flex direction="row" gap="xs" wrap="wrap">
            {applied.lineItemSearch ? (
              <StatusTag
                showRemoveIcon
                onRemoveClick={() => removeFilter("lineItemSearch")}
              >
                {translate("common.searchFilterTag", {
                  value: applied.lineItemSearch,
                })}
              </StatusTag>
            ) : null}
            {applied.eventType ? (
              <StatusTag
                showRemoveIcon
                onRemoveClick={() => removeFilter("eventType")}
              >
                {translate("common.eventTypeFilterTag", {
                  value: translate(`events.${applied.eventType}`),
                })}
              </StatusTag>
            ) : null}
            {applied.field ? (
              <StatusTag
                showRemoveIcon
                onRemoveClick={() => removeFilter("field")}
              >
                {translate("common.fieldFilterTag", {
                  value: translate(`fields.${applied.field}`),
                })}
              </StatusTag>
            ) : null}
            {applied.lineItemId ? (
              <StatusTag
                showRemoveIcon
                onRemoveClick={() => removeFilter("lineItemId")}
              >
                {translate("common.lineItemFilterTag", {
                  value: applied.lineItemId,
                })}
              </StatusTag>
            ) : null}
            {applied.from ? (
              <StatusTag
                showRemoveIcon
                onRemoveClick={() => removeFilter("from")}
              >
                {translate("common.fromFilterTag", {
                  value: dateLabel(applied.from),
                })}
              </StatusTag>
            ) : null}
            {applied.through ? (
              <StatusTag
                showRemoveIcon
                onRemoveClick={() => removeFilter("through")}
              >
                {translate("common.throughFilterTag", {
                  value: dateLabel(applied.through),
                })}
              </StatusTag>
            ) : null}
          </Flex>
        ) : null}
        {lastRefreshed ? (
          <Text>
            {translate("common.lastRefreshed", {
              date: formatter.timestamp(lastRefreshed.toISOString()),
            })}
          </Text>
        ) : null}
        {refreshFailed ? (
          <Alert
            title={translate("common.refreshFailedTitle")}
            variant="warning"
          >
            <Text>{translate("common.refreshFailedDescription")}</Text>
          </Alert>
        ) : null}
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
            paginationRequiresRestart={lineItemsLoad.requiresRestart}
            searched={Boolean(applied.lineItemSearch)}
            capped={data.lineItems.items.length >= MAX_LINE_ITEMS}
            onLoadMore={() => loadMore("lineItems")}
            onRestart={restartResults}
            onShowHistory={(lineItemId) => {
              const next = { ...draftFromApplied(applied), lineItemId };
              setDraft(next);
              applyFilters(next);
            }}
          />
          <Divider />
          <EventsSection
            items={data.events.items}
            page={data.events.page}
            formatter={formatter}
            translate={translate}
            loadingMore={eventsLoad.loading}
            paginationFailed={eventsLoad.failed}
            paginationRequiresRestart={eventsLoad.requiresRestart}
            filtered={hasEventFilters(applied)}
            capped={data.events.items.length >= MAX_EVENTS}
            now={new Date()}
            onLoadMore={() => loadMore("events")}
            onRestart={restartResults}
          />
        </>
      )}
    </Flex>
  );
}
