package com.tiam.challenge.web;

import com.tiam.challenge.dto.ChallengeDayResultResponse;
import com.tiam.challenge.dto.ChallengeProgressResponse;
import com.tiam.challenge.dto.CompleteDayRequest;
import com.tiam.challenge.service.ChallengeDayResultService;
import com.tiam.common.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/challenge")
@RequiredArgsConstructor
public class ChallengeDayResultController {

    private final ChallengeDayResultService challengeDayResultService;

    @PostMapping("/{accessToken}/days/{day}/complete")
    public ResponseEntity<ApiResponse<ChallengeDayResultResponse>> completeDay(
            @PathVariable String accessToken,
            @PathVariable int day,
            @Valid @RequestBody CompleteDayRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                challengeDayResultService.completeDay(accessToken, day, request)));
    }

    @GetMapping("/{accessToken}/progress")
    public ResponseEntity<ApiResponse<ChallengeProgressResponse>> getProgress(
            @PathVariable String accessToken) {
        return ResponseEntity.ok(ApiResponse.ok(challengeDayResultService.getProgress(accessToken)));
    }
}
