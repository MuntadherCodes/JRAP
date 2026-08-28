package dev.hmcodes.jrap.tenancy.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Development adapter: writes outbound mail to the application log instead of sending it. */
@Component
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(String toEmail, String subject, String body) {
        log.info("EMAIL to={} subject={} body={}", toEmail, subject, body);
    }
}
