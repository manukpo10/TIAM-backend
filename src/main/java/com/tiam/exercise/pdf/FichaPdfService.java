package com.tiam.exercise.pdf;

import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import com.tiam.common.exception.ResourceNotFoundException;
import com.tiam.exercise.domain.DifficultyLevel;
import com.tiam.exercise.domain.Exercise;
import com.tiam.exercise.domain.ExerciseStatus;
import com.tiam.exercise.domain.MaterialType;
import com.tiam.exercise.repository.ExerciseRepository;
import com.tiam.patient.domain.Patient;
import com.tiam.patient.service.PatientService;
import com.tiam.security.SecurityUtils;
import com.tiam.user.domain.User;
import com.tiam.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates printable A4 "fichas" (worksheets) for cognitive stimulation exercises.
 *
 * <p>Returns raw {@code byte[]} (PDF) — callers are responsible for setting HTTP headers.
 * These methods are intentionally NOT wrapped in {@code ApiResponse} because the output
 * is a binary file download, not a JSON envelope response.
 *
 * <p>Each ficha carries a branded header (logo + wordmark) and an info strip with the
 * professional's data, the date, and — when a patient is provided — the patient's name,
 * age and diagnosis. The strip repeats on every page so individual sheets stay identifiable
 * once printed and handed out.
 */
@Service
@RequiredArgsConstructor
public class FichaPdfService {

    private static final Color TIAM_BLUE = new Color(0x1E, 0x73, 0xD8);
    private static final Color LIGHT_GRAY = new Color(0xF4, 0xF6, 0xF9);
    private static final Color BORDER_GRAY = new Color(0xCC, 0xD1, 0xD9);
    private static final Color TEXT_DARK = new Color(0x1A, 0x1A, 0x2E);
    private static final Color TEXT_MUTED = new Color(0x6B, 0x7A, 0x99);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Brand logo, loaded once from the classpath. Null if the asset is missing. */
    private static final byte[] LOGO_BYTES = loadLogo();

    /** Client for fetching exercise images (from the public Supabase Storage bucket). */
    private static final HttpClient IMAGE_HTTP = HttpClient.newHttpClient();

    private final ExerciseRepository exerciseRepository;
    private final UserService userService;
    private final PatientService patientService;

    /**
     * Header data rendered on every ficha. Patient fields are null when no patient context
     * is supplied (e.g. a single ficha downloaded straight from the library).
     */
    private record FichaHeader(
        String professionalName,
        String professionalSpecialty,
        String patientName,
        Integer patientAge,
        String patientDiagnosis,
        String sessionTitle,
        String dateLabel
    ) {}

