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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The delivery email and the "I lost my link" email of the «Cuadernos TIAM» product. */
class CuadernosAccessEmailComposerTest {

    static final String TOKEN = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcde";
    static final String REPLACEMENT = "R".repeat(43);
    static final String LINK = "https://tiamdigital.com/pack/descargas/";

    PackProperties properties;
    WhatsAppProperties whatsApp;
    PackAccessEmailComposer composer;

    @BeforeEach
    void setUp() {
        properties = new PackProperties();
        properties.setDownloadBaseUrl("https://tiamdigital.com/pack/descargas");
        properties.setLandingBaseUrl("https://tiamdigital.com");
        whatsApp = new WhatsAppProperties();
        whatsApp.setBusinessNumber("5492214817297");
        PackSalesReadiness readiness = org.mockito.Mockito.mock(PackSalesReadiness.class); // dev mode off, like production
        composer = new PackAccessEmailComposer(properties, whatsApp, readiness);
    }

    // --- the first email ------------------------------------------------------------------------------------------

    @Test
    void firstEmail_goesToTheBuyer_withTheTokenLink_andAStableIdempotencyKey() {
        EmailMessage message = composer.compose(cuadernos(), Optional.empty());

        assertThat(message.to()).isEqualTo("ana@example.com");
        assertThat(message.html()).contains(LINK + TOKEN);
        assertThat(message.text()).contains(LINK + TOKEN);
        assertThat(message.idempotencyKey()).isEqualTo("pack-access/42");
    }

    @Test
    void firstEmail_subjectNamesTheProduct() {
        EmailMessage message = composer.compose(cuadernos(), Optional.empty());

        assertThat(message.subject()).isEqualTo("Tus Cuadernos TIAM están listos: descargá tus PDF");
    }

    @Test
    void firstEmail_saysCuadernosTiamEverywhere_andNeverTheBasicPack() {
        EmailMessage message = composer.compose(cuadernos(), Optional.empty());

        assertThat(message.html()).contains(">¡Tus Cuadernos TIAM están listos!</h1>").contains("CUADERNOS TIAM")
                .contains("<title>Tus Cuadernos TIAM</title>")
                .contains("Recibiste este email porque compraste los Cuadernos TIAM.")
                .doesNotContain("Pack TIAM").doesNotContain("PACK TIAM").doesNotContain("Edición");
        assertThat(message.text()).contains("Cuadernos TIAM").doesNotContain("Pack TIAM").doesNotContain("Edición");
    }

    @Test
    void firstEmail_hasTheBrandLayout_theOrangeButtonAndTheLinkAsText() {
        EmailMessage message = composer.compose(cuadernos(), Optional.empty());

        assertThat(message.html()).contains("Descargar mis archivos").contains("bgcolor=\"#CC4610\"")
                .contains("width=\"600\"").contains("@media (prefers-color-scheme: dark)").doesNotContain("<script");
        assertThat(message.html().split(LINK + TOKEN, -1).length - 1).isGreaterThanOrEqualTo(2);
        assertThat(message.text()).contains("DESCARGAR MIS ARCHIVOS");
    }

    @Test
    void firstEmail_preheaderSaysWhyItMatters() {
        String html = composer.compose(cuadernos(), Optional.empty()).html();

        assertThat(html).contains("Tus cuadernos ya están listos para descargar e imprimir. Tu acceso dura 90 días.");
    }

    // --- the picture ----------------------------------------------------------------------------------------------

    @Test
    void picture_isEmailCuadernosJpg_underTheAssetsBase() {
        String html = composer.compose(cuadernos(), Optional.empty()).html();

        assertThat(html).contains("src=\"https://www.tiamdigital.com/email/cuadernos.jpg\"")
                .contains("https://www.tiamdigital.com/email/logo-tiam.png")
                .doesNotContain("pack-familias.jpg").doesNotContain("pack-profesionales.jpg").doesNotContain(".webp");
    }

    @Test
    void picture_followsTheConfiguredAssetsBase() {
        properties.setEmailAssetsBaseUrl("https://cdn.example.com/email/");

        String html = composer.compose(cuadernos(), Optional.empty()).html();

        assertThat(html).contains("src=\"https://cdn.example.com/email/cuadernos.jpg\"");
    }

    @Test
    void images_areTwoWithAltText() {
        String html = composer.compose(cuadernos(), Optional.empty()).html();

        Matcher images = Pattern.compile("<img [^>]*>").matcher(html);
        int count = 0;
        while (images.find()) {
            count++;
            assertThat(images.group()).containsPattern("alt=\"[^\"]{3,}\"").contains("src=\"https://");
        }
        assertThat(count).isEqualTo(2);
        assertThat(html).containsPattern("alt=\"Cuadernos TIAM[^\"]*\"");
    }

