package dev.hmcodes.jrap.tenancy.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Production email adapter: activates automatically when {@code spring.mail.host} is
 * configured (Spring Boot's mail auto-configuration provides the JavaMailSender), and
 * takes precedence over the logging adapter. Delivery is best-effort — JRAP email is
 * notification, never authorisation — so a transport failure is logged loudly and the
 * calling workflow (registration, invitations, schedule notifications) carries on.
 */
@Component
@Primary
@ConditionalOnProperty(name = "spring.mail.host")
public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailSender(JavaMailSender mailSender,
                           @Value("${jrap.mail.from:${spring.mail.username:noreply@jrap.local}}") String from) {
        this.mailSender = mailSender;
        this.from = from;
        log.info("Email: SMTP transport active, from={}", from);
    }

    @Override
    public void send(String toEmail, String subject, String body) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(toEmail);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
        } catch (Exception e) {
            log.error("EMAIL DELIVERY FAILED to={} subject={}: {}", toEmail, subject, e.getMessage());
        }
    }
}
