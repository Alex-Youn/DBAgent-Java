package com.dbagent.oracle;

import com.dbagent.security.CredentialCipher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves a db_id to Oracle/RDB connection details, backed by the db_instances SQLite table
 * (dbconfig.db). Replaces the earlier databases.json file store - oracle.env 제거 마이그레이션
 * 4-1단계, 2026-09-21. On first boot (db_config_meta.migrated_from_json = 0), imports whatever
 * groups/instances are in the legacy databases.json file (if present) as a one-time migration,
 * then never reads that file again - see migrateFromJsonFile().
 */
@Service
public class DatabaseConfigService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfigService.class);

    // Legacy file this service migrates FROM exactly once - not a live config path any more.
    private static final String LEGACY_JSON_PATH = "databases.json";

    private final JdbcTemplate jdbc;
    private final CredentialCipher cipher;
    private final ObjectMapper mapper = new ObjectMapper();

    // Serializes createInstance/updateInstance/deleteInstance against each other and against the
    // migration step. Reads (resolve, listAccounts, listAllInstances, safeConfig, ...) don't need
    // this - SQLite/JDBC already gives each query a consistent snapshot.
    private final Object writeLock = new Object();

    public DatabaseConfigService(@Qualifier("dbConfigJdbcTemplate") JdbcTemplate jdbc, CredentialCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @PostConstruct
    void init() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS db_instances (" +
                "id TEXT PRIMARY KEY," +
                "group_name TEXT NOT NULL," +
                "group_order INTEGER NOT NULL," +
                "instance_order INTEGER NOT NULL," +
                "name TEXT NOT NULL DEFAULT ''," +
                "db_type TEXT NOT NULL DEFAULT 'oracle'," +
                "host TEXT NOT NULL DEFAULT ''," +
                "port INTEGER NOT NULL DEFAULT 1521," +
                "sid TEXT NOT NULL DEFAULT ''," +
                // Named db_user, not user - "user"/"USER" collides with a reserved keyword/function
                // on some JDBC drivers (H2, in the AIX build of this same service).
                "db_user TEXT NOT NULL DEFAULT ''," +
                "password TEXT NOT NULL DEFAULT ''," +
                "connect_mode TEXT NOT NULL DEFAULT ''," +
                "pool_min_idle INTEGER," +
                "pool_max_size INTEGER," +
                "session_thresholds TEXT," +
                "accounts TEXT," +
                "expected_instance_name TEXT" +
                ")");
        // A dbconfig.db created before 5단계 (expected_instance_name didn't exist yet) needs this
        // column added on top - CREATE TABLE IF NOT EXISTS above is a no-op for an already-existing
        // table, same pattern as AuthService's users-table column migrations.
        List<String> existingColumns = jdbc.query("PRAGMA table_info(db_instances)",
                (rs, rowNum) -> rs.getString("name"));
        if (existingColumns.stream().noneMatch("expected_instance_name"::equalsIgnoreCase)) {
            jdbc.execute("ALTER TABLE db_instances ADD COLUMN expected_instance_name TEXT");
        }
        jdbc.execute("CREATE TABLE IF NOT EXISTS db_config_meta (" +
                "id INTEGER PRIMARY KEY CHECK (id = 1)," +
                "migrated_from_json INTEGER NOT NULL DEFAULT 0" +
                ")");
        // 6단계(비밀번호 실암호화) 기록용 컬럼 - 재암호화 여부 판단에는 쓰지 않는다(아래
        // reencryptAllPasswords() 주석 참고). 기존 dbconfig.db에도 무중단으로 붙인다.
        List<String> metaColumns = jdbc.query("PRAGMA table_info(db_config_meta)",
                (rs, rowNum) -> rs.getString("name"));
        if (metaColumns.stream().noneMatch("password_key_id"::equalsIgnoreCase)) {
            jdbc.execute("ALTER TABLE db_config_meta ADD COLUMN password_key_id TEXT");
        }
        if (metaColumns.stream().noneMatch("password_format_ver"::equalsIgnoreCase)) {
            jdbc.execute("ALTER TABLE db_config_meta ADD COLUMN password_format_ver INTEGER DEFAULT 0");
        }
        if (metaColumns.stream().noneMatch("reencrypted_at"::equalsIgnoreCase)) {
            jdbc.execute("ALTER TABLE db_config_meta ADD COLUMN reencrypted_at TEXT");
        }
        jdbc.update("INSERT OR IGNORE INTO db_config_meta (id, migrated_from_json) VALUES (1, 0)");
        Integer migrated = jdbc.queryForObject(
                "SELECT migrated_from_json FROM db_config_meta WHERE id = 1", Integer.class);
        if (migrated == null || migrated == 0) {
            migrateFromJsonFile();
            jdbc.update("UPDATE db_config_meta SET migrated_from_json = 1 WHERE id = 1");
        }
        reencryptAllPasswords();
        warnOnDuplicates();
        errorOnMissingOracleHost();
    }

    /**
     * 평문/B64/구키로 저장된 비밀번호를 현재 활성 키의 AES 암호문으로 올린다
     * (oracle.env 제거 마이그레이션 6단계, 2026-09-21).
     *
     * <p><b>1회성 플래그가 아니라 매 기동 멱등 스캔이다.</b> 대상이 10여 행뿐이라 전수 스캔 비용이
     * 사실상 0인 반면, 플래그 방식은 자기치유가 안 된다 - 롤백으로 구 jar가 한 행을 B64로 덮어썼거나,
     * 키 로테이션으로 활성 키가 바뀌었거나, 일부 행에서 실패했을 때 플래그가 이미 1이면 영원히 다시
     * 돌지 않는다. 같은 이유로 키 로테이션 후 재암호화도 이 메서드 하나가 같이 처리한다.
     *
     * <p>실패한 행은 원본을 그대로 두고 ERROR 로그만 남긴 뒤 계속 진행한다 - 한 인스턴스 때문에
     * 전체 기동을 막지 않는다는 기존 원칙(warnOnDuplicates/errorOnMissingOracleHost)을 따른다.
     * 단 키 파일 자체를 못 읽는 경우는 MasterKeyProvider가 기동 시점에 이미 fail-fast 시킨다.
     */
    private void reencryptAllPasswords() {
        synchronized (writeLock) {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, password, accounts FROM db_instances");
            int changed = 0;
            int failed = 0;
            for (Map<String, Object> row : rows) {
                String id = (String) row.get("id");
                try {
                    String password = (String) row.get("password");
                    String accounts = (String) row.get("accounts");
                    String newPassword = cipher.needsReencrypt(password)
                            ? cipher.encrypt(cipher.decrypt(password)) : password;
                    String newAccounts = reencryptAccountsJson(accounts);
                    boolean passwordChanged = newPassword != null && !newPassword.equals(password);
                    boolean accountsChanged = newAccounts != null && !newAccounts.equals(accounts);
                    if (!passwordChanged && !accountsChanged) {
                        continue;
                    }
                    // 행 단위로 커밋해서, 중간에 한 행이 실패해도 앞서 성공한 행은 그대로 남게 한다.
                    jdbc.update("UPDATE db_instances SET password = ?, accounts = ? WHERE id = ?",
                            passwordChanged ? newPassword : password,
                            accountsChanged ? newAccounts : accounts,
                            id);
                    changed++;
                } catch (RuntimeException e) {
                    failed++;
                    // 암호문/평문/키는 절대 로그에 싣지 않는다 - 인스턴스 ID만.
                    log.error("인스턴스 '{}'의 비밀번호를 현재 키로 처리할 수 없습니다 - 관리 화면에서 "
                            + "비밀번호를 다시 입력해야 합니다. ({})", id, e.getMessage());
                }
            }
            jdbc.update("UPDATE db_config_meta SET password_key_id = ?, password_format_ver = 1, "
                            + "reencrypted_at = ? WHERE id = 1",
                    cipher.activeKeyId(), java.time.LocalDateTime.now().toString());
            if (changed > 0 || failed > 0) {
                log.info("비밀번호 재암호화: 변경 {}건, 실패 {}건 (활성 키 {})", changed, failed, cipher.activeKeyId());
            }
            if (changed > 0) {
                purgeOldPageImages();
            }
        }
    }

    /**
     * 재암호화로 덮어쓴 예전 값(평문/B64)의 잔재를 파일에서 지운다.
     *
     * <p>UPDATE만으로는 부족하다 - SQLite는 WAL에 옛 페이지 이미지를 남기고 본 파일에도 free page로
     * 남겨두므로, 암호화를 마친 뒤에도 {@code dbconfig.db-wal}을 그대로 열어보면 예전 {@code B64(...)}
     * 값이 그대로 읽힌다(2026-09-21 실측: 재암호화 직후 WAL에서 B64 마커 다수 발견). B64는 키 없이
     * 디코딩되므로, 이걸 안 지우면 "파일이 통째로 유출돼도 비밀번호는 안전하다"는 암호화의 목적
     * 자체가 무너진다.
     *
     * <p>재암호화가 실제로 행을 바꾼 기동(최초 업그레이드, 키 로테이션 직후)에만 돌고, 그 외에는
     * 호출되지 않는다. dbconfig.db는 인스턴스 수십 행짜리 작은 파일이라 비용은 무시할 수준이다.
     */
    private void purgeOldPageImages() {
        try {
            jdbc.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            jdbc.execute("VACUUM");
            log.info("재암호화 전 값이 남아있던 페이지를 정리했습니다(WAL 체크포인트 + VACUUM).");
        } catch (RuntimeException e) {
            // 정리에 실패해도 암호화 자체는 이미 끝났으므로 기동을 막지 않는다 - 다만 예전 값이
            // 파일에 남아있을 수 있다는 사실은 눈에 띄게 알린다.
            log.warn("재암호화 후 파일 정리(WAL 체크포인트/VACUUM)에 실패했습니다 - dbconfig.db와 "
                    + "그 WAL 파일에 예전 비밀번호 값이 남아있을 수 있으니, 이 파일들을 외부로 "
                    + "복사/백업할 때 주의하십시오. ({})", e.getMessage());
        }
    }

    /**
     * accounts JSON 배열 안의 각 password도 같은 규칙으로 올린다 - 비밀번호가 컬럼 하나가 아니라
     * 이 JSON 안에도 들어 있어서, 여기를 빠뜨리면 추가 계정만 평문으로 남는다.
     * 바뀐 게 없으면 원본을 그대로 반환해 불필요한 UPDATE를 피한다.
     *
     * <p>계정 하나가 실패하면 예외가 호출자로 올라가 그 인스턴스 행 전체가 건너뛰어진다
     * (reencryptAllPasswords()의 행 단위 try/catch가 받아서 원본 보존 + ERROR 로그). 계정 단위로
     * 더 잘게 살릴 수도 있지만, 행 단위로도 데이터는 보존되고 로그로 어느 인스턴스인지 드러나며
     * 다음 기동에 자동 재시도되므로 단순한 쪽을 택했다.
     */
    private String reencryptAccountsJson(String accountsJson) {
        if (accountsJson == null || accountsJson.isBlank()) {
            return accountsJson;
        }
        JsonNode parsed;
        try {
            parsed = mapper.readTree(accountsJson);
        } catch (IOException e) {
            // 형식이 깨진 JSON은 건드리지 않는다(그대로 두면 기존 동작대로 무시된다).
            return accountsJson;
        }
        if (!parsed.isArray()) {
            return accountsJson;
        }
        boolean anyChanged = false;
        ArrayNode rebuilt = mapper.createArrayNode();
        for (JsonNode acc : parsed) {
            String user = acc.path("user").asText("");
            String stored = acc.path("password").asText("");
            String updated = cipher.needsReencrypt(stored) ? cipher.encrypt(cipher.decrypt(stored)) : stored;
            if (!updated.equals(stored)) {
                anyChanged = true;
            }
            ObjectNode node = mapper.createObjectNode();
            node.put("user", user);
            node.put("password", updated);
            rebuilt.add(node);
        }
        return anyChanged ? rebuilt.toString() : accountsJson;
    }

    // oracle.env 제거 마이그레이션 5단계: alias(host 빈값 → tnsnames.ora) 경로를 완전히 삭제했으므로
    // host 없는 Oracle 인스턴스는 이제 접속 자체가 불가능하다(buildDsn()이 그냥 ":port:sid"를 만들어
    // 조용히 실패함). 그 상태로 방치되지 않도록 기동 시점에 눈에 띄게 ERROR로 남긴다 - 다른 정상
    // 인스턴스까지 막지는 않기 위해(warnOnDuplicates()와 같은 원칙) 기동 자체를 중단시키지는 않는다.
    private void errorOnMissingOracleHost() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id FROM db_instances WHERE db_type = 'oracle' AND (host IS NULL OR TRIM(host) = '')");
        for (Map<String, Object> row : rows) {
            log.error("db_instances의 Oracle 인스턴스 '{}'에 host가 비어 있습니다 - alias(tnsnames.ora) 경로가 " +
                    "제거되어 이 상태로는 접속할 수 없습니다. 관리 화면에서 host를 채워주세요.", row.get("id"));
        }
    }

    /**
     * One-time import from the legacy databases.json (if it exists next to the jar) into
     * db_instances - runs at most once per dbconfig.db (guarded by db_config_meta above), so
     * deleting/trimming databases.json before a server's first run on this new storage controls
     * exactly what gets carried over (e.g. moving only a couple of instances to a closed-network
     * server while the rest are added later through the admin UI). Missing file = nothing to
     * import, not an error - an empty db_instances table is a normal, fully supported state.
     */
    private void migrateFromJsonFile() {
        File file = new File(LEGACY_JSON_PATH);
        if (!file.exists()) {
            return;
        }
        JsonNode root;
        try {
            root = mapper.readTree(file);
        } catch (IOException e) {
            log.warn("{} 마이그레이션 실패 - 파일을 읽을 수 없습니다: {}", LEGACY_JSON_PATH, e.getMessage());
            return;
        }
        if (root == null || !root.has("groups")) {
            return;
        }
        int groupOrder = 0;
        int imported = 0;
        for (JsonNode group : root.get("groups")) {
            String groupName = group.path("group_name").asText("");
            int instanceOrder = 0;
            for (JsonNode inst : group.path("instances")) {
                String id = inst.path("id").asText("");
                if (id.isBlank()) {
                    continue;
                }
                String sessionThresholds = inst.has("session_thresholds") ? inst.get("session_thresholds").toString() : null;
                // databases.json의 비밀번호는 평문이거나 예전 B64(...) 형식이다. 이 자리에서 바로
                // 암호화해 넣는다 - 뒤따르는 reencryptAllPasswords()가 어차피 올려주긴 하지만, 그
                // 경우 평문이 dbconfig.db에 한 번 기록됐다가 덮어써져서 WAL/빈 페이지에 잔존할 수
                // 있다. encrypt(decrypt(x))인 이유는 B64(...)를 그 문자열 그대로 암호화하면 안 되기
                // 때문(decrypt가 레거시 값을 평문으로 풀어준다).
                String accounts = inst.has("accounts") ? inst.get("accounts").toString() : null;
                String password = inst.path("password").asText("");
                try {
                    password = cipher.encrypt(cipher.decrypt(password));
                    accounts = reencryptAccountsJson(accounts);
                } catch (RuntimeException e) {
                    // 손상된 값(깨진 base64 등)이 섞여 있어도 기동 자체를 막지는 않는다 - 그 인스턴스만
                    // 원본 그대로 넣고 넘어간다. 여기서 예외를 그냥 올리면 @PostConstruct가 실패해
                    // 앱이 아예 못 뜬다(전체 마이그레이션이 인스턴스 하나 때문에 무산됨).
                    // 원본으로 들어간 값은 뒤따르는 reencryptAllPasswords()가 다시 시도하고, 거기서도
                    // 실패하면 행 단위로 ERROR를 남기므로 어느 인스턴스인지 드러난다.
                    log.error("databases.json의 인스턴스 '{}' 비밀번호를 암호화하지 못해 원본 그대로 "
                            + "이관합니다 - 관리 화면에서 비밀번호를 다시 입력하십시오. ({})", id, e.getMessage());
                    password = inst.path("password").asText("");
                    accounts = inst.has("accounts") ? inst.get("accounts").toString() : null;
                }
                jdbc.update("INSERT OR REPLACE INTO db_instances " +
                                "(id, group_name, group_order, instance_order, name, db_type, host, port, sid, db_user, " +
                                "password, connect_mode, pool_min_idle, pool_max_size, session_thresholds, accounts) " +
                                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        id, groupName, groupOrder, instanceOrder,
                        inst.path("name").asText(""),
                        inst.path("db_type").asText("oracle"),
                        inst.path("host").asText(""),
                        inst.path("port").asInt(1521),
                        inst.path("sid").asText(""),
                        inst.path("user").asText(""),
                        password,
                        inst.path("connect_mode").asText(""),
                        inst.hasNonNull("pool_min_idle") ? inst.path("pool_min_idle").asInt() : null,
                        inst.hasNonNull("pool_max_size") ? inst.path("pool_max_size").asInt() : null,
                        sessionThresholds, accounts);
                instanceOrder++;
                imported++;
            }
            groupOrder++;
        }
        log.info("{}에서 db_instances 테이블로 {}개 인스턴스를 마이그레이션했습니다.", LEGACY_JSON_PATH, imported);
    }

    // oracle.env 제거 마이그레이션 1단계: id 오타/재사용이나 (host,port,sid) 중복 등록은 예전에도
    // "엉뚱한 DB에 접속" 사고로 이어졌으므로(설계문서 참고), 기동 시점에 눈에 띄게 경고만 남긴다 -
    // 기존 동작을 바꾸지 않기 위해 시작을 막지는 않는다. id 중복은 PRIMARY KEY 제약으로 이제 테이블
    // 차원에서 막히지만(마이그레이션 중 중복이 있었다면 나중 값으로 덮어써짐), endpoint 중복은 여전히
    // 가능해 그대로 검사한다.
    private void warnOnDuplicates() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, host, port, sid FROM db_instances");
        Set<String> seenEndpoints = new HashSet<>();
        for (Map<String, Object> row : rows) {
            String host = (String) row.get("host");
            if (host != null && !host.isBlank()) {
                String endpoint = host + ":" + row.get("port") + ":" + row.get("sid");
                if (!seenEndpoints.add(endpoint)) {
                    log.warn("db_instances에 동일한 (host,port,sid)를 가진 인스턴스가 여러 개 있습니다: {}", endpoint);
                }
            }
        }
    }

    /**
     * A blank/missing dbId - no instance selected yet, a typo, a stale bookmark, a removed instance -
     * returns null rather than silently substituting a real but unrelated DB (oracle.env's old fallback
     * removed in the oracle.env removal migration's 4단계: that used to let any authenticated account
     * view or query that default DB via a blank/bogus db_id, and made stale/broken links fail silently
     * instead of loudly). Callers must treat a null result as "DB not found", not attempt to connect to it.
     */
    public TargetDbConfig resolve(String dbId) {
        if (dbId == null || dbId.isBlank()) {
            return null;
        }
        Map<String, Object> row = findRow(dbId);
        return row != null ? fromRow(row) : null;
    }

    /**
     * Same as resolve(dbId), but if account is non-blank and doesn't match the instance's default
     * user, looks it up in the instance's optional "accounts" column (JSON array) and returns a
     * TargetDbConfig with that account's user/password instead, keeping the same host/port/sid/id.
     * Falls back to the default account if account is blank or isn't found in "accounts". See
     * resolve(dbId) above for why a blank or unknown dbId returns null.
     */
    public TargetDbConfig resolve(String dbId, String account) {
        if (dbId == null || dbId.isBlank()) {
            return null;
        }
        Map<String, Object> row = findRow(dbId);
        if (row == null) {
            return null;
        }
        TargetDbConfig base = fromRow(row);
        if (account == null || account.isBlank() || account.equals(base.user())) {
            return base;
        }
        String accountsJson = (String) row.get("accounts");
        if (accountsJson != null) {
            try {
                for (JsonNode acc : mapper.readTree(accountsJson)) {
                    if (account.equals(acc.path("user").asText(""))) {
                        return new TargetDbConfig(
                                base.id(),
                                base.name(),
                                base.dbType(),
                                acc.path("user").asText(),
                                resolvePassword(acc.path("password").asText("")),
                                base.host(),
                                base.port(),
                                base.sid(),
                                base.connectMode(),
                                base.poolMinIdle(),
                                base.poolMaxSize(),
                                base.expectedInstanceName());
                    }
                }
            } catch (IOException ignored) {
                // Malformed accounts JSON - fall through to the default account below.
            }
        }
        return base;
    }

    /**
     * Default account first, followed by any accounts listed in the instance's optional "accounts"
     * column. A blank or unregistered dbId lists no accounts, matching resolve(dbId)'s handling of the
     * same cases (both return "nothing found" rather than leaking a default DB's identity).
     */
    public List<String> listAccounts(String dbId) {
        List<String> users = new ArrayList<>();
        Map<String, Object> row = findRow(dbId);
        if (row == null) {
            return users;
        }
        users.add((String) row.get("db_user"));
        String accountsJson = (String) row.get("accounts");
        if (accountsJson != null) {
            try {
                for (JsonNode acc : mapper.readTree(accountsJson)) {
                    String u = acc.path("user").asText("");
                    if (!u.isBlank() && !users.contains(u)) {
                        users.add(u);
                    }
                }
            } catch (IOException ignored) {
                // Malformed accounts JSON - just the default account above.
            }
        }
        return users;
    }

    /** Every configured instance across all groups - used for fleet-wide bulk status checks. */
    public List<TargetDbConfig> listAllInstances() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM db_instances ORDER BY group_order, instance_order");
        List<TargetDbConfig> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            result.add(fromRow(row));
        }
        return result;
    }

    private Map<String, Object> findRow(String dbId) {
        if (dbId == null) {
            return null;
        }
        // Defensive: a request with a duplicated db_id query param (?db_id=x&db_id=x) gets bound by
        // Spring as a single comma-joined string ("x,x"), which would otherwise silently match no
        // instance and return null instead of resolving the one actually picked.
        String normalized = dbId.indexOf(',') >= 0 ? dbId.substring(0, dbId.indexOf(',')) : dbId;
        if (normalized.isBlank()) {
            return null;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM db_instances WHERE id = ?", normalized);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private TargetDbConfig fromRow(Map<String, Object> row) {
        Object poolMinIdle = row.get("pool_min_idle");
        Object poolMaxSize = row.get("pool_max_size");
        return new TargetDbConfig(
                (String) row.get("id"),
                (String) row.get("name"),
                (String) row.get("db_type"),
                (String) row.get("db_user"),
                resolvePassword((String) row.get("password")),
                (String) row.get("host"),
                ((Number) row.get("port")).intValue(),
                (String) row.get("sid"),
                (String) row.get("connect_mode"),
                poolMinIdle == null ? null : ((Number) poolMinIdle).intValue(),
                poolMaxSize == null ? null : ((Number) poolMaxSize).intValue(),
                (String) row.get("expected_instance_name"));
    }

    /** Java port of api_server.py's /api/config: same groups/instances shape, with passwords stripped. */
    public Map<String, Object> safeConfig() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM db_instances ORDER BY group_order, instance_order");
        LinkedHashMap<String, List<Map<String, Object>>> byGroup = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String groupName = (String) row.get("group_name");
            byGroup.computeIfAbsent(groupName, k -> new ArrayList<>()).add(toSafeInstanceMap(row));
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : byGroup.entrySet()) {
            Map<String, Object> safeGroup = new LinkedHashMap<>();
            safeGroup.put("group_name", entry.getKey());
            safeGroup.put("instances", entry.getValue());
            groups.add(safeGroup);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("groups", groups);
        return result;
    }

    private Map<String, Object> toSafeInstanceMap(Map<String, Object> row) {
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("id", row.get("id"));
        safe.put("name", row.get("name"));
        safe.put("db_type", row.get("db_type"));
        safe.put("host", row.get("host"));
        safe.put("port", row.get("port"));
        safe.put("sid", row.get("sid"));
        safe.put("user", row.get("db_user"));
        safe.put("pool_min_idle", row.get("pool_min_idle"));
        safe.put("pool_max_size", row.get("pool_max_size"));
        String connectMode = (String) row.get("connect_mode");
        if (connectMode != null && !connectMode.isBlank()) {
            safe.put("connect_mode", connectMode);
        }
        String expectedInstanceName = (String) row.get("expected_instance_name");
        if (expectedInstanceName != null && !expectedInstanceName.isBlank()) {
            safe.put("expected_instance_name", expectedInstanceName);
        }
        String sessionThresholdsJson = (String) row.get("session_thresholds");
        if (sessionThresholdsJson != null) {
            try {
                safe.put("session_thresholds", mapper.readValue(sessionThresholdsJson, List.class));
            } catch (IOException ignored) {
                // Malformed - just omit the override, global default applies on the frontend.
            }
        }
        String accountsJson = (String) row.get("accounts");
        if (accountsJson != null) {
            try {
                List<Map<String, Object>> safeAccounts = new ArrayList<>();
                for (JsonNode acc : mapper.readTree(accountsJson)) {
                    Map<String, Object> safeAcc = new LinkedHashMap<>();
                    // Strip each extra account's (AES-encrypted) password - this response goes to
                    // every logged-in user, not just admins, same as the top-level instance
                    // password above.
                    safeAcc.put("user", acc.path("user").asText(""));
                    safeAccounts.add(safeAcc);
                }
                if (!safeAccounts.isEmpty()) {
                    safe.put("accounts", safeAccounts);
                }
            } catch (IOException ignored) {
                // Malformed - just omit the extra accounts.
            }
        }
        return safe;
    }

    // 실제 AES-256-GCM 암호화(oracle.env 제거 마이그레이션 6단계, 2026-09-21). 저장 포맷과 키 관리는
    // com.dbagent.security.CredentialCipher / MasterKeyProvider 참고. 예전 B64(...) 인코딩 값과 평문은
    // CredentialCipher.decrypt()가 그대로 읽어주므로(마이그레이션 유예), 이 두 메서드의 시그니처는
    // 그대로 두고 본문만 위임으로 바꿨다 - 호출부(fromRow/resolve/createInstance/updateInstance/
    // buildAccountsJson)는 변경 없음.
    private String resolvePassword(String raw) {
        return cipher.decrypt(raw);
    }

    private String encodePassword(String plain) {
        if (plain == null) {
            return "";
        }
        return cipher.encrypt(plain);
    }

    /** Admin UI: add a new DB instance under groupName (created if it doesn't already exist). */
    public Map<String, Object> createInstance(String groupName, String id, String name, String dbType, String host,
            int port, String sid, String user, String password, String connectMode, String expectedInstanceName,
            Integer poolMinIdle, Integer poolMaxSize, List<Map<String, String>> accounts,
            List<Integer> sessionThresholds) {
        if (id == null || id.isBlank()) {
            return Map.of("success", false, "message", "ID는 필수입니다.");
        }
        if (groupName == null || groupName.isBlank()) {
            return Map.of("success", false, "message", "그룹명은 필수입니다.");
        }
        if (password == null || password.isBlank()) {
            return Map.of("success", false, "message", "비밀번호는 필수입니다.");
        }
        // oracle.env 제거 마이그레이션 5단계: alias(tnsnames.ora) 경로 삭제로 host는 이제 항상 필수.
        String resolvedDbType = (dbType == null || dbType.isBlank()) ? "oracle" : dbType;
        if ("oracle".equals(resolvedDbType) && (host == null || host.isBlank())) {
            return Map.of("success", false, "message", "Host는 필수입니다.");
        }
        synchronized (writeLock) {
            List<Map<String, Object>> existing = jdbc.queryForList("SELECT id FROM db_instances WHERE id = ?", id);
            if (!existing.isEmpty()) {
                return Map.of("success", false, "message", "이미 존재하는 ID입니다: " + id);
            }
            // A brand-new instance has no stored accounts to fall back to, so every row here needs
            // its own non-blank password.
            String accountsError = validateAccounts(accounts, null);
            if (accountsError != null) {
                return Map.of("success", false, "message", accountsError);
            }
            String thresholdsError = validateSessionThresholds(sessionThresholds);
            if (thresholdsError != null) {
                return Map.of("success", false, "message", thresholdsError);
            }

            int groupOrder = groupOrderFor(groupName);
            int instanceOrder = nextInstanceOrder(groupName);
            jdbc.update("INSERT INTO db_instances " +
                            "(id, group_name, group_order, instance_order, name, db_type, host, port, sid, db_user, " +
                            "password, connect_mode, pool_min_idle, pool_max_size, session_thresholds, accounts, " +
                            "expected_instance_name) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, groupName, groupOrder, instanceOrder,
                    name == null ? "" : name, resolvedDbType,
                    host == null ? "" : host, port, sid == null ? "" : sid, user == null ? "" : user,
                    encodePassword(password), connectMode == null ? "" : connectMode, poolMinIdle, poolMaxSize,
                    buildSessionThresholdsJson(sessionThresholds), buildAccountsJson(accounts, null),
                    (expectedInstanceName == null || expectedInstanceName.isBlank()) ? null : expectedInstanceName);
            return Map.of("success", true, "message", "DB가 추가되었습니다.");
        }
    }

    /** Admin UI: update an existing instance's fields. Blank/null password keeps the stored value. */
    public Map<String, Object> updateInstance(String id, String name, String dbType, String host, int port,
            String sid, String user, String password, String connectMode, String expectedInstanceName,
            Integer poolMinIdle, Integer poolMaxSize, List<Map<String, String>> accounts,
            List<Integer> sessionThresholds) {
        String resolvedDbType = (dbType == null || dbType.isBlank()) ? "oracle" : dbType;
        if ("oracle".equals(resolvedDbType) && (host == null || host.isBlank())) {
            return Map.of("success", false, "message", "Host는 필수입니다.");
        }
        synchronized (writeLock) {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM db_instances WHERE id = ?", id);
            if (rows.isEmpty()) {
                return Map.of("success", false, "message", "존재하지 않는 DB입니다: " + id);
            }
            Map<String, Object> existing = rows.get(0);
            String existingAccountsJson = (String) existing.get("accounts");

            // A blank password on an existing account row keeps that account's stored password.
            String accountsError = validateAccounts(accounts, existingAccountsJson);
            if (accountsError != null) {
                return Map.of("success", false, "message", accountsError);
            }
            String thresholdsError = validateSessionThresholds(sessionThresholds);
            if (thresholdsError != null) {
                return Map.of("success", false, "message", thresholdsError);
            }

            String newPassword = (password != null && !password.isBlank())
                    ? encodePassword(password) : (String) existing.get("password");
            jdbc.update("UPDATE db_instances SET name=?, db_type=?, host=?, port=?, sid=?, db_user=?, password=?, " +
                            "connect_mode=?, pool_min_idle=?, pool_max_size=?, session_thresholds=?, accounts=?, " +
                            "expected_instance_name=? WHERE id=?",
                    name == null ? "" : name, resolvedDbType,
                    host == null ? "" : host, port, sid == null ? "" : sid, user == null ? "" : user,
                    newPassword, connectMode == null ? "" : connectMode, poolMinIdle, poolMaxSize,
                    buildSessionThresholdsJson(sessionThresholds), buildAccountsJson(accounts, existingAccountsJson),
                    (expectedInstanceName == null || expectedInstanceName.isBlank()) ? null : expectedInstanceName,
                    id);
            return Map.of("success", true, "message", "DB 정보가 수정되었습니다.");
        }
    }

    /** Admin UI: remove an instance. No group bookkeeping needed - groups are derived from rows on read. */
    public Map<String, Object> deleteInstance(String id) {
        synchronized (writeLock) {
            int deleted = jdbc.update("DELETE FROM db_instances WHERE id = ?", id);
            if (deleted == 0) {
                return Map.of("success", false, "message", "존재하지 않는 DB입니다: " + id);
            }
            return Map.of("success", true, "message", "DB가 삭제되었습니다.");
        }
    }

    private int groupOrderFor(String groupName) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT group_order FROM db_instances WHERE group_name = ? LIMIT 1", groupName);
        if (!rows.isEmpty()) {
            return ((Number) rows.get(0).get("group_order")).intValue();
        }
        Integer maxGroupOrder = jdbc.queryForObject("SELECT MAX(group_order) FROM db_instances", Integer.class);
        return (maxGroupOrder == null ? -1 : maxGroupOrder) + 1;
    }

    private int nextInstanceOrder(String groupName) {
        Integer maxInstanceOrder = jdbc.queryForObject(
                "SELECT MAX(instance_order) FROM db_instances WHERE group_name = ?", Integer.class, groupName);
        return (maxInstanceOrder == null ? -1 : maxInstanceOrder) + 1;
    }

    /**
     * Validates the admin UI's account rows without building the JSON yet - a blank password on a
     * row is only valid if existingAccountsJson already has a stored password for that user (the
     * "leave blank to keep unchanged" contract). Returns an error message, or null if all rows are
     * valid.
     */
    private String validateAccounts(List<Map<String, String>> accounts, String existingAccountsJson) {
        if (accounts == null) {
            return null;
        }
        for (Map<String, String> acc : accounts) {
            String accUser = acc.get("user");
            if (accUser == null || accUser.isBlank()) {
                continue;
            }
            String accPassword = acc.get("password");
            if (accPassword != null && !accPassword.isBlank()) {
                continue;
            }
            if (findExistingAccountPassword(existingAccountsJson, accUser) == null) {
                return "추가 계정 '" + accUser + "'의 비밀번호를 입력하세요.";
            }
        }
        return null;
    }

    /**
     * Rebuilds the "accounts" JSON array from the admin UI's rows - call only after
     * validateAccounts() has confirmed every row resolves to a password. A row with a blank
     * password reuses the matching user's already-stored (AES-encrypted) password from
     * existingAccountsJson, if any.
     */
    private String buildAccountsJson(List<Map<String, String>> accounts, String existingAccountsJson) {
        if (accounts == null || accounts.isEmpty()) {
            return null;
        }
        ArrayNode accArr = mapper.createArrayNode();
        for (Map<String, String> acc : accounts) {
            String accUser = acc.get("user");
            if (accUser == null || accUser.isBlank()) {
                continue;
            }
            String accPassword = acc.get("password");
            String encoded = (accPassword != null && !accPassword.isBlank())
                    ? encodePassword(accPassword)
                    : findExistingAccountPassword(existingAccountsJson, accUser);
            ObjectNode accNode = mapper.createObjectNode();
            accNode.put("user", accUser);
            accNode.put("password", encoded);
            accArr.add(accNode);
        }
        return accArr.size() > 0 ? accArr.toString() : null;
    }

    private String findExistingAccountPassword(String existingAccountsJson, String user) {
        if (existingAccountsJson == null) {
            return null;
        }
        try {
            for (JsonNode acc : mapper.readTree(existingAccountsJson)) {
                if (user.equals(acc.path("user").asText(""))) {
                    return acc.path("password").asText(null);
                }
            }
        } catch (IOException ignored) {
            // Malformed - treat as "no existing password to reuse".
        }
        return null;
    }

    /**
     * "session_thresholds": [t1..t5] - per-instance override of the dashboard's active-session
     * color/gauge thresholds (see app.js DEFAULT_SESSION_THRESHOLDS). Null/empty from the admin UI
     * means "don't override" (global default applies); otherwise exactly 5 values are required,
     * matching what app.js's getSessColor() expects.
     */
    private String validateSessionThresholds(List<Integer> sessionThresholds) {
        if (sessionThresholds == null || sessionThresholds.isEmpty()) {
            return null;
        }
        if (sessionThresholds.size() != 5 || sessionThresholds.contains(null)) {
            return "세션 임계치는 5개 값을 모두 입력해야 합니다.";
        }
        return null;
    }

    private String buildSessionThresholdsJson(List<Integer> sessionThresholds) {
        if (sessionThresholds == null || sessionThresholds.isEmpty()) {
            return null;
        }
        ArrayNode arr = mapper.createArrayNode();
        for (Integer t : sessionThresholds) {
            arr.add(t);
        }
        return arr.toString();
    }
}
