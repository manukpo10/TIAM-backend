package com.tiam.challenge.service;

import com.tiam.challenge.config.WhatsAppProperties;
import com.tiam.challenge.domain.ChallengePurchase;
import com.tiam.common.email.EmailMessage;
import com.tiam.pack.config.PackProperties;
import com.tiam.pack.config.PackUrls;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

/**
 * Builds the "Tu Desafío TIAM está listo" email (rioplatense Spanish): the buyer's personal link, a short
 * how-it-works, and WhatsApp as an alternative. Pure: no I/O, so the copy can be unit-tested as plain strings.
 *
 * <p>The look is the Pack emails' (logo over the four-area stripe, navy band with the headline and the button,
 * one illustration, a navy footer), but the type stays big because the audience skews older: 18px body,
 * strong contrast (the button is the landing's darker orange, {@code #CC4610}, 4.7:1 with white; never the
 * brand orange, which fails AA), one big button, and the raw link repeated as text for clients that block
 * buttons or images.
 *
 * <p>Legibility rules, because email clients are hostile: a 600px table layout with every style inline, a hidden
 * preheader, a bulletproof button, every image with alt text and the headline, button and link as live text on a
 * solid colour (so the email reads with images blocked), a {@code prefers-color-scheme: dark} block for the white
 * areas, and a mobile query that makes the button full width. Every dynamic value is HTML-escaped (the buyer
 * name is user input) and substituted in a single pass, so a value can never be read as a placeholder.
 *
 * <p>The images are the frontend's {@code public/email/} folder (logo-tiam-badge.png, desafio-hero.jpg): a sent
 * email keeps pointing at those URLs for good, so the names there are stable. The logo is a badge (the logo on a
 * white tile with rounded, transparent corners), not the plain logo-tiam.png: that one is an opaque white
 * rectangle, which shows as a white box as soon as a client (Gmail, Apple Mail) darkens the header. They are served from the same folder
 * as the Pack emails' ({@code EMAIL_ASSETS_BASE_URL}); anything that is not public https falls back to the
 * production default, because an image a buyer's phone cannot open turns the email into a page of empty boxes.
 */
@Component
public class ChallengeAccessEmailComposer {

    static final String SUBJECT = "Tu Desafío TIAM está listo";
    static final String IDEMPOTENCY_KEY_PREFIX = "challenge-access/";
    static final String DEFAULT_ASSETS_BASE_URL = "https://www.tiamdigital.com/email";
    static final String LOGO_FILE = "logo-tiam-badge.png";
    static final String HERO_FILE = "desafio-hero.jpg";

    private static final String SITE_URL = "https://www.tiamdigital.com";
    private static final int MAX_NAME_LENGTH = 30;
    private static final String WHATSAPP_PREFILLED_TEXT = "Hola, quiero mi ejercicio del Desafío 30 días.";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

    // The landings' palette: navy band, the four area colours, and the darker orange of the buy button.
    private static final String NAVY = "#16263F";
    private static final String BLUE = "#1B6FC4";
    private static final String ORANGE = "#CC4610";
    private static final String LINK = "#15436F";
    private static final String MUTED = "#4F5F76";
    private static final String FONT = "'Segoe UI',Roboto,Helvetica,Arial,sans-serif";

    private final WhatsAppProperties whatsAppProperties;
    private final PackProperties packProperties;

    public ChallengeAccessEmailComposer(WhatsAppProperties whatsAppProperties, PackProperties packProperties) {
        this.whatsAppProperties = whatsAppProperties;
        this.packProperties = packProperties;
    }

    public EmailMessage compose(ChallengePurchase purchase) {
        return compose(purchase, purchase.getAccessToken());
    }

    /**
     * Same email, but the link carries {@code linkToken} instead of the purchase's own token: a month bought from the
     * player is reached from the buyer's FIRST link, so that is the one the email sends.
     */
    public EmailMessage compose(ChallengePurchase purchase, String linkToken) {
        String link = accessLinkBase() + "/" + linkToken;
        String name = firstName(purchase.getBuyerName());
        String whatsAppUrl = whatsAppUrl();
        Integer month = purchase.getChallengeMonth();
        Integer laterMonth = month != null && month > 1 ? month : null;

        return new EmailMessage(
                purchase.getEmail().trim(),
                SUBJECT,
                renderHtml(link, name, whatsAppUrl, laterMonth),
                renderText(link, name, whatsAppUrl, laterMonth),
                IDEMPOTENCY_KEY_PREFIX + purchase.getId());
    }

