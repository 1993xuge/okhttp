/*
 * Copyright (C) 2016 Square, Inc.
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
package okhttp3.internal.http;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.HttpRetryException;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.security.cert.CertificateException;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocketFactory;

import okhttp3.Address;
import okhttp3.Call;
import okhttp3.CertificatePinner;
import okhttp3.EventListener;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.Route;
import okhttp3.internal.connection.RouteException;
import okhttp3.internal.connection.StreamAllocation;
import okhttp3.internal.http2.ConnectionShutdownException;

import static java.net.HttpURLConnection.HTTP_CLIENT_TIMEOUT;
import static java.net.HttpURLConnection.HTTP_MOVED_PERM;
import static java.net.HttpURLConnection.HTTP_MOVED_TEMP;
import static java.net.HttpURLConnection.HTTP_MULT_CHOICE;
import static java.net.HttpURLConnection.HTTP_PROXY_AUTH;
import static java.net.HttpURLConnection.HTTP_SEE_OTHER;
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE;
import static okhttp3.internal.Util.closeQuietly;
import static okhttp3.internal.http.StatusLine.HTTP_PERM_REDIRECT;
import static okhttp3.internal.http.StatusLine.HTTP_TEMP_REDIRECT;

/**
 * This interceptor recovers from failures and follows redirects as necessary. It may throw an
 * {@link IOException} if the call was canceled.
 */
public final class RetryAndFollowUpInterceptor implements Interceptor {
    /**
     * How many redirects and auth challenges should we attempt? Chrome follows 21 redirects; Firefox,
     * curl, and wget follow 20; Safari follows 16; and HTTP/1.0 recommends 5.
     */
    private static final int MAX_FOLLOW_UPS = 20;

    private final OkHttpClient client;
    private final boolean forWebSocket;
    private volatile StreamAllocation streamAllocation;
    private Object callStackTrace;
    private volatile boolean canceled;

    public RetryAndFollowUpInterceptor(OkHttpClient client, boolean forWebSocket) {
        this.client = client;
        this.forWebSocket = forWebSocket;
    }

    /**
     * Immediately closes the socket connection if it's currently held. Use this to interrupt an
     * in-flight request from any thread. It's the caller's responsibility to close the request body
     * and response body streams; otherwise resources may be leaked.
     * <p>
     * <p>This method is safe to be called concurrently, but provides limited guarantees. If a
     * transport layer connection has been established (such as a HTTP/2 stream) that is terminated.
     * Otherwise if a socket connection is being established, that is terminated.
     */
    public void cancel() {
        canceled = true;
        StreamAllocation streamAllocation = this.streamAllocation;
        if (streamAllocation != null) streamAllocation.cancel();
    }

    public boolean isCanceled() {
        return canceled;
    }

    public void setCallStackTrace(Object callStackTrace) {
        this.callStackTrace = callStackTrace;
    }

    public StreamAllocation streamAllocation() {
        return streamAllocation;
    }

