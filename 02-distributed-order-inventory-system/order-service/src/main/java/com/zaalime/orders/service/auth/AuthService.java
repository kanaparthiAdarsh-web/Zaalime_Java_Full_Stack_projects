package com.zaalime.orders.service.auth;

import com.zaalime.orders.service.config.AppProperties;
import com.zaalime.orders.service.web.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final AppProperties props;
    // checked when the username does not exist, so a miss costs the same as a wrong password
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtEncoder jwtEncoder, AppProperties props) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.props = props;
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }

    @Transactional
    public AuthDtos.TokenResponse register(AuthDtos.RegisterRequest req) {
        if (users.existsByUsernameIgnoreCase(req.username())) {
            throw ApiException.conflict("Username taken", "Username '" + req.username() + "' is already registered");
        }
        try {
            UserAccount user = users.saveAndFlush(new UserAccount(req.username(), passwordEncoder.encode(req.password()), "USER"));
            return issueToken(user);
        } catch (DataIntegrityViolationException e) {
            // two registrations racing for the same name
            throw ApiException.conflict("Username taken", "Username '" + req.username() + "' is already registered");
        }
    }

    @Transactional(readOnly = true)
    public AuthDtos.TokenResponse login(AuthDtos.LoginRequest req) {
        UserAccount user = users.findByUsernameIgnoreCase(req.username()).orElse(null);
        String hash = user != null ? user.getPasswordHash() : dummyHash;
        if (!passwordEncoder.matches(req.password(), hash) || user == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Unauthorized", "Invalid username or password");
        }
        return issueToken(user);
    }

    private AuthDtos.TokenResponse issueToken(UserAccount user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(props.jwt().ttl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.jwt().issuer())
                .subject(user.getUsername())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("uid", user.getId())
                .claim("roles", List.of(user.getRole()))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AuthDtos.TokenResponse(token, "Bearer", expiresAt, user.getId(), user.getUsername(), user.getRole());
    }
}
