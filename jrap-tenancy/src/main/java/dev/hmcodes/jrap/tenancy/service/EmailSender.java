package dev.hmcodes.jrap.tenancy.service;

/** Transactional email port (SRS §3.2.3). Production adapter (SMTP/provider) arrives with ops config. */
public interface EmailSender {

    void send(String toEmail, String subject, String body);
}
