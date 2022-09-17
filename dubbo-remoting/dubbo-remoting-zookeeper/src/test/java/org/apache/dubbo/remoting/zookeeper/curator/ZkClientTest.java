/*
 * Copyright (c) 2017-2020 duxiaoman All Rights Reserved.
 * PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 * Author Email: wangzongyao@duxiaoman.com
 */

package org.apache.dubbo.remoting.zookeeper.curator;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.remoting.zookeeper.ChildListener;
import org.apache.dubbo.remoting.zookeeper.DataListener;
import org.apache.dubbo.remoting.zookeeper.EventType;
import org.apache.dubbo.remoting.zookeeper.ZookeeperClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * @author wangzongyao on 2022/8/10
 */
public class ZkClientTest {

    ZookeeperClient zkClient;

    @Before
    public void init(){
        Map<String, String> params =  new HashMap<>();
        params.put("config-file", "dubbo.properties");
        params.put("check", "true");
        params.put("highest-priority", "false");
        params.put("timeout", "10000");
        params.put("group", "dubbo");
        URL url = new URL("zookeeper", null, null, "60.205.226.17", 2181, "ConfigCenterConfig", params);

       zkClient  = new CuratorZookeeperClient(url);
    }

    @After
    public void after(){
        zkClient.close();
    }

    @Test
    public void create() {
        zkClient.create("/yaotest/aaa/bbb", false);
    }

    @Test
    public void createWithContent() {
        zkClient.create("/yaotest/aaa/bbb/ccc", "content-内容啊", false);
    }

    @Test
    public void delete() {
        zkClient.delete("/yaotest/aaa/bbb/ccc");
    }

    /**
     * 当指定的路径不存在时: 返回 null
     * 当指定的路径不存在子节点时: 返回 空白集合.
     * 存在子节点时, 只返回子节点, 不返回孙子节点.
     */
    @Test
    public void getChildren() {
        List<String> children = zkClient.getChildren("/yaotest");
        if(children == null){
            System.out.println("子节点集合 为 null");
            return ;
        }

        for (String child : children) {
            System.out.println(child);
        }
    }

    /**
     * 只监听你指定路径下的子节点的数量变化(不包括孙子节点, 也不包括内容变化),
     * 回调中的path是你监听的节点, children是该路径最新的子节点信息.
     * 该方法调用后, 会立即返回监听路径下的子节点(不返回孙子节点, 啊).
     * 注: 监听不是一次性的,
     */
    @Test
    public void addChildListener() {
        List<String> children = zkClient.addChildListener("/yaotest", new ChildListener() {
            @Override
            public void childChanged(String path, List<String> children) {
                System.out.println("path ::: " + path);

                System.out.println("children ::: " + children);
            }
        });

        System.out.println("第一次注册时获取的节点信息 ::: " + children);

        sleep();
    }

    /**
     * 监听 指定路径 及 下面所有节点, 无论是
     */
    @Test
    public void addDataListener() {
        zkClient.addDataListener("/yaotest", new DataListener() {
            @Override
            public void dataChanged(String path, Object value, EventType eventType) {
                System.out.println("path ::: " + path);
                System.out.println("value ::: " + value);
                System.out.println("eventType ::: " + eventType);
            }
        });

        sleep();
    }

    @Test
    public void testAddDataListener() {
    }

    @Test
    public void removeDataListener() {
    }

    @Test
    public void removeChildListener() {
    }

    @Test
    public void addStateListener() {
    }

    @Test
    public void removeStateListener() {
    }

    @Test
    public void isConnected() {
    }

    @Test
    public void close() {
    }

    @Test
    public void getUrl() {
    }

    @Test
    public void testCreate() {
    }

    @Test
    public void getContent() {
    }

    public void sleep(){
        try {
            TimeUnit.HOURS.sleep(1);
        }catch (Exception e){
            e.printStackTrace();
        }

    }

}