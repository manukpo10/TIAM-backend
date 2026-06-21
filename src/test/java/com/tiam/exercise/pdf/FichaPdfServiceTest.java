package com.tiam.exercise.pdf;

import com.tiam.cognitivearea.domain.CognitiveArea;
import com.tiam.common.exception.ResourceNotFoundException;
import com.tiam.exercise.domain.DifficultyLevel;
import com.tiam.exercise.domain.Exercise;
import com.tiam.exercise.domain.ExerciseStatus;
import com.tiam.exercise.domain.MaterialType;
import com.tiam.exercise.repository.ExerciseRepository;
import com.tiam.patient.domain.Patient;
import com.tiam.patient.service.PatientService;
import com.tiam.user.domain.User;
import com.tiam.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FichaPdfServiceTest {

    private static final long USER_ID = 1L;

    @Mock ExerciseRepository exerciseRepository;
    @Mock UserService userService;
    @Mock PatientService patientService;

    FichaPdfService service;

    @BeforeEach
    void setUp() {
        service = new FichaPdfService(exerciseRepository, userService, patientService);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(USER_ID, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void combinedFicha_withPatient_rendersPdfAndWritesSample() throws Exception {
        Exercise ex = exercise(1L, "Evocación de palabras por categoría", MaterialType.VERBAL,
            DifficultyLevel.BASIC, "Ejercicio de fluencia verbal para evocar palabras de una categoría.",
            "Pedile al paciente que nombre 10 elementos de una categoría (ej. frutas).",
            new CognitiveArea(1L, "memoria", "Memoria"),
            new CognitiveArea(2L, "fluencia-verbal", "Fluencia Verbal"));
        Exercise ex2 = exercise(2L, "Secuencia de números con atención dividida", MaterialType.PRINTABLE,
            DifficultyLevel.INTERMEDIATE, "Ejercicio de atención dividida utilizando secuencias numéricas.",
            "Presentá una secuencia y pedí que la complete tachando los pares.",
            new CognitiveArea(3L, "atencion", "Atención"));

        when(exerciseRepository.findAllByIdInAndActivoTrue(any())).thenReturn(List.of(ex, ex2));
        when(userService.findEntityById(USER_ID)).thenReturn(professional());
        when(patientService.findEntityById(5L, USER_ID)).thenReturn(patient());

        byte[] pdf = service.generateCombinedFicha(List.of(1L, 2L), 5L, "Sesión del 21/06/2026");

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        Files.write(Path.of("target/sample-ficha-pro.pdf"), pdf);
    }

    @Test
    void singleFicha_withoutPatient_rendersPdfAndWritesSample() throws Exception {
        Exercise ex = exercise(1L, "Evocación de palabras por categoría", MaterialType.VERBAL,
            DifficultyLevel.BASIC, "Ejercicio de fluencia verbal para evocar palabras de una categoría.",
            "Pedile al paciente que nombre 10 elementos de una categoría (ej. frutas).",
            new CognitiveArea(1L, "memoria", "Memoria"),
            new CognitiveArea(2L, "fluencia-verbal", "Fluencia Verbal"));

        when(exerciseRepository.findByIdAndActivoTrue(1L)).thenReturn(Optional.of(ex));
        when(userService.findEntityById(USER_ID)).thenReturn(professional());

        byte[] pdf = service.generateSingleFicha(1L);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        Files.write(Path.of("target/sample-ficha-single.pdf"), pdf);
    }

    @Test
    void combinedFicha_whenPatientNotOwned_propagatesNotFound() {
        Exercise ex = exercise(1L, "Test", MaterialType.VERBAL, DifficultyLevel.BASIC,
            "desc", "instr", new CognitiveArea(1L, "memoria", "Memoria"));
        when(exerciseRepository.findAllByIdInAndActivoTrue(any())).thenReturn(List.of(ex));
        when(userService.findEntityById(USER_ID)).thenReturn(professional());
        when(patientService.findEntityById(eq(99L), anyLong()))
            .thenThrow(new ResourceNotFoundException("Patient not found: 99"));

        assertThatThrownBy(() -> service.generateCombinedFicha(List.of(1L), 99L, null))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // --- fixtures -------------------------------------------------------------

    private Exercise exercise(Long id, String title, MaterialType material, DifficultyLevel difficulty,
                              String description, String instructions, CognitiveArea... areas) {
        Exercise e = new Exercise();
        e.setId(id);
        e.setTitle(title);
        e.setDescription(description);
        e.setInstructions(instructions);
        e.setMaterialType(material);
        e.setDifficulty(difficulty);
        e.setStatus(ExerciseStatus.PUBLISHED);
        e.setOwnerId(null);
        Set<CognitiveArea> set = new LinkedHashSet<>(List.of(areas));
        e.setCognitiveAreas(set);
        return e;
    }

    private User professional() {
        User u = new User();
        u.setId(USER_ID);
        u.setFullName("Lic. María García");
        u.setSpecialty("Estimulación cognitiva");
        return u;
    }

    private Patient patient() {
        Patient p = new Patient();
        p.setId(5L);
        p.setFullName("Juan Pérez");
        p.setBirthDate(LocalDate.of(1953, 4, 12));
        p.setDiagnosis("Deterioro cognitivo leve");
        p.setProfessionalId(USER_ID);
        return p;
    }
}
