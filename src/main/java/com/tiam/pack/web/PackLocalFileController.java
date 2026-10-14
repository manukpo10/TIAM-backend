package com.tiam.pack.web;

import com.tiam.pack.storage.LocalPrivateFileStore;
import com.tiam.pack.storage.PrivateFileStore;
import java.nio.file.Path;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the files of the LOCAL (development) store through the links it signed. When the active
 * store is anything else (R2 in production) this endpoint answers 404 to everything, so it adds no
 * surface there. Every failure is the same bare 404: no hint about which check failed.
 */
@RestController
@RequiredArgsConstructor
public class PackLocalFileController {

    private final PrivateFileStore store;

    @GetMapping("/packs/dev-files")
    public ResponseEntity<Resource> download(
            @RequestParam String key,
            @RequestParam long exp,
            @RequestParam String name,
            @RequestParam String sig) {
        if (!(store instanceof LocalPrivateFileStore local)) {
            return ResponseEntity.notFound().build();
        }
        Optional<Path> file = local.resolveSigned(key, exp, name, sig);
        if (file.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .cacheControl(CacheControl.noStore())
                .body(new FileSystemResource(file.get()));
    }
}