    /**
     *
     * @param chain - 拦截器链的下一个节点
     * @return
     * @throws IOException
     */
    @Override
    public Response intercept(Chain chain) throws IOException {
        //  获取请求的对象
        Request request = chain.request();
        RealInterceptorChain realChain = (RealInterceptorChain) chain;
        Call call = realChain.call();
        EventListener eventListener = realChain.eventListener();

        // 创建StreamAllocation对象
        // 这个实际上是 用来建立执行Http请求的所需要的 网络组件的
        // 该对象虽然在RetryAndFollowUpInterceptor拦截器中创建，但并没有在这个类中使用。
        // 真是使用这个对象的是 ConnectInterceptor拦截器
        // ˙主要用于 1）获取连接服务端的connection 和 2）连接用于服务端进行数据传输的输入输出流
        StreamAllocation streamAllocation = new StreamAllocation(client.connectionPool(),
                createAddress(request.url()), call, eventListener, callStackTrace);
        this.streamAllocation = streamAllocation;

        int followUpCount = 0;
        Response priorResponse = null;
        while (true) {
            // 是否已经被取消
            if (canceled) {
                // 是，则释放streamAllocation，并抛出异常
                streamAllocation.release();
                throw new IOException("Canceled");
            }

            Response response;
            boolean releaseConnection = true;
            try {
                // 调用下一个拦截器链节点的proceed方法，让下一个拦截器先去执行请求，并等待响应返回。
                response = realChain.proceed(request, streamAllocation, null, null);
                releaseConnection = false;
            } catch (RouteException e) {
                // The attempt to connect via a route failed. The request will not have been sent.
                if (!recover(e.getLastConnectException(), streamAllocation, false, request)) {
                    throw e.getFirstConnectException();
                }
                releaseConnection = false;
                // 重试
                continue;
            } catch (IOException e) {
                // An attempt to communicate with a server failed. The request may have been sent.
                // 先判断当前请求是否已经发送了
                boolean requestSendStarted = !(e instanceof ConnectionShutdownException);

                //同样的重试判断
                if (!recover(e, streamAllocation, requestSendStarted, request)) throw e;
                releaseConnection = false;

                // 重试
                continue;
            } finally {
                // We're throwing an unchecked exception. Release any resources.
                // 没有捕获到的异常，最终要释放
                if (releaseConnection) {
                    streamAllocation.streamFailed(null);
                    streamAllocation.release();
                }
            }

            // Attach the prior response if it exists. Such responses never have a body.
            // priorResponse是用来保存前一个Resposne的，这里可以看到将前一个Response和当前的Resposne结合在一起了，
            // 对应的场景是，当获得Resposne后，发现需要重定向，则将当前Resposne设置给priorResponse，再执行一遍流程，
            // 直到不需要重定向了，则将priorResponse和Resposne结合起来。
            if (priorResponse != null) {
                response = response.newBuilder()
                        .priorResponse(priorResponse.newBuilder()
                                .body(null)
                                .build())
                        .build();
            }

            Request followUp;
            try {
                //判断是否需要重定向,如果需要重定向则返回一个重定向的Request，没有则为null
                followUp = followUpRequest(response, streamAllocation.route());
            } catch (IOException e) {
                streamAllocation.release();
                throw e;
            }

            if (followUp == null) {
                //不需要重定向，释放
                streamAllocation.release();
                //返回response
                return response;
            }

            //需要重定向，关闭响应流
            closeQuietly(response.body());

            //重定向次数++,并且小于最大重定向次数MAX_FOLLOW_UPS（20）
            if (++followUpCount > MAX_FOLLOW_UPS) {
                streamAllocation.release();
                throw new ProtocolException("Too many follow-up requests: " + followUpCount);
            }

            //是UnrepeatableRequestBody， 刚才看过也就是是流类型，没有被缓存，不能重定向
            if (followUp.body() instanceof UnrepeatableRequestBody) {
                streamAllocation.release();
                throw new HttpRetryException("Cannot retry streamed HTTP body", response.code());
            }

            // 判断是否相同，不然重新创建一个streamAllocation
            if (!sameConnection(response, followUp.url())) {
                streamAllocation.release();
                streamAllocation = new StreamAllocation(client.connectionPool(),
                        createAddress(followUp.url()), call, eventListener, callStackTrace);
                this.streamAllocation = streamAllocation;
            } else if (streamAllocation.codec() != null) {
                throw new IllegalStateException("Closing the body of " + response
                        + " didn't close its backing stream. Bad interceptor?");
            }

            //赋值再来！
            request = followUp;
            priorResponse = response;
        }
    }

    private Address createAddress(HttpUrl url) {
        SSLSocketFactory sslSocketFactory = null;
        HostnameVerifier hostnameVerifier = null;
        CertificatePinner certificatePinner = null;
        if (url.isHttps()) {
            sslSocketFactory = client.sslSocketFactory();
            hostnameVerifier = client.hostnameVerifier();
            certificatePinner = client.certificatePinner();
        }

        return new Address(url.host(), url.port(), client.dns(), client.socketFactory(),
                sslSocketFactory, hostnameVerifier, certificatePinner, client.proxyAuthenticator(),
                client.proxy(), client.protocols(), client.connectionSpecs(), client.proxySelector());
    }

    /**
     * Report and attempt to recover from a failure to communicate with a server. Returns true if
     * {@code e} is recoverable, or false if the failure is permanent. Requests with a body can only
     * be recovered if the body is buffered or if the failure occurred before the request has been
     * sent.
     */
    private boolean recover(IOException e, StreamAllocation streamAllocation,
                            boolean requestSendStarted, Request userRequest) {
        streamAllocation.streamFailed(e);

        // The application layer has forbidden retries.
        if (!client.retryOnConnectionFailure()) return false;

        // We can't send the request body again.
        if (requestSendStarted && userRequest.body() instanceof UnrepeatableRequestBody)
            return false;

        // This exception is fatal.
        if (!isRecoverable(e, requestSendStarted)) return false;

        // No more routes to attempt.
        if (!streamAllocation.hasMoreRoutes()) return false;

        // For failure recovery, use the same route selector with a new connection.
        return true;
    }

    private boolean isRecoverable(IOException e, boolean requestSendStarted) {
        // If there was a protocol problem, don't recover.
        if (e instanceof ProtocolException) {
            return false;
        }

        // If there was an interruption don't recover, but if there was a timeout connecting to a route
        // we should try the next route (if there is one).
        if (e instanceof InterruptedIOException) {
            return e instanceof SocketTimeoutException && !requestSendStarted;
        }

        // Look for known client-side or negotiation errors that are unlikely to be fixed by trying
        // again with a different route.
        if (e instanceof SSLHandshakeException) {
            // If the problem was a CertificateException from the X509TrustManager,
            // do not retry.
            if (e.getCause() instanceof CertificateException) {
                return false;
            }
        }
        if (e instanceof SSLPeerUnverifiedException) {
            // e.g. a certificate pinning error.
            return false;
        }

        // An example of one we might want to retry with a different route is a problem connecting to a
        // proxy and would manifest as a standard IOException. Unless it is one we know we should not
        // retry, we return true and try a new route.
        return true;
    }

