/*
 * Copyright (C) 2020 Mathias Åhsberg
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 *
 */
package com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket;

import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.AnalysisDetails;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.DecorationResult;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.PullRequestBuildStatusDecorator;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.UnifyConfiguration;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket.client.BitbucketClient;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket.client.BitbucketClientFactory;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket.client.BitbucketException;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket.client.model.AnnotationUploadLimit;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket.client.model.BitbucketConfiguration;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket.client.model.CodeInsightsAnnotation;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket.client.model.CodeInsightsReport;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket.client.model.DataValue;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket.client.model.ReportData;
import com.google.common.annotations.VisibleForTesting;
import org.sonar.api.ce.posttask.QualityGate;
import org.sonar.api.issue.Issue;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.rule.Severity;
import org.sonar.api.rules.RuleType;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.ce.task.projectanalysis.component.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.stream.Collectors.toSet;

public class BitbucketPullRequestDecorator implements PullRequestBuildStatusDecorator {

    public static final String PULL_REQUEST_BITBUCKET_URL = "com.github.mc1arke.sonarqube.plugin.branch.pullrequest.bitbucket.url";

    public static final String PULL_REQUEST_BITBUCKET_TOKEN = "com.github.mc1arke.sonarqube.plugin.branch.pullrequest.bitbucket.token";

    public static final String PULL_REQUEST_BITBUCKET_OAUTH2_KEY = "com.github.mc1arke.sonarqube.plugin.branch.pullrequest.bitbucket.oauth2.key";

    public static final String PULL_REQUEST_BITBUCKET_PROJECT_KEY = "sonar.pullrequest.bitbucket.projectKey";

    public static final String PULL_REQUEST_BITBUCKET_REPOSITORY_SLUG = "sonar.pullrequest.bitbucket.repositorySlug";

    private static final Logger LOGGER = Loggers.get(BitbucketPullRequestDecorator.class);

    private static final DecorationResult DEFAULT_DECORATION_RESULT = DecorationResult.builder().build();

    private static final List<String> OPEN_ISSUE_STATUSES =
            Issue.STATUSES.stream().filter(s -> !Issue.STATUS_CLOSED.equals(s) && !Issue.STATUS_RESOLVED.equals(s))
                    .collect(Collectors.toList());

    @Override
    public String name() {
        return "BitbucketServer";
    }

    @Override
    public DecorationResult decorateQualityGateStatus(AnalysisDetails analysisDetails, UnifyConfiguration configuration) {
        String project = configuration.getRequiredProperty(PULL_REQUEST_BITBUCKET_PROJECT_KEY);
        String repo = configuration.getRequiredProperty(PULL_REQUEST_BITBUCKET_REPOSITORY_SLUG);
        String url = configuration.getRequiredProperty(PULL_REQUEST_BITBUCKET_URL);
        String token = configuration.getRequiredProperty(PULL_REQUEST_BITBUCKET_TOKEN);
        String oauth2Key = configuration.getRequiredProperty(PULL_REQUEST_BITBUCKET_OAUTH2_KEY);
        BitbucketConfiguration bitbucketConfiguration = new BitbucketConfiguration(url, token, oauth2Key, repo, project);
        boolean pullRequestApprovalEnabled = Boolean.parseBoolean(configuration.getRequiredProperty(PULL_REQUEST_APPROVAL_ENABLED));

        BitbucketClient client = createClient(bitbucketConfiguration);

        try {
            if (!client.supportsCodeInsights()) {
                LOGGER.warn("Your Bitbucket instance does not support the Code Insights API.");
                return DEFAULT_DECORATION_RESULT;
            }

            CodeInsightsReport codeInsightsReport = client.createCodeInsightsReport(
                    toReport(client, analysisDetails),
                    reportDescription(analysisDetails),
                    analysisDetails.getAnalysisDate().toInstant(),
                    analysisDetails.getDashboardUrl(),
                    format("%s/common/icon.png", analysisDetails.getBaseImageUrl()),
                    analysisDetails.getQualityGateStatus()
            );

            client.uploadReport(project, repo,
                    analysisDetails.getCommitSha(), codeInsightsReport);

            updateAnnotations(client, project, repo, analysisDetails);

            if (pullRequestApprovalEnabled) {
                client.appovePullRequest(project, repo,
                        Integer.parseInt(analysisDetails.getBranchName()),
                        analysisDetails.getQualityGateStatus() != QualityGate.Status.OK);
            }
        } catch (IOException e) {
            LOGGER.error("Could not decorate pull request for project {}", analysisDetails.getAnalysisProjectKey(), e);
        }

        return DEFAULT_DECORATION_RESULT;
    }

    @VisibleForTesting
    BitbucketClient createClient(BitbucketConfiguration bitbucketConfiguration) {
        return BitbucketClientFactory.createClient(bitbucketConfiguration);
    }

    private List<ReportData> toReport(BitbucketClient client, AnalysisDetails analysisDetails) {
        Map<RuleType, Long> rules = analysisDetails.countRuleByType();

        List<ReportData> reportData = new ArrayList<>();
        reportData.add(reliabilityReport(rules.get(RuleType.BUG)));
        reportData.add(new ReportData("Code coverage", new DataValue.Percentage(newCoverage(analysisDetails))));
        reportData.add(securityReport(rules.get(RuleType.VULNERABILITY), rules.get(RuleType.SECURITY_HOTSPOT)));
        reportData.add(new ReportData("Duplication", new DataValue.Percentage(newDuplication(analysisDetails))));
        reportData.add(maintainabilityReport(rules.get(RuleType.CODE_SMELL)));
        reportData.add(new ReportData("Analysis details", client.createLinkDataValue(analysisDetails.getDashboardUrl())));

        return reportData;
    }

