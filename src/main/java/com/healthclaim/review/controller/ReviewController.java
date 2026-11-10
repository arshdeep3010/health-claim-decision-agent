package com.healthclaim.review.controller;

import com.healthclaim.common.response.PagedResponse;
import com.healthclaim.review.dto.ReviewResponse;
import com.healthclaim.review.dto.SubmitReviewRequest;
import com.healthclaim.review.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Human review", description = "Reviewer decisions and AI/human agreement tracking")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @PostMapping("/claims/{claimId}/review")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Submit a human review for a claim",
            description = "APPROVE/REJECT finalizes the claim; REQUEST_INFORMATION leaves it in review. "
                    + "The reviewer identity is taken from the authenticated principal.")
    public ReviewResponse submit(@PathVariable UUID claimId,
                                 @Valid @RequestBody SubmitReviewRequest request,
                                 Authentication authentication) {
        return reviewService.submit(claimId, request, authentication.getName());
    }

    @GetMapping("/reviews/{reviewId}")
    @Operation(summary = "Get a review by id")
    public ReviewResponse get(@PathVariable UUID reviewId) {
        return reviewService.get(reviewId);
    }

    @GetMapping("/reviews")
    @Operation(summary = "List reviews (optionally filtered by reviewerId)")
    public PagedResponse<ReviewResponse> list(
            @RequestParam(required = false) String reviewerId,
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return reviewService.list(reviewerId, pageable);
    }
}
