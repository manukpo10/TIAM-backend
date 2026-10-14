package com.tiam.pack.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tiam.challenge.config.WhatsAppProperties;
import com.tiam.common.email.EmailMessage;
import com.tiam.pack.config.PackProperties;
import com.tiam.pack.domain.PackEdition;
import com.tiam.pack.domain.PackOrder;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PackAccessEmailComposerTest {

    static final String TOKEN = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcde";

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
        composer = new PackAccessEmailComposer(properties, whatsApp);
    }

    // --- the essentials -----------------------------------------------------------------------------

    @Test
    void email_goesToTheBuyer_withTheDownloadLinkContainingTheToken() {
        EmailMessage message = composer.compose(order(), Optional.empty(), PackAccessEmailComposer.Variant.FIRST);

        assertThat(message.to()).isEqualTo("ana@example.com");
        assertThat(message.html()).contains("https://tiamdigital.com/pack/descargas/" + TOKEN);
        assertThat(message.text()).contains("https://tiamdigital.com/pack/descargas/" + TOKEN);
    }

    @Test
    void email_hasASubjectThatSaysWhatToDo() {
        EmailMessage message = composer.compose(order(), Optional.empty(), PackAccessEmailComposer.Variant.FIRST);

        assertThat(message.subject()).isEqualTo("Tu Pack TIAM está listo: descargá tus PDF");
    }

    @Test
    void email_hasABigButtonAndTheLinkRepeatedAsTextForClientsThatBlockButtons() {
        EmailMessage message = composer.compose(order(), Optional.empty(), PackAccessEmailComposer.Variant.FIRST);

        assertThat(message.html()).contains("Ir a mis descargas");
        assertThat(message.html()).contains("font-size:18px"); // accessible body type
        int occurrences = message.html().split("pack/descargas/" + TOKEN, -1).length - 1;
        assertThat(occurrences).isGreaterThanOrEqualTo(2); // href of the button + visible text
    }

    @Test
    void email_mentionsHowLongAccessLastsAndHowToGetANewLink() {
        properties.setAccessDays(90);

        EmailMessage message = composer.compose(order(), Optional.empty(), PackAccessEmailComposer.Variant.FIRST);

        assertThat(message.text()).contains("90 días");
        assertThat(message.text()).contains("https://tiamdigital.com/pack/recuperar");
        assertThat(message.html()).contains("90 días").contains("https://tiamdigital.com/pack/recuperar");
    }

    @Test
    void email_namesTheEditionTheBuyerBought() {
        PackOrder professionals = order();
        professionals.setEdition(PackEdition.PROFESIONALES);

        EmailMessage message = composer.compose(professionals, Optional.empty(), PackAccessEmailComposer.Variant.FIRST);

        assertThat(message.text()).contains("Edición Profesionales");
    }

    // --- safety ---------------------------------------------------------------------------------------

    @Test
    void theBuyerName_isHtmlEscaped() {
        PackOrder order = order();
        order.setBuyerName("<img src=x onerror=alert(1)> & Co");

        EmailMessage message = composer.compose(order, Optional.empty(), PackAccessEmailComposer.Variant.FIRST);

        assertThat(message.html()).doesNotContain("<img src=x").contains("&lt;img");
    }

    @Test
    void aBuyerNameThatLooksLikeAPlaceholder_isPrintedLiterally_notSubstituted() {
        PackOrder order = order();
        order.setBuyerName("{{LINK}}");

        EmailMessage message = composer.compose(order, Optional.empty(), PackAccessEmailComposer.Variant.FIRST);

        // single-pass substitution: the greeting is data, never re-read as a template placeholder
        assertThat(message.html()).contains("Hola {{link}},");
    }

    @Test
    void firstNameIsCapitalised() {
        PackOrder order = order();
        order.setBuyerName("maría ELENA gómez");

        EmailMessage message = composer.compose(order, Optional.empty(), PackAccessEmailComposer.Variant.FIRST);

        assertThat(message.text()).startsWith("Hola María,");
    }

    @Test
    void noAttachmentsAreEverRepresented() {
        // The files are 19 MB and up and are only reachable through signed links: EmailMessage has no
        // attachment slot, and this email says where the files live instead.
        EmailMessage message = composer.compose(order(), Optional.empty(), PackAccessEmailComposer.Variant.FIRST);

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
    void trailingSlashesOnTheBase_doNotProduceDoubleSlashes() {
        properties.setDownloadBaseUrl("https://tiamdigital.com/pack/descargas//");

        EmailMessage message = composer.compose(order(), Optional.empty(), PackAccessEmailComposer.Variant.FIRST);

        assertThat(message.text()).contains("https://tiamdigital.com/pack/descargas/" + TOKEN);
        assertThat(message.text()).doesNotContain("descargas//");
    }

    // --- the extra booklet and the games block -------------------------------------------------------------------

    @Test
    void extraBooklet_isListedOnlyWhenBought() {
        PackOrder withExtra = order();
        withExtra.setWithExtra(true);

        assertThat(composer.compose(withExtra, Optional.empty(), PackAccessEmailComposer.Variant.FIRST).text())
                .contains("Cuadernillo extra");
        assertThat(composer.compose(order(), Optional.empty(), PackAccessEmailComposer.Variant.FIRST).text())
                .doesNotContain("Cuadernillo extra");
    }

    @Test
    void gamesBlock_isAbsentWhenTheBuyerDidNotBuyGames() {
        EmailMessage message = composer.compose(order(), Optional.of("https://tiamdigital.com/desafio/tok"),
                PackAccessEmailComposer.Variant.FIRST);

        assertThat(message.html()).doesNotContain("mes de juegos").doesNotContain("/desafio/tok");
        assertThat(message.text()).doesNotContain("MES DE JUEGOS");
    }

    @Test
    void gamesBlock_carriesTheDesafioLinkAndTheWhatsAppOption() {
        PackOrder games = order();
        games.setWithGames(true);

        EmailMessage message = composer.compose(games, Optional.of("https://tiamdigital.com/desafio/desafio-token-1"),
                PackAccessEmailComposer.Variant.FIRST);

        assertThat(message.html()).contains("Tu mes de juegos").contains("https://tiamdigital.com/desafio/desafio-token-1");
        assertThat(message.text()).contains("MES DE JUEGOS").contains("https://tiamdigital.com/desafio/desafio-token-1");
        assertThat(message.html()).contains("https://wa.me/5492214817297");
        assertThat(message.text()).contains("https://wa.me/5492214817297");
    }

    @Test
    void gamesBlock_withoutAUsableDesafioLink_fallsBackToWhatsAppInsteadOfAnUnusableLink() {
        PackOrder games = order();
        games.setWithGames(true);

        EmailMessage message = composer.compose(games, Optional.of("http://localhost:5173/desafio/tok"),
                PackAccessEmailComposer.Variant.FIRST);

        assertThat(message.html()).doesNotContain("localhost");
        assertThat(message.text()).doesNotContain("localhost");
        assertThat(message.text()).contains("WhatsApp").contains("https://wa.me/5492214817297");
    }

    @Test
    void gamesBlock_withNoDesafioLinkAtAll_alsoFallsBackToWhatsApp() {
        PackOrder games = order();
        games.setWithGames(true);

        EmailMessage message = composer.compose(games, Optional.empty(), PackAccessEmailComposer.Variant.FIRST);

        assertThat(message.text()).contains("MES DE JUEGOS").contains("WhatsApp");
    }

    // --- idempotency and the re-send variant -------------------------------------------------------------------------

    @Test
    void firstEmail_usesAStableIdempotencyKeyPerOrder() {
        EmailMessage message = composer.compose(order(), Optional.empty(), PackAccessEmailComposer.Variant.FIRST);

        assertThat(message.idempotencyKey()).isEqualTo("pack-access/42");
    }

    @Test
    void resentEmail_hasADifferentKeyEveryTime_soTheProviderDoesNotDropIt() {
        PackOrder first = order();
        PackOrder second = order();
        second.setAccessToken("ZZZZZZZZ" + TOKEN.substring(8));

        String a = composer.compose(first, Optional.empty(), PackAccessEmailComposer.Variant.RESEND).idempotencyKey();
        String b = composer.compose(second, Optional.empty(), PackAccessEmailComposer.Variant.RESEND).idempotencyKey();

        assertThat(a).startsWith("pack-access-resend/42/").isNotEqualTo("pack-access/42");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void resentEmail_saysItIsANewAccess() {
        EmailMessage message = composer.compose(order(), Optional.empty(), PackAccessEmailComposer.Variant.RESEND);

        assertThat(message.subject()).isEqualTo("Tu nuevo acceso al Pack TIAM");
        assertThat(message.text()).contains("anterior");
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
