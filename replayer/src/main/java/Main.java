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

import dk.dbc.pgqueue.replayer.CliArguments;
import dk.dbc.pgqueue.replayer.ExitException;
import dk.dbc.pgqueue.replayer.play.Play;
import dk.dbc.pgqueue.replayer.record.Record;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Morten Bøgeskov (mb@dbc.dk)
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        try {
            if (args.length == 0) {
                mainUsage();
                System.exit(1);
            }
            switch (args[0]) {
                case "-h":
                case "--help":
                    mainUsage();
                    System.exit(0);
                    break;
                case "play":
                    Play.main(Arrays.copyOfRange(args, 1, args.length));
                    break;
                case "record":
                    Record.main(Arrays.copyOfRange(args, 1, args.length));
                    break;
                default:
                    mainUsage();
                    System.exit(1);
                    break;
            }
        } catch (ExitException ex) {
            String message = ex.getMessage();
            if (message != null)
                log.error(message);
            System.exit(ex.getStatusCode());
        } catch (SQLException | IOException | RuntimeException ex) {
            Throwable e = ex;
            while (e.getMessage() == null && e.getCause() != null) {
                e = e.getCause();
            }
            System.err.println(e.getMessage());
            System.err.println(e);
            System.exit(1);
        }
    }

    private static void mainUsage() {
        System.err.println("Usage: " + CliArguments.executable() + " { --help | record | play } ...");
        System.err.println("");
        System.err.println("    --help  This lot");
        System.err.println("    record  Record from queue into a CSV file");
        System.err.println("    play    Rlay a recorded CSV file against a queue consumer");
        System.err.println("");
        System.err.println("    play/record both takes --help");
        System.err.println("");
        System.err.println("  © DBC (http://dbc.dk/ | https://github.com/DBCDK/pg-queue)");
        System.err.println("");
    }
}