    private void updateAnnotations(BitbucketClient client, String project, String repo, AnalysisDetails analysisDetails) throws IOException {
        final AtomicInteger chunkCounter = new AtomicInteger(0);

        client.deleteAnnotations(project, repo, analysisDetails.getCommitSha());

        AnnotationUploadLimit uploadLimit = client.getAnnotationUploadLimit();

        Map<Integer, Set<CodeInsightsAnnotation>> annotationChunks = analysisDetails.getPostAnalysisIssueVisitor().getIssues().stream()
                .filter(i -> i.getComponent().getReportAttributes().getScmPath().isPresent())
                .filter(i -> i.getComponent().getType() == Component.Type.FILE)
                .filter(i -> OPEN_ISSUE_STATUSES.contains(i.getIssue().status()))
                .sorted(Comparator.comparing(a -> Severity.ALL.indexOf(a.getIssue().severity())))
                .map(componentIssue -> {
                    String path = componentIssue.getComponent().getReportAttributes().getScmPath().get();
                    return client.createCodeInsightsAnnotation(componentIssue.getIssue().key(),
                            Optional.ofNullable(componentIssue.getIssue().getLine()).orElse(0),
                            analysisDetails.getIssueUrl(componentIssue.getIssue().key()),
                            componentIssue.getIssue().getMessage(),
                            path,
                            toBitbucketSeverity(componentIssue.getIssue().severity()),
                            toBitbucketType(componentIssue.getIssue().type()));
                }).collect(Collectors.groupingBy(s -> chunkCounter.getAndIncrement() / uploadLimit.getAnnotationBatchSize(), toSet()));

        int totalAnnotationsCounter = 1;
        for (Set<CodeInsightsAnnotation> annotations : annotationChunks.values()) {
            try {
                if (exceedsMaximumNumberOfAnnotations(totalAnnotationsCounter++, uploadLimit)) {
                    LOGGER.warn("This project has too many issues. The provider only supports {}." +
                            " The remaining annotations will be truncated.", uploadLimit.getTotalAllowedAnnotations());
                    break;
                }

                client.uploadAnnotations(project, repo, analysisDetails.getCommitSha(), annotations);
            } catch (BitbucketException e) {
                if (e.isError(BitbucketException.PAYLOAD_TOO_LARGE)) {
                    LOGGER.warn("The annotations will be truncated since the maximum number of annotations for this report has been reached.");
                } else {
                    throw e;
                }
            }
        }
    }

    @VisibleForTesting
    static boolean exceedsMaximumNumberOfAnnotations(int chunkCounter, AnnotationUploadLimit uploadLimit) {
        return (chunkCounter * uploadLimit.getAnnotationBatchSize()) > uploadLimit.getTotalAllowedAnnotations();
    }

    private String toBitbucketSeverity(String severity) {
        if (severity == null) {
            return "LOW";
        }
        switch (severity) {
            case Severity.BLOCKER:
            case Severity.CRITICAL:
                return "HIGH";
            case Severity.MAJOR:
                return "MEDIUM";
            default:
                return "LOW";
        }
    }

    private String toBitbucketType(RuleType sonarqubeType) {
        switch (sonarqubeType) {
            case SECURITY_HOTSPOT:
            case VULNERABILITY:
                return "VULNERABILITY";
            case CODE_SMELL:
                return "CODE_SMELL";
            case BUG:
                return "BUG";
            default:
                throw new IllegalStateException(format("%s is not a valid ruleType.", sonarqubeType));
        }
    }

    private ReportData securityReport(Long vulnerabilities, Long hotspots) {
        String vulnerabilityDescription = vulnerabilities == 1 ? "Vulnerability" : "Vulnerabilities";
        String hotspotDescription = hotspots == 1 ? "Hotspot" : "Hotspots";
        String security = format("%d %s (and %d %s)", vulnerabilities, vulnerabilityDescription, hotspots, hotspotDescription);
        return new ReportData("Security", new DataValue.Text(security));
    }

    private ReportData reliabilityReport(Long bugs) {
        String description = bugs == 1 ? "Bug" : "Bugs";
        return new ReportData("Reliability", new DataValue.Text(format("%d %s", bugs, description)));
    }

    private ReportData maintainabilityReport(Long codeSmells) {
        String description = codeSmells == 1 ? "Code Smell" : "Code Smells";
        return new ReportData("Maintainability", new DataValue.Text(format("%d %s", codeSmells, description)));
    }

    private String reportDescription(AnalysisDetails details) {
        String header = details.getQualityGateStatus() == QualityGate.Status.OK ? "Quality Gate passed" : "Quality Gate failed";
        String body = details.findFailedConditions().stream()
                .map(AnalysisDetails::format)
                .map(s -> format("- %s", s))
                .collect(Collectors.joining(System.lineSeparator()));
        return format("%s%n%s", header, body);
    }

    private BigDecimal newCoverage(AnalysisDetails details) {
        return details.findQualityGateCondition(CoreMetrics.NEW_COVERAGE_KEY)
                .filter(condition -> condition.getStatus() != QualityGate.EvaluationStatus.NO_VALUE)
                .map(QualityGate.Condition::getValue)
                .map(BigDecimal::new)
                .orElse(BigDecimal.ZERO);
    }

    private BigDecimal newDuplication(AnalysisDetails details) {
        return details.findQualityGateCondition(CoreMetrics.NEW_DUPLICATED_LINES_DENSITY_KEY)
                .filter(condition -> condition.getStatus() != QualityGate.EvaluationStatus.NO_VALUE)
                .map(QualityGate.Condition::getValue)
                .map(BigDecimal::new)
                .orElse(BigDecimal.ZERO);
    }
}
