package com.tapin.notifications;

import com.tapin.notifications.email.EmailMessage;
import com.tapin.notifications.email.EmailSender;
import com.tapin.notifications.email.SendResult;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** Test provider: records every message and answers with a programmable result. */
class ScriptedEmailSender implements EmailSender {

    final List<EmailMessage> sent = new CopyOnWriteArrayList<>();
    private volatile Function<EmailMessage, SendResult> behaviour = message -> new SendResult.Accepted("ok");
    private volatile Duration latency = Duration.ZERO;

    void answer(Function<EmailMessage, SendResult> behaviour) {
        this.behaviour = behaviour;
    }

    void latency(Duration latency) {
        this.latency = latency;
    }

    void reset() {
        sent.clear();
        behaviour = message -> new SendResult.Accepted("ok");
        latency = Duration.ZERO;
    }

    @Override
    public SendResult send(EmailMessage message) {
        sent.add(message);
        if (!latency.isZero()) {
            try {
                Thread.sleep(latency);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return behaviour.apply(message);
    }

    @Override
    public String name() {
        return "scripted";
    }
}
