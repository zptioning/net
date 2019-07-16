/*
 * Copyright (C) 2014 Square, Inc.
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
package okhttp3;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.internal.NamedRunnable;
import okhttp3.internal.cache.CacheInterceptor;
import okhttp3.internal.connection.ConnectInterceptor;
import okhttp3.internal.connection.StreamAllocation;
import okhttp3.internal.http.BridgeInterceptor;
import okhttp3.internal.http.CallServerInterceptor;
import okhttp3.internal.http.RealInterceptorChain;
import okhttp3.internal.http.RetryAndFollowUpInterceptor;
import okhttp3.internal.platform.Platform;

import static okhttp3.internal.platform.Platform.INFO;

final class RealCall implements Call {
    final OkHttpClient client;
    final RetryAndFollowUpInterceptor retryAndFollowUpInterceptor;

    /**
     * The application's original request unadulterated by redirects or auth headers.
     */
    final Request originalRequest;
    final boolean forWebSocket;

    // Guarded by this.
    private boolean executed;

    RealCall(OkHttpClient client, Request originalRequest, boolean forWebSocket) {
        this.client = client;
        this.originalRequest = originalRequest;
        this.forWebSocket = forWebSocket;
        this.retryAndFollowUpInterceptor = new RetryAndFollowUpInterceptor(client, forWebSocket);
    }

    @Override
    public Request request() {
        return originalRequest;
    }

    /**
     * 同步调度分析
     * 第一步是：是调用了RealCall的execute()方法里面调用executed(this);
     * @return
     * @throws IOException
     */
    @Override
    public Response execute() throws IOException {
        synchronized (this) {
            if (executed) throw new IllegalStateException("Already Executed");
            executed = true;
        }
        captureCallStackTrace();
        try {
            /*第二步：在Dispatcher里面的executed执行入队操作*/
            client.dispatcher().executed(this);
            /*第三步：执行getResponseWithInterceptorChain();进入拦截器链流程，然后进行请求，获取Response，并返回Response result 。*/
            Response result = getResponseWithInterceptorChain();
            if (result == null) throw new IOException("Canceled");
            return result;
        } finally {
            /*第四步：执行client.dispatcher().finished(this)操作*/
            client.dispatcher().finished(this);
        }
    }

    private void captureCallStackTrace() {
        Object callStackTrace = Platform.get().getStackTraceForCloseable("response.body().close()");
        retryAndFollowUpInterceptor.setCallStackTrace(callStackTrace);
    }

    /**
     * 异步流程分析
     * 第一步 是调用了RealCall的enqueue()方法
     * @param responseCallback
     */
    @Override
    public void enqueue(Callback responseCallback) {
        synchronized (this) {
            if (executed) throw new IllegalStateException("Already Executed");
            executed = true;
        }
        captureCallStackTrace();
        /*第二步：在Dispatcher里面的enqueue执行入队操作*/
        client.dispatcher().enqueue(new AsyncCall(responseCallback));
    }

    @Override
    public void cancel() {
        retryAndFollowUpInterceptor.cancel();
    }

    @Override
    public synchronized boolean isExecuted() {
        return executed;
    }

    @Override
    public boolean isCanceled() {
        return retryAndFollowUpInterceptor.isCanceled();
    }

    @SuppressWarnings("CloneDoesntCallSuperClone")
    // We are a final type & this saves clearing state.
    @Override
    public RealCall clone() {
        return new RealCall(client, originalRequest, forWebSocket);
    }

    StreamAllocation streamAllocation() {
        return retryAndFollowUpInterceptor.streamAllocation();
    }

    final class AsyncCall extends NamedRunnable {
        private final Callback responseCallback;

        AsyncCall(Callback responseCallback) {
            super("OkHttp %s", redactedUrl());
            this.responseCallback = responseCallback;
        }

        String host() {
            return originalRequest.url().host();
        }

        Request request() {
            return originalRequest;
        }

        RealCall get() {
            return RealCall.this;
        }

        // 情况1 第五步：执行AsyncCall的execute()方法
        @Override
        protected void execute() {
            boolean signalledCallback = false;
            try {
                //执行耗时任务
                Response response = getResponseWithInterceptorChain();
                if (retryAndFollowUpInterceptor.isCanceled()) {
                    //retryAndFollowUpInterceptor取消了 执行失败
                    signalledCallback = true;
                    //回调，注意这里回调是在线程池中，不是主线程
                    responseCallback.onFailure(RealCall.this, new IOException("Canceled"));
                } else {
                    //一切正常走入正常流程
                    signalledCallback = true;
                    responseCallback.onResponse(RealCall.this, response);
                }
            } catch (IOException e) {
                if (signalledCallback) {
                    // Do not signal the callback twice!
                    Platform.get().log(INFO, "Callback failure for " + toLoggableString(), e);
                } else {
                    responseCallback.onFailure(RealCall.this, e);
                }
            } finally {
                //最后执行出队
                client.dispatcher().finished(this);
            }
        }
    }

    /**
     * Returns a string that describes this call. Doesn't include a full URL as that might contain
     * sensitive information.
     */
    String toLoggableString() {
        return (isCanceled() ? "canceled " : "")
                + (forWebSocket ? "web socket" : "call")
                + " to " + redactedUrl();
    }

    String redactedUrl() {
        return originalRequest.url().redact();
    }

    Response getResponseWithInterceptorChain() throws IOException {
        // Build a full stack of interceptors.
        List<Interceptor> interceptors = new ArrayList<>();
        //添加 在配置 OkHttpClient 时设置的 interceptors
        interceptors.addAll(client.interceptors());
        //添加 负责失败重试以及重定向的 RetryAndFollowUpInterceptor；
        interceptors.add(retryAndFollowUpInterceptor);
        //添加 负责把用户构造的请求转换为发送到服务器的请求、把服务器返回的 响应转换为用户友好的响应的 BridgeInterceptor；
        interceptors.add(new BridgeInterceptor(client.cookieJar()));
        //添加 负责读取缓存直接返回、更新缓存的 CacheInterceptor；
        interceptors.add(new CacheInterceptor(client.internalCache()));
        //添加 负责和服务器建立连接的 ConnectInterceptor；
        interceptors.add(new ConnectInterceptor(client));
        //如果不是webSocket
        if (!forWebSocket) {
            //添加 OkHttpClient 时设置的 networkInterceptors；
            interceptors.addAll(client.networkInterceptors());
        }
        //最后 添加 负责向服务器发送请求数据、从服务器读取响应数据的 CallServerInterceptor。
        interceptors.add(new CallServerInterceptor(forWebSocket));

        Interceptor.Chain chain = new RealInterceptorChain(
                interceptors, null, null, null, 0, originalRequest);
        return chain.proceed(originalRequest);
    }
}
