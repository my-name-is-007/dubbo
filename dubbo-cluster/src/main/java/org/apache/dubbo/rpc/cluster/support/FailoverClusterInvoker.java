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
package org.apache.dubbo.rpc.cluster.support;

import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.LoadBalance;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_RETRIES;
import static org.apache.dubbo.common.constants.CommonConstants.RETRIES_KEY;

/**
 * When invoke fails, log the initial error and retry other invokers (retry n times, which means at most n different invokers will be invoked)
 * Note that retry causes latency.
 * <p>
 * <a href="http://en.wikipedia.org/wiki/Failover">Failover</a>
 *
 */
public class FailoverClusterInvoker<T> extends AbstractClusterInvoker<T> {

    private static final Logger logger = LoggerFactory.getLogger(FailoverClusterInvoker.class);

    public FailoverClusterInvoker(Directory<T> directory) {
        super(directory);
    }

    /**
     * 实现了失败重试的容错策略，当调用失败的时候，记录下异常，然后循环调用下一个选择出来的invoker，直到重试次数用完，抛出最后一次的异常
     */
    /**
     *
     * @param invocation
     * @param invokers 此参数为 AbstractClusterInvoker 通过 list(invocation)选出的可用的Invoker,
     * @param loadbalance
     * @return
     * @throws RpcException
     */
    @Override
    public Result doInvoke(Invocation invocation, final List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        //先复制一个出来
        List<Invoker<T>> copyInvokers = invokers;
        checkInvokers(copyInvokers, invocation);

        //要调用的方法名
        String methodName = RpcUtils.getMethodName(invocation);

        //获取重试次数
        int len = getUrl().getMethodParameter(methodName, RETRIES_KEY, DEFAULT_RETRIES) + 1;
        if (len <= 0) { len = 1; }

        //方法调用可能会发生异常, 记录最后一次的异常信息
        RpcException le = null;

        //记录已经调用的Invoker, 你在 AbstractClusterInvoker中 看到的 selected 就是 这个集合
        List<Invoker<T>> invoked = new ArrayList<>(copyInvokers.size());
        Set<String> providers = new HashSet<>(len);

        //循环调用，失败重试
        for (int i = 0; i < len; i++) {
            //在重试前 重新获取 Invoker: 如果某个服务挂了, 通过list 可得到最新可用的 Invoker 列表
            if (i > 0) {
                checkWhetherDestroyed();
                copyInvokers = list(invocation);
                checkInvokers(copyInvokers, invocation);
            }

            //负载均衡 选择 invoker
            Invoker<T> invoker = select(loadbalance, invocation, copyInvokers, invoked);

            //加入 已经调用 的 Invoker 集合
            invoked.add(invoker);
            //设置 invoked 到 RPC 上下文
            RpcContext.getContext().setInvokers((List) invoked);
            try {
                //开始调用
                Result result = invoker.invoke(invocation);
                if (le != null && logger.isWarnEnabled()) {
                    logger.warn("Although retry the method " + methodName
                            + " in the service " + getInterface().getName()
                            + " was successful by the provider " + invoker.getUrl().getAddress()
                            + ", but there have been failed providers " + providers
                            + " (" + providers.size() + "/" + copyInvokers.size()
                            + ") from the registry " + directory.getUrl().getAddress()
                            + " on the consumer " + NetUtils.getLocalHost()
                            + " using the dubbo version " + Version.getVersion() + ". Last error is: "
                            + le.getMessage(), le);
                }
                return result;
            } catch (RpcException e) {
                if (e.isBiz()) { // biz exception.
                    throw e;
                }
                le = e;
            } catch (Throwable e) {
                le = new RpcException(e.getMessage(), e);
            } finally {
                providers.add(invoker.getUrl().getAddress());
            }
        }

        //循环重试失败, 抛出最后一次的异常
        throw new RpcException(le.getCode(), "Failed to invoke the method "
                + methodName + " in the service " + getInterface().getName()
                + ". Tried " + len + " times of the providers " + providers
                + " (" + providers.size() + "/" + copyInvokers.size()
                + ") from the registry " + directory.getUrl().getAddress()
                + " on the consumer " + NetUtils.getLocalHost() + " using the dubbo version "
                + Version.getVersion() + ". Last error is: "
                + le.getMessage(), le.getCause() != null ? le.getCause() : le);
    }

}
