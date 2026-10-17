package com.tapin.notifications.push;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Web push sender when VAPID keys are configured; otherwise a sender that refuses every message (push messages are
 * not even queued then, see {@code PushSubscriptions#enabled()}).
 */
@Configuration(proxyBeanMethods = false)
class PushProviderConfig {

    @Bean
    @ConditionalOnMissingBean(PushSender.class)
    PushSender pushSender(PushProperties properties, Clock clock) {
        if (!properties.enabled()) {
            LoggerFactory.getLogger(PushProviderConfig.class)
                    .info("Web push disabled (VAPID_PUBLIC_KEY / VAPID_PRIVATE_KEY not set); member notifications remain available in the inbox");
            return (target, payload, ttl) -> new PushResult.PermanentFailure("Web push is not configured");
        }
        return new WebPushSender(properties, clock);
    }
}
