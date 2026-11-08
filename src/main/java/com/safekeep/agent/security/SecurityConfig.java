package com.safekeep.agent.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/** Users, roles and URL rules (decision D2, NFR-SEC-02). Event intake uses API keys instead. */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public UserDetailsService users(PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(
                User.withUsername("alice").password(encoder.encode("demo")).roles(Role.APP_USER.name()).build(),
                User.withUsername("sam").password(encoder.encode("demo")).roles(Role.SUPPORT_ENGINEER.name()).build(),
                User.withUsername("adam").password(encoder.encode("demo")).roles(Role.ADMIN.name()).build());
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.POST, "/api/events").permitAll()
                .requestMatchers("/login", "/css/**", "/js/**", "/favicon.ico", "/favicon.svg", "/error").permitAll()
                .requestMatchers("/api/health", "/api/metrics", "/health")
                    .hasAnyRole(Role.SUPPORT_ENGINEER.name(), Role.ADMIN.name())
                .requestMatchers("/api/applications/**", "/applications/**").hasRole(Role.ADMIN.name())
                .anyRequest().authenticated())
            // CSRF protects browser sessions. API key and Basic-auth clients send credentials in headers,
            // which a malicious site cannot make a browser add, so they are exempt.
            .csrf(c -> c.ignoringRequestMatchers(
                    PathPatternRequestMatcher.withDefaults().matcher("/api/events"),
                    request -> request.getHeader("Authorization") != null))
            .formLogin(f -> f.loginPage("/login").defaultSuccessUrl("/investigations", true).permitAll())
            .httpBasic(Customizer.withDefaults())
            .logout(l -> l.logoutSuccessUrl("/login?logout"))
            .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                    PathPatternRequestMatcher.withDefaults().matcher("/api/**")));
        return http.build();
    }
}
