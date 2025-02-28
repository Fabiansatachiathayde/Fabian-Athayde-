/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.internal.buildevents;

import com.google.common.collect.ImmutableList;
import org.apache.commons.lang.StringUtils;
import org.gradle.api.Action;
import org.gradle.api.NonNullApi;
import org.gradle.api.internal.DocumentationRegistry;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.configuration.LoggingConfiguration;
import org.gradle.api.logging.configuration.ShowStacktrace;
import org.gradle.api.problems.internal.InternalProblem;
import org.gradle.initialization.BuildClientMetaData;
import org.gradle.internal.enterprise.core.GradleEnterprisePluginManager;
import org.gradle.internal.exceptions.CompilationFailedIndicator;
import org.gradle.internal.exceptions.ContextAwareException;
import org.gradle.internal.exceptions.Contextual;
import org.gradle.internal.exceptions.ExceptionContextVisitor;
import org.gradle.internal.exceptions.FailureResolutionAware;
import org.gradle.internal.exceptions.LocationAwareException;
import org.gradle.internal.exceptions.MultiCauseException;
import org.gradle.internal.exceptions.NonGradleCause;
import org.gradle.internal.exceptions.NonGradleCauseExceptionsHolder;
import org.gradle.internal.exceptions.ResolutionProvider;
import org.gradle.internal.exceptions.StyledException;
import org.gradle.internal.logging.text.BufferingStyledTextOutput;
import org.gradle.internal.logging.text.LinePrefixingStyledTextOutput;
import org.gradle.internal.logging.text.StyledTextOutput;
import org.gradle.internal.logging.text.StyledTextOutputFactory;
import org.gradle.internal.problems.failure.DefaultFailureFactory;
import org.gradle.internal.problems.failure.Failure;
import org.gradle.problems.internal.rendering.ProblemRenderer;
import org.gradle.util.internal.GUtil;
import org.gradle.util.internal.TreeVisitor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;

import static java.lang.String.join;
import static org.apache.commons.lang.StringUtils.repeat;
import static org.gradle.api.logging.LogLevel.DEBUG;
import static org.gradle.api.logging.LogLevel.INFO;
import static org.gradle.initialization.StartParameterBuildOptions.BuildScanOption.LONG_OPTION;
import static org.gradle.internal.logging.LoggingConfigurationBuildOptions.LogLevelOption.DEBUG_LONG_OPTION;
import static org.gradle.internal.logging.LoggingConfigurationBuildOptions.LogLevelOption.INFO_LONG_OPTION;
import static org.gradle.internal.logging.LoggingConfigurationBuildOptions.StacktraceOption.STACKTRACE_LONG_OPTION;
import static org.gradle.internal.logging.text.StyledTextOutput.Style.Failure;
import static org.gradle.internal.logging.text.StyledTextOutput.Style.Info;
import static org.gradle.internal.logging.text.StyledTextOutput.Style.Normal;
import static org.gradle.internal.logging.text.StyledTextOutput.Style.UserInput;

/**
 * Reports the build exception, if any.
 */
@NonNullApi
public class BuildExceptionReporter implements Action<Throwable> {
    private static final String NO_ERROR_MESSAGE_INDICATOR = "(no error message)";

    public static final String RESOLUTION_LINE_PREFIX = "> ";
    public static final String LINE_PREFIX_LENGTH_SPACES = repeat(" ", RESOLUTION_LINE_PREFIX.length());

    @NonNullApi
    private enum ExceptionStyle {
        NONE, FULL
    }

    private final StyledTextOutputFactory textOutputFactory;
    private final LoggingConfiguration loggingConfiguration;
    private final BuildClientMetaData clientMetaData;
    private final GradleEnterprisePluginManager gradleEnterprisePluginManager;

    public BuildExceptionReporter(StyledTextOutputFactory textOutputFactory, LoggingConfiguration loggingConfiguration, BuildClientMetaData clientMetaData, GradleEnterprisePluginManager gradleEnterprisePluginManager) {
        this.textOutputFactory = textOutputFactory;
        this.loggingConfiguration = loggingConfiguration;
        this.clientMetaData = clientMetaData;
        this.gradleEnterprisePluginManager = gradleEnterprisePluginManager;
    }

