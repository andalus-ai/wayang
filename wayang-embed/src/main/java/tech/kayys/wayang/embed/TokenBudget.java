package tech.kayys.wayang.embed;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * A cap on the model tokens a release spends per UTC day ({@code WAYANG_LLM_DAILY_TOKENS}; unset or 0 means no cap). Checked before every model
 * call and charged after it with what the provider reported (an estimate of one token per four characters when it reported nothing). A call
 * already in flight may overshoot the cap once. In memory and per process: a restart starts the day at zero.
 */
public final class TokenBudget {

    private final long limit;
    private final Clock clock;
    private LocalDate day;
    private long used;

    public TokenBudget(long limit, Clock clock) {
        this.limit = Math.max(0, limit);
        this.clock = clock;
    }

    /** @return the budget for a setting such as "200000", or one with no cap when the setting is absent; @throws IllegalArgumentException when it is not a number */
    public static TokenBudget parse(String setting, Clock clock) {
        if (setting == null || setting.isBlank()) {
            return new TokenBudget(0, clock);
        }
        try {
            return new TokenBudget(Long.parseLong(setting.strip()), clock);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("WAYANG_LLM_DAILY_TOKENS must be a whole number of tokens");
        }
    }

    private void roll() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (!today.equals(day)) {
            day = today;
            used = 0;
        }
    }

    /** @throws IllegalStateException when today's cap is used up */
    public synchronized void check() {
        roll();
        if (limit > 0 && used >= limit) {
            throw new IllegalStateException("The daily limit of " + limit + " model tokens is used up; it resets at 00:00 UTC");
        }
    }

    public synchronized void charge(long tokens) {
        roll();
        used += Math.max(0, tokens);
    }

    public synchronized long usedToday() {
        roll();
        return used;
    }
}
