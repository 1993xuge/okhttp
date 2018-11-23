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
  private @Nullable EventListener eventListener;

  /** The application's original request unadulterated by redirects or auth headers. */
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
      @Override protected void timedOut() {
        cancel();
      }
    };
    this.timeout.timeout(client.callTimeoutMillis(), MILLISECONDS);
  }

  /**
   * 将 OkHttpClient 与 Request对象关联的实际方法
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

  @Override public Request request() {
    return originalRequest;
  }

  /**
   * 同步请求
   * @return
   * @throws IOException
   */
  @Override public Response execute() throws IOException {
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

      // 6、
      Response result = getResponseWithInterceptorChain();
      if (result == null) throw new IOException("Canceled");
      return result;
    } catch (IOException e) {
      e = timeoutExit(e);
      // 发生异常，回调 callFailed
      eventListener.callFailed(this, e);
      throw e;
    } finally {
      //
      client.dispatcher().finished(this);
    }
  }

  @Nullable IOException timeoutExit(@Nullable IOException cause) {
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
     * 异步请求
     * @param responseCallback
     */
  @Override public void enqueue(Callback responseCallback) {
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

  @Override public void cancel() {
    retryAndFollowUpInterceptor.cancel();
  }

  @Override public Timeout timeout() {
    return timeout;
  }

  @Override public synchronized boolean isExecuted() {
    return executed;
  }

  @Override public boolean isCanceled() {
    return retryAndFollowUpInterceptor.isCanceled();
  }

  @SuppressWarnings("CloneDoesntCallSuperClone") // We are a final type & this saves clearing state.
  @Override public RealCall clone() {
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
          // 将该AsyncCall对象加入到线程池中。
          // 在线程池中，会调用Runnable的run方法，AsyncCall继承自NamedRunnable。
          // NamedRunnable中的run方法，会执行抽象方法 execute()
        executorService.execute(this);
        success = true;
      } catch (RejectedExecutionException e) {
           //执行过程中发生了异常
        InterruptedIOException ioException = new InterruptedIOException("executor rejected");
        ioException.initCause(e);

        // 回调 callFailed。eventListener是外部类RealCall的属性
        eventListener.callFailed(RealCall.this, ioException);

        // responseCallback回调onFailure。responseCallback对象是从构造方法中传递过来的
        responseCallback.onFailure(RealCall.this, ioException);
      } finally {
        if (!success) {
            // 如果线程池处理该请求失败了。调用dispatcher的finish方法。
            // 成功，在execute()方法中已经执行了dispatcher的finish方法
          client.dispatcher().finished(this); // This call is no longer running!
        }
      }
    }

    @Override protected void execute() {
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
   * @return
   * @throws IOException
   */
  Response getResponseWithInterceptorChain() throws IOException {
    // Build a full stack of interceptors.
    List<Interceptor> interceptors = new ArrayList<>();
    interceptors.addAll(client.interceptors());

    interceptors.add(retryAndFollowUpInterceptor);
    interceptors.add(new BridgeInterceptor(client.cookieJar()));
    interceptors.add(new CacheInterceptor(client.internalCache()));
    interceptors.add(new ConnectInterceptor(client));
    if (!forWebSocket) {
      interceptors.addAll(client.networkInterceptors());
    }
    interceptors.add(new CallServerInterceptor(forWebSocket));

    Interceptor.Chain chain = new RealInterceptorChain(interceptors, null, null, null, 0,
        originalRequest, this, eventListener, client.connectTimeoutMillis(),
        client.readTimeoutMillis(), client.writeTimeoutMillis());

    return chain.proceed(originalRequest);
  }
}
