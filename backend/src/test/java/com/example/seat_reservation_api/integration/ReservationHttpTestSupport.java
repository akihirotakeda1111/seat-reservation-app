package com.example.seat_reservation_api.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

final class ReservationHttpTestSupport {
    private static final String RESERVATIONS = "/api/reservations";
    private final ObjectMapper mapper;
    private final ReservationIntegrationTestSupport support;
    private final HttpClient client;
    private final int port;

    ReservationHttpTestSupport(ObjectMapper mapper, ReservationIntegrationTestSupport support,
            HttpClient client, int port) {
        this.mapper = mapper;
        this.support = support;
        this.client = client;
        this.port = port;
    }

    String body(LocalDate date, int seat) {
        return "{\"date\":\"%s\",\"seatNumber\":%d}".formatted(date, seat);
    }

    HttpResponse<String> post(LocalDate date, int seat, String user) throws Exception {
        return request("POST", RESERVATIONS, user, body(date, seat));
    }

    HttpResponse<String> delete(String id, String user) throws Exception {
        return request("DELETE", RESERVATIONS + "/" + id, user, null);
    }

    HttpResponse<String> request(String method, String path, String user, String body)
            throws Exception {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .timeout(Duration.ofSeconds(30));
        if (user != null) {
            builder.header("X-Demo-User", user);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        return client.send(
                builder.method(method,
                        body == null ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    JsonNode json(HttpResponse<String> response, int status, MediaType type) {
        assertThat(response.statusCode()).as("HTTP response: %s", response.body())
                .isEqualTo(status);
        assertThat(MediaType
                .parseMediaType(response.headers().firstValue("Content-Type").orElseThrow())
                .isCompatibleWith(type)).isTrue();
        return mapper.readTree(response.body());
    }

    void assertProblem(HttpResponse<String> response, int status, String path, String detail) {
        JsonNode root = json(response, status, MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(root.path("status").asInt()).isEqualTo(status);
        assertThat(root.path("title").asString())
                .isEqualTo(HttpStatus.valueOf(status).getReasonPhrase());
        assertThat(root.path("instance").asString()).isEqualTo(path);
        assertThat(root.path("detail").asString()).isEqualTo(detail);
    }

    UUID assertCreated(HttpResponse<String> response, LocalDate date, int seat, String user) {
        JsonNode root = json(response, 201, MediaType.APPLICATION_JSON);
        UUID id = UUID.fromString(root.path("id").asString());
        assertThat(root.path("date").asString()).isEqualTo(date.toString());
        assertThat(root.path("seatNumber").asInt()).isEqualTo(seat);
        assertThat(response.headers().firstValue("Location")).contains(RESERVATIONS + "/" + id);
        support.assertStoredReservation(id, date, seat, user);
        return id;
    }

    void assertDeleted(HttpResponse<String> response) {
        assertThat(response.statusCode()).as("HTTP response: %s", response.body()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
    }
}