    /**
     * Generates a single-page A4 ficha for the given exercise, with the current
     * professional's data and today's date (no patient context).
     *
     * @throws ResourceNotFoundException if the exercise is not found or not visible to the current user
     */
    @Transactional(readOnly = true)
    public byte[] generateSingleFicha(Long exerciseId) {
        Long userId = SecurityUtils.currentUserId();
        Exercise exercise = findVisibleExercise(exerciseId, userId);
        FichaHeader header = buildHeader(userId, null, null);

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4, 36, 36, 36, 36);
            PdfWriter.getInstance(doc, baos);
            doc.open();
            addFichaPage(doc, exercise, header);
            doc.close();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new PdfGenerationException("Failed to generate PDF for exercise " + exerciseId, e);
        }
    }

    /**
     * Generates a combined A4 PDF with one ficha per exercise, in the given order.
     * Each exercise undergoes the same visibility check as the single-ficha endpoint;
     * exercises not visible to the current user are silently skipped.
     *
     * @param exerciseIds  ordered list of exercise IDs
     * @param patientId    optional — when present, the patient's data is rendered (ownership enforced)
     * @param sessionTitle optional — session title shown in the info strip
     * @throws PdfGenerationException    if PDF rendering fails
     * @throws ResourceNotFoundException if no visible exercises are found, or the patient is not owned by the user
     */
    @Transactional(readOnly = true)
    public byte[] generateCombinedFicha(List<Long> exerciseIds, Long patientId, String sessionTitle) {
        Long userId = SecurityUtils.currentUserId();

        // Fetch all in one query (EntityGraph loads cognitiveAreas eagerly)
        List<Exercise> all = exerciseRepository.findAllByIdInAndActivoTrue(exerciseIds);

        // Filter to visible, then re-order to match requested order
        List<Exercise> visible = new ArrayList<>();
        for (Long id : exerciseIds) {
            all.stream()
               .filter(e -> e.getId().equals(id) && isVisible(e, userId))
               .findFirst()
               .ifPresent(visible::add);
        }

        if (visible.isEmpty()) {
            throw new ResourceNotFoundException("No visible exercises found for the provided IDs");
        }

        FichaHeader header = buildHeader(userId, patientId, sessionTitle);

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4, 36, 36, 36, 36);
            PdfWriter.getInstance(doc, baos);
            doc.open();

            for (int i = 0; i < visible.size(); i++) {
                addFichaPage(doc, visible.get(i), header);
                if (i < visible.size() - 1) {
                    doc.newPage();
                }
            }

            doc.close();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new PdfGenerationException("Failed to generate combined PDF", e);
        }
    }

    // -------------------------------------------------------------------------
    // Header assembly
    // -------------------------------------------------------------------------

    /**
     * Builds the header context. The professional is the current user; the patient (if any)
     * is fetched through {@link PatientService#findEntityById} which enforces ownership.
     */
    private FichaHeader buildHeader(Long userId, Long patientId, String sessionTitle) {
        User professional = userService.findEntityById(userId);

        String patientName = null;
        Integer patientAge = null;
        String patientDiagnosis = null;
        if (patientId != null) {
            Patient patient = patientService.findEntityById(patientId, userId);
            patientName = patient.getFullName();
            patientAge = ageFrom(patient.getBirthDate());
            patientDiagnosis = blankToNull(patient.getDiagnosis());
        }

        return new FichaHeader(
            professional.getFullName(),
            blankToNull(professional.getSpecialty()),
            patientName,
            patientAge,
            patientDiagnosis,
            blankToNull(sessionTitle),
            LocalDate.now().format(DATE_FMT)
        );
    }

    private Integer ageFrom(LocalDate birthDate) {
        if (birthDate == null) return null;
        return Period.between(birthDate, LocalDate.now()).getYears();
    }

    // -------------------------------------------------------------------------
    // Visibility helpers
    // -------------------------------------------------------------------------

    private Exercise findVisibleExercise(Long id, Long userId) {
        Exercise exercise = exerciseRepository.findByIdAndActivoTrue(id)
            .orElseThrow(() -> new ResourceNotFoundException("Exercise not found: " + id));
        if (!isVisible(exercise, userId)) {
            throw new ResourceNotFoundException("Exercise not found: " + id);
        }
        return exercise;
    }

    private boolean isVisible(Exercise e, Long userId) {
        if (e.getStatus() != ExerciseStatus.PUBLISHED) return false;
        // TIAM catalog (ownerId null) is visible to all; user-owned only to the owner
        return e.getOwnerId() == null || e.getOwnerId().equals(userId);
    }

    // -------------------------------------------------------------------------
    // PDF layout — one A4 ficha
    // -------------------------------------------------------------------------

    private void addFichaPage(Document doc, Exercise exercise, FichaHeader header) throws DocumentException {
        // --- Branded header: logo + wordmark + blue rule ---
        doc.add(buildBrandHeader());

        // --- Info strip: patient / professional / date / session ---
        doc.add(buildInfoStrip(header));

        doc.add(new Paragraph(" "));

        // --- Exercise title ---
        Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 19, Font.BOLD, TEXT_DARK);
        Paragraph title = new Paragraph(exercise.getTitle(), titleFont);
        title.setSpacingAfter(8);
        doc.add(title);

        // --- Badges row: difficulty + material type + cognitive areas ---
        Phrase badges = new Phrase();
        Font badgeFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Font.BOLD, Color.WHITE);
        badges.add(buildBadgeChunk(difficultyLabel(exercise.getDifficulty()), TIAM_BLUE, badgeFont));
        badges.add(new Chunk("  "));
        badges.add(buildBadgeChunk(materialLabel(exercise.getMaterialType()), new Color(0x5C, 0x8A, 0xBE), badgeFont));
        for (var area : exercise.getCognitiveAreas()) {
            badges.add(new Chunk("  "));
            badges.add(buildBadgeChunk(area.getName(), new Color(0x2E, 0x86, 0x6E), badgeFont));
        }
        Paragraph badgeParagraph = new Paragraph(badges);
        badgeParagraph.setSpacingAfter(10);
        doc.add(badgeParagraph);

        // --- Horizontal separator ---
        doc.add(buildSeparator());

        // --- Descripción section ---
        doc.add(buildSectionHeader("Descripción"));
        Font bodyFont = FontFactory.getFont(FontFactory.HELVETICA, 10, Font.NORMAL, TEXT_DARK);
        Paragraph descParagraph = new Paragraph(exercise.getDescription(), bodyFont);
        descParagraph.setSpacingAfter(12);
        descParagraph.setLeading(14);
        doc.add(descParagraph);

        // --- Instrucciones section ---
        doc.add(buildSectionHeader("Instrucciones para el profesional"));
        String[] instructionLines = exercise.getInstructions().split("\\r?\\n", -1);
        for (String line : instructionLines) {
            Paragraph lineParagraph = new Paragraph(line.isBlank() ? " " : line, bodyFont);
            lineParagraph.setLeading(14);
            doc.add(lineParagraph);
        }

        doc.add(new Paragraph(" "));

        // --- Exercise image (if any) or blank work area ---
        byte[] imageBytes = hasText(exercise.getPreviewImageUrl())
            ? fetchImage(exercise.getPreviewImageUrl())
            : null;
        if (imageBytes != null && addExerciseImage(doc, imageBytes)) {
            doc.add(new Paragraph(" "));
        } else {
            doc.add(buildAreaDeTrabajo());
        }

        // --- Professional foot: observations + signature lines ---
        doc.add(buildWriteLine("Observaciones / próxima sesión"));
        doc.add(buildWriteLine("Firma y sello del profesional"));

        // --- Footer ---
        doc.add(buildFooter(exercise.getTitle()));
    }

    // -------------------------------------------------------------------------
    // Layout helpers
    // -------------------------------------------------------------------------

    private PdfPTable buildBrandHeader() throws DocumentException {
        PdfPTable container = new PdfPTable(1);
        container.setWidthPercentage(100);

        // Brand lockup: the logo image already carries the wordmark + tagline.
        PdfPCell brandCell = new PdfPCell();
        brandCell.setBorder(Rectangle.NO_BORDER);
        brandCell.setPaddingBottom(6);

        boolean logoRendered = false;
        if (LOGO_BYTES != null) {
            try {
                Image logo = Image.getInstance(LOGO_BYTES);
                logo.scaleToFit(150, 62);
                logo.setAlignment(Image.LEFT);
                brandCell.addElement(logo);
                logoRendered = true;
            } catch (IOException e) {
                // fall through to the text wordmark fallback
            }
        }
        if (!logoRendered) {
            Font wordmarkFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 17, Font.BOLD, TIAM_BLUE);
            Font subtitleFont = FontFactory.getFont(FontFactory.HELVETICA, 8.5f, Font.NORMAL, TEXT_MUTED);
            Paragraph wordmark = new Paragraph("TIAM", wordmarkFont);
            wordmark.setSpacingAfter(1);
            brandCell.addElement(wordmark);
            brandCell.addElement(new Paragraph("Taller interactivo · adultos mayores", subtitleFont));
        }
        container.addCell(brandCell);

        // Blue underline rule
        PdfPCell rule = new PdfPCell();
        rule.setFixedHeight(2);
        rule.setBackgroundColor(TIAM_BLUE);
        rule.setBorder(Rectangle.NO_BORDER);
        container.addCell(rule);

        return container;
    }

    private PdfPTable buildInfoStrip(FichaHeader header) throws DocumentException {
        PdfPTable strip = new PdfPTable(1);
        strip.setWidthPercentage(100);
        strip.setSpacingBefore(8);

        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(LIGHT_GRAY);
        cell.setBorderColor(BORDER_GRAY);
        cell.setBorderWidth(0.8f);
        cell.setPadding(9);

        Font label = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8.5f, Font.BOLD, TEXT_MUTED);
        Font value = FontFactory.getFont(FontFactory.HELVETICA, 9.5f, Font.NORMAL, TEXT_DARK);
        Font diag = FontFactory.getFont(FontFactory.HELVETICA, 9f, Font.ITALIC, TEXT_MUTED);

        // Patient line (only when a patient is supplied)
        if (header.patientName() != null) {
            Phrase p = new Phrase();
            p.add(new Chunk("Paciente:  ", label));
            String who = header.patientName();
            if (header.patientAge() != null) who += "  ·  " + header.patientAge() + " años";
            p.add(new Chunk(who, value));
            if (header.patientDiagnosis() != null) {
                p.add(new Chunk("      " + header.patientDiagnosis(), diag));
            }
            Paragraph pp = new Paragraph(p);
            pp.setLeading(15);
            cell.addElement(pp);
        }

        // Professional line
        Phrase pr = new Phrase();
        pr.add(new Chunk("Profesional:  ", label));
        String prof = header.professionalName();
        if (header.professionalSpecialty() != null) prof += "  ·  " + header.professionalSpecialty();
        pr.add(new Chunk(prof, value));
        Paragraph prp = new Paragraph(pr);
        prp.setLeading(15);
        cell.addElement(prp);

        // Date (+ session) line
        Phrase d = new Phrase();
        d.add(new Chunk("Fecha:  ", label));
        d.add(new Chunk(header.dateLabel(), value));
        if (header.sessionTitle() != null) {
            d.add(new Chunk("        Sesión:  ", label));
            d.add(new Chunk(header.sessionTitle(), value));
        }
        Paragraph dp = new Paragraph(d);
        dp.setLeading(15);
        cell.addElement(dp);

        strip.addCell(cell);
        return strip;
    }

    private Chunk buildBadgeChunk(String text, Color bg, Font font) {
        Chunk chunk = new Chunk(" " + text + " ", font);
        chunk.setBackground(bg, 4, 3, 4, 3);
        chunk.setCharacterSpacing(0.3f);
        return chunk;
    }

    private Paragraph buildSectionHeader(String text) {
        Font sectionFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, Font.BOLD, TIAM_BLUE);
        Paragraph header = new Paragraph(text, sectionFont);
        header.setSpacingBefore(6);
        header.setSpacingAfter(4);
        return header;
    }

    private PdfPTable buildSeparator() {
        PdfPTable sep = new PdfPTable(1);
        sep.setWidthPercentage(100);
        sep.setSpacingBefore(4);
        sep.setSpacingAfter(10);
        PdfPCell cell = new PdfPCell();
        cell.setFixedHeight(1);
        cell.setBackgroundColor(BORDER_GRAY);
        cell.setBorder(Rectangle.NO_BORDER);
        sep.addCell(cell);
        return sep;
    }

    private PdfPTable buildAreaDeTrabajo() {
        Font sectionFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, Font.BOLD, TIAM_BLUE);
        Paragraph areaHeader = new Paragraph("Área de trabajo", sectionFont);
        areaHeader.setSpacingBefore(6);
        areaHeader.setSpacingAfter(6);

        PdfPTable workTable = new PdfPTable(1);
        workTable.setWidthPercentage(100);
        workTable.setSpacingBefore(4);
        workTable.setSpacingAfter(8);

        PdfPCell workCell = new PdfPCell();
        workCell.setFixedHeight(160);
        workCell.setBackgroundColor(LIGHT_GRAY);
        workCell.setBorderColor(BORDER_GRAY);
        workCell.setBorderWidth(1.2f);
        workCell.setPadding(12);

        Font hintFont = FontFactory.getFont(FontFactory.HELVETICA, 8, Font.ITALIC, TEXT_MUTED);
        workCell.addElement(new Paragraph("Espacio para que el paciente complete la actividad", hintFont));
        workTable.addCell(workCell);

        PdfPTable container = new PdfPTable(1);
        container.setWidthPercentage(100);

        PdfPCell titleCell = new PdfPCell();
        titleCell.setBorder(Rectangle.NO_BORDER);
        titleCell.setPaddingBottom(4);
        titleCell.addElement(areaHeader);
        container.addCell(titleCell);

        PdfPCell bodyCell = new PdfPCell();
        bodyCell.setBorder(Rectangle.NO_BORDER);
        bodyCell.addElement(workTable);
        container.addCell(bodyCell);

        return container;
    }

    /** A labelled blank line for the professional to fill in by hand (observations, signature). */
    private PdfPTable buildWriteLine(String label) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        t.setSpacingBefore(10);

        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(BORDER_GRAY);
        c.setBorderWidthBottom(0.8f);
        c.setFixedHeight(24);
        c.setVerticalAlignment(Element.ALIGN_TOP);
        Font labelFont = FontFactory.getFont(FontFactory.HELVETICA, 8, Font.ITALIC, TEXT_MUTED);
        c.addElement(new Paragraph(label, labelFont));
        t.addCell(c);
        return t;
    }

    private Paragraph buildFooter(String exerciseTitle) {
        Font footerFont = FontFactory.getFont(FontFactory.HELVETICA, 7, Font.NORMAL, TEXT_MUTED);
        String footerText = "TIAM Digital — " + exerciseTitle + "   |   Ficha de estimulación cognitiva";
        Paragraph footer = new Paragraph(footerText, footerFont);
        footer.setAlignment(Element.ALIGN_CENTER);
        footer.setSpacingBefore(10);
        return footer;
    }

    // -------------------------------------------------------------------------
    // Enum → Spanish label mappings
    // -------------------------------------------------------------------------

    private String difficultyLabel(DifficultyLevel level) {
        return switch (level) {
            case BASIC        -> "Básico";
            case INTERMEDIATE -> "Intermedio";
            case ADVANCED     -> "Avanzado";
        };
    }

    private String materialLabel(MaterialType type) {
        return switch (type) {
            case PRINTABLE       -> "Imprimible";
            case SENSORIAL       -> "Sensorial";
            case VERBAL          -> "Verbal";
            case IMAGE_SEQUENCE  -> "Secuencia de imágenes";
        };
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    /** Downloads the image bytes from a (public) URL; null on any failure. */
    private byte[] fetchImage(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();
            HttpResponse<byte[]> response = IMAGE_HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) return response.body();
        } catch (Exception ignored) {
            // Network/timeout failure → caller falls back to the blank work area.
        }
        return null;
    }

    /** Adds the exercise image to the ficha. Returns false (adding nothing) if it can't render. */
    private boolean addExerciseImage(Document doc, byte[] bytes) {
        try {
            Image image = Image.getInstance(bytes);
            image.scaleToFit(430, 250);
            image.setAlignment(Image.MIDDLE);
            doc.add(buildSectionHeader("Imagen del ejercicio"));
            doc.add(image);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] loadLogo() {
        try (InputStream is = FichaPdfService.class.getResourceAsStream("/tiam-logo.png")) {
            return is != null ? is.readAllBytes() : null;
        } catch (IOException e) {
            return null;
        }
    }
}
