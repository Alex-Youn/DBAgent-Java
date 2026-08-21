package com.dbagent.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record UpdateUserRequest(
        String token,
        String role,
        @JsonProperty("hidden_menus") List<String> hiddenMenus,
        @JsonProperty("hidden_dbs") List<String> hiddenDbs) {
}
