import java.io.File;
import java.util.Properties;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * 설계문서(비밀번호_암호화_설계_2026-09-21.md)의 핵심 시나리오를 실제로 돌려보는 데모.
 * 실행: run.bat 참고. dbagent-poc.key 파일을 이 디렉터리에 만든다(최초 실행 시 자동 생성).
 */
public class Demo {

    public static void main(String[] args) throws Exception {
        String keyFile = "dbagent-poc.key";

        section("0. 키 파일 자동 생성 (최초 기동 시나리오)");
        new File(keyFile).delete(); // 데모를 몇 번 돌려도 항상 "최초 기동"부터 보여주기 위해
        MasterKeyProvider keys = MasterKeyProvider.load(keyFile);
        CredentialCipher cipher = new CredentialCipher(keys);
        System.out.println("-> 활성 키: " + cipher.activeKeyId());

        section("1. 암호화/복호화 왕복(round-trip)");
        String plain = "dbagent_user_password!";
        String enc = cipher.encrypt(plain);
        String dec = cipher.decrypt(enc);
        System.out.println("평문        : " + plain);
        System.out.println("저장될 형태 : " + enc);
        System.out.println("복호화 결과 : " + dec);
        check("왕복 결과가 원본과 일치", plain.equals(dec));

        section("2. 같은 평문이라도 암호화할 때마다 다른 암호문 (IV 랜덤)");
        String enc2 = cipher.encrypt(plain);
        System.out.println("1차: " + enc);
        System.out.println("2차: " + enc2);
        check("두 암호문이 서로 다름(IV가 매번 새로 생성됨)", !enc.equals(enc2));
        check("그래도 둘 다 같은 평문으로 복호화됨", plain.equals(cipher.decrypt(enc2)));

        section("3. 변조된 암호문은 복호화 실패(인증 태그 검증)");
        char[] chars = enc.toCharArray();
        // base64 본문 중간 한 글자를 다른 문자로 바꿔서 변조를 흉내낸다.
        int tamperIdx = enc.length() - 10;
        chars[tamperIdx] = (chars[tamperIdx] == 'A') ? 'B' : 'A';
        String tampered = new String(chars);
        try {
            cipher.decrypt(tampered);
            check("변조된 암호문은 예외를 던져야 함", false);
        } catch (RuntimeException e) {
            System.out.println("-> 예상대로 복호화 실패: " + e.getMessage());
            check("변조 시 예외 발생", true);
        }

        section("4. 레거시 값 통과 (마이그레이션 중 혼재 상태)");
        String legacyB64 = "B64(" + java.util.Base64.getEncoder().encodeToString("old_plain_pw".getBytes()) + ")";
        String legacyPlain = "still_plaintext_pw";
        System.out.println("B64(...)  decrypt -> " + cipher.decrypt(legacyB64));
        System.out.println("평문      decrypt -> " + cipher.decrypt(legacyPlain));
        check("B64(...) 레거시 값이 올바르게 디코드됨", "old_plain_pw".equals(cipher.decrypt(legacyB64)));
        check("평문 레거시 값이 그대로 통과됨", legacyPlain.equals(cipher.decrypt(legacyPlain)));

        section("5. needsReencrypt() - 재암호화 스캔이 뭘 걸러내는지");
        check("평문은 재암호화 대상", cipher.needsReencrypt(legacyPlain));
        check("B64(...)는 재암호화 대상", cipher.needsReencrypt(legacyB64));
        check("이미 ENC(v1:활성키:...)는 재암호화 불필요", !cipher.needsReencrypt(enc));
        check("빈 문자열은 재암호화 불필요(빈 비밀번호는 암호화 안 함)", !cipher.needsReencrypt(""));

        section("6. 다른 서버(다른 키 파일)로 dbconfig.db만 복사해온 경우");
        MasterKeyProvider otherServerKeys = freshOtherServerKey();
        CredentialCipher otherServerCipher = new CredentialCipher(otherServerKeys);
        try {
            otherServerCipher.decrypt(enc); // 원래 서버(keyFile)에서 만든 암호문을 다른 키로 복호화 시도
            check("다른 키로는 복호화되면 안 됨", false);
        } catch (RuntimeException e) {
            // 실측 결과(설계문서 작성 시점엔 몰랐던 디테일): 자동 생성된 키는 둘 다 기본 keyId "k1"을
            // 쓰므로, 실제로는 "keyId를 못 찾음"(IllegalArgumentException)이 아니라 "keyId는 있는데
            // 키 값이 달라 태그 검증 실패"(AEADBadTagException)로 떨어진다. 결과(복호화 실패)는 설계
            // 의도와 동일하지만, 예외 메시지/타입은 이 케이스가 실무에서 더 흔하다 - 설계문서에 반영 필요.
            System.out.println("-> 예상대로 복호화 실패(원인: " + e.getClass().getSimpleName() + " - " + e.getMessage() + ")");
            check("어느 형태로든 명확히 실패(성공적으로 복호화되지 않음)", true);
        }

        section("7. 키 로테이션 (k1 -> k2, 구키는 유지)");
        MasterKeyProvider rotated = rotateKey(keyFile);
        CredentialCipher rotatedCipher = new CredentialCipher(rotated);
        System.out.println("-> 새 활성 키: " + rotatedCipher.activeKeyId());
        check("구키(k1)로 만든 암호문도 여전히 복호화 가능(구키 보존)", plain.equals(rotatedCipher.decrypt(enc)));
        check("구키 암호문은 재암호화 대상으로 표시됨", rotatedCipher.needsReencrypt(enc));
        String reenc = rotatedCipher.encrypt(rotatedCipher.decrypt(enc)); // 재암호화 스캔이 하는 일과 동일
        System.out.println("-> 재암호화 후: " + reenc);
        check("재암호화 후에는 새 키(k2) 기준으로 더 이상 대상이 아님", !rotatedCipher.needsReencrypt(reenc));
        check("재암호화 후에도 평문은 그대로", plain.equals(rotatedCipher.decrypt(reenc)));

        section("결과");
        System.out.println(failCount == 0
                ? "전부 통과 (" + passCount + "/" + (passCount + failCount) + ")"
                : "실패 있음! (" + passCount + "/" + (passCount + failCount) + " 통과)");
        if (failCount > 0) {
            System.exit(1);
        }
    }

