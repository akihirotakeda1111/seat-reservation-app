package com.example.seat_reservation_api.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class SeatCacheControlFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String seatsPath = request.getContextPath() + "/api/seats";

        if ("GET".equals(request.getMethod()) && seatsPath.equals(request.getRequestURI())) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        }

        filterChain.doFilter(request, response);
    }
}
