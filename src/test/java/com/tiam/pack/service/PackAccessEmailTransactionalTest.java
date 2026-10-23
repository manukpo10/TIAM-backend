package com.tiam.pack.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tiam.challenge.config.WhatsAppProperties;
import com.tiam.common.email.EmailMessage;
import com.tiam.pack.config.PackProperties;
import com.tiam.pack.config.PackSalesReadiness;
import com.tiam.pack.domain.PackEdition;
import com.tiam.pack.domain.PackOrder;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What makes the delivery email read as a transactional receipt (and not as a marketing blast): a compact
 * «Resumen de tu compra» taken from the order, and a line near the top that tells a buyer whose mailbox filed it in
 * Spam what to do. The summary never invents data: a value the order does not have is left out.
 */
class PackAccessEmailTransactionalTest {

    static final String TOKEN = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcde";
    static final String REPLACEMENT = "R".repeat(43);
    static final String SPAM_LINE_HTML = "Si este mail llegó a Spam, marcalo como \"No es spam\" para que el botón de descarga funcione.";
    static final String SPAM_LINE_TEXT = "Si este mail llegó a Spam, marcalo como \"No es spam\" para que el enlace de descarga funcione.";
    /** 2026-10-24T01:30:00Z is 22:30 of the 23rd in Argentina (UTC-3): the date shown must be the Argentine one. */
    static final Instant PAID_AT = Instant.parse("2026-10-24T01:30:00Z");

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

    // --- the summary of the purchase -----------------------------------------------------------------------------------

    @Test
    void firstEmail_ofCuadernos_carriesTheSummaryOfThePurchase_inHtml() {
        EmailMessage message = composer.compose(cuadernos(), Optional.empty());

        assertThat(message.html()).contains("Resumen de tu compra")
                .contains(">Pedido<").contains(">N° 42<").contains(">Producto<").contains(">ARS 20.000<")
                .contains(">Importe pagado<").contains(">Fecha de pago<").contains(">23/10/2026<");
    }

    @Test
    void firstEmail_ofCuadernos_carriesTheSameSummary_inPlainText() {
        EmailMessage message = composer.compose(cuadernos(), Optional.empty());

        assertThat(message.text()).contains("RESUMEN DE TU COMPRA")
                .contains("Pedido: N° 42").contains("Producto: Cuadernos TIAM")
                .contains("Importe pagado: ARS 20.000").contains("Fecha de pago: 23/10/2026");
    }

    @Test
    void firstEmail_ofThePack_namesTheEditionAndTheExtraBooklet() {
        PackOrder order = pack();
        order.setWithExtra(true);
        order.setTotalAmount(new BigDecimal("27500.00"));

        EmailMessage message = composer.compose(order, Optional.empty());

        assertThat(message.html()).contains("Resumen de tu compra").contains("Pack TIAM · Edición Familias + Cuadernillo extra")
                .contains("ARS 27.500");
        assertThat(message.text()).contains("Producto: Pack TIAM · Edición Familias + Cuadernillo extra")
                .contains("Importe pagado: ARS 27.500");
    }

    @Test
    void theDesafioMonth_isInTheSummaryOnlyWhenItWasGranted() {
        PackOrder granted = cuadernos();
        granted.setWithGames(true);
        granted.setChallengePurchaseId(77L);
        PackOrder notGranted = cuadernos();
        notGranted.setWithGames(true);

        assertThat(composer.compose(granted, Optional.empty()).text()).contains("Producto: Cuadernos TIAM + mes del Desafío");
        assertThat(composer.compose(notGranted, Optional.empty()).text()).contains("Producto: Cuadernos TIAM\n")
                .doesNotContain("Desafío");
    }

    @Test
    void cents_areShownTheArgentineWay_andWholeAmountsHaveNone() {
        PackOrder withCents = cuadernos();
        withCents.setTotalAmount(new BigDecimal("12500.50"));

        assertThat(composer.compose(withCents, Optional.empty()).text()).contains("Importe pagado: ARS 12.500,50");
        assertThat(composer.compose(cuadernos(), Optional.empty()).text()).contains("Importe pagado: ARS 20.000\n");
    }

