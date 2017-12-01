/*
 * Copyright (C) 2017 DBC A/S (http://dbc.dk/)
 *
 * This is part of dbc-pg-queue-perftest
 *
 * dbc-pg-queue-perftest is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * dbc-pg-queue-perftest is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package dk.dbc.pgqueue.perftest;

import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author DBC {@literal <dbc.dk>}
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static final String PROGNAME = "java -jar pg-queue-perftest.jar";

    private void run(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: " + PROGNAME + " { load | test } ...");
            System.exit(1);
        } else if (args[0].equalsIgnoreCase("load")) {
            new Load(Arrays.copyOfRange(args, 1, args.length)).run();
        } else if (args[0].equalsIgnoreCase("test")) {
            new Test(Arrays.copyOfRange(args, 1, args.length)).run();
        }
    }

    public static void main(String[] args) {
        try {
            if (args.length == 0) {
                args = "load -d duser:dpass@localhost/data".split(" +");
            }
            new Main().run(args);
        } catch (ExitException ex) {
            String message = ex.getMessage();
            if (message != null) {
                System.err.println(message);
            }
            System.exit(ex.getCode());
        } catch (Exception ex) {
            log.error("Exception: {}", ex.getMessage());
            log.debug("Exception:", ex);
        }
    }
}
