package com.healthclaim.security.service;

import com.healthclaim.security.domain.AppUser;
import com.healthclaim.security.domain.Role;
import com.healthclaim.security.dto.CreateUserRequest;
import com.healthclaim.security.dto.UserResponse;
import com.healthclaim.security.exception.UsernameAlreadyExistsException;
import com.healthclaim.security.repository.AppUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * User management (admin). Passwords are BCrypt-hashed; plaintext is never stored.
 */
@Service
public class UserService {

    private final AppUserRepository repository;
    private final PasswordEncoder passwordEncoder;

    public UserService(AppUserRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        if (repository.existsByUsername(request.username())) {
            throw new UsernameAlreadyExistsException(request.username());
        }
        AppUser user = new AppUser();
        user.setUsername(request.username());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(request.role());
        user.setEnabled(true);
        return toResponse(repository.save(user));
    }

    @Transactional(readOnly = true)
    public List<UserResponse> list() {
        return repository.findAll().stream().map(UserService::toResponse).toList();
    }

    /** Idempotent create used by the dev seeder. */
    @Transactional
    public void createIfAbsent(String username, String rawPassword, Role role) {
        if (!repository.existsByUsername(username)) {
            AppUser user = new AppUser();
            user.setUsername(username);
            user.setPasswordHash(passwordEncoder.encode(rawPassword));
            user.setRole(role);
            user.setEnabled(true);
            repository.save(user);
        }
    }

    private static UserResponse toResponse(AppUser u) {
        return new UserResponse(u.getId(), u.getUsername(), u.getRole().name(), u.isEnabled(), u.getCreatedAt());
    }
}
