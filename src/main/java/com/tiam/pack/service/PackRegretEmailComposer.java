package com.tiam.pack.service;

import com.tiam.common.email.EmailMessage;
import com.tiam.pack.domain.PackRegretRequest;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

/**
 * The two emails of the regret form: the buyer's receipt ("constancia") and the owner's notice. Plain,
 * large-type HTML plus a text version; everything the buyer typed is HTML-escaped.
 */
@Component
public class PackRegretEmailComposer {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of("America/Argentina/Buenos_Aires"));
    private static final String NOT_GIVEN = "(no indicó)";

    /**
     * The buyer's constancia. It carries ONLY the reference and the date, never anything the form's author typed:
     * the endpoint is public and this goes out from the same domain as the purchase emails, so echoing free text
     * would turn it into a way to send phishing "from TIAM" to any address. (The full text goes to the owner.)
     */
    public EmailMessage buyerReceipt(PackRegretRequest request, String reference) {
        String when = WHEN.format(request.getCreatedAt());

        String text = "Hola,\n\n"
                + "Registramos una solicitud de arrepentimiento con este email.\n\n"
                + "Número de referencia: " + reference + "\n"
                + "Fecha y hora (Argentina): " + when + "\n\n"
                + "El equipo de TIAM va a revisar la solicitud y te va a escribir a este mismo email.\n"
                + "Guardá este mensaje como constancia.\n\n"
                + "Si vos no hiciste esta solicitud, podés ignorar este mensaje.\n\n"
                + "TIAM Digital\n";

        String html = "<div style=\"font-family:Arial,Helvetica,sans-serif;font-size:18px;line-height:1.6;color:#16263F;"
                + "max-width:600px;\">"
                + "<h1 style=\"font-size:26px;color:#15436F;margin:0 0 16px 0;\">Recibimos una solicitud de arrepentimiento</h1>"
                + "<p>Hola,</p>"
                + "<p>Registramos una solicitud de arrepentimiento con este email.</p>"
                + "<p><strong>Número de referencia:</strong> " + escape(reference) + "<br>"
                + "<strong>Fecha y hora (Argentina):</strong> " + escape(when) + "</p>"
                + "<p>El equipo de TIAM va a revisar la solicitud y te va a escribir a este mismo email. "
                + "Guardá este mensaje como constancia.</p>"
                + "<p>Si vos no hiciste esta solicitud, podés ignorar este mensaje.</p>"
                + "<p style=\"font-size:16px;color:#5A6B82;\">TIAM Digital</p></div>";

        return new EmailMessage(request.getEmail(), "Recibimos tu solicitud de arrepentimiento (" + reference + ")",
                html, text, "pack-regret/" + request.getId());
    }

    /** @param knownBuyer whether the address has a paid purchase (only then does the buyer get a receipt by email) */
    public EmailMessage ownerNotice(PackRegretRequest request, String reference, String to, boolean knownBuyer) {
        String when = WHEN.format(request.getCreatedAt());
        String orderRef = StringUtils.hasText(request.getOrderRef()) ? request.getOrderRef() : NOT_GIVEN;
        String reason = StringUtils.hasText(request.getReason()) ? request.getReason() : NOT_GIVEN;
        String linked = request.getOrderId() != null
                ? "Sí, pedido " + request.getOrderId() + " (coincide el email)"
                : "No (el número no coincide con un pedido de ese email)";

        String text = "Nueva solicitud de arrepentimiento " + reference + "\n\n"
                + "Nombre: " + request.getFullName() + "\n"
                + "Email: " + request.getEmail() + "\n"
                + "Pedido indicado: " + orderRef + "\n"
                + "Vinculado a un pedido: " + linked + "\n"
                + "Comprador conocido: " + (knownBuyer ? "sí" : "no (no se le envió constancia por email)") + "\n"
                + "Fecha y hora (Argentina): " + when + "\n"
                + "Motivo: " + reason + "\n\n"
                + "Si corresponde reembolsar, hacelo desde el panel de Mercado Pago: el reembolso revoca el acceso solo.\n";

        String html = "<p style=\"font-family:Arial,Helvetica,sans-serif;font-size:16px;\">"
                + escape(text).replace("\n", "<br>") + "</p>";

        return new EmailMessage(to, "[TIAM Packs] Arrepentimiento " + reference, html, text,
                "pack-regret-owner/" + request.getId());
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value);
    }
}