    // --- what the buyer gets ----------------------------------------------------------------------------------------

    @Test
    void filesPanel_listsTheCollectionAndTheFourLevels_withTheirRealWeights() {
        EmailMessage message = composer.compose(cuadernos(), Optional.empty());

        assertThat(message.html()).contains("Tus archivos").contains("Colección completa").contains("los 4 cuadernos en 1 archivo")
                .contains("27 MB").contains("7 MB")
                .contains("Cuaderno 1 · Nivel inicial").contains("Cuaderno 2 · Nivel intermedio")
                .contains("Cuaderno 3 · Nivel intermedio alto").contains("Cuaderno 4 · Nivel avanzado")
                .contains("A4 y en Carta");
        assertThat(message.text()).contains("Colección completa (PDF, 27 MB)").contains("7 MB")
                .contains("Cuaderno 1 · Nivel inicial").contains("Cuaderno 2 · Nivel intermedio")
                .contains("Cuaderno 3 · Nivel intermedio alto").contains("Cuaderno 4 · Nivel avanzado")
                .contains("A4 y en Carta");
    }

    @Test
    void filesPanel_neverMentionsPackFilesOrTheExtraBooklet_evenWithTheFlagSet() {
        PackOrder order = cuadernos();
        order.setWithExtra(true);

        EmailMessage message = composer.compose(order, Optional.empty());

        for (String text : List.of(message.html(), message.text())) {
            assertThat(text).doesNotContain("Cuadernillo extra").doesNotContain("Cuadernillos sueltos")
                    .doesNotContain("Pack completo").doesNotContain("19 MB").doesNotContain("38 MB");
        }
    }

    @Test
    void tips_talkAboutLevelsAndPrinting_notAboutThePacksStartHereOrAnswerBooks() {
        EmailMessage message = composer.compose(cuadernos(), Optional.empty());

        assertThat(message.html()).contains("Para imprimir").contains("Empezá por tu nivel");
        assertThat(message.text()).contains("PARA IMPRIMIR").contains("Empezá por tu nivel");
        for (String text : List.of(message.html(), message.text())) {
            assertThat(text).doesNotContain("Empezá acá").doesNotContain("solucionario").doesNotContain("las respuestas");
        }
    }

    @Test
    void access_saysHowLongItLasts_howToGetANewLink_andWhereToAskForHelp() {
        EmailMessage message = composer.compose(cuadernos(), Optional.empty());

        assertThat(message.html()).contains("dura 90 días").contains("Pedí un link nuevo")
                .contains("https://tiamdigital.com/pack/recuperar")
                .contains("https://wa.me/5492214817297?text=Hola%2C%20necesito%20ayuda%20con%20mis%20Cuadernos%20TIAM");
        assertThat(message.text()).contains("dura 90 días")
                .contains("https://wa.me/5492214817297?text=Hola%2C%20necesito%20ayuda%20con%20mis%20Cuadernos%20TIAM");
    }

    // --- the games month ----------------------------------------------------------------------------------------------

    @Test
    void gamesMonth_isListedOnlyWhenBought_withItsActivationMessageAboutTheCuadernos() {
        PackOrder games = cuadernos();
        games.setWithGames(true);

        EmailMessage with = composer.compose(games, Optional.of("https://tiamdigital.com/desafio/tok"));
        EmailMessage without = composer.compose(cuadernos(), Optional.empty());

        assertThat(with.html()).contains("Tu primer mes de juegos").contains("Abrir mi mes de juegos")
                .contains("https://tiamdigital.com/desafio/tok")
                .contains("compr%C3%A9%20los%20Cuadernos%20TIAM%20con%20el%20mes%20de%20juegos");
        assertThat(with.text()).contains("MES DE JUEGOS").contains("https://tiamdigital.com/desafio/tok");
        assertThat(without.html()).doesNotContain("mes de juegos");
        assertThat(without.text()).doesNotContain("MES DE JUEGOS");
    }

    @Test
    void gamesMonth_isTheFirstMonthOnly_startedByWhatsApp_theNextOnesAreOptionalAndPaidApartAtTheNormalPrice() {
        PackOrder games = cuadernos();
        games.setWithGames(true);

        EmailMessage unlinked = composer.compose(games, Optional.empty());
        EmailMessage linked = composer.compose(games, Optional.of("https://tiamdigital.com/desafio/tok"));

        for (EmailMessage message : List.of(unlinked, linked)) {
            assertThat(message.html()).contains("Tu primer mes de juegos").contains("empieza por WhatsApp")
                    .contains("Los meses siguientes son opcionales").contains("se pagan aparte, al precio normal")
                    .contains("No se renueva solo");
            assertThat(message.text()).contains("Tu primer mes de juegos del Desafío 30 días")
                    .contains("Los meses siguientes son opcionales").contains("se pagan aparte, al precio normal")
                    .contains("No se renueva solo");
        }
        assertThat(unlinked.html()).contains("Para activarlo, escribinos por WhatsApp");
        assertThat(unlinked.text()).contains("escribinos por WhatsApp desde el mismo número que dejaste al comprar");
    }

