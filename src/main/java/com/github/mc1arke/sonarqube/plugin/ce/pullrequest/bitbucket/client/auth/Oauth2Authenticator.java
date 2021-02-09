package com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket.client.auth;

import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket.client.model.BitbucketConfiguration;

import okhttp3.Authenticator;
import okhttp3.Credentials;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.Route;

public class Oauth2Authenticator implements Authenticator, Interceptor {

    private static final String BITBUCKET_OAUTH_URL = "https://bitbucket.org/site/oauth2/access_token";
    private static final MediaType FORM_MEDIA_TYPE = MediaType.get("application/x-www-form-urlencoded");
    private static final RequestBody OAUTH2_REQUEST_BODY = RequestBody.create(FORM_MEDIA_TYPE,
            "grant_type=client_credentials");

    private BitbucketConfiguration config;
    private ObjectMapper objectMapper;
    private String cachedAccessToken;

    protected Oauth2Authenticator() {

    }

    public Oauth2Authenticator(BitbucketConfiguration config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.cachedAccessToken = "";
    }

    @Override
    public Request authenticate(Route route, Response response) throws IOException {
        Request request = response.request().newBuilder()
                .removeHeader("Authorization")
                .header("Authorization", "Bearer " + getAccessToken())
                .build();

        return request;
    }

    public String getCachedAccessToken() throws IOException {
        return getAccessToken(true);
    }

    public String getAccessToken() throws IOException {
        return getAccessToken(false);
    }

    protected String getAccessToken(boolean cached) throws IOException {
        if (!cached) {
            String credentials = Credentials.basic(config.getOauth2Key(), config.getToken());
            OkHttpClient client = getClient();
            Request request = new Request.Builder()
                    .url(BITBUCKET_OAUTH_URL)
                    .post(OAUTH2_REQUEST_BODY)
                    .header("Authorization", credentials)
                    .build();
            Response response = client.newCall(request).execute();
            JsonNode jsonNode = objectMapper.readTree(response.body().bytes());
            String accessToken = jsonNode.get("access_token").asText();

            cachedAccessToken = accessToken;
        }

        return cachedAccessToken;
    }

    protected OkHttpClient getClient() {
        OkHttpClient client = new OkHttpClient.Builder().build();

        return client;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request originalRequest = chain.request();
        Request authenticatedRequest = originalRequest.newBuilder()
                .header("Authorization", "Bearer " + getCachedAccessToken())
                .build();
        Response authenticatedResponse = chain.proceed(authenticatedRequest);

        if ((authenticatedResponse.code() == 403) || (authenticatedResponse.code() == 404)) {
            authenticatedResponse.close();

            authenticatedRequest = originalRequest.newBuilder()
                    .header("Authorization", "Bearer " + getAccessToken())
                    .build();
            authenticatedResponse = chain.proceed(authenticatedRequest);
        }

        return authenticatedResponse;
    }

}
