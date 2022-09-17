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
package org.apache.dubbo.rpc.proxy.jdk;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.proxy.AbstractProxyFactory;
import org.apache.dubbo.rpc.proxy.AbstractProxyInvoker;
import org.apache.dubbo.rpc.proxy.InvokerInvocationHandler;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * JdkRpcProxyFactory
 */
public class JdkProxyFactory extends AbstractProxyFactory {


    /** 消费端执行此方法. **/
    @Override
    public <T> T getProxy(Invoker<T> invoker, Class<?>[] interfaces) {
        return (T) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(), interfaces, new InvokerInvocationHandler(invoker));
    }

    /** 生产端执行此方法. **/
    @Override
    public <T> Invoker<T> getInvoker(T proxy, Class<T> type, URL url) {

        /**
         * proxy: 真实对象, 就是你写的那个接口实现类, 服务提供者
         * type: 接口对应的Class对象, 就是 proxy对象实现的那个接口
         * url: 注册中心的URL, 但是包括了 provider URL的信息(只不过信息被转为了字符串化, 然后编码了), 如: ↓
         *
         * registry://60.205.226.17:2181/org.apache.dubbo.registry.RegistryService?application=dubbo-provider-application&check=false&dubbo=2.0.2&export=dubbo%3A%2F%2F192.167.2.31%3A20882%2Fcom.yao.service.UserService%3Fanyhost%3Dfalse%26application%3Ddubbo-provider-application%26bind.ip%3D192.167.2.31%26bind.port%3D20882%26deprecated%3Dfalse%26dubbo%3D2.0.2%26dynamic%3Dtrue%26generic%3Dfalse%26interface%3Dcom.yao.service.UserService%26methods%3Dall%2CgetByName%2Cclear%2CdeleteByName%26pid%3D88684%26proxy%3Djdk%26qos.port%3D22222%26release%3D%26side%3Dprovider%26timestamp%3D1656665370221&pid=88684&proxy=jdk&qos.port=22222&registry=zookeeper&subscribe=false&timestamp=1656665369507
         */
        return new AbstractProxyInvoker<T>(proxy, type, url) {

            /**
             * 调用 真是对象的目标方法, 在 Dubbo的 层层包装中, 属于 最里面的 Invoker.
             * @param proxy 代理的真实对象, 用于Method反射时的参数
             * @param methodName 方法名, 用于查找Method, 进行反射
             * @param parameterTypes 参数类型, 用于查找Method, 进行反射
             * @param arguments 方法调用时真实的参数, 作为 Method反射的参数
             * @return 目标方法的返回值, 最原始的返回结果.
             */
            @Override
            protected Object doInvoke(T proxy, String methodName,
                                      Class<?>[] parameterTypes,
                                      Object[] arguments) throws Throwable {
                Method method = proxy.getClass().getMethod(methodName, parameterTypes);
                return method.invoke(proxy, arguments);
            }
        };
    }

}
