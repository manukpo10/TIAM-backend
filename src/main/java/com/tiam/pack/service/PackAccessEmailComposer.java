package com.tiam.pack.service;

import com.tiam.challenge.config.WhatsAppProperties;
import com.tiam.common.email.EmailMessage;
import com.tiam.pack.config.PackProperties;
import com.tiam.pack.domain.PackOrder;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

/**
 * Builds the "Tu Pack TIAM está listo" email (rioplatense Spanish): one big button to the downloads page, the
 * link repeated as text, how long the access lasts and how to get a new link, and, when the buyer added the
 * games month, a block with their personal Desafío link. ONE email for the whole purchase.
 *
 * <p>Nothing is attached: the files are 19 MB and up and only reachable through short-lived signed links
 * issued from the downloads page. Pure (no I/O) so the copy is unit-testable as plain strings.
 *
 * <p>Same accessibility stance as the Desafío email (the audience skews older): web-safe font, 18px body,
 * strong contrast, no orange text. Every dynamic value is HTML-escaped (the buyer name is user input) and
 * substituted in a single pass, so a value can never be re-read as a template placeholder.
 */
@Component
@RequiredArgsConstructor
public class PackAccessEmailComposer {

    /** FIRST = right after payment (stable idempotency key); RESEND = a new link requested by the buyer. */
    public enum Variant {
        FIRST,
        RESEND
    }

    static final String SUBJECT_FIRST = "Tu Pack TIAM está listo: descargá tus PDF";
    static final String SUBJECT_RESEND = "Tu nuevo acceso al Pack TIAM";

    private static final String WHATSAPP_PREFILLED_TEXT = "Hola, compré el Pack TIAM con el mes de juegos y quiero activarlo.";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

    private final PackProperties properties;
    private final WhatsAppProperties whatsAppProperties;

    public EmailMessage compose(PackOrder order, Optional<String> gamesLink, Variant variant) {
        String link = downloadLink(order);
        String name = firstName(order.getBuyerName());
        String recoverLink = recoverLink();
        String gamesUrl = order.isWithGames() ? usableGamesLink(gamesLink) : null;
        String whatsAppUrl = order.isWithGames() ? whatsAppUrl() : null;
        boolean resend = variant == Variant.RESEND;

        return new EmailMessage(
                order.getEmail().trim(),
                resend ? SUBJECT_RESEND : SUBJECT_FIRST,
                renderHtml(order, link, name, recoverLink, gamesUrl, whatsAppUrl, resend),
                renderText(order, link, name, recoverLink, gamesUrl, whatsAppUrl, resend),
                idempotencyKey(order, variant));
    }

    /** The configured downloads base, trimmed and without trailing slashes ("" if unset). */
    String downloadBase() {
        return stripTrailingSlashes(properties.getDownloadBaseUrl());
    }

    String downloadLink(PackOrder order) {
        return downloadBase() + "/" + order.getAccessToken();
    }

    private String recoverLink() {
        return stripTrailingSlashes(properties.getLandingBaseUrl()) + "/pack/recuperar";
    }

    /** Whether the downloads base is something a buyer can open: https with a real host. */
    public boolean hasPublicDownloadBase() {
        return isPublicHttps(downloadBase());
    }

    private static String idempotencyKey(PackOrder order, Variant variant) {
        if (variant == Variant.FIRST) {
            return "pack-access/" + order.getId();
        }
        String token = order.getAccessToken() == null ? "" : order.getAccessToken();
        return "pack-access-resend/" + order.getId() + "/" + token.substring(0, Math.min(8, token.length()));
    }

    /** Only a public https Desafío link goes into an email; anything else would be a link that cannot work. */
    private static String usableGamesLink(Optional<String> gamesLink) {
        return gamesLink.filter(PackAccessEmailComposer::isPublicHttps).orElse(null);
    }

