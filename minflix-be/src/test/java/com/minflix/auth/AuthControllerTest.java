package com.minflix.auth;

import com.minflix.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtService.class, ApiExceptionHandler.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-0123456789abcdef0123456789",
        "app.cors-allowed-origin=http://localhost:5173"})
class AuthControllerTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JwtService jwt;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @MockitoBean
    private UserRepository users;

    @Test
    void registerHashesPasswordAndReturnsTokenForNewUser() throws Exception {
        when(users.save(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            assertThat(u.getEmail()).isEqualTo("a@x.io");
            assertThat(passwordEncoder.matches("pw", u.getPasswordHash())).isTrue();
            return new User("user-1", u.getEmail(), u.getPasswordHash());
        });

        MvcResult result = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@x.io\",\"password\":\"pw\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(jwt.verify(token(result))).isEqualTo("user-1");
    }

    @Test
    void registerWithExistingEmailIs409() throws Exception {
        when(users.save(any())).thenThrow(new DuplicateKeyException("dup"));

        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@x.io\",\"password\":\"pw\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Email already registered"));
    }

    @Test
    void registerWithBlankFieldsIs400() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\" \",\"password\":\"pw\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("email and password are required"));

        verify(users, never()).save(any());
    }

    @Test
    void malformedBodyIs400() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request body"));
    }

    @Test
    void loginWithCorrectPasswordReturnsToken() throws Exception {
        when(users.findByEmail("a@x.io"))
                .thenReturn(Optional.of(new User("user-1", "a@x.io", passwordEncoder.encode("pw"))));

        MvcResult result = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@x.io\",\"password\":\"pw\"}"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(jwt.verify(token(result))).isEqualTo("user-1");
    }

    @Test
    void loginWithWrongPasswordIs401() throws Exception {
        when(users.findByEmail("a@x.io"))
                .thenReturn(Optional.of(new User("user-1", "a@x.io", passwordEncoder.encode("pw"))));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@x.io\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid credentials"));
    }

    @Test
    void loginWithUnknownEmailIs401() throws Exception {
        when(users.findByEmail("nobody@x.io")).thenReturn(Optional.empty());

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@x.io\",\"password\":\"pw\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid credentials"));
    }

    private static String token(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString().replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }
}
