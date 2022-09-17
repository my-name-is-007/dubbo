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
package org.apache.dubbo.registry;

import org.apache.dubbo.common.URL;

import java.util.List;

/**
 * RegistryService. (SPI, Prototype, ThreadSafe)
 *
 * @see org.apache.dubbo.registry.Registry
 * @see org.apache.dubbo.registry.RegistryFactory#getRegistry(URL)
 */
public interface RegistryService {

    /**
     * 注册.
     * 允许URI相同但参数不同的URL并存，不能覆盖，也就是说url值必须唯一的，不能有一模一样
     */
    void register(URL url);

    /**
     * 取消注册.
     * 按全URL匹配取消注册.
     */
    void unregister(URL url);

    /**
     * 订阅.
     * 不是根据全URL匹配订阅的，而是根据条件去订阅，也就是说可以订阅多个服务，
     * listener 是用来监听处理注册数据变更的事件
     */
    void subscribe(URL url, NotifyListener listener);

    /**
     * 取消订阅.
     * 按照全URL匹配去取消订阅的，
     */
    void unsubscribe(URL url, NotifyListener listener);

    /**
     * 查询符合条件的已注册数据，与订阅的推模式相对应，这里为拉模式，只返回一次结果.
     * 通过url进行条件查询所匹配的所有URL集合，
     */
    List<URL> lookup(URL url);

}