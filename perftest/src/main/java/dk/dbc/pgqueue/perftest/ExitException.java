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

/**
 *
 * @author DBC {@literal <dbc.dk>}
 */
public class ExitException extends Exception {

    private static final long serialVersionUID = -3787669491059323384L;

    private final int code;

    public ExitException(int code) {
        this.code = code;
    }

    public ExitException(String message, int code) {
        super(message);
        this.code = code;
    }

    public ExitException(String message, Throwable cause, int code) {
        super(message, cause);
        this.code = code;
    }

    public ExitException(Throwable cause, int code) {
        super(cause);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

}