    static boolean isPublicHttps(String url) {
        if (!StringUtils.hasText(url)) {
            return false;
        }
        try {
            URI uri = new URI(url.strip());
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null) {
                return false;
            }
            String lower = host.toLowerCase(Locale.ROOT);
            return !(lower.equals("localhost") || lower.endsWith(".localhost") || lower.equals("0.0.0.0")
                    || lower.startsWith("127.") || lower.equals("[::1]") || lower.equals("::1"));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private String whatsAppUrl() {
        String number = whatsAppProperties.getBusinessNumber();
        if (!StringUtils.hasText(number)) {
            return null;
        }
        String encoded = URLEncoder.encode(WHATSAPP_PREFILLED_TEXT, StandardCharsets.UTF_8).replace("+", "%20");
        return "https://wa.me/" + number.trim() + "?text=" + encoded;
    }

    private static String stripTrailingSlashes(String value) {
        String result = value == null ? "" : value.strip();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String firstName(String buyerName) {
        if (!StringUtils.hasText(buyerName)) {
            return "";
        }
        String first = buyerName.trim().split("\\s+")[0];
        return first.substring(0, 1).toUpperCase() + first.substring(1).toLowerCase();
    }

    // ---- text version ----------------------------------------------------------------------------

    private String renderText(PackOrder order, String link, String name, String recoverLink, String gamesUrl,
            String whatsAppUrl, boolean resend) {
        StringBuilder text = new StringBuilder();
        text.append(name.isEmpty() ? "Hola," : "Hola " + name + ",").append("\n\n");
        if (resend) {
            text.append("Acá tenés tu nuevo acceso al Pack TIAM (Edición ").append(order.getEdition().displayName())
                    .append("). El link anterior dejó de funcionar.\n");
        } else {
            text.append("¡Gracias por tu compra! Tu Pack TIAM (Edición ").append(order.getEdition().displayName())
                    .append(") ya está listo para descargar.\n");
        }
        text.append("\nDescargá tus archivos desde este link:\n").append(link).append("\n\n");

        text.append("QUÉ INCLUYE\n");
        text.append("- El pack completo en un solo PDF y los cuadernillos sueltos en un ZIP, en papel A4 y en Carta.\n");
        if (order.isWithExtra()) {
            text.append("- El Cuadernillo extra (PDF), también en A4 y en Carta.\n");
        }
        text.append("\n");

        if (order.isWithGames()) {
            text.append("MES DE JUEGOS\n");
            if (gamesUrl != null) {
                text.append("Tu mes de juegos del Desafío 30 días está activo. Entrá desde tu link personal:\n")
                        .append(gamesUrl).append("\n");
            } else {
                text.append("Tu mes de juegos del Desafío 30 días está incluido. Para activarlo, escribinos por "
                        + "WhatsApp desde el mismo número que dejaste al comprar.\n");
            }
            if (whatsAppUrl != null) {
                text.append("WhatsApp: ").append(whatsAppUrl).append("\n");
            }
            text.append("\n");
        }

        text.append("CÓMO FUNCIONA\n");
        text.append("- Elegí tu papel (A4 o Carta) y descargá. Podés imprimirlo todas las veces que quieras.\n");
        text.append("- Tu acceso a las descargas dura ").append(properties.getAccessDays()).append(" días desde hoy.\n");
        text.append("- Este link es personal: no lo compartas. Si lo perdés, pedí uno nuevo acá: ").append(recoverLink).append("\n\n");
        text.append("TIAM Digital — estimulación cognitiva\n");
        return text.toString();
    }

    // ---- html version ----------------------------------------------------------------------------

    private String renderHtml(PackOrder order, String link, String name, String recoverLink, String gamesUrl,
            String whatsAppUrl, boolean resend) {
        Map<String, String> values = new HashMap<>();
        values.put("TITLE", resend ? "Tu nuevo acceso al Pack TIAM" : "¡Tu Pack TIAM está listo!");
        values.put("GREETING", escape(name.isEmpty() ? "Hola," : "Hola " + name + ","));
        values.put("INTRO", resend
                ? "Acá tenés tu nuevo acceso al Pack TIAM (Edición " + escape(order.getEdition().displayName())
                        + "). <strong>El link anterior dejó de funcionar.</strong>"
                : "¡Gracias por tu compra! Tu Pack TIAM (Edición " + escape(order.getEdition().displayName())
                        + ") ya está listo para descargar.");
        values.put("LINK", escape(link));
        values.put("EXTRA_ITEM", order.isWithExtra()
                ? "<li style=\"margin-bottom:10px;\">El <strong>Cuadernillo extra</strong> (PDF), también en A4 y en Carta.</li>"
                : "");
        values.put("GAMES_BLOCK", order.isWithGames() ? gamesBlock(gamesUrl, whatsAppUrl) : "");
        values.put("ACCESS_DAYS", String.valueOf(properties.getAccessDays()));
        values.put("RECOVER_LINK", escape(recoverLink));
        return fill(HTML_TEMPLATE, values);
    }

    private static String gamesBlock(String gamesUrl, String whatsAppUrl) {
        StringBuilder html = new StringBuilder();
        html.append("<h2 style=\"margin:0 0 12px 0;font-size:22px;line-height:1.3;color:#15436F;\">Tu mes de juegos</h2>\n");
        if (gamesUrl != null) {
            html.append("<p style=\"margin:0 0 16px 0;\">Tu mes de juegos del Desafío 30 días está activo. Entrá desde tu link personal:</p>\n");
            html.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:0 0 16px 0;\"><tr>"
                    + "<td style=\"background:#1B6FC4;border-radius:8px;\">"
                    + "<a href=\"").append(escape(gamesUrl)).append("\" style=\"display:inline-block;padding:16px 28px;"
                    + "font-family:Arial,Helvetica,sans-serif;font-size:20px;font-weight:bold;color:#FFFFFF;"
                    + "text-decoration:none;\">Abrir mi mes de juegos</a></td></tr></table>\n");
            html.append("<p style=\"margin:0 0 16px 0;font-size:16px;\"><a href=\"").append(escape(gamesUrl))
                    .append("\" style=\"color:#15436F;word-break:break-all;\">").append(escape(gamesUrl)).append("</a></p>\n");
        } else {
            html.append("<p style=\"margin:0 0 16px 0;\">Tu mes de juegos del Desafío 30 días está incluido. "
                    + "Para activarlo, escribinos por WhatsApp desde el mismo número que dejaste al comprar.</p>\n");
        }
        if (whatsAppUrl != null) {
            html.append("<p style=\"margin:0 0 32px 0;\"><a href=\"").append(escape(whatsAppUrl))
                    .append("\" style=\"color:#15436F;font-size:18px;font-weight:bold;text-decoration:underline;\">"
                            + "Escribir por WhatsApp</a></p>\n");
        }
        return html.toString();
    }

    /** Single-pass substitution, so a value can never be re-interpreted as a placeholder. */
    private static String fill(String template, Map<String, String> values) {
        return PLACEHOLDER.matcher(template)
                .replaceAll(match -> Matcher.quoteReplacement(values.getOrDefault(match.group(1), "")));
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value);
    }

