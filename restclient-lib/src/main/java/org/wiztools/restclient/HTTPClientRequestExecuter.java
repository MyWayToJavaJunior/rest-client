package org.wiztools.restclient;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponse;
import org.apache.http.ProtocolException;
import org.apache.http.ProtocolVersion;
import org.apache.http.auth.AuthScheme;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.AuthState;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpOptions;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpTrace;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.params.AuthPolicy;
import org.apache.http.client.protocol.ClientContext;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.conn.ssl.X509HostnameVerifier;
import org.apache.http.entity.AbstractHttpEntity;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.DefaultRedirectHandler;
import org.apache.http.message.AbstractHttpMessage;
import org.apache.http.message.BasicHeader;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.CoreProtocolPNames;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.ExecutionContext;
import org.apache.http.protocol.HttpContext;
import org.wiztools.commons.Charsets;
import org.wiztools.commons.StreamUtil;
import org.wiztools.commons.StringUtil;

/**
 *
 * @author subwiz
 */
class HTTPClientRequestExecuter implements RequestExecuter {

    private static final Logger LOG = Logger.getLogger(HTTPClientRequestExecuter.class.getName());

    private DefaultHttpClient httpclient;

    private boolean interruptedShutdown = false;
    private boolean isRequestCompleted = false;

    /*
     * This instance variable is for avoiding multiple execution of requests
     * on the same RequestExecuter object. We know it is not the perfect solution
     * (as it does not synchronize access to shared variable), but is
     * fine for finding this type of error during development phase.
     */
    private boolean isRequestStarted = false;

    public void execute(Request request, View... views) {
        // Verify if this is the first call to this object:
        if(isRequestStarted){
            throw new MultipleRequestInSameRequestExecuterException(
                    "A RequestExecuter object can be used only once!");
        }
        isRequestStarted = true;

        // Proceed with execution:
        for(View view: views){
            view.doStart(request);
        }

        URL url = request.getUrl();
        String urlHost = url.getHost();
        int urlPort = url.getPort()==-1?url.getDefaultPort():url.getPort();
        String urlProtocol = url.getProtocol();
        String urlStr = url.toString();

        // Needed for specifying HTTP pre-emptive authentication
        HttpContext httpContext = null;

        httpclient = new DefaultHttpClient();

        // Set HTTP version
        HTTPVersion httpVersion = request.getHttpVersion();
        ProtocolVersion protocolVersion =
                httpVersion==HTTPVersion.HTTP_1_1? new ProtocolVersion("HTTP", 1, 1):
                    new ProtocolVersion("HTTP", 1, 0);
        httpclient.getParams().setParameter(CoreProtocolPNames.PROTOCOL_VERSION,
                protocolVersion);

        // Set request timeout (default 1 minute--60000 milliseconds)
        IGlobalOptions options = Implementation.of(IGlobalOptions.class);
        options.acquire();
        HttpConnectionParams.setConnectionTimeout(httpclient.getParams(),
                Integer.parseInt(options.getProperty("request-timeout-in-millis")));
        options.release();

        // Set proxy
        ProxyConfig proxy = ProxyConfig.getInstance();
        proxy.acquire();
        if (proxy.isEnabled()) {
            final HttpHost proxyHost = new HttpHost(proxy.getHost(), proxy.getPort(), "http");
            if (proxy.isAuthEnabled()) {
                httpclient.getCredentialsProvider().setCredentials(
                        new AuthScope(proxy.getHost(), proxy.getPort()),
                        new UsernamePasswordCredentials(proxy.getUsername(), new String(proxy.getPassword())));
            }
            httpclient.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY, proxyHost);
        }
        proxy.release();