    /**
     * Figures out the HTTP request to make in response to receiving {@code userResponse}. This will
     * either add authentication headers, follow redirects or handle a client request timeout. If a
     * follow-up is either unnecessary or not applicable, this returns null.
     */
    private Request followUpRequest(Response userResponse, Route route) throws IOException {
        if (userResponse == null) throw new IllegalStateException();
        int responseCode = userResponse.code();

        final String method = userResponse.request().method();
        switch (responseCode) {
            case HTTP_PROXY_AUTH:
                Proxy selectedProxy = route != null
                        ? route.proxy()
                        : client.proxy();
                if (selectedProxy.type() != Proxy.Type.HTTP) {
                    throw new ProtocolException("Received HTTP_PROXY_AUTH (407) code while not using proxy");
                }
                return client.proxyAuthenticator().authenticate(route, userResponse);

            case HTTP_UNAUTHORIZED:
                return client.authenticator().authenticate(route, userResponse);

            case HTTP_PERM_REDIRECT:
            case HTTP_TEMP_REDIRECT:
                // "If the 307 or 308 status code is received in response to a request other than GET
                // or HEAD, the user agent MUST NOT automatically redirect the request"
                if (!method.equals("GET") && !method.equals("HEAD")) {
                    return null;
                }
                // fall-through
            case HTTP_MULT_CHOICE:
            case HTTP_MOVED_PERM:
            case HTTP_MOVED_TEMP:
            case HTTP_SEE_OTHER:
                // Does the client allow redirects?
                if (!client.followRedirects()) return null;

                String location = userResponse.header("Location");
                if (location == null) return null;
                HttpUrl url = userResponse.request().url().resolve(location);

                // Don't follow redirects to unsupported protocols.
                if (url == null) return null;

                // If configured, don't follow redirects between SSL and non-SSL.
                boolean sameScheme = url.scheme().equals(userResponse.request().url().scheme());
                if (!sameScheme && !client.followSslRedirects()) return null;

                // Most redirects don't include a request body.
                Request.Builder requestBuilder = userResponse.request().newBuilder();
                if (HttpMethod.permitsRequestBody(method)) {
                    final boolean maintainBody = HttpMethod.redirectsWithBody(method);
                    if (HttpMethod.redirectsToGet(method)) {
                        requestBuilder.method("GET", null);
                    } else {
                        RequestBody requestBody = maintainBody ? userResponse.request().body() : null;
                        requestBuilder.method(method, requestBody);
                    }
                    if (!maintainBody) {
                        requestBuilder.removeHeader("Transfer-Encoding");
                        requestBuilder.removeHeader("Content-Length");
                        requestBuilder.removeHeader("Content-Type");
                    }
                }

                // When redirecting across hosts, drop all authentication headers. This
                // is potentially annoying to the application layer since they have no
                // way to retain them.
                if (!sameConnection(userResponse, url)) {
                    requestBuilder.removeHeader("Authorization");
                }

                return requestBuilder.url(url).build();

            case HTTP_CLIENT_TIMEOUT:
                // 408's are rare in practice, but some servers like HAProxy use this response code. The
                // spec says that we may repeat the request without modifications. Modern browsers also
                // repeat the request (even non-idempotent ones.)
                if (!client.retryOnConnectionFailure()) {
                    // The application layer has directed us not to retry the request.
                    return null;
                }

                if (userResponse.request().body() instanceof UnrepeatableRequestBody) {
                    return null;
                }

                if (userResponse.priorResponse() != null
                        && userResponse.priorResponse().code() == HTTP_CLIENT_TIMEOUT) {
                    // We attempted to retry and got another timeout. Give up.
                    return null;
                }

                if (retryAfter(userResponse, 0) > 0) {
                    return null;
                }

                return userResponse.request();

            case HTTP_UNAVAILABLE:
                if (userResponse.priorResponse() != null
                        && userResponse.priorResponse().code() == HTTP_UNAVAILABLE) {
                    // We attempted to retry and got another timeout. Give up.
                    return null;
                }

                if (retryAfter(userResponse, Integer.MAX_VALUE) == 0) {
                    // specifically received an instruction to retry without delay
                    return userResponse.request();
                }

                return null;

            default:
                return null;
        }
    }

    private int retryAfter(Response userResponse, int defaultDelay) {
        String header = userResponse.header("Retry-After");

        if (header == null) {
            return defaultDelay;
        }

        // https://tools.ietf.org/html/rfc7231#section-7.1.3
        // currently ignores a HTTP-date, and assumes any non int 0 is a delay
        if (header.matches("\\d+")) {
            return Integer.valueOf(header);
        }

        return Integer.MAX_VALUE;
    }

    /**
     * Returns true if an HTTP request for {@code followUp} can reuse the connection used by this
     * engine.
     */
    private boolean sameConnection(Response response, HttpUrl followUp) {
        HttpUrl url = response.request().url();
        return url.host().equals(followUp.host())
                && url.port() == followUp.port()
                && url.scheme().equals(followUp.scheme());
    }
}
