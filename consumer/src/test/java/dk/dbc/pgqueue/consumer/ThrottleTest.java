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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 *
 * @author DBC {@literal <dbc.dk>}
 */
public class ThrottleTest {

    private static final Logger log = LoggerFactory.getLogger(ThrottleTest.class);
    private static final ObjectMapper OBJECT_MAPPER = new YAMLMapper();

    public static Stream<Arguments> tests() throws Exception {
        String name = ThrottleTest.class.getCanonicalName();
        URL url = ThrottleTest.class.getClassLoader().getResource(name);
        return Stream.of(new File(url.getPath()).list())
                .filter(s -> s.endsWith(".yaml"))
                .map(s -> Arguments.of(name + "/" + s));
    }

    @ParameterizedTest
    @MethodSource("tests")
    public void test(String resource) throws Exception {
        System.out.println(resource);
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resource)) {
            JsonNode test = OBJECT_MAPPER.readTree(is);
            AtomicLong time = new AtomicLong(1_000_000L);
            AtomicLong sleep = new AtomicLong();
            Throttle throttle = new Throttle(test.get("rules").asText()) {
                @Override
                long timeIs() {
                    return time.get();
                }

                @Override
                void sleep(long delay) {
                    time.addAndGet(delay);
                    sleep.addAndGet(delay);
                }

            };
            ArrayNode actions = (ArrayNode) test.get("test");
            for (int i = 0 ; i < actions.size() ; i++) {
                sleep.set(0);
                JsonNode action = actions.get(i);
                long expectedDelay = action.get("delay").asLong();
                boolean result = action.get("result").asBoolean();

                throttle.throttle();
                assertThat("Expected wait for Test #" + ( 1 + i ), expectedDelay, is(sleep.get()));
                throttle.register(result);
                JsonNode duration = action.get("duration");
                if (duration != null) {
                    time.addAndGet(duration.asLong());
                }
            }
        }
    }
}
