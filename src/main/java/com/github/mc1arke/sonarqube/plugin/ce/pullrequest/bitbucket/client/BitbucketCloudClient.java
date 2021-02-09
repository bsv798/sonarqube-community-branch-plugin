/*
 * Copyright (C) 2020 Marvin Wichmann
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
package com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket.client.auth.Oauth2Authenticator;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket.client.model.AnnotationUploadLimit;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket.client.model.BitbucketConfiguration;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket.client.model.CodeInsightsAnnotation;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket.client.model.CodeInsightsReport;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket.client.model.DataValue;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket.client.model.ReportData;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket.client.model.cloud.CloudAnnotation;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket.client.model.cloud.CloudCreateReportRequest;
import com.google.common.annotations.VisibleForTesting;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.OkHttpClient.Builder;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.sonar.api.ce.posttask.QualityGate;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;

import java.io.IOException;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static java.lang.String.format;


public class BitbucketCloudClient implements BitbucketClient {
    private static final Logger LOGGER = Loggers.get(BitbucketCloudClient.class);
    private static final String REPORT_KEY = "com.github.mc1arke.sonarqube";
    private static final MediaType APPLICATION_JSON_MEDIA_TYPE = MediaType.get("application/json");
    private static final RequestBody EMPTY_JSON_REQUEST_BODY = RequestBody.create(APPLICATION_JSON_MEDIA_TYPE, "{}");
    private static final String TITLE = "SonarQube";
    private static final String REPORTER = "SonarQube";
    private static final String LINK_TEXT = "Go to SonarQube";

    private final BitbucketConfiguration config;
    private final ObjectMapper objectMapper;
    private final Oauth2Authenticator oauth2;

    public BitbucketCloudClient(BitbucketConfiguration config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.oauth2 = new Oauth2Authenticator(config, objectMapper);
    }

    @Override
    public CodeInsightsAnnotation createCodeInsightsAnnotation(String issueKey, int line, String issueUrl, String message,
                                                               String path, String severity, String type) {
        return new CloudAnnotation(issueKey,
                line,
                issueUrl,
                message,
                path,
                severity,
                type);
    }

    @Override
    public CodeInsightsReport createCodeInsightsReport(List<ReportData> reportData, String reportDescription,
                                                       Instant creationDate, String dashboardUrl, String logoUrl,
                                                       QualityGate.Status status) {
        return new CloudCreateReportRequest(
                reportData,
                reportDescription,
                TITLE,
                REPORTER,
                Date.from(creationDate),
                dashboardUrl, // you need to change this to a real https URL for local debugging since localhost will get declined by the API
                logoUrl,
                "COVERAGE",
                QualityGate.Status.ERROR.equals(status) ? "FAILED" : "PASSED"
        );
    }

    @Override
    public void deleteAnnotations(String project, String repo, String commitSha) throws IOException {
        // not needed here.
    }

    public void uploadAnnotations(String project, String repository, String commit, Set<CodeInsightsAnnotation> baseAnnotations) throws IOException {
        Set<CloudAnnotation> annotations = baseAnnotations.stream().map(annotation -> (CloudAnnotation) annotation).collect(Collectors.toSet());

        if (annotations.isEmpty()) {
            return;
        }

        Request req = new Request.Builder()
                .post(RequestBody.create(APPLICATION_JSON_MEDIA_TYPE, objectMapper.writeValueAsString(annotations)))
                .url(format("%s/2.0/repositories/%s/%s/commit/%s/reports/%s/annotations", config.getUrl(), project, repository, commit, REPORT_KEY))
                .build();

        LOGGER.info("Creating annotations on bitbucket cloud");
        LOGGER.debug("Create annotations: " + objectMapper.writeValueAsString(annotations));

        try (Response response = getClient().newCall(req).execute()) {
            validate(response);
        }
    }

    @Override
    public DataValue createLinkDataValue(String dashboardUrl) {
        return new DataValue.CloudLink(LINK_TEXT, dashboardUrl);
    }

    @Override
    public void uploadReport(String project, String repository, String commit, CodeInsightsReport codeInsightReport) throws IOException {
        deleteExistingReport(project, repository, commit);

        String body = objectMapper.writeValueAsString(codeInsightReport);
        Request req = new Request.Builder()
                .put(RequestBody.create(APPLICATION_JSON_MEDIA_TYPE, body))
                .url(format("%s/2.0/repositories/%s/%s/commit/%s/reports/%s", config.getUrl(), project, repository, commit, REPORT_KEY))
                .build();

        LOGGER.info("Create report on bitbucket cloud");
        LOGGER.debug("Create report: " + body);

        try (Response response = getClient().newCall(req).execute()) {
            validate(response);
        }
    }

    @Override
    public boolean supportsCodeInsights() {
        return true;
    }

    @Override
    public AnnotationUploadLimit getAnnotationUploadLimit() {
        return new AnnotationUploadLimit(100, 1000);
    }

    void deleteExistingReport(String project, String repository, String commit) throws IOException {
        Request req = new Request.Builder()
                .delete()
                .url(format("%s/2.0/repositories/%s/%s/commit/%s/reports/%s", config.getUrl(), project, repository, commit, REPORT_KEY))
                .build();

        LOGGER.info("Deleting existing reports on bitbucket cloud");

        try (Response response = getClient().newCall(req).execute()) {
            // we dont need to validate the output here since most of the time this call will just return a 404
        }
    }

    @VisibleForTesting
    OkHttpClient getClient() {
        Builder builder = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    Request newRequest = chain.request().newBuilder()
                            .addHeader("Authorization", format("Basic %s", config.getToken()))
                            .addHeader("Accept", APPLICATION_JSON_MEDIA_TYPE.toString())
                            .build();
                    return chain.proceed(newRequest);
                });

        if (isOauth2()) {
            builder.authenticator(oauth2);
            builder.addInterceptor(oauth2);
        }

        return builder.build();
    }

    void validate(Response response) throws IOException {
        if (!response.isSuccessful()) {
            String error;
            if (response.body() != null) {
                error = response.body().string();
            } else {
                error = "Request failed but Bitbucket didn't respond with a proper error message";
            }

            throw new BitbucketCloudException(response.code(), error);
        }
    }

    @Override
    public void appovePullRequest(String project, String repository, int pullRequestId, boolean unapprove)
            throws IOException {
        okhttp3.Request.Builder builder = new Request.Builder()
                .url(format("%s/2.0/repositories/%s/%s/pullrequests/%d/approve", config.getUrl(), project, repository, pullRequestId));
        Request req = (unapprove) ? builder.delete().build() : builder.post(EMPTY_JSON_REQUEST_BODY).build();
        String approveAction = (unapprove) ? "Unapproving" : "Approving";

        LOGGER.info(format("%s pull request on bitbucket cloud", approveAction));

        try (Response response = getClient().newCall(req).execute()) {
            if (!unapprove && (response.code() != 409)) { // ignore conflict http code due to multiple approvals
                validate(response);
            }
        }
    }

    @Override
    public boolean isOauth2() {
        return (config.getOauth2Key() != null) && !config.getOauth2Key().isEmpty();
    }
}
