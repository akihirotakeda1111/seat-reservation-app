package com.example.seat_reservation_api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.ArrayList;
import java.util.Collection;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

        @Bean
        SecurityFilterChain securityFilterChain(
                        HttpSecurity http,
                        JwtAuthenticationConverter converter,
                        AuthenticationEntryPoint entryPoint,
                        AccessDeniedHandler deniedHandler) throws Exception {

                return http.csrf(csrf -> csrf.disable())
                                .sessionManagement(session -> session
                                                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                                .requestCache(cache -> cache.disable())
                                .formLogin(form -> form.disable())
                                .httpBasic(basic -> basic.disable())
                                .logout(logout -> logout.disable())
                                .authorizeHttpRequests(auth -> auth
                                                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                                                .requestMatchers("/api/**").authenticated()
                                                .anyRequest().denyAll())
                                .exceptionHandling(errors -> errors
                                                .authenticationEntryPoint(entryPoint)
                                                .accessDeniedHandler(deniedHandler))
                                .oauth2ResourceServer(resourceServer -> resourceServer
                                                .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                                                .authenticationEntryPoint(entryPoint)
                                                .accessDeniedHandler(deniedHandler))
                                .build();
        }

        @Bean
        @Profile("!demo")
        JwtDecoder jwtDecoder(
                        @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
                        @Value("${app.security.cognito-client-id}") String clientId) {

                NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuer).build();

                decoder.setJwtValidator(CognitoJwtValidators.create(issuer, clientId));

                return decoder;
        }

        @Bean
        JwtAuthenticationConverter jwtAuthenticationConverter() {
                JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();

                JwtAuthenticationConverter converter = new JwtAuthenticationConverter();

                converter.setJwtGrantedAuthoritiesConverter(jwt -> {
                        Collection<GrantedAuthority> authorities = new ArrayList<>(scopes.convert(jwt));

                        Object groups = jwt.getClaims().get("cognito:groups");

                        if (groups instanceof Collection<?> values
                                        && values.stream().allMatch(String.class::isInstance)
                                        && values.contains("ADMINS")) {
                                authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                        }

                        return authorities;
                });

                return converter;
        }
}