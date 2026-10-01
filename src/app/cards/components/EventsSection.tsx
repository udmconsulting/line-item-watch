import {
  Alert,
  Button,
  DescriptionList,
  DescriptionListItem,
  Divider,
  EmptyState,
  Flex,
  Heading,
  LoadingButton,
  Text,
} from "@hubspot/ui-extensions";
import type { Translate } from "../i18n";
import type { AuditEvent, Page } from "../lib/contracts";
import { formatObservedValue, type ValueFormatter } from "../lib/formatting";

interface EventsSectionProps {
  readonly items: readonly AuditEvent[];
  readonly page: Page;
  readonly formatter: ValueFormatter;
  readonly translate: Translate;
  readonly loadingMore: boolean;
  readonly paginationFailed: boolean;
  readonly paginationRequiresRestart: boolean;
  readonly filtered: boolean;
  readonly capped: boolean;
  readonly now: Date;
  readonly onLoadMore: () => void;
  readonly onRestart: () => void;
}

export function EventsSection({
  items,
  page,
  formatter,
  translate,
  loadingMore,
  paginationFailed,
  paginationRequiresRestart,
  filtered,
  capped,
  now,
  onLoadMore,
  onRestart,
}: EventsSectionProps) {
  const groups = items.reduce<
    Array<{ key: string; label: string; items: AuditEvent[] }>
  >((accumulator, event) => {
    const key = formatter.dayKey(event.occurredAt);
    const current = accumulator.at(-1);
    if (current?.key === key) {
      current.items.push(event);
    } else {
      accumulator.push({
        key,
        label: formatter.dayLabel(event.occurredAt, now, translate),
        items: [event],
      });
    }
    return accumulator;
  }, []);
  return (
    <Flex direction="column" gap="sm">
      <Heading>{translate("common.recentChanges")}</Heading>
      <Text>{translate("common.latestRetainedNameDisclaimer")}</Text>
      {items.length === 0 ? (
        <EmptyState
          title={translate(
            filtered ? "common.noFilteredEventsTitle" : "common.noEventsTitle",
          )}
        >
          <Text>
            {translate(
              filtered
                ? "common.noFilteredEventsDescription"
                : "common.noEventsDescription",
            )}
          </Text>
        </EmptyState>
      ) : (
        groups.map((group, groupIndex) => (
          <Flex direction="column" gap="sm" key={group.key}>
            {groupIndex > 0 ? <Divider /> : null}
            <Heading>{group.label}</Heading>
            {group.items.map((event, eventIndex) => {
              const latestName = event.latestRetainedLineItemName;
              const displayName =
                latestName.state === "VALUE" && latestName.value?.trim()
                  ? latestName.value
                  : translate("common.unnamedLineItem");
              return (
                <Flex direction="column" gap="xs" key={event.eventId}>
                  {eventIndex > 0 ? <Divider /> : null}
                  <Text format={{ fontWeight: "bold" }}>
                    {translate(`events.${event.type}`)}
                  </Text>
                  <Text>{formatter.timestamp(event.occurredAt)}</Text>
                  <Text>
                    {translate("common.eventLineItemIdentity", {
                      name: displayName,
                      reference: event.lineItemId,
                    })}
                  </Text>
                  {event.type === "PROPERTY_CHANGED" && event.field ? (
                    <Flex direction="column" gap="xs">
                      <Text>
                        {translate("common.changedField", {
                          value: translate(`fields.${event.field}`),
                        })}
                      </Text>
                      <DescriptionList>
                        <DescriptionListItem label={translate("common.before")}>
                          <Flex direction="column" gap="xs">
                            <Text>
                              {formatObservedValue(
                                event.field,
                                event.before,
                                formatter,
                                translate,
                              )}
                            </Text>
                            {event.before.truncated ? (
                              <Text>{translate("common.truncated")}</Text>
                            ) : null}
                          </Flex>
                        </DescriptionListItem>
                        <DescriptionListItem label={translate("common.after")}>
                          <Flex direction="column" gap="xs">
                            <Text>
                              {formatObservedValue(
                                event.field,
                                event.after,
                                formatter,
                                translate,
                              )}
                            </Text>
                            {event.after.truncated ? (
                              <Text>{translate("common.truncated")}</Text>
                            ) : null}
                          </Flex>
                        </DescriptionListItem>
                      </DescriptionList>
                    </Flex>
                  ) : null}
                </Flex>
              );
            })}
          </Flex>
        ))
      )}
      {paginationFailed ? (
        <Alert
          title={translate("common.paginationErrorTitle")}
          variant="warning"
        >
          <Flex direction="column" gap="xs">
            <Text>{translate("common.paginationErrorDescription")}</Text>
            <Button
              onClick={paginationRequiresRestart ? onRestart : onLoadMore}
            >
              {translate(
                paginationRequiresRestart
                  ? "common.restartResults"
                  : "common.retry",
              )}
            </Button>
          </Flex>
        </Alert>
      ) : null}
      {capped && page.hasMore ? (
        <Alert title={translate("common.eventCapTitle")} variant="info">
          <Text>{translate("common.eventCapDescription")}</Text>
        </Alert>
      ) : null}
      {page.hasMore && !paginationFailed && !capped ? (
        <LoadingButton loading={loadingMore} onClick={onLoadMore}>
          {loadingMore
            ? translate("common.eventsLoading")
            : translate("common.loadMoreEvents")}
        </LoadingButton>
      ) : null}
    </Flex>
  );
}
