/*
 * Copyright (c) 2017-2020 duxiaoman All Rights Reserved.
 * PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 * Author Email: wangzongyao@duxiaoman.com
 */

package com.yao.service;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * @author wangzongyao on 2022/2/14
 */
public class UserServiceImpl implements UserService {

    private static final Map<String, UserEntity> store = new HashMap<String, UserEntity>();

    static {
        store.put("王宗尧-1", new UserEntity("王宗尧-1", 20, "大漠，扬起你的沙砾，遮蔽太阳的光芒吧"));
        store.put("王宗尧-2", new UserEntity("王宗尧-2", 20, "王权没有永恒，我的孩子"));
        store.put("王宗尧-3", new UserEntity("王宗尧-3", 20, "种族不代表荣耀，我见过最高尚的兽人，也见过最卑劣的人类。"));
        store.put("王宗尧-4", new UserEntity("王宗尧-4", 20, "如果后会无期，那祝你死得其所。"));
        store.put("王宗尧-5", new UserEntity("王宗尧-5", 20, "在黑暗中度过一万年的漫长岁月后，你的声音还是如同皎洁的月光一般照进我的心中。"));
        store.put("王宗尧-6", new UserEntity("王宗尧-6", 20, "英雄，那是你的过去。"));
    }

    @Override
    public UserEntity getByName(String s) {
        //triggerException();
        System.out.println("<=============local-2 ::: local-2-group ::: local-2-version 被调用=============>");
        UserEntity e = store.get(s);
        return e;
    }

    @Override
    public List<UserEntity> all() {
        List<UserEntity> res = new LinkedList<UserEntity>();
        for (UserEntity e : store.values()) {
            res.add(e);
        }
        return res;
    }

    @Override
    public void deleteByName(String s) {
        store.remove(s);
    }

    @Override
    public void clear() {
        store.clear();
    }

    private void triggerException() {
        int a = 1/0;
    }

    private CallbackService callbackService;


    @Override
    public String callbackTest(CallbackService callbackService) {
        this.callbackService = callbackService;
        return "服务端返回的结果啊啊啊";
    }

    @Override
    public CompletableFuture<String> sayHello(String name) {
        return CompletableFuture.completedFuture(name + " ::: 哈哈哈");
    }
}