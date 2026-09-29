import { hubspot, logger } from "@hubspot/ui-extensions";
import { DealAuditCard } from "./components/DealAuditCard";

hubspot.extend<"crm.record.tab">(({ context, actions }) => {
  // Clipboard is a supported platform action. The local SDK exposes it through
  // DOM's Clipboard type, which is intentionally absent from this worker build.
  const copyTextToClipboard = actions.copyTextToClipboard as unknown as (
    value: string,
  ) => Promise<void>;
  return (
    <DealAuditCard
      context={context}
      fetcher={hubspot.fetch}
      copyText={(value) => {
        void copyTextToClipboard(value).catch(() => undefined);
      }}
      logMissingTranslation={(key) => {
        logger.error(`Missing app-owned translation key: ${key}`);
      }}
    />
  );
});
