package com.zaalime.orders.service.web;

import org.springframework.http.HttpStatus;

/** Business error that maps straight to an HTTP status + problem detail. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String title;

    public ApiException(HttpStatus status, String title, String detail) {
        super(detail);
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, "Not found", detail);
    }

    public static ApiException badRequest(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "Bad request", detail);
    }

    public static ApiException conflict(String title, String detail) {
        return new ApiException(HttpStatus.CONFLICT, title, detail);
    }
}