        // HTTP Authentication
        boolean authEnabled = request.getAuthMethods().size() > 0 ? true : false;
        if (authEnabled) {
            String uid = request.getAuthUsername();
            String pwd = new String(request.getAuthPassword());
            String host = StringUtil.isStrEmpty(request.getAuthHost()) ? urlHost : request.getAuthHost();
            String realm = StringUtil.isStrEmpty(request.getAuthRealm()) ? AuthScope.ANY_REALM : request.getAuthRealm();

            // Type of authentication
            List<String> authPrefs = new ArrayList<String>(2);
            List<HTTPAuthMethod> authMethods = request.getAuthMethods();
            for(HTTPAuthMethod authMethod: authMethods){
                switch(authMethod){
                    case BASIC:
                        authPrefs.add(AuthPolicy.BASIC);
                        break;
                    case DIGEST:
                        authPrefs.add(AuthPolicy.DIGEST);
                        break;
                }
            }
            httpclient.getParams().setParameter("http.auth.scheme-pref", authPrefs);

            httpclient.getCredentialsProvider().setCredentials(
                    new AuthScope(host, urlPort, realm),
                    new UsernamePasswordCredentials(uid, pwd));

            // preemptive mode
            // http://svn.apache.org/repos/asf/httpcomponents/httpclient/trunk/module-client/src/examples/org/apache/http/examples/client/ClientPreemptiveBasicAuthentication.java
            if (request.isAuthPreemptive()) {
                BasicHttpContext localcontext = new BasicHttpContext();
                BasicScheme basicAuth = new BasicScheme();
                localcontext.setAttribute("preemptive-auth", basicAuth);
                httpclient.addRequestInterceptor(new PreemptiveAuth(), 0);
                httpContext = localcontext;
            }
        }

        AbstractHttpMessage method = null;

