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
import type { AuditEvent, LineItemSummary, Page } from "../lib/contracts";
import { formatObservedValue, type ValueFormatter } from "../lib/formatting";

interface EventsSectionProps {
  readonly items: readonly AuditEvent[];
  readonly lineItems: readonly LineItemSummary[];
  readonly page: Page;
  readonly formatter: ValueFormatter;
  readonly translate: Translate;
  readonly loadingMore: boolean;
  readonly paginationFailed: boolean;
  readonly onLoadMore: () => void;
}

export function EventsSection({
  items,
  lineItems,
  page,
  formatter,
  translate,
  loadingMore,
  paginationFailed,
  onLoadMore,
}: EventsSectionProps) {
  const lineItemsById = new Map(
    lineItems.map((item) => [item.lineItemId, item]),
  );
  return (
    <Flex direction="column" gap="sm">
      <Heading>{translate("common.recentChanges")}</Heading>
      {items.length === 0 ? (
        <EmptyState title={translate("common.noEventsTitle")}>
          <Text>{translate("common.noEventsDescription")}</Text>
        </EmptyState>
      ) : (
        items.map((event, index) => {
          const loadedItem = lineItemsById.get(event.lineItemId);
          const loadedName = loadedItem?.latest.name;
          const itemLabel = loadedItem
            ? translate("common.lineItemName", {
                value:
                  loadedName?.state === "VALUE" && loadedName.value?.trim()
                    ? loadedName.value
                    : translate("common.unnamedLineItem"),
              })
            : translate("common.lineItemReference", {
                value: event.lineItemId,
              });
          return (
            <Flex direction="column" gap="xs" key={event.eventId}>
              {index > 0 ? <Divider /> : null}
              <Text format={{ fontWeight: "bold" }}>
                {translate(`events.${event.type}`)}
              </Text>
              <Text>{formatter.timestamp(event.occurredAt)}</Text>
              <Text>{itemLabel}</Text>
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
        })
      )}
      {paginationFailed ? (
        <Alert
          title={translate("common.paginationErrorTitle")}
          variant="warning"
        >
          <Flex direction="column" gap="xs">
            <Text>{translate("common.paginationErrorDescription")}</Text>
            <Button onClick={onLoadMore}>{translate("common.retry")}</Button>
          </Flex>
        </Alert>
      ) : null}
      {page.hasMore && !paginationFailed ? (
        <LoadingButton loading={loadingMore} onClick={onLoadMore}>
          {loadingMore
            ? translate("common.eventsLoading")
            : translate("common.loadMoreEvents")}
        </LoadingButton>
      ) : null}
    </Flex>
  );
}
