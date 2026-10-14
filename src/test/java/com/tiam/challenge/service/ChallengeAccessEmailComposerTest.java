package com.tiam.challenge.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tiam.challenge.config.WhatsAppProperties;
import com.tiam.challenge.domain.ChallengePurchase;
import com.tiam.challenge.domain.ChallengePurchaseStatus;
import com.tiam.common.email.EmailMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChallengeAccessEmailComposerTest {

    private static final String TOKEN = "11111111-2222-3333-4444-555555555555";

    private WhatsAppProperties whatsAppProperties;
    private ChallengeAccessEmailComposer composer;

    @BeforeEach
    void setUp() {
        whatsAppProperties = new WhatsAppProperties();
        whatsAppProperties.setDesafioPlayBaseUrl("https://www.tiamdigital.com/desafio");
        whatsAppProperties.setBusinessNumber("5492214817297");
        composer = new ChallengeAccessEmailComposer(whatsAppProperties);
    }

    @Test
    void compose_hasTheAgreedSubjectAndRecipient() {
        EmailMessage message = composer.compose(purchase("Manuel Robles", "  buyer@example.com  ", 1));

        assertThat(message.subject()).isEqualTo("Tu Desafío TIAM está listo");
        assertThat(message.to()).isEqualTo("buyer@example.com");
    }

    @Test
    void compose_idempotencyKeyIsStablePerPurchase() {
        assertThat(composer.compose(purchase("Ana", "ana@example.com", 1)).idempotencyKey())
                .isEqualTo("challenge-access/42");
    }

    @Test
    void compose_linksToTheDesafioRouteOnTheConfiguredFrontendBaseUrl() {
        EmailMessage message = composer.compose(purchase("Ana", "ana@example.com", 1));
        String expectedLink = "https://www.tiamdigital.com/desafio/" + TOKEN;

        assertThat(message.html()).contains("href=\"" + expectedLink + "\"");
        assertThat(message.text()).contains(expectedLink);
    }

    @Test
    void compose_baseUrlWithTrailingSlash_doesNotProduceADoubleSlash() {
        whatsAppProperties.setDesafioPlayBaseUrl("https://www.tiamdigital.com/desafio/");

        EmailMessage message = composer.compose(purchase("Ana", "ana@example.com", 1));

        assertThat(message.text()).contains("https://www.tiamdigital.com/desafio/" + TOKEN);
        assertThat(message.text()).doesNotContain("desafio//");
    }

    @Test
    void compose_explainsHowItWorks_inRioplatenseSpanish() {
        EmailMessage message = composer.compose(purchase("Ana", "ana@example.com", 1));

        for (String body : new String[] {message.html(), message.text()}) {
            assertThat(body)
                    .contains("7 ejercicios nuevos")
                    .contains("celular")
                    .contains("imprimirlos")
                    .contains("Entrá cuando quieras")
                    .contains("WhatsApp");
        }
        assertThat(message.html()).contains("Cómo funciona").contains("¿Preferís WhatsApp?");
        assertThat(message.text()).contains("CÓMO FUNCIONA").contains("¿PREFERÍS WHATSAPP?");
    }

    @Test
    void compose_offersWhatsAppAsAnAlternative_withAWaMeLink() {
        EmailMessage message = composer.compose(purchase("Ana", "ana@example.com", 1));

        assertThat(message.html()).contains("https://wa.me/5492214817297?text=");
        assertThat(message.text()).contains("https://wa.me/5492214817297?text=");
        assertThat(message.text()).contains("mismo número que usaste al comprar");
    }

    @Test
    void compose_noBusinessNumberConfigured_omitsTheWaMeLinkButKeepsTheWhatsAppMention() {
        whatsAppProperties.setBusinessNumber("");

        EmailMessage message = composer.compose(purchase("Ana", "ana@example.com", 1));

        assertThat(message.html()).doesNotContain("wa.me").contains("¿Preferís WhatsApp?");
        assertThat(message.text()).doesNotContain("wa.me").contains("WhatsApp");
    }

    @Test
    void compose_greetsByCapitalizedFirstName() {
        EmailMessage message = composer.compose(purchase("maria garcia", "m@example.com", 1));

        assertThat(message.html()).contains("Hola Maria,");
        assertThat(message.text()).startsWith("Hola Maria,");
    }

    @Test
    void compose_blankBuyerName_greetsWithoutAName() {
        EmailMessage message = composer.compose(purchase("   ", "m@example.com", 1));

        assertThat(message.text()).startsWith("Hola,");
    }

    @Test
    void compose_escapesTheBuyerNameInHtml() {
        EmailMessage message = composer.compose(
                purchase("<script>alert(1)</script> \"x\" & co", "m@example.com", 1));

        assertThat(message.html()).doesNotContain("<script>");
        assertThat(message.html()).contains("Hola &lt;script&gt;alert(1)&lt;/script&gt;,");
    }

    @Test
    void compose_laterMonth_saysWhichMonthItIs() {
        assertThat(composer.compose(purchase("Ana", "ana@example.com", 1)).text()).doesNotContain("Mes 1");
        assertThat(composer.compose(purchase("Ana", "ana@example.com", 3)).text()).contains("Mes 3");
        assertThat(composer.compose(purchase("Ana", "ana@example.com", 3)).html()).contains("Mes 3");
    }

    @Test
    void compose_html_isAccessibleAndReadable() {
        String html = composer.compose(purchase("Ana", "ana@example.com", 1)).html();

        assertThat(html)
                .contains("lang=\"es-AR\"")
                .contains("font-size:18px")      // body copy
                .contains("font-size:22px")      // button and section headings
                .contains("font-size:28px")      // title
                .contains("<title>Tu Desafío TIAM está listo</title>")
                .contains("role=\"presentation\"")
                // the raw link is repeated as text for clients that block buttons
                .contains(">https://www.tiamdigital.com/desafio/" + TOKEN + "</a>")
                // brand orange fails AA contrast for text; it must not be used here
                .doesNotContainIgnoringCase("#E8531E");
    }

    @Test
    void hasPublicAccessLinkBase_acceptsOnlyAPublicHttpsBase() {
        String[] usable = {
            "https://www.tiamdigital.com/desafio",
            "https://tiamdigital.com/desafio/",
            "  https://tiamdigital.com/desafio  ",
            "HTTPS://TIAMDIGITAL.COM/desafio",
            "https://tiam-frontend.vercel.app/desafio",
        };
        String[] unusable = {
            null,
            "",
            "   ",
            "http://localhost:5173/desafio",             // the application.yml default when the env var is missing
            "https://localhost/desafio",
            "https://LOCALHOST:5173/desafio",
            "https://app.localhost/desafio",
            "https://127.0.0.1/desafio",
            "https://0.0.0.0/desafio",
            "https://[::1]/desafio",
            "http://www.tiamdigital.com/desafio",        // not https
            "www.tiamdigital.com/desafio",               // no scheme
            "//www.tiamdigital.com/desafio",
            "https:///desafio",                          // no host
            "not a url at all",
            "ftp://tiamdigital.com/desafio",
        };

        for (String base : usable) {
            whatsAppProperties.setDesafioPlayBaseUrl(base);
            assertThat(composer.hasPublicAccessLinkBase()).as("usable: %s", base).isTrue();
        }
        for (String base : unusable) {
            whatsAppProperties.setDesafioPlayBaseUrl(base);
            assertThat(composer.hasPublicAccessLinkBase()).as("unusable: %s", base).isFalse();
        }
    }

    @Test
    void accessLinkBase_isTrimmedWithoutTrailingSlashes() {
        whatsAppProperties.setDesafioPlayBaseUrl("  https://tiamdigital.com/desafio//  ");

        assertThat(composer.accessLinkBase()).isEqualTo("https://tiamdigital.com/desafio");
    }

    private ChallengePurchase purchase(String buyerName, String email, int month) {
        ChallengePurchase purchase = new ChallengePurchase();
        purchase.setId(42L);
        purchase.setBuyerName(buyerName);
        purchase.setPhone("5491122334455");
        purchase.setEmail(email);
        purchase.setStatus(ChallengePurchaseStatus.PAID);
        purchase.setAccessToken(TOKEN);
        purchase.setChallengeMonth(month);
        return purchase;
    }
}
