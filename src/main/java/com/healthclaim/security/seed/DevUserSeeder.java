package com.healthclaim.security.seed;

import com.healthclaim.security.domain.Role;
import com.healthclaim.security.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Seeds default users for local development only (never in prod). Credentials are
 * synthetic and configurable via env; create real users via the admin API in
 * production.
 */
@Component
@Profile({"dev", "demo"})
public class DevUserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevUserSeeder.class);

    private final UserService userService;
    private final String adminPassword;
    private final String reviewerPassword;
    private final String userPassword;
    private final String operatorPassword;

    public DevUserSeeder(UserService userService,
                         @Value("${SEED_ADMIN_PASSWORD:admin-dev-password}") String adminPassword,
                         @Value("${SEED_REVIEWER_PASSWORD:reviewer-dev-password}") String reviewerPassword,
                         @Value("${SEED_USER_PASSWORD:user-dev-password}") String userPassword,
                         @Value("${SEED_OPERATOR_PASSWORD:}") String operatorPassword) {
        this.userService = userService;
        this.adminPassword = adminPassword;
        this.reviewerPassword = reviewerPassword;
        this.userPassword = userPassword;
        this.operatorPassword = operatorPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        userService.createIfAbsent("admin", adminPassword, Role.ADMIN);
        userService.createIfAbsent("reviewer", reviewerPassword, Role.REVIEWER);
        userService.createIfAbsent("user", userPassword, Role.USER);
        // Public demo: a private admin that may change shared data (DemoWriteGuard). Never seeded without a password.
        if (operatorPassword != null && !operatorPassword.isBlank()) {
            userService.createIfAbsent("operator", operatorPassword, Role.ADMIN);
        }
        log.info("Dev users ensured: admin (ADMIN), reviewer (REVIEWER), user (USER). "
                + "Override passwords via SEED_* env vars. DEV ONLY.");
    }
}
