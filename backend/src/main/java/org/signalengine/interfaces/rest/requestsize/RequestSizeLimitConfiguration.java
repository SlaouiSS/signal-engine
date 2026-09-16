package org.signalengine.interfaces.rest.requestsize;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers {@link RequestSizeLimitFilter} across the whole servlet context, ordered very early so
 * an oversized request is rejected before any other filter or the {@code DispatcherServlet} reads
 * its body (composition root — CLAUDE.md Section 9).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RequestSizeLimitProperties.class)
class RequestSizeLimitConfiguration {

  @Bean
  FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilter(
      RequestSizeLimitProperties properties) {
    FilterRegistrationBean<RequestSizeLimitFilter> registration =
        new FilterRegistrationBean<>(new RequestSizeLimitFilter(properties.maxRequestBytes()));
    registration.addUrlPatterns("/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
    registration.setName("requestSizeLimitFilter");
    return registration;
  }
}
