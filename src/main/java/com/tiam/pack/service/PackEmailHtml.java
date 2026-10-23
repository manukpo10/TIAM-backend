package com.tiam.pack.service;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.util.HtmlUtils;

/**
 * The look of the pack emails, as small pure functions that return HTML: a 600 px table layout with every style
 * inline (no external CSS or JS), a hidden preheader, a bulletproof button, and the colours and type of the landings
 * (navy header band, area colours, the orange of the buy button).
 *
 * <p>Legibility rules this kit keeps, because email clients are hostile:
 * <ul>
 *   <li><b>Images blocked:</b> every image has alt text, the headline, the button and every file name are live
 *       text on a solid background colour, and the product picture is decoration under them.</li>
 *   <li><b>Dark mode:</b> the inline colours are the light theme; a {@code prefers-color-scheme: dark} block
 *       re-colours the white areas (the navy bands already work on dark). The logo is flattened on white, so the
 *       white strip that holds it stays white.</li>
 *   <li><b>Mobile:</b> the container is fluid up to 600 px and a media query narrows the padding and makes the
 *       button full width. Clients that drop {@code <style>} (some Gmail setups) still get the fluid desktop look.</li>
 * </ul>
 *
 * <p>Every method takes already-escaped HTML for content and never reads a template placeholder out of a value:
 * callers escape user data with {@link #esc} before it gets here.
 */
final class PackEmailHtml {

    // The landings' palette (pack/landing.css): navy band, area colours, and the orange buy button.
    static final String NAVY = "#16263F";
    static final String BLUE = "#1B6FC4";
    static final String ORANGE = "#CC4610"; // 5:1 with white text, the landing's --color-cta
    static final String GREEN = "#2C801C";
    static final String PURPLE = "#5B3C9E";
    static final String LINK = "#15436F";

    /** Behind the product picture: it shows, with dark alt text on it, while the picture is blocked or loading. */
    private static final String PICTURE_BG = "#FBEBD3";
    private static final String SOFT_BG = "#F2F7FD";
    private static final String SOFT_BORDER = "#D5E3F3";
    private static final String MUTED = "#4F5F76";
    static final String FONT = "'Segoe UI',Roboto,Helvetica,Arial,sans-serif";

    static final String SITE_URL = "https://www.tiamdigital.com";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

    private PackEmailHtml() {
    }

