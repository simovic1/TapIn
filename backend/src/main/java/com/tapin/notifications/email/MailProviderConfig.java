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
    @Bean
    @ConditionalOnMissingBean(EmailSender.class)
    @ConditionalOnProperty(name = "tapin.mail.provider", havingValue = "gmail")
    EmailSender gmailEmailSender(@Value("${tapin.mail.from}") String from,
            @Value("${GMAIL_CLIENT_ID}") String clientId,
            @Value("${GMAIL_CLIENT_SECRET}") String clientSecret,
            @Value("${GMAIL_REFRESH_TOKEN}") String refreshToken) {
        return new GmailEmailSender(from, clientId, clientSecret, refreshToken);
    }
}
