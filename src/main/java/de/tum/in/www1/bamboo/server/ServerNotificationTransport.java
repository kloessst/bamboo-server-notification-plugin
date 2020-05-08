package de.tum.in.www1.bamboo.server;

import com.atlassian.bamboo.artifact.MutableArtifact;
import com.atlassian.bamboo.build.BuildLoggerManager;
import com.atlassian.bamboo.build.artifact.ArtifactLink;
import com.atlassian.bamboo.build.artifact.ArtifactLinkDataProvider;
import com.atlassian.bamboo.build.artifact.ArtifactLinkManager;
import com.atlassian.bamboo.build.artifact.FileSystemArtifactLinkDataProvider;
import com.atlassian.bamboo.build.logger.BuildLogger;
import com.atlassian.bamboo.chains.ChainResultsSummary;
import com.atlassian.bamboo.chains.ChainStageResult;
import com.atlassian.bamboo.commit.Commit;
import com.atlassian.bamboo.deployments.results.DeploymentResult;
import com.atlassian.bamboo.notification.Notification;
import com.atlassian.bamboo.notification.NotificationTransport;
import com.atlassian.bamboo.plan.cache.ImmutablePlan;
import com.atlassian.bamboo.results.tests.TestResults;
import com.atlassian.bamboo.resultsummary.BuildResultsSummary;
import com.atlassian.bamboo.resultsummary.ResultsSummary;
import com.atlassian.bamboo.resultsummary.tests.TestCaseResultError;
import com.atlassian.bamboo.resultsummary.tests.TestResultsSummary;
import com.atlassian.bamboo.resultsummary.vcs.RepositoryChangeset;
import com.atlassian.bamboo.utils.HttpUtils;
import com.atlassian.bamboo.variable.CustomVariableContext;
import com.atlassian.bamboo.variable.VariableDefinition;
import com.atlassian.bamboo.variable.VariableDefinitionManager;
import com.atlassian.spring.container.ContainerManager;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;

