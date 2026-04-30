package com.merakilabs.taskq.worker.runtime.handlers;

import com.merakilabs.taskq.core.domain.Job;
import com.merakilabs.taskq.core.port.HandlerOutcome;
import com.merakilabs.taskq.core.port.JobHandler;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reference handler used by demos + integration tests. The payload encodes the desired outcome:
 * <ul>
 *   <li>{@code {"outcome":"success"}} → SUCCEEDED</li>
 *   <li>{@code {"outcome":"retry"}} → retryable failure</li>
 *   <li>{@code {"outcome":"fail"}} → terminal fail (goes to DLQ on first attempt)</li>
 *   <li>{@code {"outcome":"flap","passOn":N}} → fails until {@code job.attempt} reaches N, then succeeds</li>
 * </ul>
 */
@Component
public class EchoJobHandler implements JobHandler {

    private static final Logger LOG = LoggerFactory.getLogger(EchoJobHandler.class);

    @Override
    public String type() {
        return "echo";
    }

    @Override
    public HandlerOutcome handle(final Job job) {
        final String payload = new String(job.payload(), StandardCharsets.UTF_8);
        LOG.debug("EchoJobHandler job={} attempt={} payload={}", job.id(), job.attempt(), payload);

        if (payload.contains("\"outcome\":\"retry\"")) {
            return HandlerOutcome.retry("requested retry");
        }
        if (payload.contains("\"outcome\":\"fail\"")) {
            return HandlerOutcome.fail("requested fail");
        }
        if (payload.contains("\"outcome\":\"flap\"")) {
            final int passOn = parsePassOn(payload);
            if (job.attempt() < passOn) {
                return HandlerOutcome.retry("flap until attempt " + passOn);
            }
        }
        return HandlerOutcome.success();
    }

    private static int parsePassOn(final String payload) {
        final int idx = payload.indexOf("\"passOn\":");
        if (idx < 0) {
            return 1;
        }
        int i = idx + "\"passOn\":".length();
        while (i < payload.length() && Character.isWhitespace(payload.charAt(i))) {
            i++;
        }
        int j = i;
        while (j < payload.length() && Character.isDigit(payload.charAt(j))) {
            j++;
        }
        if (j == i) {
            return 1;
        }
        return Integer.parseInt(payload.substring(i, j));
    }
}
