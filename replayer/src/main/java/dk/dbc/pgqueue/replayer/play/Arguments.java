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
package dk.dbc.pgqueue.replayer.play;

import dk.dbc.pgqueue.replayer.CliArguments;
import dk.dbc.pgqueue.replayer.Converters;
import dk.dbc.pgqueue.replayer.Database;
import dk.dbc.pgqueue.replayer.ExitException;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import org.apache.commons.cli.Option;

/**
 *
 * @author Morten Bøgeskov (mb@dbc.dk)
 */
public class Arguments extends CliArguments {

    private static final String TIMEOUT_FORMAT_ERROR = "Invalid timeout format ([number>0]{ms/s/m/h})";
    private static final String MAX_BEHIND_FORMAT_ERROR = "Invalid max-behind format ([number>=0]{ms/s/m/h})";
    private static final String MAX_BEHIND_DEFAULT = "100ms";
    public static final int EXIT_END_OF_INPUT = 50;
    public static final int EXIT_MAX_BEHIND = 51;
    public static final int EXIT_END_OF_DURATION = 0;

    private final Option database;
    private final Option input;
    private final Option maxBehind;
    private final Option queue;
    private final Option speed;
    private final Option timeout;

    public Arguments() {
        super();
        options.addOption(this.maxBehind = Option.builder("b")
                .longOpt("max-behind")
                .hasArg()
                .argName("DURATION")
                .desc("How we're allowed to be behind schedule before aborting (default: " + MAX_BEHIND_DEFAULT + ")")
                .build());
        options.addOption(this.database = Option.builder("d")
                .longOpt("database")
                .hasArg()
                .argName("URL")
                .desc("Solr-Doc-Store database")
                .required()
                .build());
        options.addOption(this.input = Option.builder("i")
                .longOpt("input")
                .hasArg()
                .argName("FILE")
                .desc("Csv input, - is stdin")
                .build());
        options.addOption(this.queue = Option.builder("c")
                .longOpt("consumer")
                .required()
                .hasArg()
                .argName("QUEUE")
                .desc("Which queue to harvest from")
                .build());
        options.addOption(this.speed = Option.builder("s")
                .longOpt("speed")
                .hasArg()
                .argName("PERCENT")
                .desc("How fast to replay the queue in percent (default: 100%)")
                .build());
        options.addOption(this.timeout = Option.builder("t")
                .longOpt("timeout")
                .hasArg()
                .argName("DURATION")
                .desc("How long to run for")
                .build());
    }

    @Override
    public List<String> extraHelp() {
        return Arrays.asList(
                "Exit codes:\n" +
                String.join("\n",
                            String.format(Locale.ROOT, "%6d - %s", EXIT_END_OF_DURATION, "Terminated by duration"),
                            String.format(Locale.ROOT, "%6d - %s", 1, "Terminated by input error"),
                            String.format(Locale.ROOT, "%6d - %s", EXIT_END_OF_INPUT, "Ran out of input before timeout"),
                            String.format(Locale.ROOT, "%6d - %s", EXIT_MAX_BEHIND, "Came too far behind in queueing (see --max-behind=DURATION)")),
                "When running with --duration=... it is a good idea to have a significanly longer recording." +
                " If you recording is n sec and your duration is n sec and you playback speed is 100%," +
                " then we cannot determine if we're at end of duration or input." +
                " Result is end of input error.",
                "All columns in the queue job should be convertable to/from strings" +
                " without dataloss for this to work."
        );
    }

    @Override
    public List<String> logPackages() {
        String className = getClass().getCanonicalName();
        String packageName = className.substring(0, Integer.max(0, className.lastIndexOf('.')));
        return Arrays.asList(packageName);
    }

    public DataSource getDataSource() {
        return Database.of(valueOfOption(database));
    }

    public long getDurationInMS() throws ExitException {
        if (!optionIsSet(timeout))
            return Long.MAX_VALUE; // Never abort
        String duration = valueOfOption(timeout);
        long ms = Converters.durationToMS(duration, () -> usage(TIMEOUT_FORMAT_ERROR));
        if (ms == 0)
            throw usage(TIMEOUT_FORMAT_ERROR);
        return ms;
    }

    public long getMaxBehindInMS() throws ExitException {
        String duration = valueOfOption(maxBehind, MAX_BEHIND_DEFAULT);
        return Converters.durationToMS(duration, () -> usage(MAX_BEHIND_FORMAT_ERROR));
    }

    public InputStream getInput() throws ExitException {
        String fileName = valueOfOption(input, "-");
        if (fileName.equals("-")) {
            return System.in;
        }
        try {
            return new FileInputStream(fileName);
        } catch (FileNotFoundException ex) {
            throw new ExitException(Arguments.EXIT_END_OF_INPUT, fileName + ": " + ex.getMessage());
        }
    }

    public String getQueue() {
        return valueOfOption(queue);
    }

    /**
     * Time stretch
     *
     * @return a factor with which to scale the offset in the file with
     * @throws ExitException If scale is invalid
     */
    public double getScale() throws ExitException {
        try {
            int percent = Integer.parseInt(valueOfOption(speed, "100"));
            if (percent < 1)
                throw new NumberFormatException();
            return 100.0 / (double) percent;
        } catch (NumberFormatException ex) {
            throw usage("speed should be a positive integer");
        }
    }

}
