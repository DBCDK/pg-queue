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

import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.JmxReporter;
import com.codahale.metrics.MetricRegistry;
import dk.dbc.pgqueue.consumer.FatalQueueError;
import dk.dbc.pgqueue.consumer.JobConsumer;
import dk.dbc.pgqueue.consumer.JobMetaData;
import dk.dbc.pgqueue.consumer.NonFatalQueueError;
import dk.dbc.pgqueue.consumer.PostponedNonFatalQueueError;
import dk.dbc.pgqueue.consumer.QueueWorker;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static dk.dbc.pgqueue.perftest.Util.*;

/**
 *
 * @author DBC {@literal <dbc.dk>}
 */
public class Test {

    private static final Logger log = LoggerFactory.getLogger(Test.class);

    private final String databaseUrl;
    private final int parallel;
    private final DataSource dataSource;
    private final String queue;
    private final String throttle;
    private final String throttleDatabase;
    private final int sleep;
    private final int maxTime;
    private final int rescan;
    private final int idleRescan;

    private final int maxTries;
    private final int delayTime;
    private final int postponeTime;
    private final double fatal;
    private final double error;
    private final double postpone;
    private AtomicLong totalCounter = new AtomicLong(0);

    public Test(String[] args) throws ExitException, URISyntaxException {
        Options options = new Options();
        options.addOption(Option.builder("h")
                .longOpt("help")
                .hasArg()
                .desc("This lot")
                .build());
        options.addOption(Option.builder("d")
                .longOpt("db")
                .hasArg()
                .argName("URL")
                .desc("Database to connect to (user:pass@host[:port]/database)")
                .required()
                .build());
        options.addOption(Option.builder("q")
                .longOpt("queue")
                .hasArg()
                .argName("NAMES")
                .desc("Comma separated list of name of queue(s) to use (default: a)")
                .build());
        options.addOption(Option.builder("T")
                .longOpt("throttle-database")
                .hasArg()
                .argName("THROTTLE")
                .desc("Throttle rules for database connections")
                .build());
        options.addOption(Option.builder("t")
                .longOpt("throttle")
                .hasArg()
                .argName("THROTTLE")
                .desc("Throttle rules for database connections")
                .build());
        options.addOption(Option.builder("w")
                .longOpt("wait")
                .hasArg()
                .argName("MIN-MAX")
                .desc("How long to wait ")
                .build());
        options.addOption(Option.builder("j")
                .longOpt("parallel")
                .hasArg()
                .argName("PARALLEL")
                .desc("Number of threads (default: 1)")
                .type(Number.class)
                .build());
        options.addOption(Option.builder("s")
                .longOpt("sleep")
                .hasArg()
                .argName("SLEEP")
                .desc("Number of milliseconds to sleep if the queue(s) are empty (default: 10000)")
                .type(Number.class)
                .build());
        options.addOption(Option.builder("m")
                .longOpt("max-time")
                .hasArg()
                .argName("QUERTTIME")
                .desc("Number of milliseconds dequeue is allowed to take before recalc queryplan (default: 250)")
                .type(Number.class)
                .build());
        options.addOption(Option.builder("r")
                .longOpt("rescan")
                .hasArg()
                .argName("INTERVAL")
                .desc("How many dequeues between start from top (default: 1000)")
                .type(Number.class)
                .build());
        options.addOption(Option.builder("i")
                .longOpt("idle-rescan")
                .hasArg()
                .argName("INTERVAL")
                .desc("How many dequeues between start from top when queue is empty (default: 10)")
                .type(Number.class)
                .build());
        options.addOption(Option.builder("t")
                .longOpt("max-tries")
                .hasArg()
                .argName("NUMBER")
                .desc("How many times a job should be tried (default: 3)")
                .type(Number.class)
                .build());
        options.addOption(Option.builder("f")
                .longOpt("fatal")
                .hasArg()
                .argName("PERCENT")
                .desc("How often a job should fail hard (default: 0)")
                .type(Number.class)
                .build());
        options.addOption(Option.builder("e")
                .longOpt("error")
                .hasArg()
                .argName("PERCENT")
                .desc("How often a job should fail soft (default: 0)")
                .type(Number.class)
                .build());
        options.addOption(Option.builder("p")
                .longOpt("postpone")
                .hasArg()
                .argName("PERCENT")
                .desc("How often a job should fail (postpone) (default: 0)")
                .type(Number.class)
                .build());
        options.addOption(Option.builder("P")
                .longOpt("postpone-time")
                .hasArg()
                .argName("MS")
                .desc("How long a soft fail should take (default: 10000)")
                .type(Number.class)
                .build());
        options.addOption(Option.builder("D")
                .longOpt("delay-time")
                .hasArg()
                .argName("MS")
                .desc("How long a delay before start processing - time for jmx connection (default: 0)")
                .type(Number.class)
                .build());

        try {
            CommandLine arguments = new DefaultParser().parse(options, args);
            if (arguments.hasOption('h')) {
                usage(options);
                throw new ExitException(0);
            }
            this.databaseUrl = arguments.getOptionValue("db");
            this.dataSource = database(databaseUrl);

            this.queue = arguments.getOptionValue("queue", "a");
            this.throttle = arguments.getOptionValue("throttle", "");
            this.throttleDatabase = arguments.getOptionValue("throttle-database", "");

            this.parallel = getArgumentInt(arguments, "parallel", 1, 1);
            this.sleep = getArgumentInt(arguments, "sleep", 10000, 1);
            this.maxTime = getArgumentInt(arguments, "max-time", 250, 1);
            this.rescan = getArgumentInt(arguments, "rescan", 1000, 1);
            this.idleRescan = getArgumentInt(arguments, "idle-rescan", 10, 1);
            this.maxTries = getArgumentInt(arguments, "max-time", 3, 1);
            this.postponeTime = getArgumentInt(arguments, "postpone-time", 10_000, 1);
            this.fatal = getArgumentDouble(arguments, "fatal", 0.0) / 100.0;
            this.error = getArgumentDouble(arguments, "error", 0.0) / 100.0 + fatal;
            this.postpone = getArgumentDouble(arguments, "postpone", 0.0) / 100.0 + error;
            this.delayTime = getArgumentInt(arguments, "delay-time", 0, 1);

        } catch (ParseException ex) {
            System.err.println(ex.getMessage());
            usage(options);
            throw new ExitException(0);
        }
    }