    // ── 데모 보조 함수 ──────────────────────────────────────────────

    private static MasterKeyProvider freshOtherServerKey() throws Exception {
        String otherFile = "dbagent-poc-other-server.key";
        new File(otherFile).delete();
        return MasterKeyProvider.load(otherFile);
    }

    /** 키 파일에 k2를 추가하고 active를 k2로 바꾼 뒤(k1은 유지), 다시 로드한다 - 3-5절 로테이션 절차 1~2단계. */
    private static MasterKeyProvider rotateKey(String keyFile) throws Exception {
        Properties props = new Properties();
        java.io.InputStream in = new java.io.FileInputStream(keyFile);
        try {
            props.load(in);
        } finally {
            in.close();
        }
        byte[] newKey = new byte[32];
        new java.security.SecureRandom().nextBytes(newKey);
        props.setProperty("key.k2", java.util.Base64.getEncoder().encodeToString(newKey));
        props.setProperty("created.k2", new java.util.Date().toString());
        props.setProperty("active", "k2");
        OutputStream out = new FileOutputStream(keyFile);
        try {
            props.store(out, "rotated");
        } finally {
            out.close();
        }
        return MasterKeyProvider.load(keyFile);
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }

    private static int passCount = 0;
    private static int failCount = 0;

    private static void check(String label, boolean ok) {
        if (ok) {
            passCount++;
            System.out.println("  [OK] " + label);
        } else {
            failCount++;
            System.out.println("  [FAIL] " + label);
        }
    }
}
