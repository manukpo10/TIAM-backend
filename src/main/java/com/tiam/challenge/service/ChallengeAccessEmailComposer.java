package com.tiam.challenge.service;

import com.tiam.challenge.config.WhatsAppProperties;
import com.tiam.challenge.domain.ChallengePurchase;
import com.tiam.common.email.EmailMessage;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

/**
 * Builds the "Tu Desafío TIAM está listo" email (rioplatense Spanish): the buyer's
 * personal link, a short how-it-works, and WhatsApp as an alternative. Pure: no
 * I/O, so the copy can be unit-tested as plain strings.
 *
 * <p>The audience skews older, so the HTML is deliberately plain: web-safe font,
 * large type (18px body), strong contrast (navy/blue on white — never the orange,
 * which fails AA), one big button, and the raw link repeated as text for clients
 * that block buttons. Every dynamic value is HTML-escaped (the buyer name is
 * user input).
 */
@Component
@RequiredArgsConstructor
public class ChallengeAccessEmailComposer {

    static final String SUBJECT = "Tu Desafío TIAM está listo";
    static final String IDEMPOTENCY_KEY_PREFIX = "challenge-access/";

    private static final String WHATSAPP_PREFILLED_TEXT = "Hola, quiero mi ejercicio del Desafío 30 días.";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

    private final WhatsAppProperties whatsAppProperties;

    public EmailMessage compose(ChallengePurchase purchase) {
        String link = accessLink(purchase);
        String name = firstName(purchase.getBuyerName());
        String whatsAppUrl = whatsAppUrl();
        Integer month = purchase.getChallengeMonth();
        boolean laterMonth = month != null && month > 1;

        return new EmailMessage(
                purchase.getEmail().trim(),
                SUBJECT,
                renderHtml(link, name, whatsAppUrl, laterMonth ? month : null),
                renderText(link, name, whatsAppUrl, laterMonth ? month : null),
                IDEMPOTENCY_KEY_PREFIX + purchase.getId());
    }

    String accessLink(ChallengePurchase purchase) {
        return accessLinkBase() + "/" + purchase.getAccessToken();
    }

    /** The configured {@code DESAFIO_PLAY_BASE_URL}, trimmed and without trailing slashes ("" if unset). */
    public String accessLinkBase() {
        String base = whatsAppProperties.getDesafioPlayBaseUrl();
        if (base == null) {
            return "";
        }
        base = base.strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
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
        String first = buyerName.trim().split("\\s+")[0];
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
        Map<String, String> values = new HashMap<>();
        values.put("GREETING", escape(name.isEmpty() ? "Hola," : "Hola " + name + ","));
        values.put("MONTH_NOTE", month == null ? "" : "<br>Este es tu acceso al <strong>Mes " + month + "</strong>.");
        values.put("LINK", escape(link));
        values.put("WHATSAPP_BLOCK", whatsAppUrl == null ? "" : WHATSAPP_BUTTON.replace("{{URL}}", escape(whatsAppUrl)));
        return fill(HTML_TEMPLATE, values);
    }

    /** Single-pass substitution, so a value can never be re-interpreted as a placeholder. */
    private static String fill(String template, Map<String, String> values) {
        return PLACEHOLDER.matcher(template)
                .replaceAll(match -> Matcher.quoteReplacement(values.getOrDefault(match.group(1), "")));
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value);
    }

    private static final String WHATSAPP_BUTTON = """
            <p style="margin:16px 0 0 0;">
              <a href="{{URL}}" style="display:inline-block;background:#FFFFFF;color:#15436F;font-size:18px;font-weight:bold;text-decoration:underline;padding:12px 4px;">Escribir por WhatsApp</a>
            </p>
            """;

    private static final String HTML_TEMPLATE = """
            <!DOCTYPE html>
            <html lang="es-AR">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Tu Desafío TIAM está listo</title>
            </head>
            <body style="margin:0;padding:0;background:#F4F7FB;">
              <div style="display:none;max-height:0;overflow:hidden;">Tu acceso personal al Desafío de 30 días ya está activo.</div>
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#F4F7FB;">
                <tr>
                  <td align="center" style="padding:24px 12px;">
                    <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:600px;background:#FFFFFF;border-radius:8px;">
                      <tr>
                        <td style="padding:32px 28px;font-family:Arial,Helvetica,sans-serif;font-size:18px;line-height:1.6;color:#16263F;">
                          <h1 style="margin:0 0 16px 0;font-size:28px;line-height:1.3;color:#15436F;">¡Tu Desafío TIAM está listo!</h1>
                          <p style="margin:0 0 16px 0;">{{GREETING}}</p>
                          <p style="margin:0 0 24px 0;">¡Gracias por tu compra! Tu Desafío de 30 días ya está activo y podés empezar cuando quieras.{{MONTH_NOTE}}</p>
                          <table role="presentation" cellpadding="0" cellspacing="0" style="margin:0 0 16px 0;">
                            <tr>
                              <td style="background:#1B6FC4;border-radius:8px;">
                                <a href="{{LINK}}" style="display:inline-block;padding:18px 32px;font-family:Arial,Helvetica,sans-serif;font-size:22px;font-weight:bold;color:#FFFFFF;text-decoration:none;">Abrir mi Desafío</a>
                              </td>
                            </tr>
                          </table>
                          <p style="margin:0 0 32px 0;font-size:16px;">Si el botón no se abre, copiá este link y pegalo en tu navegador:<br>
                            <a href="{{LINK}}" style="color:#15436F;word-break:break-all;">{{LINK}}</a></p>
                          <h2 style="margin:0 0 12px 0;font-size:22px;line-height:1.3;color:#15436F;">Cómo funciona</h2>
                          <ul style="margin:0 0 32px 0;padding-left:24px;">
                            <li style="margin-bottom:10px;">Cada semana se desbloquean <strong>7 ejercicios nuevos</strong> en este mismo link.</li>
                            <li style="margin-bottom:10px;">Entrá cuando quieras y hacelos a tu ritmo, con calma.</li>
                            <li style="margin-bottom:10px;">Podés hacerlos <strong>desde el celular o imprimirlos</strong>.</li>
                            <li style="margin-bottom:0;">No hace falta crear una cuenta ni recordar claves. Guardá este email: el link es tu acceso personal, así que no lo compartas.</li>
                          </ul>
                          <h2 style="margin:0 0 12px 0;font-size:22px;line-height:1.3;color:#15436F;">¿Preferís WhatsApp?</h2>
                          <p style="margin:0;">Si te resulta más cómodo, escribinos por WhatsApp desde el mismo número que usaste al comprar y te mandamos tu ejercicio.</p>
                          {{WHATSAPP_BLOCK}}
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