import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.DefaultProxyRoutePlanner;
import org.apache.http.util.EntityUtils;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ServerNotificationTransport implements NotificationTransport
{
    private static final Logger log = Logger.getLogger(ServerNotificationTransport.class);

    private final String webhookUrl;

    private CloseableHttpClient client;

    @Nullable
    private final ImmutablePlan plan;
    @Nullable
    private final ResultsSummary resultsSummary;
    @Nullable
    private final DeploymentResult deploymentResult;
    @Nullable
    private final BuildLoggerManager buildLoggerManager;

    // Will be injected by Bamboo
    private VariableDefinitionManager variableDefinitionManager = (VariableDefinitionManager) ContainerManager.getComponent("variableDefinitionManager");
    private ArtifactLinkManager artifactLinkManager = (ArtifactLinkManager) ContainerManager.getComponent("artifactLinkManager");;

    // Maximum length for the feedback text. The feedback will be truncated afterwards
    private static int FEEDBACK_DETAIL_TEXT_MAX_CHARACTERS = 5000;

    public ServerNotificationTransport(String webhookUrl,
                                       @Nullable ImmutablePlan plan,
                                       @Nullable ResultsSummary resultsSummary,
                                       @Nullable DeploymentResult deploymentResult,
                                       CustomVariableContext customVariableContext,
                                       BuildLoggerManager buildLoggerManager)
    {
        this.webhookUrl = customVariableContext.substituteString(webhookUrl);
        this.plan = plan;
        this.resultsSummary = resultsSummary;
        this.deploymentResult = deploymentResult;
        this.buildLoggerManager = buildLoggerManager;

        URI uri;
        try
        {
            uri = new URI(webhookUrl);
        }
        catch (URISyntaxException e)
        {
            logErrorToBuildLog("Unable to set up proxy settings, invalid URI encountered: " + e);
            log.error("Unable to set up proxy settings, invalid URI encountered: " + e);
            return;
        }

        HttpUtils.EndpointSpec proxyForScheme = HttpUtils.getProxyForScheme(uri.getScheme());
        if (proxyForScheme!=null)
        {
            HttpHost proxy = new HttpHost(proxyForScheme.host, proxyForScheme.port);
            DefaultProxyRoutePlanner routePlanner = new DefaultProxyRoutePlanner(proxy);
            this.client = HttpClients.custom().setRoutePlanner(routePlanner).build();
        }
        else
        {
            this.client = HttpClients.createDefault();
        }
    }

    public void sendNotification(@NotNull Notification notification)
    {
        logToBuildLog("Sending notification");
        try
        {
            HttpPost method = setupPostMethod();
            JSONObject jsonObject = createJSONObject(notification);
            try {
                String secret = (String) jsonObject.get("secret");
                method.addHeader("Authorization", secret);
            } catch (JSONException e) {
                logErrorToBuildLog("Error while getting secret from JSONObject: " + e.getMessage());
                log.error("Error while getting secret from JSONObject: " + e.getMessage(), e);
            }

            method.setEntity(new StringEntity(jsonObject.toString(), ContentType.APPLICATION_JSON.withCharset(StandardCharsets.UTF_8)));

            try {
                logToBuildLog("Executing call to " + method.getURI().toString());
                log.debug(method.getURI().toString());
                log.debug(method.getEntity().toString());
                CloseableHttpResponse closeableHttpResponse = client.execute(method);
                logToBuildLog("Call executed");
                if (closeableHttpResponse != null) {
                    logToBuildLog("Response is not null: " + closeableHttpResponse.toString());

                    StatusLine statusLine = closeableHttpResponse.getStatusLine();
                    if (statusLine != null) {
                        logToBuildLog("StatusLine is not null: " + statusLine.toString());
                        logToBuildLog("StatusCode is: " + statusLine.getStatusCode());
                    } else {
                        logErrorToBuildLog("Statusline is null");
                    }

                    HttpEntity httpEntity = closeableHttpResponse.getEntity();
                    if (httpEntity != null) {
                        String response = EntityUtils.toString(httpEntity);
                        logToBuildLog("Response from entity is: " + response);
                        EntityUtils.consume(httpEntity);
                    } else {
                        logErrorToBuildLog("Httpentity is null");
                    }
                } else {
                    logErrorToBuildLog("Response is null");
                }
            } catch (Exception e) {
                logErrorToBuildLog("Error while sending payload: " + e.getMessage());
                log.error("Error while sending payload: " + e.getMessage(), e);
            }
        }
        catch(URISyntaxException e)
        {
            logErrorToBuildLog("Error parsing webhook url: " + e.getMessage());
            log.error("Error parsing webhook url: " + e.getMessage(), e);
        }
    }

    private HttpPost setupPostMethod() throws URISyntaxException
    {
        HttpPost post = new HttpPost((new URI(webhookUrl)));
        post.setHeader("Content-Type", "application/json");
        return post;
    }

    private JSONObject createJSONObject(Notification notification) {
        logToBuildLog("Creating JSON object");
        JSONObject jsonObject = new JSONObject();
        try {
            // Variable name contains "password" to ensure that the secret is hidden in the UI
            VariableDefinition secretVariable = variableDefinitionManager.getGlobalVariables().stream().filter(vd -> vd.getKey().equals("SERVER_PLUGIN_SECRET_PASSWORD")).findFirst().get();

            jsonObject.put("secret", secretVariable.getValue()); // Used to verify that the request is coming from a legitimate server
            jsonObject.put("notificationType", notification.getDescription());

            if (plan != null) {
                JSONObject planDetails = new JSONObject();
                planDetails.put("key", plan.getPlanKey());


                jsonObject.put("plan", planDetails);
            }

            if (resultsSummary != null) {
                JSONObject buildDetails = new JSONObject();
                buildDetails.put("number", resultsSummary.getBuildNumber());
                buildDetails.put("reason", resultsSummary.getShortReasonSummary());
                buildDetails.put("successful", resultsSummary.isSuccessful());
                buildDetails.put("buildCompletedDate", ZonedDateTime.ofInstant(resultsSummary.getBuildCompletedDate().toInstant(), ZoneId.systemDefault()));
                buildDetails.put("artifact", !resultsSummary.getArtifactLinks().isEmpty());

                TestResultsSummary testResultsSummary = resultsSummary.getTestResultsSummary();
                JSONObject testResultOverview = new JSONObject();
                testResultOverview.put("description", testResultsSummary.getTestSummaryDescription());
                testResultOverview.put("totalCount", testResultsSummary.getTotalTestCaseCount());
                testResultOverview.put("failedCount", testResultsSummary.getFailedTestCaseCount());
                testResultOverview.put("existingFailedCount", testResultsSummary.getExistingFailedTestCount());
                testResultOverview.put("fixedCount", testResultsSummary.getFixedTestCaseCount());
                testResultOverview.put("newFailedCount", testResultsSummary.getNewFailedTestCaseCount());
                testResultOverview.put("ignoredCount", testResultsSummary.getIgnoredTestCaseCount());
                testResultOverview.put("quarantineCount", testResultsSummary.getQuarantinedTestCaseCount());
                testResultOverview.put("skippedCount", testResultsSummary.getSkippedTestCaseCount());
                testResultOverview.put("successfulCount", testResultsSummary.getSuccessfulTestCaseCount());
                testResultOverview.put("duration", testResultsSummary.getTotalTestDuration());

                buildDetails.put("testSummary", testResultOverview);


                JSONArray vcsDetails = new JSONArray();
                for (RepositoryChangeset changeset : resultsSummary.getRepositoryChangesets()) {
                    JSONObject changesetDetails = new JSONObject();
                    changesetDetails.put("id", changeset.getChangesetId());
                    changesetDetails.put("repositoryName", changeset.getRepositoryData().getName());

                    JSONArray commits = new JSONArray();
                    for (Commit commit: changeset.getCommits()) {
                        JSONObject commitDetails = new JSONObject();
                        commitDetails.put("id", commit.getChangeSetId());
                        commitDetails.put("comment", commit.getComment());

                        commits.put(commitDetails);
                    }

                    changesetDetails.put("commits", commits);

                    vcsDetails.put(changesetDetails);
                }
                buildDetails.put("vcs", vcsDetails);

                if (resultsSummary instanceof ChainResultsSummary) {
                    ChainResultsSummary chainResultsSummary = (ChainResultsSummary) resultsSummary;
                    JSONArray jobs = new JSONArray();
                    for (ChainStageResult chainStageResult : chainResultsSummary.getStageResults()) {
                        for (BuildResultsSummary buildResultsSummary : chainStageResult.getBuildResults()) {

                            JSONObject jobDetails = new JSONObject();

                            jobDetails.put("id", buildResultsSummary.getId());

                            logToBuildLog("Loading artifacts for job " + buildResultsSummary.getId());
                            JSONObject artifacts = createArtifactJSONObjectForJob(buildResultsSummary.getProducedArtifactLinks(), buildResultsSummary.getId());

                            logToBuildLog("Loading cached test results for job " + buildResultsSummary.getId());
                            TestResultsContainer testResultsContainer = ServerNotificationRecipient.getCachedTestResults().get(buildResultsSummary.getPlanResultKey().toString());
                            if (testResultsContainer != null) {
                                logToBuildLog("Tests results found");
                                JSONArray successfulTestDetails = createTestsResultsJSONArray(testResultsContainer.getSuccessfulTests(), false);
                                jobDetails.put("successfulTests", successfulTestDetails);

                                JSONArray skippedTestDetails = createTestsResultsJSONArray(testResultsContainer.getSkippedTests(), false);
                                jobDetails.put("skippedTests", skippedTestDetails);

                                JSONArray failedTestDetails = createTestsResultsJSONArray(testResultsContainer.getFailedTests(), true);
                                jobDetails.put("failedTests", failedTestDetails);
                            } else {
                                logErrorToBuildLog("Could not load cached test results!");
                            }
                            jobs.put(jobDetails);
                        }
                    }
                    buildDetails.put("jobs", jobs);

                    // TODO: This ensures outdated versions of Artemis can still process the new request. Will be removed without further notice in the future
                    buildDetails.put("failedJobs", jobs);
                }

                jsonObject.put("build", buildDetails);
            }


        } catch (JSONException e) {
            logErrorToBuildLog("JSON construction error :" + e.getMessage());
            log.error("JSON construction error :" + e.getMessage(), e);
        }

        logToBuildLog("JSON object created");
        return jsonObject;
    }

    private String fileToString(Path path) {
        try {
            // Use convenience methods to read in the files as reports are not expected to be large
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logErrorToBuildLog("Error reading in artifact file " + path.toString() + e.getMessage());
            return null;
        }
    }

    private JSONObject createArtifactJSONObject(Path path) {
        try {
            JSONObject artifactJSON = new JSONObject();
            File artifactFile = path.toFile();

            artifactJSON.put("content", fileToString(path));
            artifactJSON.put("name", artifactFile.getName());
            artifactJSON.put("path", path.toString());
            return artifactJSON;
        } catch (JSONException e) {
            log.error("JSON construction error for " + path.toString(), e);
            return new JSONObject();
        }
    }

    private JSONArray createFileSystemArtifactJSONArray(File rootFile) {
        // Travers the file system starting at the rootFile and create a JSONObject for each regular file encountered
        try (Stream<Path> paths = Files.walk(rootFile.toPath())) {
                Collection<JSONObject> artifactsForCopyPattern = paths.filter(Files::isRegularFile)
                        .map(this::createArtifactJSONObject).collect(Collectors.toList());
                return new JSONArray(artifactsForCopyPattern);
        } catch (IOException e) {
            log.error("Error occurred traversing file system for " + rootFile.getName(), e);
            return new JSONArray();
        }
    }

    private JSONObject createArtifactJSONObjectForJob(Collection<ArtifactLink> artifactLinks, long jobId) throws JSONException {
        JSONObject jobArtifacts = new JSONObject();
        // ArtifactLink is an interface referring to a single artifact configuration defined on job level
        for (ArtifactLink artifactLink: artifactLinks) {
            MutableArtifact artifact = artifactLink.getArtifact();
            ArtifactLinkDataProvider dataProvider = artifactLinkManager.getArtifactLinkDataProvider(artifact);

            if (dataProvider == null) {
                log.debug("ArtifactLinkDataProvider is null for " + artifact.getLabel() + " in job " + jobId);
                logToBuildLog("Could not retrieve data for artifact " + artifact.getLabel() + " in job " + jobId);
                continue;
            }

            /*
             *  Only handles artifact files stored on the server file system
             *  Has to be extended for more advanced or customized artifact handling
             */
            if (dataProvider instanceof FileSystemArtifactLinkDataProvider) {
                FileSystemArtifactLinkDataProvider fileDataProvider = (FileSystemArtifactLinkDataProvider) dataProvider;
                File rootFile = fileDataProvider.getFile();

                /**
                 * Each artifact definition specifies a location and copy pattern to determine which files to keep
                 * The rootFile is a directory if the copy pattern matches multiple files, otherwise it is a regular file
                 */
                jobArtifacts.put(artifact.getLabel(), createFileSystemArtifactJSONArray(rootFile));
            } else {
                log.debug("Unsupported ArtifactLinkDataProvider " + dataProvider.getClass().getSimpleName()
                        + " encountered for label" + artifact.getLabel() + " in job " + jobId);
                logToBuildLog("Unsupported artifact handler configuration encountered for artifact "
                        + artifact.getLabel() + " in job " + jobId);
            }
        }
        return jobArtifacts;
    }

    private JSONObject createTestsResultsJSONObject(TestResults testResults, boolean addErrors) throws JSONException {
        logToBuildLog("Creating test results JSON object for " + testResults.getActualMethodName());
        JSONObject testResultsJSON = new JSONObject();
        testResultsJSON.put("name", testResults.getActualMethodName());
        testResultsJSON.put("methodName", testResults.getMethodName());
        testResultsJSON.put("className", testResults.getClassName());

        if (addErrors) {
            JSONArray testCaseErrorDetails = new JSONArray();
            for(TestCaseResultError testCaseResultError : testResults.getErrors()) {
                String errorMessageString = testCaseResultError.getContent();
                if(errorMessageString != null && errorMessageString.length() > FEEDBACK_DETAIL_TEXT_MAX_CHARACTERS) {
                    errorMessageString = errorMessageString.substring(0, FEEDBACK_DETAIL_TEXT_MAX_CHARACTERS);
                }
                testCaseErrorDetails.put(errorMessageString);
            }
            testResultsJSON.put("errors", testCaseErrorDetails);
        }

        return testResultsJSON;
    }

    private JSONArray createTestsResultsJSONArray(Collection<TestResults> testResultsCollection, boolean addErrors) throws JSONException {
        logToBuildLog("Creating test results JSON array");
        JSONArray testResultsArray = new JSONArray();
        for (TestResults testResults : testResultsCollection) {
            testResultsArray.put(createTestsResultsJSONObject(testResults, addErrors));
        }

        return testResultsArray;
    }

    private void logToBuildLog(String s) {
        if (buildLoggerManager != null && plan != null) {
            BuildLogger buildLogger = buildLoggerManager.getLogger(plan.getPlanKey());
            if (buildLogger != null) {
                buildLogger.addBuildLogEntry("[BAMBOO-SERVER-NOTIFICATION] " + s);
            }
        }
    }

    private void logErrorToBuildLog(String s) {
        if (buildLoggerManager != null && plan != null) {
            BuildLogger buildLogger = buildLoggerManager.getLogger(plan.getPlanKey());
            if (buildLogger != null) {
                buildLogger.addErrorLogEntry("[BAMBOO-SERVER-NOTIFICATION] " + s);
            }
        }
    }
}
