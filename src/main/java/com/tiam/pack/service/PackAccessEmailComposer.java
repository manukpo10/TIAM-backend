package com.tiam.pack.service;

import static com.tiam.pack.service.PackEmailHtml.BLUE;
import static com.tiam.pack.service.PackEmailHtml.GREEN;
import static com.tiam.pack.service.PackEmailHtml.ORANGE;
import static com.tiam.pack.service.PackEmailHtml.PURPLE;
import static com.tiam.pack.service.PackEmailHtml.esc;

import com.tiam.challenge.config.WhatsAppProperties;
import com.tiam.common.email.EmailMessage;
import com.tiam.pack.config.PackProperties;
import com.tiam.pack.config.PackSalesReadiness;
import com.tiam.pack.config.PackUrls;
import com.tiam.pack.domain.PackCatalog;
import com.tiam.pack.domain.PackEdition;
import com.tiam.pack.domain.PackFile;
import com.tiam.pack.domain.PackOrder;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Builds the "Tu Pack TIAM está listo" email (rioplatense Spanish) and the "I lost my link" one: the look of the
 * landings (logo, navy band, the buy-button orange, the product picture), one clear button to the downloads page,
 * a "Tus archivos" panel with what was bought and what each file weighs, three printing tips, how long the access
 * lasts and where to get a new link or help. ONE email for the whole purchase; when the buyer added the games
 * month it carries their personal Desafío link and the activation step.
 *
 * <p>Nothing is attached: the files are 19 MB and up and only reachable through short-lived signed links
 * issued from the downloads page. Pure (no I/O), so the copy is unit-testable as plain strings. The markup lives in
 * {@link PackEmailHtml}; the plain-text alternative is built here from the same facts, so the two cannot drift apart
 * (the file list and the sizes come from {@link PackCatalog}).
 *
 * <p><b>Images</b> are loaded from stable public https URLs: {@code EMAIL_ASSETS_BASE_URL} (default
 * https://www.tiamdigital.com/email, the frontend's {@code public/email/}) plus a fixed file name. Every dynamic
 * value is HTML-escaped (the buyer name is user input) and nothing a user typed is ever read as a template.
 */
@Component
@RequiredArgsConstructor
public class PackAccessEmailComposer {

    static final String SUBJECT_FIRST = "Tu Pack TIAM está listo: descargá tus PDF";
    static final String SUBJECT_RESEND = "Tu acceso al Pack TIAM";
    static final String SUBJECT_FIRST_CUADERNOS = "Tus Cuadernos TIAM están listos: descargá tus PDF";
    static final String SUBJECT_RESEND_CUADERNOS = "Tu acceso a los Cuadernos TIAM";
    /** A "lost my link" email that covers a Pack order AND a Cuadernos order. */
    static final String SUBJECT_RESEND_MIXED = "Tu acceso a tus compras de TIAM";

    static final String CTA_LABEL = "Descargar mis archivos";
    static final String DEFAULT_ASSETS_BASE_URL = "https://www.tiamdigital.com/email";
    static final String LOGO_FILE = "logo-tiam.png";
    /** The Cuadernos picture, served by the frontend's {@code public/email/} next to the logo. */
    static final String CUADERNOS_IMAGE_FILE = "cuadernos.jpg";

    /** The logo file is 286x160 (2x); it is shown at 120x67, the 429:240 ratio of the landings. */
    private static final int LOGO_WIDTH = 120;
    private static final int LOGO_HEIGHT = 67;

    /**
     * One paid order in a re-send email: the replacement token to link to and, when its Desafío month was granted, the
     * Desafío link and the number of the month. Both are ignored for an order whose month was never granted.
     */
    public record ResendEntry(PackOrder order, String token, Optional<String> gamesLink, Optional<Integer> gamesMonth) {

        public ResendEntry(PackOrder order, String token, Optional<String> gamesLink) {
            this(order, token, gamesLink, Optional.empty());
        }
    }

    /** The Desafío month an order was ACTUALLY given: its usable personal link (or null) and its number (or null). */
    private record Desafio(String url, Integer month) {
        /** "Mes 2 del Desafío"; "1 mes del Desafío" when the number could not be read. Never "primer mes". */
        String title() {
            return month != null ? "Mes " + month + " del Desafío" : "1 mes del Desafío";
        }
    }

    private static final String WHATSAPP_GAMES_TEXT = "Hola, compré el Pack TIAM con el mes de juegos y quiero empezar.";
    private static final String WHATSAPP_GAMES_TEXT_CUADERNOS =
            "Hola, compré los Cuadernos TIAM con el mes del Desafío y quiero empezar.";
    private static final String WHATSAPP_HELP_TEXT = "Hola, necesito ayuda con mi Pack TIAM.";
    private static final String WHATSAPP_HELP_TEXT_CUADERNOS = "Hola, necesito ayuda con mis Cuadernos TIAM.";
    private static final String WHATSAPP_HELP_TEXT_MIXED = "Hola, necesito ayuda con mi compra en TIAM.";

    private final PackProperties properties;
    private final WhatsAppProperties whatsAppProperties;
    private final PackSalesReadiness readiness;

    /** What an email is about: only the Pack, only the Cuadernos, or (a "lost my link" for a buyer of both) both. */
    private enum Subject {
        PACK, CUADERNOS, MIXED;

        static Subject of(List<PackOrder> orders) {
            boolean cuadernos = orders.stream().anyMatch(order -> order.getEdition().isCuadernos());
            boolean pack = orders.stream().anyMatch(order -> !order.getEdition().isCuadernos());
            return cuadernos && pack ? MIXED : cuadernos ? CUADERNOS : PACK;
        }

        /** Completes "Recibiste este email porque compraste ...". */
        String boughtLabel() {
            return switch (this) {
                case PACK -> "el Pack TIAM";
                case CUADERNOS -> "los Cuadernos TIAM";
                case MIXED -> "en TIAM";
            };
        }

        /** What a buyer asked to get again, in "pediste el acceso a ... de nuevo". */
        String accessNoun() {
            return switch (this) {
                case PACK -> "tu Pack TIAM";
                case CUADERNOS -> "tus Cuadernos TIAM";
                case MIXED -> "tus compras de TIAM";
            };
        }

        String helpText() {
            return switch (this) {
                case PACK -> WHATSAPP_HELP_TEXT;
                case CUADERNOS -> WHATSAPP_HELP_TEXT_CUADERNOS;
                case MIXED -> WHATSAPP_HELP_TEXT_MIXED;
            };
        }
    }

    /** What every section of one email needs, resolved once. */
    private record Context(String name, String recoverLink, String helpUrl, String assetsBase, Subject subject) {
        String greeting() {
            return name.isEmpty() ? "Hola," : "Hola " + name + ",";
        }
    }

    private Context context(String buyerName, Subject subject) {
        return new Context(firstName(buyerName), recoverLink(), whatsAppUrl(subject.helpText()), assetsBase(), subject);
    }

    /** The WhatsApp link that opens the chat to activate the games month of THIS order's product. */
    private String activationUrl(PackOrder order) {
        return whatsAppUrl(order.getEdition().isCuadernos() ? WHATSAPP_GAMES_TEXT_CUADERNOS : WHATSAPP_GAMES_TEXT);
    }

    // ---- the first email ---------------------------------------------------------------------------

    /** The email sent right after the payment, with a stable idempotency key (one per order). */
    public EmailMessage compose(PackOrder order, Optional<String> gamesLink) {
        return compose(order, gamesLink, Optional.empty());
    }

    /**
     * Same, saying which Desafío month the order was given ({@code gamesMonth}). The Desafío is only mentioned when the
     * month was actually granted ({@link PackOrder#hasDesafioMonth()}).
     */
    public EmailMessage compose(PackOrder order, Optional<String> gamesLink, Optional<Integer> gamesMonth) {
        boolean cuadernos = order.getEdition().isCuadernos();
        Context ctx = context(order.getBuyerName(), cuadernos ? Subject.CUADERNOS : Subject.PACK);
        String link = downloadLink(order);
        Desafio desafio = desafioOf(order, gamesLink, gamesMonth);

        return new EmailMessage(
                order.getEmail().trim(),
                cuadernos ? SUBJECT_FIRST_CUADERNOS : SUBJECT_FIRST,
                renderHtml(order, link, ctx, desafio),
                renderText(order, link, ctx, desafio),
                "pack-access/" + order.getId());
    }

    private Desafio desafioOf(PackOrder order, Optional<String> gamesLink, Optional<Integer> gamesMonth) {
        return order.hasDesafioMonth() ? new Desafio(usableGamesLink(gamesLink), gamesMonth.orElse(null)) : null;
    }

    private String renderHtml(PackOrder order, String link, Context ctx, Desafio desafio) {
        PackEdition edition = order.getEdition();
        boolean cuadernos = edition.isCuadernos();
        String days = String.valueOf(properties.getAccessDays());

        String cta = PackEmailHtml.button(link, CTA_LABEL, ORANGE)
                + "<p style=\"margin:18px 0 0 0;font-size:13px;line-height:20px;color:#B5C5DB;\">¿No abre el botón? "
                + "Copiá este link en tu navegador:<br>"
                + "<a href=\"" + esc(link) + "\" style=\"color:#9CC3EE;text-decoration:underline;word-break:break-all;\">"
                + esc(link) + "</a></p>";
        String intro = "<strong style=\"color:#FFFFFF;\">" + esc(ctx.greeting()) + "</strong> gracias por tu compra. "
                + (cuadernos ? "Tus cuadernos ya están listos para descargar e imprimir."
                        : "Tus fichas ya están listas para descargar e imprimir.");

        StringBuilder rows = new StringBuilder();
        rows.append(PackEmailHtml.hero(
                cuadernos ? "CUADERNOS TIAM" : "PACK TIAM · EDICIÓN " + edition.displayName().toUpperCase(Locale.ROOT),
                cuadernos ? "¡Tus Cuadernos TIAM están listos!" : "¡Tu Pack TIAM está listo!", intro, cta));
        rows.append(PackEmailHtml.productImage(ctx.assetsBase() + "/" + imageFile(edition), productAlt(edition)));

        StringBuilder content = new StringBuilder();
        content.append(filesPanel(order, desafio));
        content.append(cuadernos ? cuadernosTips() : printingTips(edition));
        content.append(PackEmailHtml.ruleThen(
                PackEmailHtml.paragraph("<strong>Tu acceso a las descargas dura " + days + " días</strong> desde hoy. "
                        + "El link es personal: no lo compartas.", "")
                        + PackEmailHtml.muted(helpLine(ctx))));
        rows.append(PackEmailHtml.body(content.toString()));

        return PackEmailHtml.page(cuadernos ? "Tus Cuadernos TIAM" : "Tu Pack TIAM",
                (cuadernos ? "Tus cuadernos ya están listos para descargar e imprimir. Tu acceso dura "
                        : "Tus fichas ya están listas para descargar e imprimir. Tu acceso dura ") + days + " días.",
                ctx.assetsBase() + "/" + LOGO_FILE, LOGO_WIDTH, LOGO_HEIGHT, rows.toString(), ctx.subject().boughtLabel());
    }

    /** The product picture under the headline: one per Pack edition, one for the Cuadernos. */
    private static String imageFile(PackEdition edition) {
        return edition.isCuadernos() ? CUADERNOS_IMAGE_FILE : "pack-" + edition.slug() + ".jpg";
    }

    /** "Tus archivos": what was bought, one row each, with what it weighs, and the games activation step. */
    private String filesPanel(PackOrder order, Desafio desafio) {
        if (order.getEdition().isCuadernos()) {
            return cuadernosFilesPanel(order, desafio);
        }
        PackEdition edition = order.getEdition();
        StringBuilder rows = new StringBuilder();
        rows.append(PackEmailHtml.fileRow(BLUE, "Pack completo", "PDF · «Empezá acá» y los 12 cuadernillos",
                sizeMb(edition, "pack-completo-a4") + " MB", ""));
        rows.append(PackEmailHtml.fileRow(ORANGE, "Cuadernillos sueltos", "ZIP · los cuadernillos por separado, para imprimir de a uno",
                sizeMb(edition, "cuadernillos-a4") + " MB", ""));
        if (order.isWithExtra()) {
            rows.append(PackEmailHtml.fileRow(GREEN, "Cuadernillo extra", "PDF · 40 fichas más, 10 por área",
                    sizeMb(edition, "extra-a4") + " MB", ""));
        }
        if (desafio != null) {
            rows.append(PackEmailHtml.fileRow(PURPLE, "Tu mes de juegos",
                    "Desafío de 30 días · corre desde la compra · 7 ejercicios nuevos por semana", "",
                    gamesSteps(desafio.url(), activationUrl(order))));
        }
        rows.append(PackEmailHtml.panelNote("Cada archivo viene en <strong>A4 y en Carta</strong>: elegí el de tu impresora "
                + "(si dudás, A4). Podés imprimirlos todas las veces que quieras."));
        return PackEmailHtml.panel("Tus archivos", "Edición " + esc(edition.displayName()), rows.toString());
    }

    /**
     * "Tus archivos" of the Cuadernos: the complete collection, each of the four workbooks (names and sizes come from
     * {@link PackCatalog}) and, when the Desafío month was granted, that month ("Mes N del Desafío").
     */
    private String cuadernosFilesPanel(PackOrder order, Desafio desafio) {
        StringBuilder rows = new StringBuilder();
        for (PackFile file : cuadernosFilesOfOnePaper()) {
            boolean collection = file.code().startsWith("coleccion");
            rows.append(PackEmailHtml.fileRow(collection ? BLUE : ORANGE, collection ? "Colección completa" : esc(file.label()),
                    collection ? "PDF · los 4 cuadernos en 1 archivo, para imprimir lo que quieras" : "PDF · para imprimir de a uno",
                    file.sizeMb() + " MB", ""));
        }
        if (desafio != null) {
            rows.append(PackEmailHtml.fileRow(PURPLE, desafio.title(),
                    "Desafío de 30 días · corre desde la compra · 7 ejercicios nuevos por semana", "",
                    cuadernosDesafioSteps(desafio.url(), activationUrl(order))));
        }
        rows.append(PackEmailHtml.panelNote("Cada archivo viene en <strong>A4 y en Carta</strong>: elegí el de tu impresora "
                + "(si dudás, A4). Podés imprimirlos todas las veces que quieras."));
        return PackEmailHtml.panel("Tus archivos", esc("Cuadernos TIAM"), rows.toString());
    }

    /** The Cuadernos catalog for one paper (the other paper has the same files and sizes). */
    private static List<PackFile> cuadernosFilesOfOnePaper() {
        return PackCatalog.filesFor(PackEdition.CUADERNOS, false).stream()
                .filter(file -> file.code().endsWith("-a4")).toList();
    }

    /**
     * The Pack's games month. It runs 30 days from the PURCHASE (the Desafío's clock does not wait for a WhatsApp
     * message): the buyer can start right away from the personal link, or by writing on WhatsApp when there is none.
     */
    private static String gamesSteps(String gamesUrl, String activationUrl) {
        StringBuilder html = new StringBuilder("<div style=\"padding-top:10px;\">");
        if (gamesUrl != null) {
            html.append(PackEmailHtml.muted("Tu mes de juegos ya está activo y corre 30 días desde la compra. "
                    + "Podés empezar ahora mismo desde tu link personal."));
            html.append(PackEmailHtml.smallButton(gamesUrl, "Abrir mi mes de juegos", BLUE));
            html.append(PackEmailHtml.muted("Si preferís recibir los ejercicios por WhatsApp, escribinos desde el mismo número "
                    + "que dejaste al comprar."));
        } else {
            html.append(PackEmailHtml.muted("Tu mes de juegos ya está incluido y corre 30 días desde la compra. "
                    + "Para empezar, escribinos por WhatsApp desde el mismo número que dejaste al comprar."));
        }
        if (activationUrl != null) {
            html.append(PackEmailHtml.smallButton(activationUrl, "Escribir por WhatsApp", GREEN));
        }
        return html.append("</div>").toString();
    }

    /**
     * The Cuadernos' Desafío month. It runs 30 days from the PURCHASE (the Desafío's clock does not wait for a WhatsApp
     * message), so the buyer can start right away from the personal link, or by WhatsApp when there is none. It is ONE
     * month: the following ones are optional, paid apart at the normal price, and nothing renews by itself.
     */
    private static String cuadernosDesafioSteps(String desafioUrl, String activationUrl) {
        StringBuilder html = new StringBuilder("<div style=\"padding-top:10px;\">");
        if (desafioUrl != null) {
            html.append(PackEmailHtml.muted("Tu mes del Desafío ya está activo y corre 30 días desde la compra. "
                    + "Podés empezar ahora mismo desde tu link personal."));
            html.append(PackEmailHtml.smallButton(desafioUrl, "Abrir mi mes del Desafío", BLUE));
            html.append(PackEmailHtml.muted("Si preferís recibir los ejercicios por WhatsApp, escribinos desde el mismo número "
                    + "que dejaste al comprar."));
        } else {
            html.append(PackEmailHtml.muted("Tu mes del Desafío ya está incluido y corre 30 días desde la compra. "
                    + "Para empezar, escribinos por WhatsApp desde el mismo número que dejaste al comprar."));
        }
        if (activationUrl != null) {
            html.append(PackEmailHtml.smallButton(activationUrl, "Escribir por WhatsApp", GREEN));
        }
        html.append(PackEmailHtml.muted("Tu compra incluye <strong>1 mes</strong> del Desafío (30 días desde la compra). "
                + "Los meses siguientes son opcionales: se pagan aparte, al precio normal, desde la página del Desafío. "
                + "No se renueva solo."));
        return html.append("</div>").toString();
    }

    /** Printing tips of the Cuadernos: choose a level, print one at a time, go at your own pace. */
    private static String cuadernosTips() {
        return PackEmailHtml.sectionTitle("Para imprimir")
                + PackEmailHtml.tip(1, "Empezá por tu nivel.", "Los cuadernos van del nivel inicial al avanzado: elegí con cuál "
                        + "empezar y seguí a tu ritmo.")
                + PackEmailHtml.tip(2, "Imprimí de a uno.", "Los PDF sueltos pesan menos y te dejan imprimir un cuaderno por vez; "
                        + "la colección completa trae los cuatro juntos.")
                + PackEmailHtml.tip(3, "A tu ritmo.", "Los ejercicios se hacen sin ayuda: podés dejar un cuaderno y retomarlo "
                        + "cuando quieras.")
                + "<div style=\"height:14px;line-height:14px;font-size:0;\">&nbsp;</div>";
    }

    private static String printingTips(PackEdition edition) {
        String answers = edition == PackEdition.PROFESIONALES ? "el solucionario" : "las respuestas";
        return PackEmailHtml.sectionTitle("Para imprimir")
                + PackEmailHtml.tip(1, "Empezá acá.", "Abrí «Empezá acá», las primeras páginas del Pack completo: qué trae, "
                        + "por dónde empezar y cómo imprimir.")
                + PackEmailHtml.tip(2, "A una sola cara.", "La guía trae " + answers + " y no tiene que quedar en el dorso de la hoja de trabajo.")
                + PackEmailHtml.tip(3, "Con tinta negra alcanza.", "Las fichas están pensadas para verse bien en blanco y negro.")
                + "<div style=\"height:14px;line-height:14px;font-size:0;\">&nbsp;</div>";
    }

    private static String helpLine(Context ctx) {
        String help = ctx.helpUrl() == null
                ? "¿Necesitás ayuda? Escribinos desde " + PackEmailHtml.link(PackEmailHtml.SITE_URL, "tiamdigital.com") + "."
                : "¿Necesitás ayuda? " + PackEmailHtml.link(esc(ctx.helpUrl()), "Escribinos por WhatsApp") + ".";
        return "¿Lo perdiste? " + PackEmailHtml.link(esc(ctx.recoverLink()), "Pedí un link nuevo") + ". " + help;
    }

    private static String productAlt(PackEdition edition) {
        if (edition.isCuadernos()) {
            return "Cuadernos TIAM: 4 cuadernos con 120 ejercicios de estimulación cognitiva para hacer solo/a";
        }
        return edition == PackEdition.PROFESIONALES
                ? "Pack Básico de TIAM, edición para profesionales: 12 cuadernillos y 120 fichas para imprimir"
                : "Pack Básico de TIAM, edición para familias: 120 fichas para imprimir, con una guía para acompañar";
    }

    // ---- the first email, plain text -----------------------------------------------------------------

    private String renderText(PackOrder order, String link, Context ctx, Desafio desafio) {
        if (order.getEdition().isCuadernos()) {
            return renderCuadernosText(order, link, ctx, desafio);
        }
        PackEdition edition = order.getEdition();
        StringBuilder text = new StringBuilder();
        text.append(ctx.greeting()).append("\n\n");
        text.append("¡Gracias por tu compra! Tu Pack TIAM (Edición ").append(edition.displayName())
                .append(") ya está listo para descargar.\n");
        text.append("\nDESCARGAR MIS ARCHIVOS\n").append(link).append("\n\n");

        text.append("TUS ARCHIVOS (cada uno en A4 y en Carta; si dudás, A4)\n");
        text.append("- Pack completo (PDF, ").append(sizeMb(edition, "pack-completo-a4"))
                .append(" MB): «Empezá acá» y los 12 cuadernillos.\n");
        text.append("- Cuadernillos sueltos (ZIP, ").append(sizeMb(edition, "cuadernillos-a4"))
                .append(" MB): para imprimir de a uno.\n");
        if (order.isWithExtra()) {
            text.append("- Cuadernillo extra (PDF, ").append(sizeMb(edition, "extra-a4")).append(" MB): 40 fichas más.\n");
        }
        text.append("\n");

        if (desafio != null) {
            appendGamesText(text, desafio.url(), activationUrl(order));
        }

        text.append("PARA IMPRIMIR\n");
        text.append("1. Empezá acá: abrí «Empezá acá», las primeras páginas del Pack completo.\n");
        text.append("2. A una sola cara: la guía trae ")
                .append(edition == PackEdition.PROFESIONALES ? "el solucionario" : "las respuestas")
                .append(" y no tiene que quedar en el dorso de la hoja de trabajo.\n");
        text.append("3. Con tinta negra alcanza: las fichas se ven bien en blanco y negro.\n\n");

        text.append("TU ACCESO\n");
        text.append("- Tu acceso a las descargas dura ").append(properties.getAccessDays()).append(" días desde hoy.\n");
        text.append("- Este link es personal: no lo compartas. Si lo perdés, pedí uno nuevo acá: ").append(ctx.recoverLink()).append("\n");
        text.append(ctx.helpUrl() == null
                ? "- ¿Necesitás ayuda? Escribinos desde tiamdigital.com.\n"
                : "- ¿Necesitás ayuda? Escribinos por WhatsApp: " + ctx.helpUrl() + "\n");
        text.append(footerText());
        return text.toString();
    }

    private static void appendGamesText(StringBuilder text, String gamesUrl, String activationUrl) {
        text.append("MES DE JUEGOS\n");
        if (gamesUrl != null) {
            text.append("Tu mes de juegos del Desafío 30 días está activo y corre 30 días desde la compra. "
                    + "Podés empezar ahora mismo desde tu link personal:\n").append(gamesUrl).append("\n");
            text.append("Si preferís recibir los ejercicios por WhatsApp, escribinos desde el mismo número que dejaste al comprar.\n");
        } else {
            text.append("Tu mes de juegos del Desafío 30 días está incluido y corre 30 días desde la compra. "
                    + "Para empezar, escribinos por WhatsApp desde el mismo número que dejaste al comprar.\n");
        }
        if (activationUrl != null) {
            text.append("WhatsApp: ").append(activationUrl).append("\n");
        }
        text.append("\n");
    }

    /**
     * What the Cuadernos' Desafío month is, in plain text: 30 days from the purchase (start right away from the link, or
     * by WhatsApp), ONE month, the next ones optional and paid apart at the normal price, no automatic renewal.
     */
    private static void appendCuadernosDesafioText(StringBuilder text, Desafio desafio, String activationUrl) {
        text.append("TU MES DEL DESAFÍO\n");
        text.append(desafio.title()).append(": corre 30 días desde la compra.\n");
        if (desafio.url() != null) {
            text.append("Podés empezar ahora mismo desde tu link personal:\n").append(desafio.url()).append("\n");
            text.append("Si preferís recibir los ejercicios por WhatsApp, escribinos desde el mismo número que dejaste al comprar.\n");
        } else {
            text.append("Para empezar, escribinos por WhatsApp desde el mismo número que dejaste al comprar.\n");
        }
        if (activationUrl != null) {
            text.append("WhatsApp: ").append(activationUrl).append("\n");
        }
        text.append(CUADERNOS_DESAFIO_TERMS).append("\n\n");
    }

    /** What the Cuadernos offer is and is not: ONE month, the next ones optional and paid apart, no renewal. */
    private static final String CUADERNOS_DESAFIO_TERMS = "Tu compra incluye 1 mes del Desafío (30 días desde la compra). "
            + "Los meses siguientes son opcionales: se pagan aparte, al precio normal, desde la página del Desafío. "
            + "No se renueva solo.";

    /** The first email of a Cuadernos order, as plain text: the same facts as the HTML, from the same catalog. */
    private String renderCuadernosText(PackOrder order, String link, Context ctx, Desafio desafio) {
        StringBuilder text = new StringBuilder();
        text.append(ctx.greeting()).append("\n\n");
        text.append("¡Gracias por tu compra! Tus Cuadernos TIAM ya están listos para descargar.\n");
        text.append("\nDESCARGAR MIS ARCHIVOS\n").append(link).append("\n\n");

        text.append("TUS ARCHIVOS (cada uno en A4 y en Carta; si dudás, A4)\n");
        for (PackFile file : cuadernosFilesOfOnePaper()) {
            boolean collection = file.code().startsWith("coleccion");
            text.append("- ").append(collection ? "Colección completa" : file.label())
                    .append(" (PDF, ").append(file.sizeMb()).append(" MB)");
            text.append(collection ? ": los 4 cuadernos en 1 archivo, para imprimir lo que quieras.\n" : ".\n");
        }
        text.append("\n");

        if (desafio != null) {
            appendCuadernosDesafioText(text, desafio, activationUrl(order));
        }

        text.append("PARA IMPRIMIR\n");
        text.append("1. Empezá por tu nivel: los cuadernos van del nivel inicial al avanzado; elegí con cuál empezar.\n");
        text.append("2. Imprimí de a uno: los PDF sueltos pesan menos y te dejan imprimir un cuaderno por vez.\n");
        text.append("3. A tu ritmo: los ejercicios se hacen sin ayuda; podés retomar un cuaderno cuando quieras.\n\n");

        text.append("TU ACCESO\n");
        text.append("- Tu acceso a las descargas dura ").append(properties.getAccessDays()).append(" días desde hoy.\n");
        text.append("- Este link es personal: no lo compartas. Si lo perdés, pedí uno nuevo acá: ").append(ctx.recoverLink()).append("\n");
        text.append(ctx.helpUrl() == null
                ? "- ¿Necesitás ayuda? Escribinos desde tiamdigital.com.\n"
                : "- ¿Necesitás ayuda? Escribinos por WhatsApp: " + ctx.helpUrl() + "\n");
        text.append(footerText());
        return text.toString();
    }

    private static String footerText() {
        return "\nTIAM · tiamdigital.com\n"
                + "Material de estimulación cognitiva. No reemplaza la evaluación ni el tratamiento de un profesional de la salud.\n";
    }

    // ---- the "I lost my link" email ----------------------------------------------------------------------

    /**
     * The "I lost my link" email: ONE message with one section per paid order of that address (a buyer of both
     * editions gets both). Each link carries a replacement token; the links already in the buyer's hands keep
     * working until they open one of these, and the email says so, which also tells someone who did not ask for
     * it that they can simply ignore it.
     *
     * @param idempotencyKey supplied by the caller (it must change between requests or the provider drops them)
     */
    public EmailMessage composeResend(String to, List<ResendEntry> entries, String idempotencyKey) {
        Subject subject = Subject.of(entries.stream().map(ResendEntry::order).toList());
        Context ctx = context(entries.get(0).order().getBuyerName(), subject);
        String emailSubject = switch (subject) {
            case PACK -> SUBJECT_RESEND;
            case CUADERNOS -> SUBJECT_RESEND_CUADERNOS;
            case MIXED -> SUBJECT_RESEND_MIXED;
        };
        return new EmailMessage(to, emailSubject, renderResendHtml(entries, ctx), renderResendText(entries, ctx), idempotencyKey);
    }

    private String renderResendHtml(List<ResendEntry> entries, Context ctx) {
        boolean single = entries.size() == 1;
        String days = String.valueOf(properties.getAccessDays());
        Subject subject = ctx.subject();

        String intro = "<strong style=\"color:#FFFFFF;\">" + esc(ctx.greeting()) + "</strong> pediste el acceso a "
                + subject.accessNoun() + " de nuevo. "
                + "Acá tenés " + (single ? "tu link" : "tus links") + ".";
        String headline = switch (subject) {
            case PACK -> "Tu acceso al Pack TIAM";
            case CUADERNOS -> "Tu acceso a los Cuadernos TIAM";
            case MIXED -> "Tu acceso a tus compras de TIAM";
        };
        StringBuilder rows = new StringBuilder();
        rows.append(PackEmailHtml.hero(switch (subject) {
            case PACK -> "PACK TIAM";
            case CUADERNOS -> "CUADERNOS TIAM";
            case MIXED -> "TIAM";
        }, headline, intro, ""));
        if (single) {
            PackEdition edition = entries.get(0).order().getEdition();
            rows.append(PackEmailHtml.productImage(ctx.assetsBase() + "/" + imageFile(edition), productAlt(edition)));
        }

        StringBuilder content = new StringBuilder();
        for (ResendEntry entry : entries) {
            PackOrder order = entry.order();
            String link = downloadBase() + "/" + entry.token();
            String label = single ? CTA_LABEL
                    : order.getEdition().isCuadernos() ? "Descargar Cuadernos TIAM"
                    : "Descargar Edición " + esc(order.getEdition().displayName());
            content.append(PackEmailHtml.button(link, label, ORANGE));
            content.append("<p class=\"tx-muted\" style=\"margin:14px 0 26px 0;font-size:14px;line-height:21px;color:#4F5F76;\">"
                    + "¿No abre el botón? Copiá este link en tu navegador:<br>"
                    + PackEmailHtml.breakingLink(esc(link), esc(link)) + "</p>");
            content.append(filesPanel(order, desafioOf(order, entry.gamesLink(), entry.gamesMonth())));
        }
        content.append(PackEmailHtml.sectionTitle("Importante"));
        content.append(PackEmailHtml.tip(1, "Tu link anterior sigue funcionando",
                "hasta que abras uno de estos; en ese momento deja de funcionar."));
        content.append(PackEmailHtml.tip(2, "Si vos no pediste este mensaje,", "no hagas nada: tu acceso actual no se modificó."));
        content.append(PackEmailHtml.tip(3, "Tu acceso dura " + days + " días", "desde que abras el link nuevo."));
        content.append(PackEmailHtml.ruleThen(PackEmailHtml.muted("Estos links son personales: no los compartas. "
                + "Si necesitás otro, " + PackEmailHtml.link(esc(ctx.recoverLink()), "pedilo acá") + ". "
                + (ctx.helpUrl() == null ? "" : PackEmailHtml.link(esc(ctx.helpUrl()), "¿Necesitás ayuda? Escribinos por WhatsApp") + "."))));
        rows.append(PackEmailHtml.body(content.toString()));

        String preheader = switch (subject) {
            case PACK -> "Tu link nuevo para descargar tus archivos del Pack TIAM.";
            case CUADERNOS -> "Tu link nuevo para descargar tus archivos de los Cuadernos TIAM.";
            case MIXED -> "Tus links nuevos para descargar tus archivos de TIAM.";
        };
        return PackEmailHtml.page(headline, preheader, ctx.assetsBase() + "/" + LOGO_FILE, LOGO_WIDTH, LOGO_HEIGHT,
                rows.toString(), subject.boughtLabel());
    }

    private String renderResendText(List<ResendEntry> entries, Context ctx) {
        StringBuilder text = new StringBuilder();
        text.append(ctx.greeting()).append("\n\n");
        text.append("Pediste el acceso a ").append(ctx.subject().accessNoun()).append(" de nuevo. Acá tenés ")
                .append(entries.size() == 1 ? "tu link" : "tus links").append(":\n\n");
        for (ResendEntry entry : entries) {
            PackOrder order = entry.order();
            PackEdition edition = order.getEdition();
            if (edition.isCuadernos()) {
                appendCuadernosResendEntry(text, entry);
                continue;
            }
            text.append("Edición ").append(edition.displayName()).append(":\n")
                    .append(downloadBase()).append("/").append(entry.token()).append("\n");
            text.append("Incluye el pack completo (").append(sizeMb(edition, "pack-completo-a4")).append(" MB) y los cuadernillos sueltos (")
                    .append(sizeMb(edition, "cuadernillos-a4")).append(" MB), en A4 y en Carta");
            if (order.isWithExtra()) {
                text.append(", y el Cuadernillo extra (").append(sizeMb(edition, "extra-a4")).append(" MB)");
            }
            text.append(".\n");
            if (order.hasDesafioMonth()) {
                String gamesUrl = usableGamesLink(entry.gamesLink());
                text.append("Tu mes de juegos del Desafío 30 días corre 30 días desde la compra.\n");
                text.append(gamesUrl != null
                        ? "Podés empezar ahora mismo desde tu link personal: " + gamesUrl + "\n"
                        : "Para empezar, escribinos por WhatsApp desde el mismo número que dejaste al comprar.\n");
                String activation = activationUrl(order);
                if (activation != null) {
                    text.append("WhatsApp: ").append(activation).append("\n");
                }
            }
            text.append("\n");
        }
        text.append("IMPORTANTE\n");
        text.append("- Tu link anterior sigue funcionando hasta que abras uno de estos; en ese momento deja de funcionar.\n");
        text.append("- Si vos no pediste este mensaje, no hagas nada: tu acceso actual no se modificó.\n");
        text.append("- Tu acceso dura ").append(properties.getAccessDays()).append(" días desde que abras el link nuevo.\n");
        text.append("- Estos links son personales: no los compartas. Para pedir otro: ").append(ctx.recoverLink()).append("\n");
        if (ctx.helpUrl() != null) {
            text.append("- ¿Necesitás ayuda? Escribinos por WhatsApp: ").append(ctx.helpUrl()).append("\n");
        }
        text.append(footerText());
        return text.toString();
    }

    /** One Cuadernos order inside a "lost my link" text: its link, what it includes and, if bought, the Desafío month. */
    private void appendCuadernosResendEntry(StringBuilder text, ResendEntry entry) {
        PackOrder order = entry.order();
        text.append("Cuadernos TIAM:\n").append(downloadBase()).append("/").append(entry.token()).append("\n");
        text.append("Incluye la colección completa (")
                .append(sizeMb(PackEdition.CUADERNOS, "coleccion-completa-a4")).append(" MB) y los 4 cuadernos sueltos (")
                .append(sizeMb(PackEdition.CUADERNOS, "cuaderno-1-a4")).append(" MB cada uno), en A4 y en Carta.\n");
        Desafio desafio = desafioOf(order, entry.gamesLink(), entry.gamesMonth());
        if (desafio != null) {
            text.append(desafio.title()).append(": corre 30 días desde la compra.\n");
            text.append(desafio.url() != null
                    ? "Podés empezar ahora mismo desde tu link personal: " + desafio.url() + "\n"
                    : "Para empezar, escribinos por WhatsApp desde el mismo número que dejaste al comprar.\n");
            String activation = activationUrl(order);
            if (activation != null) {
                text.append("WhatsApp: ").append(activation).append("\n");
            }
            text.append(CUADERNOS_DESAFIO_TERMS).append("\n");
        }
        text.append("\n");
    }

    // ---- links and assets ------------------------------------------------------------------------------

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

    /**
     * Where the email images are served from: the configured folder when it is public https (or, in dev mode, this
     * machine, so a local test can show them), otherwise the production default. An image URL that a buyer's
     * phone cannot open would turn the email into a page of empty boxes.
     */
    String assetsBase() {
        String configured = stripTrailingSlashes(properties.getEmailAssetsBaseUrl());
        boolean usable = PackUrls.isPublicHttps(configured) || isDevLocalLink(configured);
        return usable ? configured : DEFAULT_ASSETS_BASE_URL;
    }

    /** Whether the downloads base is something a buyer can open: https with a real host. */
    public boolean hasPublicDownloadBase() {
        return isPublicHttps(downloadBase());
    }

    /**
     * Whether the downloads base can go into an email: a public https address or, ONLY with the dev mode active
     * ({@code PACK_DEV_MODE=true}, which the prod profile ignores), the developer's own machine, so a local test
     * can open the link of its own email. In production this is exactly {@link #hasPublicDownloadBase()}.
     */
    public boolean hasUsableDownloadBase() {
        return hasPublicDownloadBase() || isDevLocalLink(downloadBase());
    }

    /** True only in a local test: the dev mode is on (and not in prod) and the link points at this machine. */
    private boolean isDevLocalLink(String url) {
        return readiness.devModeActive() && PackUrls.isLoopbackHttp(url);
    }

    /** Only a public https Desafío link goes into an email (a local one too in dev mode); anything else cannot work. */
    private String usableGamesLink(Optional<String> gamesLink) {
        return gamesLink.filter(link -> isPublicHttps(link) || isDevLocalLink(link)).orElse(null);
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

    private String whatsAppUrl(String prefilledText) {
        String number = whatsAppProperties.getBusinessNumber();
        if (!StringUtils.hasText(number)) {
            return null;
        }
        String encoded = URLEncoder.encode(prefilledText, StandardCharsets.UTF_8).replace("+", "%20");
        return "https://wa.me/" + number.trim() + "?text=" + encoded;
    }

    /** What the file weighs, taken from the catalog (the same number the downloads page shows). */
    private static int sizeMb(PackEdition edition, String code) {
        return PackCatalog.find(code, edition, true).map(PackFile::sizeMb).orElse(0);
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
}
