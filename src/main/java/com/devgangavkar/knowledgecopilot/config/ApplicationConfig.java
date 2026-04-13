package com.devgangavkar.knowledgecopilot.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ApplicationProperties.class)
public class ApplicationConfig {
}
