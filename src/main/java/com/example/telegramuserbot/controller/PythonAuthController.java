package com.example.telegramuserbot.controller;

import com.example.telegramuserbot.service.python.PythonExecutionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Endpoint for authorizing Telethon session via the existing Docker execution path.
 * Use when interactive Docker TTY is unavailable (Windows Docker Desktop limitation).
 *
 * <p>Step 1: GET /api/python/auth/request?phone=+1234567890
 * <p>Step 2: GET /api/python/auth/confirm?phone=+1234567890&code=12345
 * <p>Step 3 (if 2FA): GET /api/python/auth/confirm?phone=+1234567890&code=12345&password=pass
 */
@RestController
@RequestMapping("/api/python/auth")
public final class PythonAuthController {

    private final PythonExecutionService pythonExecutionService;

    public PythonAuthController(PythonExecutionService pythonExecutionService) {
        this.pythonExecutionService = pythonExecutionService;
    }

    /**
     * Step 1: sends Telegram auth code to the phone number.
     * Starts Docker in background and returns immediately.
     */
    @GetMapping("/request")
    public ResponseEntity<Map<String, String>> requestCode(@RequestParam String phone) {
        pythonExecutionService.executeScript(
                "auth_session.py",
                "AUTH: Request code for " + phone,
                Map.of("SESSION_NAME", "2000000001"),
                "--phone", phone
        ).subscribe();
        return ResponseEntity.ok(Map.of(
                "status", "started",
                "message", "Code request sent to Docker. Check app logs — should see 'Code sent to " + phone + "'"
        ));
    }

    /**
     * Manual trigger: runs the full Python workflow (seed → scan → discover → join → mute → report).
     */
    @GetMapping("/workflow/run")
    public ResponseEntity<Map<String, String>> triggerWorkflow() {
        pythonExecutionService.executeFullWorkflow().subscribe();
        return ResponseEntity.ok(Map.of(
                "status", "started",
                "message", "Full Python workflow started. Check python-integration.log for progress."
        ));
    }

    /**
     * Step 2: confirms the code (and optional 2FA password).
     * Starts Docker in background and returns immediately.
     */
    @GetMapping("/confirm")
    public ResponseEntity<Map<String, String>> confirmCode(
            @RequestParam String phone,
            @RequestParam String code,
            @RequestParam(required = false) String password) {
        String[] extraArgs = password != null
                ? new String[]{"--phone", phone, "--code", code, "--password", password}
                : new String[]{"--phone", phone, "--code", code};
        pythonExecutionService.executeScript(
                "auth_session.py",
                "AUTH: Confirm code for " + phone,
                Map.of("SESSION_NAME", "2000000001"),
                extraArgs
        ).subscribe();
        return ResponseEntity.ok(Map.of(
                "status", "started",
                "message", "Confirm started. Check app logs for 'Authorized successfully' or errors."
        ));
    }
}