    @Test
    void gamesMonth_ofThePack_isUnchanged_noFirstMonthNorOptionalMonthsWording() {
        PackOrder games = pack();
        games.setWithGames(true);

        EmailMessage message = composer.compose(games, Optional.empty());

        assertThat(message.html()).contains("Tu mes de juegos").doesNotContain("primer mes").doesNotContain("meses siguientes");
        assertThat(message.text()).contains("MES DE JUEGOS").doesNotContain("primer mes").doesNotContain("meses siguientes");
    }

    // --- safety ---------------------------------------------------------------------------------------------------------

    @Test
    void theBuyerName_isHtmlEscaped_andCapitalisedInTheGreeting() {
        PackOrder order = cuadernos();
        order.setBuyerName("<script>alert(1)</script>");
        EmailMessage hostile = composer.compose(order, Optional.empty());
        order.setBuyerName("ana gómez");
        EmailMessage plain = composer.compose(order, Optional.empty());

        assertThat(hostile.html()).doesNotContain("<script>");
        assertThat(plain.html()).contains("Hola Ana,");
    }

    @Test
    void noEmailEverSaysItWasReviewedBySomeone() {
        PackOrder games = cuadernos();
        games.setWithGames(true);
        List<EmailMessage> messages = List.of(
                composer.compose(cuadernos(), Optional.empty()),
                composer.compose(games, Optional.of("https://tiamdigital.com/desafio/tok")),
                composer.composeResend("ana@example.com",
                        List.of(new PackAccessEmailComposer.ResendEntry(cuadernos(), REPLACEMENT, Optional.empty())), "k1"),
                composer.composeResend("ana@example.com", List.of(
                        new PackAccessEmailComposer.ResendEntry(cuadernos(), REPLACEMENT, Optional.empty()),
                        new PackAccessEmailComposer.ResendEntry(pack(), "O".repeat(43), Optional.empty())), "k2"));

        for (EmailMessage message : messages) {
            for (String text : List.of(message.html(), message.text(), message.subject())) {
                assertThat(text.toLowerCase(java.util.Locale.ROOT)).doesNotContain("revisado").doesNotContain("revisada")
                        .doesNotContain("supervisado").doesNotContain("avalado");
            }
        }
    }

