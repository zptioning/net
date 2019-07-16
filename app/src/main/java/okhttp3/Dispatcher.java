/*
 * Copyright (C) 2013 Square, Inc.
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import okhttp3.RealCall.AsyncCall;
import okhttp3.internal.Util;

/**
 * Policy on when async requests are executed.
 *
 * <p>Each dispatcher uses an {@link ExecutorService} to run calls internally. If you supply your
 * own executor, it should be able to run {@linkplain #getMaxRequests the configured maximum} number
 * of calls concurrently.
 */
public final class Dispatcher {
    private int maxRequests = 64;// 最大并发请求数为64
    private int maxRequestsPerHost = 5; //每个主机最大请求数为5
    private Runnable idleCallback;

    /**
     * Executes calls. Created lazily.
     */
    private ExecutorService executorService;//消费者池（也就是线程池）

    /**
     * Ready async calls in the order they'll be run.
     * // 异步的缓存，正在准备被消费的（用数组实现，可自动扩容，无大小限制）
     */
    private final Deque<AsyncCall> readyAsyncCalls = new ArrayDeque<>();

    /**
     * Running asynchronous calls. Includes canceled calls that haven't finished yet.
     * //正在运行的 异步的任务集合，仅仅是用来引用正在运行的任务以判断并发量，注意它并不是消费者缓存
     */
    private final Deque<AsyncCall> runningAsyncCalls = new ArrayDeque<>();

    /**
     * Running synchronous calls. Includes canceled calls that haven't finished yet.
     * //正在运行的，同步的任务集合。仅仅是用来引用正在运行的同步任务以判断并发量
     */
    private final Deque<RealCall> runningSyncCalls = new ArrayDeque<>();

    public Dispatcher(ExecutorService executorService) {
        this.executorService = executorService;
    }

    public Dispatcher() {
    }

