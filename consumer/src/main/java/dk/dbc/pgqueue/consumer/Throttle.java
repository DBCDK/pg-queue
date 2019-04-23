/*
 * Copyright (C) 2017 DBC A/S (http://dbc.dk/)
 *
 * This is part of dbc-pg-queue-consumer
 *
 * dbc-pg-queue-consumer is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * dbc-pg-queue-consumer is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package dk.dbc.pgqueue.consumer;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * System for throttling errors.
 * <p>
 * Register an error, and the rules determine for how long you're supposed to
 * sleep, before doing anything
 *
 * @author DBC {@literal <dbc.dk>}
 */
class Throttle {

    private static final Logger log = LoggerFactory.getLogger(Throttle.class);

    private static final Pattern RULE_PATTERN = Pattern.compile("([1-9]\\d*)/([1-9]\\d*)?(ms|s|m|h)(!?)");
    private final ArrayList<Rule> rules;

    /**
     *
     * @param ruleSet        rules for delaying
     * @param resetOnSuccess
     */
    Throttle(String ruleSet) {
        this.rules = makeRules(ruleSet);
    }

    /**
     * Convert a rule spec into a list of rules
     *
     * @param ruleSet a number of rules
     * @return list of rules
     */
    private static ArrayList<Rule> makeRules(String ruleSet) {
        ArrayList<Rule> rules = new ArrayList<>();
        if (!ruleSet.trim().isEmpty()) {
            for (String rule : ruleSet.split("[^0-9a-zA-Z/]+")) {
                Matcher matcher = RULE_PATTERN.matcher(rule);
                if (!matcher.matches()) {
                    throw new IllegalArgumentException("Invalid rule: " + rule);
                }
                int max = Integer.parseUnsignedInt(matcher.group(1));
                long periods = 1;
                if (matcher.group(2) != null) {
                    periods = Long.parseUnsignedLong(matcher.group(2));
                }
                long timeout = toMs(periods, matcher.group(3));
                boolean resetOnSuccess = matcher.group(4).isEmpty();
                rules.add(new Rule(max, timeout, resetOnSuccess));
            }
        }
        return rules;
    }

    /**
     * Convert a number of time units (ms/s/m/h) into milliseconds
     *
     * @param periods number of periods
     * @param type    period type
     * @return number of milliseconds
     */
    private static long toMs(long periods, String type) {
        long timeout;
        switch (type) {
            case "ms":
                timeout = TimeUnit.MILLISECONDS.toMillis(periods);
                break;
            case "s":
                timeout = TimeUnit.SECONDS.toMillis(periods);
                break;
            case "m":
                timeout = TimeUnit.MINUTES.toMillis(periods);
                break;
            case "h":
                timeout = TimeUnit.HOURS.toMillis(periods);
                break;
            default:
                throw new IllegalStateException("INTERNAL LOGIC ERROR");
        }
        return timeout;
    }

    /**
     * The throttle
     * <p>
     * This function returns when it is valid to run
     */
    synchronized void throttle() {
        for (;;) {
            long sleepFor = 0;
            long now = timeIs();
            for (Rule rule : rules) {
                sleepFor = Long.max(sleepFor, rule.delay(now));
            }
            sleep(sleepFor);
            if (sleepFor == 0) {
                break;
            }
        }
    }

    /**
     * Register success/failure of a function
     *
     * @param success if this was a success
     */
    synchronized void register(boolean success) {
        long now = timeIs();
        if (success) {
            for (Rule rule : rules) {
                rule.reset();
            }
            notifyAll();
        } else {
            for (Rule rule : rules) {
                rule.register(now);
            }
        }
    }

    /**
     * Get current time in ms
     *
     * @return time value with arbitrary origin
     */
    long timeIs() {
        return System.currentTimeMillis();
    }

    /**
     * Actual sleep function
     * <p>
     * this is called in synchronized context, for immediate continuation if:
     * <pre>
     * - resetOnSucces is set
     * - a success is comming from another thread
     * </pre>
     */
    void sleep(long delay) {
        if (delay == 0) {
            return;
        }
        try {
            wait(delay);
        } catch (InterruptedException ex) {
            log.info("Throttle sleep interrupted");
        }
    }

    /**
     * Rule wrapper
     */
    private static class Rule {

        private final long[] timestamps;
        private final long timeout;
        private final boolean resetOnSuccess;
        private int pos; // this is where to read in the circular list

        /**
         * Make a circular list, for storing failure times
         *
         * @param max     st most how many failures
         * @param timeout in how man milliseconds
         */
        private Rule(int max, long timeout, boolean resetOnSuccess) {
            this.timestamps = new long[max];
            for (int i = 0 ; i < timestamps.length ; i++) {
                timestamps[i] = 0;
            }
            this.resetOnSuccess = resetOnSuccess;
            this.timeout = timeout;
            this.pos = 0;
        }

        /**
         * Clears all stored failure times (set time to
         * 1970-01-01T00:00:00.000Z)
         */
        private void reset() {
            if (resetOnSuccess) {
                for (int i = 0 ; i < timestamps.length ; i++) {
                    timestamps[i] = 0;
                }
            }
        }

        /**
         * Register a failure
         *
         * @param now when the failure occurred
         */
        private void register(long now) {
            timestamps[pos] = now + timeout;
            pos = pos + 1;
            if (pos == timestamps.length) {
                pos = 0;
            }
        }

        /**
         * How long to sleep for for this rule to be followed
         *
         * @param now the the request is made
         * @return how many ms (can be negative, which is don't wait)
         */
        private long delay(long now) {
//            System.out.println("timestamps = " + (timestamps[pos] - now) + " from " + java.util.Arrays.toString(java.util.Arrays.stream(timestamps).sorted().map(l -> l - now).toArray()));
            return timestamps[pos] - now;
        }
    }
}
