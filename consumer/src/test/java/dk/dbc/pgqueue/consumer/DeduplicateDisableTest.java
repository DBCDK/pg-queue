/*
 * Copyright (C) 2019 DBC A/S (http://dbc.dk/)
 *
 * This is part of pg-queue-consumer
 *
 * pg-queue-consumer is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * pg-queue-consumer is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package dk.dbc.pgqueue.consumer;

import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.*;

/**
 *
 * @author Morten Bøgeskov (mb@dbc.dk)
 */
public class DeduplicateDisableTest {

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void testTimeout() throws Exception {
        System.out.println("testTimeout");

        Iterator<Long> i = Arrays.asList(
                // CLOCK: // test action
                1000000L, // can

                1000001L, // create context
                1000201L, // close context (disable to 1201)
                1001200L, // can't
                1001201L, // can't
                1001202L, // can

                1001500L, // create context
                1001501L, // close context (no disable)
                1001502L, // can

                Long.MAX_VALUE
        ).iterator();
        DeduplicateDisable obj = new DeduplicateDisable(100, 1000) {
            @Override
            long time() {
                return i.next();
            }
        };

        assertThat(obj.canDeduplicate(), is(true));

        try (DeduplicateDisable.Context context = obj.context()) {
            // Hit timeout
        }
        assertThat(obj.canDeduplicate(), is(false));
        assertThat(obj.canDeduplicate(), is(false));
        assertThat(obj.canDeduplicate(), is(true));

        try (DeduplicateDisable.Context context = obj.context()) {
            // Not hit timeout
        }
        assertThat(obj.canDeduplicate(), is(true));

        assertThat(i.next(), is(Long.MAX_VALUE));
    }

}
