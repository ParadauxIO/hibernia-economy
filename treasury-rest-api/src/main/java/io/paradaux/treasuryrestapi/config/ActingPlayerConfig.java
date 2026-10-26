package io.paradaux.treasuryrestapi.config;

import io.paradaux.treasuryrestapi.security.ActingPlayerInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers {@link ActingPlayerInterceptor} on the SERVICE-only admin surface (PAR-542). */
@Configuration
public class ActingPlayerConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ActingPlayerInterceptor()).addPathPatterns("/api/v1/admin/**");
    }
}