    String accessLink(ChallengePurchase purchase) {
        return accessLinkBase() + "/" + purchase.getAccessToken();
    }

    /** The configured {@code DESAFIO_PLAY_BASE_URL}, trimmed and without trailing slashes ("" if unset). */
    public String accessLinkBase() {
        return stripTrailingSlashes(whatsAppProperties.getDesafioPlayBaseUrl());
    }

    /**
     * Whether the access link base is something a buyer can actually open: an
     * {@code https} URL with a real host. Empty, localhost (the application.yml
     * default when the env var is missing), loopback and plain-http bases are rejected
     * so an email never goes out with a link that cannot work.
     */
    public boolean hasPublicAccessLinkBase() {
        String base = accessLinkBase();
        if (base.isEmpty()) {
            return false;
        }
        try {
            URI uri = new URI(base);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme()) && host != null && !isLocalHost(host);
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** Where the email images are served from: the configured folder when it is public https, else the default. */
    String assetsBase() {
        String configured = stripTrailingSlashes(packProperties.getEmailAssetsBaseUrl());
        return PackUrls.isPublicHttps(configured) ? configured : DEFAULT_ASSETS_BASE_URL;
    }

    private static String stripTrailingSlashes(String value) {
        if (value == null) {
            return "";
        }
        String stripped = value.strip();
        while (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }

    private static boolean isLocalHost(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.equals("localhost") || lower.endsWith(".localhost")
                || lower.equals("0.0.0.0") || lower.startsWith("127.")
                || lower.equals("[::1]") || lower.equals("::1");
    }

    private String whatsAppUrl() {
        String number = whatsAppProperties.getBusinessNumber();
        if (!StringUtils.hasText(number)) {
            return null;
        }
        String encoded = URLEncoder.encode(WHATSAPP_PREFILLED_TEXT, StandardCharsets.UTF_8).replace("+", "%20");
        return "https://wa.me/" + number.trim() + "?text=" + encoded;
    }

    private static String firstName(String buyerName) {
        if (!StringUtils.hasText(buyerName)) {
            return "";
        }
        // The name is typed by the buyer: drop control and invisible formatting characters (a right-to-left
        // override would scramble the line) and cap it, so a huge "first name" can't blow the layout.
        String clean = buyerName.replaceAll("[\\p{Cc}\\p{Cf}]", "").trim();
        if (clean.isEmpty()) {
            return "";
        }
        String first = clean.split("\\s+")[0];
        if (first.length() > MAX_NAME_LENGTH) {
            first = first.substring(0, MAX_NAME_LENGTH);
        }
        return first.substring(0, 1).toUpperCase() + first.substring(1).toLowerCase();
    }

    // ---- text version -----------------------------------------------------------

    private String renderText(String link, String name, String whatsAppUrl, Integer month) {
        StringBuilder text = new StringBuilder();
        text.append(name.isEmpty() ? "Hola," : "Hola " + name + ",").append("\n\n");
        text.append("¡Gracias por tu compra! Tu Desafío de 30 días ya está activo y podés empezar cuando quieras.\n");
        if (month != null) {
            text.append("Este es tu acceso al Mes ").append(month).append(".\n");
        }
        text.append("\nEntrá a tu Desafío desde este link:\n").append(link).append("\n\n");
        text.append("CÓMO FUNCIONA\n");
        text.append("- Cada semana se desbloquean 7 ejercicios nuevos en este mismo link.\n");
        text.append("- Entrá cuando quieras y hacelos a tu ritmo, con calma.\n");
        text.append("- Podés hacerlos desde el celular o imprimirlos.\n");
        text.append("- No hace falta crear una cuenta ni recordar claves. Guardá este email: "
                + "el link es tu acceso personal, así que no lo compartas.\n\n");
        text.append("¿PREFERÍS WHATSAPP?\n");
        text.append("Si te resulta más cómodo, escribinos por WhatsApp desde el mismo número que usaste "
                + "al comprar y te mandamos tu ejercicio.\n");
        if (whatsAppUrl != null) {
            text.append(whatsAppUrl).append("\n");
        }
        text.append("\nTIAM Digital — estimulación cognitiva\n");
        return text.toString();
    }

    // ---- html version -----------------------------------------------------------

    private String renderHtml(String link, String name, String whatsAppUrl, Integer month) {
        String base = assetsBase();
        String greeting = esc(name.isEmpty() ? "Hola," : "Hola " + name + ",");
        String monthNote = month == null ? "" : "<br>Este es tu acceso al <strong>Mes " + month + "</strong>.";

        String hero = "<tr><td class=\"pad\" bgcolor=\"" + NAVY + "\" style=\"background-color:" + NAVY
                + ";padding:34px 36px 38px;font-family:" + FONT + ";\">"
                + "<p style=\"margin:0 0 10px 0;font-size:13px;line-height:18px;font-weight:700;letter-spacing:1.5px;color:#9CC3EE;\">"
                + (month == null ? "DESAFÍO 30 DÍAS" : "DESAFÍO 30 DÍAS · MES " + month) + "</p>"
                + "<h1 class=\"h1\" style=\"margin:0 0 16px 0;font-size:30px;line-height:37px;font-weight:800;color:#FFFFFF;\">"
                + "¡Tu Desafío TIAM está listo!</h1>"
                + "<p style=\"margin:0 0 28px 0;font-size:18px;line-height:28px;color:#DCE6F4;\">" + greeting
                + "<br>¡Gracias por tu compra! Tu Desafío de 30 días ya está activo y podés empezar cuando quieras."
                + monthNote + "</p>"
                + button(link, "Abrir mi Desafío", ORANGE, 22, 20, 36)
                + "</td></tr>\n";

        String picture = "<tr><td bgcolor=\"#FBEBD3\" style=\"background-color:#FBEBD3;font-size:0;line-height:0;\">"
                + "<img src=\"" + esc(base + "/" + HERO_FILE) + "\" width=\"600\" "
                + "alt=\"Ilustración de una hija y su mamá sonriendo ante un celular, en el living de casa\" "
                + "style=\"display:block;width:100%;max-width:600px;height:auto;border:0;outline:none;text-decoration:none;"
                + "font-family:" + FONT + ";font-size:16px;line-height:22px;color:" + NAVY + ";\"></td></tr>\n";

        String howItWorks = sectionTitle("Cómo funciona")
                + tip(1, "Cada semana, 7 ejercicios nuevos.", "Se desbloquean en este mismo link.")
                + tip(2, "Entrá cuando quieras", "y hacelos a tu ritmo, con calma.")
                + tip(3, "Desde el celular o en papel.", "Podés hacerlos en la pantalla o imprimirlos.");

        String yourLink = "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" "
                + "class=\"bg-soft bd-soft\" bgcolor=\"#F2F7FD\" style=\"background-color:#F2F7FD;border:1px solid #D5E3F3;"
                + "border-radius:14px;border-collapse:separate;margin:10px 0 30px 0;\"><tr>"
                + "<td style=\"padding:20px 22px 18px 22px;\">"
                + "<h2 class=\"tx-h\" style=\"margin:0 0 8px 0;font-size:20px;line-height:26px;font-weight:800;color:" + NAVY
                + ";\">Tu link personal</h2>"
                + "<p class=\"tx\" style=\"margin:0 0 12px 0;font-size:16px;line-height:24px;color:" + NAVY
                + ";\">Si el botón no se abre, copiá este link y pegalo en tu navegador:<br>"
                + "<a class=\"lnk\" href=\"" + esc(link) + "\" style=\"color:" + LINK
                + ";text-decoration:underline;word-break:break-all;\">" + esc(link) + "</a></p>"
                + "<p class=\"tx-muted\" style=\"margin:0;font-size:15px;line-height:22px;color:" + MUTED
                + ";\">No hace falta crear una cuenta ni recordar claves. <strong>Guardá este email</strong>: "
                + "el link es tu acceso personal, así que no lo compartas.</p>"
                + "</td></tr></table>";

        String whatsApp = sectionTitle("¿Preferís WhatsApp?")
                + "<p class=\"tx\" style=\"margin:0 0 14px 0;\">Si te resulta más cómodo, escribinos por WhatsApp desde el "
                + "mismo número que usaste al comprar y te mandamos tu ejercicio.</p>"
                + (whatsAppUrl == null ? "" : button(whatsAppUrl, "Escribir por WhatsApp", BLUE, 18, 14, 26));

        String body = "<tr><td class=\"pad bg-card tx\" bgcolor=\"#FFFFFF\" style=\"background-color:#FFFFFF;"
                + "padding:34px 36px 38px;font-family:" + FONT + ";font-size:18px;line-height:28px;color:" + NAVY + ";\">"
                + howItWorks + yourLink + whatsApp + "</td></tr>\n";

        Map<String, String> values = new HashMap<>();
        values.put("TITLE", esc(SUBJECT));
        values.put("PREHEADER", esc("Tu acceso personal al Desafío de 30 días ya está activo."));
        values.put("FILLER", "&zwnj;&nbsp;".repeat(70));
        values.put("LOGO", esc(base + "/" + LOGO_FILE));
        values.put("SITE", SITE_URL);
        values.put("ROWS", hero + picture + body);
        return fill(PAGE, values);
    }

    /** Bulletproof button: the table cell carries the colour (so it is a button even where the anchor's padding is ignored, as in desktop Outlook). */
    private static String button(String href, String label, String background, int fontSize, int padV, int padH) {
        return "<table role=\"presentation\" class=\"btn\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" "
                + "style=\"border-collapse:separate;\"><tr>"
                + "<td align=\"center\" bgcolor=\"" + background + "\" style=\"background-color:" + background
                + ";border-radius:10px;mso-padding-alt:" + padV + "px " + padH + "px;\">"
                + "<a href=\"" + esc(href) + "\" target=\"_blank\" style=\"display:inline-block;padding:" + padV + "px "
                + padH + "px;font-family:" + FONT + ";font-size:" + fontSize + "px;line-height:" + (fontSize + 6)
                + "px;font-weight:700;color:#FFFFFF;text-decoration:none;border-radius:10px;\">" + label
                + "</a></td></tr></table>";
    }

    private static String sectionTitle(String text) {
        return "<h2 class=\"tx-h\" style=\"margin:0 0 16px 0;font-size:22px;line-height:29px;font-weight:800;color:" + NAVY
                + ";\">" + text + "</h2>";
    }

    /** A numbered step: a round badge and a bold lead-in followed by one sentence. */
    private static String tip(int number, String leadHtml, String textHtml) {
        return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin:0 0 16px 0;\"><tr>"
                + "<td width=\"46\" valign=\"top\" style=\"width:46px;padding-top:1px;\">"
                + "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>"
                + "<td align=\"center\" valign=\"middle\" width=\"32\" height=\"32\" bgcolor=\"" + BLUE + "\" style=\"width:32px;height:32px;"
                + "background-color:" + BLUE + ";border-radius:16px;font-size:17px;line-height:32px;font-weight:700;color:#FFFFFF;\">"
                + number + "</td></tr></table></td>"
                + "<td class=\"tx\" valign=\"top\" style=\"font-size:18px;line-height:28px;color:" + NAVY + ";\">"
                + "<strong class=\"tx-h\">" + leadHtml + "</strong> " + textHtml + "</td></tr></table>";
    }

    /** Escapes user-controlled text. UTF-8 mode: only the HTML specials, accents stay readable in the source. */
    private static String esc(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value, "UTF-8");
    }

    /** Single-pass substitution, so a value can never be re-interpreted as a placeholder. */
    private static String fill(String template, Map<String, String> values) {
        return PLACEHOLDER.matcher(template)
                .replaceAll(match -> Matcher.quoteReplacement(values.getOrDefault(match.group(1), "")));
    }

    private static final String PAGE = """
            <!DOCTYPE html>
            <html lang="es-AR" xmlns="http://www.w3.org/1999/xhtml" xmlns:v="urn:schemas-microsoft-com:vml" xmlns:o="urn:schemas-microsoft-com:office:office">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta http-equiv="X-UA-Compatible" content="IE=edge">
            <meta name="x-apple-disable-message-reformatting">
            <meta name="format-detection" content="telephone=no,address=no,email=no,date=no,url=no">
            <meta name="color-scheme" content="light dark">
            <meta name="supported-color-schemes" content="light dark">
            <title>{{TITLE}}</title>
            <!--[if mso]><xml><o:OfficeDocumentSettings><o:PixelsPerInch>96</o:PixelsPerInch></o:OfficeDocumentSettings></xml><![endif]-->
            <style>
              :root { color-scheme: light dark; supported-color-schemes: light dark; }
              body, table, td, a { -webkit-text-size-adjust: 100%; -ms-text-size-adjust: 100%; }
              table, td { mso-table-lspace: 0; mso-table-rspace: 0; }
              img { -ms-interpolation-mode: bicubic; }
              @media only screen and (max-width: 520px) {
                .outer { padding: 0 !important; }
                .pad { padding-left: 22px !important; padding-right: 22px !important; }
                .h1 { font-size: 27px !important; line-height: 33px !important; }
                .btn { width: 100% !important; }
                .btn a { display: block !important; text-align: center !important; padding-left: 12px !important; padding-right: 12px !important; }
              }
              @media (prefers-color-scheme: dark) {
                body, .bg-page { background-color: #0E1624 !important; }
                .bg-card { background-color: #17233A !important; }
                .bg-soft { background-color: #1F2E48 !important; }
                .bd-soft { border-color: #34496E !important; }
                .tx { color: #E3EAF5 !important; }
                .tx-h { color: #FFFFFF !important; }
                .tx-muted { color: #AEBCD0 !important; }
                a.lnk { color: #8FBDF0 !important; }
              }
            </style>
            </head>
            <body class="bg-page" bgcolor="#EEF3F9" style="margin:0;padding:0;background-color:#EEF3F9;">
            <div style="display:none;max-height:0;max-width:0;overflow:hidden;opacity:0;font-size:1px;line-height:1px;color:#EEF3F9;mso-hide:all;">{{PREHEADER}}{{FILLER}}</div>
            <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" class="bg-page" bgcolor="#EEF3F9" style="background-color:#EEF3F9;">
              <tr>
                <td class="outer" align="center" style="padding:24px 12px 32px 12px;">
                  <table role="presentation" class="container" width="600" cellpadding="0" cellspacing="0" border="0" style="width:100%;max-width:600px;border-collapse:separate;border-radius:16px;overflow:hidden;">
                    <tr>
                      <td align="center" bgcolor="#FFFFFF" style="background-color:#FFFFFF;padding:12px 24px 12px 24px;">
                        <a href="{{SITE}}" target="_blank" style="text-decoration:none;"><img src="{{LOGO}}" width="140" height="87" alt="TIAM Digital" style="display:block;margin:0 auto;border:0;outline:none;font-family:'Segoe UI',Roboto,Helvetica,Arial,sans-serif;font-size:24px;line-height:87px;font-weight:800;color:#16263F;"></a>
                      </td>
                    </tr>
                    <tr>
                      <td style="font-size:0;line-height:0;">
                        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0">
                          <tr>
                            <td width="25%" height="5" bgcolor="#1B6FC4" style="height:5px;font-size:0;line-height:0;background-color:#1B6FC4;">&nbsp;</td>
                            <td width="25%" height="5" bgcolor="#CC4610" style="height:5px;font-size:0;line-height:0;background-color:#CC4610;">&nbsp;</td>
                            <td width="25%" height="5" bgcolor="#2C801C" style="height:5px;font-size:0;line-height:0;background-color:#2C801C;">&nbsp;</td>
                            <td width="25%" height="5" bgcolor="#5B3C9E" style="height:5px;font-size:0;line-height:0;background-color:#5B3C9E;">&nbsp;</td>
                          </tr>
                        </table>
                      </td>
                    </tr>
            {{ROWS}}
                    <tr>
                      <td class="pad" align="center" bgcolor="#16263F" style="background-color:#16263F;padding:28px 36px 30px 36px;font-family:'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
                        <p style="margin:0 0 8px 0;font-size:15px;line-height:22px;font-weight:700;color:#FFFFFF;"><a href="{{SITE}}" target="_blank" style="color:#FFFFFF;text-decoration:none;">TIAM &middot; tiamdigital.com</a></p>
                        <p style="margin:0 0 10px 0;font-size:13px;line-height:20px;color:#B5C5DB;">Material de estimulación cognitiva. No reemplaza la evaluación ni el tratamiento de un profesional de la salud.</p>
                        <p style="margin:0;font-size:12px;line-height:18px;color:#8FA3BF;">Recibiste este email porque compraste el Desafío 30 días.</p>
                      </td>
                    </tr>
                  </table>
                </td>
              </tr>
            </table>
            </body>
            </html>
            """;
}
