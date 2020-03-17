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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.OutputStreamAppender;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Morten Bøgeskov (mb@dbc.dk)
 */
public class CliArguments {

    protected final Options options;
    private final Option help;
    private final Option verbose;
    private final Option quiet;

    protected CommandLine commandLine;
    private char positionalOptionFlag = '\u00e0'; // Positional late in the alphabet.

    /**
     * Setup default options
     *
     * @param help    null means no help switch
     * @param verbose null means no verbose switch
     * @param quiet   null means no quiet switch
     */
    public CliArguments(Option help, Option verbose, Option quiet) {
        options = new Options();
        this.help = help;
        if (help != null)
            options.addOption(help);
        this.verbose = verbose;
        if (verbose != null)
            options.addOption(verbose);
        this.quiet = quiet;
        if (quiet != null)
            options.addOption(quiet);
    }

    /**
     * Default constructor with -h/--help, -v/--verbose and -q/--quiet
     */
    public CliArguments() {
        this(Option.builder("h").longOpt("help").desc("this help").build(),
             Option.builder("v").longOpt("verbose").desc("debug log").build(),
             Option.builder("q").longOpt("quiet").desc("log only warnings and errors").build());
    }

    /**
     * Parse arguments from {@code public static void main()}
     *
     * @param arguments command line arguments
     * @throws ExitException is arguments aren't valid
     */
    public void parseArguments(String... arguments) throws ExitException {
        Stream.Builder<String> missing = parseAsNonRequired(arguments);
        if (optionIsSet(help))
            throw usage(null);

        setupLogLevel();

        Optional<String> error = validateOptionsAndProcessPositionalArguments(missing, commandLine.getArgList());

        if (error.isPresent())
            throw usage(error.get());
    }

    /**
     * Validate the arguments, and handle positional arguments
     * <p>
     * You need to handle (place) positional arguments yourself. Default action
     * is to not allow any positional arguments.
     * <p>
     * Usage for complex required and positional arguments:
     * <pre>
     *    &#64;Override
     *    public Optional&lt;String&gt; validateOptionsAndProcessPositionalArguments(Stream.Builder&lt;String&gt; missingRequired, List&lt;String&gt; positionals) &#123;
     *        if(!optionIsSet(optionA) &amp;&amp; !optionIsSet(optionB)) // One of a or b is required
     *            missingRequired.add("-a/-b");
     *
     *        switch(positionals.size()) &#123; // 2 positionals required
     *            case 0:
     *                missingRequired.add(fromOption.getArgName())
     *                missingRequired.add(toOption.getArgName())
     *                break;
     *            case 1:
     *                missingRequired.add(toOption.getArgName())
     *                break;
     *        &#125;
     *
     *        Optional&lt;String&gt; usageMessage = super.validateOptionsAndProcessPositionalArguments(missingRequired, Collections.EMPTY_LIST);
     *        if(usageMessage.isPresent())
     *            return usageMessage;
     *
     *        if(positionals.size() &gt; 2) // only 2 positionals allowed
     *            return Optional.of("Too many positional arguments starting at: " + positionals.get(0));
     *
     *        this.from = positionals.get(0);
     *        this.to = positionals.get(1);
     *
     *        if(optionIsSet(optionA) &amp;&amp; optionIsSet(optionB)) // Only ONE of a or b is allowed
     *            return Optional.of("You cannot have both a and b");
     *
     *        return Optional.empty()
     *   &#125;
     * </pre>
     *
     * @param missingRequired A builder with the option names that are missing.
     *                        You can add to this your self (see usage)
     * @param positionals     List of positional arguments
     * @return Optional string, if set usage is printed and this is written
     *         before the usage message from
     *         {@link #parseArguments(java.lang.String...)} and exit(0) is
     *         thrown.
     */
    public Optional<String> validateOptionsAndProcessPositionalArguments(Stream.Builder<String> missingRequired, List<String> positionals) {
        String missing = missingRequired.build()
                .collect(Collectors.joining(", "))
                .replaceFirst("\\(.*\\), ", "\\1 & ");
        if (!missing.isEmpty())
            return Optional.of("Missing required options: " + missing);
        if (!positionals.isEmpty())
            return Optional.of("Unexpected positional arguments at: " + positionals.get(0));
        return Optional.empty();
    }

