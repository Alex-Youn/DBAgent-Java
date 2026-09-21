import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 설계문서 2절의 포맷/알고리즘 프로토타입.
 *   ENC(v1:<keyId>:<base64(IV 12B .. ciphertext .. tag 16B)>)
 * - AES/GCM/NoPadding, 매 암호화마다 SecureRandom 12바이트 IV, 128비트 태그, AAD="v1:<keyId>".
 * - 레거시 B64(...) 및 평문은 그대로 통과(decrypt), 마이그레이션 유예.
 * - encrypt()는 항상 ENC(v1:<active keyId>:...)만 생산.
 */
public class CredentialCipher {

    private static final String ENC_PREFIX = "ENC(";
    private static final String ENC_SUFFIX = ")";
    private static final String B64_PREFIX = "B64(";
    private static final String B64_SUFFIX = ")";
    private static final String FORMAT_VERSION = "v1";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final MasterKeyProvider keyProvider;
    private final SecureRandom secureRandom = new SecureRandom();

    public CredentialCipher(MasterKeyProvider keyProvider) {
        this.keyProvider = keyProvider;
    }

    public String activeKeyId() {
        return keyProvider.activeKeyId();
    }

    /** 항상 ENC(v1:<active keyId>:...) 형식을 생산한다. null/빈 문자열은 그대로 통과. */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        try {
            String keyId = keyProvider.activeKeyId();
            SecretKeySpec key = keyProvider.activeKey();
            byte[] iv = new byte[IV_LEN];
            secureRandom.nextBytes(iv);
            byte[] aad = (FORMAT_VERSION + ":" + keyId).getBytes(StandardCharsets.UTF_8);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad);
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buf = ByteBuffer.allocate(iv.length + ct.length);
            buf.put(iv).put(ct);
            String body = Base64.getEncoder().encodeToString(buf.array());
            return ENC_PREFIX + FORMAT_VERSION + ":" + keyId + ":" + body + ENC_SUFFIX;
        } catch (Exception e) {
            throw new RuntimeException("암호화 실패", e);
        }
    }

    /**
     * ENC(...) -> 해당 keyId로 복호화(미지 버전/keyId는 예외).
     * B64(...) -> 레거시 디코드.
     * 그 외 -> 레거시 평문 그대로 통과.
     */
    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) {
            return stored;
        }
        if (stored.startsWith(ENC_PREFIX) && stored.endsWith(ENC_SUFFIX)) {
            return decryptEnc(stored);
        }
        if (stored.startsWith(B64_PREFIX) && stored.endsWith(B64_SUFFIX)) {
            String encoded = stored.substring(B64_PREFIX.length(), stored.length() - B64_SUFFIX.length());
            return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        }
        return stored; // 레거시 평문
    }

    private String decryptEnc(String stored) {
        String inner = stored.substring(ENC_PREFIX.length(), stored.length() - ENC_SUFFIX.length());
        String[] parts = inner.split(":", 3);
        if (parts.length != 3) {
            throw new IllegalArgumentException("ENC(...) 포맷이 올바르지 않습니다: " + stored);
        }
        String version = parts[0];
        String keyId = parts[1];
        String body = parts[2];
        if (!FORMAT_VERSION.equals(version)) {
            throw new IllegalArgumentException("알 수 없는 암호화 포맷 버전입니다: " + version);
        }
        SecretKeySpec key = keyProvider.keyFor(keyId);
        if (key == null) {
            throw new IllegalArgumentException("키 ID '" + keyId + "'를 찾을 수 없습니다 - 이 서버의 키 파일에 "
                    + "해당 키가 없습니다(다른 서버의 dbconfig.db를 키 파일 없이 옮겨온 경우일 수 있습니다).");
        }
        byte[] raw = Base64.getDecoder().decode(body);
        byte[] iv = new byte[IV_LEN];
        byte[] ct = new byte[raw.length - IV_LEN];
        System.arraycopy(raw, 0, iv, 0, IV_LEN);
        System.arraycopy(raw, IV_LEN, ct, 0, ct.length);
        byte[] aad = (version + ":" + keyId).getBytes(StandardCharsets.UTF_8);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad);
            byte[] plain = cipher.doFinal(ct);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            throw new RuntimeException("인증 태그 불일치 - 암호문이 변조되었거나 잘못된 키입니다", e);
        } catch (Exception e) {
            throw new RuntimeException("복호화 실패", e);
        }
    }

    /** 이미 활성 키로 암호화된(ENC(v1:<active>:...)) 값이면 false, 그 외(평문/B64/구키/구버전)는 true. */
    public boolean needsReencrypt(String stored) {
        if (stored == null || stored.isEmpty()) {
            return false;
        }
        if (!stored.startsWith(ENC_PREFIX) || !stored.endsWith(ENC_SUFFIX)) {
            return true; // 평문 또는 B64(...)
        }
        String inner = stored.substring(ENC_PREFIX.length(), stored.length() - ENC_SUFFIX.length());
        String[] parts = inner.split(":", 3);
        if (parts.length != 3) {
            return true;
        }
        boolean sameVersion = FORMAT_VERSION.equals(parts[0]);
        boolean sameKey = keyProvider.activeKeyId().equals(parts[1]);
        return !(sameVersion && sameKey);
    }
}