    public BuildExceptionReporter(StyledTextOutputFactory textOutputFactory, LoggingConfiguration loggingConfiguration, BuildClientMetaData clientMetaData) {
        this(textOutputFactory, loggingConfiguration, clientMetaData, null);
    }

    public void buildFinished(@Nullable Failure failure) {
        if (failure == null) {
            return;
        }
        renderFailure(failure);
    }

    @Override
    public void execute(@Nonnull Throwable throwable) {
        Failure failure = DefaultFailureFactory.withDefaultClassifier().create(throwable);
        renderFailure(failure);
    }

    public void renderFailure(@Nonnull Failure failure) {
        List<Failure> causes = failure.getCauses();
        if (causes.size() > 1) {
            renderMultipleBuildExceptions(failure);
        } else {
            renderSingleBuildException(failure);
        }
    }

    private void renderMultipleBuildExceptions(Failure failure) {
        String message = failure.getMessage();
        List<Failure> flattenedFailures = failure.getCauses();
        StyledTextOutput output = textOutputFactory.create(BuildExceptionReporter.class, LogLevel.ERROR);
        output.println();
        output.withStyle(Failure).format("FAILURE: %s", message);
        output.println();

        for (int i = 0; i < flattenedFailures.size(); i++) {
            Failure cause = flattenedFailures.get(i);
            FailureDetails details = constructFailureDetails("Task", cause);

            output.println();
            output.withStyle(Failure).format("%s: ", i + 1);
            details.summary.writeTo(output.withStyle(Failure));
            output.println();
            output.text("-----------");

            writeFailureDetails(output, details);

            output.println("==============================================================================");
        }
    }

    private void renderSingleBuildException(Failure failure) {
        StyledTextOutput output = textOutputFactory.create(BuildExceptionReporter.class, LogLevel.ERROR);
        FailureDetails details = constructFailureDetails("Build", failure);

        output.println();
        output.withStyle(Failure).text("FAILURE: ");
        details.summary.writeTo(output.withStyle(Failure));
        output.println();

        writeFailureDetails(output, details);
    }

    private static boolean hasCauseAncestry(Failure failure, Class<?> type) {
        Deque<Failure> causes = new ArrayDeque<>(failure.getCauses());
        while (!causes.isEmpty()) {
            Failure cause = causes.pop();
            if (hasCause(cause, type)) {
                return true;
            }
            causes.addAll(cause.getCauses());
        }
        return false;
    }

    private static boolean hasCause(Failure cause, Class<?> type) {
        if (NonGradleCauseExceptionsHolder.class.isAssignableFrom(cause.getExceptionType())) {
            return ((NonGradleCauseExceptionsHolder) cause.getOriginal()).hasCause(type);
        }
        return false;
    }

    private ExceptionStyle getShowStackTraceOption() {
        if (loggingConfiguration.getShowStacktrace() != ShowStacktrace.INTERNAL_EXCEPTIONS) {
            return ExceptionStyle.FULL;
        } else {
            return ExceptionStyle.NONE;
        }
    }

    private FailureDetails constructFailureDetails(String granularity, Failure failure) {
        FailureDetails details = new FailureDetails(failure, getShowStackTraceOption());
        details.summary.format("%s failed with an exception.", granularity);

        fillInFailureResolution(details);

        if (failure.getOriginal() instanceof ContextAwareException) {
            ExceptionFormattingVisitor exceptionFormattingVisitor = new ExceptionFormattingVisitor(details);
            exceptionFormattingVisitor.accept(failure.getCauses());
            if (failure.getOriginal() instanceof LocationAwareException) {
                String location = ((LocationAwareException) failure.getOriginal()).getLocation();
                if (location != null) {
                    exceptionFormattingVisitor.visitLocation(location);
                }
            }
        } else {
            details.appendDetails();
        }
        details.renderStackTrace();
        return details;
    }

    private static class ExceptionFormattingVisitor extends ExceptionContextVisitor {
        private final FailureDetails failureDetails;

        private final Set<Failure> printedNodes = new HashSet<>();
        private int depth;
        private int suppressedDuplicateBranchCount;

        private ExceptionFormattingVisitor(FailureDetails failureDetails) {
            this.failureDetails = failureDetails;
        }

        @Override
        protected void visitCause(Failure cause) {
            failureDetails.failure = cause;
            failureDetails.appendDetails();
        }

