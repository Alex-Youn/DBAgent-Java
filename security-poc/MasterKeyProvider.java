import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 설계문서(비밀번호_암호화_설계_2026-09-21.md) 3절의 프로토타입 구현.
 * - 키 파일(java.util.Properties)에서 active 키를 읽고, 없으면 자동 생성한다.
 * - Cipher.getMaxAllowedKeyLength("AES")로 이 JVM이 AES-256을 허용하는지 확인해서
 *   가능하면 32바이트, 아니면 16바이트(AES-128)로 자동 폴백한다(폐쇄망 IBM J9 대응).
 * - Java 8 호환 문법만 사용(var/record/텍스트블록 금지) - AIX 포팅 가능성을 이 프로토타입
 *   단계에서부터 검증하기 위함.
 */
public class MasterKeyProvider {

    private static final String ENV_KEY = "DBAGENT_MASTER_KEY";

    private final String activeKeyId;
    private final Map<String, SecretKeySpec> keysById;

    private MasterKeyProvider(String activeKeyId, Map<String, SecretKeySpec> keysById) {
        this.activeKeyId = activeKeyId;
        this.keysById = keysById;
    }

    public String activeKeyId() {
        return activeKeyId;
    }

    public SecretKeySpec keyFor(String keyId) {
        return keysById.get(keyId);
    }

    public SecretKeySpec activeKey() {
        return keysById.get(activeKeyId);
    }

    /** 키 소스 우선순위: 환경변수 DBAGENT_MASTER_KEY > keyFilePath > 없으면 자동 생성. */
    public static MasterKeyProvider load(String keyFilePath) throws Exception {
        String envValue = System.getenv(ENV_KEY);
        if (envValue != null && !envValue.trim().isEmpty()) {
            return fromEnv(envValue.trim());
        }
        File file = new File(keyFilePath);
        Properties props = new Properties();
        if (file.exists()) {
            try (InputStream in = new FileInputStream(file)) {
                props.load(in);
            }
        } else {
            generateInto(props);
            save(file, props);
            System.out.println("[MasterKeyProvider] 키 파일이 없어 새로 생성했습니다: " + file.getAbsolutePath());
        }
        return fromProperties(props);
    }

    private static MasterKeyProvider fromEnv(String value) {
        // 형식: "k1:<base64키>"
        int idx = value.indexOf(':');
        if (idx < 0) {
            throw new IllegalArgumentException(ENV_KEY + " 형식이 올바르지 않습니다(예: k1:base64키)");
        }
        String keyId = value.substring(0, idx);
        byte[] raw = Base64.getDecoder().decode(value.substring(idx + 1));
        Map<String, SecretKeySpec> keys = new HashMap<String, SecretKeySpec>();
        keys.put(keyId, new SecretKeySpec(raw, "AES"));
        System.out.println("[MasterKeyProvider] 환경변수 " + ENV_KEY + "에서 키 로드 (keyId=" + keyId + ", " + (raw.length * 8) + "bit)");
        return new MasterKeyProvider(keyId, keys);
    }

    private static void generateInto(Properties props) throws Exception {
        int maxLen = Cipher.getMaxAllowedKeyLength("AES");
        int keyBytes;
        if (maxLen >= 256) {
            keyBytes = 32; // AES-256
        } else {
            keyBytes = 16; // AES-128 폴백 (정책 파일 미설치 환경, 예: IBM J9 Java 8 폐쇄망)
            System.out.println("[MasterKeyProvider] WARN: 이 JVM은 AES-256을 허용하지 않습니다(max=" + maxLen
                    + "bit) - AES-128로 자동 생성합니다.");
        }
        byte[] raw = new byte[keyBytes];
        new SecureRandom().nextBytes(raw);
        props.setProperty("active", "k1");
        props.setProperty("key.k1", Base64.getEncoder().encodeToString(raw));
        props.setProperty("created.k1", new Date().toString());
    }

    private static void save(File file, Properties props) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            props.store(out, "DBAgent credential master key - git commit, backup_* 폴더 복사 금지");
        }
        // POSIX 환경이면 소유자만 읽기/쓰기(600)로 제한. Windows는 POSIX 뷰가 없으므로 조용히 건너뜀.
        try {
            if (file.toPath().getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Set<PosixFilePermission> perms = new HashSet<PosixFilePermission>();
                perms.add(PosixFilePermission.OWNER_READ);
                perms.add(PosixFilePermission.OWNER_WRITE);
                Files.setPosixFilePermissions(file.toPath(), perms);
            }
        } catch (Exception e) {
            System.out.println("[MasterKeyProvider] WARN: 파일 권한(600) 설정 실패 - " + e.getMessage());
        }
    }

    private static MasterKeyProvider fromProperties(Properties props) {
        String active = props.getProperty("active");
        if (active == null || active.trim().isEmpty()) {
            throw new IllegalStateException("키 파일에 active 항목이 없습니다");
        }
        Map<String, SecretKeySpec> keys = new HashMap<String, SecretKeySpec>();
        for (String name : props.stringPropertyNames()) {
            if (name.startsWith("key.")) {
                String keyId = name.substring("key.".length());
                byte[] raw = Base64.getDecoder().decode(props.getProperty(name));
                keys.put(keyId, new SecretKeySpec(raw, "AES"));
            }
        }
        if (!keys.containsKey(active)) {
            throw new IllegalStateException("active 키(" + active + ")에 해당하는 key." + active + " 항목이 없습니다");
        }
        System.out.println("[MasterKeyProvider] 키 로드 완료 (active=" + active + ", 보유 키 " + keys.size() + "개, "
                + (keys.get(active).getEncoded().length * 8) + "bit)");
        return new MasterKeyProvider(active, keys);
    }
}
