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
import java.util.List;

import okhttp3.Connection;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.connection.StreamAllocation;

/**
 * A concrete interceptor chain that carries the entire interceptor chain: all application
 * interceptors, the OkHttp core, all network interceptors, and finally the network caller.
 */
public final class RealInterceptorChain implements Interceptor.Chain {
    private final List<Interceptor> interceptors;
    private final Request request;
    // 下面属性会在执行各个拦截器的过程中一步一步赋值
    private final StreamAllocation streamAllocation;//在RetryAndFollowUpInterceptor中new的
    private final HttpCodec httpCodec;//在ConnectInterceptor中new的
    private final Connection connection;//在ConnectInterceptor中new的
    private final int index;//通过index + 1
    private int calls;//通过call++

    public RealInterceptorChain(List<Interceptor> interceptors, StreamAllocation streamAllocation,
                                HttpCodec httpCodec, Connection connection, int index, Request request) {
        this.interceptors = interceptors;
        this.connection = connection;
        this.streamAllocation = streamAllocation;
        this.httpCodec = httpCodec;
        this.index = index;
        this.request = request;
    }

    @Override
    public Connection connection() {
        return connection;
    }

    public StreamAllocation streamAllocation() {
        return streamAllocation;
    }

    public HttpCodec httpStream() {
        return httpCodec;
    }

    @Override
    public Request request() {
        return request;
    }

    // 实现了父类proceed方法
    @Override
    public Response proceed(Request request) throws IOException {
        return proceed(request, streamAllocation, httpCodec, connection);
    }

    /*
    * 第一步，先判断是否超过list的size，如果超过则遍历结束，如果没有超过则继续执行
        第二步calls+1
        第三步new了一个RealInterceptorChain，其中然后下标index+1
        第四步 从list取出下一个interceptor对象
        第五步 执行interceptor的intercept方法
        这里的拦截器有点像安卓里面的触控反馈的Interceptor。既一个网络请求，按一定的顺序，经由多个拦截器进行处理，该拦截器可以决定自己处理并且返回我的结果，也可以选择向下继续传递，让后面的拦截器处理返回它的结果。这个设计模式叫做责任链模式。
与Android中的触控反馈interceptor的设计略有不同的是，后者通过返回true 或者 false 来决定是否已经拦截。而OkHttp这里的拦截器通过函数调用的方式，讲参数传递给后面的拦截器的方式进行传递。这样做的好处是拦截器的逻辑比较灵活，可以在后面的拦截器处理完并返回结果后仍然执行自己的逻辑；缺点是逻辑没有前者清晰。
    * */
    public Response proceed(Request request, StreamAllocation streamAllocation, HttpCodec httpCodec,
                            Connection connection) throws IOException {
        // 1、迭代拦截器集合
        if (index >= interceptors.size())
            throw new AssertionError();
        //2、创建一次实例，call+1
        calls++;

        // If we already have a stream, confirm that the incoming request will use it.
        if (this.httpCodec != null && !sameConnection(request.url())) {
            throw new IllegalStateException("network interceptor " + interceptors.get(index - 1)
                    + " must retain the same host and port");
        }

        // If we already have a stream, confirm that this is the only call to chain.proceed().
        if (this.httpCodec != null && calls > 1) {
            throw new IllegalStateException("network interceptor " + interceptors.get(index - 1)
                    + " must call proceed() exactly once");
        }

        // Call the next interceptor in the chain.
        // 3、创建一个RealInterceptorChain实例
        RealInterceptorChain next = new RealInterceptorChain(
                interceptors, streamAllocation, httpCodec, connection, index + 1, request);
        //4、取出下一个 interceptor
        Interceptor interceptor = interceptors.get(index);
        //5、执行intercept方法，拦截器又会调用proceed()方法
        Response response = interceptor.intercept(next);

        // Confirm that the next interceptor made its required call to chain.proceed().
        if (httpCodec != null && index + 1 < interceptors.size() && next.calls != 1) {
            throw new IllegalStateException("network interceptor " + interceptor
                    + " must call proceed() exactly once");
        }

        // Confirm that the intercepted response isn't null.
        if (response == null) {
            throw new NullPointerException("interceptor " + interceptor + " returned null");
        }

        return response;
    }

    private boolean sameConnection(HttpUrl url) {
        return url.host().equals(connection.route().address().url().host())
                && url.port() == connection.route().address().url().port();
    }
}
