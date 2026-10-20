package com.tiam.pack.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tiam.challenge.config.WhatsAppProperties;
import com.tiam.common.email.EmailMessage;
import com.tiam.pack.config.PackProperties;
import com.tiam.pack.config.PackSalesReadiness;
import com.tiam.pack.domain.PackEdition;
import com.tiam.pack.domain.PackOrder;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Pack's Desafío month in the emails tells the same truth as the Cuadernos': the 30 days run from the PURCHASE (the
 * Desafío's clock does not wait for a WhatsApp message), the buyer can start right away from the personal link and,
 * with no link, by writing on WhatsApp. Nothing is "activated" by a message and no email promises "el primer ejercicio".
 */
class PackDesafioWordingTest {

    static final String TOKEN = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcde";
    static final String REPLACEMENT = "R".repeat(43);
    static final String LINK = "https://tiamdigital.com/desafio/tok";

    PackAccessEmailComposer composer;

    @BeforeEach
    void setUp() {
        PackProperties properties = new PackProperties();
        properties.setDownloadBaseUrl("https://tiamdigital.com/pack/descargas");
        properties.setLandingBaseUrl("https://tiamdigital.com");
        WhatsAppProperties whatsApp = new WhatsAppProperties();
        whatsApp.setBusinessNumber("5492214817297");
        composer = new PackAccessEmailComposer(properties, whatsApp, org.mockito.Mockito.mock(PackSalesReadiness.class));
    }

    @Test
    void withTheLink_theBuyerCanStartRightAway_andTheClockRunsFromThePurchase() {
        EmailMessage message = composer.compose(packWithMonth(), Optional.of(LINK));

        for (String text : List.of(message.html(), message.text())) {
            assertThat(text).contains("30 días desde la compra").contains("Podés empezar ahora mismo desde tu link personal")
                    .contains("Si preferís recibir los ejercicios por WhatsApp")
                    .contains("mismo número que dejaste al comprar");
            assertThat(text).doesNotContain("te llega el primer ejercicio").doesNotContain("Para activarlo")
                    .doesNotContain("Ya está activo");
        }
        assertThat(message.html()).contains("Abrir mi mes de juegos").contains(LINK);
        assertThat(message.text()).contains(LINK);
    }

    @Test
    void withoutALink_whatsAppIsTheWayToStart_butTheClockStillRunsFromThePurchase() {
        EmailMessage message = composer.compose(packWithMonth(), Optional.empty());

        for (String text : List.of(message.html(), message.text())) {
            assertThat(text).contains("30 días desde la compra")
                    .contains("Para empezar, escribinos por WhatsApp desde el mismo número que dejaste al comprar");
            assertThat(text).doesNotContain("te llega el primer ejercicio").doesNotContain("Para activarlo");
        }
        assertThat(message.html()).contains("Escribir por WhatsApp").doesNotContain("Abrir mi mes de juegos");
    }

    @Test
    void theWhatsAppButtonOpensAChatThatSaysIWantToStart_notToActivate() {
        EmailMessage message = composer.compose(packWithMonth(), Optional.empty());

        assertThat(message.html()).contains("compr%C3%A9%20el%20Pack%20TIAM%20con%20el%20mes%20de%20juegos%20y%20quiero%20empezar")
                .doesNotContain("quiero%20activarlo");
        assertThat(message.text()).contains("quiero%20empezar").doesNotContain("quiero%20activarlo");
    }

    @Test
    void theLostLinkEmail_saysTheSame() {
        EmailMessage linked = composer.composeResend("ana@example.com",
                List.of(new PackAccessEmailComposer.ResendEntry(packWithMonth(), REPLACEMENT, Optional.of(LINK))), "k1");
        EmailMessage unlinked = composer.composeResend("ana@example.com",
                List.of(new PackAccessEmailComposer.ResendEntry(packWithMonth(), REPLACEMENT, Optional.empty())), "k2");

        for (EmailMessage message : List.of(linked, unlinked)) {
            for (String text : List.of(message.html(), message.text())) {
                assertThat(text).contains("30 días desde la compra");
                assertThat(text).doesNotContain("para activarlo").doesNotContain("Para activarlo")
                        .doesNotContain("te llega el primer ejercicio");
            }
        }
        assertThat(linked.text()).contains("Podés empezar ahora mismo desde tu link personal: " + LINK);
        assertThat(unlinked.text()).contains("Para empezar, escribinos por WhatsApp");
    }

    @Test
    void theRestOfThePacksCopyIsUnchanged() {
        EmailMessage message = composer.compose(packWithMonth(), Optional.of(LINK));

        assertThat(message.html()).contains("Tu mes de juegos").contains("Desafío de 30 días");
        assertThat(message.text()).contains("MES DE JUEGOS").contains("Tu mes de juegos del Desafío 30 días");
        assertThat(message.subject()).isEqualTo("Tu Pack TIAM está listo: descargá tus PDF");
        assertThat(message.html()).doesNotContain("Mes 1 del Desafío").doesNotContain("meses siguientes"); // the Cuadernos' offer terms
    }

    private static PackOrder packWithMonth() {
        PackOrder order = new PackOrder();
        order.setId(42L);
        order.setAccessToken(TOKEN);
        order.setEdition(PackEdition.FAMILIAS);
        order.setBuyerName("Ana Gómez");
        order.setEmail("ana@example.com");
        order.setWithGames(true);
        order.setChallengePurchaseId(77L);
        return order;
    }
}
