package com.zaalime.orders.service.auth;

import com.zaalime.orders.service.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Makes sure there is one admin to manage the catalog. Credentials come from env / config. */
@Component
public class AdminAccountInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminAccountInitializer.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties props;

    public AdminAccountInitializer(UserRepository users, PasswordEncoder passwordEncoder, AppProperties props) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        String username = props.admin().username();
        if (users.existsByUsernameIgnoreCase(username)) {
            return;
        }
        try {
            users.save(new UserAccount(username, passwordEncoder.encode(props.admin().password()), "ADMIN"));
            log.info("Created admin account '{}'", username);
        } catch (DataIntegrityViolationException e) {
            // another instance created it at the same time - fine
        }
    }
}