    @Test
    void sizeBudget_theFullestCuadernosEmailStaysFarBelowGmailsClipLimit() {
        PackOrder everything = cuadernos();
        everything.setWithGames(true);

        EmailMessage message = composer.compose(everything, Optional.of("https://tiamdigital.com/desafio/desafio-token-1"));

        assertThat(message.html().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThan(40_000);
        assertThat(message.text().length()).isLessThan(6_000);
    }

    // --- the Pack emails are untouched -----------------------------------------------------------------------------------

    @Test
    void packFirstEmail_keepsItsSubjectHeadlineAndFooter() {
        EmailMessage message = composer.compose(pack(), Optional.empty());

        assertThat(message.subject()).isEqualTo("Tu Pack TIAM está listo: descargá tus PDF");
        assertThat(message.html()).contains(">¡Tu Pack TIAM está listo!</h1>").contains("PACK TIAM · EDICIÓN FAMILIAS")
                .contains("Recibiste este email porque compraste el Pack TIAM.")
                .contains("https://www.tiamdigital.com/email/pack-familias.jpg").doesNotContain("cuadernos.jpg");
        assertThat(message.text()).contains("Pack completo (PDF, 19 MB)").doesNotContain("Cuadernos TIAM");
    }

    // --- the "I lost my link" email ----------------------------------------------------------------------------------------

    @Test
    void resend_ofACuadernosOrder_linksToTheReplacementToken_withItsOwnSubjectAndWording() {
        EmailMessage message = composer.composeResend("ana@example.com",
                List.of(new PackAccessEmailComposer.ResendEntry(cuadernos(), REPLACEMENT, Optional.empty())), "key-1");

        assertThat(message.to()).isEqualTo("ana@example.com");
        assertThat(message.subject()).isEqualTo("Tu acceso a los Cuadernos TIAM");
        assertThat(message.idempotencyKey()).isEqualTo("key-1");
        assertThat(message.html()).contains(LINK + REPLACEMENT).doesNotContain(TOKEN)
                .contains("Tu acceso a los Cuadernos TIAM").contains("Descargar mis archivos")
                .contains("Recibiste este email porque compraste los Cuadernos TIAM.")
                .doesNotContain("Pack TIAM").doesNotContain("Edición");
        assertThat(message.text()).contains(LINK + REPLACEMENT).doesNotContain(TOKEN)
                .contains("Pediste el acceso a tus Cuadernos TIAM de nuevo").contains("tu link")
                .contains("la colección completa (27 MB)").contains("en A4 y en Carta")
                .contains("sigue funcionando").contains("Si vos no pediste este mensaje")
                .doesNotContain("Pack TIAM").doesNotContain("Edición");
    }

    @Test
    void resend_ofACuadernosOrder_showsTheProductPictureAndTheFilesPanel() {
        EmailMessage message = composer.composeResend("ana@example.com",
                List.of(new PackAccessEmailComposer.ResendEntry(cuadernos(), REPLACEMENT, Optional.empty())), "key");

        assertThat(message.html()).contains("https://www.tiamdigital.com/email/cuadernos.jpg")
                .contains("https://www.tiamdigital.com/email/logo-tiam.png")
                .contains("Tus archivos").contains("Colección completa").contains("27 MB").contains("dura 90 días");
    }

    @Test
    void resend_withTheGamesMonth_carriesThePersonalLink() {
        PackOrder games = cuadernos();
        games.setWithGames(true);

        EmailMessage message = composer.composeResend("ana@example.com", List.of(
                new PackAccessEmailComposer.ResendEntry(games, REPLACEMENT, Optional.of("https://tiamdigital.com/desafio/tok"))), "key");

        assertThat(message.html()).contains("https://tiamdigital.com/desafio/tok").contains("Tu primer mes de juegos")
                .contains("Los meses siguientes son opcionales").contains("se pagan aparte, al precio normal");
        assertThat(message.text()).contains("https://tiamdigital.com/desafio/tok")
                .contains("Los meses siguientes son opcionales").contains("se pagan aparte, al precio normal");
    }

    @Test
    void resend_forABuyerOfThePackAndTheCuadernos_putsBothLinksInOneEmail_neutralWording() {
        EmailMessage message = composer.composeResend("ana@example.com", List.of(
                new PackAccessEmailComposer.ResendEntry(pack(), REPLACEMENT, Optional.empty()),
                new PackAccessEmailComposer.ResendEntry(cuadernos(), "O".repeat(43), Optional.empty())), "key");

        assertThat(message.subject()).isEqualTo("Tu acceso a tus compras de TIAM");
        assertThat(message.html()).contains("Descargar Edición Familias").contains("Descargar Cuadernos TIAM")
                .contains("/" + REPLACEMENT).contains("/" + "O".repeat(43))
                .doesNotContain("pack-familias.jpg").doesNotContain("cuadernos.jpg")
                .contains("Recibiste este email porque compraste en TIAM.");
        assertThat(message.text()).contains("Edición Familias:").contains("Cuadernos TIAM:")
                .contains("tus links").contains("Pediste el acceso a tus compras de TIAM de nuevo");
    }

    @Test
    void resend_ofThePackOnly_isUnchanged() {
        EmailMessage message = composer.composeResend("ana@example.com",
                List.of(new PackAccessEmailComposer.ResendEntry(pack(), REPLACEMENT, Optional.empty())), "key");

        assertThat(message.subject()).isEqualTo("Tu acceso al Pack TIAM");
        assertThat(message.html()).contains("Recibiste este email porque compraste el Pack TIAM.")
                .contains("https://www.tiamdigital.com/email/pack-familias.jpg");
        assertThat(message.text()).contains("Pediste el acceso a tu Pack TIAM de nuevo");
    }

    @Test
    void resend_sizeBudget_aMixedEmailStaysWithinIt() {
        PackOrder games = cuadernos();
        games.setWithGames(true);
        PackOrder fullPack = pack();
        fullPack.setWithExtra(true);
        fullPack.setWithGames(true);

        EmailMessage message = composer.composeResend("ana@example.com", List.of(
                new PackAccessEmailComposer.ResendEntry(fullPack, REPLACEMENT, Optional.of("https://tiamdigital.com/desafio/a")),
                new PackAccessEmailComposer.ResendEntry(games, "O".repeat(43), Optional.of("https://tiamdigital.com/desafio/b"))), "key");

        assertThat(message.html().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThan(40_000);
    }

    // --- helpers ----------------------------------------------------------------------------------------------------------

    private static PackOrder cuadernos() {
        PackOrder order = pack();
        order.setEdition(PackEdition.CUADERNOS);
        return order;
    }

    private static PackOrder pack() {
        PackOrder order = new PackOrder();
        order.setId(42L);
        order.setAccessToken(TOKEN);
        order.setEdition(PackEdition.FAMILIAS);
        order.setBuyerName("Ana Gómez");
        order.setEmail("ana@example.com");
        return order;
    }
}
