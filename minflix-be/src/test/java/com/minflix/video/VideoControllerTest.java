package com.minflix.video;

import com.minflix.ApiExceptionHandler;
import com.minflix.auth.JwtService;
import com.minflix.auth.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VideoController.class)
@Import({SecurityConfig.class, JwtService.class, ApiExceptionHandler.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-0123456789abcdef0123456789",
        "app.cors-allowed-origin=http://localhost:5173"})
class VideoControllerTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JwtService jwt;
    @MockitoBean
    private VideoService service;

    @Test
    void requestWithoutTokenIs401Json() throws Exception {
        mvc.perform(get("/api/videos"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Unauthorized"));

        verifyNoInteractions(service);
    }

    @Test
    void requestWithInvalidTokenIs401() throws Exception {
        mvc.perform(get("/api/videos").header("Authorization", "Bearer garbage"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    void createPassesTokenSubjectAsOwnerAndReturns201() throws Exception {
        when(service.create("user-1", new CreateVideoRequest("T", "a.mp4", "video/mp4")))
                .thenReturn(new CreateVideoResponse("v1", "http://upload"));

        mvc.perform(post("/api/videos").header("Authorization", bearer("user-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"T\",\"filename\":\"a.mp4\",\"contentType\":\"video/mp4\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.videoId").value("v1"))
                .andExpect(jsonPath("$.uploadUrl").value("http://upload"));
    }

    @Test
    void videoResponseHasContractShapeWithoutRawKey() throws Exception {
        when(service.get("user-1", "v1")).thenReturn(video());

        mvc.perform(get("/api/videos/v1").header("Authorization", bearer("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("v1"))
                .andExpect(jsonPath("$.title").value("T"))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.ownerId").value("user-1"))
                .andExpect(jsonPath("$.masterPlaylistUrl").value("http://cdn/hls/v1/master.m3u8"))
                .andExpect(jsonPath("$.durationSec").value(6.5))
                .andExpect(jsonPath("$.createdAt").value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.rawKey").doesNotExist());
    }

    @Test
    void listReturnsServiceResultInOrder() throws Exception {
        Video other = video();
        other.setId("v2");
        when(service.list("user-1")).thenReturn(List.of(video(), other));

        mvc.perform(get("/api/videos").header("Authorization", bearer("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("v1"))
                .andExpect(jsonPath("$[1].id").value("v2"));
    }

    @Test
    void completeReturnsVideo() throws Exception {
        Video v = video();
        v.setStatus(VideoStatus.PROCESSING);
        when(service.complete("user-1", "v1")).thenReturn(v);

        mvc.perform(post("/api/videos/v1/complete").header("Authorization", bearer("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    @Test
    void serviceErrorsBecomeMessageBodies() throws Exception {
        when(service.complete("user-2", "v1")).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden"));
        when(service.get("user-1", "missing")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));

        mvc.perform(post("/api/videos/v1/complete").header("Authorization", bearer("user-2")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Forbidden"));
        mvc.perform(get("/api/videos/missing").header("Authorization", bearer("user-1")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Not found"));
    }

    @Test
    void corsPreflightAllowsConfiguredOriginOnly() throws Exception {
        mvc.perform(options("/api/videos")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));

        mvc.perform(options("/api/videos")
                        .header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    private String bearer(String userId) {
        return "Bearer " + jwt.issue(userId);
    }

    private static Video video() {
        Video v = new Video();
        v.setId("v1");
        v.setOwnerId("user-1");
        v.setTitle("T");
        v.setStatus(VideoStatus.READY);
        v.setRawKey("raw/v1/original.mp4");
        v.setMasterPlaylistUrl("http://cdn/hls/v1/master.m3u8");
        v.setDurationSec(6.5);
        v.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return v;
    }
}
