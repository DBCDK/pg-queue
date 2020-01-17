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

import com.opencsv.ICSVWriter;
import dk.dbc.pgqueue.consumer.JobConsumer;
import dk.dbc.pgqueue.consumer.JobMetaData;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;

/**
 * Job consumer to queue-worker that outputs jobs to cvs file
 *
 * @author Morten Bøgeskov (mb@dbc.dk)
 */
public class JobToCSVConsumer implements JobConsumer<String[]> {

    private final ICSVWriter writer;
    private Instant epoch;
    private int rows;

    public JobToCSVConsumer(ICSVWriter writer) {
        this.writer = writer;
        this.epoch = Instant.now();
        this.rows = 0;
    }

    /**
     * Set timestamp when recording started
     *
     * @param epoch timestamp
     */
    public void setEpoch(Instant epoch) {
        this.epoch = epoch;
    }

    /**
     * Set timestamp when recording started to
     */
    public void setEpoch() {
        setEpoch(Instant.now());
    }

    /**
     * How many rows has been written
     *
     * @return number of rows, for reporting to user
     */
    public int getRows() {
        return rows;
    }

    /**
     * Write row to disk
     * <p>
     * Synthesizes column 0 to delta between epoch and when it should be
     * dequeued
     *
     * @param connection database (unused)
     * @param job        columns from queue table
     * @param metaData   metadata about when it was queued
     */
    @Override
    public void accept(Connection connection, String[] job, JobMetaData metaData) {
        Instant dequeueAfter = metaData.getDequeueAfter().toInstant();
        Duration duration = Duration.between(epoch, dequeueAfter);
        job[0] = String.valueOf(duration.toMillis());
        writer.writeNext(job, true); // Everything is quoted - null is empty unquoted
        rows++;
    }
}
