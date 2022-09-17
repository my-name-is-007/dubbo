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
package org.apache.dubbo.rpc.cluster;

import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.SPI;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.support.FailoverCluster;

/**
 * Cluster. (SPI, Singleton, ThreadSafe)
 * <p>
 * <a href="http://en.wikipedia.org/wiki/Computer_cluster">Cluster</a>
 * <a href="http://en.wikipedia.org/wiki/Fault-tolerant_system">Fault-Tolerant</a>
 *
 * 每一个Cluster实现类都对应着一个invoker，因为这个模式启用的时间点就是在调用的时候.
 * 
 * 下面是其实现类, 但每个实现类中仅有一个构造器方法, 就是创建对应的Invoker.
 *
 * Failover Cluster：失败自动切换，当调用出现失败的时候，会自动切换集群中其他服务器，来获得invoker重试，通常用于读操作，但重试会带来更长延迟。一般都会设置重试次数。
 * Failsafe Cluster：失败安全，出现异常时，直接忽略。失败安全就是当调用过程中出现异常时，FailsafeClusterInvoker 仅会打印异常，而不会抛出异常。适用于写入审计日志等操作
 * Failfast Cluster：只会进行一次调用，失败后立即抛出异常。适用于幂等操作，比如新增记录。
 * Failback Cluster：失败自动恢复，在调用失败后，返回一个空结果给服务提供者。并通过定时任务对失败的调用记录并且重传，适合执行消息通知等操作。
 * Forking Cluster：会在线程池中运行多个线程，来调用多个服务器，只要一个成功即返回。通常用于实时性要求较高的读操作，但需要浪费更多服务资源。一般会设置最大并行数。
 * Available Cluster：调用第一个可用的服务器，仅仅应用于多注册中心。
 * Broadcast Cluster：广播调用所有提供者，逐个调用，在循环调用结束后，只要任意一台报错就报错。通常用于通知所有提供者更新缓存或日志等本地资源信息
 * Mergeable Cluster：该部分在分组聚合讲述。
 * MockClusterWrapper：该部分在本地伪装讲述。
 */
@SPI(FailoverCluster.NAME)
public interface Cluster {

    /** 多个合并为一个. **/
    @Adaptive
    <T> Invoker<T> join(Directory<T> directory) throws RpcException;

}