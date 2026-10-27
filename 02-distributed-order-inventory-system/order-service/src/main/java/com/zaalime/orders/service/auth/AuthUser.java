package com.zaalime.orders.service.auth;

import org.springframework.security.oauth2.jwt.Jwt;

/** The caller, as read from the validated JWT. */
public record AuthUser(long id, String username) {

    public static AuthUser from(Jwt jwt) {
        Number uid = jwt.getClaim("uid");
        return new AuthUser(uid.longValue(), jwt.getSubject());
    }
}
