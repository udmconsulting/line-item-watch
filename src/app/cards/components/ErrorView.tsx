import {
  Button,
  ButtonRow,
  ErrorState,
  Flex,
  Text,
} from "@hubspot/ui-extensions";
import type { ErrorCode, Translate } from "../i18n";
import type { DealAuditClientError } from "../lib/client";

interface ErrorViewProps {
  readonly error: DealAuditClientError;
  readonly translate: Translate;
  readonly onRetry: () => void;
  readonly onCopy: (value: string) => void;
}

export function ErrorView({
  error,
  translate,
  onRetry,
  onCopy,
}: ErrorViewProps) {
  const code = error.code as ErrorCode;
  return (
    <ErrorState title={translate(`errors.${code}.title`)}>
      <Flex direction="column" gap="sm">
        <Text>{translate(`errors.${code}.description`)}</Text>
        <Text>{translate(`errors.${code}.action`)}</Text>
        {error.correlationId ? (
          <Text>
            {translate("common.reference", { value: error.correlationId })}
          </Text>
        ) : null}
        <ButtonRow>
          <Button onClick={onRetry}>{translate("common.retry")}</Button>
          {error.correlationId ? (
            <Button
              onClick={() => onCopy(error.correlationId!)}
              variant="transparent"
            >
              {translate("common.copyReference")}
            </Button>
          ) : null}
        </ButtonRow>
      </Flex>
    </ErrorState>
  );
}
