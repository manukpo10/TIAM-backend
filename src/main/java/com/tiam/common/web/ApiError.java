package com.tiam.common.web;

import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ApiError {

    private final int status;
    private final String error;
    private final String message;
    private final String path;
    private final Instant timestamp;
    private final List<String> details;

    public static ApiError of(int status, String error, String message, String path) {
        return ApiError.builder()
                .status(status)
                .error(error)
                .message(message)
                .path(path)
                .timestamp(Instant.now())
                .build();
    }

    public static ApiError of(int status, String error, String message, String path,
            List<String> details) {
        return ApiError.builder()
                .status(status)
                .error(error)
                .message(message)
                .path(path)
                .timestamp(Instant.now())
                .details(details)
                .build();
    }
}
