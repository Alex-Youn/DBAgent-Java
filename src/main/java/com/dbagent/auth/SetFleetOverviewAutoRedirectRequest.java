package com.dbagent.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SetFleetOverviewAutoRedirectRequest(
        String token,
        @JsonProperty("auto_redirect") boolean autoRedirect) {
}
