package com.tiam.cognitivearea.web;

import com.tiam.cognitivearea.dto.CognitiveAreaResponse;
import com.tiam.cognitivearea.service.CognitiveAreaService;
import com.tiam.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/cognitive-areas")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class CognitiveAreaController {

    private final CognitiveAreaService cognitiveAreaService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<CognitiveAreaResponse>>> findAll() {
        return ResponseEntity.ok(ApiResponse.ok(cognitiveAreaService.findAll()));
    }
}