    void run() throws Exception {
        MetricRegistry registry = new MetricRegistry();
        JmxReporter.forRegistry(registry)
                .build()
                .start();
        ConsoleReporter.forRegistry(registry)
                .convertRatesTo(TimeUnit.SECONDS)
                .convertDurationsTo(TimeUnit.MILLISECONDS)
                .build()
                .start(10, TimeUnit.SECONDS);

        QueueWorker worker = QueueWorker.builder()
                .consume(queue.split(","))
                .dataSource(dataSource)
                .databaseConnectThrottle(throttleDatabase)
                .failureThrottle(throttle)
                .emptyQueueSleep(sleep)
                .maxQueryTime(maxTime)
                .maxTries(maxTries)
                .rescanEvery(rescan)
                .idleRescanEvery(idleRescan)
                .metricRegistry(registry)
                .build(STORAGE_ABSTRACTION, parallel, this::supplier);
        if (delayTime != 0) {
            try {
                Thread.sleep(delayTime);
            } catch (InterruptedException ex) {
                log.error("Error waiting to start: {}", ex.getMessage());
                log.debug("Error waiting to start:", ex);
            }
        }
        worker.start();
    }

    private JobConsumer<Job> supplier() {
        AtomicLong counter = new AtomicLong(0);
        return (Connection connection, Job job, JobMetaData metaData) -> {
            long no = counter.incrementAndGet();
            long total = totalCounter.incrementAndGet();
            if (no % 1000L == 0L) {
                log.info("Processed no {} in this thread, total {} (meta: {})", no, total, metaData);
            }
            double random = Math.random();
            if (random < fatal) {
                log.info("fatal");
                throw new FatalQueueError("Fatal");
            }
            if (random < error) {
                log.info("error");
                throw new NonFatalQueueError("NonFatal");
            }
            if (random < postpone) {
                log.info("postpone");
                throw new PostponedNonFatalQueueError("NonFatal", postponeTime);
            }
        };
    }

    private void usage(Options options) {
        new HelpFormatter().printHelp(Main.PROGNAME + " load [...]", options);
    }

}