    @Test
    void aValueTheOrderDoesNotHave_isLeftOut_notInvented() {
        PackOrder bare = cuadernos();
        bare.setTotalAmount(null);
        bare.setPaidAt(null);

        EmailMessage message = composer.compose(bare, Optional.empty());

        assertThat(message.text()).contains("Pedido: N° 42").doesNotContain("Importe pagado").doesNotContain("Fecha de pago")
                .doesNotContain("null");
        assertThat(message.html()).contains("Resumen de tu compra").doesNotContain("Importe pagado")
                .doesNotContain("Fecha de pago").doesNotContain("null");
    }

    // --- the line for a buyer whose mailbox filed the email in Spam ------------------------------------------------------

    @Test
    void firstEmail_tellsTheBuyerWhatToDoIfItLandedInSpam_beforeTheDownloadButton() {
        for (PackOrder order : List.of(cuadernos(), pack())) {
            EmailMessage message = composer.compose(order, Optional.empty());

            assertThat(message.html()).contains(SPAM_LINE_HTML);
            assertThat(message.html().indexOf(SPAM_LINE_HTML)).isLessThan(message.html().indexOf("Descargar mis archivos"));
            assertThat(message.text()).contains(SPAM_LINE_TEXT);
            assertThat(message.text().indexOf(SPAM_LINE_TEXT)).isLessThan(message.text().indexOf("DESCARGAR MIS ARCHIVOS"));
        }
    }

    @Test
    void firstEmail_keepsItsSubject() {
        assertThat(composer.compose(cuadernos(), Optional.empty()).subject())
                .isEqualTo("Tus Cuadernos TIAM están listos: descargá tus PDF");
        assertThat(composer.compose(pack(), Optional.empty()).subject()).isEqualTo("Tu Pack TIAM está listo: descargá tus PDF");
    }

    // --- the plain-text alternative ---------------------------------------------------------------------------------------

    @Test
    void firstEmail_alwaysHasAPlainTextPart_withTheDownloadLink() {
        for (PackOrder order : List.of(cuadernos(), pack())) {
            EmailMessage message = composer.compose(order, Optional.empty());

            assertThat(message.text()).isNotBlank().contains("https://tiamdigital.com/pack/descargas/" + TOKEN);
        }
    }

    // --- the "I lost my link" email shares the template -------------------------------------------------------------------

    @Test
    void resend_alsoCarriesTheSpamLine_andTheSummaryOfEachOrder() {
        PackOrder pack = pack();
        pack.setId(7L);
        pack.setTotalAmount(new BigDecimal("15000.00"));
        EmailMessage message = composer.composeResend("ana@example.com", List.of(
                new PackAccessEmailComposer.ResendEntry(cuadernos(), REPLACEMENT, Optional.empty()),
                new PackAccessEmailComposer.ResendEntry(pack, "O".repeat(43), Optional.empty())), "key");

        assertThat(message.html()).contains(SPAM_LINE_HTML).contains("Resumen de tu compra")
                .contains("N° 42").contains("N° 7").contains("ARS 20.000").contains("ARS 15.000");
        assertThat(message.text()).contains(SPAM_LINE_TEXT).contains("RESUMEN DE TU COMPRA")
                .contains("Pedido: N° 42").contains("Pedido: N° 7");
        assertThat(message.subject()).isEqualTo("Tu acceso a tus compras de TIAM");
    }

    // --- helpers ----------------------------------------------------------------------------------------------------------

    private static PackOrder cuadernos() {
        PackOrder order = pack();
        order.setEdition(PackEdition.CUADERNOS);
        order.setTotalAmount(new BigDecimal("20000.00"));
        return order;
    }

    private static PackOrder pack() {
        PackOrder order = new PackOrder();
        order.setId(42L);
        order.setAccessToken(TOKEN);
        order.setEdition(PackEdition.FAMILIAS);
        order.setBuyerName("Ana Gómez");
        order.setEmail("ana@example.com");
        order.setTotalAmount(new BigDecimal("15000.00"));
        order.setPaidAt(PAID_AT);
        return order;
    }
}
