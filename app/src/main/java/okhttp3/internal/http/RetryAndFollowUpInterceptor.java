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
import okhttp3.CertificatePinner;
import okhttp3.Connection;
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
import static okhttp3.internal.Util.closeQuietly;
import static okhttp3.internal.http.StatusLine.HTTP_PERM_REDIRECT;
import static okhttp3.internal.http.StatusLine.HTTP_TEMP_REDIRECT;

/**
 * This interceptor recovers from failures and follows redirects as necessary. It may throw an
 * {@link IOException} if the call was canceled.
 * <p>
 * 负责失败重连以及重定向
 */
public final class RetryAndFollowUpInterceptor implements Interceptor {
    /**
     * How many redirects and auth challenges should we attempt? Chrome follows 21 redirects; Firefox,
     * curl, and wget follow 20; Safari follows 16; and HTTP/1.0 recommends 5.
     */
    private static final int MAX_FOLLOW_UPS = 20;

    private final OkHttpClient client;
    private final boolean forWebSocket;
    private StreamAllocation streamAllocation;
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
     *
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

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        // 三个参数分别对应：(1)全局的连接池，(2)连接线路Address, (3)堆栈对象
        // 1 根据url创建一个Address对象，初始化一个Socket连接对象，基于Okio
        // 2 用前面创建的address作为参数去实例化StreamAllocation
        // PS：此处还没有真正的去建立连接，只是初始化一个连接对象
        streamAllocation = new StreamAllocation(
                client.connectionPool(), createAddress(request.url()), callStackTrace);

        int followUpCount = 0;
        Response priorResponse = null;
        // 3 开启一个while(true)循环
        while (true) {
            // 4 如果取消，释放资源并抛出异常，结束流程
            if (canceled) {
                streamAllocation.release();
                throw new IOException("Canceled");
            }

            Response response = null;
            boolean releaseConnection = true;
            try {
                //  5 执行下一个拦截器，一般是BridgeInterceptor
                // 这里有个很重的信息，即会将初始化好的连接对象传递给下一个拦截器，也是贯穿整个请求的连击对象，
                // 上面我们说过，在拦截器执行过程中，RealInterceptorChain的几个属性字段会一步一步赋值
                response = ((RealInterceptorChain) chain).proceed(request, streamAllocation, null, null);
                releaseConnection = false;
            } catch (RouteException e) {
                // The attempt to connect via a route failed. The request will not have been sent.
                // 6 如果发生异常，走到catch里面，判断是否继续请求，不继续请求则退出
                if (!recover(e.getLastConnectException(), false, request)) {
                    throw e.getLastConnectException();
                }
                releaseConnection = false;
                continue;
            } catch (IOException e) {
                // An attempt to communicate with a server failed. The request may have been sent.
                boolean requestSendStarted = !(e instanceof ConnectionShutdownException);
                if (!recover(e, requestSendStarted, request)) throw e;
                releaseConnection = false;
                continue;
            } finally {
                // We're throwing an unchecked exception. Release any resources.
                if (releaseConnection) {
                    streamAllocation.streamFailed(null);
                    streamAllocation.release();
                }
            }

            // Attach the prior response if it exists. Such responses never have a body.
            // 7 如果priorResponse不为空，则说明前面已经获取到了响应，这里会结合当前获取的Response和先前的Response
            if (priorResponse != null) {
                response = response.newBuilder()
                        .priorResponse(priorResponse.newBuilder()
                                .body(null)
                                .build())
                        .build();
            }
            // 检查是否符合要求
            // 8 调用followUpRequest查看响应是否需要重定向，如果不需要重定向则返回当前请求
            Request followUp = followUpRequest(response);

            if (followUp == null) {
                if (!forWebSocket) {
                    streamAllocation.release();
                }
                return response;
            }
            //不符合，关闭响应流
            closeQuietly(response.body());
            // 是否超过最大限制
            // 9 重定向次数+1，同时判断是否达到最大限制数量。是：退出
            if (++followUpCount > MAX_FOLLOW_UPS) {
                streamAllocation.release();
                throw new ProtocolException("Too many follow-up requests: " + followUpCount);
            }

            if (followUp.body() instanceof UnrepeatableRequestBody) {
                streamAllocation.release();
                throw new HttpRetryException("Cannot retry streamed HTTP body", response.code());
            }
            // 是否有相同的连接  10 检查是否有相同的链接，是：释放，重建创建
            if (!sameConnection(response, followUp.url())) {
                streamAllocation.release();
                streamAllocation = new StreamAllocation(
                        client.connectionPool(), createAddress(followUp.url()), callStackTrace);
            } else if (streamAllocation.codec() != null) {
                throw new IllegalStateException("Closing the body of " + response
                        + " didn't close its backing stream. Bad interceptor?");
            }

            // 11 重新设置request，并把当前的Response保存到priorResponse，继续while循环
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

    /*通过构createAddress方法
    ，我们发现除了uriHost和uriPort外的所有构造函数的参数均来自OkHttpClient，
    而Address的url字段正式根据这两个字段构造的，由此可见，
    Address的url字段仅仅包含HTTP请求的url的schema+host+port三部分的信息，
    而不包含path和query等信息
    */
        return new Address(url.host(), url.port(), client.dns(), client.socketFactory(),
                sslSocketFactory, hostnameVerifier, certificatePinner, client.proxyAuthenticator(),
                client.proxy(), client.protocols(), client.connectionSpecs(), client.proxySelector());
    }

    /**
     * Report and attempt to recover from a failure to communicate with a server. Returns true if
     * {@code e} is recoverable, or false if the failure is permanent. Requests with a body can only
     * be recovered if the body is buffered or if the failure occurred before the request has been
     * sent.
     * 1 应用层配置不在连接(默认为true)，则不可恢复
     * 2 请求Request是不可重复使用的Request，则不可恢复
     * 3 根据Exception的类型判断是否可以恢复的 (isRecoverable()方法)
     *     3.1、如果是协议错误（ProtocolException）则不可恢复
     *     3.2、如果是中断异常（InterruptedIOException）则不可恢复
     *     3.3、如果是SSL握手错误（SSLHandshakeException && CertificateException）则不可恢复
     *     3.4、certificate pinning错误（SSLPeerUnverifiedException）则不可恢复
     * 4 没用更多线路可供选择 则不可恢复
     * 如果上述条件都不满足，则这个request可以恢复
     *
     */
    private boolean recover(IOException e, boolean requestSendStarted, Request userRequest) {
        streamAllocation.streamFailed(e);
        // 1. 应用层配置不在连接，默认为true
        // The application layer has forbidden retries.
        if (!client.retryOnConnectionFailure()) return false;
        // 2. 请求Request出错不能继续使用
        // We can't send the request body again.
        if (requestSendStarted && userRequest.body() instanceof UnrepeatableRequestBody)
            return false;
        //  是否可以恢复的
        // This exception is fatal.
        if (!isRecoverable(e, requestSendStarted)) return false;
        // 4. 没用更多线路可供选择
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
     * 这里主要是根据响应码(code)和响应头(header)，查看是否需要重定向，并重新设置请求，当然，如果是正常响应则直接返回Response停止循环
     */
    private Request followUpRequest(Response userResponse) throws IOException {
        if (userResponse == null) throw new IllegalStateException();
        Connection connection = streamAllocation.connection();
        Route route = connection != null
                ? connection.route()
                : null;
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
                if (userResponse.request().body() instanceof UnrepeatableRequestBody) {
                    return null;
                }

                return userResponse.request();

            default:
                return null;
        }
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
