package com.tiam.pack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.tiam.challenge.config.WhatsAppProperties;
import com.tiam.common.email.EmailMessage;
import com.tiam.pack.config.PackProperties;
import com.tiam.pack.config.PackSalesReadiness;
import com.tiam.pack.domain.PackEdition;
import com.tiam.pack.domain.PackOrder;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PackAccessEmailComposerTest {

    static final String TOKEN = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcde";

    PackProperties properties;
    WhatsAppProperties whatsApp;
    PackSalesReadiness readiness;
    PackAccessEmailComposer composer;

    @BeforeEach
    void setUp() {
        properties = new PackProperties();
        properties.setDownloadBaseUrl("https://tiamdigital.com/pack/descargas");
        properties.setLandingBaseUrl("https://tiamdigital.com");
        whatsApp = new WhatsAppProperties();
        whatsApp.setBusinessNumber("5492214817297");
        readiness = org.mockito.Mockito.mock(PackSalesReadiness.class); // dev mode off, like production
        composer = new PackAccessEmailComposer(properties, whatsApp, readiness);
    }

    // --- the essentials -----------------------------------------------------------------------------

    @Test
    void email_goesToTheBuyer_withTheDownloadLinkContainingTheToken() {
        EmailMessage message = composer.compose(order(), Optional.empty());

        assertThat(message.to()).isEqualTo("ana@example.com");
        assertThat(message.html()).contains("https://tiamdigital.com/pack/descargas/" + TOKEN);
        assertThat(message.text()).contains("https://tiamdigital.com/pack/descargas/" + TOKEN);
    }

    @Test
    void email_hasASubjectThatSaysWhatToDo() {
        EmailMessage message = composer.compose(order(), Optional.empty());

        assertThat(message.subject()).isEqualTo("Tu Pack TIAM está listo: descargá tus PDF");
    }

    @Test
    void email_hasABulletproofOrangeButtonAndTheLinkRepeatedAsTextForClientsThatBlockButtons() {
        EmailMessage message = composer.compose(order(), Optional.empty());

        assertThat(message.html()).contains("Descargar mis archivos");
        // the colour lives on the table cell (bgcolor + style), so it is a button even where the link's padding is ignored
        assertThat(message.html()).contains("bgcolor=\"#CC4610\"").contains("color:#FFFFFF");
        int occurrences = message.html().split("pack/descargas/" + TOKEN, -1).length - 1;
        assertThat(occurrences).isGreaterThanOrEqualTo(2); // href of the button + visible text
        assertThat(message.text()).contains("DESCARGAR MIS ARCHIVOS");
    }

    // --- the look: robust email HTML ------------------------------------------------------------------------

    @Test
    void html_isA600pxTableLayout_withEveryStyleInline_andNothingExternal() {
        String html = composer.compose(order(), Optional.empty()).html();

        assertThat(html).contains("role=\"presentation\"").contains("width=\"600\"").contains("max-width:600px");
        assertThat(html).contains("<meta name=\"viewport\"").contains("<html lang=\"es-AR\"");
        // no scripts, no external stylesheets, no web fonts, no background images from CSS
        assertThat(html).doesNotContain("<script").doesNotContain("<link ").doesNotContain("@import")
                .doesNotContain("@font-face").doesNotContain("url(");
    }

    @Test
    void html_hasAHiddenPreheader_thatSaysWhyTheEmailMatters() {
        String html = composer.compose(order(), Optional.empty()).html();

        assertThat(html).contains("display:none").contains("Tus fichas ya están listas para descargar e imprimir.");
        assertThat(html.indexOf("Tus fichas ya están listas")).as("it comes first in the body")
                .isLessThan(html.indexOf("¡Tu Pack TIAM está listo!"));
    }

    @Test
    void html_adaptsToDarkModeAndToPhones() {
        String html = composer.compose(order(), Optional.empty()).html();

        assertThat(html).contains("<meta name=\"color-scheme\" content=\"light dark\">")
                .contains("@media (prefers-color-scheme: dark)")
                .contains("@media only screen and (max-width: 520px)");
    }

    @Test
    void images_areAbsoluteHttpsUrlsOnTheStableAssetsFolder_withAltText_neverWebp() {
        String html = composer.compose(order(), Optional.empty()).html();

        java.util.regex.Matcher images = java.util.regex.Pattern.compile("<img [^>]*>").matcher(html);
        int count = 0;
        while (images.find()) {
            count++;
            String tag = images.group();
            assertThat(tag).contains("src=\"https://www.tiamdigital.com/email/");
            assertThat(tag).containsPattern("alt=\"[^\"]{3,}\"");
        }
        assertThat(count).isEqualTo(2); // the logo and the product picture
        assertThat(html).contains("https://www.tiamdigital.com/email/logo-tiam.png")
                .contains("https://www.tiamdigital.com/email/pack-familias.jpg")
                .doesNotContain(".webp");
    }

    @Test
    void images_followTheEdition() {
        PackOrder professionals = order();
        professionals.setEdition(PackEdition.PROFESIONALES);

        String html = composer.compose(professionals, Optional.empty()).html();

        assertThat(html).contains("https://www.tiamdigital.com/email/pack-profesionales.jpg")
                .doesNotContain("pack-familias.jpg");
    }

    @Test
    void images_comeFromTheConfiguredFolder_withoutDoubleSlashes() {
        properties.setEmailAssetsBaseUrl("https://static.example.com/mail//");

        String html = composer.compose(order(), Optional.empty()).html();

        assertThat(html).contains("https://static.example.com/mail/logo-tiam.png")
                .contains("https://static.example.com/mail/pack-familias.jpg").doesNotContain("mail//");
    }

    @Test
    void images_ignoreAFolderABuyerCouldNotOpen_exceptInDevMode() {
        properties.setEmailAssetsBaseUrl("http://localhost:5180/email");

        assertThat(composer.compose(order(), Optional.empty()).html())
                .contains("https://www.tiamdigital.com/email/logo-tiam.png").doesNotContain("localhost");

        when(readiness.devModeActive()).thenReturn(true);
        assertThat(composer.compose(order(), Optional.empty()).html())
                .contains("http://localhost:5180/email/logo-tiam.png").contains("http://localhost:5180/email/pack-familias.jpg");

        properties.setEmailAssetsBaseUrl("");
        assertThat(composer.assetsBase()).isEqualTo("https://www.tiamdigital.com/email");
    }

    @Test
    void imageFileNames_areTheStableOnesTheFrontendServesFromPublicEmail() {
        // The names are a contract with the frontend's public/email/: a sent email keeps pointing at them for good.
        assertThat(PackAccessEmailComposer.LOGO_FILE).isEqualTo("logo-tiam.png");
        String html = composer.compose(order(), Optional.empty()).html();
        assertThat(html).containsPattern("/email/logo-tiam\\.png").containsPattern("/email/pack-familias\\.jpg");
    }

    @Test
    void theLogoKeepsTheLandingsRatio() {
        String html = composer.compose(order(), Optional.empty()).html();

        assertThat(html).contains("width=\"120\" height=\"67\""); // 429:240
        assertThat(120.0 / 67.0).isCloseTo(429.0 / 240.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void textStillCarriesEverythingWithImagesBlocked_headlineButtonAndEveryFileAreLiveText() {
        String html = composer.compose(withExtra(), Optional.empty()).html();

        assertThat(html).contains(">¡Tu Pack TIAM está listo!</h1>").contains(">Descargar mis archivos</a>")
                .contains("Pack completo").contains("Cuadernillos sueltos").contains("Cuadernillo extra");
    }

    // --- what the buyer gets: the "Tus archivos" panel ------------------------------------------------------------------

    @Test
    void filesPanel_listsTheRealWeights_andBothPapers() {
        EmailMessage message = composer.compose(order(), Optional.empty());

        assertThat(message.html()).contains("Tus archivos").contains("Edición Familias")
                .contains("19 MB").contains("38 MB").contains("A4 y en Carta").doesNotContain("32 MB");
        assertThat(message.text()).contains("Pack completo (PDF, 19 MB)").contains("Cuadernillos sueltos (ZIP, 38 MB)")
                .contains("A4 y en Carta");
    }

    @Test
    void filesPanel_showsTheExtraBookletAndItsWeight_onlyWhenBought() {
        EmailMessage with = composer.compose(withExtra(), Optional.empty());
        EmailMessage without = composer.compose(order(), Optional.empty());

        assertThat(with.html()).contains("Cuadernillo extra").contains("7 MB");
        assertThat(with.text()).contains("Cuadernillo extra (PDF, 7 MB)");
        assertThat(without.html()).doesNotContain("Cuadernillo extra").doesNotContain("7 MB");
        assertThat(without.text()).doesNotContain("Cuadernillo extra");
    }

    @Test
    void gamesMonth_listsItsActivationStep_withTheLinkWhenThereIsOne() {
        PackOrder games = order();
        games.setWithGames(true);

        EmailMessage linked = composer.compose(games, Optional.of("https://tiamdigital.com/desafio/tok"));
        EmailMessage unlinked = composer.compose(games, Optional.empty());

        assertThat(linked.html()).contains("Abrir mi mes de juegos").contains("Escribir por WhatsApp")
                .contains("mismo número que dejaste al comprar");
        assertThat(unlinked.html()).doesNotContain("Abrir mi mes de juegos").contains("Escribir por WhatsApp")
                .contains("Para activarlo, escribinos por WhatsApp");
        assertThat(unlinked.text()).contains("Para activarlo, escribinos por WhatsApp");
    }

    @Test
    void printingTips_sayWhereToStart_andToPrintOneSided_inBlackAndWhite() {
        EmailMessage message = composer.compose(order(), Optional.empty());

        assertThat(message.html()).contains("Para imprimir").contains("Empezá acá").contains("A una sola cara")
                .contains("las respuestas").contains("Con tinta negra alcanza");
        assertThat(message.text()).contains("PARA IMPRIMIR").contains("Empezá acá").contains("A una sola cara")
                .contains("las respuestas").contains("Con tinta negra alcanza");
    }

    @Test
    void printingTips_forProfessionalsTalkAboutTheSolutionsBook() {
        PackOrder professionals = order();
        professionals.setEdition(PackEdition.PROFESIONALES);

        assertThat(composer.compose(professionals, Optional.empty()).html()).contains("el solucionario");
    }

    @Test
    void access_saysHowLongItLasts_howToGetANewLink_andWhereToAskForHelp() {
        EmailMessage message = composer.compose(order(), Optional.empty());

        assertThat(message.html()).contains("dura 90 días").contains("Pedí un link nuevo")
                .contains("https://tiamdigital.com/pack/recuperar").contains("https://wa.me/5492214817297?text=Hola%2C%20necesito%20ayuda");
        assertThat(message.text()).contains("https://wa.me/5492214817297?text=Hola%2C%20necesito%20ayuda");
    }

    @Test
    void access_withoutABusinessNumber_stillSaysWhereToFindHelp_withoutABrokenWhatsAppLink() {
        whatsApp.setBusinessNumber("");

        EmailMessage message = composer.compose(order(), Optional.empty());

        assertThat(message.html()).doesNotContain("wa.me").contains("¿Necesitás ayuda?");
        assertThat(message.text()).doesNotContain("wa.me").contains("¿Necesitás ayuda?");
    }

    @Test
    void footer_namesTheBrand_andSaysItDoesNotReplaceProfessionalCare() {
        EmailMessage message = composer.compose(order(), Optional.empty());

        assertThat(message.html()).contains("tiamdigital.com").contains("No reemplaza la evaluación ni el tratamiento");
        assertThat(message.text()).contains("tiamdigital.com").contains("No reemplaza la evaluación ni el tratamiento");
    }

    @Test
    void htmlAndText_agreeOnWhatWasBought() {
        for (boolean extra : new boolean[] {false, true}) {
            for (boolean games : new boolean[] {false, true}) {
                PackOrder order = order();
                order.setWithExtra(extra);
                order.setWithGames(games);

                EmailMessage message = composer.compose(order, Optional.empty());

                assertThat(message.html().contains("Cuadernillo extra")).as("extra=%s games=%s", extra, games)
                        .isEqualTo(message.text().contains("Cuadernillo extra")).isEqualTo(extra);
                assertThat(message.html().contains("Tu mes de juegos")).as("extra=%s games=%s", extra, games)
                        .isEqualTo(message.text().contains("MES DE JUEGOS")).isEqualTo(games);
            }
        }
    }

    // --- size budget: Gmail clips a message at 102 KB ----------------------------------------------------------------

    @Test
    void sizeBudget_theFullestFirstEmailStaysFarBelowGmailsClipLimit() {
        PackOrder everything = order();
        everything.setEdition(PackEdition.PROFESIONALES);
        everything.setWithExtra(true);
        everything.setWithGames(true);

        EmailMessage message = composer.compose(everything, Optional.of("https://tiamdigital.com/desafio/desafio-token-1"));

        assertThat(message.html().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .as("HTML bytes (Gmail clips at 102 KB; our budget is 40 KB)").isLessThan(40_000);
        assertThat(message.text().length()).isLessThan(6_000);
    }

    @Test
    void sizeBudget_aResendWithTwoEditionsAndAllTheAddOns_staysWithinTheBudgetToo() {
        PackOrder familias = withExtra();
        PackOrder professionals = order();
        professionals.setEdition(PackEdition.PROFESIONALES);
        professionals.setWithExtra(true);
        professionals.setWithGames(true);

        EmailMessage message = composer.composeResend("ana@example.com", java.util.List.of(
                new PackAccessEmailComposer.ResendEntry(familias, "R".repeat(43), Optional.empty()),
                new PackAccessEmailComposer.ResendEntry(professionals, "O".repeat(43),
                        Optional.of("https://tiamdigital.com/desafio/tok"))), "key");

        assertThat(message.html().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThan(40_000);
    }

    @Test
    void email_mentionsHowLongAccessLastsAndHowToGetANewLink() {
        properties.setAccessDays(90);

        EmailMessage message = composer.compose(order(), Optional.empty());

        assertThat(message.text()).contains("90 días");
        assertThat(message.text()).contains("https://tiamdigital.com/pack/recuperar");
        assertThat(message.html()).contains("90 días").contains("https://tiamdigital.com/pack/recuperar");
    }

    @Test
    void email_namesTheEditionTheBuyerBought() {
        PackOrder professionals = order();
        professionals.setEdition(PackEdition.PROFESIONALES);

        EmailMessage message = composer.compose(professionals, Optional.empty());

        assertThat(message.text()).contains("Edición Profesionales");
    }

    // --- safety ---------------------------------------------------------------------------------------

    @Test
    void theBuyerName_isHtmlEscaped() {
        PackOrder order = order();
        order.setBuyerName("<img src=x onerror=alert(1)> & Co");

        EmailMessage message = composer.compose(order, Optional.empty());

        assertThat(message.html()).doesNotContain("<img src=x").contains("&lt;img");
    }

    @Test
    void aBuyerNameThatLooksLikeAPlaceholder_isPrintedLiterally_notSubstituted() {
        PackOrder order = order();
        order.setBuyerName("{{LINK}}");

        EmailMessage message = composer.compose(order, Optional.empty());

        // single-pass substitution: the greeting is data, never re-read as a template placeholder
        assertThat(message.html()).contains("Hola {{link}},");
    }

    @Test
    void firstNameIsCapitalised() {
        PackOrder order = order();
        order.setBuyerName("maría ELENA gómez");

        EmailMessage message = composer.compose(order, Optional.empty());

        assertThat(message.text()).startsWith("Hola María,");
    }

    @Test
    void noAttachmentsAreEverRepresented() {
        // The files are 19 MB and up and are only reachable through signed links: EmailMessage has no
        // attachment slot, and this email says where the files live instead.
        EmailMessage message = composer.compose(order(), Optional.empty());

        assertThat(message.getClass().getRecordComponents()).extracting("name")
                .containsExactly("to", "subject", "html", "text", "idempotencyKey");
    }

    // --- link base ------------------------------------------------------------------------------------------

    @Test
    void publicDownloadBase_requiresHttpsAndARealHost() {
        assertThat(composer.hasPublicDownloadBase()).isTrue();

        properties.setDownloadBaseUrl("http://tiamdigital.com/pack/descargas");
        assertThat(composer.hasPublicDownloadBase()).isFalse();
        properties.setDownloadBaseUrl("https://localhost:5173/pack/descargas");
        assertThat(composer.hasPublicDownloadBase()).isFalse();
        properties.setDownloadBaseUrl("");
        assertThat(composer.hasPublicDownloadBase()).isFalse();
        properties.setDownloadBaseUrl("not a url");
        assertThat(composer.hasPublicDownloadBase()).isFalse();
    }

    @Test
    void usableDownloadBase_inProduction_isExactlyThePublicHttpsRule() {
        for (String base : new String[] {"http://localhost:5180/pack/descargas", "http://127.0.0.1/pack/descargas",
                "http://tiamdigital.com/pack/descargas", ""}) {
            properties.setDownloadBaseUrl(base);
            assertThat(composer.hasUsableDownloadBase()).as(base).isFalse();
        }
        properties.setDownloadBaseUrl("https://tiamdigital.com/pack/descargas");
        assertThat(composer.hasUsableDownloadBase()).isTrue();
    }

    @Test
    void usableDownloadBase_inDevMode_alsoAcceptsTheDevelopersOwnMachine() {
        when(readiness.devModeActive()).thenReturn(true);

        properties.setDownloadBaseUrl("http://localhost:5180/pack/descargas");
        assertThat(composer.hasUsableDownloadBase()).isTrue();
        assertThat(composer.hasPublicDownloadBase()).as("the public check itself never relaxes").isFalse();
        properties.setDownloadBaseUrl("http://localhost.evil.com/pack/descargas");
        assertThat(composer.hasUsableDownloadBase()).isFalse();
    }

    @Test
    void gamesLink_onLocalhost_isUsedOnlyInDevMode() {
        PackOrder games = order();
        games.setWithGames(true);
        Optional<String> local = Optional.of("http://localhost:5180/desafio/tok");

        assertThat(composer.compose(games, local).html()).doesNotContain("localhost");

        when(readiness.devModeActive()).thenReturn(true);
        assertThat(composer.compose(games, local).html()).contains("http://localhost:5180/desafio/tok");
    }

    @Test
    void trailingSlashesOnTheBase_doNotProduceDoubleSlashes() {
        properties.setDownloadBaseUrl("https://tiamdigital.com/pack/descargas//");

        EmailMessage message = composer.compose(order(), Optional.empty());

        assertThat(message.text()).contains("https://tiamdigital.com/pack/descargas/" + TOKEN);
        assertThat(message.text()).doesNotContain("descargas//");
    }

    // --- the extra booklet and the games block -------------------------------------------------------------------

    @Test
    void extraBooklet_isListedOnlyWhenBought() {
        PackOrder withExtra = order();
        withExtra.setWithExtra(true);

        assertThat(composer.compose(withExtra, Optional.empty()).text())
                .contains("Cuadernillo extra");
        assertThat(composer.compose(order(), Optional.empty()).text())
                .doesNotContain("Cuadernillo extra");
    }

    @Test
    void gamesBlock_isAbsentWhenTheBuyerDidNotBuyGames() {
        EmailMessage message = composer.compose(order(), Optional.of("https://tiamdigital.com/desafio/tok"));

        assertThat(message.html()).doesNotContain("mes de juegos").doesNotContain("/desafio/tok");
        assertThat(message.text()).doesNotContain("MES DE JUEGOS");
    }

    @Test
    void gamesBlock_carriesTheDesafioLinkAndTheWhatsAppOption() {
        PackOrder games = order();
        games.setWithGames(true);

        EmailMessage message = composer.compose(games, Optional.of("https://tiamdigital.com/desafio/desafio-token-1"));

        assertThat(message.html()).contains("Tu mes de juegos").contains("https://tiamdigital.com/desafio/desafio-token-1");
        assertThat(message.text()).contains("MES DE JUEGOS").contains("https://tiamdigital.com/desafio/desafio-token-1");
        assertThat(message.html()).contains("https://wa.me/5492214817297");
        assertThat(message.text()).contains("https://wa.me/5492214817297");
    }

    @Test
    void gamesBlock_withoutAUsableDesafioLink_fallsBackToWhatsAppInsteadOfAnUnusableLink() {
        PackOrder games = order();
        games.setWithGames(true);

        EmailMessage message = composer.compose(games, Optional.of("http://localhost:5173/desafio/tok"));

        assertThat(message.html()).doesNotContain("localhost");
        assertThat(message.text()).doesNotContain("localhost");
        assertThat(message.text()).contains("WhatsApp").contains("https://wa.me/5492214817297");
    }

    @Test
    void gamesBlock_withNoDesafioLinkAtAll_alsoFallsBackToWhatsApp() {
        PackOrder games = order();
        games.setWithGames(true);

        EmailMessage message = composer.compose(games, Optional.empty());

        assertThat(message.text()).contains("MES DE JUEGOS").contains("WhatsApp");
    }

    // --- idempotency and the re-send variant -------------------------------------------------------------------------

    @Test
    void firstEmail_usesAStableIdempotencyKeyPerOrder() {
        EmailMessage message = composer.compose(order(), Optional.empty());

        assertThat(message.idempotencyKey()).isEqualTo("pack-access/42");
    }

    // --- the "I lost my link" email (composeResend) -----------------------------------------------------------------

    private static final String REPLACEMENT = "R".repeat(43);

    @Test
    void resend_linksToTheReplacementToken_notToTheOrdersCurrentOne() {
        EmailMessage message = composer.composeResend("ana@example.com",
                java.util.List.of(new PackAccessEmailComposer.ResendEntry(order(), REPLACEMENT, Optional.empty())), "key-1");

        assertThat(message.to()).isEqualTo("ana@example.com");
        assertThat(message.subject()).isEqualTo("Tu acceso al Pack TIAM");
        assertThat(message.idempotencyKey()).isEqualTo("key-1");
        assertThat(message.html()).contains("https://tiamdigital.com/pack/descargas/" + REPLACEMENT);
        assertThat(message.text()).contains("https://tiamdigital.com/pack/descargas/" + REPLACEMENT);
        assertThat(message.html()).doesNotContain(TOKEN);
        assertThat(message.text()).doesNotContain(TOKEN);
    }

    @Test
    void resend_isHonestThatTheOldLinkStillWorks_andTellsAStrangersVictimToIgnoreIt() {
        EmailMessage message = composer.composeResend("ana@example.com",
                java.util.List.of(new PackAccessEmailComposer.ResendEntry(order(), REPLACEMENT, Optional.empty())), "key");

        assertThat(message.text()).contains("sigue funcionando").contains("Si vos no pediste este mensaje");
        assertThat(message.html()).contains("sigue funcionando").contains("Si vos no pediste este mensaje");
        assertThat(message.text()).doesNotContain("dejó de funcionar");
    }

    @Test
    void resend_forABuyerOfBothEditions_putsBothLinksInOneEmail() {
        PackOrder professionals = order();
        professionals.setEdition(PackEdition.PROFESIONALES);
        String otherToken = "O".repeat(43);

        EmailMessage message = composer.composeResend("ana@example.com", java.util.List.of(
                new PackAccessEmailComposer.ResendEntry(order(), REPLACEMENT, Optional.empty()),
                new PackAccessEmailComposer.ResendEntry(professionals, otherToken, Optional.empty())), "key");

        assertThat(message.text()).contains("Edición Familias").contains("Edición Profesionales")
                .contains("/" + REPLACEMENT).contains("/" + otherToken).contains("tus links");
        assertThat(message.html()).contains("Familias").contains("Profesionales")
                .contains("/" + REPLACEMENT).contains("/" + otherToken);
    }

    @Test
    void resend_carriesTheGamesBlockOnlyForTheOrdersThatBoughtIt() {
        PackOrder games = order();
        games.setWithGames(true);

        EmailMessage message = composer.composeResend("ana@example.com", java.util.List.of(
                new PackAccessEmailComposer.ResendEntry(games, REPLACEMENT, Optional.of("https://tiamdigital.com/desafio/tok"))), "key");
        EmailMessage without = composer.composeResend("ana@example.com", java.util.List.of(
                new PackAccessEmailComposer.ResendEntry(order(), REPLACEMENT, Optional.empty())), "key");

        assertThat(message.text()).contains("https://tiamdigital.com/desafio/tok");
        assertThat(message.html()).contains("https://tiamdigital.com/desafio/tok");
        assertThat(without.text()).doesNotContain("Desafío");
    }

    @Test
    void resend_escapesTheBuyerName() {
        PackOrder order = order();
        order.setBuyerName("<script>alert(1)</script>");

        EmailMessage message = composer.composeResend("ana@example.com",
                java.util.List.of(new PackAccessEmailComposer.ResendEntry(order, REPLACEMENT, Optional.empty())), "key");

        assertThat(message.html()).doesNotContain("<script>");
    }

    @Test
    void resend_looksLikeTheFirstEmail_sameButton_sameLogo_theProductPictureForASingleOrder() {
        EmailMessage message = composer.composeResend("ana@example.com", java.util.List.of(
                new PackAccessEmailComposer.ResendEntry(withExtra(), REPLACEMENT, Optional.empty())), "key");

        assertThat(message.html()).contains("Descargar mis archivos").contains("bgcolor=\"#CC4610\"")
                .contains("https://www.tiamdigital.com/email/logo-tiam.png")
                .contains("https://www.tiamdigital.com/email/pack-familias.jpg")
                .contains("Tus archivos").contains("Cuadernillo extra").contains("38 MB").contains("dura 90 días");
        assertThat(message.text()).contains("Cuadernillo extra (7 MB)");
    }

    @Test
    void resend_forTwoEditions_hasOneLabelledButtonEach_andNoSinglePicture() {
        PackOrder professionals = order();
        professionals.setEdition(PackEdition.PROFESIONALES);

        EmailMessage message = composer.composeResend("ana@example.com", java.util.List.of(
                new PackAccessEmailComposer.ResendEntry(order(), REPLACEMENT, Optional.empty()),
                new PackAccessEmailComposer.ResendEntry(professionals, "O".repeat(43), Optional.empty())), "key");

        assertThat(message.html()).contains("Descargar Edición Familias").contains("Descargar Edición Profesionales")
                .doesNotContain("pack-familias.jpg").doesNotContain("pack-profesionales.jpg");
    }

    @Test
    void resend_htmlHasAltOnEveryImage_andTheSameRobustShell() {
        String html = composer.composeResend("ana@example.com", java.util.List.of(
                new PackAccessEmailComposer.ResendEntry(order(), REPLACEMENT, Optional.empty())), "key").html();

        java.util.regex.Matcher images = java.util.regex.Pattern.compile("<img [^>]*>").matcher(html);
        while (images.find()) {
            assertThat(images.group()).containsPattern("alt=\"[^\"]{3,}\"").contains("src=\"https://");
        }
        assertThat(html).contains("width=\"600\"").contains("@media (prefers-color-scheme: dark)").contains("display:none")
                .doesNotContain("<script");
    }

    private static PackOrder withExtra() {
        PackOrder order = order();
        order.setWithExtra(true);
        return order;
    }

    private static PackOrder order() {
        PackOrder order = new PackOrder();
        order.setId(42L);
        order.setAccessToken(TOKEN);
        order.setEdition(PackEdition.FAMILIAS);
        order.setBuyerName("Ana Gómez");
        order.setEmail("ana@example.com");
        return order;
    }
}
