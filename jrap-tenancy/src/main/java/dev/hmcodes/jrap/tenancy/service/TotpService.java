package dev.hmcodes.jrap.tenancy.service;

import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** TOTP two-factor authentication (FR-AUTH-2). */
@Component
public class TotpService {

    private final SecretGenerator secretGenerator = new DefaultSecretGenerator();
    private final CodeVerifier verifier = new DefaultCodeVerifier(
            new DefaultCodeGenerator(HashingAlgorithm.SHA1), new SystemTimeProvider());

    public String generateSecret() {
        return secretGenerator.generate();
    }

    public boolean verify(String secret, String code) {
        return secret != null && code != null && verifier.isValidCode(secret, code);
    }

    public String otpauthUri(String secret, String accountEmail) {
        String issuer = "JRAP";
        return "otpauth://totp/" + issuer + ":" + URLEncoder.encode(accountEmail, StandardCharsets.UTF_8)
                + "?secret=" + secret + "&issuer=" + issuer;
    }
}