    public List<String> logPackages() {
        return Arrays.asList("dk.dbc");
    }

    private String optName(Option option) {
        String opt = option.getLongOpt();
        if (opt == null)
            opt = option.getOpt();
        return opt;
    }

    /**
     * Check if an option is set
     *
     * @param option option to test
     * @return is it has been set
     */
    protected boolean optionIsSet(Option option) {
        if (option == null)
            return false;
        return commandLine.hasOption(optName(option));
    }

    /**
     * Get value of option
     *
     * @param option       option to query
     * @param defaultValue is option is unset use this
     * @return the value related to the option
     */
    protected String valueOfOption(Option option, String defaultValue) {
        return commandLine.getOptionValue(optName(option), defaultValue);
    }

    /**
     * Get value of option
     *
     * @param option option to query
     * @return the value related to the option
     */
    protected String valueOfOption(Option option) {
        return commandLine.getOptionValue(optName(option));
    }

    /**
     * Perform the parsing of the options
     *
     * @param args the commend line arguments
     * @return StreamBuilder with all the missing options
     * @throws ExitException if options are really wrong
     */
    private Stream.Builder<String> parseAsNonRequired(String[] args) throws ExitException {
        try {
            List<Option> required = options.getOptions().stream()
                    .filter(PositionalOption::isNotOfType)
                    .filter(Option::isRequired)
                    .collect(Collectors.toList());
            required.forEach(r -> r.setRequired(false));
            Options nonRequired = new Options(); // Only non positional
            options.getOptions().stream()
                    .filter(PositionalOption::isNotOfType)
                    .forEach(nonRequired::addOption);
            this.commandLine = new DefaultParser().parse(nonRequired, args);
            required.forEach(r -> r.setRequired(true));

            Stream.Builder<String> errors = Stream.builder();
            required.stream()
                    .filter(PositionalOption::isNotOfType)
                    .map(Option::getOpt)
                    .filter(opt -> !commandLine.hasOption(opt))
                    .forEach(errors::accept);
            return errors;
        } catch (ParseException ex) {
            throw usage(ex.getMessage());
        }
    }

    /**
     * Outputs usage and produces an exception to throw
     *
     * @param error Message before usage null or empty string results in
     *              exit-code = 0
     * @return exception with exit-code indicating error has been printed too
     */
    public ExitException usage(String error) {
        boolean hasError = error != null && !error.isEmpty();
        try (PrintWriter writer = new PrintWriter(getUsageWriter(hasError))) {
            HelpFormatter formatter = new HelpFormatter();
            if (hasError) {
                formatter.printWrapped(writer, 76, error);
                formatter.printWrapped(writer, 76, "");
            }
            formatter.printUsage(writer, 76, executable(), options);
            formatter.printWrapped(writer, 76, "");
            formatter.printOptions(writer, 76, options, 4, 4);
            formatter.printWrapped(writer, 76, "");
            if (!hasError) {
                List<String> extraHelp = extraHelp();
                extraHelp.forEach(extra -> {
                    formatter.printWrapped(writer, 76, extra);
                    formatter.printWrapped(writer, 76, "");
                });
            }
            writer.flush();
        }
        if (hasError)
            return new ExitException(1);
        return new ExitException(0);
    }

    public List<String> extraHelp() {
        return Collections.EMPTY_LIST;
    }

    /**
     * Find a writer to the relevant stream
     *
     * @param hasError if this is --help or bad argument output
     * @return there to print usage
     */
    protected Writer getUsageWriter(boolean hasError) {
        OutputStream os = hasError ? System.err : System.out;
        return new OutputStreamWriter(os, StandardCharsets.UTF_8);
    }

