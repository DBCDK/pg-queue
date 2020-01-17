/*
 * Copyright (C) 2020 DBC A/S (http://dbc.dk/)
 *
 * This is part of pg-queue-replayer
 *
 * pg-queue-replayer is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * pg-queue-replayer is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package dk.dbc.pgqueue.replayer;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 *
 * @author Morten Bøgeskov (mb@dbc.dk)
 */
public class Converters {

    /**
     * Turn a number and a unit into a number of milliseconds
     *
     * @param <T>       Exception type
     * @param duration  String containing number and unit
     * @param exception Supplier for exception, if duration cannot be processed
     * @return number of milliseconds
     * @throws T Exception in case of parse error
     */
    public static <T extends Exception> long durationToMS(String duration, Supplier<T> exception) throws T {
        String[] parts = duration.split("(?=\\D)", 2);
        long amount;
        try {
            amount = Long.parseUnsignedLong(parts[0].trim());
        } catch (NumberFormatException ex) {
            throw exception.get();
        }
        switch (parts[1].trim().toLowerCase(Locale.ROOT)) {
            case "ms":
                return amount;
            case "s":
                return TimeUnit.SECONDS.toMillis(amount);
            case "m":
                return TimeUnit.MINUTES.toMillis(amount);
            case "h":
                return TimeUnit.HOURS.toMillis(amount);
            case "d":
                return TimeUnit.DAYS.toMillis(amount);
            default:
                throw exception.get();
        }
    }
}
