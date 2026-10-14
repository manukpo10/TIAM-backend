package com.tiam.pack.dto;

/**
 * A short-lived signed URL for one file. The browser navigates to {@code url} right away; asking again
 * issues (and counts) a new one.
 */
public record DownloadLinkResponse(String url, long expiresInSeconds) {
}
