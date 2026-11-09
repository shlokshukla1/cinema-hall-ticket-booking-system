package com.cinemahall.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI cinemaOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Cinema Hall Ticket Booking API")
                        .version("1.0.0")
                        .description("""
                                Customer, staff and admin APIs. Log in through POST /api/auth/login and paste the \
                                returned token into Authorize. Errors always use the ErrorResponse shape, with a \
                                stable `code` such as SEATS_UNAVAILABLE or HOLD_EXPIRED."""))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
