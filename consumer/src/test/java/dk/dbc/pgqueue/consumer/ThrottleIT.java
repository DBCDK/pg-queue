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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 *
 * @author DBC {@literal <dbc.dk>}
 */
public class ThrottleIT {

    /**
     * Test of throttle method, of class Throttle.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void testThrottleReset() throws Exception {
        ArrayList<Instant> times = new ArrayList<>();
        ArrayList<Long> delayList = new ArrayList<>();

        Throttle throttle = new Throttle("3/500ms") {
            @Override
            void sleep(long delay) {
                delayList.add(delay);
                super.sleep(delay);
            }

        };

        new Thread(new Runnable() {

            void action() {
                throttle.throttle();
                synchronized (times) {
                    times.add(Instant.now());
                    System.out.println("times = " + times);
                    times.notifyAll();
                }
                throttle.register(false);
            }

            @Override
            public void run() {
                /* prime throttle */
                throttle.throttle();
                throttle.register(false);
                throttle.register(true);

                /* reset test values */
                times.clear();
                delayList.clear();

                Thread.yield();
                /* test */
                action();
                action();
                action();
                action();
                action();
            }
        }).start();

        synchronized (times) {
            while (times.size() != 3) {
                times.wait();
            }
        }
        Thread.sleep(100L);
        throttle.register(true);
        synchronized (times) {
            while (times.size() != 5) {
                times.wait();
            }
        }

        long origin = times.get(0).toEpochMilli();

        long[] delays = delayList.stream()
                .mapToLong(i -> i)
                .toArray();
        long[] fromOrigin = times.stream()
                .mapToLong(i -> i.toEpochMilli() - origin)
                .toArray();
        System.out.println("delays = " + Arrays.toString(delays));
        System.out.println("fromOrigin = " + Arrays.toString(fromOrigin));

        assertThat("1st delay <= 1", delays[0], lessThanOrEqualTo(1L));
        assertThat("1st invocation time <= 100", fromOrigin[0], lessThanOrEqualTo(100L));

        assertThat("2nd delay <= 1", delays[1], lessThanOrEqualTo(1L));
        assertThat("2nd invocation time <= 100", fromOrigin[1], lessThanOrEqualTo(100L));

        assertThat("3rd delay <= 1", delays[2], lessThanOrEqualTo(1L));
        assertThat("3rd invocation time <= 100", fromOrigin[2], lessThanOrEqualTo(100L));

        // Ensure delay is expected
        assertThat("4th delay >= 400", delays[3], greaterThanOrEqualTo(400L));
        assertThat("4th delay <= 500", delays[3], lessThanOrEqualTo(500L));
        // Ensure sleep in interrupted
        assertThat("4th invocation time >= 100", fromOrigin[3], greaterThanOrEqualTo(100L));
        assertThat("4th invocation time <= 200", fromOrigin[3], lessThanOrEqualTo(200L));

        // Ensure list has been cleared
        assertThat("5th delay <= 1", delays[4], lessThanOrEqualTo(1L));
    }
}
