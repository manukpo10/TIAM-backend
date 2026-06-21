package com.tiam.common.storage;

import com.tiam.common.exception.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.UUID;

/**
 * Uploads images to a public Supabase Storage bucket using the service-role key,
 * and returns their public URL. The service key bypasses storage RLS, so uploads
 * are only possible server-side (the bucket stays read-only for the public).
 *
 * <p>Config (env vars): {@code SUPABASE_URL}, {@code SUPABASE_SERVICE_KEY}, {@code SUPABASE_BUCKET}.
 * If the URL or key are missing the service is considered unconfigured and rejects uploads
 * with a clear message instead of failing obscurely.
 */
@Service
public class SupabaseStorageService {

    private static final Set<String> ALLOWED_TYPES = Set.of("image/png", "image/jpeg");

    private final String baseUrl;
    private final String serviceKey;
    private final String bucket;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public SupabaseStorageService(
            @Value("${supabase.url:}") String supabaseUrl,
            @Value("${supabase.service-key:}") String serviceKey,
            @Value("${supabase.bucket:exercise-images}") String bucket) {
        this.baseUrl = supabaseUrl.endsWith("/")
            ? supabaseUrl.substring(0, supabaseUrl.length() - 1)
            : supabaseUrl;
        this.serviceKey = serviceKey;
        this.bucket = bucket;
    }

    /** Uploads an image and returns its public URL. */
    public String uploadImage(MultipartFile file) {
        if (baseUrl.isBlank() || serviceKey.isBlank()) {
            throw new BadRequestException(
                "Storage no configurado: faltan las variables SUPABASE_URL / SUPABASE_SERVICE_KEY.");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new BadRequestException("La imagen debe ser PNG o JPG.");
        }

        String objectPath = "exercises/" + UUID.randomUUID() + extensionFor(contentType);
        URI uploadUri = URI.create(baseUrl + "/storage/v1/object/" + bucket + "/" + objectPath);

        try {
            HttpRequest request = HttpRequest.newBuilder(uploadUri)
                .header("Authorization", "Bearer " + serviceKey)
                .header("Content-Type", contentType)
                .header("x-upsert", "true")
                .POST(HttpRequest.BodyPublishers.ofByteArray(file.getBytes()))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BadRequestException(
                    "No se pudo subir la imagen al storage (HTTP " + response.statusCode() + ").");
            }
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("Error subiendo la imagen: " + e.getMessage());
        }

        // Public URL (bucket must be public for reads).
        return baseUrl + "/storage/v1/object/public/" + bucket + "/" + objectPath;
    }

    private String extensionFor(String contentType) {
        return "image/png".equals(contentType) ? ".png" : ".jpg";
    }
}
