package com.tiam.exercise.pdf;

import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import com.tiam.common.exception.ResourceNotFoundException;
import com.tiam.exercise.domain.DifficultyLevel;
import com.tiam.exercise.domain.Exercise;
import com.tiam.exercise.domain.ExerciseStatus;
import com.tiam.exercise.domain.MaterialType;
import com.tiam.exercise.repository.ExerciseRepository;
import com.tiam.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates printable A4 "fichas" (worksheets) for cognitive stimulation exercises.
 *
 * <p>Returns raw {@code byte[]} (PDF) — callers are responsible for setting HTTP headers.
 * These methods are intentionally NOT wrapped in {@code ApiResponse} because the output
 * is a binary file download, not a JSON envelope response.
 */
@Service
@RequiredArgsConstructor
public class FichaPdfService {

    private static final Color TIAM_BLUE = new Color(0x1E, 0x73, 0xD8);
    private static final Color LIGHT_GRAY = new Color(0xF4, 0xF6, 0xF9);
    private static final Color BORDER_GRAY = new Color(0xCC, 0xD1, 0xD9);
    private static final Color TEXT_DARK = new Color(0x1A, 0x1A, 0x2E);
    private static final Color TEXT_MUTED = new Color(0x6B, 0x7A, 0x99);

    private final ExerciseRepository exerciseRepository;

