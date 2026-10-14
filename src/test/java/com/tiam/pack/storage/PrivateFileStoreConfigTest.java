package com.tiam.pack.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tiam.pack.config.PackProperties;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrivateFileStoreConfigTest {

    @TempDir Path dir;
    PackProperties properties;
    PrivateFileStoreConfig config = new PrivateFileStoreConfig();

    @BeforeEach
    void setUp() {
        properties = new PackProperties();
        properties.getStorage().setLocalDir(dir.toString());
    }

    @Test
    void noType_noCredentials_usesTheLocalStoreForDevelopment() {
        assertThat(config.privateFileStore(properties)).isInstanceOf(LocalPrivateFileStore.class);
    }

    @Test
    void noType_withEveryR2Credential_usesR2() {
        fillR2();

        assertThat(config.privateFileStore(properties)).isInstanceOf(R2PrivateFileStore.class);
    }

    @Test
    void noType_withOnlySomeR2Credentials_staysLocalInsteadOfBreakingStartup() {
        properties.getStorage().setR2AccountId("acct");
        properties.getStorage().setR2Bucket("bucket");

        assertThat(config.privateFileStore(properties)).isInstanceOf(LocalPrivateFileStore.class);
    }

    @Test
    void explicitLocal_winsEvenWhenR2IsConfigured() {
        fillR2();
        properties.getStorage().setType("local");

        assertThat(config.privateFileStore(properties)).isInstanceOf(LocalPrivateFileStore.class);
    }

    @Test
    void explicitR2_withIncompleteCredentials_failsOnUseNotOnBoot() {
        properties.getStorage().setType("r2");
        properties.getStorage().setR2AccountId("acct");

        PrivateFileStore store = config.privateFileStore(properties);

        assertThat(store).isNotInstanceOf(LocalPrivateFileStore.class).isNotInstanceOf(R2PrivateFileStore.class);
        assertThatThrownBy(() -> store.signedDownloadUrl("packs/v1/x.pdf", Duration.ofSeconds(300), "x.pdf"))
                .isInstanceOf(StorageUnavailableException.class);
    }

    @Test
    void unknownType_isTreatedAsAMisconfigurationThatFailsOnUse() {
        properties.getStorage().setType("s3-somewhere");

        PrivateFileStore store = config.privateFileStore(properties);

        assertThatThrownBy(() -> store.signedDownloadUrl("packs/v1/x.pdf", Duration.ofSeconds(300), "x.pdf"))
                .isInstanceOf(StorageUnavailableException.class);
    }

    @Test
    void onlyR2_countsAsARemoteStore_soSalesRefuseToStartOnAnythingElse() {
        assertThat(config.privateFileStore(properties).isRemote()).as("local folder").isFalse();

        properties.getStorage().setType("r2"); // incomplete credentials -> unavailable placeholder
        assertThat(config.privateFileStore(properties).isRemote()).as("unavailable placeholder").isFalse();

        fillR2();
        assertThat(config.privateFileStore(properties).isRemote()).as("R2").isTrue();
    }

    private void fillR2() {
        properties.getStorage().setR2AccountId("acct");
        properties.getStorage().setR2AccessKeyId("akid");
        properties.getStorage().setR2SecretAccessKey("secret");
        properties.getStorage().setR2Bucket("bucket");
    }
}
