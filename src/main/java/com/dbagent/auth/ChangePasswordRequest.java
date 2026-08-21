package com.dbagent.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ChangePasswordRequest(
        String token,
        @JsonProperty("current_password") String currentPassword,
        @JsonProperty("new_password") String newPassword) {
}
