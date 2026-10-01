package com.udmconsulting.platform.supportability;

public enum ApplicationOperation {
    HTTP_REQUEST("http", "http_request"),
    HUBSPOT_OAUTH_INSTALL("hubspot_oauth", "oauth_install"),
    HUBSPOT_OAUTH_CALLBACK("hubspot_oauth", "oauth_callback"),
    HUBSPOT_WEBHOOK_INGEST("hubspot_webhook", "webhook_ingest"),
    LINE_ITEM_BASELINE_SYNC("line_item_watch", "baseline_sync"),
    LINE_ITEM_SIGNAL_PROCESS("line_item_watch", "signal_process"),
    LINE_ITEM_RECONCILE("line_item_watch", "reconcile"),
    LINE_ITEM_REPLAY("line_item_watch", "replay"),
    LINE_ITEM_RETENTION("line_item_watch", "retention"),
    DEAL_AUDIT_READ("line_item_watch", "deal_audit_read"),
    HUBSPOT_UNINSTALL("hubspot_oauth", "uninstall");

    private final String component;
    private final String operation;

    ApplicationOperation(String component, String operation) {
        this.component = component;
        this.operation = operation;
    }

    public String component() {
        return component;
    }

    public String operation() {
        return operation;
    }
}
