package com.tiam.pack.web;

import com.tiam.pack.service.CheckoutCommand;
import com.tiam.pack.service.PackCheckoutError;
import com.tiam.pack.service.PackCheckoutException;
import com.tiam.pack.service.PackCheckoutService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The landing page's buy button is a plain HTML form posting here, so this endpoint answers with
 * redirects, not JSON: {@code 303} to the Mercado Pago checkout on success, {@code 303} back to the
 * landing page with {@code ?error=<code>} on any failure. A buyer who clicks "Comprar" must never end
 * up staring at a JSON error page.
 */
@Slf4j
@RestController
@RequestMapping("/packs")
@RequiredArgsConstructor
public class PackCheckoutController {

    /** Honeypot: invisible to people, tempting to bots. */
    private static final String HONEYPOT_FIELD = "web";

    private final PackCheckoutService checkoutService;
    private final PackOriginPolicy originPolicy;
    private final PackRedirects redirects;

    @PostMapping(value = "/checkout", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> checkout(@RequestParam MultiValueMap<String, String> form, HttpServletRequest request) {
        String edition = first(form, "edicion", "edition");

        if (!originPolicy.isAllowed(request.getHeader("Origin"))) {
            log.warn("Pack checkout refused: origin not allowed");
            return seeOther(redirects.landing(edition, PackCheckoutError.ORIGEN));
        }
        if (StringUtils.hasText(form.getFirst(HONEYPOT_FIELD))) {
            // A bot filled the trap field. Create nothing, say nothing: it just lands back on the page.
            log.info("Pack checkout ignored: honeypot field filled");
            return seeOther(redirects.landing(edition, null));
        }

        CheckoutCommand command = new CheckoutCommand(
                form.getFirst("email"),
                first(form, "nombre", "name"),
                edition,
                form.get("extras"),
                first(form, "telefono", "phone"),
                form.getFirst("expected_total"),
                form.getFirst("utm_source"),
                form.getFirst("utm_medium"),
                form.getFirst("utm_campaign"),
                form.getFirst("utm_content"),
                request.getRemoteAddr());

        String paymentUrl = checkoutService.start(command);
        if (!PackRedirects.isMercadoPagoUrl(paymentUrl)) {
            // Never redirect somewhere that is not Mercado Pago, whatever produced this value.
            log.error("Pack checkout produced a non-Mercado Pago payment URL; refusing to redirect");
            return seeOther(redirects.landing(edition, PackCheckoutError.PAGO));
        }
        return seeOther(paymentUrl);
    }

    @ExceptionHandler(PackCheckoutException.class)
    ResponseEntity<Void> onCheckoutError(PackCheckoutException error, HttpServletRequest request) {
        log.info("Pack checkout not started: {}", error.error().code());
        return seeOther(redirects.landing(editionOf(request), error.error()));
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<Void> onUnexpectedError(RuntimeException error, HttpServletRequest request) {
        log.error("Unexpected error starting a pack checkout: {}", error.getMessage(), error);
        return seeOther(redirects.landing(editionOf(request), PackCheckoutError.INTERNO));
    }

    private static String editionOf(HttpServletRequest request) {
        String edicion = request.getParameter("edicion");
        return StringUtils.hasText(edicion) ? edicion : request.getParameter("edition");
    }

    private static String first(MultiValueMap<String, String> form, String primary, String alias) {
        String value = form.getFirst(primary);
        return value != null ? value : form.getFirst(alias);
    }

    private static ResponseEntity<Void> seeOther(String location) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(URI.create(location))
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