    /**
     * Generates a single-page A4 ficha for the given exercise.
     * Applies visibility rules: exercise must be PUBLISHED + either TIAM-owned or owned by current user.
     *
     * @throws ResourceNotFoundException if the exercise is not found or not visible to the current user
     */
    @Transactional(readOnly = true)
    public byte[] generateSingleFicha(Long exerciseId) {
        Long userId = SecurityUtils.currentUserId();
        Exercise exercise = findVisibleExercise(exerciseId, userId);

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4, 36, 36, 36, 36);
            PdfWriter.getInstance(doc, baos);
            doc.open();
            addFichaPage(doc, exercise);
            doc.close();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new PdfGenerationException("Failed to generate PDF for exercise " + exerciseId, e);
        }
    }

    /**
     * Generates a combined A4 PDF with one ficha per exercise, in the given order.
     * Each exercise undergoes the same visibility check as the single-ficha endpoint.
     * Exercises not visible to the current user are silently skipped.
     *
     * @throws PdfGenerationException   if PDF rendering fails
     * @throws ResourceNotFoundException if no visible exercises are found for the given IDs
     */
    @Transactional(readOnly = true)
    public byte[] generateCombinedFicha(List<Long> exerciseIds) {
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

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4, 36, 36, 36, 36);
            PdfWriter.getInstance(doc, baos);
            doc.open();

            for (int i = 0; i < visible.size(); i++) {
                addFichaPage(doc, visible.get(i));
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

    private void addFichaPage(Document doc, Exercise exercise) throws DocumentException {
        // --- Header band ---
        PdfPTable headerTable = new PdfPTable(1);
        headerTable.setWidthPercentage(100);

        PdfPCell headerCell = new PdfPCell();
        headerCell.setBackgroundColor(TIAM_BLUE);
        headerCell.setBorder(Rectangle.NO_BORDER);
        headerCell.setPadding(14);

        Font wordmarkFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, Font.BOLD, Color.WHITE);
        Font subtitleFont = FontFactory.getFont(FontFactory.HELVETICA, 9, Font.NORMAL, new Color(0xC5, 0xD8, 0xF5));

        Paragraph wordmark = new Paragraph("TIAM Digital", wordmarkFont);
        wordmark.setSpacingAfter(2);
        Paragraph subtitle = new Paragraph("Estimulación cognitiva", subtitleFont);

        headerCell.addElement(wordmark);
        headerCell.addElement(subtitle);
        headerTable.addCell(headerCell);
        doc.add(headerTable);

        doc.add(new Paragraph(" "));

        // --- Exercise title ---
        Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 20, Font.BOLD, TEXT_DARK);
        Paragraph title = new Paragraph(exercise.getTitle(), titleFont);
        title.setSpacingAfter(10);
        doc.add(title);

        // --- Badges row: difficulty + material type + cognitive areas ---
        PdfPTable badgeTable = new PdfPTable(1);
        badgeTable.setWidthPercentage(100);
        PdfPCell badgeCell = new PdfPCell();
        badgeCell.setBorder(Rectangle.NO_BORDER);
        badgeCell.setPaddingBottom(10);

        Phrase badges = new Phrase();
        Font badgeFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Font.BOLD, Color.WHITE);

        badges.add(buildBadgeChunk(difficultyLabel(exercise.getDifficulty()), TIAM_BLUE, badgeFont));
        badges.add(new Chunk("  "));
        badges.add(buildBadgeChunk(materialLabel(exercise.getMaterialType()), new Color(0x5C, 0x8A, 0xBE), badgeFont));

        for (var area : exercise.getCognitiveAreas()) {
            badges.add(new Chunk("  "));
            badges.add(buildBadgeChunk(area.getName(), new Color(0x2E, 0x86, 0x6E), badgeFont));
        }

        badgeCell.addElement(new Paragraph(badges));
        badgeTable.addCell(badgeCell);
        doc.add(badgeTable);

        // --- Horizontal separator ---
        doc.add(buildSeparator());

        // --- Descripción section ---
        doc.add(buildSectionHeader("Descripción"));
        Font bodyFont = FontFactory.getFont(FontFactory.HELVETICA, 10, Font.NORMAL, TEXT_DARK);
        Paragraph descParagraph = new Paragraph(exercise.getDescription(), bodyFont);
        descParagraph.setSpacingAfter(14);
        descParagraph.setLeading(14);
        doc.add(descParagraph);

        // --- Instrucciones section ---
        doc.add(buildSectionHeader("Instrucciones para el profesional"));

        // Preserve line breaks from the instructions text
        String[] instructionLines = exercise.getInstructions().split("\\r?\\n", -1);
        for (String line : instructionLines) {
            Paragraph lineParagraph = new Paragraph(line.isBlank() ? " " : line, bodyFont);
            lineParagraph.setLeading(14);
            doc.add(lineParagraph);
        }

        doc.add(new Paragraph(" "));

        // --- Área de trabajo (blank worksheet space) ---
        doc.add(buildAreaDeTrabajo());

        // --- Footer ---
        doc.add(buildFooter(exercise.getTitle()));
    }

    // -------------------------------------------------------------------------
    // Layout helpers
    // -------------------------------------------------------------------------

    private Chunk buildBadgeChunk(String text, Color bg, Font font) {
        Chunk chunk = new Chunk(" " + text + " ", font);
        chunk.setBackground(bg, 4, 3, 4, 3);
        chunk.setCharacterSpacing(0.3f);
        return chunk;
    }

    private Paragraph buildSectionHeader(String text) throws DocumentException {
        Font sectionFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, Font.BOLD, TIAM_BLUE);
        Paragraph header = new Paragraph(text, sectionFont);
        header.setSpacingBefore(6);
        header.setSpacingAfter(4);
        return header;
    }

    private PdfPTable buildSeparator() throws DocumentException {
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

    private PdfPTable buildAreaDeTrabajo() throws DocumentException {
        // Section header
        Font sectionFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, Font.BOLD, TIAM_BLUE);
        Paragraph areaHeader = new Paragraph("Área de trabajo", sectionFont);
        areaHeader.setSpacingBefore(6);
        areaHeader.setSpacingAfter(6);

        // Bordered box for patient work space
        PdfPTable workTable = new PdfPTable(1);
        workTable.setWidthPercentage(100);
        workTable.setSpacingBefore(4);
        workTable.setSpacingAfter(10);

        PdfPCell workCell = new PdfPCell();
        workCell.setFixedHeight(180);
        workCell.setBackgroundColor(LIGHT_GRAY);
        workCell.setBorderColor(BORDER_GRAY);
        workCell.setBorderWidth(1.2f);
        workCell.setPadding(12);

        Font hintFont = FontFactory.getFont(FontFactory.HELVETICA, 8, Font.ITALIC, TEXT_MUTED);
        workCell.addElement(new Paragraph("Espacio para que el paciente complete la actividad", hintFont));
        workTable.addCell(workCell);

        // Combine into a containing table so we can add the header above
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

    private Paragraph buildFooter(String exerciseTitle) {
        Font footerFont = FontFactory.getFont(FontFactory.HELVETICA, 7, Font.NORMAL, TEXT_MUTED);
        String footerText = "TIAM Digital — " + exerciseTitle + "   |   Ficha de estimulación cognitiva";
        Paragraph footer = new Paragraph(footerText, footerFont);
        footer.setAlignment(Element.ALIGN_CENTER);
        footer.setSpacingBefore(6);
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
}
