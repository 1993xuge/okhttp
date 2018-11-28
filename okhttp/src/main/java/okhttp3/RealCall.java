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
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import javax.annotation.Nullable;

import okhttp3.internal.NamedRunnable;
import okhttp3.internal.cache.CacheInterceptor;
import okhttp3.internal.connection.ConnectInterceptor;
import okhttp3.internal.connection.StreamAllocation;
import okhttp3.internal.http.BridgeInterceptor;
import okhttp3.internal.http.CallServerInterceptor;
import okhttp3.internal.http.RealInterceptorChain;
import okhttp3.internal.http.RetryAndFollowUpInterceptor;
import okhttp3.internal.platform.Platform;
import okio.AsyncTimeout;
import okio.Timeout;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static okhttp3.internal.platform.Platform.INFO;

final class RealCall implements Call {
    final OkHttpClient client;
    final RetryAndFollowUpInterceptor retryAndFollowUpInterceptor;
    final AsyncTimeout timeout;

    /**
     * There is a cycle between the {@link Call} and {@link EventListener} that makes this awkward.
     * This will be set after we create the call instance then create the event listener instance.
     */
    private @Nullable
    EventListener eventListener;

    /**
     * The application's original request unadulterated by redirects or auth headers.
     */
    final Request originalRequest;
    final boolean forWebSocket;

    // Guarded by this.
    private boolean executed;

    private RealCall(OkHttpClient client, Request originalRequest, boolean forWebSocket) {
        this.client = client;
        this.originalRequest = originalRequest;
        this.forWebSocket = forWebSocket;
        this.retryAndFollowUpInterceptor = new RetryAndFollowUpInterceptor(client, forWebSocket);
        this.timeout = new AsyncTimeout() {
            @Override
            protected void timedOut() {
                cancel();
            }
        };
        this.timeout.timeout(client.callTimeoutMillis(), MILLISECONDS);
    }

    /**
     * 将 OkHttpClient 与 Request对象关联的实际方法
     *
     * @param client
     * @param originalRequest
     * @param forWebSocket
     * @return
     */
    static RealCall newRealCall(OkHttpClient client, Request originalRequest, boolean forWebSocket) {
        // Safely publish the Call instance to the EventListener.
        // 创建newCall对象
        RealCall call = new RealCall(client, originalRequest, forWebSocket);
        // 创建 EventListener对象
        call.eventListener = client.eventListenerFactory().create(call);
        return call;
    }

    @Override
    public Request request() {
        return originalRequest;
    }

    /**
     * 同步请求方法，此方法会阻塞当前线程知道请求结果放回
     *
     * @return
     * @throws IOException
     */
    @Override
    public Response execute() throws IOException {
        // 1、首先利用 synchronized 加入了对象锁，防止多线程同时调用
        synchronized (this) {
            // 判断executed是否为true，
            // true表示当前的call已经被执行了，那么抛出异常。
            // false表示没有被执行，那么将executed设置为true，并且继续进行执行。
            if (executed) throw new IllegalStateException("Already Executed");
            executed = true;
        }
        // 2
        captureCallStackTrace();

        // 3
        timeout.enter();

        // 4、回调 callStart
        eventListener.callStart(this);
        try {
            // 5、将 RealCall对象 加入到 dispatcher的同步队列中
            client.dispatcher().executed(this);

            // 6、通过拦截器链获取响应Response
            Response result = getResponseWithInterceptorChain();
            if (result == null) throw new IOException("Canceled");
            return result;
        } catch (IOException e) {
            e = timeoutExit(e);
            // 发生异常，回调 callFailed
            eventListener.callFailed(this, e);
            throw e;
        } finally {
            // 不管请求成功与否，都进行finished()操作
            client.dispatcher().finished(this);
        }
    }

    @Nullable
    IOException timeoutExit(@Nullable IOException cause) {
        if (!timeout.exit()) return cause;

        InterruptedIOException e = new InterruptedIOException("timeout");
        if (cause != null) {
            e.initCause(cause);
        }
        return e;
    }

    private void captureCallStackTrace() {
        Object callStackTrace = Platform.get().getStackTraceForCloseable("response.body().close()");
        // retryAndFollowUpInterceptor加入了一个用于追踪堆栈信息的callStackTrace
        retryAndFollowUpInterceptor.setCallStackTrace(callStackTrace);
    }

    /**
     * 异步请求方法，此方法会将请求添加到队列中，然后等待请求返回
     *
     * @param responseCallback
     */
    @Override
    public void enqueue(Callback responseCallback) {
        // 1、首先利用 synchronized 加入了对象锁，防止多线程同时调用
        synchronized (this) {
            // 判断executed是否为true，
            // true表示当前的call已经被执行了，那么抛出异常。
            // false表示没有被执行，那么将executed设置为true，并且继续进行执行。
            if (executed) throw new IllegalStateException("Already Executed");
            executed = true;
        }
        // 2
        captureCallStackTrace();

        // 3、回调 callStart
        eventListener.callStart(this);

        // 4、使用分发器
        client.dispatcher().enqueue(new AsyncCall(responseCallback));
    }