        final HTTPMethod httpMethod = request.getMethod();
        try {
            switch(httpMethod){
                case GET:
                    method = new HttpGet(urlStr);
                    break;
                case POST:
                    method = new HttpPost(urlStr);
                    break;
                case PUT:
                    method = new HttpPut(urlStr);
                    break;
                case DELETE:
                    method = new HttpDelete(urlStr);
                    break;
                case HEAD:
                    method = new HttpHead(urlStr);
                    break;
                case OPTIONS:
                    method = new HttpOptions(urlStr);
                    break;
                case TRACE:
                    method = new HttpTrace(urlStr);
                    break;
            }
            method.setParams(new BasicHttpParams().setParameter(urlStr, url));

            // Get request headers
            Map<String, String> header_data = request.getHeaders();
            for (String key : header_data.keySet()) {
                String value = header_data.get(key);
                Header header = new BasicHeader(key, value);
                method.addHeader(header);
            }

            // POST/PUT method specific logic
            if (method instanceof HttpEntityEnclosingRequest) {

                HttpEntityEnclosingRequest eeMethod = (HttpEntityEnclosingRequest) method;

                // Create and set RequestEntity
                ReqEntity bean = request.getBody();
                if (bean != null) {
                    try {
                        AbstractHttpEntity entity = new ByteArrayEntity(bean.getBody().getBytes(bean.getCharSet()));
                        entity.setContentType(bean.getContentTypeCharsetFormatted());
                        eeMethod.setEntity(entity);
                    } catch (UnsupportedEncodingException ex) {
                        for(View view: views){
                            view.doError(Util.getStackTrace(ex));
                            view.doEnd();
                        }
                        return;
                    }
                }
            }

            // SSL
            String trustStorePath = request.getSslTrustStore();
            char[] trustStorePassword = request.getSslTrustStorePassword();
            if(urlProtocol.equalsIgnoreCase("https") && !StringUtil.isStrEmpty(trustStorePath)){
                KeyStore trustStore  = KeyStore.getInstance(KeyStore.getDefaultType());
                FileInputStream instream = new FileInputStream(new File(trustStorePath));
                try{
                    trustStore.load(instream, trustStorePassword);
                }
                finally{
                    instream.close();
                }

                SSLSocketFactory socketFactory = new SSLSocketFactory(trustStore);
                SSLHostnameVerifier verifier = request.getSslHostNameVerifier();
                X509HostnameVerifier hcVerifier = null;
                switch(verifier){
                    case STRICT:
                        hcVerifier = SSLSocketFactory.STRICT_HOSTNAME_VERIFIER;
                        break;
                    case BROWSER_COMPATIBLE:
                        hcVerifier = SSLSocketFactory.BROWSER_COMPATIBLE_HOSTNAME_VERIFIER;
                        break;
                    case ALLOW_ALL:
                        hcVerifier = SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER;
                        break;
                    default:
                        hcVerifier = SSLSocketFactory.STRICT_HOSTNAME_VERIFIER;
                        break;
                }
                socketFactory.setHostnameVerifier(hcVerifier);
                Scheme sch = new Scheme(urlProtocol, socketFactory, urlPort);
                httpclient.getConnectionManager().getSchemeRegistry().register(sch);
            }

            // How to handle retries and redirects:
            httpclient.setHttpRequestRetryHandler(new DefaultHttpRequestRetryHandler());

            if(request.isAutoRedirect()){
                httpclient.setRedirectHandler(new LoggingRedirectHandler());
            }
            else{ // Do not redirect!
                httpclient.setRedirectHandler(new NoRedirectRedirectHandler());
            }

            // Now Execute:
            long startTime = System.currentTimeMillis();
            HttpResponse http_res = httpclient.execute((HttpUriRequest) method,
                    httpContext);
            long endTime = System.currentTimeMillis();

            ResponseBean response = new ResponseBean();

            response.setExecutionTime(endTime - startTime);

            response.setStatusCode(http_res.getStatusLine().getStatusCode());
            response.setStatusLine(http_res.getStatusLine().toString());

            final Header[] responseHeaders = http_res.getAllHeaders();
            for (Header header : responseHeaders) {
                response.addHeader(header.getName(), header.getValue());
            }

            HttpEntity entity = http_res.getEntity();
            if(entity != null){
                InputStream is = entity.getContent();
                Charset charset = null;
                if(entity.getContentEncoding() != null){
                    charset = Charset.forName(entity.getContentEncoding().getValue());
                }
                else{
                    // Assume UTF-8 when information is not available:
                    charset = Charsets.UTF_8;
                }

                byte[] responseBody = StreamUtil.inputStream2Bytes(is);
                if (responseBody != null) {
                    response.setResponseBodyBytes(responseBody);
                }
            }

            // Now execute tests:
            try {
                junit.framework.TestSuite suite = TestUtil.getTestSuite(request, response);
                if (suite != null) { // suite will be null if there is no associated script
                    TestResult testResult = TestUtil.execute(suite);
                    response.setTestResult(testResult);
                }
            } catch (TestException ex) {
                for(View view: views){
                    view.doError(Util.getStackTrace(ex));
                }
            }

            for(View view: views){
                view.doResponse(response);
            }
        } /*catch (HttpException ex) {
            view.doError(Util.getStackTrace(ex));
        }*/ catch (IOException ex) {
            if(!interruptedShutdown){
                for(View view: views){
                    view.doError(Util.getStackTrace(ex));
                }
            }
            else{
                for(View view: views){
                    view.doCancelled();
                }
            }
        } catch (Exception ex) {
            if(!interruptedShutdown){
                for(View view: views){
                    view.doError(Util.getStackTrace(ex));
                }
            }
            else{
                for(View view: views){
                    view.doCancelled();
                }
            }
        } finally {
            if (method != null && !interruptedShutdown) {
                httpclient.getConnectionManager().shutdown();
            }
            for(View view: views){
                view.doEnd();
            }
            isRequestCompleted = true;
        }
    }

    public void abortExecution(){
        if(!isRequestCompleted){
            ClientConnectionManager conMgr = httpclient.getConnectionManager();
            interruptedShutdown = true;
            conMgr.shutdown();
        }
        else{
            LOG.info("Request already completed. Doing nothing.");
        }
    }
    
    private static final class PreemptiveAuth implements HttpRequestInterceptor {

        public void process(
                final HttpRequest request,
                final HttpContext context) throws HttpException, IOException {

            AuthState authState = (AuthState) context.getAttribute(
                    ClientContext.TARGET_AUTH_STATE);

            // If no auth scheme avaialble yet, try to initialize it preemptively
            if (authState.getAuthScheme() == null) {
                AuthScheme authScheme = (AuthScheme) context.getAttribute(
                        "preemptive-auth");
                CredentialsProvider credsProvider = (CredentialsProvider) context.getAttribute(
                        ClientContext.CREDS_PROVIDER);
                HttpHost targetHost = (HttpHost) context.getAttribute(
                        ExecutionContext.HTTP_TARGET_HOST);
                if (authScheme != null) {
                    Credentials creds = credsProvider.getCredentials(
                            new AuthScope(
                                    targetHost.getHostName(),
                                    targetHost.getPort()));
                    if (creds == null) {
                        throw new HttpException("No credentials for preemptive authentication");
                    }
                    authState.setAuthScheme(authScheme);
                    authState.setCredentials(creds);
                }
            } // if ends
        } // process() method ends
    } // Inner class ends

    class LoggingRedirectHandler extends DefaultRedirectHandler{

        @Override
        public URI getLocationURI(HttpResponse response, HttpContext context) throws ProtocolException {
            URI uri = super.getLocationURI(response, context);
            LOG.info("Redirect response status: " + response.getStatusLine());
            LOG.info("Redirect: " + uri);
            return uri;
        }
        
    }

    class NoRedirectRedirectHandler extends LoggingRedirectHandler{

        @Override
        public boolean isRedirectRequested(HttpResponse response, HttpContext context) {
            return false; // Always return false!
        }

    }
}