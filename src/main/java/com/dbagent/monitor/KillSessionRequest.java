package com.dbagent.monitor;

import java.util.List;

/** reason: "FAILOVER"(장애조치 버튼) 또는 생략(선택 세션 Kill) - 감사 기록 구분용. */
public record KillSessionRequest(List<SessionRef> sessions, String token, String reason) {

    public record SessionRef(Long sid, Long serial) {
    }
}