    private static final String HTML_TEMPLATE = """
            <!DOCTYPE html>
            <html lang="es-AR">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Tu Pack TIAM</title>
            </head>
            <body style="margin:0;padding:0;background:#F4F7FB;">
              <div style="display:none;max-height:0;overflow:hidden;">Tus PDF imprimibles están listos para descargar.</div>
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#F4F7FB;">
                <tr>
                  <td align="center" style="padding:24px 12px;">
                    <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:600px;background:#FFFFFF;border-radius:8px;">
                      <tr>
                        <td style="padding:32px 28px;font-family:Arial,Helvetica,sans-serif;font-size:18px;line-height:1.6;color:#16263F;">
                          <h1 style="margin:0 0 16px 0;font-size:28px;line-height:1.3;color:#15436F;">{{TITLE}}</h1>
                          <p style="margin:0 0 16px 0;">{{GREETING}}</p>
                          <p style="margin:0 0 24px 0;">{{INTRO}}</p>
                          <table role="presentation" cellpadding="0" cellspacing="0" style="margin:0 0 16px 0;">
                            <tr>
                              <td style="background:#1B6FC4;border-radius:8px;">
                                <a href="{{LINK}}" style="display:inline-block;padding:18px 32px;font-family:Arial,Helvetica,sans-serif;font-size:22px;font-weight:bold;color:#FFFFFF;text-decoration:none;">Ir a mis descargas</a>
                              </td>
                            </tr>
                          </table>
                          <p style="margin:0 0 32px 0;font-size:16px;">Si el botón no se abre, copiá este link y pegalo en tu navegador:<br>
                            <a href="{{LINK}}" style="color:#15436F;word-break:break-all;">{{LINK}}</a></p>
                          <h2 style="margin:0 0 12px 0;font-size:22px;line-height:1.3;color:#15436F;">Qué incluye</h2>
                          <ul style="margin:0 0 32px 0;padding-left:24px;">
                            <li style="margin-bottom:10px;">El <strong>pack completo</strong> en un solo PDF y los <strong>cuadernillos sueltos</strong> en un ZIP, en papel A4 y en Carta.</li>
                            {{EXTRA_ITEM}}
                          </ul>
                          {{GAMES_BLOCK}}
                          <h2 style="margin:0 0 12px 0;font-size:22px;line-height:1.3;color:#15436F;">Cómo funciona</h2>
                          <ul style="margin:0 0 32px 0;padding-left:24px;">
                            <li style="margin-bottom:10px;">Elegí tu papel (A4 o Carta) y descargá. Podés imprimirlo todas las veces que quieras.</li>
                            <li style="margin-bottom:10px;">Tu acceso a las descargas dura <strong>{{ACCESS_DAYS}} días</strong> desde hoy.</li>
                            <li style="margin-bottom:0;">Este link es personal: no lo compartas. Si lo perdés, <a href="{{RECOVER_LINK}}" style="color:#15436F;">pedí uno nuevo acá</a>.</li>
                          </ul>
                          <p style="margin:32px 0 0 0;font-size:16px;color:#5A6B82;">TIAM Digital — estimulación cognitiva</p>
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