        @Override
        protected void visitLocation(String location) {
            failureDetails.location.text(location);
        }

        @Override
        public void node(Failure node) {
            if (shouldBePrinted(node)) {
                printedNodes.add(node);
                if (node.getCauses().isEmpty() || isUsefulMessage(getMessage(node))) {
                    LinePrefixingStyledTextOutput output = getLinePrefixingStyledTextOutput(failureDetails);
                    renderStyledError(node, output);
                }
            } else {
                // Only increment the suppressed branch count for the ultimate cause of the failure, which has no cause itself
                if (node.getCauses().isEmpty()) {
                    suppressedDuplicateBranchCount++;
                }
            }
        }

        /**
         * Determines if the given node should be printed.
         *
         * A node should be printed iff it is not in the {@link #printedNodes} set, and it is not a
         * transitive cause of a node that is in the set.  Direct causes will be checked, as well
         * as each branch of {@link #getReportableCauses(Failure)}s for nodes of that type.
         *
         * @param node the node to check
         * @return {@code true} if the node should be printed; {@code false} otherwise
         */
        private boolean shouldBePrinted(Failure node) {
            if (printedNodes.isEmpty()) {
                return true;
            }

            Queue<Failure> next = new ArrayDeque<>();
            next.add(node);

            while (!next.isEmpty()) {
                Failure curr = next.poll();
                if (printedNodes.contains(curr)) {
                    return false;
                } else {
                    if (!curr.getCauses().isEmpty()) {
                        next.addAll(curr.getCauses());
                    }
                    if (curr.getOriginal() instanceof ContextAwareException) {
                        next.addAll(getReportableCauses(curr));
                    }
                }
            }

            return true;
        }

        private boolean isUsefulMessage(String message) {
            return StringUtils.isNotBlank(message) && !message.endsWith(NO_ERROR_MESSAGE_INDICATOR);
        }

        @Override
        public void startChildren() {
            depth++;
        }

        @Override
        public void endChildren() {
            depth--;
        }

        private LinePrefixingStyledTextOutput getLinePrefixingStyledTextOutput(FailureDetails details) {
            details.details.format("%n");
            StringBuilder prefix = new StringBuilder(repeat("   ", depth - 1));
            details.details.text(prefix);
            prefix.append("  ");
            details.details.style(Info).text(RESOLUTION_LINE_PREFIX).style(Normal);

            return new LinePrefixingStyledTextOutput(details.details, prefix, false);
        }

        @Override
        protected void endVisiting() {
            if (suppressedDuplicateBranchCount > 0) {
                LinePrefixingStyledTextOutput output = getLinePrefixingStyledTextOutput(failureDetails);
                boolean plural = suppressedDuplicateBranchCount > 1;
                if (plural) {
                    output.append(String.format("There are %d more failures with identical causes.", suppressedDuplicateBranchCount));
                } else {
                    output.append("There is 1 more failure with an identical cause.");
                }
            }
        }

        private void accept(List<Failure> causes) {
            if (!causes.isEmpty()) {
                for (Failure cause : causes) {
                    visitCause(cause);
                    visitCauses(cause, this);

                }
            }
            endVisiting();
        }
    }

    private void fillInFailureResolution(FailureDetails details) {
        ContextImpl context = new ContextImpl(details.resolution);
        if (details.failure.getOriginal() instanceof FailureResolutionAware) {
            ((FailureResolutionAware) details.failure.getOriginal()).appendResolutions(context);
        }
        getResolutions(details.failure).stream()
            .distinct()
            .forEach(resolution ->
                context.appendResolution(output ->
                    output.text(join("\n " + LINE_PREFIX_LENGTH_SPACES, resolution.split("\n"))))
            );
        boolean shouldDisplayGenericResolutions = !hasCauseAncestry(details.failure, NonGradleCause.class) && !hasProblemReportsWithSolutions(details.failure);
        if (details.exceptionStyle == ExceptionStyle.NONE && shouldDisplayGenericResolutions) {
            context.appendResolution(output ->
                runWithOption(output, STACKTRACE_LONG_OPTION, " option to get the stack trace.")
            );
        }

        LogLevel logLevel = loggingConfiguration.getLogLevel();
        boolean isLessThanInfo = logLevel.ordinal() > INFO.ordinal();
        if (logLevel != DEBUG && shouldDisplayGenericResolutions) {
            context.appendResolution(output -> {
                output.text("Run with ");
                if (isLessThanInfo) {
                    output.withStyle(UserInput).format("--%s", INFO_LONG_OPTION);
                    output.text(" or ");
                }
                output.withStyle(UserInput).format("--%s", DEBUG_LONG_OPTION);
                output.text(" option to get more log output.");
            });
        }

        if (!context.missingBuild && !isGradleEnterprisePluginApplied()) {
            addBuildScanMessage(context);
        }

        if (shouldDisplayGenericResolutions) {
            context.appendResolution(this::writeGeneralTips);
        }
    }

