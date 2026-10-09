package com.minflix.video;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/videos")
@RequiredArgsConstructor
public class VideoController {

    private final VideoService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateVideoResponse create(@AuthenticationPrincipal String userId, @RequestBody CreateVideoRequest req) {
        return service.create(userId, req);
    }

    @PostMapping("/{id}/complete")
    public VideoResponse complete(@AuthenticationPrincipal String userId, @PathVariable String id) {
        return VideoResponse.of(service.complete(userId, id));
    }

    @GetMapping
    public List<VideoResponse> list(@AuthenticationPrincipal String userId) {
        return service.list(userId).stream().map(VideoResponse::of).toList();
    }

    @GetMapping("/{id}")
    public VideoResponse get(@AuthenticationPrincipal String userId, @PathVariable String id) {
        return VideoResponse.of(service.get(userId, id));
    }
}
