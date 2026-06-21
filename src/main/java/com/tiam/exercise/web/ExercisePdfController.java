package com.tiam.exercise.web;

import com.tiam.exercise.pdf.FichaPdfService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * PDF download endpoints for exercise fichas (printable A4 worksheets).
 *
 * <p><strong>Note on response format:</strong> These endpoints return raw {@code application/pdf}
 * bytes, NOT the {@code ApiResponse<T>} JSON envelope. Binary file downloads are a deliberate
 * exception to the envelope rule — the client streams the bytes directly as a file.
 */
@Tag(name = "Exercise PDF", description = "Printable A4 ficha generation for exercises")
@RestController
@RequestMapping("/exercises")
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
public class ExercisePdfController {

    private final FichaPdfService fichaPdfService;

    /**
     * Download a single A4 ficha PDF for one exercise.
     *
     * <p>Visibility: the exercise must be PUBLISHED and either TIAM-owned or owned by
     * the authenticated user. Returns 404 if the exercise does not exist or is not visible.
     *
     * @param id the exercise ID
     * @return PDF bytes with {@code Content-Disposition: attachment; filename="{slugified-title}.pdf"}
     */
    @Operation(summary = "Download single exercise ficha as PDF")
    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> downloadSingleFicha(@PathVariable Long id) {
        byte[] pdf = fichaPdfService.generateSingleFicha(id);

        // We don't have access to the title here without a second fetch;
        // FichaPdfService already validated and fetched the exercise — keep it simple.
        // The service could return a DTO with bytes+title, but that adds coupling.
        // Returning a generic filename is acceptable; clients can rename the download.
        String filename = "ejercicio-" + id + ".pdf";

        return ResponseEntity.ok()
            .headers(buildPdfHeaders(filename))
            .contentType(MediaType.APPLICATION_PDF)
            .body(pdf);
    }

    /**
     * Download a combined A4 PDF with one ficha per exercise, in the specified order.
     * Used by the session builder "Imprimir fichas" feature.
     *
     * <p>Exercises not visible to the current user are silently skipped.
     * Returns 404 if none of the requested exercises are visible.
     *
     * @param request body containing the ordered list of exercise IDs
     * @return combined PDF bytes
     */
    @Operation(summary = "Download combined fichas PDF for a list of exercises")
    @PostMapping(value = "/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> downloadCombinedFichas(
            @Valid @RequestBody CombinedFichaRequest request) {
        byte[] pdf = fichaPdfService.generateCombinedFicha(
            request.exerciseIds(), request.patientId(), request.sessionTitle());
        return ResponseEntity.ok()
            .headers(buildPdfHeaders("sesion-fichas.pdf"))
            .contentType(MediaType.APPLICATION_PDF)
            .body(pdf);
    }

    // -------------------------------------------------------------------------

    private HttpHeaders buildPdfHeaders(String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(
            ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build()
        );
        return headers;
    }

    /**
     * Request body for the combined PDF endpoint.
     *
     * @param exerciseIds  ordered list of exercise IDs to include in the PDF
     * @param patientId    optional patient ID — when present, the patient's data is rendered on each ficha
     * @param sessionTitle optional session title shown in the info strip
     */
    public record CombinedFichaRequest(
        @NotEmpty(message = "At least one exercise ID is required")
        List<Long> exerciseIds,
        Long patientId,
        String sessionTitle
    ) {}
}
