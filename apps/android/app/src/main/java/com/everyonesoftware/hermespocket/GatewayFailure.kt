package com.everyonesoftware.hermespocket

/** A fixed, user-safe error vocabulary: never surface raw server exceptions or credentials. */
class GatewayFailure(val reason: Reason) : Exception(reason.userMessage) {
    enum class Reason(val userMessage: String) {
        UNSUPPORTED_PROTOCOL("This server does not support the required Hermes session API. Update the backend or use a compatible remote gateway."),
        AUTHENTICATION("The gateway rejected this session token. Sign in again using a gateway token, not a model API key."),
        CONNECTION("The gateway connection was lost. Reconnect, then reopen the session to check its current state."),
        TURN_FAILED("The agent reported that this turn failed. Reopen the session to review its current state."),
        TURN_INTERRUPTED("This turn was interrupted. Reopen the session before continuing."),
        INTERACTION_REQUIRED("This run needs an approval or clarification. Review it on your desktop, then reopen this session."),
        UNKNOWN_OUTCOME("A reply was not confirmed. The task may still be running. Review the session before retrying; no automatic retry was sent."),
    }
}
