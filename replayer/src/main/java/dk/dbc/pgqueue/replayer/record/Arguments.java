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
package dk.dbc.pgqueue.replayer.record;

import dk.dbc.pgqueue.replayer.CliArguments;
import dk.dbc.pgqueue.replayer.Converters;
import dk.dbc.pgqueue.replayer.Database;
import dk.dbc.pgqueue.replayer.ExitException;
import org.apache.commons.cli.Option;

import javax.sql.DataSource;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;

/**
 *
 * @author Morten Bøgeskov (mb@dbc.dk)
 */
public class Arguments extends CliArguments {

    private static final String TIMEOUT_FORMAT_ERROR = "Invalid timeout format (number{ms/s/m/h})";

    private final Option database;
    private final Option output;
    private final Option timeout;
    private final Option queue;

    public Arguments() {
        super();
        options.addOption(this.database = Option.builder("d")
                .longOpt("database")
                .required()
                .hasArg()
                .argName("URL")
                .desc("Solr-Doc-Store database")
                .build());
        options.addOption(this.output = Option.builder("o")
                .longOpt("output")
                .hasArg()
                .argName("FILE")
                .desc("Csv output, - is stdout")
                .build());
        options.addOption(this.queue = Option.builder("c")
                .longOpt("consumer")
                .required()
                .hasArg()
                .argName("QUEUE")
                .desc("Which queue to harvest from")
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
                "When running you need to ensure that something writes to this queue." +
                " Remember to create supplier before and remove it after run, or your queue will grow." +
                "The queue will be flushed before recording, to get correct timestamps.",
                "The CVS file format is 1st line: offsetInMs, [database column names]." +
                " Other lines are: offset from recording start in milliseconds, [database values].",
                "Make sure NO OTHER COMSUMER takes jobs from same queue," +
                " this will mess up you business flow, as this consumes not monitors queue events.",
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

    public OutputStream getOutput() throws ExitException {
        String fileName = valueOfOption(output, "-");
        if (fileName.equals("-")) {
            logTo(System.err);
            return System.out;
        }
        try {
            return new FileOutputStream(fileName);
        } catch (FileNotFoundException ex) {
            throw new ExitException(1, fileName + ": " + ex.getMessage());
        }
    }

    public String getQueue() {
        return valueOfOption(queue);
    }

    public long getDurationInMS() throws ExitException {
        if (!optionIsSet(timeout))
            return Long.MAX_VALUE; // Run forever
        String duration = valueOfOption(timeout);
        long ms = Converters.durationToMS(duration, () -> usage(TIMEOUT_FORMAT_ERROR));
        if (ms == 0)
            throw usage(TIMEOUT_FORMAT_ERROR);
        return ms;
    }
}
