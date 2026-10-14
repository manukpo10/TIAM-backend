package com.tiam.pack.storage;

import com.tiam.pack.config.PackProperties;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Picks the {@link PrivateFileStore}. {@code PACK_STORAGE} says which one; left empty it means "R2 when
 * every R2 credential is present, otherwise the local folder", which is what a developer wants and what
 * production gets as soon as the four R2 variables are set.
 *
 * <p>A wrong setting never stops the app from booting (that would take the Desafío down too): the store
 * degrades to one that fails when a link is requested, after a clear ERROR in the boot log.
 */
@Slf4j
@Configuration
public class PrivateFileStoreConfig {

    @Bean
    public PrivateFileStore privateFileStore(PackProperties properties) {
        return build(properties.getStorage(), Clock.systemUTC());
    }

    PrivateFileStore build(PackProperties.Storage storage, Clock clock) {
        String type = storage.getType() == null ? "" : storage.getType().strip().toLowerCase();
        boolean r2Complete = StringUtils.hasText(storage.getR2AccountId())
                && StringUtils.hasText(storage.getR2AccessKeyId())
                && StringUtils.hasText(storage.getR2SecretAccessKey())
                && StringUtils.hasText(storage.getR2Bucket());

        switch (type) {
            case "" -> {
                return r2Complete ? r2(storage, clock) : local(storage, clock);
            }
            case "local" -> {
                return local(storage, clock);
            }
            case "r2" -> {
                if (r2Complete) {
                    return r2(storage, clock);
                }
                String reason = "PACK_STORAGE=r2 but R2_ACCOUNT_ID, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY or R2_BUCKET is missing";
                log.error("Pack file storage UNAVAILABLE: {}. Download links will fail until it is fixed.", reason);
                return new UnavailablePrivateFileStore(reason);
            }
            default -> {
                String reason = "PACK_STORAGE must be 'r2' or 'local'";
                log.error("Pack file storage UNAVAILABLE: {}. Download links will fail until it is fixed.", reason);
                return new UnavailablePrivateFileStore(reason);
            }
        }
    }

    private PrivateFileStore r2(PackProperties.Storage storage, Clock clock) {
        try {
            log.info("Pack file storage: Cloudflare R2 (bucket={})", storage.getR2Bucket());
            return new R2PrivateFileStore(storage.getR2AccountId().strip(), storage.getR2Bucket().strip(),
                    new S3Presigner(storage.getR2AccessKeyId().strip(), storage.getR2SecretAccessKey().strip(),
                            R2PrivateFileStore.REGION, clock));
        } catch (IllegalArgumentException e) {
            log.error("Pack file storage UNAVAILABLE: {}. Download links will fail until it is fixed.", e.getMessage());
            return new UnavailablePrivateFileStore(e.getMessage());
        }
    }

    private PrivateFileStore local(PackProperties.Storage storage, Clock clock) {
        log.warn("Pack file storage: LOCAL folder '{}' (development only). Set the R2_* variables for production.",
                storage.getLocalDir());
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        return new LocalPrivateFileStore(Path.of(storage.getLocalDir()), storage.getLocalBaseUrl(), secret, clock);
    }
}