    /** Escapes user-controlled text. UTF-8 mode: only the HTML specials, accents stay readable in the source. */
    static String esc(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value, "UTF-8");
    }

    // ---- the page ------------------------------------------------------------------------------------

    /**
     * The whole document: head, hidden preheader, a white strip with the logo over a four-colour stripe (the four
     * areas), then {@code rows} (each a {@code <tr>} built by this class), then the footer.
     */
    static String page(String title, String preheader, String logoSrc, int logoWidth, int logoHeight, String rows) {
        return page(title, preheader, logoSrc, logoWidth, logoHeight, rows, "el Pack TIAM");
    }

    /**
     * Same, with the closing line of the footer: "Recibiste este email porque compraste {@code bought}."
     * ({@code "el Pack TIAM"}, {@code "los Cuadernos TIAM"}, {@code "en TIAM"}).
     */
    static String page(String title, String preheader, String logoSrc, int logoWidth, int logoHeight, String rows,
            String bought) {
        Map<String, String> values = Map.of(
                "TITLE", esc(title),
                "PREHEADER", esc(preheader),
                "FILLER", "&zwnj;&nbsp;".repeat(70),
                "LOGO", esc(logoSrc),
                "LOGO_W", String.valueOf(logoWidth),
                "LOGO_H", String.valueOf(logoHeight),
                "SITE", SITE_URL,
                "BOUGHT", esc(bought),
                "ROWS", rows);
        return fill(PAGE, values);
    }

    /** Navy band: small label, headline, a short paragraph, and whatever {@code extra} adds (the button). */
    static String hero(String eyebrow, String title, String introHtml, String extraHtml) {
        return "<tr><td class=\"pad\" bgcolor=\"" + NAVY + "\" style=\"background-color:" + NAVY
                + ";padding:34px 36px 38px;font-family:" + FONT + ";\">"
                + "<p style=\"margin:0 0 10px 0;font-size:12px;line-height:16px;font-weight:700;letter-spacing:1.5px;color:#9CC3EE;\">"
                + esc(eyebrow) + "</p>"
                + "<h1 class=\"h1\" style=\"margin:0 0 14px 0;font-size:30px;line-height:37px;font-weight:800;color:#FFFFFF;\">"
                + esc(title) + "</h1>"
                + "<p style=\"margin:0 0 " + (extraHtml.isEmpty() ? "0" : "26px") + ";font-size:17px;line-height:26px;color:#DCE6F4;\">"
                + introHtml + "</p>"
                + extraHtml
                + "</td></tr>\n";
    }

    /**
     * A thin light bar above the headline for one short sentence the reader should see first (it re-colours in dark
     * mode like the panels). {@code textHtml} must already be escaped.
     */
    static String notice(String textHtml) {
        return "<tr><td class=\"pad bg-soft bd-soft\" bgcolor=\"" + SOFT_BG + "\" style=\"background-color:" + SOFT_BG
                + ";border-bottom:1px solid " + SOFT_BORDER + ";padding:12px 36px;font-family:" + FONT + ";\">"
                + "<p class=\"tx\" style=\"margin:0;font-size:14px;line-height:21px;color:" + NAVY + ";\">" + textHtml
                + "</p></td></tr>\n";
    }

    /** Full-width product picture. The width attribute is for Outlook; the style makes it fluid everywhere else. */
    static String productImage(String src, String alt) {
        return "<tr><td bgcolor=\"" + PICTURE_BG + "\" style=\"background-color:" + PICTURE_BG + ";font-size:0;line-height:0;\">"
                + "<img src=\"" + esc(src) + "\" width=\"600\" alt=\"" + esc(alt) + "\" style=\"display:block;width:100%;"
                + "max-width:600px;height:auto;border:0;outline:none;text-decoration:none;font-family:" + FONT
                + ";font-size:16px;line-height:22px;color:" + NAVY + ";\"></td></tr>\n";
    }

    /** The white content area: {@code inner} is a run of blocks built by the methods below. */
    static String body(String inner) {
        return "<tr><td class=\"pad bg-card tx\" bgcolor=\"#FFFFFF\" style=\"background-color:#FFFFFF;padding:34px 36px 10px;"
                + "font-family:" + FONT + ";font-size:16px;line-height:24px;color:" + NAVY + ";\">"
                + inner + "</td></tr>\n";
    }

    // ---- blocks ----------------------------------------------------------------------------------------

    static String h2(String text) {
        return "<h2 class=\"tx-h\" style=\"margin:0 0 6px 0;font-size:21px;line-height:28px;font-weight:800;color:" + NAVY
                + ";\">" + text + "</h2>";
    }

    /** A title that opens a stand-alone section (more air below than the one inside a panel). */
    static String sectionTitle(String text) {
        return "<h2 class=\"tx-h\" style=\"margin:0 0 16px 0;font-size:21px;line-height:28px;font-weight:800;color:" + NAVY
                + ";\">" + text + "</h2>";
    }

    static String paragraph(String html, String extraStyle) {
        return "<p style=\"margin:0 0 14px 0;" + extraStyle + "\">" + html + "</p>";
    }

    static String muted(String html) {
        return "<p class=\"tx-muted\" style=\"margin:0 0 14px 0;font-size:14px;line-height:21px;color:" + MUTED + ";\">"
                + html + "</p>";
    }

    /** A link that is readable in light and dark. {@code href} must already be escaped. */
    static String link(String hrefEscaped, String textHtml) {
        return "<a class=\"lnk\" href=\"" + hrefEscaped + "\" style=\"color:" + LINK + ";text-decoration:underline;\">" + textHtml + "</a>";
    }

    /** Like {@link #link}, for a long URL shown as text: it may wrap anywhere instead of pushing the page sideways. */
    static String breakingLink(String hrefEscaped, String textHtml) {
        return "<a class=\"lnk\" href=\"" + hrefEscaped + "\" style=\"color:" + LINK
                + ";text-decoration:underline;word-break:break-all;\">" + textHtml + "</a>";
    }

    /**
     * Bulletproof button: a table cell that carries the colour (so it is a button even when the anchor's padding is
     * ignored, as in desktop Outlook) with the anchor inside. {@code href} is escaped here.
     */
    static String button(String href, String label, String background) {
        return "<table role=\"presentation\" class=\"btn\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" "
                + "style=\"border-collapse:separate;\"><tr>"
                + "<td align=\"center\" bgcolor=\"" + background + "\" style=\"background-color:" + background
                + ";border-radius:10px;mso-padding-alt:16px 34px;\">"
                + "<a href=\"" + esc(href) + "\" target=\"_blank\" style=\"display:inline-block;padding:16px 34px;"
                + "font-family:" + FONT + ";font-size:18px;line-height:24px;font-weight:700;color:#FFFFFF;"
                + "text-decoration:none;border-radius:10px;\">" + label + "</a></td></tr></table>";
    }

    /** A smaller button for secondary actions (same construction as {@link #button}). */
    static String smallButton(String href, String label, String background) {
        return "<table role=\"presentation\" class=\"btn\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" "
                + "style=\"border-collapse:separate;margin:2px 0 12px 0;\"><tr>"
                + "<td align=\"center\" bgcolor=\"" + background + "\" style=\"background-color:" + background
                + ";border-radius:8px;mso-padding-alt:11px 22px;\">"
                + "<a href=\"" + esc(href) + "\" target=\"_blank\" style=\"display:inline-block;padding:11px 22px;"
                + "font-family:" + FONT + ";font-size:16px;line-height:22px;font-weight:700;color:#FFFFFF;"
                + "text-decoration:none;border-radius:8px;\">" + label + "</a></td></tr></table>";
    }

    /** The light panel with a title; {@code rows} are {@link #fileRow} / {@link #panelNote} cells. */
    static String panel(String title, String subtitle, String rows) {
        return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" "
                + "class=\"bg-soft bd-soft\" bgcolor=\"" + SOFT_BG + "\" style=\"background-color:" + SOFT_BG + ";border:1px solid "
                + SOFT_BORDER + ";border-radius:14px;border-collapse:separate;margin:0 0 28px 0;\">"
                + "<tr><td style=\"padding:22px 22px 8px 22px;\">" + h2(title)
                + "<p class=\"tx-muted\" style=\"margin:0;font-size:14px;line-height:20px;color:" + MUTED + ";\">" + subtitle + "</p></td></tr>"
                + rows + "</table>";
    }

    /** One file: a coloured bar (an area colour), the name, a detail line and the weight. */
    static String fileRow(String color, String nameHtml, String detailHtml, String size, String extraHtml) {
        return "<tr><td style=\"padding:12px 22px 4px 22px;\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>"
                + "<td class=\"tx\" valign=\"top\" style=\"border-left:5px solid " + color + ";padding:1px 0 1px 14px;\">"
                + "<span class=\"tx-h\" style=\"font-size:17px;line-height:24px;font-weight:700;color:" + NAVY + ";\">" + nameHtml + "</span><br>"
                + "<span class=\"tx-muted\" style=\"font-size:14px;line-height:20px;color:" + MUTED + ";\">" + detailHtml + "</span>"
                + extraHtml + "</td>"
                + "<td class=\"tx-h\" align=\"right\" valign=\"top\" style=\"padding:1px 0 0 12px;white-space:nowrap;font-size:15px;"
                + "line-height:24px;font-weight:700;color:" + NAVY + ";\">" + size + "</td>"
                + "</tr></table></td></tr>";
    }

    /**
     * A compact receipt: the light panel with one "label ... value" line per fact. Labels and values must already be
     * escaped; the caller leaves out any fact it does not have.
     */
    static String summaryPanel(String title, List<Map.Entry<String, String>> facts) {
        StringBuilder rows = new StringBuilder();
        for (Map.Entry<String, String> fact : facts) {
            rows.append("<tr><td style=\"padding:3px 22px;\">"
                    + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>"
                    + "<td class=\"tx-muted\" valign=\"top\" style=\"white-space:nowrap;font-size:14px;line-height:21px;color:"
                    + MUTED + ";\">" + fact.getKey() + "</td>"
                    + "<td class=\"tx-h\" align=\"right\" valign=\"top\" style=\"padding-left:14px;font-size:14px;line-height:21px;"
                    + "font-weight:700;color:" + NAVY + ";\">" + fact.getValue() + "</td>"
                    + "</tr></table></td></tr>");
        }
        rows.append("<tr><td style=\"height:16px;font-size:0;line-height:0;\">&nbsp;</td></tr>");
        return panel(title, "", rows.toString());
    }

    static String panelNote(String html) {
        return "<tr><td style=\"padding:14px 22px 22px 22px;\"><p class=\"tx-muted\" style=\"margin:0;font-size:14px;line-height:21px;color:"
                + MUTED + ";\">" + html + "</p></td></tr>";
    }

    /** A numbered tip: a round badge and a bold lead-in followed by one sentence. */
    static String tip(int number, String leadHtml, String textHtml) {
        return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin:0 0 14px 0;\"><tr>"
                + "<td width=\"40\" valign=\"top\" style=\"width:40px;padding-top:1px;\">"
                + "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>"
                + "<td align=\"center\" valign=\"middle\" width=\"28\" height=\"28\" bgcolor=\"" + BLUE + "\" style=\"width:28px;height:28px;"
                + "background-color:" + BLUE + ";border-radius:14px;font-size:15px;line-height:28px;font-weight:700;color:#FFFFFF;\">"
                + number + "</td></tr></table></td>"
                + "<td class=\"tx\" valign=\"top\" style=\"font-size:16px;line-height:24px;color:" + NAVY + ";\">"
                + "<strong class=\"tx-h\">" + leadHtml + "</strong> " + textHtml + "</td></tr></table>";
    }

    /** A thin rule that separates the closing notes from the content above. */
    static String ruleThen(String html) {
        return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>"
                + "<td class=\"bd-soft\" style=\"border-top:1px solid #E1E8F2;padding:20px 0 14px 0;\">" + html + "</td></tr></table>";
    }

    // ---- skeleton ------------------------------------------------------------------------------------

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
                      <td align="center" bgcolor="#FFFFFF" style="background-color:#FFFFFF;padding:20px 24px 16px 24px;">
                        <a href="{{SITE}}" target="_blank" style="text-decoration:none;"><img src="{{LOGO}}" width="{{LOGO_W}}" height="{{LOGO_H}}" alt="TIAM Digital" style="display:block;margin:0 auto;border:0;outline:none;font-family:'Segoe UI',Roboto,Helvetica,Arial,sans-serif;font-size:24px;line-height:{{LOGO_H}}px;font-weight:800;color:#16263F;"></a>
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
                        <p style="margin:0;font-size:12px;line-height:18px;color:#8FA3BF;">Recibiste este email porque compraste {{BOUGHT}}.</p>
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
