package com.tapin.notifications.email;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/** Selects the e-mail provider adapter with {@code tapin.mail.provider} ({@code smtp} default, or {@code log}). */
@Configuration(proxyBeanMethods = false)
class MailProviderConfig {

    @Bean
    @ConditionalOnMissingBean(EmailSender.class)
    @ConditionalOnProperty(name = "tapin.mail.provider", havingValue = "smtp", matchIfMissing = true)
    EmailSender smtpEmailSender(JavaMailSender mailSender, @Value("${tapin.mail.from}") String from) {
        return new SmtpEmailSender(mailSender, from);
    }

    @Bean
    @ConditionalOnMissingBean(EmailSender.class)
    @ConditionalOnProperty(name = "tapin.mail.provider", havingValue = "log")
    EmailSender loggingEmailSender() {
        return new LoggingEmailSender();
    }
}
