package dev.hmcodes.jrap.app.support;

import dev.hmcodes.jrap.tenancy.service.EmailSender;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Captures outbound mail so tests can extract verification/invitation tokens. */
public class RecordingEmailSender implements EmailSender {

    public record Sent(String to, String subject, String body) {}

    private final List<Sent> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(String toEmail, String subject, String body) {
        sent.add(new Sent(toEmail, subject, body));
    }

    public boolean hasMessage(String to, String subjectContains) {
        return sent.stream().anyMatch(m -> m.to().equals(to)
                && m.subject().contains(subjectContains));
    }

    public String lastTokenFor(String email) {
        Pattern pattern = Pattern.compile("token=([A-Za-z0-9_-]+)");
        for (int i = sent.size() - 1; i >= 0; i--) {
            if (sent.get(i).to().equals(email)) {
                Matcher matcher = pattern.matcher(sent.get(i).body());
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        }
        throw new IllegalStateException("No token email captured for " + email);
    }
}
