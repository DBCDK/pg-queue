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
import dk.dbc.parameterized.arguments.from.resources.ParameterizedArgumentsFromResources;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.*;

/**
 *
 * @author DBC {@literal <dbc.dk>}
 */
@RunWith(Parameterized.class)
public class ThrottleTest {

    private static final Logger log = LoggerFactory.getLogger(ThrottleTest.class);
    private static final ObjectMapper OBJECT_MAPPER = new YAMLMapper();

    private final String resource;
    private final JsonNode test;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> tests() throws Exception {
        return ParameterizedArgumentsFromResources
                .builder(ThrottleTest.class)
                .filter(Pattern.compile("\\.ya?ml$"))
                .build();
    }

    public ThrottleTest(String resource) throws IOException {
        this.resource = resource;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resource)) {
            this.test = OBJECT_MAPPER.readTree(is);
        }
    }

    @Test
    public void test() {
        System.out.println(resource);
//        AtomicLong time = new AtomicLong(System.currentTimeMillis());
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
            int expectedDelay = action.get("delay").asInt();
            boolean result = action.get("result").asBoolean();

            throttle.throttle();
            assertEquals("Expected wait for Test #" + (1 + i), expectedDelay, sleep.get());
            throttle.register(result);
            JsonNode duration = action.get("duration");
            if(duration != null) {
                time.addAndGet(duration.asLong());
            }
        }
    }

}
