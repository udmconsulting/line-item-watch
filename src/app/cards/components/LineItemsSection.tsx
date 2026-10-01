import {
  Accordion,
  Alert,
  Button,
  DescriptionList,
  DescriptionListItem,
  Divider,
  EmptyState,
  Flex,
  Heading,
  LoadingButton,
  StatusTag,
  Text,
} from "@hubspot/ui-extensions";
import type { Translate } from "../i18n";
import {
  MONITORED_PROPERTIES,
  type LineItemSummary,
  type MonitoredProperty,
  type Page,
} from "../lib/contracts";
import { formatObservedValue, type ValueFormatter } from "../lib/formatting";

const commercialFields: readonly MonitoredProperty[] = [
  "name",
  "quantity",
  "unitPrice",
  "unitDiscount",
  "discountPercentage",
];
const billingFields = MONITORED_PROPERTIES.filter(
  (field) => field !== "name" && !commercialFields.includes(field),
);

interface DetailFieldsProps {
  readonly item: LineItemSummary;
  readonly fields: readonly MonitoredProperty[];
  readonly formatter: ValueFormatter;
  readonly translate: Translate;
}

function DetailFields({
  item,
  fields,
  formatter,
  translate,
}: DetailFieldsProps) {
  return (
    <DescriptionList>
      {fields.map((field) => {
        const observed = item.latest[field];
        return (
          <DescriptionListItem key={field} label={translate(`fields.${field}`)}>
            <Flex direction="column" gap="xs">
              <Text>
                {formatObservedValue(field, observed, formatter, translate)}
              </Text>
              {observed.truncated ? (
                <Text>{translate("common.truncated")}</Text>
              ) : null}
            </Flex>
          </DescriptionListItem>
        );
      })}
    </DescriptionList>
  );
}

function membershipText(item: LineItemSummary, translate: Translate): string {
  if (item.deleted && item.dealMembership.membershipAtDeletion) {
    return translate("membership.atDeletion", {
      value: translate(
        `membership.${item.dealMembership.membershipAtDeletion}`,
      ),
    });
  }
  return translate(`membership.${item.dealMembership.currentMembership}`);
}

interface LineItemsSectionProps {
  readonly items: readonly LineItemSummary[];
  readonly page: Page;
  readonly formatter: ValueFormatter;
  readonly translate: Translate;
  readonly loadingMore: boolean;
  readonly paginationFailed: boolean;
  readonly paginationRequiresRestart: boolean;
  readonly searched: boolean;
  readonly capped: boolean;
  readonly onLoadMore: () => void;
  readonly onRestart: () => void;
  readonly onShowHistory: (lineItemId: string) => void;
}

export function LineItemsSection({
  items,
  page,
  formatter,
  translate,
  loadingMore,
  paginationFailed,
  paginationRequiresRestart,
  searched,
  capped,
  onLoadMore,
  onRestart,
  onShowHistory,
}: LineItemsSectionProps) {
  return (
    <Flex direction="column" gap="sm">
      <Heading>{translate("common.lineItems")}</Heading>
      {items.length === 0 ? (
        <EmptyState
          title={translate(
            searched
              ? "common.noLineItemSearchResultsTitle"
              : "common.noLineItemsTitle",
          )}
        >
          <Text>
            {translate(
              searched
                ? "common.noLineItemSearchResultsDescription"
                : "common.noLineItemsDescription",
            )}
          </Text>
        </EmptyState>
      ) : (
        items.map((item) => {
          const name = item.latest.name;
          const title =
            name.state === "VALUE" && name.value?.trim()
              ? name.value
              : translate("common.unnamedLineItem");
          return (
            <Accordion key={item.lineItemId} title={title}>
              <Flex direction="column" gap="sm">
                <Text>
                  {translate("common.lineItemReference", {
                    value: item.lineItemId,
                  })}
                </Text>
                <Button onClick={() => onShowHistory(item.lineItemId)}>
                  {translate("common.showHistory")}
                </Button>
                <StatusTag variant={item.deleted ? "default" : "info"}>
                  {item.deleted
                    ? translate("common.deleted")
                    : membershipText(item, translate)}
                </StatusTag>
                {item.deletedAt ? (
                  <Text>
                    {translate("common.deletedAt", {
                      date: formatter.timestamp(item.deletedAt),
                    })}
                  </Text>
                ) : null}
                {item.deleted ? (
                  <Text>{membershipText(item, translate)}</Text>
                ) : null}
                <Alert
                  title={translate(
                    `historyCoverage.${item.historyCoverage.mode}`,
                    {
                      date: formatter.timestamp(
                        item.historyCoverage.observedFrom,
                      ),
                    },
                  )}
                  variant={
                    item.historyCoverage.hasUnknownState ? "warning" : "info"
                  }
                >
                  <Flex direction="column" gap="xs">
                    {item.historyCoverage.hasUnknownState ? (
                      <Text>{translate("historyCoverage.unknownState")}</Text>
                    ) : null}
                    <Text>{translate("historyCoverage.disclaimer")}</Text>
                  </Flex>
                </Alert>
                <Heading>{translate("common.commercialDetails")}</Heading>
                <DetailFields
                  item={item}
                  fields={commercialFields}
                  formatter={formatter}
                  translate={translate}
                />
                <Divider />
                <Heading>{translate("common.billingDetails")}</Heading>
                <DetailFields
                  item={item}
                  fields={billingFields}
                  formatter={formatter}
                  translate={translate}
                />
              </Flex>
            </Accordion>
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
        <Alert title={translate("common.lineItemCapTitle")} variant="info">
          <Text>{translate("common.lineItemCapDescription")}</Text>
        </Alert>
      ) : null}
      {page.hasMore && !paginationFailed && !capped ? (
        <LoadingButton loading={loadingMore} onClick={onLoadMore}>
          {loadingMore
            ? translate("common.lineItemsLoading")
            : translate("common.loadMoreLineItems")}
        </LoadingButton>
      ) : null}
    </Flex>
  );
}
