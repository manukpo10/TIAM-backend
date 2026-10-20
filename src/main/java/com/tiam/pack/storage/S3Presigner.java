package com.tiam.pack.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Hand-written AWS Signature Version 4 presigner for S3-compatible GET requests (about 60 lines of
 * logic). It exists so the app does not pull in the AWS SDK just to sign one kind of URL: that SDK adds
 * several megabytes of classes and slows startup on a small Render instance, and everything it would
 * do for us here is a handful of HMACs. Correctness is pinned by the worked example in the AWS
 * documentation (see S3PresignerTest).
 *
 * <p>Scope is deliberately narrow: GET and HEAD, query-string authentication, only the {@code host} header
 * signed, {@code UNSIGNED-PAYLOAD}. Anything else (PUT, multipart, headers) is out of scope.
 */
final class S3Presigner {

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final long MAX_EXPIRY_SECONDS = 7L * 24 * 3600; // SigV4 hard limit
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final String accessKeyId;
    private final String secretAccessKey;
    private final String region;
    private final Clock clock;

    S3Presigner(String accessKeyId, String secretAccessKey, String region, Clock clock) {
        this.accessKeyId = accessKeyId;
        this.secretAccessKey = secretAccessKey;
        this.region = region;
        this.clock = clock;
    }

    /**
     * @param path          the object path as it appears in the URL, un-encoded, starting with "/"
     * @param extraQuery    extra query parameters that must be signed (e.g. response-content-disposition)
     */
    String presignGet(String scheme, String host, String path, Duration expires, Map<String, String> extraQuery) {
        return presign("GET", scheme, host, path, expires, extraQuery);
    }

    /**
     * The same query-string authentication for another bodiless method ("GET" to download, "HEAD" to ask whether an
     * object exists without reading it). The method is part of what is signed, so a URL is only good for the method it
     * was made for.
     */
    String presign(String method, String scheme, String host, String path, Duration expires, Map<String, String> extraQuery) {
        long seconds = expires.getSeconds();
        if (seconds < 1 || seconds > MAX_EXPIRY_SECONDS) {
            throw new IllegalArgumentException("Expiry must be between 1 second and 7 days");
        }

        Instant now = clock.instant();
        String amzDate = AMZ_DATE.format(now);
        String dateStamp = DATE_STAMP.format(now);
        String credentialScope = dateStamp + "/" + region + "/s3/aws4_request";

        TreeMap<String, String> params = new TreeMap<>();
        params.put("X-Amz-Algorithm", ALGORITHM);
        params.put("X-Amz-Credential", accessKeyId + "/" + credentialScope);
        params.put("X-Amz-Date", amzDate);
        params.put("X-Amz-Expires", String.valueOf(seconds));
        params.put("X-Amz-SignedHeaders", "host");
        extraQuery.forEach(params::put); // names are plain ASCII; they are percent-encoded once, below

        StringBuilder canonicalQuery = new StringBuilder();
        params.forEach((name, value) -> {
            if (canonicalQuery.length() > 0) {
                canonicalQuery.append('&');
            }
            canonicalQuery.append(encode(name)).append('=').append(encode(value));
        });

        String canonicalUri = encodePath(path);
        String canonicalRequest = method + "\n" + canonicalUri + "\n" + canonicalQuery + "\n"
                + "host:" + host + "\n\n" + "host\n" + "UNSIGNED-PAYLOAD";
        String stringToSign = ALGORITHM + "\n" + amzDate + "\n" + credentialScope + "\n" + sha256Hex(canonicalRequest);

        byte[] signingKey = hmac(hmac(hmac(hmac(("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8),
                dateStamp), region), "s3"), "aws4_request");
        String signature = HexFormat.of().formatHex(hmac(signingKey, stringToSign));

        return scheme + "://" + host + canonicalUri + "?" + canonicalQuery + "&X-Amz-Signature=" + signature;
    }

    /** RFC 3986 unreserved characters stay; everything else is percent-encoded as UTF-8 (space is %20, never +). */
    static String encode(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~';
            if (unreserved) {
                out.append((char) c);
            } else {
                out.append('%').append(String.format("%02X", c));
            }
        }
        return out.toString();
    }

    /** S3 does not double-encode: each segment is encoded once and the slashes stay. */
    static String encodePath(String path) {
        String[] segments = path.split("/", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                out.append('/');
            }
            out.append(encode(segments[i]));
        }
        return out.toString();
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