    private static boolean hasProblemReportsWithSolutions(Failure failure) {
        Optional<String> solution = failure.getProblems().stream()
            .flatMap(p -> p.getSolutions().stream())
            .findFirst();
        if (solution.isPresent()) {
            return true;
        } else {
            return hasProblemReportsWithSolutions(failure.getCauses());
        }
    }

    private static boolean hasProblemReportsWithSolutions(List<Failure> failures) {
        return failures.stream().anyMatch(BuildExceptionReporter::hasProblemReportsWithSolutions);
    }

    private static void runWithOption(StyledTextOutput output, String optionName, String text) {
        output.text("Run with ");
        output.withStyle(UserInput).format("--%s", optionName);
        output.text(text);
    }

    private static List<String> getResolutions(Failure failure) {
        ImmutableList.Builder<String> resolutions = ImmutableList.builder();

        if (ResolutionProvider.class.isAssignableFrom(failure.getExceptionType())) {
            resolutions.addAll(((ResolutionProvider) failure.getOriginal()).getResolutions());
        }

        Collection<InternalProblem> all = failure.getProblems();
        for (InternalProblem problem : all) {
            resolutions.addAll(problem.getSolutions());
        }

        for (Failure cause : failure.getCauses()) {
            resolutions.addAll(getResolutions(cause));
        }

        return resolutions.build();
    }

    private void addBuildScanMessage(ContextImpl context) {
        context.appendResolution(output -> runWithOption(output, LONG_OPTION, " to get full insights."));
    }

    private boolean isGradleEnterprisePluginApplied() {
        return gradleEnterprisePluginManager != null && gradleEnterprisePluginManager.isPresent();
    }

    private void writeGeneralTips(StyledTextOutput resolution) {
        resolution.text("Get more help at ");
        resolution.withStyle(UserInput).text("https://help.gradle.org");
        resolution.text(".");
    }

    private static String getMessage(Failure failure) {
        try {
            String msg = failure.getMessage();
            StringBuilder builder = new StringBuilder(msg == null ? "" : msg);
            Collection<InternalProblem> problems = failure.getProblems();
            if (!problems.isEmpty()) {
                builder.append(System.lineSeparator());
                StringWriter problemWriter = new StringWriter();
                new ProblemRenderer(problemWriter).render(new ArrayList<>(problems));
                builder.append(problemWriter);

                // Workaround to keep the original behavior for Java compilation. We should render counters for all problems in the future.
                if (failure.getOriginal() instanceof CompilationFailedIndicator) {
                    String diagnosticCounts = ((CompilationFailedIndicator) failure).getDiagnosticCounts();
                    if (diagnosticCounts != null) {
                        builder.append(System.lineSeparator());
                        builder.append(diagnosticCounts);
                    }
                }
            }

            String message = builder.toString();
            if (GUtil.isTrue(message)) {
                return message;
            }
            return String.format("%s %s", failure.getExceptionType().getName(), NO_ERROR_MESSAGE_INDICATOR);
        } catch (Throwable t) {
            return String.format("Unable to get message for failure of type %s due to %s", failure.getExceptionType().getSimpleName(), t.getMessage());
        }
    }

    private void writeFailureDetails(StyledTextOutput output, FailureDetails details) {
        writeSection(details.location, output, "* Where:");
        writeSection(details.details, output, "* What went wrong:");
        writeSection(details.resolution, output, "* Try:");
        writeSection(details.stackTrace, output, "* Exception is:");
    }