    /**
     * 1、0：核心线程数量，保持在线程池中的线程数量(即使已经空闲)，为0代表线程空闲后不会保留，等待一段时间后停止。
     * 2、Integer.MAX_VALUE:表示线程池可以容纳最大线程数量
     * 3、TimeUnit.SECOND:当线程池中的线程数量大于核心线程时，空闲的线程就会等待60s才会被终止，如果小于，则会立刻停止。
     * 4、new SynchronousQueue<Runnable>()：线程等待队列。同步队列，按序排队，先来先服务
     * 5、Util.threadFactory("OkHttp Dispatcher", false):线程工厂，直接创建一个名为OkHttp Dispatcher的非守护线程。
     *
     * @return
     */
    public synchronized ExecutorService executorService() {
        if (executorService == null) {
            executorService = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS,
                    new SynchronousQueue<Runnable>(), Util.threadFactory("OkHttp Dispatcher", false));
        }
        return executorService;
    }

    /**
     * Set the maximum number of requests to execute concurrently. Above this requests queue in
     * memory, waiting for the running calls to complete.
     *
     * <p>If more than {@code maxRequests} requests are in flight when this is invoked, those requests
     * will remain in flight.
     */
    public synchronized void setMaxRequests(int maxRequests) {
        //设置的maxRequests不能小于1
        if (maxRequests < 1) {
            throw new IllegalArgumentException("max < 1: " + maxRequests);
        }
        this.maxRequests = maxRequests;
        promoteCalls();
    }

    public synchronized int getMaxRequests() {
        return maxRequests;
    }

    /**
     * Set the maximum number of requests for each host to execute concurrently. This limits requests
     * by the URL's host name. Note that concurrent requests to a single IP address may still exceed
     * this limit: multiple hostnames may share an IP address or be routed through the same HTTP
     * proxy.
     *
     * <p>If more than {@code maxRequestsPerHost} requests are in flight when this is invoked, those
     * requests will remain in flight.
     */
    public synchronized void setMaxRequestsPerHost(int maxRequestsPerHost) {
        //设置的maxRequestsPerHost不能小于1
        if (maxRequestsPerHost < 1) {
            throw new IllegalArgumentException("max < 1: " + maxRequestsPerHost);
        }
        this.maxRequestsPerHost = maxRequestsPerHost;
        promoteCalls();
    }

    public synchronized int getMaxRequestsPerHost() {
        return maxRequestsPerHost;
    }

    /**
     * Set a callback to be invoked each time the dispatcher becomes idle (when the number of running
     * calls returns to zero).
     *
     * <p>Note: The time at which a {@linkplain Call call} is considered idle is different depending
     * on whether it was run {@linkplain Call#enqueue(Callback) asynchronously} or
     * {@linkplain Call#execute() synchronously}. Asynchronous calls become idle after the
     * {@link Callback#onResponse onResponse} or {@link Callback#onFailure onFailure} callback has
     * returned. Synchronous calls become idle once {@link Call#execute() execute()} returns. This
     * means that if you are doing synchronous calls the network layer will not truly be idle until
     * every returned {@link Response} has been closed.
     */
    public synchronized void setIdleCallback(Runnable idleCallback) {
        this.idleCallback = idleCallback;
    }

    /**
     * 异步
     * 将Call加入队列：
     * 如果当前正在执行的call的数量大于maxRequest(64),
     * 或者该call的Host上的call超过maxRequestsPerHos(5)，
     * 则加入readyAsyncCall排队等待，否则加入runningAsyncCalls并执行
     * <p>
     * 异步流程分析
     * 第二步：在Dispatcher里面的enqueue执行入队操作
     *
     * @param call
     */
    synchronized void enqueue(AsyncCall call) {
        //判断是否满足入队的条件(立即执行)
        if (runningAsyncCalls.size() < maxRequests && runningCallsForHost(call) < maxRequestsPerHost) {
            //正在运行的异步集合添加call
            // 情况1 第三步 可以直接入队
            runningAsyncCalls.add(call);
            //执行这个call
            // 情况1 第四步：线程池executorService执行execute()方法
            executorService().execute(call);
        } else {
            //不满足入队(立即执行)条件,则添加到等待集合中
            // 情况2 第三步 不能直接入队，需要等待
            readyAsyncCalls.add(call);
        }
        /*如果满足条件，那么就直接把AsyncCall直接加到runningCalls的队列中，
        并在线程池中执行（线程池会根据当前负载自动创建，销毁，缓存相应的线程）。反之就放入readyAsyncCalls进行缓存等待。
runningAsyncCalls.size() < maxRequests  表示当前正在运行的AsyncCall是否小于maxRequests = 64
runningCallsForHost(call) < maxRequestsPerHos 表示同一个地址访问的AsyncCall是否小于maxRequestsPerHost = 5;
即 当前正在并发的请求不能超过64且同一个地址的访问不能超过5个*/
    }

    /**
     * Cancel all calls currently enqueued or executing. Includes calls executed both {@linkplain
     * Call#execute() synchronously} and {@linkplain Call#enqueue asynchronously}.
     */
    public synchronized void cancelAll() {
        for (AsyncCall call : readyAsyncCalls) {
            call.get().cancel();
        }

        for (AsyncCall call : runningAsyncCalls) {
            call.get().cancel();
        }

        for (RealCall call : runningSyncCalls) {
            call.cancel();
        }
    }


    /**
     * Returns the number of running calls that share a host with {@code call}.
     */
    private int runningCallsForHost(AsyncCall call) {
        int result = 0;
        for (AsyncCall c : runningAsyncCalls) {
            if (c.host().equals(call.host())) result++;
        }
        return result;
    }

    /**
     * 同步
     * Dispatcher在执行同步的Call：
     * 直接加入到runningSyncCall队列中，实际上并没有执行该Call，而是交给外部执行
     *
     * 同步調度分析
     * 第二步：在Dispatcher里面的executed执行入队操作
     */
    /**
     * Used by {@code Call#execute} to signal it is in-flight.
     */
    synchronized void executed(RealCall call) {
        runningSyncCalls.add(call);
    }

    /*
     * Used by {@code AsyncCall#run} to signal completion.
     */
    void finished(AsyncCall call) {
        finished(runningAsyncCalls, call, true);
    }

    /**
     * Used by {@code Call#execute} to signal completion.
     */
    void finished(RealCall call) {
        finished(runningSyncCalls, call, false);
    }

    /**
     * 从ready到running,在每个call结束的时候都会调用finished
     * <p>
     * <p>
     * 通过上面代码，大家可以知道finished先执行calls.remove(call)删除call，
     * 然后执行promoteCalls()，
     * 在promoteCalls()方法里面：如果当前线程大于maxRequest则不操作，
     * 如果小于maxRequest则遍历readyAsyncCalls，
     * 取出一个call，并把这个call放入runningAsyncCalls，
     * 然后执行execute。
     * 在遍历过程中如果runningAsyncCalls超过maxRequest则不再添加，否则一直添加。所以可以这样说：
     *
     * @param calls
     * @param call
     * @param promoteCalls
     * @param <T>
     */
    private <T> void finished(Deque<T> calls, T call, boolean promoteCalls) {
        int runningCallsCount;
        Runnable idleCallback;
        synchronized (this) {
            if (!calls.remove(call)) throw new AssertionError("Call wasn't in-flight!");
            if (promoteCalls) promoteCalls();
            runningCallsCount = runningCallsCount();
            idleCallback = this.idleCallback;
        }

        if (runningCallsCount == 0 && idleCallback != null) {
            idleCallback.run();
        }
    }

    /**
     * promoteCalls()负责ready的Call到running的Call的转化
     * 具体的执行请求则在RealCall里面实现的，
     * 同步的在RealCall的execute里面实现的，
     * 而异步的则在AsyncCall的execute里面实现的。
     * 里面都是调用RealCall的getResponseWithInterceptorChain的方法来实现责任链的调用。
     * <p>
     * 1 Dispatcher的setMaxRequestsPerHost()方法被调用时
     * 2 Dispatcher的setMaxRequests()被调用时
     * 3 当有一条请求结束了，执行了finish()的出队操作，这时候会触发promoteCalls()进行调整
     * <p>
     * 情况2： 第五步 执行Dispatcher的promoteCalls()方法
     */
    private void promoteCalls() {
        // 情况2 第六步 先判断是否满足 初步入队条件
        /*如果此时 并发的数量还是大于maxRequests=64则return并继续等待
如果此时，没有等待的任务，则直接return并继续等待*/
        if (runningAsyncCalls.size() >= maxRequests)
            return; // Already running max capacity.
        if (readyAsyncCalls.isEmpty())
            return; // No ready calls to promote.

        /* 情况2 第七步 满足初步的入队条件，进行遍历，然后进行第二轮入队判断*/
        for (Iterator<AsyncCall> i = readyAsyncCalls.iterator(); i.hasNext(); ) {
            AsyncCall call = i.next();
            /*进行同一个host是否已经有5请求在了，如果在了，则return返回并继续等待*/
            if (runningCallsForHost(call) < maxRequestsPerHost) {
                i.remove();
                /* 情况2 第八步 此时已经全部满足条件,则从等待队列面移除这个call，然后添加到正在运行的队列中*/
                runningAsyncCalls.add(call);
                /* 情况2 第九步 线程池executorService执行execute()方法 */
                executorService().execute(call);
            }

            if (runningAsyncCalls.size() >= maxRequests)
                return; // Reached max capacity.
        }
    }

    /**
     * Returns a snapshot of the calls currently awaiting execution.
     */
    public synchronized List<Call> queuedCalls() {
        List<Call> result = new ArrayList<>();
        for (AsyncCall asyncCall : readyAsyncCalls) {
            result.add(asyncCall.get());
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Returns a snapshot of the calls currently being executed.
     */
    public synchronized List<Call> runningCalls() {
        List<Call> result = new ArrayList<>();
        result.addAll(runningSyncCalls);
        for (AsyncCall asyncCall : runningAsyncCalls) {
            result.add(asyncCall.get());
        }
        return Collections.unmodifiableList(result);
    }

    public synchronized int queuedCallsCount() {
        return readyAsyncCalls.size();
    }

    public synchronized int runningCallsCount() {
        return runningAsyncCalls.size() + runningSyncCalls.size();
    }
}
