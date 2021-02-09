package com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket.client.auth;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.internal.verification.VerificationModeFactory.times;

import java.io.IOException;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.MockitoJUnitRunner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket.client.model.BitbucketConfiguration;

import okhttp3.Call;
import okhttp3.Interceptor.Chain;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

@RunWith(MockitoJUnitRunner.class)
public class Oauth2AuthenticatorTest {

    private Oauth2Authenticator oauth2;
    private ObjectMapper mapper;
    private OkHttpClient client;

    @Before
    public void init() {
        BitbucketConfiguration config = new BitbucketConfiguration("https://api.bitbucket.org",
                "oauth_token", "oauth_key", "repository", "project");

        mapper = spy(new ObjectMapper());
        oauth2 = spy(new Oauth2Authenticator(config, mapper));
        client = spy(new OkHttpClient.Builder()
                .authenticator(oauth2)
                .addInterceptor(oauth2)
                .build());
    }

    @Test
    public void getAccessTokenTest() throws IOException {
        ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);

        mockAccessToken();

        assertEquals("oauth_access_token", oauth2.getAccessToken());
        verify(client, times(2)).newCall(captor.capture());
        assertEquals("Basic b2F1dGhfa2V5Om9hdXRoX3Rva2Vu", captor.getValue().header("Authorization"));
    }

    private void mockAccessToken() throws IOException {
        Call call = mock(Call.class);
        Response response = mock(Response.class);
        ResponseBody responseBody = mock(ResponseBody.class);

        when(oauth2.getClient()).thenReturn(client);
        when(client.newCall(any())).thenReturn(call);
        when(call.execute()).thenReturn(response);
        when(response.body()).thenReturn(responseBody);
        when(responseBody.bytes()).thenReturn("{\"access_token\": \"oauth_access_token\"}".getBytes());
    }

    @Test
    public void interceptTest() throws IOException {
        Request request = new Request.Builder()
                .url("http://url.com")
                .build();
        Chain chain = mock(Chain.class);
        Response response = mock(Response.class);
        ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);

        when(chain.request()).thenReturn(request);
        when(chain.proceed(any())).thenReturn(response);
        when(response.code()).thenReturn(200);
        when(oauth2.getCachedAccessToken()).thenReturn("cached_access_token");

        oauth2.intercept(chain);

        verify(chain, times(1)).proceed(captor.capture());
        assertEquals("Bearer cached_access_token", captor.getValue().header("Authorization"));
    }

    @Test
    public void intercept403Test() throws IOException {
        interceptCodeTest(403);
    }

    private void interceptCodeTest(int code) throws IOException {
        Request request = new Request.Builder()
                .url("http://url.com")
                .build();
        Chain chain = mock(Chain.class);
        Response firstResponse = mock(Response.class);
        Response secondResponse = mock(Response.class);
        ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);

        when(chain.request()).thenReturn(request);
        when(chain.proceed(any())).thenReturn(firstResponse, secondResponse);
        when(firstResponse.code()).thenReturn(code);
        when(oauth2.getCachedAccessToken()).thenReturn("cached_access_token");

        mockAccessToken();

        oauth2.intercept(chain);

        verify(chain, times(2)).proceed(captor.capture());
        assertEquals("Bearer oauth_access_token", captor.getValue().header("Authorization"));
    }

    @Test
    public void intercept404Test() throws IOException {
        interceptCodeTest(404);
    }

    @Test
    public void authenticateTest() throws IOException {
        Request request = new Request.Builder()
                .url("http://url.com")
                .build();
        Request authenticatedRequest = null;
        Response response = mock(Response.class);
        ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);

        when(response.request()).thenReturn(request);

        mockAccessToken();

        authenticatedRequest = oauth2.authenticate(null, response);

        verify(client, times(2)).newCall(captor.capture());
        assertEquals("Basic b2F1dGhfa2V5Om9hdXRoX3Rva2Vu", captor.getValue().header("Authorization"));
        assertEquals("Bearer oauth_access_token", authenticatedRequest.header("Authorization"));
    }
}
