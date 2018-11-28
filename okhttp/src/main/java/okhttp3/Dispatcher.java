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

import javax.annotation.Nullable;

import okhttp3.RealCall.AsyncCall;
import okhttp3.internal.Util;

/**
 * Policy on when async requests are executed.
 * <p>
 * <p>Each dispatcher uses an {@link ExecutorService} to run calls internally. If you supply your
 * own executor, it should be able to run {@linkplain #getMaxRequests the configured maximum} number
 * of calls concurrently.
 */
public final class Dispatcher {
    private int maxRequests = 64;
    private int maxRequestsPerHost = 5;
    private @Nullable
    Runnable idleCallback;

    /**
     * Executes calls. Created lazily.
     * 线程池，用于执行请求
     */
    private @Nullable
    ExecutorService executorService;

    /**
     * Ready async calls in the order they'll be run.
     * 准备运行的异步请求
     */
    private final Deque<AsyncCall> readyAsyncCalls = new ArrayDeque<>();

    /**
     * Running asynchronous calls. Includes canceled calls that haven't finished yet.
     * 表示 正在运行的异步请求的队列，其中包含了已经取消了的但未完成的任务。
     */
    private final Deque<AsyncCall> runningAsyncCalls = new ArrayDeque<>();

    /**
     * Running synchronous calls. Includes canceled calls that haven't finished yet.
     * 正在运行的同步请求
     */
    private final Deque<RealCall> runningSyncCalls = new ArrayDeque<>();

    public Dispatcher(ExecutorService executorService) {
        this.executorService = executorService;
    }

    public Dispatcher() {
    }

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
     * <p>
     * <p>If more than {@code maxRequests} requests are in flight when this is invoked, those requests
     * will remain in flight.
     */
    public void setMaxRequests(int maxRequests) {
        if (maxRequests < 1) {
            throw new IllegalArgumentException("max < 1: " + maxRequests);
        }
        synchronized (this) {
            this.maxRequests = maxRequests;
        }
        promoteAndExecute();
    }

    public synchronized int getMaxRequests() {
        return maxRequests;
    }

    /**
     * Set the maximum number of requests for each host to execute concurrently. This limits requests
     * by the URL's host name. Note that concurrent requests to a single IP address may still exceed
     * this limit: multiple hostnames may share an IP address or be routed through the same HTTP
     * proxy.
     * <p>
     * <p>If more than {@code maxRequestsPerHost} requests are in flight when this is invoked, those
     * requests will remain in flight.
     * <p>
     * <p>WebSocket connections to hosts <b>do not</b> count against this limit.
     */
    public void setMaxRequestsPerHost(int maxRequestsPerHost) {
        if (maxRequestsPerHost < 1) {
            throw new IllegalArgumentException("max < 1: " + maxRequestsPerHost);
        }
        synchronized (this) {
            this.maxRequestsPerHost = maxRequestsPerHost;
        }
        promoteAndExecute();
    }

    public synchronized int getMaxRequestsPerHost() {
        return maxRequestsPerHost;
    }

    /**
     * Set a callback to be invoked each time the dispatcher becomes idle (when the number of running
     * calls returns to zero).
     * <p>
     * <p>Note: The time at which a {@linkplain Call call} is considered idle is different depending
     * on whether it was run {@linkplain Call#enqueue(Callback) asynchronously} or
     * {@linkplain Call#execute() synchronously}. Asynchronous calls become idle after the
     * {@link Callback#onResponse onResponse} or {@link Callback#onFailure onFailure} callback has
     * returned. Synchronous calls become idle once {@link Call#execute() execute()} returns. This
     * means that if you are doing synchronous calls the network layer will not truly be idle until
     * every returned {@link Response} has been closed.
     */
    public synchronized void setIdleCallback(@Nullable Runnable idleCallback) {
        this.idleCallback = idleCallback;
    }

    void enqueue(AsyncCall call) {
        synchronized (this) {
            // 将 call加入到 readyAsyncCalls队列当中
            readyAsyncCalls.add(call);
        }
        promoteAndExecute();
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
     * Promotes eligible calls from {@link #readyAsyncCalls} to {@link #runningAsyncCalls} and runs
     * them on the executor service. Must not be called with synchronization because executing calls
     * can call into user code.
     *
     * @return true if the dispatcher is currently running calls.
     */
    private boolean promoteAndExecute() {
        assert (!Thread.holdsLock(this));

        List<AsyncCall> executableCalls = new ArrayList<>();
        boolean isRunning;
        synchronized (this) {
            // 1、遍历 readyAsyncCalls
            for (Iterator<AsyncCall> i = readyAsyncCalls.iterator(); i.hasNext(); ) {
                AsyncCall asyncCall = i.next();

                // 1.1 如果 runningAsyncCalls的size大于最大请求数，则直接跳出循环，不再遍历readyAsyncCalls
                if (runningAsyncCalls.size() >= maxRequests) break; // Max capacity.

                // 1.2 如果 该asyncCall所运行的主机上运行的请求数 大于 最大主机请求数，那么跳过执行该请求
                if (runningCallsForHost(asyncCall) >= maxRequestsPerHost)
                    continue; // Host max capacity.

                // 1.3 将该请求从 readyAsyncCalls队列中删除
                i.remove();
                // 1.4 将该请求加入到 executableCalls
                executableCalls.add(asyncCall);

                // 1.5 将该请求加入到 runningAsyncCalls中
                runningAsyncCalls.add(asyncCall);
            }

            // 2、runningAsyncCalls.size() + runningSyncCalls.size()
            isRunning = runningCallsCount() > 0;
        }

        // 3、遍历 executableCalls，将其中的请求对象AsyncCall放入线程池中执行
        for (int i = 0, size = executableCalls.size(); i < size; i++) {
            AsyncCall asyncCall = executableCalls.get(i);
            asyncCall.executeOn(executorService());
        }

        return isRunning;
    }

    /**
     * Returns the number of running calls that share a host with {@code call}.
     * 返回该主机上运行的请求书
     */
    private int runningCallsForHost(AsyncCall call) {
        int result = 0;
        for (AsyncCall c : runningAsyncCalls) {
            if (c.get().forWebSocket) continue;
            if (c.host().equals(call.host())) result++;
        }
        return result;
    }

    /**
     * Used by {@code Call#execute} to signal it is in-flight.
     * 调用该方法，将RealCall对象加入到 runningSyncCalls中
     */
    synchronized void executed(RealCall call) {
        runningSyncCalls.add(call);
    }

    /**
     * Used by {@code AsyncCall#run} to signal completion.
     *
     * 结束异步请求
     */
    void finished(AsyncCall call) {
        finished(runningAsyncCalls, call);
    }

    /**
     * Used by {@code Call#execute} to signal completion.
     *
     * 结束同步请求
     */
    void finished(RealCall call) {
        finished(runningSyncCalls, call);
    }

    private <T> void finished(Deque<T> calls, T call) {
        Runnable idleCallback;
        synchronized (this) {
            // 1、将请求从相应的队列中移出。
            if (!calls.remove(call)) throw new AssertionError("Call wasn't in-flight!");
            idleCallback = this.idleCallback;
        }

        // 2、继续尝试执行readyAsyncCalls中缓存的异步请求。
        // 如果runningAsyncCalls和runningSyncCalls队列中都没有请求在执行，isRunning = false
        boolean isRunning = promoteAndExecute();

        if (!isRunning && idleCallback != null) {
            // 3、如果没有请求执行isRunning = false，并且设置了idleCallback，则需要回调该callback
            idleCallback.run();
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
