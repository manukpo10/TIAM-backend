package com.tiam.pack.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tiam.pack.storage.LocalPrivateFileStore;
import com.tiam.pack.storage.PrivateFileStore;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PackLocalFileControllerTest {

    @TempDir Path dir;
    LocalPrivateFileStore store;
    MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(dir.resolve("packs/v1"));
        Files.writeString(dir.resolve("packs/v1/file.pdf"), "PDF-BYTES");
        store = new LocalPrivateFileStore(dir, "http://localhost:8080", "controller-test-secret-32-bytes!!!".getBytes(),
                Clock.fixed(Instant.now(), ZoneOffset.UTC));
        mvc = MockMvcBuilders.standaloneSetup(new PackLocalFileController(store)).build();
    }

    @Test
    void validLink_streamsTheFileAsAnAttachmentWithoutCaching() throws Exception {
        URI url = URI.create(store.signedDownloadUrl("packs/v1/file.pdf", Duration.ofSeconds(300), "My Pack.pdf"));

        mvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(content().string("PDF-BYTES"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("My Pack.pdf")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void tamperedLink_isNotFound_withoutSayingWhy() throws Exception {
        URI url = URI.create(store.signedDownloadUrl("packs/v1/file.pdf", Duration.ofSeconds(300), "x.pdf"));
        String tampered = url.getRawQuery().replaceAll("sig=[0-9a-f]+", "sig=" + "0".repeat(64));

        mvc.perform(get(URI.create(url.getPath() + "?" + tampered))).andExpect(status().isNotFound());
    }

    @Test
    void missingParameters_areABadRequest() throws Exception {
        mvc.perform(get("/packs/dev-files")).andExpect(status().isBadRequest());
    }

    @Test
    void whenTheActiveStoreIsNotTheLocalOne_everyRequestIsNotFound() throws Exception {
        PrivateFileStore r2Like = (key, ttl, name) -> "https://example.invalid/" + key;
        MockMvc other = MockMvcBuilders.standaloneSetup(new PackLocalFileController(r2Like)).build();
        URI url = URI.create(store.signedDownloadUrl("packs/v1/file.pdf", Duration.ofSeconds(300), "x.pdf"));

        other.perform(get(url)).andExpect(status().isNotFound());
    }
}
