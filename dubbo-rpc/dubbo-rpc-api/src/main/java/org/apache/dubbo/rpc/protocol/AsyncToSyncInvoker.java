/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.rpc.protocol;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.TimeoutException;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.InvokeMode;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 这个Invoker会对所有的Invoker进行包装,
 *
 * invoke方法中进行异步至同步的转换
 *
 * @param <T>
 */
public class AsyncToSyncInvoker<T> implements Invoker<T> {

    private Invoker<T> invoker;

    public AsyncToSyncInvoker(Invoker<T> invoker) { this.invoker = invoker; }

    @Override
    public Result invoke(Invocation invocation) throws RpcException {
        Result asyncResult = invoker.invoke(invocation);

        try {
            //异步转同步, 即是 调用了其get方法, 使其无限阻塞下去
            if (InvokeMode.SYNC == ((RpcInvocation) invocation).getInvokeMode()) {
                /**
                 * NOTICE!
                 * must call {@link java.util.concurrent.CompletableFuture#get(long, TimeUnit)} because
                 * {@link java.util.concurrent.CompletableFuture#get()} was proved to have serious performance drop.
                 */
                asyncResult.get(Integer.MAX_VALUE, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            throw new RpcException("Interrupted unexpectedly while waiting for remoting result to return!  method: " + invocation.getMethodName() + ", provider: " + getUrl() + ", cause: " + e.getMessage(), e);
        } catch (ExecutionException e) {
            Throwable t = e.getCause();
            if (t instanceof TimeoutException) {
                throw new RpcException(RpcException.TIMEOUT_EXCEPTION, "Invoke remote method timeout. method: " + invocation.getMethodName() + ", provider: " + getUrl() + ", cause: " + e.getMessage(), e);
            } else if (t instanceof RemotingException) {
                throw new RpcException(RpcException.NETWORK_EXCEPTION, "Failed to invoke remote method: " + invocation.getMethodName() + ", provider: " + getUrl() + ", cause: " + e.getMessage(), e);
            }
        } catch (Throwable e) {
            throw new RpcException(e.getMessage(), e);
        }
        return asyncResult;
    }

    @Override
    public Class<T> getInterface() { return invoker.getInterface(); }

    @Override
    public URL getUrl() { return invoker.getUrl(); }

    @Override
    public boolean isAvailable() { return invoker.isAvailable(); }

    @Override
    public void destroy() { invoker.destroy(); }

    public Invoker<T> getInvoker() { return invoker; }
}
