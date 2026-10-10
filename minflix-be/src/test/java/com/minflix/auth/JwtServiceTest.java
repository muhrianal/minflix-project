package com.minflix.auth;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "test-secret-0123456789abcdef0123456789";

    private final JwtService jwt = new JwtService(SECRET);

    @Test
    void issuedTokenVerifiesToTheUserId() {
        assertThat(jwt.verify(jwt.issue("user-1"))).isEqualTo("user-1");
    }

    @Test
    void tokenExpiresAfter24Hours() {
        String token = jwt.issue("user-1");
        Date expiration = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).build()
                .parseSignedClaims(token).getPayload().getExpiration();

        assertThat(expiration.toInstant())
                .isBetween(Instant.now().plusSeconds(24 * 3600 - 60), Instant.now().plusSeconds(24 * 3600 + 60));
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        String foreign = new JwtService("another-secret-0123456789abcdef01234").issue("user-1");

        assertThatThrownBy(() -> jwt.verify(foreign)).isInstanceOf(JwtException.class);
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = jwt.issue("user-1");
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");

        assertThatThrownBy(() -> jwt.verify(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void expiredTokenIsRejected() {
        String expired = Jwts.builder()
                .subject("user-1")
                .expiration(Date.from(Instant.now().minusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwt.verify(expired)).isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void garbageIsRejected() {
        assertThatThrownBy(() -> jwt.verify("not-a-jwt")).isInstanceOf(JwtException.class);
    }
}
