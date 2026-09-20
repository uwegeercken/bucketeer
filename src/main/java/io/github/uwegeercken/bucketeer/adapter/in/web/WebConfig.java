package io.github.uwegeercken.bucketeer.adapter.in.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ApiTokenInterceptor apiTokenInterceptor;

    public WebConfig(ApiTokenInterceptor apiTokenInterceptor) {
        this.apiTokenInterceptor = apiTokenInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiTokenInterceptor).addPathPatterns("/api/v1/**");
    }
}