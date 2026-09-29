package com.ticketflow.auth;

import com.ticketflow.user.UserResponse;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank(message = "Informe o nome.") @Size(max = 100, message = "Nome muito longo.") String name,
            @NotBlank(message = "Informe o e-mail.") @Email(message = "E-mail inválido.")
            @Size(max = 255, message = "E-mail muito longo.") String email,
            @NotBlank(message = "Informe a senha.")
            @Size(min = 8, max = 64, message = "A senha deve ter entre 8 e 64 caracteres.") String password) {
    }

    public record LoginRequest(
            @NotBlank(message = "Informe o e-mail.") String email,
            @NotBlank(message = "Informe a senha.") String password) {
    }

    public record AuthResponse(String token, UserResponse user) {
    }
}
