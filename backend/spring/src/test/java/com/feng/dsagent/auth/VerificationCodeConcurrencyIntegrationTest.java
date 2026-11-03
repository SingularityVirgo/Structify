package com.feng.dsagent.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.feng.dsagent.common.exception.BizException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.feng.dsagent.auth.domain.VerificationCodeDelivery;
import com.feng.dsagent.auth.support.VerificationCodeManager;
@SpringBootTest
class VerificationCodeConcurrencyIntegrationTest {

    @Autowired
    private VerificationCodeManager codes;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void onlyOneConcurrentRequestCanConsumeTheSameVerificationCode() throws Exception {
        String email = "concurrent-code@example.com";
        jdbc.update("DELETE FROM mail_configurations");
        jdbc.update("DELETE FROM verification_codes WHERE email = ?", email);
        VerificationCodeDelivery delivery = codes.request(email, "reset");
        assertThat(delivery.developmentCode()).isNotBlank();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> consumeAfterStart(ready, start, email, delivery.developmentCode()));
            Future<Boolean> second = executor.submit(() -> consumeAfterStart(ready, start, email, delivery.developmentCode()));
            ready.await();
            start.countDown();

            int successes = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
            assertThat(successes).isEqualTo(1);
        }
    }

    private boolean consumeAfterStart(
        CountDownLatch ready,
        CountDownLatch start,
        String email,
        String code
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            codes.consume(email, "reset", code);
            return true;
        } catch (BizException error) {
            return false;
        }
    }
}
