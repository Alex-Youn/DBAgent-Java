package com.dbagent.monitor;

import java.util.List;

public record KillSessionRequest(List<SessionRef> sessions, String token) {

    public record SessionRef(Long sid, Long serial) {
    }
}
