package com.tiam.play.web;

import com.tiam.common.web.ApiResponse;
import com.tiam.play.dto.*;
import com.tiam.play.service.PlayService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/play")
@RequiredArgsConstructor
public class PlayController {

    private final PlayService playService;

    @GetMapping("/{token}")
    public ResponseEntity<ApiResponse<PlaySessionResponse>> getSession(
            @PathVariable String token) {
        return ResponseEntity.ok(ApiResponse.ok(playService.getSession(token)));
    }

    @PostMapping("/{token}/complete")
    public ResponseEntity<ApiResponse<SubscribeResponse>> complete(
            @PathVariable String token,
            @Valid @RequestBody CompletePlayRequest request) {
        playService.recordResult(token, request);
        return ResponseEntity.ok(ApiResponse.ok(new SubscribeResponse(true)));
    }

    @PostMapping("/{token}/subscribe")
    public ResponseEntity<ApiResponse<SubscribeResponse>> subscribe(
            @PathVariable String token) {
        playService.activateSubscription(token);
        return ResponseEntity.ok(ApiResponse.ok(new SubscribeResponse(true)));
    }
}
