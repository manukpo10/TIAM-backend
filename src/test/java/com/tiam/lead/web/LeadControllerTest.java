package com.tiam.lead.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tiam.common.web.ClientIpResolver;
import com.tiam.lead.dto.CreateLeadRequest;
import com.tiam.lead.service.LeadCaptureResult;
import com.tiam.lead.service.LeadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class LeadControllerTest {

    static final String BODY = """
            {"email":"ana@example.com","name":"Ana","source":"recursos","consent":true,
             "website":"","elapsedMs":8500}
            """;

    @Mock LeadService leadService;
    @Mock ClientIpResolver ipResolver;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new LeadController(leadService, ipResolver)).build();
    }

    /** A dropped bot gets exactly what a person gets, so it cannot learn which check stopped it. */
    @ParameterizedTest
    @EnumSource(LeadCaptureResult.class)
    void everyOutcome_answersTheSame201(LeadCaptureResult outcome) throws Exception {
        when(leadService.capture(any(), any())).thenReturn(outcome);

        mvc.perform(post("/leads").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("¡Listo! Ya podés descargar las fichas."));
    }

    @Test
    void passesTheFormFieldsAndTheResolvedClientIpToTheService() throws Exception {
        when(ipResolver.resolve(any())).thenReturn("203.0.113.7");
        when(leadService.capture(any(), any())).thenReturn(LeadCaptureResult.SAVED);

        mvc.perform(post("/leads").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());

        ArgumentCaptor<CreateLeadRequest> captor = ArgumentCaptor.forClass(CreateLeadRequest.class);
        verify(leadService).capture(captor.capture(), org.mockito.ArgumentMatchers.eq("203.0.113.7"));
        assertThat(captor.getValue().website()).isEmpty();
        assertThat(captor.getValue().elapsedMs()).isEqualTo(8500L);
    }

    @Test
    void anOldFormWithoutTheNewFields_stillDeserializes() throws Exception {
        when(leadService.capture(any(), any())).thenReturn(LeadCaptureResult.TOO_FAST);

        mvc.perform(post("/leads").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ana@example.com\",\"source\":\"plataforma\",\"consent\":true}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<CreateLeadRequest> captor = ArgumentCaptor.forClass(CreateLeadRequest.class);
        verify(leadService).capture(captor.capture(), any());
        assertThat(captor.getValue().website()).isNull();
        assertThat(captor.getValue().elapsedMs()).isNull();
    }

    /** A size limit on the honeypot would answer 400 naming the field: it would tell the bot what stopped it. */
    @Test
    void anOversizedHoneypot_stillAnswersTheSame201() throws Exception {
        when(leadService.capture(any(), any())).thenReturn(LeadCaptureResult.HONEYPOT);

        mvc.perform(post("/leads").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ana@example.com\",\"consent\":true,\"elapsedMs\":8500,\"website\":\""
                                + "x".repeat(300) + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void withoutConsent_isStillRejectedByValidation() throws Exception {
        mvc.perform(post("/leads").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ana@example.com\",\"consent\":false,\"elapsedMs\":8500}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(leadService);
    }
}