    /**
     * 取消请求
     */
    @Override
    public void cancel() {
        retryAndFollowUpInterceptor.cancel();
    }

    @Override
    public Timeout timeout() {
        return timeout;
    }

    /**
     * 请求是否在执行，
     * 当execute()或者enqueue(Callback responseCallback)执行后该方法返回true
     * @return
     */
    @Override
    public synchronized boolean isExecuted() {
        return executed;
    }

    /**
     * 请求是否被取消
     * @return
     */
    @Override
    public boolean isCanceled() {
        return retryAndFollowUpInterceptor.isCanceled();
    }

    /**
     * 创建一个新的一模一样的请求
     * @return
     */
    @SuppressWarnings("CloneDoesntCallSuperClone")
    // We are a final type & this saves clearing state.
    @Override
    public RealCall clone() {
        return RealCall.newRealCall(client, originalRequest, forWebSocket);
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

        /**
         * Attempt to enqueue this async call on {@code executorService}. This will attempt to clean up
         * if the executor has been shut down by reporting the call as failed.
         */
        void executeOn(ExecutorService executorService) {
            assert (!Thread.holdsLock(client.dispatcher()));
            boolean success = false;
            try {
                // 1、将该AsyncCall对象加入到线程池中。
                // 在线程池中，会调用Runnable的run方法，AsyncCall继承自NamedRunnable。
                // NamedRunnable中的run方法，会执行抽象方法 execute()
                executorService.execute(this);
                success = true;
            } catch (RejectedExecutionException e) {
                //2、执行过程中发生了异常
                InterruptedIOException ioException = new InterruptedIOException("executor rejected");
                ioException.initCause(e);

                // 回调 callFailed。eventListener是外部类RealCall的属性
                eventListener.callFailed(RealCall.this, ioException);

                // responseCallback回调onFailure。responseCallback对象是从构造方法中传递过来的
                responseCallback.onFailure(RealCall.this, ioException);
            } finally {
                if (!success) {
                    // 3、如果线程池处理该请求失败了。调用dispatcher的finish方法。
                    // 成功，在execute()方法中已经执行了dispatcher的finish方法
                    client.dispatcher().finished(this); // This call is no longer running!
                }
            }
        }

        @Override
        protected void execute() {
            boolean signalledCallback = false;
            timeout.enter();
            try {
                //
                Response response = getResponseWithInterceptorChain();
                if (retryAndFollowUpInterceptor.isCanceled()) {
                    signalledCallback = true;
                    responseCallback.onFailure(RealCall.this, new IOException("Canceled"));
                } else {
                    signalledCallback = true;
                    responseCallback.onResponse(RealCall.this, response);
                }
            } catch (IOException e) {
                e = timeoutExit(e);
                if (signalledCallback) {
                    // Do not signal the callback twice!
                    Platform.get().log(INFO, "Callback failure for " + toLoggableString(), e);
                } else {
                    eventListener.callFailed(RealCall.this, e);
                    responseCallback.onFailure(RealCall.this, e);
                }
            } finally {
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

    /**
     * 真正执行 网络请求的方法
     * 在该方法中创建拦截器链，通过依次执行每一个不同功能的拦截器来获取服务器的响应返回。
     * @return
     * @throws IOException
     */
    Response getResponseWithInterceptorChain() throws IOException {
        // Build a full stack of interceptors.
        List<Interceptor> interceptors = new ArrayList<>();
        // 添加用户自定义的拦截器，也就是应用程序拦截器。
        interceptors.addAll(client.interceptors());

        //失败和重定向过滤器
        interceptors.add(retryAndFollowUpInterceptor);

        // 封装request和response过滤器
        interceptors.add(new BridgeInterceptor(client.cookieJar()));

        // 缓存相关的过滤器，负责读取缓存直接返回、更新缓存
        interceptors.add(new CacheInterceptor(client.internalCache()));

        // 负责和服务器建立连接
        interceptors.add(new ConnectInterceptor(client));

        if (!forWebSocket) {
            // 配置 OkHttpClient 时设置的 networkInterceptors
            interceptors.addAll(client.networkInterceptors());
        }

        // 负责向服务器发送请求数据、从服务器读取响应数据(实际网络请求)
        interceptors.add(new CallServerInterceptor(forWebSocket));

        // 生成 过滤器链
        Interceptor.Chain chain = new RealInterceptorChain(interceptors, null, null, null, 0,
                originalRequest, this, eventListener, client.connectTimeoutMillis(),
                client.readTimeoutMillis(), client.writeTimeoutMillis());

        // 执行过滤器
        return chain.proceed(originalRequest);
    }
}
