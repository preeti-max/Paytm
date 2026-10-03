package com.seatreservation.controller;

import com.seatreservation.dto.TokenRequest;
import com.seatreservation.dto.TokenResponse;
import com.seatreservation.security.JwtService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final JwtService jwtService;

    public AuthController(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @PostMapping("/token")
    public ResponseEntity<TokenResponse> createToken(@Valid @RequestBody TokenRequest request) {
        String role = request.role() != null && !request.role().isBlank() ? request.role().toUpperCase() : "USER";
        String token = jwtService.generateToken(request.userId().trim(), role);
        return ResponseEntity.ok(new TokenResponse(token));
    }
}
