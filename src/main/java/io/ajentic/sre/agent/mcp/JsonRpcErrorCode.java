package io.ajentic.sre.agent.mcp;

/** Standard JSON-RPC 2.0 error codes, so the handler never hard-codes a magic integer. */
public enum JsonRpcErrorCode {

    PARSE_ERROR(-32700, "Parse error"),
    INVALID_REQUEST(-32600, "Invalid Request"),
    METHOD_NOT_FOUND(-32601, "Method not found"),
    INVALID_PARAMS(-32602, "Invalid params"),
    INTERNAL_ERROR(-32603, "Internal error");

    private final int code;
    private final String defaultMessage;

    JsonRpcErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public int code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
