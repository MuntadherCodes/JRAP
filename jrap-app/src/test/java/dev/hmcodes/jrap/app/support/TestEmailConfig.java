package dev.hmcodes.jrap.app.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestEmailConfig {

    @Bean
    @Primary
    public RecordingEmailSender recordingEmailSender() {
        return new RecordingEmailSender();
    }
}