    /**
     * Given a list of packages from {@link #logPackages()} set logging level
     * for these according to verbose/quiet
     *
     * @throws ExitException If both verbose and quiet is set
     */
    private void setupLogLevel() throws ExitException {
        boolean v = optionIsSet(verbose);
        boolean q = optionIsSet(quiet);

        if (!v && !q)
            return;
        if (v && q)
            throw usage("You cannot have both quiet and verbose");
        Level level = Level.INFO;
        if (v)
            level = Level.DEBUG;
        if (q)
            level = Level.WARN;
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        for (String pkg : logPackages()) {
            if (pkg == null || pkg.isEmpty())
                pkg = Logger.ROOT_LOGGER_NAME;
            context.getLogger(pkg).setLevel(level);
        }
    }

    /**
     * Send log to other stream
     * <p>
     * If std-out is needed for content to be saved
     *
     * @param os where to log
     */
    public void logTo(PrintStream os) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.getLogger(Logger.ROOT_LOGGER_NAME)
                .iteratorForAppenders();
        for (Iterator<Appender<ILoggingEvent>> iterator = context.getLogger(Logger.ROOT_LOGGER_NAME).iteratorForAppenders() ; iterator.hasNext() ;) {
            Appender<ILoggingEvent> next = iterator.next();
            if (next instanceof OutputStreamAppender) {
                OutputStreamAppender<ILoggingEvent> consoleAppender = (OutputStreamAppender) next;
                consoleAppender.setOutputStream(os);
            }
        }
    }

    /**
     * Create a positional option that is required
     *
     * @param argName     Name of field
     * @param description description of field
     * @return Option for
     *         {@link Options#addOption(org.apache.commons.cli.Option)}
     */
    public final Option positionalRequired(String argName, String description) {
        return positional(true, argName, description);
    }

    /**
     * Create a positional option that is optional
     *
     * @param argName     Name of field
     * @param description description of field
     * @return Option for
     *         {@link Options#addOption(org.apache.commons.cli.Option)}
     */
    public final Option positionalOptional(String argName, String description) {
        return positional(false, argName, description);
    }

    private Option positional(boolean required, String argName, String description) {
        return new PositionalOption(String.valueOf(++positionalOptionFlag), description, argName, required);
    }

    /**
     * Try to make a string indicating the command of this executable
     *
     * @return "$0" like string
     */
    public static String executable() {
        try {
            return "java -jar " +
                   new java.io.File(CliArguments.class
                           .getProtectionDomain()
                           .getCodeSource()
                           .getLocation()
                           .toURI()
                           .getPath())
                           .getName();
        } catch (RuntimeException | URISyntaxException ex) {
            return "[executable]";
        }
    }

    private static class PositionalOption extends Option {

        private static final long serialVersionUID = 7186966870093513714L;

        private final String argName;
        private final boolean required;

        public PositionalOption(String opt, String description, String argName, boolean required) {
            super(opt, description);
            this.argName = argName;
            this.required = required;
        }

        @Override
        public String getArgName() {
            return argName;
        }

        @Override
        public boolean hasArg() {
            return true;
        }

        @Override
        public boolean hasLongOpt() {
            return false;
        }

        @Override
        public boolean isRequired() {
            return required;
        }

        @Override
        public String getDescription() {
            return "    " + super.getDescription();
        }

        @Override
        public String getOpt() {
            return "\010\010";
        }

        private static boolean isNotOfType(Object obj) {
            return !isOfType(obj);
        }

        private static boolean isOfType(Object obj) {
            return obj instanceof PositionalOption;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null || getClass() != obj.getClass())
                return false;
            final PositionalOption other = (PositionalOption) obj;
            return super.equals(obj) &&
                   this.required != other.required &&
                   Objects.equals(this.argName, other.argName);
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 37 * hash + super.hashCode();
            hash = 37 * hash + Objects.hashCode(this.argName);
            hash = 37 * hash + ( this.required ? 1 : 0 );
            return hash;
        }
    }
}