    private static void writeSection(BufferingStyledTextOutput textOutput, StyledTextOutput output, String sectionTitle) {
        if (textOutput.getHasContent()) {
            output.println();
            output.println(sectionTitle);
            textOutput.writeTo(output);
            output.println();
        }
    }

    @NonNullApi
    private static class FailureDetails {
        Failure failure;
        final BufferingStyledTextOutput summary = new BufferingStyledTextOutput();
        final BufferingStyledTextOutput details = new BufferingStyledTextOutput();
        final BufferingStyledTextOutput location = new BufferingStyledTextOutput();
        final BufferingStyledTextOutput stackTrace = new BufferingStyledTextOutput();
        final BufferingStyledTextOutput resolution = new BufferingStyledTextOutput();
        final ExceptionStyle exceptionStyle;

        public FailureDetails(Failure failure, ExceptionStyle exceptionStyle) {
            this.failure = failure;
            this.exceptionStyle = exceptionStyle;
        }

        void appendDetails() {
            renderStyledError(failure, details);
        }

        void renderStackTrace() {
            if (exceptionStyle == ExceptionStyle.FULL) {
                try {
                    // TODO: Print the stacktrace from the Failure instead.
                    stackTrace.exception(failure.getOriginal());
                } catch (Throwable t) {
                    // Discard. Should also render this as a separate build failure
                }
            }
        }
    }

    static void renderStyledError(Failure failure, StyledTextOutput details) {
        if (failure.getOriginal() instanceof StyledException) {
            ((StyledException) failure).render(details);
        } else {
            details.text(getMessage(failure));
        }
    }

    @NonNullApi
    private class ContextImpl implements FailureResolutionAware.Context {
        private final BufferingStyledTextOutput resolution;

        private final DocumentationRegistry documentationRegistry = new DocumentationRegistry();

        private boolean missingBuild;

        public ContextImpl(BufferingStyledTextOutput resolution) {
            this.resolution = resolution;
        }

        @Override
        public BuildClientMetaData getClientMetaData() {
            return clientMetaData;
        }

        @Override
        public void doNotSuggestResolutionsThatRequireBuildDefinition() {
            missingBuild = true;
        }

        @Override
        public void appendResolution(Consumer<StyledTextOutput> resolutionProducer) {
            if (resolution.getHasContent()) {
                resolution.println();
            }
            resolution.style(Info).text(RESOLUTION_LINE_PREFIX).style(Normal);
            resolutionProducer.accept(resolution);
        }

        @Override
        public void appendDocumentationResolution(String prefix, String userGuideId, String userGuideSection) {
            appendResolution(output -> output.text(documentationRegistry.getDocumentationRecommendationFor(prefix, userGuideId, userGuideSection)));
        }
    }

    /**
     * Returns the reportable causes for this failure.
     *
     * @return The causes. Never returns null, returns an empty list if this exception has no reportable causes.
     */
    public static List<Failure> getReportableCauses(Failure failure) {
        final List<Failure> causes = new ArrayList<>();
        for (Failure cause : failure.getCauses()) {
            visitCauses(cause, new TreeVisitor<Failure>() {
                @Override
                public void node(Failure node) {
                    causes.add(node);
                }
            });
        }

        return causes;
    }

    private static void visitCauses(Failure t, TreeVisitor<? super Failure> visitor) {
        List<Failure> causes = t.getCauses();
        if (!causes.isEmpty()) {
            visitor.startChildren();
            for (Failure cause : causes) {
                visitContextual(cause, visitor);
            }
            visitor.endChildren();
        }
    }

    private static void visitContextual(Failure t, TreeVisitor<? super Failure> visitor) {
        Failure next = findNearestContextual(t);
        if (next != null) {
            // Show any contextual cause recursively
            visitor.node(next);
            visitCauses(next, visitor);
        } else {
            // Show the direct cause of the last contextual cause only
            visitor.node(t);
        }
    }

    @Nullable
    private static Failure findNearestContextual(@Nullable Failure t) {
        if (t == null) {
            return null;
        }
        if (t.getOriginal().getClass().getAnnotation(Contextual.class) != null || MultiCauseException.class.isAssignableFrom(t.getExceptionType())) {
            return t;
        }
        // Not multicause, so at most one cause.
        Optional<Failure> cause = t.getCauses().stream().findAny();
        return findNearestContextual(cause.orElse(null));
    }
}
