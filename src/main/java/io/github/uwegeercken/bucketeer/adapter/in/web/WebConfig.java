package io.github.uwegeercken.bucketeer.adapter.in.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ApiTokenInterceptor apiTokenInterceptor;
    private final SelectedServerInterceptor selectedServerInterceptor;

    public WebConfig(ApiTokenInterceptor apiTokenInterceptor,
                     SelectedServerInterceptor selectedServerInterceptor) {
        this.apiTokenInterceptor       = apiTokenInterceptor;
        this.selectedServerInterceptor = selectedServerInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiTokenInterceptor).addPathPatterns("/api/v1/**");
        // /api/v1/** is the stateless terminal API - it must not create sessions.
        registry.addInterceptor(selectedServerInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/css/**", "/js/**", "/favicon.ico", "/error", "/api/v1/**");
    }
}